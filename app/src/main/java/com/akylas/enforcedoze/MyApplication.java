package com.akylas.enforcedoze;

import android.content.Context;
import android.preference.PreferenceManager;

public class MyApplication extends android.app.Application {
    private static Context context;
    private ShizukuHandler.OnAvailibilityChange shizukuAvailabilityListener;

    @Override
    public void onCreate() {
        super.onCreate();
        MyApplication.context = getApplicationContext();

        // Every process entry point goes through Application.onCreate, so initialising here means
        // a service recreated at 03:00 starts logging without anything else having to remember to.
        DiagnosticLogger.init(MyApplication.context);

        /*
         * ShizukuProvider can start this process solely to deliver a newly available binder. Keep a
         * process-level listener alive even when ForceDozeService is stopped, so durable restore
         * debt from a disabled boot recovery gets another opportunity when Shizuku later starts.
         */
        ShizukuHandler shizukuHandler = ShizukuHandler.getInstance(MyApplication.context);
        shizukuAvailabilityListener = available -> {
            if (Boolean.TRUE.equals(available)) {
                maybeRecoverDisabledStateAfterShizukuStart();
            }
        };
        shizukuHandler.addOnAvailabilityChangeListener(shizukuAvailabilityListener);

        // addBinderReceivedListenerSticky() may have delivered the binder while ShizukuHandler was
        // being constructed, before this Application listener was registered. Cover that ordering.
        if (shizukuHandler.isShizukuAvailable()) {
            maybeRecoverDisabledStateAfterShizukuStart();
        }
    }

    private void maybeRecoverDisabledStateAfterShizukuStart() {
        if (!Utils.isShizukuMode(MyApplication.context)) {
            return;
        }

        if (PreferenceManager.getDefaultSharedPreferences(MyApplication.context)
                .getBoolean("serviceEnabled", false)) {
            // A live/enabled service owns its normal Shizuku reconnect recovery.
            return;
        }

        DozeStateStore store = DozeStateStore.getInstance(MyApplication.context);
        boolean packageDebt = store.hasAppliedSuspendedPackages();
        boolean stateDebt = store.hasPendingRestore();
        if (!packageDebt && !stateDebt) {
            return;
        }

        DiagnosticLogger.i("RECOVERY", "app_shizuku_restore_trigger"
                + " pendingPackages=" + store.getAppliedSuspendedPackages().size()
                + " pendingStates=" + store.getAppliedKeys());

        boolean started = Utils.startForceDozeServiceAction(
                MyApplication.context, ForceDozeService.ACTION_RESTORE_STATE);

        DiagnosticLogger.i("RECOVERY", "app_shizuku_restore_start_result started=" + started);
    }

    public static Context getAppContext() {
        return MyApplication.context;
    }
}
