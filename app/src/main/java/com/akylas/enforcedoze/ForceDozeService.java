package com.akylas.enforcedoze;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.chainfire.libsuperuser.Shell;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;
import static com.akylas.enforcedoze.Utils.isAirplaneEnabled;
import static com.akylas.enforcedoze.Utils.logToLogcat;

public class ForceDozeService extends Service {

    private static final String CHANNEL_STATS = "CHANNEL_STATS";
    private static final String CHANNEL_TIPS = "CHANNEL_TIPS";
    private static final String CHANNEL_SILENT = "CHANNEL_SILENT";
    private static final int PERSISTENT_NOTIF_ID = 1234;

    /**
     * Delivered as a real service intent (not only as a LocalBroadcast) so that a settings change
     * reaches the service even when it was killed in the meantime: starting it redelivers the
     * action instead of the change being silently dropped.
     */
    public static final String ACTION_RELOAD_SETTINGS = BuildConfig.APPLICATION_ID + ".ACTION_RELOAD_SETTINGS";
    public static final String ACTION_RELOAD_NOTIFICATION_BLOCKLIST = BuildConfig.APPLICATION_ID + ".ACTION_RELOAD_NOTIFICATION_BLOCKLIST";
    public static final String ACTION_RELOAD_APP_BLOCKLIST = BuildConfig.APPLICATION_ID + ".ACTION_RELOAD_APP_BLOCKLIST";
    /**
     * Sent by {@link BootCompleteReceiver} when the device rebooted with toggles still applied.
     * Airplane mode, location mode and battery saver all survive a restart, so without this the
     * phone would come back up permanently in the state Doze left it in.
     */
    public static final String ACTION_RESTORE_STATE = BuildConfig.APPLICATION_ID + ".ACTION_RESTORE_STATE";


    private static Shell.Interactive rootSession;
    private static Shell.Interactive nonRootSession;
    private static Shell.OnCommandResultListener2 onCommandResultListener2;
    boolean isSuAvailable = false;
    boolean isShizukuAvailable = false;
    ShizukuHandler shizukuHandler;
    boolean disableWhenCharging = true;
    boolean disableMotionSensors = true;
    boolean useAutoRotateAndBrightnessFix = false;
    boolean showPersistentNotif = false;
    boolean ignoreLockscreenTimeout = false;
    boolean waitForUnlock = false;
    boolean turnOffAllSensorsInDoze = false;
    boolean turnOffBiometricsInDoze = false;
    boolean turnOnBatterySaverInDoze = false;
    boolean turnOnAirplaneInDoze = false;
    boolean turnOffBluetoothInDoze = false;
    boolean turnOffGPSInDoze = false;
    boolean turnOffWiFiInDoze = false;
    boolean ignoreIfHotspot = false;
    boolean turnOffDataInDoze = false;
    boolean whitelistMusicAppNetwork = false;
    boolean whitelistCurrentApp = false;
    /**
     * Guards the un-suspend batch. SCREEN_ON and USER_PRESENT can arrive milliseconds apart, and
     * two concurrent batches over the same packages would race each other.
     */
    private final AtomicBoolean packageRestoreInFlight = new AtomicBoolean(false);
    /** Guards a device-state key while its restore command is outstanding. */
    private final Set<String> stateRestoreInFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** SystemClock.elapsedRealtime() of the last SCREEN_ON, for the WAKE_TIMING logs. */
    private volatile long wakeStartedAt = 0L;
    boolean maintenance = false;
    boolean setPendingDozeEnterAlarm = false;
    boolean disableStats = false;
    boolean disableLogcat = false;
    int dozeEnterDelay = 0;
    Timer enterDozeTimer;
    Timer disableSensorsTimer;
    DozeReceiver localDozeReceiver;
    ReloadSettingsReceiver reloadSettingsReceiver;
    ReloadNotificationBlocklistReceiver reloadNotificationBlocklistReceiver;
    ReloadAppsBlocklistReceiver reloadAppsBlocklistReceiver;
    NotificationCompat.Builder mStatsBuilder;
    PowerManager pm;
    PowerManager.WakeLock tempWakeLock;
    DozeStateStore dozeStateStore;
    ShizukuHandler.OnAvailibilityChange shizukuAvailabilityListener;
    Set<String> dozeUsageData;
    Set<String> dozeNotificationBlocklist;
    Set<String> dozeAppBlocklist;
    String sensorWhitelistPackage = "";
    String state = "";
    Long timeEnterDoze = 0L;
    Long timeExitDoze = 0L;
    String lastScreenOff = "Unknown";
    int lastDozeEnterBatteryLife = 0;
    int lastDozeExitBatteryLife = 0;
    String TAG = "ForceDozeService";
    /** Separate tag so wake timings can be filtered on their own during device testing. */
    static final String TAG_TIMING = "EnforceDozeTiming";
    /** Printed by the compatibility loop for each package it could not change. */
    private static final String FALLBACK_FAILURE_MARKER = "ENFORCEDOZE_PKG_FAIL";
    String lastKnownState = "null";

    // Add near the top of the class
    private static final String ACTION_IGNORE_RESULT = RequestIgnoreBatteryActivity.ACTION_IGNORE_RESULT;
    private static final String EXTRA_IGNORED = RequestIgnoreBatteryActivity.EXTRA_IGNORED;

    private BroadcastReceiver ignoreBatteryResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean ignored = intent.getBooleanExtra(EXTRA_IGNORED, false);
            String packageName = getPackageName();
            if (!ignored && !pm.isIgnoringBatteryOptimizations(packageName)) {
                log("Service still optimized after user prompt, showing notification...");
                Intent notificationIntent = new Intent();
                notificationIntent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
                        notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                Notification n = new NotificationCompat.Builder(ForceDozeService.this, CHANNEL_TIPS)
                        .setContentTitle("EnforceDoze")
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(
                                "EnforceDoze needs to be added to the Doze whitelist in order to work reliably. Please open the battery optimisation view and select 'Don't optimize' for EnforceDoze."))
                        .setSmallIcon(R.drawable.ic_battery_health)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(pi)
                        .setOngoing(false)
                        .build();
                NotificationManager notificationManager =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                notificationManager.notify(8765, n);
            } else {
                log("User granted ignore battery optimizations for service.");
            }
        }
    };
    private final ExecutorService rootShellExecutor = Executors.newSingleThreadExecutor();


    /**
     * What a Doze maintenance window brings back. Biometrics and the motion sensors stay off: they
     * are only re-disabled when a new Doze cycle begins, so restoring them here would leave them on
     * for the rest of the night.
     */
    private static final Set<String> MAINTENANCE_RESTORE_KEYS = new HashSet<>(Arrays.asList(
            DozeStateStore.KEY_AIRPLANE, DozeStateStore.KEY_BLUETOOTH, DozeStateStore.KEY_GPS,
            DozeStateStore.KEY_WIFI, DozeStateStore.KEY_MOBILE_DATA,
            DozeStateStore.KEY_BATTERY_SAVER, DozeStateStore.KEY_ALL_SENSORS));

    private void log(String message) {
        logToLogcat(TAG, message);
    }

    /**
     * Wake-path instrumentation. Deliberately on its own tag and not routed through
     * logToLogcat(), so a device test can capture the timings even with "Disable Logcat" on.
     * Seven lines per wake-up at most.
     */
    private void wakeTiming(String event) {
        long started = wakeStartedAt;
        long elapsed = started == 0L ? 0L : SystemClock.elapsedRealtime() - started;
        Log.i(TAG_TIMING, "WAKE_TIMING " + event + " +" + elapsed + "ms");
    }

    public ForceDozeService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localDozeReceiver = new DozeReceiver();
        reloadSettingsReceiver = new ReloadSettingsReceiver();
        reloadNotificationBlocklistReceiver = new ReloadNotificationBlocklistReceiver();
        reloadAppsBlocklistReceiver = new ReloadAppsBlocklistReceiver();
        enterDozeTimer = new Timer();
        disableSensorsTimer = new Timer();


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence statsName = getString(R.string.notification_channel_stats_name);
            String statsDescription = getString(R.string.notification_channel_stats_description);
            int statsImportance = NotificationManager.IMPORTANCE_MIN;
            NotificationChannel statsChannel = new NotificationChannel(CHANNEL_STATS, statsName, statsImportance);
            statsChannel.setDescription(statsDescription);

            CharSequence tipsName = getString(R.string.notification_channel_tips_name);
            String tipsDescription = getString(R.string.notification_channel_tips_description);
            int tipsImportance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel tipsChannel = new NotificationChannel(CHANNEL_TIPS, tipsName, tipsImportance);
            tipsChannel.setDescription(tipsDescription);
            
            // Create a silent channel for Android 12+ foreground service requirement
            CharSequence silentName = getString(R.string.notification_channel_silent_name);
            String silentDescription = getString(R.string.notification_channel_silent_description);
            int silentImportance = NotificationManager.IMPORTANCE_MIN;
            NotificationChannel silentChannel = new NotificationChannel(CHANNEL_SILENT, silentName, silentImportance);
            silentChannel.setDescription(silentDescription);
            silentChannel.setSound(null, null);
            silentChannel.setShowBadge(false);
            
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(statsChannel);
            notificationManager.createNotificationChannel(tipsChannel);
            notificationManager.createNotificationChannel(silentChannel);
        }

        mStatsBuilder = new NotificationCompat.Builder(this, CHANNEL_STATS);
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        dozeStateStore = DozeStateStore.getInstance(getApplicationContext());
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
//        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        if (Utils.isDeviceRunningOnN()) {
            filter.addAction("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED");
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadSettingsReceiver, new IntentFilter("reload-settings"));
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadNotificationBlocklistReceiver, new IntentFilter("reload-notification-blocklist"));
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadAppsBlocklistReceiver, new IntentFilter("reload-app-blocklist"));
        LocalBroadcastManager.getInstance(this).registerReceiver(ignoreBatteryResultReceiver, new IntentFilter(ACTION_IGNORE_RESULT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.registerReceiver(localDozeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            this.registerReceiver(localDozeReceiver, filter);
        }
        turnOffDataInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffDataInDoze", false);
        ignoreIfHotspot = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreIfHotspot", true);
        turnOffWiFiInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffWiFiInDoze", false);
        turnOffAllSensorsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffAllSensorsInDoze", false);
        turnOffBiometricsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffBiometricsInDoze", false);
        turnOnBatterySaverInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOnBatterySaverInDoze", false);
        turnOnAirplaneInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOnAirplaneInDoze", false);
        turnOffBluetoothInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffBluetoothInDoze", false);
        turnOffGPSInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffGPSInDoze", false);
        whitelistMusicAppNetwork = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistMusicAppNetwork", false);
        whitelistCurrentApp = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistCurrentApp", false);
        ignoreLockscreenTimeout = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreLockscreenTimeout", true);
        waitForUnlock = getDefaultSharedPreferences(getApplicationContext()).getBoolean("waitForUnlock", false);
        dozeEnterDelay = getDefaultSharedPreferences(getApplicationContext()).getInt("dozeEnterDelay", 0);
        useAutoRotateAndBrightnessFix = getDefaultSharedPreferences(getApplicationContext()).getBoolean("autoRotateAndBrightnessFix", false);
        sensorWhitelistPackage = getDefaultSharedPreferences(getApplicationContext()).getString("sensorWhitelistPackage", "");
        disableMotionSensors = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableMotionSensors", true);
        disableStats = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableStats", false);
        disableLogcat = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableLogcat", false);
        disableWhenCharging = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableWhenCharging", true);
        isSuAvailable = getDefaultSharedPreferences(getApplicationContext()).getBoolean("isSuAvailable", false);
        showPersistentNotif = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("showPersistentNotif", false);
        dozeUsageData = loadDozeUsageData();
        dozeNotificationBlocklist = loadStringSet("notificationBlockList");
        dozeAppBlocklist = loadStringSet("dozeAppBlockList");

        // Initialize Shizuku handler
        shizukuHandler = ShizukuHandler.getInstance(getApplicationContext());
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());
        isShizukuAvailable = false;
        // Registered unconditionally: the user can switch to Shizuku mode while the service runs,
        // and the binder can arrive (or come back) long after the service was created.
        shizukuAvailabilityListener = value -> {
            isShizukuAvailable = value;
            log("Shizuku availability changed: " + value);
            if (value) {
                // A pending reversion may have been waiting for exactly this.
                restoreDeviceStates(getApplicationContext(), "Shizuku became available");
            }
        };
        shizukuHandler.addOnAvailabilityChangeListener(shizukuAvailabilityListener);
        if (useShizuku) {
            shizukuHandler.checkShizukuAvailability();
            isShizukuAvailable = shizukuHandler.isShizukuAvailable();
            log("Shizuku mode enabled, available: " + isShizukuAvailable);
        }

        if (!Utils.isDumpPermissionGranted(getApplicationContext())) {
            if (isShizukuAvailable) {
                grantDumpPermissionViaShizuku();
            } else if (isSuAvailable) {
                grantDumpPermission();
            }
        }

        if (Utils.isDeviceRunningOnN()) {
            if (!Utils.isSecureSettingsPermissionGranted(getApplicationContext())) {
                if (isShizukuAvailable) {
                    grantSecureSettingsPermissionViaShizuku();
                } else if (isSuAvailable) {
                    grantSecureSettingsPermission();
                }
            }
        }

        if (!Utils.isReadPhoneStatePermissionGranted(getApplicationContext())) {
            if (isShizukuAvailable) {
                grantReadPhoneStatePermissionViaShizuku();
            } else if (isSuAvailable) {
                grantReadPhoneStatePermission();
            }
        }

        // To initialize root shell/shell on service start
        if (useShizuku && isShizukuAvailable) {
            shizukuHandler.executeCommand("whoami", (commandCode, exitCode, stdout, stderr) -> {
                log("Shizuku test command executed");
            }, true);
        } else if (isSuAvailable) {
            executeCommandWithRoot("whoami");
        } else {
            executeCommand("whoami");
        }
        recoverAfterServiceRecreation();
    }

    /**
     * One UI kills and recreates this service freely, including in the middle of the night with the
     * screen off and Doze still in force.
     * <p>
     * The old code unconditionally un-suspended everything in the blocklist here, which destroyed
     * the blocklist effect for the rest of the sleep period every time the system recycled the
     * service. Recovery now only runs when the Doze session is genuinely over - the screen is on,
     * or the persisted inDoze flag says we are not dozing.
     */
    private void recoverAfterServiceRecreation() {
        boolean screenOn = Utils.isScreenOn(getApplicationContext());
        boolean inDoze = dozeStateStore.isInDoze();
        boolean hasPackages = dozeStateStore.hasAppliedSuspendedPackages();
        boolean hasStates = dozeStateStore.hasPendingRestore();

        if (!hasPackages && !hasStates) {
            log("RECOVERY_NONE: service recreated with nothing pending");
            return;
        }

        log("RECOVERY_CHECK screenOn=" + screenOn + " inDoze=" + inDoze
                + " pendingPackages=" + (hasPackages ? dozeStateStore.getAppliedSuspendedPackages().size() : 0)
                + " pendingStates=" + dozeStateStore.getAppliedKeys());

        if (!screenOn && inDoze) {
            log("RECOVERY_DEFERRED: still dozing with the screen off, keeping suspensions and "
                    + "pending state until the screen comes back on");
            return;
        }

        log("RECOVERY_RUNNING: Doze session is over, restoring now");
        restoreSuspendedPackages("service recreated");
        reEnableBlockedNotifications();
        restoreDeviceStates(getApplicationContext(), "service recreated");
    }


    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("Stopping service and enabling sensors");
        this.unregisterReceiver(localDozeReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reloadSettingsReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reloadNotificationBlocklistReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reloadAppsBlocklistReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(ignoreBatteryResultReceiver);
        if (shizukuAvailabilityListener != null) {
            shizukuHandler.removeOnAvailabilityChangeListener(shizukuAvailabilityListener);
        }
        if (disableMotionSensors) {
            executeCommand("dumpsys sensorservice enable");
        }
        // Put back everything we changed for Doze; without this a service stopped while dozing
        // would leave airplane mode on and the sensors off with nobody left to revert them.
        restoreSuspendedPackages("service destroyed");
        restoreDeviceStates(getApplicationContext(), "service destroyed");
        //ensure we exit doze if stopped from background
        exitDoze(getDeviceIdleState());
        if (rootSession != null) {
            rootSession.close();
            rootSession = null;
        }
        if (nonRootSession != null) {
            nonRootSession.close();
            nonRootSession = null;
        }
        // Show disabled notification only when the user disabled EnforceDoze, not when a schedule stops the service.
        if (!PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("serviceEnabled", false)) {
            Utils.showDisabledNotification(getApplicationContext());
        }
        // Update tile state
        Utils.updateTileState(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        log("Service has now started");

        // A reload delivered as a service intent survives the service having been killed, unlike a
        // LocalBroadcast which is dropped when nothing is listening at that instant.
        String action = intent != null ? intent.getAction() : null;
        if (action != null) {
            switch (action) {
                case ACTION_RELOAD_SETTINGS:
                    ensureForegroundNotification();
                    reloadSettings();
                    return START_STICKY;
                case ACTION_RELOAD_NOTIFICATION_BLOCKLIST:
                    ensureForegroundNotification();
                    reloadNotificationBlockList();
                    return START_STICKY;
                case ACTION_RELOAD_APP_BLOCKLIST:
                    ensureForegroundNotification();
                    reloadAppsBlockList();
                    return START_STICKY;
                case ACTION_RESTORE_STATE:
                    ensureForegroundNotification();
                    handleRestoreStateRequest();
                    return START_STICKY;
            }
        }

        ensureForegroundNotification();
        addSelfToDozeWhitelist();
        enterDoze(this);
        lastKnownState = getDeviceIdleState();
        // Hide disabled notification when service starts
        Utils.hideDisabledNotification(getApplicationContext());
        // Update tile state
        Utils.updateTileState(getApplicationContext());
        return START_STICKY;
    }

    /**
     * Restores whatever Doze left applied and, when EnforceDoze itself is switched off, stops again
     * afterwards - the service was started purely to get the device back to normal.
     */
    private void handleRestoreStateRequest() {
        dozeStateStore.setInDoze(false);
        restoreDeviceStates(getApplicationContext(), "restore requested");

        if (!getDefaultSharedPreferences(getApplicationContext()).getBoolean("serviceEnabled", false)) {
            log("EnforceDoze is disabled, stopping once the reversion has been given time to run");
            // Brief grace period so the fire-and-forget shell commands have left the process.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                log("Reversion issued, stopping the service again");
                stopSelf();
            }, 3000);
        }
    }

    public void reloadSettings() {
        log("EnforceDoze settings reloaded ----------------------------------");
        // The execution mode can be switched from the UI while we are running, so re-evaluate it
        // here instead of trusting what was cached when the service was created.
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());
        if (useShizuku) {
            shizukuHandler.checkShizukuAvailability();
            isShizukuAvailable = shizukuHandler.isShizukuAvailable();
        } else {
            isShizukuAvailable = false;
        }
        log("executionMode: " + (useShizuku ? "shizuku" : "root") + ", Shizuku available: " + isShizukuAvailable);
        isSuAvailable = getDefaultSharedPreferences(getApplicationContext()).getBoolean("isSuAvailable", false);
        dozeUsageData = loadDozeUsageData();
        log("dozeUsageData: " + "Total Entries -> " + dozeUsageData.size());
        turnOffDataInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffDataInDoze", false);
        log("turnOffDataInDoze: " + turnOffDataInDoze);
        ignoreIfHotspot = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreIfHotspot", true);
        log("ignoreIfHotspot: " + ignoreIfHotspot);
        turnOffWiFiInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffWiFiInDoze", false);
        log("turnOffWiFiInDoze: " + turnOffWiFiInDoze);
        turnOffAllSensorsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffAllSensorsInDoze", false);
        log("turnOffAllSensorsInDoze: " + turnOffAllSensorsInDoze);
        turnOffBiometricsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffBiometricsInDoze", false);
        log("turnOffBiometricsInDoze: " + turnOffBiometricsInDoze);
        turnOnBatterySaverInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOnBatterySaverInDoze", false);
        log("turnOnBatterySaverInDoze: " + turnOnBatterySaverInDoze);
        turnOnAirplaneInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOnAirplaneInDoze", false);
        log("turnOnAirplaneInDoze: " + turnOnAirplaneInDoze);
        turnOffBluetoothInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffBluetoothInDoze", false);
        log("turnOffBluetoothInDoze: " + turnOffBluetoothInDoze);
        turnOffGPSInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffGPSInDoze", false);
        log("turnOffGPSInDoze: " + turnOffGPSInDoze);
        whitelistMusicAppNetwork = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistMusicAppNetwork", false);
        log("whitelistMusicAppNetwork: " + whitelistMusicAppNetwork);
        whitelistCurrentApp = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistCurrentApp", false);
        log("whitelistCurrentApp: " + whitelistCurrentApp);
        ignoreLockscreenTimeout = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreLockscreenTimeout", true);
        log("ignoreLockscreenTimeout: " + ignoreLockscreenTimeout);
        waitForUnlock = getDefaultSharedPreferences(getApplicationContext()).getBoolean("waitForUnlock", false);
        log("waitForUnlock: " + waitForUnlock);
        dozeEnterDelay = getDefaultSharedPreferences(getApplicationContext()).getInt("dozeEnterDelay", 0);
        log("dozeEnterDelay: " + dozeEnterDelay);
        useAutoRotateAndBrightnessFix = getDefaultSharedPreferences(getApplicationContext()).getBoolean("autoRotateAndBrightnessFix", false);
        log("useAutoRotateAndBrightnessFix: " + useAutoRotateAndBrightnessFix);
        sensorWhitelistPackage = getDefaultSharedPreferences(getApplicationContext()).getString("sensorWhitelistPackage", "");
        log("sensorWhitelistPackage: " + sensorWhitelistPackage);
        disableMotionSensors = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableMotionSensors", true);
        log("disableMotionSensors: " + disableMotionSensors);
        disableStats = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableStats", false);
        log("disableStats: " + disableStats);
        disableLogcat = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableLogcat", false);
        log("disableLogcat: " + disableLogcat);
        disableWhenCharging = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableWhenCharging", true);
        log("disableWhenCharging: " + disableWhenCharging);
        showPersistentNotif = getDefaultSharedPreferences(getApplicationContext()).getBoolean("showPersistentNotif", false);
        log("showPersistentNotif: " + showPersistentNotif);
        log("EnforceDoze settings reloaded ----------------------------------");
        ensureForegroundNotification();
    }

    /**
     * On Android 12+ a foreground service must own a notification, so we always post one (a silent
     * one when the user does not want the stats notification). Must be called on every entry point
     * that can be reached through startForegroundService().
     */
    private void ensureForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (showPersistentNotif) {
                // Show notification with stats if user enabled it
                showPersistentNotification();
            } else {
                // Show minimal silent notification on Android 12+ to comply with foreground service requirements
                showSilentNotification();
            }
        } else {
            // On older versions, respect the user's preference
            if (showPersistentNotif) {
                showPersistentNotification();
            } else {
                hidePersistentNotification();
            }
        }
    }

    public void reloadNotificationBlockList() {
        log("Notification blocklist reloaded ----------------------------------");
        dozeNotificationBlocklist = loadStringSet("notificationBlockList");
        log("notificationBlockList: " + dozeNotificationBlocklist.size() + " items");
        log("Notification blocklist reloaded ----------------------------------");
    }

    public void reloadAppsBlockList() {
        log("Apps blocklist reloaded ----------------------------------");
        dozeAppBlocklist = loadStringSet("dozeAppBlockList");
        log("dozeAppBlockList: " + dozeAppBlocklist.size() + " items");
        log("Apps blocklist reloaded ----------------------------------");
    }

    public void grantDumpPermission() {
        log("Granting android.permission.DUMP to " + BuildConfig.APPLICATION_ID);
        executeCommandWithRoot("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.DUMP");
    }

    public void grantDumpPermissionViaShizuku() {
        log("Granting android.permission.DUMP to " + BuildConfig.APPLICATION_ID + " via Shizuku");
        shizukuHandler.executeCommand("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.DUMP",
            (commandCode, exitCode, stdout, stderr) -> {
                if (exitCode == 0) {
                    log("DUMP permission granted successfully");
                }
            }, true);
    }

    public void grantSecureSettingsPermission() {
        log("Granting android.permission.WRITE_SECURE_SETTINGS to " + BuildConfig.APPLICATION_ID);
        executeCommandWithRoot("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.WRITE_SECURE_SETTINGS");
    }

    public void grantSecureSettingsPermissionViaShizuku() {
        log("Granting android.permission.WRITE_SECURE_SETTINGS to " + BuildConfig.APPLICATION_ID + " via Shizuku");
        shizukuHandler.executeCommand("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.WRITE_SECURE_SETTINGS",
            (commandCode, exitCode, stdout, stderr) -> {
                if (exitCode == 0) {
                    log("WRITE_SECURE_SETTINGS permission granted successfully");
                }
            }, true);
    }

    public void grantReadPhoneStatePermission() {
        log("Granting android.permission.READ_PHONE_STATE to " + BuildConfig.APPLICATION_ID);
        executeCommandWithRoot("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.READ_PHONE_STATE");
    }

    public void grantReadPhoneStatePermissionViaShizuku() {
        log("Granting android.permission.READ_PHONE_STATE to " + BuildConfig.APPLICATION_ID + " via Shizuku");
        shizukuHandler.executeCommand("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.READ_PHONE_STATE",
            (commandCode, exitCode, stdout, stderr) -> {
                if (exitCode == 0) {
                    log("READ_PHONE_STATE permission granted successfully");
                }
            }, true);
    }

    public void grantSensorPrivacyPermission() {
        log("Granting android.permission.MANAGE_SENSOR_PRIVACY to " + BuildConfig.APPLICATION_ID);
        executeCommandWithRoot("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.MANAGE_SENSOR_PRIVACY");
    }

    public void addSelfToDozeWhitelist() {
        log("Checking self-whitelist capability....");
        log("Nougat: " + Utils.isDeviceRunningOnN());
        log("SU available: " + isSuAvailable);
        String packageName = getPackageName();
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            if (!Utils.isDeviceRunningOnN()) {
                log("Adding service to Doze whitelist for stability");
                executeCommand("dumpsys deviceidle whitelist +" + BuildConfig.APPLICATION_ID);
            } else if (Utils.isDeviceRunningOnN() && (isSuAvailable || isShizukuAvailable)) {
                log("Adding service to Doze whitelist for stability");
                executeCommandWithRoot("dumpsys deviceidle whitelist +" + BuildConfig.APPLICATION_ID);
            } else {
                log("Requesting user to disable battery optimizations via system dialog...");
                try {
                    Intent reqActivity = new Intent(this, RequestIgnoreBatteryActivity.class);
                    reqActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(reqActivity);
                } catch (Exception e) {
                    log("Failed to launch RequestIgnoreBatteryActivity: " + e.getMessage());
                    // fallback: show the old notification immediately
                    // (optional) reuse existing notification code here
                    log("Service cannot be added to Doze whitelist because user is on Nougat. Showing notification...");
                    Intent notificationIntent = new Intent();
                    notificationIntent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                            notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                    Notification n = new NotificationCompat.Builder(this, CHANNEL_TIPS)
                            .setContentTitle("EnforceDoze")
                            .setStyle(new NotificationCompat.BigTextStyle().bigText("EnforceDoze needs to be added to the Doze whitelist in order to work reliably. Please click on this notification to open the battery optimisation view, click on 'EnforceDoze' and select 'Don't' Optimize'"))
                            .setSmallIcon(R.drawable.ic_battery_health)
                            .setPriority(1)
                            .setContentIntent(intent)
                            .setOngoing(false).build();
                    NotificationManager notificationManager =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    notificationManager.notify(8765, n);
                }
            }

        } else {
            log("Service already in Doze whitelist for stability");
        }
    }

    public void applyDoze() {
        if (Utils.isDeviceRunningOnN()) {
            if (isSuAvailable || isShizukuAvailable) {
                executeCommandWithRoot("dumpsys deviceidle force-idle deep");
            } else {
                DozeTunableHandler handler = DozeTunableHandler.getInstance();
                log("Unrooted device, putting custom values in device_idle_constants...");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ArrayList<String> commands = handler.getCommandsList();
                    commands.forEach(this::executeCommand);
                } else {
                    Settings.Global.putString(getContentResolver(), "device_idle_constants", handler.getTunableString());
                }
            }
        } else {
            executeCommand("dumpsys deviceidle force-idle");
        }
    }

    public void leaveDoze() {
        wakeTiming("doze_unforce_start");
        if (Utils.isDeviceRunningOnN()) {
            if (isSuAvailable || isShizukuAvailable) {
                executeCommandWithRoot("dumpsys deviceidle unforce",
                        (commandCode, exitCode, stdout, stderr) -> {
                            Log.i(TAG, "DOZE_UNFORCE_FINISHED exit=" + exitCode);
                            wakeTiming("doze_unforce_finished");
                        }, false);
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    executeCommand("device_config reset trusted_defaults device_idle");
                    executeCommand("dumpsys deviceidle step");
                } else {
                    Settings.Global.putString(getContentResolver(), "device_idle_constants", null);

                }
            }
        } else {
            executeCommand("dumpsys deviceidle step");
        }
    }

    public void enterDoze(Context context) {
        if (!Utils.isInsideCustomDozePeriod(context)) {
            log("Outside custom Doze periods, skip entering Doze");
            return;
        }
        if (!getDeviceIdleState().equals("IDLE") || !lastKnownState.equals("IDLE")) {
            if (!Utils.isScreenOn(context)) {
                lastKnownState = "IDLE";
                if (tempWakeLock != null) {
                    if (tempWakeLock.isHeld()) {
                        log("Releasing ForceDozeTempWakelock");
                        tempWakeLock.release();
                    }
                }
                if (dozeAppBlocklist.size() != 0) {
                    log("Disabling apps that are in the Doze app blocklist");
                    if (whitelistCurrentApp) {
                        // when root is not available we use UsageStatsManager
                        // but i am not sure i can trust it as it does not really returns the front
                        // app but last one used (what about apps running in the background?)
                        if (isSuAvailable || isShizukuAvailable) {
                            try {
                                getFocusedApps((HashSet<String> packageNames) -> {
                                    List<String> toBlock = new ArrayList<>();
                                    for (String pkg : dozeAppBlocklist) {
                                        if (!packageNames.contains(pkg)) {
                                            toBlock.add(pkg);
                                        }
                                    }
                                    suspendPackagesForDoze(toBlock);
                                });
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else {
                            String currentlyFocused = getNonRootFocusedPackageName();
                            List<String> toBlock = new ArrayList<>();
                            for (String pkg : dozeAppBlocklist) {
                                if (!pkg.equals(currentlyFocused)) {
                                    toBlock.add(pkg);
                                }
                            }
                            suspendPackagesForDoze(toBlock);
                        }
                    } else {
                        suspendPackagesForDoze(dozeAppBlocklist);
                    }

                }

                if (dozeNotificationBlocklist.size() != 0) {
                    log("Disabling notifications for apps in the Notification blocklist");
                    List<String> toBlock = new ArrayList<>();
                    for (String pkg : dozeNotificationBlocklist) {
                        if (!dozeAppBlocklist.contains(pkg)) {
                            toBlock.add(pkg);
                        }
                    }
                    setNotificationsEnabledForPackages(toBlock, false);
                }
                timeEnterDoze = System.currentTimeMillis();
                if (Utils.isConnectedToCharger(getApplicationContext())) {
                    lastDozeEnterBatteryLife = 0;
                } else {
                    lastDozeEnterBatteryLife = Utils.getBatteryLevel(getApplicationContext());
                }
                log("Entering Doze");
                dozeStateStore.setInDoze(true);
                applyDoze();
                lastScreenOff = Utils.getDateCurrentTimeZone(System.currentTimeMillis());

                if (!disableStats) {
                    dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.isConnectedToCharger(getApplicationContext()) ? 0.0f : Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("ENTER"));
                    saveDozeDataStats();
                }

                if (disableMotionSensors) {
                    dozeStateStore.markApplied(DozeStateStore.KEY_MOTION_SENSORS, true);
                    disableSensorsTimer = new Timer();
                    disableSensorsTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            log("Disabling motion sensors");
                            if (sensorWhitelistPackage.equals("")) {
                                executeCommand("dumpsys sensorservice restrict");
                            } else {
                                log("Package " + sensorWhitelistPackage + " is whitelisted from sensorservice");
                                log("Note: Packages that get whitelisted are supposed to request sensor access again, if the app doesn't work, email the dev of that app!");
                                executeCommand("dumpsys sensorservice restrict " + sensorWhitelistPackage);
                            }
                        }
                    }, 2000);
                } else {
                    log("Not disabling motion sensors because disableMotionSensors=false");
                }
                enterDozeHandleNetwork(context);

            } else {
                log("Screen is on, skip entering Doze");
            }
        } else {
            log("enterDoze() received but skipping because device is already Dozing");
        }
    }

    /**
     * Undoes the notification blocking. Package un-suspension is handled separately by
     * {@link #restoreSuspendedPackages(String)}, which is driven by the persisted set rather than
     * the live blocklist and runs first on the wake path.
     */
    private void reEnableBlockedNotifications() {
        if (dozeNotificationBlocklist.size() == 0) {
            return;
        }
        log("Re-enabling notifications for apps in the Notification blocklist");
        List<String> toUnblock = new ArrayList<>();
        for (String pkg : dozeNotificationBlocklist) {
            if (!dozeAppBlocklist.contains(pkg)) {
                toUnblock.add(pkg);
            }
        }
        setNotificationsEnabledForPackages(toUnblock, true);
    }

    public void exitDoze(String newDeviceIdleState) {
        timeExitDoze = System.currentTimeMillis();
        if (Utils.isConnectedToCharger(getApplicationContext())) {
            lastDozeExitBatteryLife = 0;
        } else {
            lastDozeExitBatteryLife = Utils.getBatteryLevel(getApplicationContext());
        }
        lastKnownState = "ACTIVE";
        dozeStateStore.setInDoze(false);
        leaveDoze();

        log("exitDoze current Doze state: " + newDeviceIdleState);

        if (!disableStats) {
            dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.isConnectedToCharger(getApplicationContext()) ? 0.0f : Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("EXIT"));
            saveDozeDataStats();
        }

        restoreSuspendedPackages("exit Doze");
        reEnableBlockedNotifications();

        // Motion sensors are part of the persisted state, so they are re-enabled by the same code
        // path as the radios instead of a one-shot timer.
        restoreDeviceStates(getApplicationContext(), "exit Doze");

        if (showPersistentNotif) {
            Timer updateNotif = new Timer();
            updateNotif.schedule(new TimerTask() {
                @Override
                public void run() {
                    updatePersistentNotification(lastScreenOff, Utils.diffInMins(timeEnterDoze, timeExitDoze), (lastDozeEnterBatteryLife - lastDozeExitBatteryLife));
                }
            }, 2000);
        }

    }

    public void executeCommand(final String command) {
        executeCommand(command, null, false);
    }
    public void executeCommand(final String command, Shell.OnCommandResultListener2 onResult, Boolean printOutput) {
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());

        if (useShizuku) {
            shizukuHandler.executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
                if (onResult != null) {
                    onResult.onCommandResult(commandCode, exitCode, stdout, stderr);
                }
                if (printOutput) {
                    printShellOutput(stdout);
                    printShellOutput(stderr);
                }
            }, printOutput);
            return;
        }

        rootShellExecutor.execute(() -> {
            if (nonRootSession != null) {
                nonRootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                    if (onResult != null) {
                        onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                    }
                    if (printOutput){
                        printShellOutput(STDOUT);
                        printShellOutput(STDERR);
                    }
                });
            } else {
                nonRootSession = new Shell.Builder().
                        useSH().
                        setWatchdogTimeout(5).
                        setMinimalLogging(true).
                        open((success, reason) -> {
                            if (reason != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                                log("Error opening shell: exitCode " + reason);
                            } else {
                                nonRootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                                    if (onResult != null) {
                                        onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                                    }
                                    if (printOutput){
                                        printShellOutput(STDOUT);
                                        printShellOutput(STDERR);
                                    }
                                });
                            }
                        });
            }
        });
    }

    public interface OnGetFocusedApp {
        void onGetFocusedApps(HashSet<String> result);
    }

    public HashSet<String> parseFocusedApps(String services) {
        if (!services.isEmpty()) {
            return new HashSet<String>(Arrays.asList(services.split("\\r?\\n")));
        }
        return new HashSet<String>();
    }

    public String getNonRootFocusedPackageName() {
        var usm = (UsageStatsManager) this.getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time);
        if (appList != null && !appList.isEmpty()) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats usageStats : appList) {
                mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (!mySortedMap.isEmpty()) {
                return Objects.requireNonNull(mySortedMap.get(mySortedMap.lastKey())).getPackageName();
            }
        }
        return null;
    }

    String FOCUSED_APP_REGEXP = "\\{[a-z0-9]+\\s[a-z0-9]+\\s(.*)\\/";

    public void getFocusedApps(OnGetFocusedApp callback) {
        executeCommandWithRoot("dumpsys activity activities | grep -E 'CurrentFocus|ResumedActivity|FocusedApp'", (commandCode, exitCode, STDOUT, STDERR) -> {
            String result = "";
            if (commandCode == 0) {
                if (!STDOUT.isEmpty()) {
                    Matcher m = Pattern.compile(FOCUSED_APP_REGEXP).matcher(STDOUT.get(0));
                    if (m.find()) {
                        result = m.group(1);
                    }
                }
            }
            callback.onGetFocusedApps(parseFocusedApps(result));
        });
    }

    public void executeCommandWithRoot(final String command) {
        executeCommandWithRoot(command, null);
    }

    public void executeCommandWithRoot(final String command, Shell.OnCommandResultListener2 onResult) {
        executeCommandWithRoot(command, onResult, true);
    }

    public void executeCommandWithRoot(final String command, Shell.OnCommandResultListener2 onResult, boolean printOutput) {
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());

        if (useShizuku) {
            // Do not gate on the cached availability flag: ShizukuHandler waits for the binder and
            // retries by itself, so a command issued while Shizuku is briefly disconnected is
            // delivered once it comes back instead of being dropped.
            shizukuHandler.executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
                if (onResult != null) {
                    onResult.onCommandResult(commandCode, exitCode, stdout, stderr);
                }
                if (printOutput) {
                    printShellOutput(stdout);
                    printShellOutput(stderr);
                }
            }, printOutput);
            return;
        }

        rootShellExecutor.execute(() -> {
            if (rootSession != null) {
                rootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                    if (onResult != null) {
                        onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                    }
                    if (printOutput) {
                        printShellOutput(STDOUT);
                        printShellOutput(STDERR);
                    }
                });
            } else {
                rootSession = new Shell.Builder().
                        useSU().
                        setWatchdogTimeout(5).
                        setMinimalLogging(true).
                        open((success, reason) -> {
                            if (reason != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                                log("Error opening root shell: exitCode " + reason);
                                if (onResult != null) {
                                    // Report the failure instead of leaving the caller waiting
                                    // forever for a callback that will never come.
                                    onResult.onCommandResult(0, -1, new ArrayList<>(), new ArrayList<>());
                                }
                            } else {
                                rootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                                    if (onResult != null) {
                                        onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                                    }
                                    if (printOutput) {
                                        printShellOutput(STDOUT);
                                        printShellOutput(STDERR);
                                    }
                                });
                            }
                        });
            }
        });
    }

    public void printShellOutput(List<String> output) {
        if (disableLogcat) {
            return;
        }
        if (output != null && !output.isEmpty()) {
            for (String s : output) {
                log(s);
            }
        }
    }

    /**
     * SharedPreferences hands back the very instance it keeps in its cache, and explicitly forbids
     * modifying it. The old code added entries to that instance and then wrote it back, so
     * SharedPreferencesImpl saw "new value equals existing value" and skipped the write entirely -
     * which is why the stats (and anything else stored as a string set) never survived a restart.
     * Always read into a copy, and always write a fresh copy back.
     */
    private Set<String> loadStringSet(String key) {
        return new LinkedHashSet<>(PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .getStringSet(key, new LinkedHashSet<String>()));
    }

    private Set<String> loadDozeUsageData() {
        return loadStringSet("dozeUsageDataAdvanced");
    }

    public void saveDozeDataStats() {
        SharedPreferences sharedPreferences = getDefaultSharedPreferences(getApplicationContext());
        sharedPreferences.edit()
                .putStringSet("dozeUsageDataAdvanced", new LinkedHashSet<>(dozeUsageData))
                .apply();
    }

    /**
     * The toggle dance below sleeps 4 x 100ms. Upstream only ever reached it from a background
     * TimerTask; the reversion path calls it straight from the screen-on broadcast, so it gets its
     * own thread rather than stalling the main thread for 400ms at exactly the wrong moment.
     */
    public void autoRotateBrightnessFix() {
        if (!useAutoRotateAndBrightnessFix) {
            return;
        }
        new Thread(this::runAutoRotateBrightnessFix, "ForceDozeAutoRotateFix").start();
    }

    private void runAutoRotateBrightnessFix() {
        if (useAutoRotateAndBrightnessFix && Utils.isWriteSettingsPermissionGranted(getApplicationContext())) {
            log("Executing auto-rotate fix by doing a toggle");
            log("Current value: " + (Utils.isAutoRotateEnabled(getApplicationContext())) + " to " + (!Utils.isAutoRotateEnabled(getApplicationContext())));
            Utils.setAutoRotateEnabled(getApplicationContext(), !Utils.isAutoRotateEnabled(getApplicationContext()));
            try {
                log("Sleeping for 100ms");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }
            log("Current value: " + (Utils.isAutoRotateEnabled(getApplicationContext())) + " to " + !Utils.isAutoRotateEnabled(getApplicationContext()));
            Utils.setAutoRotateEnabled(getApplicationContext(), !Utils.isAutoRotateEnabled(getApplicationContext()));
            try {
                log("Sleeping for 100ms");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }
            log("Executing auto-brightness fix by doing a toggle");
            log("Current value: " + (Utils.isAutoBrightnessEnabled(getApplicationContext())) + " to " + (!Utils.isAutoBrightnessEnabled(getApplicationContext())));
            Utils.setAutoBrightnessEnabled(getApplicationContext(), !Utils.isAutoBrightnessEnabled(getApplicationContext()));
            try {
                log("Sleeping for 100ms");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }
            log("Current value: " + (Utils.isAutoBrightnessEnabled(getApplicationContext())) + " to " + (!Utils.isAutoBrightnessEnabled(getApplicationContext())));
            Utils.setAutoBrightnessEnabled(getApplicationContext(), !Utils.isAutoBrightnessEnabled(getApplicationContext()));
        }
    }

    public void showPersistentNotification() {
        Context context = getApplicationContext();
        Intent notificationIntent = new Intent(context, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification n = mStatsBuilder
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(getString(R.string.stats_no_data)))
                .setSmallIcon(R.drawable.ic_battery_health)
                .setPriority(-2)
                .setContentIntent(intent)
                .setOngoing(true)
                .build();
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE and the matching manifest value both arrive in API
        // 34. On Android 13 the manifest attribute parses to "no type", so passing the constant
        // there throws IllegalArgumentException ("not a subset of foregroundServiceType").
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(PERSISTENT_NOTIF_ID, n);
        } else {
            startForeground(PERSISTENT_NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    public void updatePersistentNotification(String lastScreenOff, int timeSpentDozing, int batteryUsage) {
        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification n = mStatsBuilder
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(getString(R.string.stats_long_text, lastScreenOff, timeSpentDozing, batteryUsage))
                                .setSummaryText(getString(R.string.stats_summary_text, batteryUsage)))
                .setShowWhen(false)
                .setSmallIcon(R.drawable.ic_battery_health)
                .setPriority(-2)
                .setContentIntent(intent)
                .setOngoing(true)
                .build();
        startForeground(PERSISTENT_NOTIF_ID, n);
    }

    public void hidePersistentNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }
    }

    public void showSilentNotification() {
        // On Android 12+, foreground services require a notification.
        // Clicking this notification opens the channel settings where the user can disable it
        // or minimize it further by setting it to "Silent" or "Minimized" importance.
        Intent notificationIntent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        notificationIntent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        notificationIntent.putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_SILENT);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        Notification n = new NotificationCompat.Builder(this, CHANNEL_SILENT)
                .setSmallIcon(R.drawable.ic_battery_health)
                .setContentTitle(getString(R.string.silent_notification_title))
                .setContentText(getString(R.string.silent_notification_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(intent)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .build();
        
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE and the matching manifest value both arrive in API
        // 34. On Android 13 the manifest attribute parses to "no type", so passing the constant
        // there throws IllegalArgumentException ("not a subset of foregroundServiceType").
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(PERSISTENT_NOTIF_ID, n);
        } else {
            startForeground(PERSISTENT_NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    public void setMobileNetwork(Context context, int targetState) {

        if (!Utils.isReadPhoneStatePermissionGranted(context)) {
            grantReadPhoneStatePermission();
        }

        String command;
        try {
            String transactionCode = getTransactionCode(context);
            SubscriptionManager mSubscriptionManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            for (int i = 0; i < mSubscriptionManager.getActiveSubscriptionInfoCountMax(); i++) {
                if (transactionCode != null && transactionCode.length() > 0) {
                    @SuppressLint("MissingPermission") int subscriptionId = mSubscriptionManager.getActiveSubscriptionInfoList().get(i).getSubscriptionId();
                    command = "service call phone " + transactionCode + " i32 " + subscriptionId + " i32 " + targetState;
                    // Goes through executeCommandWithRoot so it uses Shizuku when that is the
                    // configured backend; Shell.Pool.SU always tried su and silently did nothing
                    // (while blocking the caller) on a Shizuku-only device.
                    executeCommandWithRoot(command);
                }
            }
        } catch (Exception e) {
            log("Failed to toggle mobile data: " + e.getMessage());
        }
    }

    private static String getTransactionCode(Context context) {
        try {
            final TelephonyManager mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            final Class<?> mTelephonyClass = Class.forName(mTelephonyManager.getClass().getName());
            final Method mTelephonyMethod = mTelephonyClass.getDeclaredMethod("getITelephony");
            mTelephonyMethod.setAccessible(true);
            final Object mTelephonyStub = mTelephonyMethod.invoke(mTelephonyManager);
            final Class<?> mTelephonyStubClass = Class.forName(mTelephonyStub.getClass().getName());
            final Class<?> mClass = mTelephonyStubClass.getDeclaringClass();
            final Field field = mClass.getDeclaredField("TRANSACTION_setDataEnabled");
            field.setAccessible(true);
            return String.valueOf(field.getInt(null));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void setNotificationEnabledForPackage(String packageName, boolean enabled) {
        setNotificationsEnabledForPackages(Collections.singletonList(packageName), enabled);
    }

    /**
     * Toggles notifications for a whole set of packages using a single shell invocation.
     * <p>
     * The old per-package version called getInstalledPackages(GET_META_DATA) - a full enumeration
     * of every app on the device, several hundred of them on One UI - once for <em>each</em>
     * package, just to look up one uid. The uid is now read directly.
     */
    public void setNotificationsEnabledForPackages(Collection<String> packageNames, boolean enabled) {
        if (packageNames == null || packageNames.isEmpty()) {
            return;
        }

        int transaction = 0;
        try {
            @SuppressLint("PrivateApi") Field field = Class.forName("android.app.INotificationManager").getDeclaredClasses()[0].getDeclaredField("TRANSACTION_setNotificationsEnabledForPackage");
            field.setAccessible(true);
            transaction = field.getInt(null);
        } catch (ClassNotFoundException e) {
            log(e.toString());
        } catch (NoSuchFieldException e2) {
            log(e2.toString());
        } catch (IllegalAccessException e3) {
            log(e3.toString());
        }
        if (transaction == 0) {
            Log.e(TAG, "Could not resolve the notification transaction code, skipping");
            return;
        }

        List<String> commands = new ArrayList<>();
        for (String packageName : packageNames) {
            if (!Utils.isValidPackageName(packageName)) {
                Log.e(TAG, "Refusing to run a shell command for invalid package name: " + packageName);
                continue;
            }
            try {
                int uid = getPackageManager().getApplicationInfo(packageName, 0).uid;
                commands.add(String.format(Locale.US, "service call notification %d s16 %s i32 %d i32 %d",
                        transaction, packageName, uid, enabled ? 1 : 0));
            } catch (PackageManager.NameNotFoundException e) {
                log("Skipping notifications for '" + packageName + "', it is not installed");
            }
        }

        if (commands.isEmpty()) {
            return;
        }
        log((enabled ? "Turning on " : "Turning off ") + "notifications for " + commands.size() + " package(s)");
        executeCommandWithRoot(TextUtils.join("; ", commands), null, false);
    }

    public void setPackageState(Context context, String packageName, boolean enabled) {
        setPackagesState(Collections.singletonList(packageName), enabled);
    }

    /**
     * Suspends or un-suspends a whole set of packages.
     * <p>
     * Modern Android accepts several packages in a single {@code pm suspend}/{@code pm unsuspend}
     * call, which becomes one PackageManager batch transition - the fastest form available and the
     * one used on the API 36 devices this fork targets. Older releases only accept one package per
     * call, so a non-zero exit falls back to a shell loop. Either way this is a single Shizuku
     * command; never one process per package.
     *
     * @param done invoked with the final exit code once the batch (or its fallback) has finished
     */
    public void setPackagesState(Collection<String> packageNames, boolean enabled,
                                 Shell.OnCommandResultListener2 done) {
        final List<String> valid = new ArrayList<>();
        if (packageNames != null) {
            for (String packageName : packageNames) {
                if (!Utils.isValidPackageName(packageName)) {
                    Log.e(TAG, "Refusing to run a shell command for invalid package name: " + packageName);
                    continue;
                }
                valid.add(packageName.trim());
            }
        }
        if (valid.isEmpty()) {
            if (done != null) {
                done.onCommandResult(0, 0, new ArrayList<>(), new ArrayList<>());
            }
            return;
        }

        // pm suspend/unsuspend is what dims the launcher icons and makes widgets read
        // "unavailable"; that is the intended blocking behaviour, unchanged from upstream.
        final String verb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            verb = enabled ? "unsuspend" : "suspend";
        } else {
            verb = enabled ? "enable" : "disable";
        }

        final String joined = TextUtils.join(" ", valid);
        log((enabled ? "Enabling " : "Disabling ") + valid.size() + " blocklisted package(s)");

        executeCommandWithRoot("pm " + verb + " " + joined, (commandCode, exitCode, stdout, stderr) -> {
            if (exitCode == 0) {
                Log.i(TAG, "HARD_BLOCK_BATCH " + verb + " count=" + valid.size() + " exit=0");
                if (done != null) {
                    done.onCommandResult(commandCode, exitCode, stdout, stderr);
                }
                return;
            }
            // The multi-package form is not accepted here; retry one package per iteration inside
            // the same single shell. Re-running it over already-changed packages is harmless.
            Log.w(TAG, "HARD_BLOCK_COMPAT_FALLBACK " + verb + " count=" + valid.size()
                    + " batchExit=" + exitCode);
            runPackageStateFallback(valid, verb, done);
        }, false);
    }

    /**
     * One shell, one {@code pm} call per package. Individual failures (a package the user has since
     * uninstalled, say) are reported on stdout rather than aborting the loop, so one dead entry
     * cannot block the rest of the list from being restored.
     */
    private void runPackageStateFallback(List<String> valid, String verb,
                                         Shell.OnCommandResultListener2 done) {
        String command = "for p in " + TextUtils.join(" ", valid) + "; do pm " + verb
                + " \"$p\" || echo " + FALLBACK_FAILURE_MARKER + " \"$p\"; done";
        executeCommandWithRoot(command, (commandCode, exitCode, stdout, stderr) -> {
            int failures = 0;
            if (stdout != null) {
                for (String line : stdout) {
                    if (line != null && line.contains(FALLBACK_FAILURE_MARKER)) {
                        failures++;
                    }
                }
            }
            // Every package failing means the shell itself could not do the job, so report failure
            // and keep the recovery record. A few failures are as good as it gets and must not pin
            // the record open forever.
            int effectiveExit = (exitCode == 0 && failures < valid.size()) ? 0 : 1;
            Log.i(TAG, "HARD_BLOCK_COMPAT_FALLBACK finished " + verb + " count=" + valid.size()
                    + " failures=" + failures + " exit=" + effectiveExit);
            if (done != null) {
                done.onCommandResult(commandCode, effectiveExit, stdout, stderr);
            }
        }, false);
    }

    public void setPackagesState(Collection<String> packageNames, boolean enabled) {
        setPackagesState(packageNames, enabled, null);
    }

    /**
     * Suspends the blocklist for this Doze session and records exactly which packages were acted
     * on, before the command goes out.
     */
    private void suspendPackagesForDoze(Collection<String> packageNames) {
        List<String> valid = new ArrayList<>();
        for (String packageName : packageNames) {
            if (Utils.isValidPackageName(packageName)) {
                valid.add(packageName.trim());
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        // Persist first: a kill between here and the command completing still leaves a record.
        dozeStateStore.setAppliedSuspendedPackages(valid);
        setPackagesState(valid, false, null);
    }

    /**
     * Un-suspends exactly the packages recorded for this Doze session and clears the record only
     * once the command reports success. Safe to call from several wake triggers - the in-flight
     * guard collapses them into one batch.
     */
    public void restoreSuspendedPackages(String reason) {
        final Set<String> packages = dozeStateStore.getAppliedSuspendedPackages();
        if (packages.isEmpty()) {
            return;
        }
        if (!packageRestoreInFlight.compareAndSet(false, true)) {
            log("Un-suspend already in flight, skipping duplicate request (" + reason + ")");
            return;
        }

        Log.i(TAG, "HARD_BLOCK_RESTORE_START reason=" + reason + " count=" + packages.size());
        wakeTiming("hard_unsuspend_dispatched");
        setPackagesState(packages, true, (commandCode, exitCode, stdout, stderr) -> {
            try {
                if (exitCode == 0) {
                    dozeStateStore.clearAppliedSuspendedPackages();
                    Log.i(TAG, "HARD_BLOCK_RESTORE_COMMAND_FINISHED exit=0 count=" + packages.size());
                } else {
                    // Keep the record so the next trigger - USER_PRESENT, a Shizuku reconnect or
                    // the next service start - can try again.
                    Log.e(TAG, "HARD_BLOCK_RESTORE_COMMAND_FINISHED exit=" + exitCode
                            + " count=" + packages.size() + ", record kept for retry");
                }
            } finally {
                packageRestoreInFlight.set(false);
                wakeTiming("hard_unsuspend_finished");
            }
        });
    }

    public String getDeviceIdleState() {
        log("Fetching Device Idle state...");
        if (Utils.isDeviceRunningOnN()) {
            state = pm.isDeviceIdleMode() ? "IDLE" : "ACTIVE";
            if (isSuAvailable || isShizukuAvailable) {
                refreshDeviceIdleStateAsync();
            }
        } else {
            List<String> output = new ArrayList<>();
            List<String> err = new ArrayList<>();
            try {
                Shell.Pool.SU.run("dumpsys deviceidle", output, err, false);
            } catch (Shell.ShellDiedException e) {
                e.printStackTrace();
            }
            String parsed = parseDeviceIdleState(output);
            if (parsed != null) {
                state = parsed;
            }
        }

        return state;
    }

    private void refreshDeviceIdleStateAsync() {
        executeCommandWithRoot("dumpsys deviceidle", (commandCode, exitCode, output, stderr) -> {
            String parsed = parseDeviceIdleState(output);
            if (parsed != null) {
                state = parsed;
            }
        }, false);
    }

    private String parseDeviceIdleState(List<String> output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        String outputString = TextUtils.join(", ", output);
        // Order matters: "mState=IDLE_MAINTENANCE" also contains "mState=IDLE".
        String[] states = {"ACTIVE", "INACTIVE", "IDLE_PENDING", "SENSING", "LOCATING",
                "IDLE_MAINTENANCE", "PRE_IDLE", "WAITING_FOR_NETWORK", "OVERRIDE", "IDLE"};
        for (String candidate : states) {
            if (outputString.contains("mState=" + candidate)) {
                return candidate;
            }
        }
        return null;
    }


    public void disableMobileData() {
        setMobileDataState(false, null);
    }

    public void enableMobileData() {
        setMobileDataState(true, null);
    }

    public void setMobileDataState(boolean enabled, Shell.OnCommandResultListener2 done) {
        executeCommandWithRoot("svc data " + (enabled ? "enable" : "disable"), done, false);
    }


    public void disableWiFi() {
        setWiFiState(false, null);
    }

    /**
     * The old bodies called Utils.isMobileDataEnabled() straight after issuing the command - a
     * TelephonyManager round trip on the caller's thread, which on the wake path is the main
     * thread, and which reported on the wrong radio anyway.
     */
    public void setWiFiState(boolean enabled, Shell.OnCommandResultListener2 done) {
        if (isSuAvailable || isShizukuAvailable) {
            executeCommandWithRoot("svc wifi " + (enabled ? "enable" : "disable"), done, false);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi.setWifiEnabled(enabled);
            notifyCommandFinished(done, 0);
            return;
        }
        notifyCommandFinished(done, -1);
    }

    public void setAllSensorsState(Context context, boolean enabled) {
        setAllSensorsState(context, enabled, null);
    }

    public void setAllSensorsState(Context context, boolean enabled, Shell.OnCommandResultListener2 done) {
        if (!isSuAvailable && !isShizukuAvailable) {
            log("Cannot toggle sensors, neither root nor Shizuku is available");
            notifyCommandFinished(done, -1);
            return;
        }
//        if (!Utils.isSecureSensorPrivacyPermissionGranted(context)) {
//            grantSensorPrivacyPermission();
//        }

        try {
            int transactionCode = 4;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                transactionCode = 9;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                transactionCode = 8;
            }
            // Was running through Shell.Pool.SU, which bypassed Shizuku entirely: on a Shizuku-only
            // device the sensors were never turned back on. It also blocked the calling thread.
            executeCommandWithRoot("service call sensor_privacy " + transactionCode + " i32 "
                    + (enabled ? 0 : 1), done, false);
        } catch (Exception e) {
            log("Failed to toggle sensors: " + e.getMessage());
            notifyCommandFinished(done, -1);
        }
    }

    public void setBiometricsSensorState(Context context, boolean enabled) {
        setBiometricsSensorState(context, enabled, null);
    }

    public void setBiometricsSensorState(Context context, boolean enabled, Shell.OnCommandResultListener2 done) {
        if (!isSuAvailable && !isShizukuAvailable) {
            notifyCommandFinished(done, -1);
            return;
        }
        if (!Utils.isSecureSettingsPermissionGranted(context)) {
            grantSecureSettingsPermission();
        }
        executeCommandWithRoot("settings put secure biometric_keyguard_enabled "
                + (enabled ? 1 : 0), done, false);
    }

    public void setBatterSaverState(Context context, boolean enabled) {
        setBatterSaverState(context, enabled, null);
    }

    public void setBatterSaverState(Context context, boolean enabled, Shell.OnCommandResultListener2 done) {
        if (!isSuAvailable && !isShizukuAvailable) {
            notifyCommandFinished(done, -1);
            return;
        }
        executeCommandWithRoot("settings put global low_power " + (enabled ? 1 : 0), done, false);
    }

    public void setAirplaneState(Context context, boolean enabled) {
        setAirplaneState(context, enabled, null);
    }

    /**
     * The settings write and the broadcast that makes the system act on it are one dependent
     * operation, joined with {@code &&} in a single shell command. As two separate Shizuku calls
     * they each got their own thread, so the broadcast could be delivered before - or without -
     * the value it is meant to announce.
     */
    public void setAirplaneState(Context context, boolean enabled, Shell.OnCommandResultListener2 done) {
        if (!isSuAvailable && !isShizukuAvailable) {
            notifyCommandFinished(done, -1);
            return;
        }
        String command = "settings put global airplane_mode_on " + (enabled ? 1 : 0)
                + " && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state "
                + (enabled ? "true" : "false");
        executeCommandWithRoot(command, done, false);
    }

    public void setBluetoothState(Context context, boolean enabled) {
        setBluetoothState(context, enabled, null);
    }

    public void setBluetoothState(Context context, boolean enabled, Shell.OnCommandResultListener2 done) {
        if (!isSuAvailable && !isShizukuAvailable) {
            notifyCommandFinished(done, -1);
            return;
        }
        executeCommandWithRoot("svc bluetooth " + (enabled ? "enable" : "disable"), done, false);
    }

    public void setGPSState(Context context, boolean enabled) {
        setGPSState(context, enabled, null);
    }

    public void setGPSState(Context context, boolean enabled, Shell.OnCommandResultListener2 done) {
        if (!isSuAvailable && !isShizukuAvailable) {
            notifyCommandFinished(done, -1);
            return;
        }
        int locationMode = enabled ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF;
        executeCommandWithRoot("settings put secure location_mode " + locationMode, done, false);
    }

    /** Completes a callback for a path that never reached the shell. */
    private void notifyCommandFinished(Shell.OnCommandResultListener2 done, int exitCode) {
        if (done != null) {
            done.onCommandResult(0, exitCode, new ArrayList<>(), new ArrayList<>());
        }
    }

    public void enableWiFi() {
        setWiFiState(true, null);
    }

    class ReloadSettingsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User changed a setting, loading new settings into service");
            reloadSettings();
        }
    }

    class ReloadNotificationBlocklistReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User modified Notification blocklist, loading new packages into service");
            reloadNotificationBlockList();
        }
    }

    class ReloadAppsBlocklistReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User modified Doze app blocklist, loading new packages into service");
            reloadAppsBlockList();
        }
    }

    class PendingIntentDozeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("Pending intent broadcast received");
            setPendingDozeEnterAlarm = false;
            applyDoze();
        }
    }

    public void actualEnterDozeHandleNetwork(Context context, String packageName) {
        log("playingPackageName: " + packageName);
        // Capture the CURRENT device state at the moment screen turns off
        // These represent user's preference while screen was on.
        // Every toggle we actually change is written to disk *before* the shell command runs, so a
        // service kill mid-Doze cannot lose the information needed to put the device back.
        boolean wasWiFiTurnedOn = Utils.isWiFiEnabled(context);
        boolean wasMobileDataTurnedOn = Utils.isMobileDataEnabled(context);
        boolean wasAirplaneOn = Utils.isAirplaneEnabled(getContentResolver());
        boolean wasBluetoothOn = Utils.isBluetoothEnabled(getContentResolver());
        boolean wasGPSOn = Utils.isLocationEnabled(getContentResolver());
        boolean wasHotSpotTurnedOn = Utils.isHotspotEnabled(context);
        boolean wasBatterSaverOn = Utils.isBatterSaverEnabled(getContentResolver());
        dozeStateStore.recordPreDozeValue(DozeStateStore.KEY_HOTSPOT, wasHotSpotTurnedOn);

        if (turnOffAllSensorsInDoze) {
            log("Disabling All sensors");
            dozeStateStore.markApplied(DozeStateStore.KEY_ALL_SENSORS, true);
            setAllSensorsState(context, false);
        }
        if (turnOffBiometricsInDoze) {
            log("Disabling Biometrics");
            dozeStateStore.markApplied(DozeStateStore.KEY_BIOMETRICS, true);
            setBiometricsSensorState(context, false);
        }
        if (turnOnBatterySaverInDoze && !wasBatterSaverOn) {
            log("Enabling Battery Saver");
            dozeStateStore.markApplied(DozeStateStore.KEY_BATTERY_SAVER, false);
            setBatterSaverState(context, true);
        }

        if (turnOnAirplaneInDoze && (ignoreIfHotspot || !wasHotSpotTurnedOn) && !wasAirplaneOn && packageName == null) {
            log("Enabling airplane");
            dozeStateStore.markApplied(DozeStateStore.KEY_AIRPLANE, false);
            setAirplaneState(context, true);
        }

        if (turnOffBluetoothInDoze && wasBluetoothOn && packageName == null) {
            log("Disabling Bluetooth");
            dozeStateStore.markApplied(DozeStateStore.KEY_BLUETOOTH, true);
            setBluetoothState(context, false);
        }

        if (turnOffGPSInDoze && wasGPSOn && packageName == null) {
            log("Disabling GPS/Location");
            dozeStateStore.markApplied(DozeStateStore.KEY_GPS, true);
            setGPSState(context, false);
        }

        if (turnOffWiFiInDoze && (ignoreIfHotspot || !wasHotSpotTurnedOn) && wasWiFiTurnedOn && packageName == null) {
            log("Disabling WiFi");
            dozeStateStore.markApplied(DozeStateStore.KEY_WIFI, true);
            disableWiFi();
        }

        if (turnOffDataInDoze && wasMobileDataTurnedOn && (ignoreIfHotspot || !wasHotSpotTurnedOn) && (packageName == null || wasWiFiTurnedOn)) {
            log("Disabling mobile data");
            dozeStateStore.markApplied(DozeStateStore.KEY_MOBILE_DATA, true);
            disableMobileData();
        }
    }

    public void enterDozeHandleNetwork(Context context) {
        if (whitelistMusicAppNetwork) {
            try {
                NotificationService notifService = NotificationService.Companion.getInstance();
                if (notifService != null) {
                    notifService.getPlayingPackageName((String packageName) -> {
                        actualEnterDozeHandleNetwork(context, packageName);
                        return null;
                    });
                    return;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        actualEnterDozeHandleNetwork(context, null);
    }

    /**
     * Puts back every toggle we changed for Doze, driven by what is recorded on disk rather than by
     * in-memory fields, so a reversion still happens after the service was killed and recreated.
     * <p>
     * Every command is dispatched immediately and runs concurrently; nothing here blocks the
     * caller, which on the wake path is the main thread. The durable marker for each state is
     * cleared only in that command's completion callback, and only on exit code 0 - a Shizuku
     * binder restart or a service death mid-restore must not silently lose the record.
     */
    public void restoreDeviceStates(Context context, String reason) {
        restoreDeviceStates(context, reason, null);
    }

    /**
     * @param onlyKeys when non-null, restricts the reversion to those toggles. Used for maintenance
     *                 windows, which bring the radios back briefly but must leave the sensors off
     *                 (they are only re-disabled when a fresh Doze cycle starts).
     */
    public void restoreDeviceStates(Context context, String reason, Set<String> onlyKeys) {
        Set<String> pending = dozeStateStore.getAppliedKeys();
        if (onlyKeys != null) {
            pending.retainAll(onlyKeys);
        }
        if (pending.isEmpty()) {
            return;
        }
        Log.i(TAG, "DEVICE_STATE_RESTORE_STARTED reason=" + reason + " keys=" + pending);
        wakeTiming("device_state_restore_started");

        Context appContext = context.getApplicationContext();
        int dispatched = 0;
        for (final String key : pending) {
            // One outstanding command per key. Without this, SCREEN_ON followed closely by
            // USER_PRESENT would send two commands for the same radio.
            if (!stateRestoreInFlight.add(key)) {
                log("RESTORE_PENDING " + key + " (already in flight)");
                continue;
            }
            Log.i(TAG, "RESTORE_PENDING " + key);
            try {
                performRestore(appContext, key, (commandCode, exitCode, stdout, stderr) ->
                        onRestoreFinished(key, exitCode));
                dispatched++;
            } catch (Exception e) {
                stateRestoreInFlight.remove(key);
                Log.e(TAG, "RESTORE_FAILED " + key + " error=" + e.getClass().getSimpleName());
            }
        }
        Log.i(TAG, "DEVICE_STATE_RESTORE_DISPATCHED count=" + dispatched);
        wakeTiming("device_state_restore_dispatched");
    }

    /**
     * Clears the durable marker only when the privileged command actually succeeded. A failure
     * leaves it pending on purpose, so the next trigger - USER_PRESENT, a Shizuku reconnect, the
     * next service start or the next boot - picks it up again.
     */
    private void onRestoreFinished(String key, int exitCode) {
        try {
            if (exitCode == 0) {
                dozeStateStore.clearApplied(key);
                Log.i(TAG, "RESTORE_SUCCESS " + key + " exit=0");
            } else {
                Log.e(TAG, "RESTORE_FAILED " + key + " exit=" + exitCode + ", marker kept for retry");
            }
        } finally {
            stateRestoreInFlight.remove(key);
            wakeTiming("device_state_restore_finished:" + key);
        }
    }

    /**
     * Biometrics are unlock-critical, so they are restored as early as possible on SCREEN_ON and
     * outside the waitForUnlock branch - waiting for ACTION_USER_PRESENT to re-enable the very
     * sensor needed to produce ACTION_USER_PRESENT would be circular.
     * <p>
     * The durable marker is cleared in the completion callback rather than up front. The previous
     * code cleared it first and then issued the command, so a failed or dropped command left the
     * user with biometric unlock disabled and no record that it still needed restoring.
     */
    private void restoreBiometricsForUnlock(Context context) {
        if (!dozeStateStore.isApplied(DozeStateStore.KEY_BIOMETRICS)) {
            return;
        }
        if (!stateRestoreInFlight.add(DozeStateStore.KEY_BIOMETRICS)) {
            return;
        }
        Log.i(TAG, "RESTORE_PENDING " + DozeStateStore.KEY_BIOMETRICS + " (unlock-critical)");
        setBiometricsSensorState(context, true, (commandCode, exitCode, stdout, stderr) ->
                onRestoreFinished(DozeStateStore.KEY_BIOMETRICS, exitCode));
    }

    /**
     * Cancels a delayed enterDoze if one is armed. Timer.cancel() makes the instance unusable, but
     * every scheduling site already builds a fresh Timer, so this is safe to call at any time.
     */
    private void cancelPendingEnterDoze() {
        try {
            if (enterDozeTimer != null) {
                enterDozeTimer.cancel();
                enterDozeTimer = new Timer();
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not cancel the pending enterDoze timer: " + e.getMessage());
        }
    }

    private void performRestore(Context context, String key, Shell.OnCommandResultListener2 done) {
        switch (key) {
            case DozeStateStore.KEY_AIRPLANE:
                setAirplaneState(context, dozeStateStore.getPreDozeValue(key, false), done);
                break;
            case DozeStateStore.KEY_BLUETOOTH:
                setBluetoothState(context, dozeStateStore.getPreDozeValue(key, true), done);
                break;
            case DozeStateStore.KEY_GPS:
                setGPSState(context, dozeStateStore.getPreDozeValue(key, true), done);
                break;
            case DozeStateStore.KEY_WIFI:
                setWiFiState(dozeStateStore.getPreDozeValue(key, true), done);
                break;
            case DozeStateStore.KEY_MOBILE_DATA:
                setMobileDataState(dozeStateStore.getPreDozeValue(key, true), done);
                break;
            case DozeStateStore.KEY_BATTERY_SAVER:
                setBatterSaverState(context, dozeStateStore.getPreDozeValue(key, false), done);
                break;
            case DozeStateStore.KEY_ALL_SENSORS:
                setAllSensorsState(context, dozeStateStore.getPreDozeValue(key, true), done);
                break;
            case DozeStateStore.KEY_BIOMETRICS:
                setBiometricsSensorState(context, dozeStateStore.getPreDozeValue(key, true), done);
                break;
            case DozeStateStore.KEY_MOTION_SENSORS:
                // The auto-rotate workaround is fire-and-forget on its own thread and must not
                // decide whether the sensor marker can be cleared.
                executeCommand("dumpsys sensorservice enable", done, false);
                autoRotateBrightnessFix();
                break;
            default:
                Log.e(TAG, "Unknown state key: " + key);
                notifyCommandFinished(done, -1);
                break;
        }
    }

    public void handleScreenOn(Context context, int time, int delay) {
        log("handleScreenOn");
        log("Last known Doze state: " + lastKnownState);

        if (tempWakeLock != null) {
            if (tempWakeLock.isHeld()) {
                log("Releasing ForceDozeTempWakelock");
                tempWakeLock.release();
            }
        }

        dozeStateStore.setInDoze(false);
        // A maintenance window cannot outlive the screen turning on
        maintenance = false;
        // Always drop a delayed enterDoze: it used to be cancelled only when the device was found
        // ACTIVE, so turning the screen on during the delay could still let Doze fire afterwards.
        cancelPendingEnterDoze();

        // Packages were already dispatched straight from ACTION_SCREEN_ON; this call is the
        // recovery path for the routes that reach handleScreenOn without one (USER_PRESENT with
        // waitForUnlock, charger connect) and no-ops when the record is already clear.
        restoreSuspendedPackages("handleScreenOn");
        reEnableBlockedNotifications();
        restoreDeviceStates(context, "screen on");

        String newDeviceIdleState = getDeviceIdleState();
        if (!newDeviceIdleState.equals("ACTIVE") || !lastKnownState.equals("ACTIVE")) {
            log("Exiting Doze");
            exitDoze(newDeviceIdleState);
        } else {
            if (ignoreLockscreenTimeout) {
                log("Cancelling enterDoze() because user turned on screen and " + (delay) + "ms has not passed OR disableWhenCharging=true");
            } else {
                log("Cancelling enterDoze() because user turned on screen and " + (time) + "ms has not passed OR disableWhenCharging=true");
            }
            // Ensure apps in dozeAppBlocklist are re-enabled even when device is already ACTIVE
            restoreSuspendedPackages("handleScreenOn/active");
            reEnableBlockedNotifications();
        }
    }

    class DozeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, Intent intent) {
            final String action = intent != null ? intent.getAction() : null;
            if (action == null) {
                return;
            }

            int time = Settings.Secure.getInt(getContentResolver(), "lock_screen_lock_after_timeout", 5000);
            if (time == 0) {
                time = 1000;
            }
            int delay = dozeEnterDelay * 1000;
            time = time + delay;

            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                log("airplane mode changed " + Utils.isAirplaneEnabled(getContentResolver()));
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                log("UNLOCK received " + waitForUnlock);
                // Recovery only: SCREEN_ON already dispatched the un-suspend. Both of these are
                // no-ops when there is nothing left pending, and the in-flight guards collapse a
                // SCREEN_ON/USER_PRESENT pair into a single batch.
                restoreSuspendedPackages("user present");
                if (waitForUnlock) {
                    handleScreenOn(context, time, delay);
                } else {
                    restoreDeviceStates(context, "user present");
                }
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                wakeStartedAt = SystemClock.elapsedRealtime();
                wakeTiming("screen_on");
                log("Screen ON received" + waitForUnlock);

                // Everything below runs before the waitForUnlock decision on purpose. The user is
                // looking at the launcher now; suspended apps must not stay greyed out until they
                // happen to unlock, and biometrics must be back before the unlock is attempted.
                cancelPendingEnterDoze();
                restoreSuspendedPackages("screen on");
                restoreBiometricsForUnlock(context);

                if (!Utils.isDeviceLocked(context) || !waitForUnlock) {
                    handleScreenOn(context, time, delay);
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                log("Screen OFF received");
                if (disableWhenCharging && Utils.isConnectedToCharger(getApplicationContext())) {
                    log("Connected to charger and disableWhenCharging=true, skip entering Doze");
                } else if (Utils.isUserInCommunicationCall(context)) {
                    log("User is in a VOIP call or an audio/video chat, skip entering Doze");
                } else if (Utils.isUserInCall(context)) {
                    log("User is in a phone call, skip entering Doze");
                } else {
                    log("Doze delay: " + delay + "ms");
                    if (ignoreLockscreenTimeout) {
                        if (dozeEnterDelay == 0) {
                            log("Ignoring lockscreen timeout value and entering Doze immediately");
                            enterDoze(context);
                        } else {
                            log("Waiting for " + (delay) + "ms and then entering Doze");
                            tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:tempWakelock");
                            log("Acquiring temporary wakelock (ForceDozeTempWakelock)");
                            tempWakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
                            enterDozeTimer = new Timer();
                            enterDozeTimer.schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    enterDoze(context);
                                }
                            }, delay);
                        }
                    } else {
                        log("Waiting for " + (time) + "ms and then entering Doze");
                        if (Utils.isLockscreenTimeoutValueTooHigh(getContentResolver())) {
                            tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:tempWakelock");
                            log("Acquiring temporary wakelock (ForceDozeTempWakelock)");
                            tempWakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
                        }
                        enterDozeTimer = new Timer();
                        enterDozeTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                enterDoze(context);
                            }
                        }, time);
                    }

                }
            } else if (action.equals(Intent.ACTION_POWER_CONNECTED)) {
                String newDeviceIdleState = getDeviceIdleState();
                if (disableWhenCharging && (newDeviceIdleState.equals("IDLE") || !Utils.isScreenOn(context)) ) {
                    log("Charger connected, exiting Doze mode");
                    enterDozeTimer.cancel();
                    exitDoze(newDeviceIdleState);
                }
            } else if (action.equals(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)) {
                if (!Utils.isScreenOn(context)) {
                    log("ACTION_DEVICE_IDLE_MODE_CHANGED received");
                    // Leaving deep idle while the screen is still off means a maintenance window.
                    // Reading it from PowerManager works on every backend - the old code compared
                    // against a dumpsys-derived string that was never filled in Shizuku mode, so
                    // maintenance windows went unnoticed there.
                    boolean inDeepIdle = pm.isDeviceIdleMode();
                    lastKnownState = getDeviceIdleState();
                    log("Current (Deep) state: " + lastKnownState + ", deep idle: " + inDeepIdle);
                    if (!inDeepIdle) {
                        if (!maintenance) {
                            log("Device exited Doze for maintenance");
                            if (!disableStats) {
                                dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("EXIT_MAINTENANCE"));
                                saveDozeDataStats();
                            }

                            restoreDeviceStates(context, "Doze maintenance window", MAINTENANCE_RESTORE_KEYS);
                            maintenance = true;
                        }
                    } else if (lastKnownState.equals("IDLE")) {
                        if (maintenance) {
                            log("Device entered Doze after maintenance");
                            if (!disableStats) {
                                dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("ENTER_MAINTENANCE"));
                                saveDozeDataStats();
                            }
                            enterDozeHandleNetwork(context);
                            maintenance = false;
                        }
                    }
                }
            } else if (action.equals("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED")) {
                if (!Utils.isScreenOn(context)) {
                    log("LIGHT_DEVICE_IDLE_MODE_CHANGED received");
                    lastKnownState = getDeviceIdleState();
                    log("Current (Light) state: " + lastKnownState);
                }
            }
        }
    }

}
