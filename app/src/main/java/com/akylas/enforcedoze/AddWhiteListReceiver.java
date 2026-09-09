package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;

import java.util.List;

import eu.chainfire.libsuperuser.Shell;

public class AddWhiteListReceiver extends BroadcastReceiver {
    public static String TAG = "EnforceDoze";

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AutomationSecurity.isAuthorizedAutomationIntent(
                context,
                intent,
                Utils.ACTION_ADD_WHITELIST
        )) {
            log("Rejected unauthorized " + Utils.ACTION_ADD_WHITELIST + " broadcast");
            return;
        }

        final String packageName = AutomationSecurity.resolveInstalledPackage(
                context,
                intent.getStringExtra("packageName")
        );

        if (packageName == null) {
            log("Rejected invalid or non-installed packageName");
            return;
        }

        log(Utils.ACTION_ADD_WHITELIST
                + " authenticated broadcast received for "
                + packageName);

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                List<String> output =
                        Shell.SH.run("dumpsys deviceidle whitelist +" + packageName);

                if (output != null) {
                    for (String s : output) {
                        log(s);
                    }
                } else {
                    log("Error occurred while adding validated package to DeviceIdle whitelist");
                }
            }
        });
    }
}
