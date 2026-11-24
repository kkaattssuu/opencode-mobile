package app.opencodemobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** App-scoped checks only: no input injection, package installs, prompts, or system settings. */
public final class PhoneCheck extends Instrumentation {
    private Activity activity;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            activity = launch();
            waitForChat();
            JSONObject before = info();
            runOnMainSync(() -> activity.onBackPressed());
            runOnMainSync(() -> activity.findViewById(R.id.bGrant).performClick());
            waitUntil(() -> ((TextView) activity.findViewById(R.id.log)).getText().toString().contains("is ready."),
                    "Runtime probe did not finish", 40);
            runOnMainSync(() -> activity.findViewById(R.id.bStart).performClick());
            waitForChat();
            runOnMainSync(() -> activity.finish());
            activity = launch();
            waitForChat();
            JSONObject after = info();
            if (before.getInt("pid") != after.getInt("pid")) throw new AssertionError("Reopening duplicated/restarted the server");
            report.putString("stream", "\nPASS: runtime probe, server startup, authenticated WebView, Matrix theme, reopen without duplicate server.\nOpenCode " + after.getString("version") + "\n");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable e) {
            AtomicReference<String> log = new AtomicReference<>("");
            if (activity != null) runOnMainSync(() -> log.set(((TextView) activity.findViewById(R.id.log)).getText().toString()));
            report.putString("stream", "\nFAIL: " + e + "\nApp status: " + log.get() + "\n");
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private Activity launch() {
        Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startActivitySync(intent);
    }

    private JSONObject info() throws Exception {
        Context ctx = getTargetContext();
        String password = ctx.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("serverPassword", null);
        if (password == null) throw new AssertionError("Missing app server credential");
        String auth = "Basic " + Base64.encodeToString(("opencode:" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return new JSONObject(ServerConnection.request(ServerConnection.BASE, "api/info", "GET", auth));
    }

    private void waitForChat() throws Exception {
        waitUntil(() -> activity.findViewById(R.id.web).getVisibility() == View.VISIBLE,
                "Chat did not open", 100);
        AtomicReference<WebView> view = new AtomicReference<>();
        runOnMainSync(() -> {
            view.set(activity.findViewById(R.id.web));
            view.get().evaluateJavascript("fetch('/api/info').then(r=>document.documentElement.dataset.phoneCheckApi=String(r.status));true", null);
        });
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        while (System.nanoTime() < end) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean good = new AtomicBoolean();
            runOnMainSync(() -> view.get().evaluateJavascript(
                    "document.documentElement.dataset.phoneCheckApi==='200' && !!document.getElementById('android-matrix-theme') && document.body.innerText.trim().length>0 && !document.body.innerText.includes('Connect to a server')",
                    result -> { good.set("true".equals(result)); done.countDown(); }));
            if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView stopped responding");
            if (good.get()) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Chat did not authenticate/render");
    }

    private interface UiCheck { boolean ok(); }
    private void waitUntil(UiCheck check, String message, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            AtomicBoolean ready = new AtomicBoolean();
            runOnMainSync(() -> ready.set(check.ok()));
            if (ready.get()) return;
            Thread.sleep(250);
        }
        throw new AssertionError(message);
    }
}
