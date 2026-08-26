package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SettingsChangeReceiver extends BroadcastReceiver {

    public static String TAG = "EnforceDoze";private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        log(Utils.ACTION_CHANGE_SETTING + " broadcast intent received");
        final String settingName = intent.getStringExtra("settingName");
        final String settingValue = intent.getStringExtra("settingValue");

        if (settingName != null && settingValue != null) {
            if (Utils.doesSettingExist(settingName)) {
                if (Utils.isSettingBool(settingName)) {
                    Utils.updateSettingBool(context, settingName, Boolean.valueOf(settingValue));
                } else {
                    try {
                        Utils.updateSettingInt(context, settingName, Integer.valueOf(settingValue));
                    } catch (NumberFormatException e) {
                        log("settingValue '" + settingValue + "' is not a number, ignoring");
                        return;
                    }
                }
                Utils.notifyServiceSettingsChanged(context);
            } else {
                log("Setting does not exist or not updatable");
            }
        } else {
            log("settingName and/or settingValue null");
        }
    }
}
