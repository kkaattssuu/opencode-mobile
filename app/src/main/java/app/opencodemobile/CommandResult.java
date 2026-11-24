package app.opencodemobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Receives the result bundle Termux sends back for a RUN_COMMAND. */
public class CommandResult extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle result = intent.getBundleExtra("result");
        if (intent.getData() == null) return;
        try {
            Termux.deliver(Integer.parseInt(intent.getData().getSchemeSpecificPart()), result);
        } catch (NumberFormatException ignored) {
            // Ignore malformed callbacks.
        }
    }
}
