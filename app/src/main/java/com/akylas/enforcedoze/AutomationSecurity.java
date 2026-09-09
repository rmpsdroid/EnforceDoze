package com.akylas.enforcedoze;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Security boundary for the intentionally exported Tasker/automation receivers.
 *
 * The receivers must remain exported so user-chosen automation applications can call them.
 * A per-install random token prevents arbitrary installed applications from using that public
 * surface without the user's explicit cooperation.
 */
final class AutomationSecurity {

    static final String EXTRA_AUTH_TOKEN = "authToken";
    static final String TARGET_PACKAGE_ITEM = "targetPackage";
    static final int MAX_DOZE_ENTER_DELAY_SECONDS = 1800;

    private static final String TAG = "AutomationSecurity";
    private static final String SECURITY_PREFS = "automation_security";
    private static final String PREF_AUTH_TOKEN = "authToken";
    private static final int TOKEN_BYTES = 32;

    /**
     * Android package names cannot contain shell metacharacters. PackageManager validation below
     * is authoritative; this syntax gate additionally guarantees that no raw shell syntax can ever
     * reach the legacy whitelist command.
     */
    private static final Pattern SAFE_PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AutomationSecurity() {
    }

    /**
     * Returns the stable per-install automation token.
     *
     * commit() is deliberate: a token returned to Tasker or embedded in our PendingIntent must
     * already be durable before it can be used. Failure therefore fails closed.
     */
    static synchronized String getAutomationAuthToken(Context context) {
        if (context == null) {
            return null;
        }

        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE);

        String existing = preferences.getString(PREF_AUTH_TOKEN, null);
        if (!TextUtils.isEmpty(existing)) {
            return existing;
        }

        byte[] random = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(random);

        String generated = Base64.encodeToString(
                random,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );

        if (!preferences.edit().putString(PREF_AUTH_TOKEN, generated).commit()) {
            Log.e(TAG, "Could not persist automation authentication token");
            return null;
        }

        return generated;
    }

    /**
     * Requires package-scoped delivery, the exact public action and the per-install token before
     * an exported receiver performs any side effect.
     */
    static boolean isAuthorizedAutomationIntent(
            Context context,
            Intent intent,
            String expectedAction
    ) {
        if (context == null
                || intent == null
                || expectedAction == null
                || !expectedAction.equals(intent.getAction())
                || !context.getPackageName().equals(intent.getPackage())) {
            return false;
        }

        String supplied = intent.getStringExtra(EXTRA_AUTH_TOKEN);
        if (TextUtils.isEmpty(supplied)) {
            return false;
        }

        String expected = getAutomationAuthToken(context);
        if (TextUtils.isEmpty(expected)
                || supplied.length() != expected.length()) {
            return false;
        }

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Converts untrusted automation input into a PackageManager-confirmed canonical package name.
     * Only this validated value may be concatenated into the legacy DeviceIdle shell command.
     */
    static String resolveInstalledPackage(Context context, String requestedPackage) {
        if (context == null
                || TextUtils.isEmpty(requestedPackage)
                || requestedPackage.length() > 255
                || !SAFE_PACKAGE_NAME.matcher(requestedPackage).matches()) {
            return null;
        }

        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(requestedPackage, 0);

            if (packageInfo == null
                    || TextUtils.isEmpty(packageInfo.packageName)
                    || !SAFE_PACKAGE_NAME.matcher(packageInfo.packageName).matches()) {
                return null;
            }

            return packageInfo.packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Explicit public automation allowlist.
     *
     * This intentionally does NOT use Utils.doesSettingExist(): that helper contains internal
     * settings which are not part of the Tasker API.
     *
     * The two historical public names shown by TaskerBroadcastsActivity are retained as aliases:
     * useAutoRotateAndBrightnessFix -> autoRotateAndBrightnessFix
     * enableSensors -> inverse of disableMotionSensors
     */
    static boolean applyAutomationSetting(
            Context context,
            String settingName,
            String settingValue
    ) {
        if (context == null || TextUtils.isEmpty(settingName) || settingValue == null) {
            return false;
        }

        switch (settingName) {
            case "turnOffDataInDoze":
            case "turnOffWiFiInDoze":
            case "ignoreLockscreenTimeout":
            case "disableWhenCharging":
            case "showPersistentNotif":
                return updateStrictBoolean(
                        context,
                        settingName,
                        settingValue,
                        false
                );

            case "useAutoRotateAndBrightnessFix":
            case "autoRotateAndBrightnessFix":
                return updateStrictBoolean(
                        context,
                        "autoRotateAndBrightnessFix",
                        settingValue,
                        false
                );

            case "enableSensors":
                return updateStrictBoolean(
                        context,
                        "disableMotionSensors",
                        settingValue,
                        true
                );

            case "disableMotionSensors":
                return updateStrictBoolean(
                        context,
                        "disableMotionSensors",
                        settingValue,
                        false
                );

            case "dozeEnterDelay":
                try {
                    int delay = Integer.parseInt(settingValue);
                    if (delay < 0
                            || delay > MAX_DOZE_ENTER_DELAY_SECONDS) {
                        return false;
                    }
                    Utils.updateSettingInt(context, "dozeEnterDelay", delay);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }

            default:
                return false;
        }
    }

    private static boolean updateStrictBoolean(
            Context context,
            String internalSettingName,
            String suppliedValue,
            boolean invert
    ) {
        Boolean parsed = parseStrictBoolean(suppliedValue);
        if (parsed == null) {
            return false;
        }

        boolean value = invert ? !parsed : parsed;
        Utils.updateSettingBool(context, internalSettingName, value);
        return true;
    }

    /**
     * Boolean.valueOf() silently turns arbitrary malformed strings into false. External automation
     * input instead accepts only the two actual boolean spellings.
     */
    private static Boolean parseStrictBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
