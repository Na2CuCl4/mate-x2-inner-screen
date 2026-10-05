package net.weng.innerlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Preferences are device-protected so automatic mode is also available before first unlock. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!FoldController.isAutoEnabled(context)) return;
        FoldController.appendLog(context, "Boot receiver: " + action);
        AutoLockService.start(context);
    }
}
