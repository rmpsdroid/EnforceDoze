package com.akylas.enforcedoze;


import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.preference.PreferenceManager;

public class BootCompleteReceiver extends BroadcastReceiver {
    public static String TAG = "EnforceDoze";
    private static void log(String message) {
        logToLogcat(TAG, message);
    }
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean isServiceEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("serviceEnabled", false);
        log("Received BOOT_COMPLETED intent, isServiceEnabled=" + Boolean.toString(isServiceEnabled));

        // Do this before anything else: if the phone was rebooted (or crashed) mid-Doze, toggles
        // like airplane mode, location mode and battery saver are persisted by the system and come
        // back up still applied, with nobody left holding the pre-Doze state except our own store.
        boolean restoring = recoverStateAfterBoot(context);

        if (isServiceEnabled) {
            Utils.startForceDozeService(context);
        } else if (restoring) {
            // The service is up purely to undo the leftover state and stops itself afterwards;
            // stopping it here would kill the reversion before it had a chance to run.
            log("Leaving the service running until the post-boot reversion finishes");
            Utils.showDisabledNotification(context);
        } else {
            // Show disabled notification if EnforceDoze is disabled on startup
            Utils.stopForceDozeService(context);
        }
        Utils.scheduleNextCustomDozePeriodBoundary(context);
    }

    /** @return true when a reversion was handed to the service. */
    private boolean recoverStateAfterBoot(Context context) {
        DozeStateStore store = DozeStateStore.getInstance(context);
        // A reboot always ends the Doze session, whatever the flag said when we went down.
        store.setInDoze(false);

        if (!store.hasPendingRestore()) {
            return false;
        }

        log("Device booted with " + store.getAppliedKeys() + " still applied, restoring");
        try {
            Intent restore = new Intent(context, ForceDozeService.class);
            restore.setAction(ForceDozeService.ACTION_RESTORE_STATE);
            // BOOT_COMPLETED is an exemption from the background foreground-service start
            // restrictions, so this is allowed even though we are in the background.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(restore);
            } else {
                context.startService(restore);
            }
            return true;
        } catch (Exception e) {
            // Leave the marks in place; the next time the service runs it will pick them up.
            log("Could not start the service to restore state after boot: " + e.getMessage());
            return false;
        }
    }
}
