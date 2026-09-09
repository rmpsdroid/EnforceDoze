package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;

public class DisableForceDozeService extends BroadcastReceiver {
    public static String TAG = "EnforceDoze";

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AutomationSecurity.isAuthorizedAutomationIntent(
                context,
                intent,
                Utils.ACTION_DISABLE_FORCEDOZE
        )) {
            log("Rejected unauthorized " + Utils.ACTION_DISABLE_FORCEDOZE + " broadcast");
            return;
        }

        log(Utils.ACTION_DISABLE_FORCEDOZE + " authenticated broadcast received");
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean("serviceEnabled", false)
                .apply();
        Utils.stopForceDozeService(context);
    }
}
