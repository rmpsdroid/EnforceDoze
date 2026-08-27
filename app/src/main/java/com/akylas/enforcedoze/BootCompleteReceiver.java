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
        DiagnosticLogger.init(context);
        DiagnosticLogger.i("APP", "BOOT_COMPLETED serviceEnabled=" + isServiceEnabled);

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

        // The same applies to an interrupted force-idle attempt, and more strongly: the reboot took
        // DeviceIdleController down with everything else, so the mForceIdle the marker stands for no
        // longer exists anywhere. Clearing it synchronously is therefore the whole of the recovery -
        // an unforce would be aimed at nothing, and could only confuse a natural post-boot idle.
        // No ENTER and no EXIT: PREPARING never became a session. Package and device-state recovery
        // below is decided independently and is untouched by this.
        if (!store.abortForceIdleAttempt()) {
            // Self-correcting rather than fatal: the marker survives, the service sees it on start
            // and issues an unforce that a freshly booted device treats as a no-op, then clears it.
            DiagnosticLogger.e("RECOVERY", "boot_entry_pending_clear_failed");
        }

        // An owned-session reforce marker means the same thing after a reboot as a PREPARING one:
        // nothing. DeviceIdleController went down with the rest of the system, so the mForceIdle the
        // marker stands for no longer exists and there is nothing to unforce. Clearing it is the
        // whole of the recovery, and no ENTER or EXIT is written for a transaction that never was a
        // session boundary.
        // Only when a marker actually exists. finishOwnedReforceAttempt() restores TRUE if its
        // clearing commit fails, because it assumes it is protecting a real debt; calling it with
        // nothing outstanding could therefore manufacture one out of a storage failure and block
        // Doze entry for a transaction that never happened.
        if (store.isOwnedReforcePending() && !store.finishOwnedReforceAttempt()) {
            // Deliberately not a reason to start the service on its own. A stuck marker only blocks
            // fresh Doze entry, and with EnforceDoze disabled there is no entry to block; the next
            // ordinary service start resolves it, and on a freshly booted device the corrective
            // unforce it issues is a no-op.
            DiagnosticLogger.e("RECOVERY", "boot_owned_reforce_clear_failed");
        }

        // Two independent kinds of pending recovery, deliberately tested separately rather than
        // folded into hasPendingRestore(): that method means "device-state toggles" everywhere
        // else, and widening it here would change behaviour at call sites this commit is not
        // meant to touch.
        //
        // The package-only case is real and was previously missed: with the Doze App Blocklist in
        // use but no radio/sensor enhancement enabled, getAppliedKeys() is empty while packages
        // are still suspended. pm suspend is persistent PackageManager state that survives the
        // reboot, so those apps would have come back up greyed out with nothing left to fix them.
        boolean hasDeviceStateRestore = store.hasPendingRestore();
        boolean hasPackageRestore = store.hasAppliedSuspendedPackages();

        if (!hasDeviceStateRestore && !hasPackageRestore) {
            return false;
        }

        log("BOOT_RECOVERY_PENDING deviceStates=" + store.getAppliedKeys()
                + " suspendedPackages=" + store.getAppliedSuspendedPackages().size());
        DiagnosticLogger.i("RECOVERY", "BOOT_RECOVERY_PENDING deviceStates=" + store.getAppliedKeys()
                + " suspendedPackages=" + store.getAppliedSuspendedPackages().size());
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
