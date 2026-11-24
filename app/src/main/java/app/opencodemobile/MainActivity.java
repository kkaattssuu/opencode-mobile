package app.opencodemobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.graphics.drawable.Animatable;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;
import android.util.Base64;
import android.webkit.CookieManager;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String SERVER = ServerConnection.BASE;
    private static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_PERMISSION = 1;

    private static final String PASTE =
            "mkdir -p ~/.termux\n"
          + "printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties\n"
          + "termux-reload-settings";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private WebView web;
    private View setup;
    private Button bTermux;
    private Button bGrant;
    private Button bInstall;
    private Button bStart;
    private Button bCopy;
    private TextView logView;
    private TextView pasteView;

    private boolean busy;
    private long token;
    private int commandId;
    private int serverCommandId;
    private String password;
    private String authorization;
    private String matrixCss;
    private long pageToken;
    private boolean loadingPage;
    private boolean clearPairingHistory;
    private boolean resumed;
    private boolean startOnResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();

        password = getPreferences(MODE_PRIVATE).getString("serverPassword", null);
        if (password == null) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            password = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            getPreferences(MODE_PRIVATE).edit().putString("serverPassword", password).apply();
        }
        authorization = "Basic " + Base64.encodeToString(
                ("opencode:" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        web = findViewById(R.id.web);
        setup = findViewById(R.id.setup);
        bTermux = findViewById(R.id.bTermux);
        bGrant = findViewById(R.id.bGrant);
        bInstall = findViewById(R.id.bInstall);
        bStart = findViewById(R.id.bStart);
        bCopy = findViewById(R.id.bCopy);
        logView = findViewById(R.id.log);
        pasteView = findViewById(R.id.paste);

        configureWebView();
        matrixCss = asset("matrix.css");

        bTermux.setOnClickListener(v -> openTermuxPage());
        bGrant.setOnClickListener(v -> askForPermission());
        bInstall.setOnClickListener(v -> install());
        bStart.setOnClickListener(v -> start());
        bCopy.setOnClickListener(v -> copyPaste());
        findViewById(R.id.bOpenTermux).setOnClickListener(v -> openTermux());
        findViewById(R.id.bTurnOff).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.turn_off_title)
                        .setMessage(R.string.turn_off_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.turn_off, (dialog, which) -> turnOff())
                        .show());
        if (getPreferences(MODE_PRIVATE).getBoolean("turnedOff", false)) appendLog(getString(R.string.turned_off));
        if (!supportedPhone()) appendLog(getString(R.string.unsupported_phone));
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setBackgroundColor(getColor(R.color.matrix_black));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isLocal(Uri.parse(url)) || matrixCss == null) return;
                long page = pageToken;
                view.evaluateJavascript("(() => {"
                        + "document.documentElement.dataset.colorScheme='dark';"
                        + "let s=document.getElementById('android-matrix-theme');"
                        + "if(!s){s=document.createElement('style');s.id='android-matrix-theme';document.head.appendChild(s);}"
                        + "s.textContent=" + JSONObject.quote(matrixCss) + ";"
                        + "return true;})()", result -> {
                            if (!isDestroyed() && page == pageToken && loadingPage && "true".equals(result)) {
                                loadingPage = false;
                                pageToken++;
                                finishRun();
                                setup.setVisibility(View.GONE);
                                view.setVisibility(View.VISIBLE);
                                if (clearPairingHistory) {
                                    view.clearHistory();
                                    clearPairingHistory = false;
                                }
                            }
                        });
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocal(uri)) return false;
                if (request.isForMainFrame() && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                    openBrowser(uri);
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageToken++;
                    loadingPage = false;
                    finishRun();
                    showSetup();
                    appendLog("OpenCode disconnected: " + error.getDescription() + ". Tap Launch OpenCode to reconnect.");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                pageToken++;
                loadingPage = false;
                finishRun();
                showSetup();
                appendLog("The chat page returned HTTP " + response.getStatusCode() + ". Tap Launch OpenCode to sign in again.");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        animateFace(setup.getVisibility() == View.VISIBLE);
        if (startOnResume && !getPreferences(MODE_PRIVATE).getBoolean("turnedOff", false)
                && !busy && supportedPhone() && Termux.installed(this)
                && checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startOnResume = false;
            start();
            return;
        }
        if (web.getVisibility() == View.VISIBLE && !busy
                && !getPreferences(MODE_PRIVATE).getBoolean("turnedOff", false)) {
            io.execute(() -> {
                boolean healthy;
                try { healthy = serverUp(); } catch (Exception e) { healthy = false; }
                if (!healthy) ui.post(() -> {
                    if (isDestroyed() || isFinishing() || busy
                            || getPreferences(MODE_PRIVATE).getBoolean("turnedOff", false)) return;
                    showSetup();
                    start();
                });
            });
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        animateFace(false);
        super.onPause();
    }

    private void animateFace(boolean running) {
        Animatable face = (Animatable) ((ImageView) findViewById(R.id.smiley)).getDrawable();
        if (running && ValueAnimator.areAnimatorsEnabled()) face.start();
        else face.stop();
    }

    @Override
    public void onBackPressed() {
        if (loadingPage) {
            pageToken++;
            loadingPage = false;
            web.stopLoading();
            finishRun();
            showSetup();
            return;
        }
        if (setup.getVisibility() == View.GONE) {
            if (web.canGoBack()) web.goBack();
            else showSetup();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        token++;
        ui.removeCallbacksAndMessages(null);
        Termux.dropPending(commandId);
        Termux.dropPending(serverCommandId);
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    private void refresh() {
        boolean termux = Termux.installed(this);
        boolean granted = checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED;

        bTermux.setVisibility(termux ? View.GONE : View.VISIBLE);
        bGrant.setEnabled(termux && !busy);
        bGrant.setText(granted ? R.string.check_connection : R.string.allow_commands);
        boolean installed = getPreferences(MODE_PRIVATE).getBoolean("installed", false);
        bInstall.setEnabled(supportedPhone() && termux && granted && !busy && !installed);
        bStart.setEnabled(supportedPhone() && termux && granted && !busy);
        findViewById(R.id.bTurnOff).setEnabled(supportedPhone() && termux && granted && !busy);
        bInstall.setText(installed ? R.string.opencode_ready : R.string.install_opencode);
        bStart.setText(busy ? R.string.working : R.string.start_opencode);
        findViewById(R.id.progress).setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private boolean supportedPhone() {
        return java.util.Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
    }

    private void showChat(String url) {
        animateFace(false);
        busy = true;
        loadingPage = true;
        clearPairingHistory = true;
        refresh();
        appendLog("Opening your workspace…");
        web.setVisibility(View.INVISIBLE);
        long page = ++pageToken;
        web.loadUrl(url);
        ui.postDelayed(() -> {
            if (isDestroyed() || page != pageToken || !loadingPage) return;
            loadingPage = false;
            web.stopLoading();
            finishRun();
            showSetup();
            appendLog("The chat page took too long to load. Check the connection and tap Launch OpenCode to retry.");
        }, 45_000);
    }

    private void showSetup() {
        web.setVisibility(View.GONE);
        setup.setVisibility(View.VISIBLE);
        refresh();
        animateFace(true);
    }

    private void askForPermission() {
        if (checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            testTermux();
            return;
        }
        requestPermissions(new String[]{RUN_PERMISSION}, REQUEST_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            testTermux();
        } else {
            appendLog("Permission denied. Allow it in Android settings, then tap Allow Termux commands again.");
            refresh();
        }
    }

    private void testTermux() {
        String probe = asset("probe.sh");
        if (probe == null) return;
        runCommand(probe, true, 30_000L, "Termux did not answer. Open Termux once, then check the connection again.", result -> {
            if (result.exitCode == 0) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("installed", true).apply();
                appendLog(result.stdout);
                hidePaste();
            } else if (result.exitCode >= 20 && result.exitCode <= 22) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("installed", false).apply();
                hidePaste();
                appendLog("Termux is connected.\n" + result.stderr + result.stdout);
            } else {
                appendLog("Termux refused the command.\n" + result.stderr + result.error);
                showPaste();
            }
        });
    }

    private void install() {
        appendLog("Installing in Termux. Open Termux to watch progress and answer package prompts. Return here when it finishes.");
        String script = asset("install.sh");
        String probe = asset("probe.sh");
        if (script == null || probe == null) return;
        runCommand(script + "\n" + probe, false, 15 * 60_000L, "Setup is taking longer than expected. Check Termux before retrying; the install may still be running.", result -> {
            if (result.exitCode == 0) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("installed", true).apply();
                appendLog("OpenCode installed. Tap Launch OpenCode.");
                startOnResume = true;
                if (resumed) {
                    startOnResume = false;
                    start();
                }
            } else {
                appendLog("Install failed (exit " + result.exitCode + ").");
                appendLog(result.stdout + result.stderr + result.error);
            }
        });
    }

    private void start() {
        if (busy) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("turnedOff", false).apply();
        appendLog("Starting OpenCode on 127.0.0.1:4096…");
        busy = true;
        refresh();
        long run = ++token;
        io.execute(() -> {
            boolean up;
            try {
                up = serverUp();
            } catch (ServerConnection.HttpError e) {
                ui.post(() -> {
                    if (isDestroyed() || run != token) return;
                    finishRun();
                    appendLog("Port 4096 is running a server with different credentials. I have not stopped or changed it. Close that OpenCode server in Termux, then tap Launch OpenCode.");
                });
                return;
            }
            ui.post(() -> {
                if (isDestroyed() || run != token) return;
                if (up) {
                    connect(run);
                    return;
                }
                String probe = asset("probe.sh");
                if (probe == null) {
                    finishRun();
                    return;
                }
                runCommand(probe, true, 30_000L, "Termux did not answer. Open Termux once and check the connection.", result -> {
                    if (result.exitCode == 0) {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("installed", true).apply();
                        hidePaste();
                        launchServer();
                    } else {
                        if (result.exitCode >= 20 && result.exitCode <= 22) {
                            getPreferences(MODE_PRIVATE).edit().putBoolean("installed", false).apply();
                            hidePaste();
                        } else {
                            showPaste();
                        }
                        appendLog("Could not prepare OpenCode.\n" + result.stdout + result.stderr + result.error);
                    }
                });
            });
        });
    }

    private void turnOff() {
        if (busy) return;
        String stopScript = asset("stop.sh");
        if (stopScript == null) return;
        startOnResume = false;
        getPreferences(MODE_PRIVATE).edit().putBoolean("turnedOff", true).apply();
        busy = true;
        loadingPage = false;
        pageToken++;
        web.stopLoading();
        showSetup();
        appendLog("Stopping OpenCode tasks and terminals…");
        long run = ++token;
        io.execute(() -> {
            try {
                JSONObject info = new JSONObject(ServerConnection.request(SERVER, "api/info", "GET", authorization));
                int pid = info.getInt("pid");
                if (pid <= 1) throw new IllegalStateException("Invalid server process");
                JSONObject active = new JSONObject(ServerConnection.request(
                        SERVER, "api/session/active", "GET", authorization)).getJSONObject("data");
                java.util.Iterator<String> sessions = active.keys();
                while (sessions.hasNext()) {
                    String id = sessions.next();
                    ServerConnection.request(SERVER, "api/session/" + Uri.encode(id) + "/interrupt?resume=false",
                            "POST", authorization);
                }
                ServerConnection.request(SERVER, "api/experimental/persistent-pty/shutdown", "POST", authorization);
                ui.post(() -> {
                    if (isDestroyed() || run != token) return;
                    runCommand("export OPENCODE_PASSWORD='" + password + "'\nset -- " + pid + "\n" + stopScript,
                            true, 20_000L, "Shutdown could not be confirmed. Check Termux before retrying.", result -> {
                                if (result.exitCode == 0) {
                                    Toast.makeText(this, "OpenCode is off.", Toast.LENGTH_SHORT).show();
                                    finish();
                                } else {
                                    appendLog("Shutdown could not be confirmed.\n" + result.stdout + result.stderr + result.error);
                                }
                            });
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (isDestroyed() || run != token) return;
                    finishRun();
                    appendLog("Could not confirm shutdown: " + e.getMessage()
                            + ". Automatic startup is off; no unrelated process was stopped.");
                });
            }
        });
    }

    private void launchServer() {
        String script = asset("start.sh");
        if (script == null) return;
        busy = true;
        refresh();
        long run = ++token;
        Termux.dropPending(serverCommandId);
        serverCommandId = Termux.run(this,
                        "export OPENCODE_PASSWORD='" + password + "'\nexport OPENCODE_SERVER_PASSWORD=\"$OPENCODE_PASSWORD\"\n" + script,
                        true, (code, out, err, error) -> ui.post(() -> {
                            if (isDestroyed()) return;
                            if (run == token && code != 75) {
                                token++;
                                finishRun();
                                appendLog("OpenCode stopped (exit " + code + ").\n" + out + err + error);
                            }
                        }));
        poll(run, 0);
    }

    private void poll(long run, int attempt) {
        if (run != token || isDestroyed()) return;
        if (attempt > 60) {
            finishRun();
            appendLog("OpenCode did not become ready. Check Termux; port 4096 may be occupied by another server.");
            return;
        }
        io.execute(() -> {
            boolean up;
            try { up = serverUp(); } catch (Exception e) {
                ui.post(() -> {
                    if (isDestroyed() || run != token) return;
                    finishRun();
                    appendLog("The server rejected sign-in. Another OpenCode server may be using port 4096; nothing was stopped.");
                });
                return;
            }
            ui.post(() -> {
                if (run != token || isDestroyed()) return;
                if (up) {
                    connect(run);
                } else {
                    ui.postDelayed(() -> poll(run, attempt + 1), 1000);
                }
            });
        });
    }

    private boolean serverUp() throws ServerConnection.HttpError {
        try {
            JSONObject info = new JSONObject(ServerConnection.request(SERVER, "api/info", "GET", authorization));
            return info.getString("version").matches("(?:opencode )?v?2\\..+");
        } catch (ServerConnection.HttpError e) {
            if (e.status == 401 || e.status == 403) throw e;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void connect(long run) {
        io.execute(() -> {
            try {
                JSONObject pairing = new JSONObject(ServerConnection.request(SERVER, "api/pair", "POST", authorization));
                String code = pairing.getString("code");
                if (!code.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalStateException("Invalid pairing code");
                ui.post(() -> {
                    if (run != token || isDestroyed()) return;
                    hidePaste();
                    getPreferences(MODE_PRIVATE).edit().putBoolean("installed", true).apply();
                    showChat(SERVER + "auth/connect/" + code);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (run != token || isDestroyed()) return;
                    finishRun();
                    appendLog("Could not sign in: " + e.getMessage());
                });
            }
        });
    }

    private void finishRun() {
        busy = false;
        refresh();
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            appendLog("Could not read app setup: " + e.getMessage());
            return null;
        }
    }

    private static boolean isLocal(Uri uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() == 4096 && uri.getUserInfo() == null;
    }

    private static final class Result {
        final int exitCode;
        final String stdout;
        final String stderr;
        final String error;

        Result(int exitCode, String stdout, String stderr, String error) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.error = error == null ? "" : error;
        }
    }

    private interface RunDone { void done(Result result); }

    private void runCommand(String script, boolean background, long timeoutMs, String timeoutMessage, RunDone done) {
        busy = true;
        refresh();
        final long run = ++token;
        commandId = Termux.run(this, script, background, (exitCode, stdout, stderr, error) -> ui.post(() -> {
            if (run != token || isDestroyed()) return;
            token++;
            busy = false;
            done.done(new Result(exitCode, stdout, stderr, error));
            refresh();
        }));
        ui.postDelayed(() -> {
            if (run != token || !busy || isDestroyed()) return;
            token++;
            busy = false;
            Termux.dropPending(commandId);
            appendLog(timeoutMessage);
            if (background) showPaste();
            refresh();
        }, timeoutMs);
    }

    private void openTermuxPage() {
        openBrowser(Uri.parse("https://f-droid.org/packages/com.termux/"));
    }

    private void openBrowser(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            showSetup();
            appendLog("Install a browser to open this link.");
        }
    }

    private void openTermux() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.termux");
        if (launch == null) openTermuxPage();
        else startActivity(launch);
    }

    private void showPaste() {
        pasteView.setText(PASTE);
        findViewById(R.id.pasteBox).setVisibility(View.VISIBLE);
    }

    private void hidePaste() {
        findViewById(R.id.pasteBox).setVisibility(View.GONE);
    }

    private void copyPaste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Termux setup", PASTE));
        appendLog("Copied. Paste it in Termux, press enter, then tap Check Termux connection.");
        openTermux();
    }

    private void appendLog(String text) {
        String old = logView.getText().toString();
        if (old.equals(getString(R.string.matrix_log_idle))) old = "";
        String line = (old.isEmpty() ? "" : old + "\n\n") + text;
        if (line.length() > 8000) line = line.substring(line.length() - 8000);
        logView.setText(line);
        logView.post(() -> {
            if (!isDestroyed() && setup.getVisibility() == View.VISIBLE) {
                int bottom = logView.getHeight();
                logView.requestRectangleOnScreen(new android.graphics.Rect(
                        0, Math.max(0, bottom - 80), logView.getWidth(), bottom), false);
            }
        });
    }
}
