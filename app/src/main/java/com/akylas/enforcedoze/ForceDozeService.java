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
     * Serialises every package suspend/un-suspend, so the newest lifecycle event decides the final
     * physical state. Replaces the old drop-duplicates AtomicBoolean, which could not express
     * "apply the newest instead of the one already running". Same shape as the All Sensors
     * serializer, plus session identity: an operation belongs to one Doze generation and may never
     * be collapsed into, or replaced by, an operation from a different one.
     */
    private final Object packageOpLock = new Object();
    private PackageOp inFlightPackageOp = null;
    private boolean inFlightPackageFinal = false;
    private PackageOp pendingPackageOp = null;
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
    /**
     * ACTION_USER_PRESENT gets its own receiver so it can be registered RECEIVER_EXPORTED without
     * exporting the rest of the screen/Doze actions. It is a protected system broadcast, so this
     * receiver deliberately carries no other action - nothing unprotected is reachable through it.
     */
    BroadcastReceiver userPresentReceiver;
    /** Watches cellular and VoIP call state so a call can release an active Doze session. */
    CallStateWatcher callStateWatcher;

    /**
     * Serialises every write to the global All Sensors toggle, so the newest lifecycle event always
     * decides the final physical state. Without it the temporary lock-screen enable and the
     * screen-off re-disable would be two independent Shizuku threads carrying opposite values.
     * Latest-wins with a single pending slot; no timers and no sleeps.
     */
    private final Object sensorOpLock = new Object();
    private boolean sensorOpInFlight = false;
    private Boolean pendingSensorTarget = null;
    private Shell.OnCommandResultListener2 pendingSensorCallback = null;
    private String pendingSensorLabel = null;
    /** True while sensors are temporarily enabled for lock-screen use during an owned session. */
    private boolean lockscreenSensorOverrideActive = false;

    /**
     * Biometrics get their own serializer, deliberately not sharing the sensor slot: coalescing is
     * per physical toggle, and one shared slot would let a sensor write cancel a biometric write.
     * It became necessary once SCREEN_OFF started issuing a disable - before that only the unlock
     * restore ever wrote biometrics, so the stateRestoreInFlight guard was enough. With two actors
     * carrying opposite targets on independent Shizuku threads, "drop the duplicate" cannot express
     * "apply the newest instead".
     */
    private final Object biometricOpLock = new Object();
    private boolean biometricOpInFlight = false;
    private Boolean pendingBiometricTarget = null;
    private Shell.OnCommandResultListener2 pendingBiometricCallback = null;
    private String pendingBiometricLabel = null;
    /**
     * Latched for the duration of one call so the telephony callback, the audio-mode callback and
     * the screen-on fallback together produce a single Doze exit rather than three.
     */
    private final AtomicBoolean callDozeExitDone = new AtomicBoolean(false);
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
    /**
     * Reported to a sensor request that was still queued when a newer one replaced it. Non-zero on
     * purpose: it releases stateRestoreInFlight without letting the caller clear a durable marker
     * for work that never ran.
     */
    private static final int SUPERSEDED_EXIT_CODE = -3;
    /** Reported for a package operation whose Doze generation has already been replaced. */
    private static final int STALE_GENERATION_EXIT_CODE = -4;

    /**
     * Phase of the privileged physical Doze entry, i.e. of an actual
     * {@code dumpsys deviceidle force-idle deep} transaction. Only ever meaningful for the
     * root/Shizuku backend; the unprivileged tunable fallback performs no immediate physical
     * transition and never leaves this at anything but {@link #PHASE_NONE}.
     */
    private static final int PHASE_NONE = 0;
    private static final int PHASE_ATTEMPTING = 1;
    private static final int PHASE_CLEANING_UP = 2;

    /**
     * Serializes the four things that must not interleave: lifecycle invalidation of a pending
     * entry, the force callback's stale/current decision, the durable PREPARING to ACTIVE commit,
     * and every phase transition.
     * <p>
     * Without it the callback could verify success, find itself current, and only then be overtaken
     * by SCREEN_ON - committing inDoze=true after the wake had already been processed. Nothing that
     * blocks is done while holding it: verification and the "still wanted" re-check run before it
     * is taken, and every shell command is dispatched after it is released. Correctness does not
     * depend on those pre-checks, because {@link #invalidateDesiredEntry(String)} bumps
     * {@link #entryAttemptToken} under this same monitor and that is what the callback compares.
     */
    private final Object physicalEntryLock = new Object();
    private int physicalEntryPhase = PHASE_NONE;

    /**
     * Identifies one privileged fresh force attempt. Deliberately separate from the ACTIVE logical
     * session identity: a locked SCREEN_ON with waitForUnlock invalidates any pending fresh entry
     * while the session already owned continues to live.
     */
    private int entryAttemptToken = 0;

    /**
     * Whether the attempt currently outstanding is allowed to trigger the single post-cleanup
     * policy re-evaluation. False for an attempt that was itself started by one, so a re-entry can
     * never chain into another. Guarded by {@link #physicalEntryLock}.
     */
    private boolean entryAttemptAllowsReentry = true;

    /**
     * Set once onDestroy() begins, so a cleanup callback that lands during teardown cannot start a
     * new entry on a service that is going away.
     */
    private volatile boolean serviceStopping = false;
    private static final String SENSOR_LABEL_LOCKSCREEN_RESTORE = "lockscreen_restore";
    private static final String SENSOR_LABEL_LOCKSCREEN_REAPPLY = "lockscreen_reapply";
    private static final String SENSOR_LABEL_ENTER = "doze_enter";
    private static final String SENSOR_LABEL_FINAL = "final_restore";
    private static final String BIOMETRIC_LABEL_LOCKSCREEN_RESTORE = "biometric_lockscreen_restore";
    private static final String BIOMETRIC_LABEL_LOCKSCREEN_REAPPLY = "biometric_lockscreen_reapply";
    private static final String BIOMETRIC_LABEL_FINAL = "biometric_final_restore";
    private static final String BIOMETRIC_LABEL_ENTER = "biometric_doze_enter";
    /**
     * Lowest API level on which the multi-package {@code pm suspend a b c} form is trusted. Below
     * this the compatibility loop is used directly rather than probing with a batch that older
     * PackageManagerShellCommand builds may silently mishandle.
     */
    private static final int MULTI_PACKAGE_PM_MIN_SDK = 36;
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
        DiagnosticLogger.i("WAKE_TIMING", event + " +" + elapsed + "ms");
    }

    public ForceDozeService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localDozeReceiver = new DozeReceiver();
        userPresentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    handleUserPresent(context);
                }
            }
        };
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
        // ACTION_USER_PRESENT is handled by userPresentReceiver, which is registered separately
        // and exported; see registerUserPresentReceiver().
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
        registerUserPresentReceiver();
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
            DiagnosticLogger.i("SHIZUKU", "availability_changed value=" + value);
            if (value) {
                onShizukuBecameAvailable();
            }
        };
        shizukuHandler.addOnAvailabilityChangeListener(shizukuAvailabilityListener);
        if (useShizuku) {
            shizukuHandler.checkShizukuAvailability();
            isShizukuAvailable = shizukuHandler.isShizukuAvailable();
            log("Shizuku mode enabled, available: " + isShizukuAvailable);
            DiagnosticLogger.i("APP", "service onCreate executionMode=shizuku available="
                    + isShizukuAvailable + " sdk=" + Build.VERSION.SDK_INT);
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

        // Deliberately last. A call callback goes straight to exitDoze(), which touches the
        // settings, the blocklists, the statistics set and the shell backend - all of which are
        // initialised above. Starting the watcher in the receiver block, as it was, meant an
        // immediate callback could reach them while they were still null.
        startCallStateWatcher();
    }

    /** unregisterReceiver throws if the receiver was never registered; onDestroy must not crash. */
    private void unregisterReceiverQuietly(BroadcastReceiver receiver) {
        if (receiver == null) {
            return;
        }
        try {
            this.unregisterReceiver(receiver);
        } catch (Exception e) {
            log("Receiver was not registered: " + e.getMessage());
        }
    }

    private void startCallStateWatcher() {
        callStateWatcher = new CallStateWatcher();
        callStateWatcher.start(getApplicationContext(), new CallStateWatcher.Listener() {
            @Override
            public void onCallActive(String reason) {
                handleCallStarted(reason);
            }

            @Override
            public void onCallEnded() {
                handleCallEnded();
            }
        });
    }

    /**
     * A call outranks waitForUnlock.
     * <p>
     * The existing protection only covered "a call is already running when the screen goes off, so
     * do not enter Doze". The opposite order was unhandled: with waitForUnlock=true the device
     * deliberately keeps its Doze state applied until ACTION_USER_PRESENT, so answering a call
     * from the lock screen left the proximity sensor disabled for the whole call - the user had to
     * unlock the phone to make their own call work properly.
     * <p>
     * Releasing the session here goes through the ordinary {@link #exitDoze(String)} transition, so
     * packages, notifications, sensors and radios are all restored from the durable journal exactly
     * as they are on a normal wake, and the EXIT statistics stay consistent. Nothing is cleared
     * ahead of its command callback.
     */
    private void handleCallStarted(String reason) {
        if (!callDozeExitDone.compareAndSet(false, true)) {
            return;
        }

        // Unconditional, and before the "nothing to release" return below. A call can begin during
        // the dozeEnterDelay window, when no session has been applied yet and nothing is journalled
        // - the old ordering returned early and left the TimerTask armed, so with no screen wake to
        // cancel it (a Bluetooth headset, watch or Android Auto answer) Doze would start in the
        // middle of the call. Cancelling touches no journal markers.
        cancelPendingEnterDoze();
        boolean wakelockReleased = releaseTempWakeLock();
        DiagnosticLogger.i("CALL", "pending_doze_cancelled wakelockReleased=" + wakelockReleased);

        boolean inDoze = dozeStateStore.isInDoze();
        boolean pending = dozeStateStore.hasPendingRestore()
                || dozeStateStore.hasAppliedSuspendedPackages();
        if (!inDoze && !pending) {
            log("Call started (" + reason + ") but no Doze session is owned, nothing to release");
            return;
        }

        log("Call started (" + reason + "), releasing the Doze session without waiting for unlock");
        DiagnosticLogger.i("CALL", "doze_exit_for_call reason=" + reason
                + " inDoze=" + inDoze + " pendingStates=" + dozeStateStore.getAppliedKeys()
                + " suspendedPackages=" + dozeStateStore.getAppliedSuspendedPackages().size());

        // A maintenance window cannot survive the session ending.
        maintenance = false;
        DiagnosticLogger.i("CALL", "restore_dispatched keys=" + dozeStateStore.getAppliedKeys());

        if (inDoze) {
            exitDoze(getDeviceIdleState());
        } else {
            // Reached with pending markers but no owned session, i.e. an earlier restore failed.
            // Release them, but do not invent a session boundary in the statistics.
            DiagnosticLogger.i("CALL", "pending_release_without_exit_row");
            restoreSuspendedPackages("call started");
            reEnableBlockedNotifications();
            restoreDeviceStates(getApplicationContext(), "call started");
        }
    }

    /**
     * On call end the screen is usually still on, in which case staying ACTIVE is right and the
     * ordinary SCREEN_OFF path will start the next session. When the call ended with the screen
     * already off - ended at the ear, proximity still holding the screen down - there will be no
     * further SCREEN_OFF event, so a fresh cycle is started here using the same preconditions the
     * SCREEN_OFF handler applies.
     */
    private void handleCallEnded() {
        callDozeExitDone.set(false);
        boolean screenOn = Utils.isScreenOn(getApplicationContext());
        DiagnosticLogger.i("CALL", "call_ended screenOn=" + screenOn);

        if (screenOn) {
            log("Call ended with the screen on, staying ACTIVE");
            return;
        }
        if (disableWhenCharging && Utils.isConnectedToCharger(getApplicationContext())) {
            log("Call ended with the screen off but charging and disableWhenCharging=true");
            return;
        }
        if (Utils.isUserInCommunicationCall(getApplicationContext()) || Utils.isUserInCall(getApplicationContext())) {
            log("Call ended but another call is still active, not entering Doze");
            return;
        }
        if (dozeStateStore.hasAppliedSuspendedPackages() || dozeStateStore.hasPendingRestore()) {
            // The previous session has not finished unwinding. Entering again would let
            // suspendPackagesForDoze() overwrite the journal with the current blocklist and lose
            // any package the failed restore still owes the user. Leave it to the next SCREEN_OFF.
            log("Call ended but the previous Doze journal is still pending, not entering Doze");
            DiagnosticLogger.w("CALL", "doze_reentry_skipped reason=pending_restore"
                    + " pendingStates=" + dozeStateStore.getAppliedKeys()
                    + " suspendedPackages=" + dozeStateStore.getAppliedSuspendedPackages().size());
            return;
        }
        log("Call ended with the screen off, starting a fresh Doze cycle");
        DiagnosticLogger.i("CALL", "doze_reentry_after_call");
        enterDoze(this);
    }

    /**
     * Registers the unlock receiver on its own, exported on API 33+.
     * <p>
     * The combined receiver was registered RECEIVER_NOT_EXPORTED, and on the S26 Ultra
     * ACTION_USER_PRESENT never arrived while ACTION_SCREEN_ON on the same registration did. The
     * documented guidance for framework broadcasts is RECEIVER_EXPORTED, and the way to apply it
     * without loosening the rest of the Doze actions is to partition the broadcast into its own
     * receiver. Nothing unprotected is registered here, so exporting it grants no new surface:
     * ACTION_USER_PRESENT can only be sent by the system.
     */
    private void registerUserPresentReceiver() {
        IntentFilter userPresentFilter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        boolean exported = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.registerReceiver(userPresentReceiver, userPresentFilter, Context.RECEIVER_EXPORTED);
            exported = true;
        } else {
            this.registerReceiver(userPresentReceiver, userPresentFilter);
        }
        log("Registered ACTION_USER_PRESENT receiver, exported=" + exported);
        DiagnosticLogger.i("WAKE", "user_present_receiver_registered exported=" + exported);
    }

    /**
     * The single home of the unlock handling. Unchanged in behaviour from the branch it replaces in
     * DozeReceiver; only the delivery route is different.
     */
    private void handleUserPresent(Context context) {
        int time = Settings.Secure.getInt(getContentResolver(), "lock_screen_lock_after_timeout", 5000);
        if (time == 0) {
            time = 1000;
        }
        int delay = dozeEnterDelay * 1000;
        time = time + delay;

        log("UNLOCK received " + waitForUnlock);
        DiagnosticLogger.i("WAKE", "user_present waitForUnlock=" + waitForUnlock);
        // The full restore below owns the sensor state from here on.
        cancelLockscreenSensorOverride("user_present");
        // Recovery only: SCREEN_ON already dispatched the un-suspend. Both of these are
        // no-ops when there is nothing left pending, and the in-flight guards collapse a
        // SCREEN_ON/USER_PRESENT pair into a single batch.
        restoreSuspendedPackages("user present");
        if (waitForUnlock) {
            handleScreenOn(context, time, delay);
        } else {
            restoreDeviceStates(context, "user present");
        }
    }

    /**
     * Shizuku coming back is a recovery opportunity: anything that failed while it was gone is
     * still recorded and can be retried now.
     * <p>
     * It is only an opportunity when the Doze session is actually over, though. A reconnect at
     * 03:00 with the screen off and inDoze still true must leave every marker alone - restoring
     * the radios there would end the Doze session the user asked for. This gate is the same one
     * {@link #recoverAfterServiceRecreation()} applies.
     */
    private void onShizukuBecameAvailable() {
        applyRecoveryPolicy("SHIZUKU_RECOVERY", "Shizuku became available");
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
        boolean entryPending = dozeStateStore.isEntryPending();

        // A durable inDoze flag is itself something to recover, independently of any package or
        // device-state marker. A configuration that only forces idle - no Hard Suspend blocklist
        // and no optional radio/sensor restrictions - owns a logical session with neither marker
        // set, and the old condition returned RECOVERY_NONE for it, so applyRecoveryPolicy and its
        // Mode C finalization were never reached and the session was left owned with no EXIT.
        // entryPending is a fourth independent reason to recover: an interrupted force-idle owns no
        // session and no marker, so without this term the one state that can leave the device
        // physically forced would return RECOVERY_NONE and never be resolved.
        if (!inDoze && !hasPackages && !hasStates && !entryPending) {
            log("RECOVERY_NONE: service recreated with nothing pending");
            return;
        }

        DiagnosticLogger.i("RECOVERY", "RECOVERY_CHECK screenOn=" + screenOn + " inDoze=" + inDoze
                + " entryPending=" + entryPending
                + " pendingPackages=" + (hasPackages ? dozeStateStore.getAppliedSuspendedPackages().size() : 0)
                + " pendingStates=" + dozeStateStore.getAppliedKeys());
        log("RECOVERY_CHECK screenOn=" + screenOn + " inDoze=" + inDoze
                + " pendingPackages=" + (hasPackages ? dozeStateStore.getAppliedSuspendedPackages().size() : 0)
                + " pendingStates=" + dozeStateStore.getAppliedKeys());

        applyRecoveryPolicy("RECOVERY", "service recreated");
    }


    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Before anything below can dispatch a command whose callback might want to start a new
        // entry on a service that is going away.
        serviceStopping = true;
        log("Stopping service and enabling sensors");
        DiagnosticLogger.i("APP", "onDestroy");
        unregisterReceiverQuietly(localDozeReceiver);
        unregisterReceiverQuietly(userPresentReceiver);
        if (callStateWatcher != null) {
            callStateWatcher.stop();
        }
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
        // An explicit stop differs from ordinary process death only in that code still runs here:
        // the desired entry can be cancelled, and the physical undo can at least be dispatched.
        // entryPending is deliberately NOT cleared - the shell backends are torn down a few lines
        // below and the unforce may never complete, so the durable marker has to survive as the
        // record of that debt. The next service start resolves it through applyRecoveryPolicy().
        // Nothing here blocks the main thread waiting for it, and no ENTER or EXIT row is written
        // for an attempt that never became a session.
        invalidateDesiredEntry("service destroyed");
        if (dozeStateStore.isEntryPending()) {
            cleanupPendingPhysicalForce("service destroyed");
        }

        // Put back everything we changed for Doze; without this a service stopped while dozing
        // would leave airplane mode on and the sensors off with nobody left to revert them.
        restoreSuspendedPackages("service destroyed");
        restoreDeviceStates(getApplicationContext(), "service destroyed");
        //ensure we exit doze if stopped from background
        if (dozeStateStore.isInDoze()) {
            exitDoze(getDeviceIdleState());
        } else {
            // No session was owned, so stopping the service must not append an EXIT row that
            // pairs with somebody else's ENTER. The restores above already ran.
            log("Service destroyed without an owned Doze session, no EXIT recorded");
        }
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
        DiagnosticLogger.i("APP", "onStartCommand action=" + (intent != null ? intent.getAction() : "null")
                + " flags=" + flags + " startId=" + startId);

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

        // Both of these are safe on a restart and must not be skipped: the foreground notification
        // has a hard deadline after startForegroundService(), and staying on the Doze whitelist is
        // what keeps a recreated service alive.
        ensureForegroundNotification();
        addSelfToDozeWhitelist();

        // START_STICKY brings the service back with a fresh process, so lastKnownState is back to
        // its "null" initial value. enterDoze() tests !lastKnownState.equals("IDLE"), which is
        // therefore true even though the device is already idle, and it would run the whole
        // Doze-entry sequence a second time against a session that is still active: re-suspending
        // packages, re-blocking notifications, another sensor timer, another ENTER stats row, and
        // - worst of all - overwriting appliedSuspendedPackages with the *current* blocklist,
        // which loses any package that was suspended before the blocklist was edited.
        //
        // Resuming an existing session means taking ownership of it, not starting it again.
        boolean screenOn = Utils.isScreenOn(getApplicationContext());
        boolean persistedInDoze = dozeStateStore.isInDoze();
        if (!screenOn && persistedInDoze) {
            lastKnownState = getDeviceIdleState();
            DiagnosticLogger.i("RECOVERY", "RECOVERY_RESUME_ACTIVE_DOZE state=" + lastKnownState
                    + " suspendedPackages=" + dozeStateStore.getAppliedSuspendedPackages().size());
            log("RECOVERY_RESUME_ACTIVE_DOZE: screen off and inDoze persisted, resuming the "
                    + "existing session without re-entering Doze (state=" + lastKnownState
                    + ", suspendedPackages=" + dozeStateStore.getAppliedSuspendedPackages().size() + ")");
            Utils.hideDisabledNotification(getApplicationContext());
            Utils.updateTileState(getApplicationContext());
            return START_STICKY;
        }

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
        DiagnosticLogger.i("RECOVERY", "ACTION_RESTORE_STATE received");
        dozeStateStore.setInDoze(false);
        // ACTION_RESTORE_STATE means "put back everything EnforceDoze owns", so the persisted
        // suspended packages are part of it. Restoring only the radios left a device that booted
        // mid-Doze with its blocklisted apps still greyed out.
        restoreSuspendedPackages("restore requested");
        reEnableBlockedNotifications();
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
        executeCommandWithRoot("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.READ_PHONE_STATE",
                (commandCode, exitCode, stdout, stderr) -> {
                    if (exitCode == 0) {
                        onReadPhoneStateGranted();
                    }
                }, true);
    }

    public void grantReadPhoneStatePermissionViaShizuku() {
        log("Granting android.permission.READ_PHONE_STATE to " + BuildConfig.APPLICATION_ID + " via Shizuku");
        shizukuHandler.executeCommand("pm grant " + BuildConfig.APPLICATION_ID + " android.permission.READ_PHONE_STATE",
            (commandCode, exitCode, stdout, stderr) -> {
                if (exitCode == 0) {
                    log("READ_PHONE_STATE permission granted successfully");
                    onReadPhoneStateGranted();
                }
            }, true);
    }

    /**
     * On a fresh install the permission is granted through Shizuku/root some time after the
     * service starts, so the telephony half of the call watcher would otherwise stay unregistered
     * for the life of the process. Registration is idempotent, so this cannot double-register.
     */
    private void onReadPhoneStateGranted() {
        if (callStateWatcher != null) {
            callStateWatcher.ensureTelephonyRegistered();
        }
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

    /**
     * Kept as a no-argument overload so callers that do not care about the outcome - including the
     * unreachable legacy {@code PendingIntentDozeReceiver}, which is left in place for a separate
     * cleanup commit - continue to compile and behave exactly as before.
     */
    public void applyDoze() {
        applyDoze(null);
    }

    /**
     * @param onForceResult invoked with the raw shell result of the privileged force-idle command,
     *                      or never at all on the unprivileged tunable path, which performs no
     *                      single physical transaction whose result could be reported. Callers must
     *                      therefore only pass a listener after checking
     *                      {@link #isPrivilegedForceIdleBackend()}.
     */
    public void applyDoze(Shell.OnCommandResultListener2 onForceResult) {
        if (Utils.isDeviceRunningOnN()) {
            if (isSuAvailable || isShizukuAvailable) {
                // printOutput stays true so the existing logcat echo is unchanged; the callback
                // only adds the exit code to the diagnostic file.
                executeCommandWithRoot("dumpsys deviceidle force-idle deep",
                        (commandCode, exitCode, stdout, stderr) -> {
                            DiagnosticLogger.i("DOZE", "force_idle_deep exit=" + exitCode);
                            if (onForceResult != null) {
                                onForceResult.onCommandResult(commandCode, exitCode, stdout, stderr);
                            }
                        }, true);
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
                            DiagnosticLogger.i("DOZE", "DOZE_UNFORCE_FINISHED exit=" + exitCode);
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
        enterDoze(context, true);
    }

    /**
     * @param allowPostCleanupReentry false when this entry is itself the single re-evaluation that
     *                                follows a physical cleanup, so one re-entry can never chain
     *                                into another
     */
    private void enterDoze(Context context, boolean allowPostCleanupReentry) {
        // Defence in depth for the delayed path: SCREEN_OFF checks for a call before scheduling,
        // but the call may begin during the delay, and this TimerTask would otherwise apply the
        // whole Doze setup underneath it. Checked before anything is suspended, journalled or
        // counted, so a skip leaves no trace to unwind.
        if (isCallActiveNow()) {
            log("Call is active, skip entering Doze");
            DiagnosticLogger.i("CALL", "doze_entry_skipped reason=active_call");
            return;
        }
        if (!Utils.isInsideCustomDozePeriod(context)) {
            log("Outside custom Doze periods, skip entering Doze");
            return;
        }
        // Defence in depth against a stale TimerTask armed before this session existed: a session
        // already owned is continued by resumeOwnedDozeAfterLockedWake(), never re-entered here.
        if (dozeStateStore.isInDoze()) {
            log("A Doze session is already owned, skipping fresh entry");
            DiagnosticLogger.i("DOZE", "fresh_entry_skipped reason=session_already_owned");
            return;
        }
        if (getDeviceIdleState().equals("IDLE") && lastKnownState.equals("IDLE")) {
            log("enterDoze() received but skipping because device is already Dozing");
            return;
        }
        if (Utils.isScreenOn(context)) {
            log("Screen is on, skip entering Doze");
            return;
        }

        if (isPrivilegedForceIdleBackend()) {
            // The force is a real transaction whose success has to be established before anything
            // is suspended or journalled, so the whole entry moves behind its callback.
            beginPrivilegedFreshEntry(context, allowPostCleanupReentry);
            return;
        }

        // Unprivileged tunable fallback. It writes device_idle constants rather than performing an
        // immediate force-idle transition, so there is no single result to verify and nothing that
        // could be left forced across a process death. Its ordering is deliberately identical to
        // what it has always been.
        lastKnownState = "IDLE";
        releaseTempWakeLock();
        applyEntryPackageAndNotificationBlocking(context);
        timeEnterDoze = System.currentTimeMillis();
        if (Utils.isConnectedToCharger(getApplicationContext())) {
            lastDozeEnterBatteryLife = 0;
        } else {
            lastDozeEnterBatteryLife = Utils.getBatteryLevel(getApplicationContext());
        }
        log("Entering Doze");
        DiagnosticLogger.i("DOZE", "enter_doze_start mode=tunable_fallback");
        dozeStateStore.setInDoze(true);
        applyDoze();
        lastScreenOff = Utils.getDateCurrentTimeZone(System.currentTimeMillis());
        recordDozeEnterStats();
        applyEntryMotionAndNetwork(context);
    }

    /**
     * True when Doze entry goes through a real privileged force-idle transaction, as opposed to the
     * unprivileged tunable path or the pre-N command. Only this backend gets the
     * NONE/PREPARING/ACTIVE protocol.
     */
    private boolean isPrivilegedForceIdleBackend() {
        return Utils.isDeviceRunningOnN() && (isSuAvailable || isShizukuAvailable);
    }

    /**
     * Claims the physical-entry slot, records the durable PREPARING marker and dispatches the
     * force. Nothing is suspended, journalled or counted here: on this device
     * "dumpsys deviceidle force-idle deep" answers "Unable to go deep idle; stopped at INACTIVE"
     * with shell exit code 0 whenever an alarm falls inside min_time_to_alarm, and the old code
     * took that for success - starting a whole session, and its statistics, on a device that never
     * left INACTIVE.
     */
    private void beginPrivilegedFreshEntry(Context context, boolean allowPostCleanupReentry) {
        final int token;
        synchronized (physicalEntryLock) {
            if (physicalEntryPhase != PHASE_NONE) {
                log("A physical Doze entry is already outstanding, skipping fresh entry");
                DiagnosticLogger.i("DOZE", "entry_refused reason=attempt_in_flight phase="
                        + phaseName(physicalEntryPhase));
                return;
            }
            // The durable bit gates fresh entry too, not just the in-memory phase: a recreated
            // process starts at PHASE_NONE while a force from the previous process may still be in
            // effect and unaccounted for.
            if (dozeStateStore.isEntryPending()) {
                log("A previous force-idle attempt is still unresolved, skipping fresh entry");
                DiagnosticLogger.i("DOZE", "entry_refused reason=entry_pending_unresolved");
                return;
            }
            if (dozeStateStore.isInDoze()) {
                DiagnosticLogger.i("DOZE", "entry_refused reason=session_already_owned");
                return;
            }
            // The durable record is written before the phase is claimed and before anything is
            // dispatched. An external physical force with no owner on disk is the one outcome this
            // protocol exists to prevent, so a journal failure stops the entry outright rather than
            // proceeding unrecorded. Nothing has been changed yet, so there is nothing to unwind:
            // the phase is still NONE and no token has been consumed.
            if (!dozeStateStore.beginForceIdleAttempt()) {
                log("Could not record the pending force-idle attempt, not entering Doze");
                DiagnosticLogger.e("DOZE", "entry_aborted reason=preparing_journal_write_failed");
                return;
            }
            token = ++entryAttemptToken;
            physicalEntryPhase = PHASE_ATTEMPTING;
            entryAttemptAllowsReentry = allowPostCleanupReentry;
        }

        releaseTempWakeLock();
        log("Entering Doze");
        DiagnosticLogger.i("DOZE", "force_idle_attempt_start mode=fresh token=" + token);
        applyDoze((commandCode, exitCode, stdout, stderr) ->
                onFreshForceIdleResult(context, token, exitCode, stdout, stderr));
    }

    /**
     * Decides what a finished fresh force attempt means. The ordering here is deliberate: physical
     * success is established first and is never short-circuited by staleness, because a force that
     * succeeded has left mForceIdle=true behind and owes an unforce even when nobody wants the
     * session any more.
     */
    private void onFreshForceIdleResult(Context context, int token, int exitCode,
                                        List<String> stdout, List<String> stderr) {
        // Both reads happen before the lock is taken: they are binder calls, and correctness rests
        // on the token comparison below rather than on them.
        boolean physicallyIdle = verifyPhysicalDozeEntered();
        boolean commandCompleted = (exitCode == 0);
        boolean stillWanted = isFreshEntryStillWanted(context);

        // Exit code 0 is necessary but not sufficient. The device probe established one direction
        // only - exit 0 with the device not idle is a semantic refusal - and says nothing about
        // what a non-zero result means. ShizukuHandler reports -1 both when the command could not
        // be started at all and when runCommandOnce threw partway through, so a non-zero result
        // with the device idle cannot be read as a force this app owns, nor safely as one it does
        // not. Those two cases are therefore separated below rather than collapsed into "success".
        String verdict;
        if (commandCompleted && physicallyIdle) {
            verdict = "verified_success";
        } else if (commandCompleted) {
            verdict = "semantic_rejection";
        } else if (!physicallyIdle) {
            verdict = "transport_failure";
        } else {
            verdict = "ambiguous_idle_after_command_failure";
        }
        DiagnosticLogger.i("DOZE", "force_idle_result mode=fresh verdict=" + verdict
                + " exit=" + exitCode + " idleMode=" + physicallyIdle
                + " verifiedBy=exit_code+idle_mode");
        if ("semantic_rejection".equals(verdict)) {
            // The only case with output worth reporting. Never an input to the decision.
            DiagnosticLogger.i("DOZE", "force_idle_rejected stoppedAt="
                    + describeForceIdleFailure(stdout, stderr));
        }

        boolean cleanup = false;
        boolean cleanupAllowsReentry = true;
        boolean abort = false;
        boolean abortJournalClearFailed = false;
        boolean commitFailed = false;
        String abortReason = verdict;
        synchronized (physicalEntryLock) {
            if (physicalEntryPhase == PHASE_CLEANING_UP) {
                // A recovery or shutdown cleanup already owns the debt and has its own unforce out;
                // issuing a second one here would race it for the durable bit.
                DiagnosticLogger.i("DOZE", "entry_result_ignored reason=cleanup_in_flight success="
                        + physicallyIdle);
            } else if (!physicallyIdle) {
                // Semantic rejection or transport failure. Either way the device is not idle, so
                // Android is holding nothing on this app's behalf and there is nothing to undo.
                // The durable bit is cleared inside the lock, before the phase is released, so a
                // fresh entry can never find PHASE_NONE while entryPending is still set and refuse
                // itself for a reason that has already stopped being true.
                abortJournalClearFailed = !dozeStateStore.abortForceIdleAttempt();
                abort = true;
                physicalEntryPhase = PHASE_NONE;
            } else if (!commandCompleted) {
                // Ambiguous: the command did not report completion, yet the device is idle. That
                // idle state may be this app's doing or may not, and it is not a basis for claiming
                // a session. Treated as a possibly-owned physical force and conservatively undone -
                // an unforce on an idle device this app did not force clears a flag that was
                // already false, so the cautious choice is also the cheap one.
                cleanup = true;
                cleanupAllowsReentry = entryAttemptAllowsReentry;
                physicalEntryPhase = PHASE_CLEANING_UP;
            } else if (token != entryAttemptToken || physicalEntryPhase != PHASE_ATTEMPTING
                    || !stillWanted) {
                // Verified success that nobody wants any more. Never dropped: the device really is
                // forced, and only this branch will take it back out.
                cleanup = true;
                cleanupAllowsReentry = entryAttemptAllowsReentry;
                physicalEntryPhase = PHASE_CLEANING_UP;
            } else if (!dozeStateStore.commitDozeSession()) {
                // The force succeeded but the ownership transition did not reach disk, so this is
                // not an ACTIVE session and none of the session work may run: an ENTER row,
                // suspended packages or disabled radios recorded against a session no recovery
                // would find is worse than no session at all. The store has put the local view back
                // to PREPARING, which is what the file still holds, so the device converges the
                // same way an invalidated attempt does - unforce, and leave the marker to be
                // cleared only when that completes. Persistence is not retried here.
                commitFailed = true;
                physicalEntryPhase = PHASE_CLEANING_UP;
            } else {
                physicalEntryPhase = PHASE_NONE;
                DiagnosticLogger.i("DOZE", "entry_committed token=" + token);
                // Deliberately inside the lock. Committing and then setting the session up outside
                // it left a window where SCREEN_ON could see inDoze=true, run a complete owned exit
                // with all its restores, and only then have this thread apply the ENTER row, the
                // package suspension and the notification blocking on top of an already-finished
                // session. A session-epoch check cannot close that: this work is synchronous and
                // the epoch it would compare against is the one just committed.
                //
                // Every path that ends a session calls invalidateDesiredEntry() - directly or via
                // cancelPendingEnterDoze() - before it reads the durable inDoze flag, and that
                // acquires this same monitor. So a lifecycle event either arrives first, in which
                // case the token has moved and this branch is not reached at all, or it waits here
                // and then observes a session that is fully established and restores all of it.
                //
                // Only dispatch happens under the lock; no shell command is waited on. The package
                // and sensor serializers never acquire this monitor, so there is no lock ordering
                // to invert.
                commitFreshDozeSession(context);
            }
        }

        if (abort) {
            // A clean end. Deliberately no retry: an entry refused because a user alarm falls
            // inside min_time_to_alarm must stay refused until the next genuine lifecycle event.
            DiagnosticLogger.i("DOZE", "entry_aborted reason=" + abortReason + " token=" + token);
            if (abortJournalClearFailed) {
                // Nothing was forced, so the phone is in the right state; only the marker is stale.
                // A later recovery will unforce a device that is not forced, which is a no-op.
                DiagnosticLogger.e("DOZE", "entry_cleanup_journal_clear_failed phase=abort");
            }
            return;
        }
        if (commitFailed) {
            DiagnosticLogger.e("DOZE", "entry_commit_failed action=physical_cleanup token=" + token);
            // No automatic re-entry: the storage that just failed is the same storage the next
            // attempt would depend on.
            dispatchPhysicalForceCleanup(false);
            return;
        }
        if (cleanup) {
            String detail = !commandCompleted ? "ambiguous_result"
                    : (stillWanted ? "attempt_superseded" : "precondition_changed");
            DiagnosticLogger.i("DOZE", "entry_cleanup_started reason=possible_owned_force detail="
                    + detail + " token=" + token);
            dispatchPhysicalForceCleanup(cleanupAllowsReentry);
        }
    }

    /**
     * Everything the session owes once, and only once, Android is confirmed to be in deep idle and
     * the durable ACTIVE state is written.
     */
    private void commitFreshDozeSession(Context context) {
        lastKnownState = "IDLE";
        timeEnterDoze = System.currentTimeMillis();
        if (Utils.isConnectedToCharger(getApplicationContext())) {
            lastDozeEnterBatteryLife = 0;
        } else {
            lastDozeEnterBatteryLife = Utils.getBatteryLevel(getApplicationContext());
        }
        lastScreenOff = Utils.getDateCurrentTimeZone(System.currentTimeMillis());
        DiagnosticLogger.i("DOZE", "enter_doze_start mode=privileged");

        // The remaining writes are not transactional with the commit above, and are not claimed to
        // be. A crash between the commit and the ENTER row leaves an owned session whose eventual
        // EXIT row has no partner, which the statistics screen skips; that is cosmetic, and strictly
        // better than the ENTER rows a failed force used to leave behind for ever. A crash later
        // leaves partial journal markers, which is exactly what the journal exists to repair.
        recordDozeEnterStats();
        applyEntryPackageAndNotificationBlocking(context);
        applyEntryMotionAndNetwork(context);
    }

    /**
     * The second of the two conditions a fresh entry must satisfy, not the only one. The device
     * probe confirms that by the time the shell command returns successfully the device is already
     * at mState=IDLE with mForceIdle=true, so one in-process read is enough and nothing is polled.
     * <p>
     * A zero exit code is necessary but not sufficient - it is 0 for a semantic refusal too - and
     * this read is what separates those. It is not sufficient on its own either: paired with a
     * non-zero exit code it means the device is idle for reasons this app cannot attribute to
     * itself, which {@link #onFreshForceIdleResult} treats as ambiguous rather than as success.
     */
    private boolean verifyPhysicalDozeEntered() {
        try {
            return pm != null && pm.isDeviceIdleMode();
        } catch (Exception e) {
            Log.e(TAG, "Could not read device idle mode: " + e.getMessage());
            return false;
        }
    }

    /**
     * Diagnostic only - never an input to the success decision. Reports the state the controller
     * stopped at when it refuses, which is the one genuinely useful detail in the output.
     */
    private String describeForceIdleFailure(List<String> stdout, List<String> stderr) {
        List<String> lines = new ArrayList<>();
        if (stdout != null) {
            lines.addAll(stdout);
        }
        if (stderr != null) {
            lines.addAll(stderr);
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            int at = line.indexOf("stopped at ");
            if (at >= 0) {
                return line.substring(at + "stopped at ".length()).trim();
            }
        }
        return "unknown";
    }

    /**
     * Re-checks the policy that made this entry desirable. Charging is deliberately conditional:
     * with "Disable when charging" off, dozing while on USB power is intentional and must keep
     * working.
     */
    private boolean isFreshEntryStillWanted(Context context) {
        if (Utils.isScreenOn(context)) {
            return false;
        }
        if (isCallActiveNow()) {
            return false;
        }
        if (disableWhenCharging && Utils.isConnectedToCharger(getApplicationContext())) {
            return false;
        }
        if (!Utils.isInsideCustomDozePeriod(context)) {
            return false;
        }
        return !dozeStateStore.isInDoze();
    }

    /**
     * Cancels any fresh entry this process still intends or has outstanding, so a force callback
     * that has not yet run can no longer commit a session. Called from every path that ends or
     * supersedes a session, always before that path reads the durable inDoze flag - which is what
     * makes both orderings safe: either this wins and the callback finds itself stale, or the
     * callback commits first and the caller then sees inDoze=true and performs its ordinary
     * owned-session exit.
     * <p>
     * It deliberately touches only the fresh-entry identity. An ACTIVE session that continues
     * across a locked wake is not affected.
     */
    private void invalidateDesiredEntry(String reason) {
        synchronized (physicalEntryLock) {
            entryAttemptToken++;
            if (physicalEntryPhase == PHASE_ATTEMPTING) {
                DiagnosticLogger.i("DOZE", "entry_invalidated reason=" + reason);
            }
        }
    }

    /**
     * The narrow physical undo for a PREPARING marker. Deliberately not leaveDoze(): that method
     * falls back to resetting device_idle tunables when no privileged backend is present, and a
     * tunable reset is not an unforce - clearing the marker after one would abandon a device that
     * is still forced.
     */
    private void cleanupPendingPhysicalForce(String reason) {
        synchronized (physicalEntryLock) {
            if (physicalEntryPhase == PHASE_CLEANING_UP) {
                DiagnosticLogger.i("DOZE", "entry_cleanup_skipped reason=already_cleaning_up");
                return;
            }
            if (physicalEntryPhase == PHASE_ATTEMPTING) {
                // An unforce must never run alongside a force that is still executing. Every
                // command gets its own thread and its own remote process, so the two would race:
                // the unforce could finish first, clear entryPending, and then the force could
                // land and put the device into deep idle with the marker already gone - the exact
                // ownership the durable bit exists to prevent.
                //
                // The attempt's own callback is the only safe place to resolve this, and it always
                // arrives: ShizukuHandler invokes the listener even when the binder is unavailable,
                // reporting exit -1, so this cannot wait forever. The token bump means that
                // callback will find itself stale, and it will then either abort (force refused,
                // nothing forced) or run the unforce itself (force succeeded).
                entryAttemptToken++;
                log("A force-idle attempt is still running, deferring its undo to its own result");
                DiagnosticLogger.i("DOZE", "entry_cleanup_deferred reason=waiting_for_attempt_result");
                return;
            }
            if (!isPrivilegedForceIdleBackend()) {
                // The debt stays on disk. Shizuku coming back runs the shared recovery path again,
                // which retries this.
                log("No privileged backend available to undo a pending force-idle, deferring");
                DiagnosticLogger.i("DOZE", "entry_cleanup_deferred reason=no_privileged_backend");
                physicalEntryPhase = PHASE_NONE;
                return;
            }
            // PHASE_NONE with entryPending set: an orphan marker, left by a process that died
            // before its force callback ran. Shizuku documents that the process it hands out is
            // killed when its caller dies, so no force from that process can still be executing and
            // the unforce is safe to issue.
            //
            // NOTE: the same path also serves root mode through libsuperuser Shell.Interactive,
            // and that guarantee has NOT been established there - a root child could in principle
            // outlive the app process. Root mode is untested for this scenario and is recorded here
            // as an open item for a separate root-mode audit; it is not a reason to change the
            // Shizuku path.
            entryAttemptToken++;
            physicalEntryPhase = PHASE_CLEANING_UP;
        }
        DiagnosticLogger.i("DOZE", "entry_cleanup_started reason=" + reason);
        // Recovery and shutdown cleanups always allow the one-shot re-evaluation: recovering a
        // marker left by a dead process is exactly the case where no further SCREEN_OFF is coming.
        dispatchPhysicalForceCleanup(true);
    }

    /**
     * Runs the unforce and clears the durable marker only once it has actually completed. The exit
     * code is the acceptance condition here, unlike for force-idle: unforce has no "unable to"
     * outcome, and idle state cannot be used because with the screen off a device that was naturally
     * idle stays idle after the force flag is dropped.
     */
    private void dispatchPhysicalForceCleanup(boolean allowReentry) {
        executeCommandWithRoot("dumpsys deviceidle unforce",
                (commandCode, exitCode, stdout, stderr) -> {
                    boolean unforced = exitCode == 0;
                    boolean cleared = false;
                    if (unforced) {
                        cleared = dozeStateStore.abortForceIdleAttempt();
                        if (cleared) {
                            DiagnosticLogger.i("DOZE", "entry_cleanup_complete exit=" + exitCode);
                        } else {
                            // The physical undo did happen, so the device itself is safe; only the
                            // record of the debt could not be cleared. It must not be reported as
                            // settled, and no re-entry may follow, because a later recovery will
                            // legitimately act on the marker it can still see. Unforcing a device
                            // that is no longer forced is a no-op, so that repeat is harmless, and
                            // it invents neither an ENTER nor an EXIT.
                            DiagnosticLogger.e("DOZE", "entry_cleanup_journal_clear_failed exit="
                                    + exitCode);
                        }
                    } else {
                        // Marker retained: the next recovery opportunity retries it.
                        DiagnosticLogger.i("DOZE", "entry_cleanup_failed exit=" + exitCode);
                    }
                    synchronized (physicalEntryLock) {
                        physicalEntryPhase = PHASE_NONE;
                    }
                    if (cleared && allowReentry) {
                        reevaluateEntryAfterCleanup();
                    }
                }, false);
    }

    /**
     * One policy re-evaluation after a physical cleanup has actually completed - never a loop, and
     * never after a plain rejection.
     * <p>
     * Cleanup is asynchronous, and while it is outstanding every fresh entry is refused because
     * entryPending is still set. Two real cases end there with nothing left to restart them: a
     * process recreated with the screen off, where onStartCommand's entry is refused and no further
     * SCREEN_OFF is coming; and a call that ends while the cleanup for its invalidated attempt is
     * still running, which would silently lose the existing re-entry-after-call behaviour. Both are
     * covered by asking the ordinary policy once, here, at the only moment the answer can have
     * changed.
     * <p>
     * The attempt this starts carries allowPostCleanupReentry=false, so if it is itself invalidated
     * and cleaned up the chain stops. If Android simply refuses it, that is an abort and no retry
     * follows.
     */
    private void reevaluateEntryAfterCleanup() {
        if (serviceStopping) {
            DiagnosticLogger.i("DOZE", "entry_reevaluation_skipped reason=service_stopping");
            return;
        }
        Context context = getApplicationContext();
        if (dozeStateStore.isEntryPending() || dozeStateStore.isInDoze()) {
            DiagnosticLogger.i("DOZE", "entry_reevaluation_skipped reason=state_changed");
            return;
        }
        if (!isFreshEntryStillWanted(context)) {
            DiagnosticLogger.i("DOZE", "entry_reevaluation_skipped reason=policy_not_met");
            return;
        }
        DiagnosticLogger.i("DOZE", "entry_reevaluated_after_cleanup");
        enterDoze(context, false);
    }

    private static String phaseName(int phase) {
        switch (phase) {
            case PHASE_ATTEMPTING:
                return "ATTEMPTING";
            case PHASE_CLEANING_UP:
                return "CLEANING_UP";
            default:
                return "NONE";
        }
    }

    private void recordDozeEnterStats() {
        if (!disableStats) {
            dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.isConnectedToCharger(getApplicationContext()) ? 0.0f : Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("ENTER"));
            saveDozeDataStats();
        }
    }

    /** Unchanged entry work, factored out so both backends run exactly the same code. */
    private void applyEntryPackageAndNotificationBlocking(Context context) {
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
    }

    /** Unchanged entry work, factored out so both backends run exactly the same code. */
    private void applyEntryMotionAndNetwork(Context context) {
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
        DiagnosticLogger.i("DOZE", "exit_doze state=" + newDeviceIdleState);

        if (!disableStats) {
            dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.isConnectedToCharger(getApplicationContext()) ? 0.0f : Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("EXIT"));
            saveDozeDataStats();
        }

        restoreSuspendedPackages("exit Doze");
        reEnableBlockedNotifications();

        // Motion sensors are part of the persisted state, so they are re-enabled by the same code
        // path as the radios instead of a one-shot timer.
        restoreDeviceStates(getApplicationContext(), "exit Doze");

        // timeEnterDoze > 0 means this process performed the entry. After a recreation these
        // entry-side fields are back to 0/"Unknown", which would render a duration of roughly
        // 29,000,000 minutes and a negative battery delta. The durable EXIT row above is written
        // either way; only the notification is skipped.
        if (showPersistentNotif && timeEnterDoze > 0L) {
            Timer updateNotif = new Timer();
            updateNotif.schedule(new TimerTask() {
                @Override
                public void run() {
                    updatePersistentNotification(lastScreenOff, Utils.diffInMins(timeEnterDoze, timeExitDoze), (lastDozeEnterBatteryLife - lastDozeExitBatteryLife));
                }
            }, 2000);
        } else if (showPersistentNotif) {
            DiagnosticLogger.i("DOZE", "stats_notification_update_skipped reason=missing_entry_context");
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

        log((enabled ? "Enabling " : "Disabling ") + valid.size() + " blocklisted package(s)");

        if (Build.VERSION.SDK_INT < MULTI_PACKAGE_PM_MIN_SDK) {
            // Deliberately no fast-path attempt here. Older PackageManagerShellCommand builds are
            // single-target oriented and cannot be relied on to reject the extra arguments in a way
            // we could detect, so a "try it and see" would risk acting on only the first package
            // while reporting success.
            Log.i(TAG, "HARD_BLOCK_COMPAT_FALLBACK reason=legacy_api verb=" + verb
                    + " count=" + valid.size() + " sdk=" + Build.VERSION.SDK_INT);
            DiagnosticLogger.i("HARD_BLOCK", "HARD_BLOCK_COMPAT_FALLBACK reason=legacy_api verb=" + verb
                    + " count=" + valid.size() + " sdk=" + Build.VERSION.SDK_INT);
            runPackageStateFallback(valid, verb, done);
            return;
        }

        executeCommandWithRoot("pm " + verb + " " + TextUtils.join(" ", valid),
                (commandCode, exitCode, stdout, stderr) -> {
                    if (exitCode == 0) {
                        Log.i(TAG, "HARD_BLOCK_BATCH " + verb + " count=" + valid.size() + " exit=0");
                        DiagnosticLogger.i("HARD_BLOCK", "HARD_BLOCK_BATCH " + verb
                                + " count=" + valid.size() + " exit=0");
                        if (done != null) {
                            done.onCommandResult(commandCode, exitCode, stdout, stderr);
                        }
                        return;
                    }
                    // The batch was rejected or partially failed on an API level that should
                    // support it; fall back so a single bad package cannot strand the rest.
                    Log.w(TAG, "HARD_BLOCK_COMPAT_FALLBACK reason=batch_failed verb=" + verb
                            + " count=" + valid.size() + " batchExit=" + exitCode);
                    DiagnosticLogger.w("HARD_BLOCK", "HARD_BLOCK_COMPAT_FALLBACK reason=batch_failed verb="
                            + verb + " count=" + valid.size() + " batchExit=" + exitCode);
                    runPackageStateFallback(valid, verb, done);
                }, false);
    }

    /**
     * One shell, one {@code pm} call per installed package.
     * <p>
     * A package the user has uninstalled since Doze began can never be un-suspended, so it is
     * skipped via {@code pm path} and does not count as a failure - otherwise the durable record
     * would stay pending forever and every wake-up would retry it. A package that <em>is</em>
     * installed and whose {@code pm} call fails does count, and the non-zero exit keeps the record
     * pending so a later trigger retries it.
     */
    private void runPackageStateFallback(List<String> valid, String verb,
                                         Shell.OnCommandResultListener2 done) {
        StringBuilder command = new StringBuilder("failed=0; for p in ");
        command.append(TextUtils.join(" ", valid));
        command.append("; do ");
        // "pm path" is silent and cheap; a non-zero exit means the package is not installed.
        command.append("if ! pm path \"$p\" >/dev/null 2>&1; then continue; fi; ");
        command.append("if ! pm ").append(verb).append(" \"$p\" >/dev/null 2>&1; then ");
        command.append("echo ").append(FALLBACK_FAILURE_MARKER).append(" \"$p\"; failed=1; fi; ");
        command.append("done; exit $failed");

        executeCommandWithRoot(command.toString(), (commandCode, exitCode, stdout, stderr) -> {
            int failures = 0;
            if (stdout != null) {
                for (String line : stdout) {
                    if (line != null && line.contains(FALLBACK_FAILURE_MARKER)) {
                        failures++;
                    }
                }
            }
            Log.i(TAG, "HARD_BLOCK_COMPAT_FALLBACK finished " + verb + " count=" + valid.size()
                    + " installedFailures=" + failures + " exit=" + exitCode);
            DiagnosticLogger.i("HARD_BLOCK", "HARD_BLOCK_COMPAT_FALLBACK finished " + verb
                    + " count=" + valid.size() + " installedFailures=" + failures + " exit=" + exitCode);
            if (done != null) {
                done.onCommandResult(commandCode, exitCode, stdout, stderr);
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
        DiagnosticLogger.i("HARD_BLOCK", "suspend_intended count=" + valid.size());
        // Persist first: a kill between here and the command completing still leaves a record.
        // A genuinely fresh session, so this is the only place that allocates a new generation.
        // The store returns the union of the fresh set with anything the previous session still
        // owed, so a package whose final un-suspend failed keeps its owner and is released later.
        DozeStateStore.SuspendedPackageSession session =
                dozeStateStore.beginSuspendedPackageSession(valid);
        DiagnosticLogger.i("HARD_BLOCK", "session_started gen=" + session.generation
                + " owned=" + session.packages.size() + " intended=" + valid.size());
        // Submitted from the returned snapshot verbatim: rebuilding the union here would reopen
        // the split read/write the atomic call exists to close.
        submitPackageOp(new PackageOp(true, false, session.generation, session.packages, "enter Doze"));
    }

    /**
     * Un-suspends exactly the packages recorded for this Doze session and clears the record only
     * once the command reports success. Safe to call from several wake triggers - the in-flight
     * guard collapses them into one batch.
     */
    /**
     * One immutable package operation. The package set is snapshotted with its generation and is
     * never re-read: an operation must never silently adopt whatever the durable record happens to
     * hold by the time it runs, and must never consult the live blocklist.
     */
    private static final class PackageOp {
        final boolean suspend;
        final boolean finalRestore;
        final long generation;
        final Set<String> packages;
        final String reason;

        PackageOp(boolean suspend, boolean finalRestore, long generation,
                  Set<String> packages, String reason) {
            this.suspend = suspend;
            this.finalRestore = finalRestore;
            this.generation = generation;
            this.packages = packages;
            this.reason = reason;
        }
    }

    /**
     * FINAL un-suspend of whatever the current session owns: clears ownership on success. Used by
     * USER_PRESENT, exitDoze, the call override, boot restore and shutdown.
     */
    public void restoreSuspendedPackages(String reason) {
        DozeStateStore.SuspendedPackageSession session = dozeStateStore.getSuspendedPackageSession();
        if (session.isEmpty()) {
            return;
        }
        submitPackageOp(new PackageOp(false, true, session.generation, session.packages, reason));
    }

    /**
     * Brings the packages into line with the current lifecycle, derived from durable facts so it is
     * correct after a process death or a Shizuku reconnect. Mirrors the All Sensors policy.
     * <p>
     * Only a FINAL un-suspend may clear ownership; the temporary lock-screen un-suspend and the
     * screen-off re-suspend both keep the record, which is what makes the exact session set - not
     * the live blocklist - available for the whole session.
     */
    private void enforcePackageStateForLifecycle(String reason) {
        DozeStateStore.SuspendedPackageSession session = dozeStateStore.getSuspendedPackageSession();
        if (session.isEmpty()) {
            return;
        }

        boolean suspend;
        boolean isFinal;
        if (isCallActiveNow()) {
            // Packages must stay usable for the call; exitDoze() supplies the FINAL un-suspend.
            suspend = false;
            isFinal = false;
        } else if (!dozeStateStore.isInDoze()) {
            suspend = false;
            isFinal = true;
        } else if (Utils.isScreenOn(getApplicationContext())) {
            boolean lockedBehindWaitForUnlock =
                    waitForUnlock && Utils.isDeviceLocked(getApplicationContext());
            suspend = false;
            isFinal = !lockedBehindWaitForUnlock;
        } else {
            suspend = true;
            isFinal = false;
        }

        submitPackageOp(new PackageOp(suspend, isFinal, session.generation, session.packages, reason));
    }

    /**
     * Single pending slot, latest-wins within a generation and higher-generation-wins across them.
     * <p>
     * Generation ownership outranks arrival order: an operation carrying an older generation refers
     * to a package set a newer session has already replaced, so it is rejected outright rather than
     * being allowed to overwrite the pending slot.
     * <p>
     * Lock order is packageOpLock then the DozeStateStore monitor, never the reverse. DozeStateStore
     * holds no reference to this service and cannot call back into it.
     */
    private void submitPackageOp(PackageOp op) {
        PackageOp toRun = null;
        PackageOp displaced = null;
        boolean stale = false;

        synchronized (packageOpLock) {
            long newestKnown = dozeStateStore.getSuspendedPackageSession().generation;
            if (inFlightPackageOp != null) {
                newestKnown = Math.max(newestKnown, inFlightPackageOp.generation);
            }
            if (pendingPackageOp != null) {
                newestKnown = Math.max(newestKnown, pendingPackageOp.generation);
            }

            if (op.generation < newestKnown) {
                stale = true;
            } else if (inFlightPackageOp == null) {
                displaced = pendingPackageOp;
                pendingPackageOp = null;
                inFlightPackageOp = op;
                inFlightPackageFinal = op.finalRestore;
                toRun = op;
            } else if (isSatisfiedByInFlight(op)) {
                // The newest lifecycle request is already being satisfied physically. Drop any
                // older pending operation - leaving it queued would let a stale opposite target
                // run after this one completes - and upgrade the mode if this request is FINAL.
                displaced = pendingPackageOp;
                pendingPackageOp = null;
                if (op.finalRestore) {
                    inFlightPackageFinal = true;
                }
            } else {
                displaced = pendingPackageOp;
                pendingPackageOp = op;
            }
        }

        if (displaced != null) {
            DiagnosticLogger.i("HARD_BLOCK", "package_op_superseded target="
                    + (displaced.suspend ? "suspend" : "unsuspend")
                    + " gen=" + displaced.generation + " exit=" + SUPERSEDED_EXIT_CODE);
        }
        if (stale) {
            DiagnosticLogger.w("HARD_BLOCK", "package_op_stale_generation gen=" + op.generation
                    + " target=" + (op.suspend ? "suspend" : "unsuspend")
                    + " exit=" + STALE_GENERATION_EXIT_CODE);
            log("Discarding a stale package operation from generation " + op.generation);
            return;
        }
        if (toRun != null) {
            dispatchPackageOp(toRun);
        }
    }

    /** Caller must hold packageOpLock. Generation equality is required, not just direction. */
    private boolean isSatisfiedByInFlight(PackageOp op) {
        return inFlightPackageOp != null
                && inFlightPackageOp.generation == op.generation
                && inFlightPackageOp.suspend == op.suspend
                && inFlightPackageOp.packages.equals(op.packages);
    }

    private void dispatchPackageOp(final PackageOp op) {
        final long startedAt = SystemClock.elapsedRealtime();
        final int count = op.packages.size();

        if (op.suspend) {
            DiagnosticLogger.i("HARD_BLOCK", "re_suspend_start count=" + count
                    + " gen=" + op.generation);
        } else {
            Log.i(TAG, "HARD_BLOCK_RESTORE_START reason=" + op.reason + " count=" + count);
            DiagnosticLogger.i("HARD_BLOCK", "HARD_BLOCK_RESTORE_START reason=" + op.reason
                    + " count=" + count);
            DiagnosticLogger.i("HARD_BLOCK", (op.finalRestore ? "final_unsuspend_start"
                    : "temporary_unsuspend_start") + " count=" + count + " gen=" + op.generation);
            wakeTiming("hard_unsuspend_dispatched");
        }

        setPackagesState(op.packages, !op.suspend, (commandCode, exitCode, stdout, stderr) ->
                onPackageOpComplete(op, exitCode, startedAt));
    }

    private void onPackageOpComplete(PackageOp op, int exitCode, long startedAt) {
        final PackageOp next;
        final boolean wasFinal;
        synchronized (packageOpLock) {
            wasFinal = inFlightPackageFinal;
            next = pendingPackageOp;
            pendingPackageOp = null;
            inFlightPackageOp = next;
            inFlightPackageFinal = next != null && next.finalRestore;
        }

        long durationMs = SystemClock.elapsedRealtime() - startedAt;
        int count = op.packages.size();

        if (op.suspend) {
            DiagnosticLogger.i("HARD_BLOCK", (exitCode == 0 ? "re_suspend_success"
                    : "re_suspend_failed") + " count=" + count + " exit=" + exitCode
                    + " durationMs=" + durationMs);
        } else {
            Log.i(TAG, "HARD_BLOCK_RESTORE_COMMAND_FINISHED exit=" + exitCode + " count=" + count);
            DiagnosticLogger.i("HARD_BLOCK", "HARD_BLOCK_RESTORE_COMMAND_FINISHED exit=" + exitCode
                    + " count=" + count + " durationMs=" + durationMs);
            wakeTiming("hard_unsuspend_finished");

            if (exitCode == 0 && wasFinal) {
                // Two guards. isInDoze() catches a session that is currently active; the
                // generation compare-and-clear catches a session that started and ended while this
                // command was still outstanding, which isInDoze() alone cannot see.
                if (dozeStateStore.isInDoze()) {
                    DiagnosticLogger.w("HARD_BLOCK", "final_unsuspend_stale reason=active_session"
                            + " gen=" + op.generation);
                } else if (!dozeStateStore.clearAppliedSuspendedPackagesIfGeneration(op.generation)) {
                    DiagnosticLogger.w("HARD_BLOCK", "final_unsuspend_stale reason=generation"
                            + " gen=" + op.generation);
                } else {
                    DiagnosticLogger.i("HARD_BLOCK", "final_unsuspend_success count=" + count
                            + " gen=" + op.generation + " durationMs=" + durationMs);
                }
            } else if (exitCode == 0) {
                DiagnosticLogger.i("HARD_BLOCK", "temporary_unsuspend_success count=" + count
                        + " gen=" + op.generation + " durationMs=" + durationMs);
            } else {
                Log.e(TAG, "HARD_BLOCK un-suspend failed exit=" + exitCode + ", record kept");
            }
        }

        if (next != null) {
            dispatchPackageOp(next);
        }
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
            // Same serializer as every other sensor write, so a late command from the previous
            // session cannot be applied on top of this one.
            requestSensorState(false, null, SENSOR_LABEL_ENTER);
        }
        if (turnOffBiometricsInDoze) {
            log("Disabling Biometrics");
            dozeStateStore.markApplied(DozeStateStore.KEY_BIOMETRICS, true);
            // Through the same serializer as every other biometric write. Bypassing it left the
            // fresh disable racing an old session's final enable on independent Shizuku threads:
            // the cross-session marker guard would correctly keep the new marker, but the stale
            // enable could still land last and leave biometrics on for the new session.
            requestBiometricState(false, null, BIOMETRIC_LABEL_ENTER);
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
        DiagnosticLogger.i("STATE", "DEVICE_STATE_RESTORE_STARTED reason=" + reason + " keys=" + pending);
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
            DiagnosticLogger.i("STATE", "RESTORE_PENDING " + key);
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
        DiagnosticLogger.i("STATE", "DEVICE_STATE_RESTORE_DISPATCHED count=" + dispatched);
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
                // Cross-session guard for the two keys a temporary lock-screen command can
                // outlive: an old session's final restore can land after a new SCREEN_OFF has
                // already begun a new session and re-marked them. Clearing then would drop a debt
                // the new session genuinely owes.
                if ((DozeStateStore.KEY_ALL_SENSORS.equals(key) || DozeStateStore.KEY_BIOMETRICS.equals(key))
                        && dozeStateStore.isInDoze()) {
                    Log.i(TAG, "RESTORE_SUPERSEDED " + key + ", a newer Doze session owns the marker");
                    DiagnosticLogger.i("STATE", "RESTORE_SUPERSEDED " + key + " newSessionOwnsMarker=true");
                    return;
                }
                dozeStateStore.clearApplied(key);
                Log.i(TAG, "RESTORE_SUCCESS " + key + " exit=0");
                DiagnosticLogger.i("STATE", "RESTORE_SUCCESS " + key + " exit=0");
            } else {
                Log.e(TAG, "RESTORE_FAILED " + key + " exit=" + exitCode + ", marker kept for retry");
                DiagnosticLogger.e("STATE", "RESTORE_FAILED " + key + " exit=" + exitCode + " markerKept=true");
            }
        } finally {
            stateRestoreInFlight.remove(key);
            wakeTiming("device_state_restore_finished:" + key);
        }
    }

    /**
     * Ends an owned session that this process itself started, using ordinary exit semantics.
     * exitDoze() already sets inDoze false before dispatching, so every completion callback sees a
     * finished session and the final clears are permitted.
     */
    private void endOwnedDozeSession(String reason) {
        log("Ending the owned Doze session: " + reason);
        DiagnosticLogger.i("DOZE", "owned_session_final_exit reason=" + reason);
        cancelPendingEnterDoze();
        releaseTempWakeLock();
        maintenance = false;
        exitDoze(getDeviceIdleState());
    }

    /**
     * Ends an owned session this process did not start, after a recreation.
     * <p>
     * Deliberately not exitDoze(): its tail renders the statistics notification from
     * timeEnterDoze, lastDozeEnterBatteryLife and lastScreenOff, which a fresh process has reset to
     * 0/0/"Unknown". The durable EXIT row is still written - it is built only from the current
     * clock and a live battery read, and it pairs correctly with the ENTER row the original process
     * committed, so the session is preserved in the statistics.
     */
    private void finalizeRecoveredOwnedSession(String reason) {
        log("Finalizing a recovered owned Doze session: " + reason);
        DiagnosticLogger.i("DOZE", "recovered_session_final_exit reason=" + reason);

        cancelPendingEnterDoze();
        releaseTempWakeLock();
        maintenance = false;

        // Durable flag first: the package generation clear and the KEY_ALL_SENSORS/KEY_BIOMETRICS
        // marker clears all refuse to release ownership while inDoze is true.
        dozeStateStore.setInDoze(false);
        lastKnownState = "ACTIVE";
        leaveDoze();

        if (!disableStats) {
            dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",")
                    .concat(Float.toString(Utils.isConnectedToCharger(getApplicationContext())
                            ? 0.0f : Utils.getBatteryLevel(getApplicationContext())))
                    .concat(",").concat("EXIT"));
            saveDozeDataStats();
        }

        restoreSuspendedPackages(reason);
        reEnableBlockedNotifications();
        restoreDeviceStates(getApplicationContext(), reason);
    }

    /**
     * Makes sure an owned session is still physically idle, without running any fresh-entry work.
     * Shared by the screen-off resume and by recovery Mode A, so a force-idle dropped while Shizuku
     * was away is reissued on reconnect rather than leaving restrictions applied on a device that
     * is not actually idle.
     */
    private void ensureOwnedDozePhysicalState(String reason) {
        if (!dozeStateStore.isInDoze()) {
            return;
        }
        if (Utils.isScreenOn(getApplicationContext())) {
            return;
        }
        if (isCallActiveNow()) {
            return;
        }
        if (disableWhenCharging && Utils.isConnectedToCharger(getApplicationContext())) {
            return;
        }
        if (!Utils.isInsideCustomDozePeriod(getApplicationContext())) {
            return;
        }
        if (maintenance) {
            // A genuine maintenance window is open and its restores are asynchronous. Forcing deep
            // idle now could make the re-entry handler recapture pre-Doze values from a partially
            // restored state, so let Android finish the window on its own.
            log("Owned session is inside a maintenance window, not forcing deep idle (" + reason + ")");
            DiagnosticLogger.i("DOZE", "owned_session_waiting_for_maintenance_completion reason=" + reason);
            return;
        }
        // Cheap in-process read; no dumpsys and nothing to wait on.
        if (pm.isDeviceIdleMode()) {
            return;
        }
        DiagnosticLogger.i("DOZE", "owned_session_reforce_idle reason=" + reason);
        // A resume carries no PREPARING marker and never cleans up: the logical session is already
        // owned and committed, so a successful re-force is exactly what is wanted and a rejected one
        // changes nothing except what the diagnostics have to say honestly.
        DiagnosticLogger.i("DOZE", "force_idle_attempt_start mode=resume");
        if (isPrivilegedForceIdleBackend()) {
            applyDoze((commandCode, exitCode, stdout, stderr) -> {
                boolean physicallyIdle = verifyPhysicalDozeEntered();
                DiagnosticLogger.i("DOZE", "force_idle_result mode=resume success=" + physicallyIdle
                        + " exit=" + exitCode + " verifiedBy=idle_mode");
                if (!physicallyIdle) {
                    // The session stays owned. No ENTER, no new generation, and no EXIT: nothing
                    // about the logical session has ended just because Android declined to re-force
                    // deep idle right now.
                    DiagnosticLogger.i("DOZE", "owned_session_reforce_failed stoppedAt="
                            + describeForceIdleFailure(stdout, stderr));
                }
            });
        } else {
            applyDoze();
        }
    }

    /**
     * The user woke the lock screen and turned it off again. That is a continuation of the session
     * already owned, not a new one, so none of the fresh-entry work runs: no new package
     * generation, no ENTER statistics row, no pre-Doze recapture, no second motion-sensor timer and
     * no configured entry delay. The restrictions themselves are re-applied by the enforce* calls
     * at the SCREEN_OFF site before this runs.
     */
    private void resumeOwnedDozeAfterLockedWake(String reason) {
        if (!dozeStateStore.isInDoze()) {
            return;
        }
        if (Utils.isScreenOn(getApplicationContext())) {
            return;
        }
        log("Resuming the owned Doze session (" + reason + ")");
        DiagnosticLogger.i("DOZE", "owned_session_resumed reason=" + reason);
        cancelPendingEnterDoze();
        releaseTempWakeLock();
        lastKnownState = "IDLE";
        // maintenance is deliberately left as it is: while a genuine window is open the flag is the
        // only record that its restored states must be re-applied when deep idle resumes.
        ensureOwnedDozePhysicalState(reason);
    }

    /**
     * Queues a target state for the biometric keyguard toggle. Same contract as the sensor
     * serializer: an in-flight command is never completed artificially and receives only its real
     * callback, once; a request still queued when a newer one replaces it is completed exactly once
     * with {@link #SUPERSEDED_EXIT_CODE}.
     */
    private void requestBiometricState(boolean enabled, Shell.OnCommandResultListener2 done, String label) {
        Shell.OnCommandResultListener2 superseded;
        boolean dispatchNow;
        synchronized (biometricOpLock) {
            superseded = pendingBiometricCallback;
            pendingBiometricTarget = enabled;
            pendingBiometricCallback = done;
            pendingBiometricLabel = label;
            dispatchNow = !biometricOpInFlight;
            if (dispatchNow) {
                biometricOpInFlight = true;
            }
        }
        if (superseded != null) {
            superseded.onCommandResult(0, SUPERSEDED_EXIT_CODE, new ArrayList<>(), new ArrayList<>());
        }
        if (dispatchNow) {
            dispatchPendingBiometricOp();
        }
    }

    private void dispatchPendingBiometricOp() {
        final boolean target;
        final Shell.OnCommandResultListener2 done;
        final String label;
        synchronized (biometricOpLock) {
            if (pendingBiometricTarget == null) {
                biometricOpInFlight = false;
                return;
            }
            target = pendingBiometricTarget;
            done = pendingBiometricCallback;
            label = pendingBiometricLabel;
            pendingBiometricTarget = null;
            pendingBiometricCallback = null;
            pendingBiometricLabel = null;
            biometricOpInFlight = true;
        }

        if (BIOMETRIC_LABEL_LOCKSCREEN_RESTORE.equals(label)) {
            DiagnosticLogger.i("LOCKSCREEN", "biometrics_restore_start");
        } else if (BIOMETRIC_LABEL_LOCKSCREEN_REAPPLY.equals(label)) {
            DiagnosticLogger.i("LOCKSCREEN", "biometrics_reapply_start");
        }

        setBiometricsSensorState(getApplicationContext(), target,
                (commandCode, exitCode, stdout, stderr) -> {
                    if (BIOMETRIC_LABEL_LOCKSCREEN_RESTORE.equals(label)) {
                        DiagnosticLogger.i("LOCKSCREEN", (exitCode == 0 ? "biometrics_restore_success"
                                : "biometrics_restore_failed") + " exit=" + exitCode);
                    } else if (BIOMETRIC_LABEL_LOCKSCREEN_REAPPLY.equals(label)) {
                        DiagnosticLogger.i("LOCKSCREEN", (exitCode == 0 ? "biometrics_reapply_success"
                                : "biometrics_reapply_failed") + " exit=" + exitCode);
                    }
                    if (done != null) {
                        done.onCommandResult(commandCode, exitCode, stdout, stderr);
                    }
                    dispatchPendingBiometricOp();
                });
    }

    /**
     * Brings biometrics into line with the current lifecycle, derived from durable facts exactly as
     * the sensor policy is.
     * <p>
     * Biometrics are unlock-critical, so a locked wake restores them to their recorded pre-Doze
     * value immediately - waiting for ACTION_USER_PRESENT to re-enable the sensor needed to produce
     * ACTION_USER_PRESENT would be circular. That restore is TEMPORARY and keeps the marker, so the
     * following screen-off can disable them again for the same still-owned session.
     */
    private void enforceBiometricStateForLifecycle(String reason) {
        if (!dozeStateStore.isApplied(DozeStateStore.KEY_BIOMETRICS)) {
            return;
        }
        if (isCallActiveNow()) {
            log("Not enforcing biometrics (" + reason + "), a call is active");
            return;
        }
        if (!dozeStateStore.isInDoze()) {
            // Session over; the final restore path owns the value and the marker.
            return;
        }

        if (Utils.isScreenOn(getApplicationContext())) {
            if (!(waitForUnlock && Utils.isDeviceLocked(getApplicationContext()))) {
                // A full restore is running for this wake; leave it to that path.
                return;
            }
            boolean preDoze = dozeStateStore.getPreDozeValue(DozeStateStore.KEY_BIOMETRICS, true);
            log("Temporarily restoring biometrics for the lock screen (" + reason + ")");
            requestBiometricState(preDoze, null, BIOMETRIC_LABEL_LOCKSCREEN_RESTORE);
        } else {
            log("Re-applying the biometric restriction (" + reason + ")");
            requestBiometricState(false, null, BIOMETRIC_LABEL_LOCKSCREEN_REAPPLY);
        }
    }

    /**
     * Queues a target state for the All Sensors toggle. Returns immediately.
     * <p>
     * If a command is already in flight the target is parked and applied when that command's real
     * callback arrives, so an in-flight command is never completed artificially and no callback
     * ever fires twice. A request that is still queued when a newer one arrives has its callback
     * completed exactly once with {@link #SUPERSEDED_EXIT_CODE}.
     */
    private void requestSensorState(boolean enabled, Shell.OnCommandResultListener2 done, String label) {
        Shell.OnCommandResultListener2 superseded;
        boolean dispatchNow;
        synchronized (sensorOpLock) {
            superseded = pendingSensorCallback;
            pendingSensorTarget = enabled;
            pendingSensorCallback = done;
            pendingSensorLabel = label;
            dispatchNow = !sensorOpInFlight;
            if (dispatchNow) {
                sensorOpInFlight = true;
            }
        }
        if (superseded != null) {
            superseded.onCommandResult(0, SUPERSEDED_EXIT_CODE, new ArrayList<>(), new ArrayList<>());
        }
        if (dispatchNow) {
            dispatchPendingSensorOp();
        }
    }

    private void dispatchPendingSensorOp() {
        final boolean target;
        final Shell.OnCommandResultListener2 done;
        final String label;
        synchronized (sensorOpLock) {
            if (pendingSensorTarget == null) {
                sensorOpInFlight = false;
                return;
            }
            target = pendingSensorTarget;
            done = pendingSensorCallback;
            label = pendingSensorLabel;
            pendingSensorTarget = null;
            pendingSensorCallback = null;
            pendingSensorLabel = null;
            sensorOpInFlight = true;
        }

        if (SENSOR_LABEL_LOCKSCREEN_RESTORE.equals(label)) {
            DiagnosticLogger.i("LOCKSCREEN", "all_sensors_restore_start");
        } else if (SENSOR_LABEL_LOCKSCREEN_REAPPLY.equals(label)) {
            DiagnosticLogger.i("LOCKSCREEN", "all_sensors_reapply_start");
        }

        setAllSensorsState(getApplicationContext(), target, (commandCode, exitCode, stdout, stderr) -> {
            if (SENSOR_LABEL_LOCKSCREEN_RESTORE.equals(label)) {
                DiagnosticLogger.i("LOCKSCREEN", (exitCode == 0 ? "all_sensors_restore_success"
                        : "all_sensors_restore_failed") + " exit=" + exitCode);
            } else if (SENSOR_LABEL_LOCKSCREEN_REAPPLY.equals(label)) {
                DiagnosticLogger.i("LOCKSCREEN", (exitCode == 0 ? "all_sensors_reapply_success"
                        : "all_sensors_reapply_failed") + " exit=" + exitCode);
            }
            if (done != null) {
                done.onCommandResult(commandCode, exitCode, stdout, stderr);
            }
            // Apply whatever the newest lifecycle event asked for while this was running.
            dispatchPendingSensorOp();
        });
    }

    /**
     * True when the Doze session EnforceDoze owns has genuinely ended, as opposed to the device
     * merely showing its lock screen mid-session. This is what separates a FINAL restore, which may
     * clear durable markers, from TEMPORARY lock-screen access, which may not.
     */
    private boolean isDozeSessionOver() {
        if (!dozeStateStore.isInDoze()) {
            return true;
        }
        if (!Utils.isScreenOn(getApplicationContext())) {
            return false;
        }
        return !(waitForUnlock && Utils.isDeviceLocked(getApplicationContext()));
    }

    /**
     * Brings the physical All Sensors state into line with the current lifecycle, derived entirely
     * from durable facts rather than from any in-memory flag - which is what makes it correct after
     * a process death or a Shizuku reconnect. Never touches the journal: while KEY_ALL_SENSORS is
     * applied the session still owes the user their recorded pre-Doze value.
     */
    private void enforceSensorStateForLifecycle(String reason) {
        if (!dozeStateStore.isApplied(DozeStateStore.KEY_ALL_SENSORS)) {
            return;
        }
        if (isCallActiveNow()) {
            // The call handler owns the transition back to normal; never re-disable underneath it.
            log("Not enforcing sensor state (" + reason + "), a call is active");
            return;
        }
        if (!dozeStateStore.isInDoze()) {
            // Session is over; the final restore path owns the value and the marker.
            return;
        }

        boolean screenOn = Utils.isScreenOn(getApplicationContext());
        if (screenOn) {
            if (!(waitForUnlock && Utils.isDeviceLocked(getApplicationContext()))) {
                // A full restore is running for this wake; leave it to that path.
                return;
            }
            // Lock-screen access: give the user their recorded pre-Doze value so the secure camera
            // and the proximity sensor work without unlocking. The marker deliberately stays.
            boolean preDoze = dozeStateStore.getPreDozeValue(DozeStateStore.KEY_ALL_SENSORS, true);
            lockscreenSensorOverrideActive = true;
            log("Temporarily restoring All Sensors for the lock screen (" + reason + ")");
            requestSensorState(preDoze, null, SENSOR_LABEL_LOCKSCREEN_RESTORE);
        } else {
            // Screen off inside an owned session: sensors must be off, whatever happened before.
            log("Re-applying the All Sensors restriction (" + reason + ")");
            requestSensorState(false, null, SENSOR_LABEL_LOCKSCREEN_REAPPLY);
        }
    }

    /**
     * Called on unlock. After this no queued or late temporary command may leave sensors off: a
     * queued disable is rewritten to the pre-Doze value, and the session is about to end so
     * enforceSensorStateForLifecycle() can no longer ask for off either.
     */
    private void cancelLockscreenSensorOverride(String reason) {
        boolean wasActive;
        boolean rewrotePending = false;
        synchronized (sensorOpLock) {
            wasActive = lockscreenSensorOverrideActive;
            lockscreenSensorOverrideActive = false;
            if (pendingSensorTarget != null && !pendingSensorTarget) {
                pendingSensorTarget = dozeStateStore.getPreDozeValue(DozeStateStore.KEY_ALL_SENSORS, true);
                rewrotePending = true;
            }
        }
        if (wasActive || rewrotePending) {
            DiagnosticLogger.i("LOCKSCREEN", "override_cancelled reason=" + reason
                    + " rewrotePendingDisable=" + rewrotePending);
        }
    }

    /**
     * The three recovery modes, shared by service recreation and Shizuku reconnect.
     * <p>
     * Mode A - screen off inside an owned session: defer packages, radios and notifications exactly
     * as before, but enforce the sensor restriction, because leaving sensors physically on while
     * the phone is locked and dozing breaks what turnOffAllSensorsInDoze promises.
     * <p>
     * Mode B - screen on but still locked with waitForUnlock: mirror the tested pre-unlock SCREEN_ON
     * behaviour (packages, biometrics) plus temporary sensor access. No radios, no Doze exit, and
     * above all no generic restoreDeviceStates(), which would clear KEY_ALL_SENSORS mid-session.
     * <p>
     * Mode C - the session is genuinely over: the existing full restore.
     */
    private void applyRecoveryPolicy(String logPrefix, String reason) {
        boolean screenOn = Utils.isScreenOn(getApplicationContext());
        boolean inDoze = dozeStateStore.isInDoze();

        // Highest priority, ahead of every ownership mode. A PREPARING marker means a privileged
        // force-idle was dispatched and its outcome was never recorded, so the device may be held
        // in deep idle by this app with no session to account for it. It must be resolved before
        // anything else reasons about ownership, and it lives here rather than in
        // recoverAfterServiceRecreation() because onShizukuBecameAvailable() enters through this
        // method directly - which is also what retries a cleanup that had to be deferred.
        if (dozeStateStore.isEntryPending()) {
            log(logPrefix + "_PREPARING: an interrupted force-idle attempt is unresolved");
            DiagnosticLogger.i("RECOVERY", "RECOVERY_PREPARING prefix=" + logPrefix);
            // No ENTER, no EXIT and no statistics of any kind: PREPARING never became a session.
            cleanupPendingPhysicalForce(reason + " (preparing recovery)");
            return;
        }

        if (isDozeSessionOver()) {
            log(logPrefix + "_RUNNING screenOn=" + screenOn + " inDoze=" + inDoze);
            DiagnosticLogger.i("RECOVERY", logPrefix + "_RUNNING screenOn=" + screenOn + " inDoze=" + inDoze);
            if (inDoze) {
                // A logical session was still owned, so this is a session END, not just a restore.
                // Clearing inDoze and restoring - what this branch used to do - left out
                // leaveDoze() and the EXIT statistics row, so the session's ENTER row stayed
                // unmatched and the statistics screen skipped it: the recovered equivalent of the
                // live handleScreenOn bug. finalizeRecoveredOwnedSession() owns the whole
                // transition, including clearing inDoze before its callbacks can observe it, so
                // nothing may pre-clear the flag here.
                log(logPrefix + "_RUNNING: finalizing the recovered owned session");
                DiagnosticLogger.i("RECOVERY", logPrefix + "_SESSION_ENDED recoveredOwned=true");
                finalizeRecoveredOwnedSession(reason);
            } else {
                // Nothing was owned; retry whatever an earlier restore failed to complete, but do
                // not invent a session boundary in the statistics.
                restoreSuspendedPackages(reason);
                reEnableBlockedNotifications();
                restoreDeviceStates(getApplicationContext(), reason);
            }
            return;
        }

        // The broadcasts that would normally end a session - SCREEN_OFF and
        // ACTION_POWER_CONNECTED - may have been delivered while this process was dead, so policy
        // validity is re-checked here rather than assumed.
        if (isCallActiveNow()) {
            log(logPrefix + "_CALL: a call owns the transition");
            DiagnosticLogger.i("RECOVERY", logPrefix + "_CALL");
            handleCallStarted(reason + "_recovery");
            return;
        }
        if (disableWhenCharging && Utils.isConnectedToCharger(getApplicationContext())) {
            DiagnosticLogger.i("RECOVERY", logPrefix + "_POLICY_EXIT reason=charging");
            finalizeRecoveredOwnedSession("charging (recovered)");
            return;
        }
        if (!Utils.isInsideCustomDozePeriod(getApplicationContext())) {
            DiagnosticLogger.i("RECOVERY", logPrefix + "_POLICY_EXIT reason=outside_custom_period");
            finalizeRecoveredOwnedSession("outside custom Doze period (recovered)");
            return;
        }

        if (screenOn) {
            log(logPrefix + "_PARTIAL_LOCKSCREEN: session still active behind the keyguard");
            DiagnosticLogger.i("RECOVERY", logPrefix + "_PARTIAL_LOCKSCREEN screenOn=true inDoze=true");
            // Temporary, so the session keeps ownership of its exact package set.
            enforcePackageStateForLifecycle(reason);
            enforceBiometricStateForLifecycle(reason);
            enforceSensorStateForLifecycle(reason);
            return;
        }

        log(logPrefix + "_DEFERRED: still dozing with the screen off, keeping suspensions and "
                + "pending state until the screen comes back on");
        DiagnosticLogger.i("RECOVERY", logPrefix + "_DEFERRED screenOn=false inDoze=true");
        enforceSensorStateForLifecycle(reason);
        // Packages belong to the same still-active session: they must be suspended while the
        // screen is off, and the record must survive.
        enforcePackageStateForLifecycle(reason);
        enforceBiometricStateForLifecycle(reason);
        // Converge Android's own idle state too, so a force-idle dropped while Shizuku was away
        // does not leave restrictions applied on a device that is not actually idle.
        ensureOwnedDozePhysicalState(reason);
    }

    /**
     * Releases the temporary wakelock taken to hold the CPU across a delayed Doze entry. Only that
     * wakelock - the restore wakelock and any other are untouched.
     *
     * @return true when it was actually held, which is a good proxy for a delayed entry having
     * been armed
     */
    private boolean releaseTempWakeLock() {
        try {
            if (tempWakeLock != null && tempWakeLock.isHeld()) {
                log("Releasing ForceDozeTempWakelock");
                tempWakeLock.release();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not release ForceDozeTempWakelock: " + e.getMessage());
        }
        return false;
    }

    /**
     * Live call check used as a guard rather than as an event source. Prefers the watcher, which
     * already folds in the telephony and audio-mode state, and falls back to the original Utils
     * checks for the window before the watcher is started at the end of onCreate.
     */
    private boolean isCallActiveNow() {
        if (callStateWatcher != null) {
            return callStateWatcher.isCallActive();
        }
        Context context = getApplicationContext();
        return Utils.isUserInCall(context) || Utils.isUserInCommunicationCall(context);
    }

    /**
     * Cancels a delayed enterDoze if one is armed. Timer.cancel() makes the instance unusable, but
     * every scheduling site already builds a fresh Timer, so this is safe to call at any time.
     */
    private void cancelPendingEnterDoze() {
        // A fresh entry can be outstanding as a physical force-idle transaction as well as an armed
        // TimerTask, and both mean the same thing: this process still intends to start a session.
        // Cancelling one without the other would let a force callback commit a session moments
        // after the event that cancelled it.
        invalidateDesiredEntry("pending entry cancelled");
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
                // Through the serializer so a temporary lock-screen command can never be applied
                // after this one and leave the user with the wrong sensor state.
                requestSensorState(dozeStateStore.getPreDozeValue(key, true), done, SENSOR_LABEL_FINAL);
                break;
            case DozeStateStore.KEY_BIOMETRICS:
                // Through the serializer so a temporary lock-screen command can never be applied
                // after this one and leave the user unable to unlock.
                requestBiometricState(dozeStateStore.getPreDozeValue(key, true), done, BIOMETRIC_LABEL_FINAL);
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

        releaseTempWakeLock();

        // Before the durable flag is read, so a force callback still in flight can no longer
        // commit. Every caller already cancels the pending entry first; doing it here as well keeps
        // the property local to this method rather than dependent on the caller.
        invalidateDesiredEntry("screen on");

        // Captured BEFORE the durable flag is cleared. This is the once-only token for the
        // logical session: whether EnforceDoze owned a Doze session, which is independent of
        // whether Android has already moved its own device-idle state to ACTIVE.
        boolean ownedSession = dozeStateStore.isInDoze();

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

        // Physical state is read for the log only. It must not decide whether a logical
        // EnforceDoze session existed: Android frequently leaves deep idle before
        // ACTION_USER_PRESENT arrives, and the old condition
        //     !newDeviceIdleState.equals("ACTIVE") || !lastKnownState.equals("ACTIVE")
        // then skipped exitDoze() entirely. The restores still ran, so the device behaved
        // correctly, but no EXIT row was written and the session's ENTER row stayed unmatched -
        // which the statistics parser skips, so whole sessions vanished from the UI.
        String newDeviceIdleState = getDeviceIdleState();
        if (ownedSession) {
            log("Exiting Doze (owned session), physical state: " + newDeviceIdleState);
            exitDoze(newDeviceIdleState);
        } else {
            // Nothing was owned, so no session boundary is invented. Note the old condition
            // would have written an EXIT row here whenever Android happened to report IDLE.
            log("No EnforceDoze session was owned on wake, physical state: " + newDeviceIdleState);
            DiagnosticLogger.i("DOZE", "no_owned_session_on_wake physicalState=" + newDeviceIdleState);
            // Belt and braces for markers left by an earlier failed restore.
            restoreSuspendedPackages("handleScreenOn/unowned");
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
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                wakeStartedAt = SystemClock.elapsedRealtime();
                wakeTiming("screen_on");
                boolean deviceLocked = Utils.isDeviceLocked(context);
                log("Screen ON received" + waitForUnlock);
                DiagnosticLogger.i("WAKE", "screen_on waitForUnlock=" + waitForUnlock
                        + " locked=" + deviceLocked);

                // Everything below runs before the waitForUnlock decision on purpose. The user is
                // looking at the launcher now; suspended apps must not stay greyed out until they
                // happen to unlock, and biometrics must be back before the unlock is attempted.
                cancelPendingEnterDoze();
                // Lifecycle enforcement rather than restoreSuspendedPackages(): when the keyguard
                // is still up with waitForUnlock the un-suspend must be TEMPORARY and must keep
                // session ownership, or the next screen-off has nothing left to re-suspend. With
                // waitForUnlock off, or already unlocked, this resolves to the same FINAL
                // un-suspend as before.
                enforcePackageStateForLifecycle("screen on");
                enforceBiometricStateForLifecycle("screen on");
                // Lock-screen sensor access. No-ops unless the session is still owned behind the
                // keyguard, so the waitForUnlock=false path is untouched.
                enforceSensorStateForLifecycle("screen on");

                // Safety net for the cases the callbacks cannot cover: VoIP below API 31, or
                // READ_PHONE_STATE not granted yet. handleCallStarted is latched, so when the
                // telephony callback already fired this is a no-op.
                if (callStateWatcher != null) {
                    if (callStateWatcher.isCallActive()) {
                        handleCallStarted("screen_on_fallback");
                    } else {
                        // Clears the latch for the degraded case where a call was only ever seen
                        // through this fallback and its end was therefore never observed.
                        callDozeExitDone.set(false);
                    }
                }

                if (!deviceLocked || !waitForUnlock) {
                    handleScreenOn(context, time, delay);
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                log("Screen OFF received");
                DiagnosticLogger.i("DOZE", "screen_off");

                // Conditions that END a session are decided before any restriction is re-applied,
                // so an owned session is never re-suspended only to be restored a moment later.
                if (isCallActiveNow()) {
                    // Latched, so this is a no-op once the call path has already released the
                    // session, and a guaranteed final release when the call was only ever noticed
                    // through the audio mode.
                    log("A call is active, letting the call path own the transition");
                    handleCallStarted("screen_off_fallback");
                } else if (disableWhenCharging && Utils.isConnectedToCharger(getApplicationContext())) {
                    if (dozeStateStore.isInDoze()) {
                        endOwnedDozeSession("charging");
                    } else {
                        log("Connected to charger and disableWhenCharging=true, skip entering Doze");
                    }
                } else if (!Utils.isInsideCustomDozePeriod(context)) {
                    if (dozeStateStore.isInDoze()) {
                        endOwnedDozeSession("outside custom Doze period");
                    } else {
                        log("Outside custom Doze periods, skip entering Doze");
                    }
                } else if (dozeStateStore.isInDoze()) {
                    // Owned-session continuation after a lock-screen wake.
                    enforcePackageStateForLifecycle("screen off");
                    enforceSensorStateForLifecycle("screen off");
                    enforceBiometricStateForLifecycle("screen off");
                    resumeOwnedDozeAfterLockedWake("screen off");
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
                if (disableWhenCharging) {
                    // Any pending fresh entry is dropped whether or not a session is owned.
                    cancelPendingEnterDoze();
                    // Gated on ownership rather than on Android's physical idle state, for the
                    // same reason as handleScreenOn: the old "IDLE || screen off" test could both
                    // miss a real owned session and invent an EXIT row for a natural system Doze
                    // that EnforceDoze never started.
                    if (dozeStateStore.isInDoze()) {
                        log("Charger connected, exiting the owned Doze session");
                        endOwnedDozeSession("charger connected");
                    }
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
                    // Maintenance is a property of a session this app owns, so it needs durable
                    // ownership and not merely a physical transition with the screen off. A
                    // PREPARING attempt makes the device enter and leave deep idle before any
                    // session exists - a force that succeeds and is then invalidated by a call or a
                    // charger is unforced while the screen is still off - and the resulting
                    // broadcast used to be read as a maintenance window: an EXIT_MAINTENANCE row,
                    // maintenance=true, and device states restored for a session that never
                    // started. The next physical IDLE would then write ENTER_MAINTENANCE and run
                    // the network entry work on top of nothing. The physical state above is still
                    // recorded; only the bookkeeping is gated.
                    if (!dozeStateStore.isInDoze()) {
                        DiagnosticLogger.i("DOZE", "device_idle_mode_changed_ignored"
                                + " reason=no_owned_session deepIdle=" + inDeepIdle);
                    } else if (!inDeepIdle) {
                        if (!maintenance) {
                            log("Device exited Doze for maintenance");
                            DiagnosticLogger.i("DOZE", "maintenance_enter");
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
                            DiagnosticLogger.i("DOZE", "maintenance_exit");
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
