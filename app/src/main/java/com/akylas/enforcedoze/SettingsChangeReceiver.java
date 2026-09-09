package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SettingsChangeReceiver extends BroadcastReceiver {

    public static String TAG = "EnforceDoze";

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AutomationSecurity.isAuthorizedAutomationIntent(
                context,
                intent,
                Utils.ACTION_CHANGE_SETTING
        )) {
            log("Rejected unauthorized " + Utils.ACTION_CHANGE_SETTING + " broadcast");
            return;
        }

        final String settingName = intent.getStringExtra("settingName");
        final String settingValue = intent.getStringExtra("settingValue");

        if (!AutomationSecurity.applyAutomationSetting(
                context,
                settingName,
                settingValue
        )) {
            log("Rejected unsupported or invalid automation setting");
            return;
        }

        log(Utils.ACTION_CHANGE_SETTING + " authenticated setting update accepted");
        Utils.notifyServiceSettingsChanged(context);
    }
}
