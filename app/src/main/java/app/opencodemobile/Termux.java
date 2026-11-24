package app.opencodemobile;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.util.SparseArray;

/** Runs shell commands inside Termux through its RUN_COMMAND intent. */
final class Termux {
    interface Callback { void result(int exitCode, String stdout, String stderr, String error); }

    private static final String PKG = "com.termux";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";

    private static int nextCode = new java.security.SecureRandom().nextInt(1_000_000_000) + 1;
    private static final SparseArray<Callback> pending = new SparseArray<>();

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static int run(Context ctx, String script, boolean background, Callback cb) {
        int code = nextCode++;
        pending.put(code, cb);

        Intent i = new Intent("com.termux.RUN_COMMAND")
                .setClassName(PKG, "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        // Don't source the user's shell profile for app-managed commands.
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", script});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", background ? "3" : "0");
        i.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "OpenCode");

        if (cb != null) {
            Intent result = new Intent("app.opencodemobile.RESULT")
                    .setClass(ctx, CommandResult.class)
                    .setData(Uri.parse("opencode-command:" + code));
            int flags = PendingIntent.FLAG_ONE_SHOT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",
                    PendingIntent.getBroadcast(ctx, code, result, flags));
        }

        try {
            ctx.startForegroundService(i);
        } catch (Exception e) {
            if (cb != null) {
                pending.remove(code);
                cb.result(-1, "", "", e.getMessage());
            }
        }
        return code;
    }

    static void deliver(int code, Bundle b) {
        Callback cb = pending.get(code);
        pending.remove(code);
        if (cb == null) return;
        if (b == null) {
            cb.result(-1, "", "", "Termux returned no result");
            return;
        }
        cb.result(b.getInt("exitCode", -1), text(b, "stdout"), text(b, "stderr"), text(b, "errmsg"));
    }

    static void dropPending(int code) {
        pending.remove(code);
    }

    private static String text(Bundle b, String key) {
        String s = b.getString(key);
        return s == null ? "" : s;
    }

    private Termux() {}
}
