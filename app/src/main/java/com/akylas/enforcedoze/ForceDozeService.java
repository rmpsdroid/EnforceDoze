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
import java.util.concurrent.atomic.AtomicLong;
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
    /**
     * Notification toggles get the same single-slot treatment as the other physical toggles, and
     * for the same concrete reason. The whole set is already one joined shell invocation, but
     * ShizukuHandler runs every command on its own thread and its own remote process, so the
     * DISABLE issued at entry and the ENABLE issued on the wake can be in flight together and
     * complete in either order. If the older DISABLE lands last, notifications stay off after the
     * Doze session has ended and nothing is left to notice.
     * <p>
     * One slot for the one toggle, latest request wins, and an in-flight command is never
     * completed artificially - it always runs to its real callback, which is what then releases the
     * newest pending request. Deliberately narrow: unrelated Shizuku commands still run
     * concurrently, exactly as upstream intends.
     * <p>
     * The pending request holds the built command, which is the package-set snapshot and the target
     * state together, so a superseded request cannot later be re-resolved against a different set
     * of installed packages or uids.
     */
    private final Object notificationOpLock = new Object();
    private boolean notificationOpInFlight = false;
    private String pendingNotificationCommand = null;
    private boolean pendingNotificationEnabled = false;
    private int pendingNotificationCount = 0;

    /**
     * Motion sensors get the same treatment as the other physical toggles, and for the same
     * concrete reason. Ordering the Java dispatches is not enough: every command runs on its own
     * thread and its own remote process, so "restrict" dispatched at entry and "enable" dispatched
     * moments later by the wake restore can complete in either order, and the losing order leaves
     * the sensors restricted after the session is over with the journal already clear. One slot per
     * physical toggle, latest target wins, and an in-flight command is never completed artificially.
     */
    private final Object motionOpLock = new Object();
    private boolean motionOpInFlight = false;
    private Boolean pendingMotionRestrict = null;
    private Shell.OnCommandResultListener2 pendingMotionCallback = null;
    private String pendingMotionLabel = null;

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

    /**
     * Classification of a session-scoped async result, decided under
     * {@link #physicalEntryLock}: eligible to apply now, belonging to a session that is alive but
     * temporarily showing its lock screen, or belonging to a session that is over.
     */
    private static final int WORK_ELIGIBLE = 0;
    private static final int WORK_LOCKED_WAKE = 1;
    private static final int WORK_STALE = 2;
    private int physicalEntryPhase = PHASE_NONE;

    /**
     * Identifies one privileged fresh force attempt. Deliberately separate from the ACTIVE logical
     * session identity: a locked SCREEN_ON with waitForUnlock invalidates any pending fresh entry
     * while the session already owned continues to live.
     */
    private int entryAttemptToken = 0;

    /**
     * Two-signal confirmation for a fresh privileged entry, guarded by {@link #physicalEntryLock}.
     * <p>
     * The device proved that a completed force-idle can report success while
     * PowerManager.isDeviceIdleMode() is still false: the controller printed "Now forced in to deep
     * idle mode", the command exited 0, the immediate read was false, and the transition became
     * visible 47 ms later. Treating that single sample as proof of refusal aborted PREPARING and
     * left the device forced with every durable flag clear - an orphaned force with nothing
     * recording it.
     * <p>
     * A commit therefore needs both halves, for the same still-current attempt: the command must
     * have been accepted, and deep idle must actually have been observed. Neither alone is enough.
     * A broadcast on its own proves the device is idle but says nothing about the result of
     * <em>this</em> command - the transition could be anyone's - so it is recorded and waited on
     * rather than acted upon.
     * <p>
     * In memory only. {@link DozeStateStore#isEntryPending()} remains the durable record across
     * process death, and its existing conservative recovery is unchanged.
     */
    private int pendingEntryConfirmToken = 0;
    private boolean pendingEntryCommandAccepted = false;
    private boolean pendingEntryIdleObserved = false;

    /**
     * What DeviceIdleController itself said about the force. Used only to tell a refusal apart from
     * a transition that has not become visible yet - never to claim success, which always requires a
     * real physical observation.
     */
    private static final int CONTROLLER_UNKNOWN = 0;
    private static final int CONTROLLER_SUCCESS = 1;
    private static final int CONTROLLER_REFUSED = 2;

    /**
     * Phase of the owned-session physical reforce - the force-idle issued on behalf of a session
     * that is ALREADY committed, from the lock-screen resume and from recovery Mode A.
     * <p>
     * Kept apart from {@link #physicalEntryPhase}, which belongs to the fresh PREPARING protocol.
     * A reforce claims no PREPARING marker, writes no ENTER row and allocates no generation; what it
     * shares with fresh entry is only that it dispatches a real physical transaction whose outcome
     * arrives later, on another thread, and can outlive the session that asked for it.
     * <pre>
     * REFORCE_NONE               nothing in flight
     * REFORCE_FORCING            force dispatched, callback outstanding
     * REFORCE_CLEANUP_PENDING    corrective unforce owed, none dispatched
     * REFORCE_CLEANUP_IN_FLIGHT  corrective unforce dispatched, callback outstanding
     * </pre>
     * Every state other than REFORCE_NONE implies the durable marker is set. REFORCE_NONE with the
     * marker set is also legal and means "a durable transaction exists but no live command can tell
     * us its outcome" - a recreated process, or a durable clear that failed. That state is resolved
     * conservatively, by unforcing.
     */
    private static final int REFORCE_NONE = 0;
    private static final int REFORCE_FORCING = 1;
    private static final int REFORCE_CLEANUP_PENDING = 2;
    private static final int REFORCE_CLEANUP_IN_FLIGHT = 3;

    /** Guarded by {@link #physicalEntryLock}. */
    private int ownedReforcePhase = REFORCE_NONE;
    /** Session identity the outstanding reforce belongs to. Guarded by {@link #physicalEntryLock}. */
    private long ownedReforceEpoch = EPOCH_NONE;

    /**
     * The temporary physical release performed when an owned session's lock screen becomes visible.
     * <p>
     * A locked wake with waitForUnlock keeps the logical session, its epoch and its package
     * generation, but the device must not stay in forced deep idle while the user is looking at it.
     * Every existing unforce was welded to something else - session exit, the PREPARING debt, or the
     * reforce debt - so this is the one physical operation that leaves ownership completely alone.
     * <p>
     * It is serialized against the reforce for the obvious reason: commands run on independent
     * threads and processes, so an unforce dispatched at SCREEN_ON and a force dispatched at the
     * following SCREEN_OFF could complete in either order. The losing order leaves the phone awake
     * and unrestricted with the screen off. Only one of the two may ever be outstanding, and while
     * this one is, nothing samples pm.isDeviceIdleMode() to decide anything - that sample is exactly
     * what is untrustworthy until the release settles.
     */
    private static final int RELEASE_NONE = 0;
    private static final int RELEASE_IN_FLIGHT = 1;

    /** Guarded by {@link #physicalEntryLock}. */
    private int lockedWakeReleasePhase = RELEASE_NONE;
    /** Session identity the outstanding release belongs to. Guarded by {@link #physicalEntryLock}. */
    private long lockedWakeReleaseEpoch = EPOCH_NONE;

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

    /**
     * "A fresh entry became due, and the selected Shizuku backend was not there."
     * <p>
     * Armed in exactly one place: the backend decision inside enterDoze(), which is only ever
     * reached once the configured entry delay has already elapsed and every ordinary entry guard
     * has passed. That is what keeps dozeEnterDelay intact - a Shizuku binder arriving while the
     * delayed task is still waiting finds this false and does nothing, because no entry has become
     * due yet. It also means an unrelated reconnect can never start a session that was never asked
     * for.
     * <p>
     * In-memory: it describes an intent this process formed, and a process that dies forms it again
     * from the next screen-off. Cleared by {@link #invalidateDesiredEntry(String)} along with the
     * rest of the pending-entry state, so every path that cancels a fresh entry cancels this too.
     */
    private final AtomicBoolean shizukuFreshEntryDeferred = new AtomicBoolean(false);

    /**
     * "A real fresh entry became due, but owned-reforce or restore debt had priority."
     * <p>
     * Deliberately separate from {@link #shizukuFreshEntryDeferred}, which means something else -
     * the backend was absent - and is consumed by different events. Armed only where an otherwise
     * valid entry is refused specifically because an owned reforce is unresolved, and cleared by
     * the same lifecycle invalidation that cancels any other pending entry.
     * <p>
     * It exists because both the reforce debt and the restore debt are settled asynchronously, and
     * between them a screen-off that legitimately wanted a session can be refused with nothing left
     * to try again: the reforce cleanup finishes, sees restore debt and defers; the restore finishes
     * later; and no further screen-off is coming. In memory only - the entry it remembers is the
     * one this process was asked for, and a process that dies is asked again.
     */
    private final AtomicBoolean ownedReforceFreshEntryDeferred = new AtomicBoolean(false);

    /**
     * Identity of the ACTIVE logical session, deliberately separate from {@link #entryAttemptToken}.
     * The attempt token identifies a PREPARING physical force and is invalidated by anything that
     * merely cancels a pending entry - including a locked SCREEN_ON, which leaves the session it
     * interrupts perfectly alive. Async work belonging to a session needs the opposite: an identity
     * that survives a lock-screen wake and changes only when the session really ends.
     * <p>
     * Read from Shizuku command threads and from TimerTasks while lifecycle events write it from the
     * main thread, so it is atomic rather than lock-guarded; using the physical-entry lock here
     * would couple unrelated work to the entry transition for no benefit.
     * <p>
     * Not durable, and does not need to be: every callback and timer it guards dies with the process
     * that created them. A process that adopts an already-durable session mints a fresh local epoch
     * for its own async work instead.
     */
    private static final long EPOCH_NONE = 0L;
    private final AtomicLong activeSessionEpoch = new AtomicLong(EPOCH_NONE);
    private final AtomicLong sessionEpochCounter = new AtomicLong(EPOCH_NONE);

    /**
     * Epoch whose motion-sensor restriction was postponed because the lock screen came up before
     * the two-second task fired. Applied once when that same session's screen goes off again, and
     * discarded when the session ends. In-memory only: the task it stands in for would have died
     * with the process anyway.
     */
    private final AtomicLong deferredMotionEpoch = new AtomicLong(EPOCH_NONE);
    private static final String SENSOR_LABEL_LOCKSCREEN_RESTORE = "lockscreen_restore";
    private static final String SENSOR_LABEL_LOCKSCREEN_REAPPLY = "lockscreen_reapply";
    private static final String SENSOR_LABEL_ENTER = "doze_enter";
    private static final String SENSOR_LABEL_FINAL = "final_restore";
    private static final String BIOMETRIC_LABEL_LOCKSCREEN_RESTORE = "biometric_lockscreen_restore";
    private static final String BIOMETRIC_LABEL_LOCKSCREEN_REAPPLY = "biometric_lockscreen_reapply";
    private static final String BIOMETRIC_LABEL_FINAL = "biometric_final_restore";
    private static final String MOTION_LABEL_ENTER = "motion_enter";
    private static final String MOTION_LABEL_RESUME = "motion_resume";
    private static final String MOTION_LABEL_FINAL = "motion_final_restore";
    private static final String MOTION_LABEL_SERVICE_STOP = "motion_service_stop";
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
        // First: an unresolved owned reforce is the only state that can leave the device physically
        // forced with no owner at all. Its cleanup is asynchronous, so applyRecoveryPolicy below
        // runs before the callback lands - which is safe because that path, fresh entry and the
        // deferred retry all gate on the durable marker and refuse while it is set.
        maybeResolveOwnedReforceDebt("shizuku available");
        applyRecoveryPolicy("SHIZUKU_RECOVERY", "Shizuku became available");
        maybeRetryDeferredShizukuEntry("shizuku_available");
    }

    /**
     * Liveness for the deferral above. Without it, a screen-off that was declined because Shizuku
     * was missing would wait for the next screen-off - which, overnight, may be hours away or may
     * never come.
     * <p>
     * Recovery has already run by the time this is reached, and it has priority: while any restore
     * debt is still outstanding, a fresh session must not be started on top of it. That is the same
     * rule handleCallEnded() applies, and for the same reason - a new entry would allocate a new
     * package generation and lose whatever the unfinished restore still owes.
     * <p>
     * Nothing here re-implements entry. It asks the ordinary predicates and then calls the ordinary
     * enterDoze(), so the existing barrier, PREPARING protocol and physical verification all apply
     * unchanged. That is also what settles the race against a simultaneous SCREEN_ON: if the wake
     * wins, enterDoze() finds the screen on and skips; if it arrives after the force is dispatched,
     * the attempt token is bumped and the result is cleaned up rather than committed. A session can
     * never appear after a wake has been processed.
     */
    /**
     * Arms the deferred fresh-entry intent, but only if the entry is still due at the moment the
     * intent is recorded.
     * <p>
     * Under the same monitor as {@link #invalidateDesiredEntry(String)}, because the two race
     * directly. A delayed entry task passes its screen-off check, is overtaken by SCREEN_ON - which
     * cancels every pending entry, this intent included - and then carries on to discover the
     * backend is missing. Arming unconditionally there would attach the old screen-off's intent to
     * a device that is now awake, and a later screen-off plus a Shizuku reconnect could consume it
     * and enter while that new screen-off's configured delay was still running. Every fact is
     * therefore re-read here rather than inherited from the caller: if the wake takes the monitor
     * first nothing is armed, and if the deferral takes it first the wake's invalidation clears it
     * immediately afterwards.
     * <p>
     * Dispatches nothing, so it is safe to call from a caller already holding the monitor.
     */
    private void armDeferredShizukuEntryIfStillWanted(Context context, String stage) {
        synchronized (physicalEntryLock) {
            if (serviceStopping
                    || !Utils.isShizukuMode(context)
                    || isShizukuAvailable
                    || dozeStateStore.isEntryPending()
                    // Carries screen-off, no call, the conditional charging predicate, the custom
                    // period and !inDoze.
                    || !isFreshEntryStillWanted(context)) {
                log("Not deferring Doze entry, it is no longer due");
                DiagnosticLogger.i("DOZE", "entry_deferral_not_armed stage=" + stage);
                return;
            }
            shizukuFreshEntryDeferred.set(true);
            log("Shizuku mode is selected but Shizuku is not available, deferring Doze entry");
            DiagnosticLogger.i("DOZE", "entry_deferred reason=shizuku_unavailable stage=" + stage);
        }
    }

    private void maybeRetryDeferredShizukuEntry(String reason) {
        // Cheapest possible no-op for the overwhelmingly common case: nothing was ever deferred.
        // This is what makes it safe to call from restore-completion callbacks.
        if (!shizukuFreshEntryDeferred.get()) {
            return;
        }
        Context context = getApplicationContext();
        if (serviceStopping) {
            shizukuFreshEntryDeferred.set(false);
            return;
        }
        // The configured mode is the trigger, not availability. If the user has moved away from
        // Shizuku the intent is meaningless and is dropped; the newly selected mode takes over at
        // the next screen-off.
        if (!Utils.isShizukuMode(context)) {
            shizukuFreshEntryDeferred.set(false);
            DiagnosticLogger.i("DOZE", "entry_deferral_cancelled reason=execution_mode_changed");
            return;
        }
        // Still waiting for the backend. Intent stays armed.
        if (!isShizukuAvailable) {
            return;
        }
        // PREPARING owns the transition and has its own post-cleanup re-evaluation; duplicating it
        // here would be a second entry protocol. Intent stays armed.
        if (dozeStateStore.isEntryPending()) {
            DiagnosticLogger.i("DOZE", "entry_retry_skipped reason=entry_pending");
            return;
        }
        // Recovery keeps priority. A fresh session started over an unfinished restore would
        // allocate a new package generation and lose whatever that restore still owes, so the
        // intent stays armed and the completion of the last debt tries again.
        if (dozeStateStore.hasAppliedSuspendedPackages() || dozeStateStore.hasPendingRestore()) {
            DiagnosticLogger.i("DOZE", "entry_retry_skipped reason=pending_restore"
                    + " pendingStates=" + dozeStateStore.getAppliedKeys().size()
                    + " suspendedPackages=" + dozeStateStore.getAppliedSuspendedPackages().size());
            return;
        }
        // Carries the screen-off, call, conditional-charging, custom-period and !inDoze tests. A
        // failure here is not a wait: the reason the entry was wanted has gone, so the intent is
        // consumed and the ordinary lifecycle takes over. !inDoze is what stops an owned session -
        // including one being re-forced by recovery Mode A - from ever growing a second session.
        if (!isFreshEntryStillWanted(context)) {
            shizukuFreshEntryDeferred.set(false);
            DiagnosticLogger.i("DOZE", "entry_deferral_cancelled reason=policy_not_met");
            return;
        }
        // Exactly one caller consumes the intent, so duplicate availability callbacks and a restore
        // completion landing together cannot produce two attempts.
        if (!shizukuFreshEntryDeferred.compareAndSet(true, false)) {
            return;
        }
        log("Starting the Doze entry that was deferred while Shizuku was unavailable");
        DiagnosticLogger.i("SHIZUKU", "backend_available_reentry");
        DiagnosticLogger.i("DOZE", "entry_retried reason=" + reason);
        enterDoze(context);
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
        boolean ownedReforcePending = dozeStateStore.isOwnedReforcePending();

        // A durable inDoze flag is itself something to recover, independently of any package or
        // device-state marker. A configuration that only forces idle - no Hard Suspend blocklist
        // and no optional radio/sensor restrictions - owns a logical session with neither marker
        // set, and the old condition returned RECOVERY_NONE for it, so applyRecoveryPolicy and its
        // Mode C finalization were never reached and the session was left owned with no EXIT.
        // entryPending is a fourth independent reason to recover: an interrupted force-idle owns no
        // session and no marker, so without this term the one state that can leave the device
        // physically forced would return RECOVERY_NONE and never be resolved.
        if (!inDoze && !hasPackages && !hasStates && !entryPending && !ownedReforcePending) {
            log("RECOVERY_NONE: service recreated with nothing pending");
            return;
        }

        DiagnosticLogger.i("RECOVERY", "RECOVERY_CHECK screenOn=" + screenOn + " inDoze=" + inDoze
                + " entryPending=" + entryPending
                + " ownedReforcePending=" + ownedReforcePending
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
            // Kept as a safety net for the case where no session was owned and therefore no journal
            // marker exists to drive a restore, but routed through the serializer: a direct write
            // here could overtake, or be overtaken by, the restore dispatched a few lines below.
            requestMotionSensorState(false, null, MOTION_LABEL_SERVICE_STOP);
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
        // One non-blocking opportunity to discharge an owned-reforce debt while the shell backends
        // are still up. It is a no-op while a force or an unforce is already outstanding, so it
        // neither duplicates a cleanup nor issues an unforce beside a force whose callback owns the
        // classification. Nothing is waited on, the marker is never cleared optimistically, and the
        // continuation is suppressed because serviceStopping is already set.
        if (dozeStateStore.isOwnedReforcePending()) {
            maybeResolveOwnedReforceDebt("service destroyed");
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
        if (!useShizuku && shizukuFreshEntryDeferred.getAndSet(false)) {
            DiagnosticLogger.i("DOZE", "entry_deferral_cancelled reason=execution_mode_changed");
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
        // Last, once every field this service reasons with has been refreshed. A mode change is a
        // real retry opportunity - the backend that could not run the corrective unforce a moment
        // ago may be usable now - but the cleanup completes on another thread, and its continuation
        // must not observe a half-applied settings pass.
        maybeResolveOwnedReforceDebt("settings reloaded");
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
     * Starts a new ACTIVE logical session identity. Called once per genuinely fresh session, on
     * both entry backends, before any session-scoped async work is spawned.
     */
    private long beginActiveSessionEpoch(String reason) {
        long epoch = sessionEpochCounter.incrementAndGet();
        activeSessionEpoch.set(epoch);
        DiagnosticLogger.i("DOZE", "session_epoch_started epoch=" + epoch + " reason=" + reason);
        return epoch;
    }

    /**
     * Mints a local identity for a session this process did not start, so async work it creates has
     * something valid to capture. Not a fresh entry: no ENTER row, no package generation and no
     * change to anything durable - the session on disk is simply adopted as it stands.
     */
    private long ensureActiveSessionEpoch(String reason) {
        // Under the barrier so adoption has exactly one winner. Two threads arriving together -
        // service recreation recovery and a Shizuku reconnect, which is a realistic pairing - could
        // otherwise both read NONE and mint two identities for the same durable session, after
        // which work tagged with the losing identity would be treated as stale for ever.
        synchronized (physicalEntryLock) {
            if (!dozeStateStore.isInDoze()) {
                return EPOCH_NONE;
            }
            long current = activeSessionEpoch.get();
            if (current != EPOCH_NONE) {
                return current;
            }
            long epoch = sessionEpochCounter.incrementAndGet();
            activeSessionEpoch.set(epoch);
            DiagnosticLogger.i("DOZE", "session_epoch_adopted epoch=" + epoch + " reason=" + reason);
            return epoch;
        }
    }

    /**
     * Decides whether a session-scoped async result may still act. Must be called while holding
     * {@link #physicalEntryLock}, so that the decision and the work it authorises cannot be split
     * by a lifecycle event - an unsynchronised check would only narrow the stale-callback window
     * rather than close it.
     * <p>
     * A wake is not automatically the end of the session. With waitForUnlock the keyguard can come
     * up mid-entry and go away again with the session still owned throughout, so that case is
     * reported separately instead of being collapsed into "stale": dropping it would silently lose
     * the entry work for the rest of that session.
     */
    private int classifySessionEntryWork(long capturedEpoch) {
        Context context = getApplicationContext();
        if (serviceStopping) {
            // Teardown is already running; establishing new entry work now would race the restores
            // onDestroy is in the middle of dispatching.
            return WORK_STALE;
        }
        if (!isActiveSessionEpoch(capturedEpoch)) {
            return WORK_STALE;
        }
        if (isCallActiveNow()) {
            return WORK_STALE;
        }
        if (disableWhenCharging && Utils.isConnectedToCharger(context)) {
            return WORK_STALE;
        }
        if (!Utils.isInsideCustomDozePeriod(context)) {
            return WORK_STALE;
        }
        if (!Utils.isScreenOn(context)) {
            return WORK_ELIGIBLE;
        }
        // Screen on: only a keyguard this session is expected to outlive keeps it alive.
        return (waitForUnlock && Utils.isDeviceLocked(context)) ? WORK_LOCKED_WAKE : WORK_STALE;
    }

    /**
     * Decides, as one barrier operation, whether SCREEN_OFF is continuing an already-owned session
     * and which session that is, and performs the package enforcement that belongs to the same
     * decision.
     * <p>
     * The package enforcement is here because it races the focused-app callback for the journal:
     * that callback, landing during a locked wake, claims the generation without suspending
     * anything and relies on this call to do the physical work. Serialised with it, either the
     * callback journals first and this suspends that exact generation, or this runs first and the
     * callback then classifies as eligible and does both itself - one generation, one blocklist
     * snapshot, and no suspension while the lock screen is up.
     *
     * @return the epoch of the session being continued, or {@link #EPOCH_NONE} when there is none
     * and SCREEN_OFF should take its ordinary fresh-entry path
     */
    private long claimOwnedSessionForScreenOff() {
        synchronized (physicalEntryLock) {
            if (!dozeStateStore.isInDoze()) {
                return EPOCH_NONE;
            }
            long epoch = activeSessionEpoch.get();
            if (!isActiveSessionEpoch(epoch)) {
                // Owned on disk but with no local identity - nothing this process can scope work
                // to. Recovery adopts an epoch for such a session; this event does not invent one.
                DiagnosticLogger.i("DOZE", "screen_off_owned_claim_skipped reason=no_local_epoch");
                return EPOCH_NONE;
            }
            enforcePackageStateForLifecycle("screen off");
            return epoch;
        }
    }

    /**
     * Physically leaves forced deep idle while an owned session's lock screen is showing, without
     * ending anything.
     * <p>
     * This is the piece that was simply missing: the fork already restores packages, sensors and
     * biometrics temporarily for a locked wake, but nothing ever released the force, so the device
     * stayed in deep idle behind a visible lock screen - and, because the force never dropped, the
     * following screen-off found pm.isDeviceIdleMode() still true and never performed a genuine
     * owned-session reforce either.
     * <p>
     * Touches no ownership whatsoever: not inDoze, not the epoch, not the package generation, not
     * any journal marker other than the shared physical-transaction bit, and it writes neither ENTER
     * nor EXIT.
     */
    private void releasePhysicalDozeForLockedWake(String reason, long expectedEpoch) {
        final int plan;
        final long epoch;
        synchronized (physicalEntryLock) {
            if (serviceStopping) {
                return;
            }
            // EPOCH_NONE means the caller has no prior identity to honour - a live ACTION_SCREEN_ON
            // is simply an event, and the barrier is where it first decides which session it is
            // looking at. A caller that already adopted or claimed an epoch passes it instead, so a
            // delayed continuation cannot release a session that replaced the one it was started
            // for.
            if (expectedEpoch == EPOCH_NONE) {
                epoch = activeSessionEpoch.get();
            } else {
                epoch = expectedEpoch;
                if (activeSessionEpoch.get() != expectedEpoch) {
                    DiagnosticLogger.i("DOZE", "lockscreen_release_skipped reason=stale_session"
                            + " expectedEpoch=" + expectedEpoch);
                    return;
                }
            }
            if (epoch == EPOCH_NONE || !isActiveSessionEpoch(epoch)) {
                return;
            }
            Context context = getApplicationContext();
            // Only for a keyguard this session is expected to outlive. A full wake is a session
            // exit and is owned by handleScreenOn.
            if (!Utils.isScreenOn(context) || !waitForUnlock || !Utils.isDeviceLocked(context)) {
                return;
            }
            if (isCallActiveNow()) {
                return;
            }
            if (dozeStateStore.isEntryPending() || physicalEntryPhase != PHASE_NONE) {
                DiagnosticLogger.i("DOZE", "lockscreen_release_skipped reason=fresh_entry_active");
                return;
            }
            if (isOwnedReforceUnresolved()) {
                // The reforce state machine already owns this: its callback classifies a locked
                // wake as WORK_LOCKED_WAKE and issues the corrective unforce itself. A second
                // command here would be the duplicate that protocol exists to avoid.
                DiagnosticLogger.i("DOZE", "lockscreen_release_skipped reason=owned_reforce_unresolved");
                return;
            }
            if (lockedWakeReleasePhase != RELEASE_NONE) {
                return;
            }
            if (!pm.isDeviceIdleMode()) {
                // Nothing to release. Trustworthy: no physical command of ours is outstanding.
                return;
            }

            plan = classifyReforcePlan(context);
            if (plan == REFORCE_PLAN_SHIZUKU_UNAVAILABLE) {
                // Selected Shizuku is absent. Nothing is dispatched, no marker is written, and no
                // other backend is borrowed - the session simply stays forced behind the lock
                // screen, which is what it did before this change.
                log("Shizuku mode is selected but Shizuku is not available, not releasing deep idle");
                DiagnosticLogger.i("DOZE", "lockscreen_release_deferred reason=shizuku_unavailable");
                return;
            }
            if (!isPrivilegedReforcePlan(plan)) {
                // The tunable path performs no physical force-idle transaction at all, so there is
                // genuinely nothing to release there.
                //
                // The pre-N legacy path is different and the distinction matters: it does issue a
                // real "dumpsys deviceidle force-idle". It is excluded here only because it has
                // never had the callback-backed transaction protocol the durable marker depends on,
                // so a release could not be settled. That is an existing pre-N compatibility
                // limitation carried forward unchanged, not a claim that nothing is forced.
                DiagnosticLogger.i("DOZE", "lockscreen_release_skipped reason=no_tracked_transaction"
                        + " plan=" + reforcePlanName(plan));
                return;
            }
            // Durable before dispatch, exactly as a reforce is. A release that succeeds changes
            // physical state, so an interrupted one must leave a record; the shared marker means
            // "an owned-session physical transaction is unresolved", and its existing conservative
            // recovery - unforce, clear, then let ordinary policy decide - is already the right
            // answer for an interrupted release.
            if (!dozeStateStore.beginOwnedReforceAttempt()) {
                log("Could not record the locked-wake release, not releasing deep idle");
                DiagnosticLogger.e("DOZE", "lockscreen_release_aborted reason=journal_write_failed");
                return;
            }
            lockedWakeReleaseEpoch = epoch;
            lockedWakeReleasePhase = RELEASE_IN_FLIGHT;
        }

        log("Temporarily leaving forced deep idle for the lock screen (" + reason + ")");
        DiagnosticLogger.i("DOZE", "lockscreen_release_start epoch=" + epoch
                + " plan=" + reforcePlanName(plan));
        dispatchOnCapturedPlan(plan, "dumpsys deviceidle unforce", "lockscreen_unforce", false,
                (commandCode, exitCode, stdout, stderr) -> onLockedWakeReleaseResult(epoch, exitCode));
    }

    /**
     * Settles a locked-wake release and decides what the session needs next.
     * <p>
     * The exit code says whether the command ran; it is never used to decide lifecycle. That comes
     * from the existing classifier, against the epoch this release was started for.
     */
    private void onLockedWakeReleaseResult(long capturedEpoch, int exitCode) {
        boolean physicallyIdle = verifyPhysicalDozeEntered();

        boolean cleared;
        boolean reevaluate = false;
        int state;
        synchronized (physicalEntryLock) {
            state = classifySessionEntryWork(capturedEpoch);
            lockedWakeReleasePhase = RELEASE_NONE;
            lockedWakeReleaseEpoch = EPOCH_NONE;

            DiagnosticLogger.i("DOZE", "lockscreen_release_result exit=" + exitCode
                    + " idleMode=" + physicallyIdle + " lifecycle=" + sessionWorkStateName(state));

            cleared = dozeStateStore.finishOwnedReforceAttempt();
            if (!cleared) {
                // The physical command may well have worked, but nothing durable says so. It is not
                // reported as settled and no force may follow it; the ordinary conservative resolver
                // owns it from here - unforce, clear, then re-evaluate policy - which is safe
                // because unforcing an already-unforced device is a no-op.
                DiagnosticLogger.e("DOZE", "lockscreen_release_journal_clear_failed");
            } else if (state == WORK_ELIGIBLE) {
                // The screen went off again while this was outstanding, so the session wants deep
                // idle back. Exactly one ordinary re-evaluation, through the existing reforce
                // machinery: same epoch, no ENTER, no EXIT, no new generation. This also covers a
                // failed unforce, where the re-evaluation simply finds the device still idle and
                // does nothing.
                reevaluate = true;
            }
            // WORK_LOCKED_WAKE: still behind the keyguard, stay released.
            // WORK_STALE: the session ended; never reforce it.
        }

        if (!cleared) {
            maybeResolveOwnedReforceDebt("locked-wake release journal clear failed");
            return;
        }
        // The shared marker gates fresh entry, so while this release held it a legitimate new
        // session could have been refused and its intent parked. Clearing the marker here is the
        // moment that becomes retryable, and the existing settlement helper is what knows how to do
        // it: it no-ops while any session is owned, and with none it consults the restore debt
        // before consuming the deferred intent. Skipping it would let that entry sit deferred with
        // nothing left to release it.
        onOwnedReforceSettled(false);
        if (reevaluate) {
            ensureOwnedDozePhysicalState("locked-wake release settled", capturedEpoch);
        }
    }

    private static String sessionWorkStateName(int state) {
        switch (state) {
            case WORK_ELIGIBLE:
                return "eligible";
            case WORK_LOCKED_WAKE:
                return "locked_wake";
            default:
                return "stale";
        }
    }

    private void logDeferredAsyncWork(String type) {
        log("Deferring asynchronous Doze entry work until the screen goes off again (" + type + ")");
        DiagnosticLogger.i("DOZE", "async_session_work_deferred type=" + type + " reason=locked_wake");
    }

    /** The motion restriction itself, shared by the entry task and the deferred resume. */
    private void applyMotionSensorRestriction(String label) {
        requestMotionSensorState(true, null, label);
    }

    /**
     * Applies a motion restriction that was postponed by a lock-screen wake, once, when that same
     * session's screen goes off again. No new two-second timer is armed for a continuation: the
     * delay exists to let the screen finish turning off on a fresh entry, and re-arming it on every
     * lock-screen cycle would be a different feature.
     */
    private void applyDeferredMotionRestrictionIfDue(String reason, long expectedEpoch) {
        synchronized (physicalEntryLock) {
            long deferred = deferredMotionEpoch.get();
            if (deferred == EPOCH_NONE) {
                return;
            }
            // The deferral must belong to the session this continuation is servicing. Without this
            // an old continuation could apply a restriction a newer session had postponed, and
            // consume that session's deferral in the process.
            if (deferred != expectedEpoch) {
                DiagnosticLogger.i("DOZE", "async_session_work_skipped reason=stale_session"
                        + " type=motion_resume");
                return;
            }
            if (classifySessionEntryWork(deferred) != WORK_ELIGIBLE) {
                return;
            }
            deferredMotionEpoch.set(EPOCH_NONE);
            DiagnosticLogger.i("DOZE", "async_session_work_resumed type=motion reason=" + reason);
            applyMotionSensorRestriction(MOTION_LABEL_RESUME);
        }
    }

    /**
     * Ends the local session identity. Only for a real session end, never for a lock-screen wake
     * that leaves the session owned.
     * <p>
     * The durable inDoze flag alone would already make a stale callback refuse, but only until the
     * next session begins: without this, an old callback holding epoch 10 would start passing again
     * the moment a later session set inDoze back to true. Retiring the epoch is what makes the
     * refusal permanent.
     */
    private void endActiveSessionEpoch(String reason) {
        deferredMotionEpoch.set(EPOCH_NONE);
        long previous = activeSessionEpoch.getAndSet(EPOCH_NONE);
        if (previous != EPOCH_NONE) {
            DiagnosticLogger.i("DOZE", "session_epoch_ended epoch=" + previous + " reason=" + reason);
        }
    }

    /**
     * The common guard for session-scoped async work: the session must still be owned on disk, and
     * it must be the same session that spawned the work.
     */
    private boolean isActiveSessionEpoch(long capturedEpoch) {
        return capturedEpoch != EPOCH_NONE
                && activeSessionEpoch.get() == capturedEpoch
                && dozeStateStore.isInDoze();
    }

    private void logStaleAsyncWork(String type) {
        log("Skipping asynchronous Doze entry work from a finished session (" + type + ")");
        DiagnosticLogger.i("DOZE", "async_session_work_skipped reason=stale_session type=" + type);
    }

    /**
     * True while an owned-session reforce is unresolved, on disk or in this process. Nothing may
     * claim a new logical session while it is: the physical force-idle state is unaccounted for,
     * and a fresh session committed on top of it could neither own it nor undo it - most obviously
     * when the new session runs on the tunable backend, whose exit path writes device_idle constants
     * and never unforces at all.
     * <p>
     * Must be called while holding {@link #physicalEntryLock}.
     */
    private boolean isOwnedReforceUnresolved() {
        return ownedReforcePhase != REFORCE_NONE || dozeStateStore.isOwnedReforcePending();
    }

    /**
     * Resolves an unresolved owned-reforce debt, conservatively and at most once at a time.
     * <p>
     * The resolution is always a corrective unforce, never an adoption of the existing physical
     * state. pm.isDeviceIdleMode() cannot distinguish a force this app caused from a device that is
     * naturally idle, and a durable inDoze=true proves only that a logical session exists - not that
     * an uncertain physical transaction belongs to it, which stops being true as soon as the
     * execution mode changes underneath a live session. Unforcing first and letting recovery Mode A
     * re-establish deep idle for the same session is deterministic; guessing is not. Mode A writes
     * no ENTER, no EXIT, no package generation and no new session epoch, so the cost is one brief
     * idle exit.
     */
    private void maybeResolveOwnedReforceDebt(String reason) {
        boolean dispatch = false;
        synchronized (physicalEntryLock) {
            if (!dozeStateStore.isOwnedReforcePending()) {
                return;
            }
            if (ownedReforcePhase == REFORCE_FORCING) {
                // The force callback owns the outcome and will classify it. Issuing an unforce
                // alongside a force still executing is exactly the inversion this protocol exists
                // to prevent.
                return;
            }
            if (ownedReforcePhase == REFORCE_CLEANUP_IN_FLIGHT) {
                return;
            }
            if (lockedWakeReleasePhase == RELEASE_IN_FLIGHT) {
                // The marker is shared, and right now it belongs to a locked-wake release whose own
                // callback will settle it. Dispatching a cleanup unforce here would put a second
                // command alongside that one and could clear the marker underneath it.
                DiagnosticLogger.i("DOZE", "owned_reforce_cleanup_skipped reason=release_in_flight");
                return;
            }
            // REFORCE_CLEANUP_PENDING, or REFORCE_NONE with the durable marker still set.
            if (!hasPrivilegedCleanupBackend()) {
                ownedReforcePhase = REFORCE_CLEANUP_PENDING;
                log("No privileged backend available to undo an owned reforce, deferring");
                DiagnosticLogger.i("DOZE", "owned_reforce_cleanup_deferred"
                        + " reason=no_privileged_backend trigger=" + reason);
                return;
            }
            ownedReforcePhase = REFORCE_CLEANUP_IN_FLIGHT;
            dispatch = true;
        }
        if (dispatch) {
            DiagnosticLogger.i("DOZE", "owned_reforce_cleanup_started reason=" + reason);
            dispatchMarkerlessUnforce();
        }
    }

    /** True when some privileged backend can execute a corrective unforce right now. */
    private boolean hasPrivilegedCleanupBackend() {
        return shizukuHandler.isShizukuAvailable() || isSuAvailable;
    }

    /**
     * The corrective unforce for an owned reforce. Deliberately not executeCommandWithRoot(), which
     * re-reads the configured execution mode for every command: the backend that issued the force
     * may no longer be the selected one, and routing the undo to whatever is selected now can send
     * it to a backend that cannot run it at all.
     * <p>
     * Undoing physical state this app already caused is not new Doze work, so it may use any
     * privileged backend that is actually available. It changes no preference and starts nothing;
     * fresh entry continues to obey the configured mode exactly as before.
     * <p>
     * Touches no PREPARING state: it never calls beginForceIdleAttempt, abortForceIdleAttempt or
     * commitDozeSession, and never reads or writes the entry-pending key.
     */
    private void dispatchMarkerlessUnforce() {
        final String command = "dumpsys deviceidle unforce";
        Shell.OnCommandResultListener2 listener =
                (commandCode, exitCode, stdout, stderr) -> onMarkerlessUnforceResult(exitCode);

        // Re-read at the moment of dispatch, not inherited from the pre-flight check: the backend
        // that was available when the phase was claimed can be gone by now, and falling through to
        // root regardless would ask for su on a device that has none.
        boolean useShizuku = shizukuHandler.isShizukuAvailable();
        boolean useRoot = !useShizuku && isSuAvailable;
        if (!useShizuku && !useRoot) {
            synchronized (physicalEntryLock) {
                ownedReforcePhase = REFORCE_CLEANUP_PENDING;
            }
            log("No privileged backend available at dispatch, deferring the owned reforce undo");
            DiagnosticLogger.i("DOZE", "owned_reforce_cleanup_deferred"
                    + " reason=no_privileged_backend stage=dispatch");
            return;
        }

        if (useShizuku) {
            DiagnosticLogger.i("DOZE", "owned_reforce_cleanup_backend=shizuku");
            shizukuHandler.executeCommand(command,
                    (commandCode, exitCode, stdout, stderr) ->
                            listener.onCommandResult(commandCode, exitCode, stdout, stderr), false);
            return;
        }
        DiagnosticLogger.i("DOZE", "owned_reforce_cleanup_backend=root");
        rootShellExecutor.execute(() -> {
            if (rootSession != null) {
                rootSession.addCommand(command, 0, listener);
            } else {
                rootSession = new Shell.Builder()
                        .useSU()
                        .setWatchdogTimeout(5)
                        .setMinimalLogging(true)
                        .open((success, reason) -> {
                            if (reason != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                                log("Error opening root shell for owned reforce cleanup: " + reason);
                                // Reported rather than dropped: a lost callback would strand the
                                // debt with nothing left to retry it in this process.
                                onMarkerlessUnforceResult(-1);
                            } else {
                                rootSession.addCommand(command, 0, listener);
                            }
                        });
            }
        });
    }

    private void onMarkerlessUnforceResult(int exitCode) {
        boolean settled = false;
        synchronized (physicalEntryLock) {
            if (exitCode != 0) {
                // The device may still be forced. The marker stays and the debt stays, to be retried
                // at the next lifecycle or recovery opportunity.
                ownedReforcePhase = REFORCE_CLEANUP_PENDING;
                DiagnosticLogger.e("DOZE", "owned_reforce_cleanup_failed exit=" + exitCode);
            } else if (dozeStateStore.finishOwnedReforceAttempt()) {
                ownedReforcePhase = REFORCE_NONE;
                settled = true;
                DiagnosticLogger.i("DOZE", "owned_reforce_cleanup_complete exit=" + exitCode);
            } else {
                // The undo happened, so the device itself is safe, but the record of the debt could
                // not be cleared. It must not be reported as settled: a later recovery will
                // legitimately act on the marker it can still see, and unforcing an unforced device
                // is harmless.
                ownedReforcePhase = REFORCE_CLEANUP_PENDING;
                DiagnosticLogger.e("DOZE", "owned_reforce_journal_clear_failed exit=" + exitCode);
            }
        }
        if (settled) {
            // A corrective unforce has just removed physical deep idle; if the session is still
            // owned it is allowed to have it back, once.
            onOwnedReforceSettled(true);
        }
    }

    /**
     * The single continuation after a corrective unforce has succeeded and the durable marker has
     * been cleared.
     * <p>
     * reevaluateEntryAfterCleanup() alone is not enough here, because it deliberately declines while
     * a session is owned - which is exactly the case a conservative unforce creates. The session is
     * still ACTIVE and has just been taken out of deep idle, so it needs the ordinary recovery pass
     * to put it back. That pass writes no ENTER, no EXIT, no package generation and no new epoch:
     * it is the same continuation a Shizuku reconnect uses for a session it did not start.
     * <p>
     * Bounded, not a loop. It runs only when both the unforce and the journal clear succeeded, so a
     * persistently failing clear leaves CLEANUP_PENDING and reaches none of this. The recovery it
     * triggers re-forces once; that reforce settles against the same session and ends in
     * reevaluateEntryAfterCleanup(), which no-ops while the session is owned.
     */
    private void armOwnedReforceEntryIntent() {
        if (ownedReforceFreshEntryDeferred.compareAndSet(false, true)) {
            DiagnosticLogger.i("DOZE", "owned_reforce_entry_deferred reason=owned_reforce_unresolved");
        }
    }

    /**
     * The one continuation for every way an owned reforce can settle.
     *
     * @param afterCorrectiveUnforce true only when a corrective unforce has just taken physical deep
     *                               idle away from a session that is still ACTIVE. That is the sole
     *                               case in which a settlement may put it back. A normal reforce
     *                               that settles - including one Android refused - must not, or a
     *                               semantic rejection would re-force itself in a loop.
     */
    private void onOwnedReforceSettled(boolean afterCorrectiveUnforce) {
        if (serviceStopping) {
            return;
        }
        if (dozeStateStore.isInDoze()) {
            if (afterCorrectiveUnforce) {
                DiagnosticLogger.i("DOZE", "owned_reforce_settled_continuation=recover_active_session");
                applyRecoveryPolicy("OWNED_REFORCE_RECOVERY", "owned reforce debt settled");
            }
            // Otherwise nothing: the session is owned and keeps whatever physical state it has.
            return;
        }
        // No session. A fresh entry may be due, but never on top of restore debt an earlier session
        // still owes: entering would allocate a new package generation and lose whatever the
        // unfinished restore has not yet given back. The intent survives so the completion of that
        // debt gets the chance instead.
        if (dozeStateStore.hasAppliedSuspendedPackages() || dozeStateStore.hasPendingRestore()) {
            DiagnosticLogger.i("DOZE", "owned_reforce_settled_continuation=skipped"
                    + " reason=pending_restore");
            return;
        }
        if (ownedReforceFreshEntryDeferred.compareAndSet(true, false)) {
            DiagnosticLogger.i("DOZE", "owned_reforce_entry_retried reason=debt_settled");
        }
        reevaluateEntryAfterCleanup();
    }

    /**
     * Retry hook for an entry that was refused while owned-reforce debt outranked it and then had
     * to wait again for the restore debt to finish. Called from the two points that already know a
     * durable debt has just changed, so nothing is polled and no timer exists.
     * <p>
     * Ordinary policy is re-read at consumption through reevaluateEntryAfterCleanup(), so a wake, a
     * call, the charger or the custom period ending between arming and consuming cannot force an
     * entry that is no longer wanted.
     */
    private void maybeConsumeOwnedReforceEntryIntent(String reason) {
        if (!ownedReforceFreshEntryDeferred.get()) {
            return;
        }
        if (serviceStopping) {
            ownedReforceFreshEntryDeferred.set(false);
            return;
        }
        // Under the barrier: ownedReforcePhase is guarded by it, and this hook runs on package and
        // device-state completion threads. An unsynchronised read could see a stale CLEANUP_* and
        // return, stranding the intent with no further event able to consume it.
        boolean unresolved;
        synchronized (physicalEntryLock) {
            unresolved = isOwnedReforceUnresolved();
        }
        if (unresolved) {
            return;
        }
        if (dozeStateStore.hasAppliedSuspendedPackages() || dozeStateStore.hasPendingRestore()) {
            return;
        }
        if (!ownedReforceFreshEntryDeferred.compareAndSet(true, false)) {
            return;
        }
        DiagnosticLogger.i("DOZE", "owned_reforce_entry_retried reason=" + reason);
        reevaluateEntryAfterCleanup();
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
                applyDozeTunableConstants();
            }
        } else {
            executeCommand("dumpsys deviceidle force-idle");
        }
    }

    /**
     * The unprivileged tunable write, extracted verbatim so the owned-reforce path can dispatch
     * exactly this operation without going back through applyDoze() and risking a different branch.
     */
    private void applyDozeTunableConstants() {
        DozeTunableHandler handler = DozeTunableHandler.getInstance();
        log("Unrooted device, putting custom values in device_idle_constants...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ArrayList<String> commands = handler.getCommandsList();
            commands.forEach(this::executeCommand);
        } else {
            Settings.Global.putString(getContentResolver(), "device_idle_constants", handler.getTunableString());
        }
    }

    /**
     * How an owned-session reforce will be carried out, decided once under
     * {@link #physicalEntryLock} and never re-derived afterwards.
     * <p>
     * applyDoze() and the general command dispatcher both re-read live state - the availability
     * fields, and the configured execution mode - every time they run. For ordinary work that is
     * correct. For a transaction that has already been classified, journalled and gated it is not:
     * a preference or availability change between classification and dispatch could turn a
     * privileged force into a tunable write that never calls back, stranding the marker and the
     * FORCING phase for the life of the process, or turn a deliberately markerless tunable resume
     * into a real privileged force with no journal behind it - which is the original orphan.
     */
    private static final int REFORCE_PLAN_SHIZUKU = 0;
    private static final int REFORCE_PLAN_ROOT = 1;
    private static final int REFORCE_PLAN_TUNABLE = 2;
    private static final int REFORCE_PLAN_LEGACY = 3;
    private static final int REFORCE_PLAN_SHIZUKU_UNAVAILABLE = 4;

    private static boolean isPrivilegedReforcePlan(int plan) {
        return plan == REFORCE_PLAN_SHIZUKU || plan == REFORCE_PLAN_ROOT;
    }

    private static String reforcePlanName(int plan) {
        switch (plan) {
            case REFORCE_PLAN_SHIZUKU:
                return "shizuku";
            case REFORCE_PLAN_ROOT:
                return "root";
            case REFORCE_PLAN_LEGACY:
                return "legacy";
            case REFORCE_PLAN_SHIZUKU_UNAVAILABLE:
                return "shizuku_unavailable";
            default:
                return "tunable";
        }
    }

    /**
     * Classifies the reforce backend. Must be called while holding {@link #physicalEntryLock}, so
     * the plan and the durable marker that goes with it are decided together.
     * <p>
     * The choice mirrors applyDoze() and executeCommandWithRoot() exactly, so which backend runs a
     * reforce is unchanged: privileged when some privileged backend is present, and among those the
     * one the configured mode selects. Only the moment of the decision moves.
     */
    private int classifyReforcePlan(Context context) {
        if (!Utils.isDeviceRunningOnN()) {
            return REFORCE_PLAN_LEGACY;
        }
        // The configured mode decides first, and decides alone. Asking about availability before
        // asking about the mode is what let a selected Shizuku whose binder had just died fall
        // through to the tunable branch - the silent downgrade the deferred-entry work exists to
        // prevent. A selected Shizuku that is not there right now is its own answer, not an excuse
        // to use a different backend.
        if (Utils.isShizukuMode(context)) {
            return isShizukuAvailable ? REFORCE_PLAN_SHIZUKU : REFORCE_PLAN_SHIZUKU_UNAVAILABLE;
        }
        if (isSuAvailable || isShizukuAvailable) {
            return REFORCE_PLAN_ROOT;
        }
        return REFORCE_PLAN_TUNABLE;
    }

    /**
     * Dispatches exactly the plan that was captured, with a callback guaranteed on both privileged
     * backends - Shizuku reports -1 when its binder is unavailable, and the root shell reports -1
     * when it cannot be opened - so a FORCING phase can never be stranded by a backend that went
     * away between classification and dispatch.
     */
    private void dispatchOwnedReforce(int plan, Shell.OnCommandResultListener2 onResult) {
        switch (plan) {
            case REFORCE_PLAN_SHIZUKU:
            case REFORCE_PLAN_ROOT:
                dispatchOnCapturedPlan(plan, "dumpsys deviceidle force-idle deep",
                        "force_idle_deep", true, onResult);
                return;
            case REFORCE_PLAN_LEGACY:
                // Pre-N behaviour, preserved exactly as applyDoze() has always performed it. No
                // durable marker and no result handling, unchanged from before this commit.
                executeCommand("dumpsys deviceidle force-idle");
                return;
            default:
                applyDozeTunableConstants();
        }
    }

    /**
     * Runs one command on the privileged backend that was captured under the barrier, rather than
     * on whichever backend the configured mode names at dispatch time. Shared by the owned-session
     * reforce and by the locked-wake release so both get the same guarantee: a callback arrives
     * whether the command runs, the binder is gone, or the root shell cannot be opened.
     *
     * @param plan must be {@link #REFORCE_PLAN_SHIZUKU} or {@link #REFORCE_PLAN_ROOT}
     */
    private void dispatchOnCapturedPlan(int plan, String command, String tag, boolean printOutput,
                                        Shell.OnCommandResultListener2 onResult) {
        if (plan == REFORCE_PLAN_SHIZUKU) {
            shizukuHandler.executeCommand(command,
                    (commandCode, exitCode, stdout, stderr) -> {
                        DiagnosticLogger.i("DOZE", tag + " exit=" + exitCode);
                        if (printOutput) {
                            printShellOutput(stdout);
                            printShellOutput(stderr);
                        }
                        onResult.onCommandResult(commandCode, exitCode, stdout, stderr);
                    }, printOutput);
            return;
        }
        rootShellExecutor.execute(() -> {
            if (rootSession != null) {
                rootSession.addCommand(command, 0, (Shell.OnCommandResultListener2)
                        (commandCode, exitCode, STDOUT, STDERR) -> {
                            DiagnosticLogger.i("DOZE", tag + " exit=" + exitCode);
                            onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                        });
            } else {
                rootSession = new Shell.Builder()
                        .useSU()
                        .setWatchdogTimeout(5)
                        .setMinimalLogging(true)
                        .open((success, reason) -> {
                            if (reason != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                                log("Error opening root shell for " + tag + ": " + reason);
                                onResult.onCommandResult(0, -1, new ArrayList<>(), new ArrayList<>());
                            } else {
                                rootSession.addCommand(command, 0, (Shell.OnCommandResultListener2)
                                        (commandCode, exitCode, STDOUT, STDERR) -> {
                                            DiagnosticLogger.i("DOZE", tag + " exit=" + exitCode);
                                            onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                                        });
                            }
                        });
            }
        });
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

        // The configured mode is asked first, and answers on its own. isPrivilegedForceIdleBackend()
        // is an availability test - isSuAvailable || isShizukuAvailable - so consulting it first
        // would let a device with Shizuku selected and stopped, but a stale isSuAvailable=true, take
        // the privileged path anyway: writing PREPARING and dispatching a force command through a
        // backend the user did not choose. Selected Shizuku decides its own outcome, and never
        // borrows root.
        if (Utils.isDeviceRunningOnN() && Utils.isShizukuMode(context)) {
            if (!isShizukuAvailable) {
                // A deferral, never a downgrade. The tunable fallback is the behaviour of a device
                // that was never configured for a privileged backend; it is not a substitute for
                // the one the user chose, and claiming a session with it would produce restrictions
                // that cannot be applied and journal debt that cannot be paid. Nothing is claimed
                // and nothing is written.
                armDeferredShizukuEntryIfStillWanted(context, "fresh_entry");
                return;
            }
            beginPrivilegedFreshEntry(context, allowPostCleanupReentry);
            return;
        }

        // Root and non-Shizuku modes keep their original backend decision untouched.
        if (isPrivilegedForceIdleBackend()) {
            // The force is a real transaction whose success has to be established before anything
            // is suspended or journalled, so the whole entry moves behind its callback.
            beginPrivilegedFreshEntry(context, allowPostCleanupReentry);
            return;
        }

        // Reachable for a configured-Shizuku device only below API N, where there is no
        // force-idle deep to defer to; the rule that the fallback must not stand in for a chosen
        // privileged backend still applies.
        if (isShizukuModeButUnavailable(context)) {
            armDeferredShizukuEntryIfStillWanted(context, "fresh_entry_pre_n");
            return;
        }

        // Unprivileged tunable fallback. It writes device_idle constants rather than performing an
        // immediate force-idle transition, so there is no single result to verify and nothing that
        // could be left forced across a process death.
        lastKnownState = "IDLE";
        releaseTempWakeLock();
        // The whole ownership claim and initial setup run under the barrier, for the same reason
        // the privileged commit does. This path can be reached from a delayed TimerTask, so a wake
        // can arrive in the middle of it; without the barrier the wake could observe the freshly
        // set flag, run a full exit with all its restores, and leave this thread to carry on
        // blocking notifications, writing an ENTER row and applying restrictions afterwards.
        final long fallbackEpoch;
        synchronized (physicalEntryLock) {
            // Re-checked before ownership is claimed rather than trusted from the caller: the
            // policy may have changed during the entry delay. isFreshEntryStillWanted() carries the
            // conditional charging predicate and the !inDoze test.
            if (!isFreshEntryStillWanted(context) || dozeStateStore.isEntryPending()) {
                log("Policy changed before the fallback entry could claim the session, skipping");
                DiagnosticLogger.i("DOZE", "entry_aborted reason=policy_changed_before_commit"
                        + " mode=tunable_fallback");
                return;
            }
            // The fallback needs this gate more than the privileged path does, not less: its exit
            // resets device_idle constants and never unforces, so a session claimed here on top of
            // an unresolved force would never undo it.
            if (isOwnedReforceUnresolved()) {
                log("An owned reforce is unresolved, skipping the fallback entry");
                DiagnosticLogger.i("DOZE", "entry_refused reason=owned_reforce_unresolved"
                        + " mode=tunable_fallback");
                armOwnedReforceEntryIntent();
                return;
            }
            // Re-checked inside the barrier on the CONFIGURED MODE, not merely on availability.
            // The mode can be switched between the decision at the top of enterDoze() and this
            // point, and the dangerous direction is the one an unavailability test misses: a
            // fallback invocation chosen while no privileged backend existed, arriving here after
            // the user has selected Shizuku and Shizuku has become available. Ownership must never
            // be claimed by the fallback for a configuration that asked for a privileged backend,
            // whether or not that backend is currently present.
            if (Utils.isShizukuMode(context)) {
                if (!isShizukuAvailable) {
                    // Re-entrant on the monitor already held here, and it dispatches nothing, so
                    // the lock ordering is unchanged.
                    armDeferredShizukuEntryIfStillWanted(context, "fallback_commit");
                } else {
                    // This invocation is stale. No attempt is made to switch backends from inside
                    // the barrier; the next screen-off, or the settings reload that changed the
                    // mode, starts a correct entry.
                    log("Execution mode changed to Shizuku before the fallback could commit, aborting");
                    DiagnosticLogger.i("DOZE", "entry_aborted reason=execution_mode_changed"
                            + " mode=tunable_fallback");
                }
                return;
            }
            // One ordering change against the original: the durable flag is set before the entry
            // work rather than after it. The session-scoped guard requires an owned session, so
            // leaving setInDoze() below the package block would let a fast getFocusedApps callback
            // be rejected as stale and silently skip the whole blocklist. Setting it first is also
            // the safer of the two crash windows - an owned session with no packages is what
            // recovery is built for, whereas suspended packages with no session is the case that
            // leaves apps greyed out.
            dozeStateStore.setInDoze(true);
            // The fallback owns a logical session exactly as the privileged path does, so its async
            // entry work needs the same cross-session protection even though it has no PREPARING
            // state.
            fallbackEpoch = beginActiveSessionEpoch("fresh entry (tunable fallback)");
            applyEntryPackageAndNotificationBlocking(context, fallbackEpoch);
            timeEnterDoze = System.currentTimeMillis();
            if (Utils.isConnectedToCharger(getApplicationContext())) {
                lastDozeEnterBatteryLife = 0;
            } else {
                lastDozeEnterBatteryLife = Utils.getBatteryLevel(getApplicationContext());
            }
            log("Entering Doze");
            DiagnosticLogger.i("DOZE", "enter_doze_start mode=tunable_fallback");
            applyDoze();
            lastScreenOff = Utils.getDateCurrentTimeZone(System.currentTimeMillis());
            recordDozeEnterStats();
            applyEntryMotionAndNetwork(context, fallbackEpoch);
        }
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
     * True when Shizuku is the configured execution mode but its binder is not there right now.
     * <p>
     * Configured mode and current availability are different questions, and conflating them is what
     * produced the bug this guards: with Shizuku selected and stopped, every privileged predicate
     * read false, fresh entry fell through to the unprivileged tunable branch, and the service
     * started a whole logical session - epoch, package generation, ENTER row, notification blocking
     * - whose commands were then all dropped with "command_dropped reason=unavailable". A fake
     * session with no way to enforce anything, and durable markers owed by nobody.
     * <p>
     * Read live rather than cached: the user can switch execution mode while the service runs, and
     * reloadSettings() re-evaluates availability on the same basis.
     */
    private boolean isShizukuModeButUnavailable(Context context) {
        return Utils.isShizukuMode(context) && !isShizukuAvailable;
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
            // An unresolved owned reforce means the physical force-idle state is unaccounted for.
            // Committing a new session on top of it would leave that force owned by nobody, and the
            // corrective unforce would then be unable to tell it apart from the new session's own.
            if (isOwnedReforceUnresolved()) {
                log("An owned reforce is unresolved, skipping fresh entry");
                DiagnosticLogger.i("DOZE", "entry_refused reason=owned_reforce_unresolved phase="
                        + ownedReforcePhaseName(ownedReforcePhase));
                // This entry was otherwise valid - every guard above it passed - so it is
                // remembered rather than lost, and retried once the debt that outranked it clears.
                armOwnedReforceEntryIntent();
                return;
            }
            // A real attempt is taking over, so the intent "entry is due but the backend is
            // absent" has stopped describing anything and must not survive as a second retry
            // mechanism running alongside it. Consumed here, inside the critical section that
            // claims the attempt and ahead of the journal write, so it is already false whatever
            // the attempt goes on to do - commit, semantic rejection, transport failure, or the
            // journal failure handled immediately below. From this point the physical-entry
            // protocol owns every decision about whether anything is retried.
            if (shizukuFreshEntryDeferred.getAndSet(false)) {
                DiagnosticLogger.i("DOZE", "entry_deferral_consumed reason=fresh_attempt_started");
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
            // Armed before dispatch, because the confirming broadcast can arrive before the command
            // callback does. Both signals belong to this token and to no other attempt.
            pendingEntryConfirmToken = token;
            pendingEntryCommandAccepted = false;
            pendingEntryIdleObserved = false;
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
        int controllerResult = classifyControllerOutput(stdout, stderr);

        // Exit code 0 is necessary but not sufficient, and neither is one immediate idle sample.
        // The device demonstrated a completed force that reported "Now forced in to deep idle mode"
        // while isDeviceIdleMode() was still false, with the transition visible 47 ms later; calling
        // that a refusal aborted the attempt and orphaned a real force. What the controller itself
        // said is therefore consulted - but only to separate a refusal from a transition that has
        // not surfaced yet. Success is still never claimed from text.
        String verdict;
        if (commandCompleted && controllerResult == CONTROLLER_REFUSED) {
            // Tested before the idle sample, so the classifier's "refusal wins" rule holds at the
            // verdict level too. An explicit refusal proves THIS command established no force; a
            // deep idle observed at the same moment may belong to a natural transition or to
            // somebody else, and adopting it would claim a session on someone else's state.
            verdict = "semantic_rejection";
        } else if (commandCompleted && physicallyIdle) {
            verdict = "verified_success";
        } else if (commandCompleted && controllerResult == CONTROLLER_SUCCESS) {
            verdict = "accepted_pending_confirmation";
        } else if (commandCompleted) {
            verdict = "unknown_output";
        } else {
            // Deliberately one verdict for both idle samples. A non-zero result does not prove the
            // command never reached DeviceIdleController: ShizukuHandler reports -1 when execution
            // could not start AND when runCommandOnce threw partway through, so the force may
            // already have landed. Neither does an immediate idle=false sample, which this device
            // has now shown can precede a real transition by tens of milliseconds. With both
            // premises unproven the only honest description is that the physical outcome is
            // unknown.
            verdict = "transport_outcome_uncertain";
        }
        DiagnosticLogger.i("DOZE", "force_idle_result mode=fresh verdict=" + verdict
                + " exit=" + exitCode + " idleMode=" + physicallyIdle
                + " controllerResult=" + controllerResultName(controllerResult)
                + " verifiedBy=exit_code+idle_mode+controller_output");
        if ("semantic_rejection".equals(verdict)) {
            // The only case with output worth reporting. Never an input to the success decision.
            DiagnosticLogger.i("DOZE", "force_idle_rejected stoppedAt="
                    + describeForceIdleFailure(stdout, stderr));
        }

        boolean cleanup = false;
        boolean cleanupAllowsReentry = true;
        boolean abort = false;
        boolean abortJournalClearFailed = false;
        boolean commitFailed = false;
        boolean awaitConfirmation = false;
        String abortReason = verdict;
        synchronized (physicalEntryLock) {
            if (physicalEntryPhase == PHASE_CLEANING_UP) {
                // A recovery or shutdown cleanup already owns the debt and has its own unforce out;
                // issuing a second one here would race it for the durable bit.
                DiagnosticLogger.i("DOZE", "entry_result_ignored reason=cleanup_in_flight success="
                        + physicallyIdle);
            } else if (!commandCompleted) {
                // Physical outcome unknown, whatever the idle sample said. The force may already
                // have been applied, so the marker must survive: it is the only record that would
                // let anything undo a force that becomes visible later. Clearing it here on the
                // strength of an immediate idle=false read is precisely how a force was orphaned
                // with every durable flag clear. The uncertainty is neutralised by a real unforce
                // instead, and KEY_ENTRY_PENDING is cleared only by that cleanup's checked path.
                //
                // Controller success text is deliberately not consulted here either: with no
                // completed command it cannot establish a logical success, so cleanup remains the
                // conservative choice.
                cleanup = true;
                cleanupAllowsReentry = entryAttemptAllowsReentry;
                clearPendingFreshEntryConfirmationLocked();
                physicalEntryPhase = PHASE_CLEANING_UP;
            } else if ("semantic_rejection".equals(verdict)) {
                // The one result strong enough to clear PREPARING without neutralising anything:
                // the command completed AND the controller said in as many words that it refused.
                // Only then is it established that no force exists to undo.
                abortJournalClearFailed = !dozeStateStore.abortForceIdleAttempt();
                abort = true;
                clearPendingFreshEntryConfirmationLocked();
                physicalEntryPhase = PHASE_NONE;
            } else if ("accepted_pending_confirmation".equals(verdict)) {
                // The controller accepted the force; only its visibility is outstanding. PREPARING
                // is deliberately kept: the durable marker is the sole record that the device is
                // being forced, and clearing it here is exactly what orphaned the force on the
                // device. Nothing is committed yet either - no session, no epoch, no generation, no
                // ENTER - because a session must not exist until deep idle has actually been seen.
                if (token != entryAttemptToken || physicalEntryPhase != PHASE_ATTEMPTING
                        || !stillWanted) {
                    // Nobody wants it any more, and the controller says the device is forced, so
                    // the force is undone rather than left behind.
                    cleanup = true;
                    cleanupAllowsReentry = entryAttemptAllowsReentry;
                    clearPendingFreshEntryConfirmationLocked();
                    physicalEntryPhase = PHASE_CLEANING_UP;
                } else {
                    pendingEntryCommandAccepted = true;
                    if (pendingEntryIdleObserved) {
                        // The confirming broadcast already arrived while the command was finishing.
                        // Both halves are in, so this is the commit - and it follows the same
                        // pattern as the immediate verified-success path below, setting the session
                        // up before the barrier is released. Splitting the two would let SCREEN_ON
                        // see inDoze=true, run a complete owned exit, and leave this thread to
                        // establish an epoch, an ENTER row and restrictions for a session that had
                        // already finished.
                        if (dozeStateStore.commitDozeSession()) {
                            physicalEntryPhase = PHASE_NONE;
                            clearPendingFreshEntryConfirmationLocked();
                            DiagnosticLogger.i("DOZE", "entry_committed token=" + token
                                    + " confirmedBy=callback");
                            commitFreshDozeSession(context);
                            return;
                        }
                        commitFailed = true;
                        clearPendingFreshEntryConfirmationLocked();
                        physicalEntryPhase = PHASE_CLEANING_UP;
                    } else {
                        awaitConfirmation = true;
                    }
                }
            } else if ("unknown_output".equals(verdict)) {
                // The command completed, the device is not visibly idle, and the controller said
                // nothing this build recognises. That is not evidence of refusal and must never be
                // read as success, so the physical state is treated as uncertain and neutralised
                // before the marker is allowed to clear.
                cleanup = true;
                cleanupAllowsReentry = entryAttemptAllowsReentry;
                clearPendingFreshEntryConfirmationLocked();
                physicalEntryPhase = PHASE_CLEANING_UP;
            } else if (token != entryAttemptToken || physicalEntryPhase != PHASE_ATTEMPTING
                    || !stillWanted) {
                // Verified success that nobody wants any more. Never dropped: the device really is
                // forced, and only this branch will take it back out.
                cleanup = true;
                cleanupAllowsReentry = entryAttemptAllowsReentry;
                clearPendingFreshEntryConfirmationLocked();
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
                clearPendingFreshEntryConfirmationLocked();
                physicalEntryPhase = PHASE_CLEANING_UP;
            } else {
                physicalEntryPhase = PHASE_NONE;
                clearPendingFreshEntryConfirmationLocked();
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

        if (awaitConfirmation) {
            // PREPARING stays, and so does the durable marker. The confirming broadcast, a
            // lifecycle cancellation or recovery will resolve it; nothing is polled or timed.
            DiagnosticLogger.i("DOZE", "entry_awaiting_idle_confirmation token=" + token);
            return;
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
            String detail = !commandCompleted ? "transport_outcome_uncertain"
                    : ("unknown_output".equals(verdict) ? "unrecognised_controller_output"
                    : (stillWanted ? "attempt_superseded" : "precondition_changed"));
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
        long epoch = beginActiveSessionEpoch("fresh entry (privileged)");
        applyEntryPackageAndNotificationBlocking(context, epoch);
        applyEntryMotionAndNetwork(context, epoch);
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
     * Classifies what the controller reported, conservatively.
     * <p>
     * Only the two forms observed on the device are recognised. Anything else is UNKNOWN and is
     * never read as success: an OEM or version that words its output differently must fall into the
     * conservative path, not into a claimed session. The raw output is not persisted; only this
     * bounded classification is logged.
     */
    private int classifyControllerOutput(List<String> stdout, List<String> stderr) {
        List<String> lines = new ArrayList<>();
        if (stdout != null) {
            lines.addAll(stdout);
        }
        if (stderr != null) {
            lines.addAll(stderr);
        }
        boolean refused = false;
        boolean success = false;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String normalised = line.toLowerCase(Locale.US);
            if (normalised.contains("unable to go deep idle") || normalised.contains("stopped at ")) {
                refused = true;
            } else if (normalised.contains("now forced in to deep idle mode")
                    || normalised.contains("now forced into deep idle mode")) {
                success = true;
            }
        }
        // Refusal wins if both somehow appear: never upgrade an ambiguous result to success.
        if (refused) {
            return CONTROLLER_REFUSED;
        }
        return success ? CONTROLLER_SUCCESS : CONTROLLER_UNKNOWN;
    }

    private static String controllerResultName(int result) {
        switch (result) {
            case CONTROLLER_SUCCESS:
                return "success";
            case CONTROLLER_REFUSED:
                return "refused";
            default:
                return "unknown";
        }
    }

    /** Caller must hold {@link #physicalEntryLock}. */
    private void clearPendingFreshEntryConfirmationLocked() {
        pendingEntryConfirmToken = 0;
        pendingEntryCommandAccepted = false;
        pendingEntryIdleObserved = false;
    }

    /**
     * The second of the two signals: deep idle has actually been observed.
     * <p>
     * On its own this proves only that the device is idle, not that this app's command caused it -
     * the transition could be anyone's. So it is recorded against the exact attempt and the commit
     * still waits for the command to report acceptance. A stale token never commits, and an
     * observation arriving after cancellation cannot resurrect an abandoned attempt.
     *
     * @return true when the observation belonged to a current fresh attempt
     */
    private boolean onDeepIdleObservedForFreshEntry() {
        boolean commitFailed = false;
        boolean cleanupUnwanted = false;
        int token;
        synchronized (physicalEntryLock) {
            if (!isPendingFreshEntryConfirmationLocked()) {
                return false;
            }
            token = pendingEntryConfirmToken;
            pendingEntryIdleObserved = true;
            if (!pendingEntryCommandAccepted) {
                // Broadcast first. Hold the observation; the command result decides.
                DiagnosticLogger.i("DOZE", "entry_idle_observed token=" + token
                        + " awaiting=command_result");
                return true;
            }
            if (!isFreshEntryStillWanted(getApplicationContext())) {
                // Both halves are in but the session is no longer wanted, and the device really is
                // forced, so it must be taken back out rather than committed or abandoned.
                clearPendingFreshEntryConfirmationLocked();
                physicalEntryPhase = PHASE_CLEANING_UP;
                cleanupUnwanted = true;
            } else if (dozeStateStore.commitDozeSession()) {
                physicalEntryPhase = PHASE_NONE;
                clearPendingFreshEntryConfirmationLocked();
                DiagnosticLogger.i("DOZE", "entry_committed token=" + token
                        + " confirmedBy=idle_broadcast");
                // Inside the barrier, exactly as the callback routes do: the durable
                // PREPARING -> ACTIVE transition and the session setup it implies must not be
                // separable by a lifecycle event.
                commitFreshDozeSession(getApplicationContext());
                return true;
            } else {
                clearPendingFreshEntryConfirmationLocked();
                physicalEntryPhase = PHASE_CLEANING_UP;
                commitFailed = true;
            }
        }

        if (cleanupUnwanted) {
            DiagnosticLogger.i("DOZE", "entry_cleanup_started reason=possible_owned_force"
                    + " detail=precondition_changed token=" + token);
            dispatchPhysicalForceCleanup(false);
        } else if (commitFailed) {
            DiagnosticLogger.e("DOZE", "entry_commit_failed action=physical_cleanup token=" + token);
            dispatchPhysicalForceCleanup(false);
        }
        return true;
    }

    /** Caller must hold {@link #physicalEntryLock}. */
    private boolean isPendingFreshEntryConfirmationLocked() {
        return physicalEntryPhase == PHASE_ATTEMPTING
                && pendingEntryConfirmToken != 0
                && pendingEntryConfirmToken == entryAttemptToken;
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
        boolean abandonConfirmation = false;
        synchronized (physicalEntryLock) {
            if (physicalEntryPhase == PHASE_ATTEMPTING && pendingEntryCommandAccepted) {
                // The command already reported acceptance, so the callback that would normally
                // settle this attempt has already run and nothing else is coming for it. The
                // controller said the device is forced, so the force is undone rather than left to
                // an observation that may never arrive. Done before the token moves, so the state
                // being abandoned is unambiguous.
                clearPendingFreshEntryConfirmationLocked();
                physicalEntryPhase = PHASE_CLEANING_UP;
                abandonConfirmation = true;
                DiagnosticLogger.i("DOZE", "entry_confirmation_abandoned reason=" + reason);
            }
            entryAttemptToken++;
            // A deferred fresh entry is pending-entry state like any other: the screen coming on, a
            // call taking over, the charger policy or the custom period ending, a session ending and
            // teardown all reach here, and all of them make the intent obsolete.
            if (shizukuFreshEntryDeferred.getAndSet(false)) {
                DiagnosticLogger.i("DOZE", "entry_deferral_cancelled reason=" + reason);
            }
            if (ownedReforceFreshEntryDeferred.getAndSet(false)) {
                DiagnosticLogger.i("DOZE", "owned_reforce_entry_deferral_cancelled reason=" + reason);
            }
            if (physicalEntryPhase == PHASE_ATTEMPTING) {
                DiagnosticLogger.i("DOZE", "entry_invalidated reason=" + reason);
            }
        }
        if (abandonConfirmation) {
            // Dispatch only; nothing is waited on, and the monitor above has been released.
            dispatchPhysicalForceCleanup(false);
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
    private void applyEntryPackageAndNotificationBlocking(Context context, long sessionEpoch) {
            if (dozeAppBlocklist.size() != 0) {
                log("Disabling apps that are in the Doze app blocklist");
                if (whitelistCurrentApp) {
                    // when root is not available we use UsageStatsManager
                    // but i am not sure i can trust it as it does not really returns the front
                    // app but last one used (what about apps running in the background?)
                    if (isSuAvailable || isShizukuAvailable) {
                        try {
                            getFocusedApps((HashSet<String> packageNames) -> {
                                // This callback allocates a durable package generation and suspends
                                // apps, so it is the most damaging of the three to run late: a
                                // result arriving after the wake would re-suspend the blocklist
                                // with nothing left to restore it, and one arriving during a later
                                // session would overwrite that session's generation. Classification
                                // and the journal write share the barrier, so a wake cannot slip
                                // between them.
                                synchronized (physicalEntryLock) {
                                    int state = classifySessionEntryWork(sessionEpoch);
                                    if (state == WORK_STALE) {
                                        logStaleAsyncWork("focused_apps");
                                        return;
                                    }
                                    List<String> toBlock = new ArrayList<>();
                                    for (String pkg : dozeAppBlocklist) {
                                        if (!packageNames.contains(pkg)) {
                                            toBlock.add(pkg);
                                        }
                                    }
                                    if (state == WORK_LOCKED_WAKE) {
                                        // The session is alive but its lock screen is up, so the
                                        // apps must stay usable. Only the record is written: the
                                        // generation and its exact set become this session's
                                        // property, and the SCREEN_OFF enforcement suspends that
                                        // stored generation without allocating a second one.
                                        // Dropping the result instead would leave the session with
                                        // no package ownership at all and no blocklist for the rest
                                        // of the night.
                                        logDeferredAsyncWork("focused_apps");
                                        suspendPackagesForDoze(toBlock, false);
                                        return;
                                    }
                                    suspendPackagesForDoze(toBlock, true);
                                }
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
    private void applyEntryMotionAndNetwork(Context context, long sessionEpoch) {
            if (disableMotionSensors) {
                dozeStateStore.markApplied(DozeStateStore.KEY_MOTION_SENSORS, true);
                disableSensorsTimer = new Timer();
                disableSensorsTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        // Two seconds is long enough for the whole session to end. The restore runs
                        // "sensorservice enable" on the way out, and this task would then restrict
                        // the sensors again behind it, with the journal already clear and nobody
                        // left to undo it.
                        synchronized (physicalEntryLock) {
                            int state = classifySessionEntryWork(sessionEpoch);
                            if (state == WORK_STALE) {
                                logStaleAsyncWork("motion");
                                return;
                            }
                            if (state == WORK_LOCKED_WAKE) {
                                // Same session, lock screen up. Dropping it would lose the motion
                                // restriction for the rest of the session, because no further timer
                                // is armed for a continuation; it is remembered against this epoch
                                // and applied when the screen goes off again.
                                deferredMotionEpoch.set(sessionEpoch);
                                logDeferredAsyncWork("motion");
                                return;
                            }
                            applyMotionSensorRestriction(MOTION_LABEL_ENTER);
                        }
                    }
                }, 2000);
            } else {
                log("Not disabling motion sensors because disableMotionSensors=false");
            }
        enterDozeHandleNetwork(context, sessionEpoch);
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
        // Ownership is dropped under the barrier so that a session-scoped callback either completes
        // its journal and dispatch entirely before this point - in which case the restores below
        // find it and undo it - or observes a finished session and refuses. There is no ordering in
        // which entry work lands after the restore.
        synchronized (physicalEntryLock) {
            dozeStateStore.setInDoze(false);
            endActiveSessionEpoch("exit doze");
        }
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
        if (transaction == 0 && Build.VERSION.SDK_INT == 36) {
            transaction = 17;
            Log.w(TAG, "Using Android 16 notification Binder transaction fallback");
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
        requestNotificationState(TextUtils.join("; ", commands), enabled, commands.size());
    }

    /**
     * Queues a notification toggle. Returns immediately; the command itself is dispatched by
     * {@link #dispatchPendingNotificationOp()}, either now or after whatever is already running has
     * genuinely finished.
     *
     * @param command the fully built shell invocation for this exact package set and target state
     * @param enabled the target, for diagnostics only
     * @param count   how many packages the command covers, for diagnostics only
     */
    private void requestNotificationState(String command, boolean enabled, int count) {
        boolean dispatchNow;
        synchronized (notificationOpLock) {
            // Replacing a queued request is safe: it never ran, so nothing physical is undone by
            // dropping it. An in-flight command is untouched - it keeps its own callback and is
            // never declared finished early.
            pendingNotificationCommand = command;
            pendingNotificationEnabled = enabled;
            pendingNotificationCount = count;
            dispatchNow = !notificationOpInFlight;
            if (dispatchNow) {
                notificationOpInFlight = true;
            }
        }
        if (dispatchNow) {
            dispatchPendingNotificationOp();
        }
    }

    private void dispatchPendingNotificationOp() {
        final String command;
        final boolean enabled;
        final int count;
        synchronized (notificationOpLock) {
            if (pendingNotificationCommand == null) {
                notificationOpInFlight = false;
                return;
            }
            command = pendingNotificationCommand;
            enabled = pendingNotificationEnabled;
            count = pendingNotificationCount;
            pendingNotificationCommand = null;
            notificationOpInFlight = true;
        }

        // The lock is released before the command is issued; only the slot bookkeeping is guarded.
        DiagnosticLogger.i("NOTIF", "toggle_start enabled=" + enabled + " count=" + count);
        executeCommandWithRoot(command, (commandCode, exitCode, stdout, stderr) -> {
            DiagnosticLogger.i("NOTIF", "toggle_finished enabled=" + enabled + " count=" + count
                    + " exit=" + exitCode);
            // Only the real callback releases the next request, so ordering follows completion
            // rather than dispatch. Works identically on the root backend.
            dispatchPendingNotificationOp();
        }, false);
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
        suspendPackagesForDoze(packageNames, true);
    }

    /**
     * @param dispatchSuspend false to claim the generation without physically suspending anything.
     *                        Used when the session is alive but its lock screen is up: the record
     *                        must exist so the next screen-off suspends this exact set in this exact
     *                        generation, while the apps stay usable in the meantime. An unlock
     *                        before that screen-off reaches the ordinary final un-suspend, which
     *                        finds an already-unsuspended set and clears the generation.
     */
    private void suspendPackagesForDoze(Collection<String> packageNames, boolean dispatchSuspend) {
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
        if (!dispatchSuspend) {
            DiagnosticLogger.i("HARD_BLOCK", "session_journalled_only gen=" + session.generation);
            return;
        }
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
        // The other half of the debt: a final un-suspend that clears the generation can be the last
        // thing standing between a deferred entry and its retry.
        maybeRetryDeferredShizukuEntry("package_debt_cleared");
        maybeConsumeOwnedReforceEntryIntent("package_debt_cleared");
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

    /**
     * Entry point for work that belongs to whichever session is owned right now - currently the
     * return from a maintenance window. It adopts the current identity rather than starting one:
     * maintenance is a continuation, so no new ENTER row, no package generation and no new epoch.
     */
    public void enterDozeHandleNetwork(Context context) {
        enterDozeHandleNetwork(context, ensureActiveSessionEpoch("network entry"));
    }

    private void enterDozeHandleNetwork(Context context, long sessionEpoch) {
        if (whitelistMusicAppNetwork) {
            try {
                NotificationService notifService = NotificationService.Companion.getInstance();
                if (notifService != null) {
                    notifService.getPlayingPackageName((String packageName) -> {
                        // The only asynchronous route into the radio work, and the one with the
                        // widest window: a full ACTION_SCREEN_ON leaves inDoze set until
                        // handleScreenOn clears it, so an epoch check alone would still pass during
                        // wake restoration and capture "pre-Doze" values from an already restored
                        // device. The wake predicate inside the classification is what excludes it.
                        synchronized (physicalEntryLock) {
                            if (classifySessionEntryWork(sessionEpoch) == WORK_STALE) {
                                logStaleAsyncWork("network");
                                return null;
                            }
                            actualEnterDozeHandleNetwork(context, packageName);
                        }
                        return null;
                    });
                    return;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // Synchronous route. Usually reached inside the transition that established the session,
        // where the barrier is already held and the check is a formality - but not always: the
        // tunable fallback and the maintenance re-entry both arrive here at other moments, so it is
        // validated on the same terms rather than assumed safe.
        synchronized (physicalEntryLock) {
            if (classifySessionEntryWork(sessionEpoch) == WORK_STALE) {
                logStaleAsyncWork("network");
                return;
            }
            actualEnterDozeHandleNetwork(context, null);
        }
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
                // Cross-session guard for the keys whose commands a later session can outlive: an
                // old session's final restore can land after a new SCREEN_OFF has already begun a
                // new session and re-marked them. Clearing then would drop a debt the new session
                // genuinely owes. Motion joins the list because its ENABLE is now serialized and can
                // therefore be held behind an in-flight command for longer than the gap between two
                // sessions.
                if ((DozeStateStore.KEY_ALL_SENSORS.equals(key)
                        || DozeStateStore.KEY_BIOMETRICS.equals(key)
                        || DozeStateStore.KEY_MOTION_SENSORS.equals(key))
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
        // After the marker and the in-flight record have both settled, so the debt test below sees
        // the state this restore just produced. Recovery outranks a deferred entry, and recovery is
        // asynchronous: without a hook at the point the last debt actually clears, an entry deferred
        // while Shizuku was away could sit armed for ever, with no further screen-off or
        // availability callback coming to release it.
        maybeRetryDeferredShizukuEntry("restore_debt_cleared");
        maybeConsumeOwnedReforceEntryIntent("restore_debt_cleared");
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
        synchronized (physicalEntryLock) {
            dozeStateStore.setInDoze(false);
            endActiveSessionEpoch("recovered session finalized");
        }
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
    private void ensureOwnedDozePhysicalState(String reason, long expectedEpoch) {
        // Cheap pre-filter only. Every one of these is re-read under the barrier below, because a
        // continuation that passed them can be arbitrarily delayed - Mode A arrives on the Shizuku
        // listener thread - and the session it was servicing can end, and a different one begin,
        // before it acquires the lock.
        if (!dozeStateStore.isInDoze() || Utils.isScreenOn(getApplicationContext())) {
            return;
        }

        final int plan;
        synchronized (physicalEntryLock) {
            // Identity first, and it is the epoch the CALLER was servicing - not whatever happens
            // to be active now. Re-reading the current epoch here would prove only that some
            // session exists, so a continuation delayed across the end of session A and the start
            // of session B would find B and quietly adopt it, re-forcing on behalf of a session
            // that never asked.
            if (expectedEpoch == EPOCH_NONE
                    || activeSessionEpoch.get() != expectedEpoch
                    || !isActiveSessionEpoch(expectedEpoch)) {
                DiagnosticLogger.i("DOZE", "owned_session_reforce_skipped reason=stale_session"
                        + " expectedEpoch=" + expectedEpoch);
                return;
            }
            if (serviceStopping) {
                return;
            }
            if (Utils.isScreenOn(getApplicationContext()) || isCallActiveNow()) {
                return;
            }
            if (disableWhenCharging && Utils.isConnectedToCharger(getApplicationContext())) {
                return;
            }
            if (!Utils.isInsideCustomDozePeriod(getApplicationContext())) {
                return;
            }
            if (maintenance) {
                // A genuine maintenance window is open and its restores are asynchronous. Forcing
                // deep idle now could make the re-entry handler recapture pre-Doze values from a
                // partially restored state, so let Android finish the window on its own.
                log("Owned session is inside a maintenance window, not forcing deep idle (" + reason + ")");
                DiagnosticLogger.i("DOZE", "owned_session_waiting_for_maintenance_completion reason=" + reason);
                return;
            }
            // A fresh entry protocol must never be running underneath an owned session; if one is,
            // this continuation belongs to a state that has already moved on.
            if (dozeStateStore.isEntryPending() || physicalEntryPhase != PHASE_NONE) {
                DiagnosticLogger.i("DOZE", "owned_session_reforce_skipped reason=fresh_entry_active");
                return;
            }
            // Single-flight. Two continuation sources reach this method - the lock-screen resume on
            // the main thread and recovery Mode A on the Shizuku listener thread - and two
            // concurrent forces defeat any callback-side reasoning: one can land after the other's
            // corrective unforce and leave the device forced with no owner.
            if (isOwnedReforceUnresolved()) {
                log("An owned reforce is already unresolved, not issuing another (" + reason + ")");
                DiagnosticLogger.i("DOZE", "owned_session_reforce_skipped reason=unresolved phase="
                        + ownedReforcePhaseName(ownedReforcePhase)
                        + " outstandingEpoch=" + ownedReforceEpoch);
                return;
            }
            if (lockedWakeReleasePhase == RELEASE_IN_FLIGHT) {
                // Deliberately before the idle sample, not after it. A release is outstanding, so
                // pm.isDeviceIdleMode() may still report the state the release is in the middle of
                // undoing; acting on it would either skip a reforce that is genuinely needed or
                // dispatch a force that could complete before the older unforce. The release
                // callback re-evaluates this exact epoch once it settles.
                log("A locked-wake release is still in flight, deferring the reforce (" + reason + ")");
                DiagnosticLogger.i("DOZE", "owned_session_reforce_skipped reason=release_in_flight"
                        + " releaseEpoch=" + lockedWakeReleaseEpoch);
                return;
            }
            // Cheap in-process read; no dumpsys and nothing to wait on. Trustworthy here because
            // the two checks above guarantee no physical command of ours is outstanding.
            if (pm.isDeviceIdleMode()) {
                return;
            }
            // One decision point, not two. A separate availability guard here followed by a
            // classification below could disagree with itself, because the availability field is
            // written by the Shizuku listener without this lock.
            plan = classifyReforcePlan(getApplicationContext());
            if (plan == REFORCE_PLAN_SHIZUKU_UNAVAILABLE) {
                // Selected Shizuku is simply absent. Nothing is dispatched, no marker is written,
                // and the logical session stays exactly as it is - owned, with its markers still
                // journalled. The existing Shizuku-availability recovery path retries it.
                log("Shizuku mode is selected but Shizuku is not available, not re-forcing deep idle");
                DiagnosticLogger.i("DOZE", "owned_session_reforce_deferred reason=shizuku_unavailable");
                return;
            }
            if (!isPrivilegedReforcePlan(plan)) {
                // No physical force-idle transaction exists on these plans - the tunable path
                // writes device_idle constants and reports no result - so there is nothing to
                // journal and no callback that could ever release a gate. They must not arm the
                // durable machine, and they are dispatched inside the barrier with the plan already
                // fixed, so neither the validation nor the branch can be reinterpreted afterwards.
                DiagnosticLogger.i("DOZE", "owned_session_reforce_idle reason=" + reason
                        + " plan=" + reforcePlanName(plan));
                dispatchOwnedReforce(plan, null);
                return;
            }

            {
                // Durable first, and only on success. A force that succeeds leaves mForceIdle=true
                // behind, so dispatching one with no record on disk is exactly the orphan this
                // marker exists to prevent. A journal failure abandons the reforce; the session
                // stays owned and simply is not re-forced this time.
                if (!dozeStateStore.beginOwnedReforceAttempt()) {
                    log("Could not record the owned reforce, not re-forcing deep idle");
                    DiagnosticLogger.e("DOZE", "owned_session_reforce_aborted"
                            + " reason=journal_write_failed");
                    return;
                }
                ownedReforceEpoch = expectedEpoch;
                ownedReforcePhase = REFORCE_FORCING;
            }
        }

        DiagnosticLogger.i("DOZE", "owned_session_reforce_idle reason=" + reason
                + " plan=" + reforcePlanName(plan));
        // A resume claims no PREPARING marker, writes no ENTER row and allocates no generation: the
        // logical session is already owned and committed. What it does own is the physical
        // transaction, until its result is known.
        DiagnosticLogger.i("DOZE", "force_idle_attempt_start mode=resume");
        dispatchOwnedReforce(plan, (commandCode, exitCode, stdout, stderr) ->
                onOwnedReforceResult(expectedEpoch, exitCode, stdout, stderr));
    }

    private static String ownedReforcePhaseName(int phase) {
        switch (phase) {
            case REFORCE_FORCING:
                return "FORCING";
            case REFORCE_CLEANUP_PENDING:
                return "CLEANUP_PENDING";
            case REFORCE_CLEANUP_IN_FLIGHT:
                return "CLEANUP_IN_FLIGHT";
            default:
                return "NONE";
        }
    }

    /**
     * Classifies a finished owned reforce. Physical state is read before the barrier is taken, and
     * the decision is made under it, so a session ending cannot slip between the two.
     */
    private void onOwnedReforceResult(long capturedEpoch, int exitCode,
                                      List<String> stdout, List<String> stderr) {
        boolean physicallyIdle = verifyPhysicalDozeEntered();

        boolean resolveDebt = false;
        boolean settled = false;
        synchronized (physicalEntryLock) {
            // The existing lifecycle classifier, not a bare epoch test. A session can stay owned
            // while its lock screen is showing under waitForUnlock, and in that state physical deep
            // idle is deliberately not wanted - the wake temporarily gave it back. Treating that as
            // "same session, keep the force" would re-restrict the device the user is looking at.
            int state = classifySessionEntryWork(capturedEpoch);
            ownedReforceEpoch = EPOCH_NONE;

            DiagnosticLogger.i("DOZE", "force_idle_result mode=resume success=" + physicallyIdle
                    + " exit=" + exitCode + " verifiedBy=idle_mode lifecycle="
                    + sessionWorkStateName(state));

            // A force is only wanted by a session that is currently eligible for it. Locked-wake and
            // ended sessions both owe an undo if one actually landed.
            if (physicallyIdle && state != WORK_ELIGIBLE) {
                ownedReforcePhase = REFORCE_CLEANUP_PENDING;
                resolveDebt = true;
                DiagnosticLogger.i("DOZE", "owned_reforce_stale_force detected=true lifecycle="
                        + sessionWorkStateName(state));
            } else {
                // Either the force is wanted where it landed, or nothing was left forced. Both
                // settle by clearing the durable marker; neither is a session boundary, so no
                // ENTER, no EXIT and no generation.
                if (state == WORK_ELIGIBLE && !physicallyIdle) {
                    DiagnosticLogger.i("DOZE", "owned_session_reforce_failed stoppedAt="
                            + describeForceIdleFailure(stdout, stderr));
                }
                if (dozeStateStore.finishOwnedReforceAttempt()) {
                    ownedReforcePhase = REFORCE_NONE;
                    settled = true;
                } else {
                    // The marker could not be cleared, so as far as anything durable is concerned
                    // the transaction is still unresolved. It is not forgotten: it drops to the
                    // no-command-in-flight state and is resolved conservatively like any other
                    // unresolved debt.
                    ownedReforcePhase = REFORCE_NONE;
                    resolveDebt = true;
                    DiagnosticLogger.e("DOZE", "owned_reforce_journal_clear_failed stage=result");
                }
            }
        }

        if (resolveDebt) {
            maybeResolveOwnedReforceDebt("owned reforce result");
        } else if (settled) {
            // A normal settle: nothing was unforced, so an owned session keeps what it has and a
            // rejection is not retried. With no session this is where an entry refused during the
            // reforce gets its chance, subject to any restore debt still outstanding.
            onOwnedReforceSettled(false);
        }
    }

    /**
     * The user woke the lock screen and turned it off again. That is a continuation of the session
     * already owned, not a new one, so none of the fresh-entry work runs: no new package
     * generation, no ENTER statistics row, no pre-Doze recapture, no second motion-sensor timer and
     * no configured entry delay. The restrictions themselves are re-applied by the enforce* calls
     * at the SCREEN_OFF site before this runs.
     */
    private void resumeOwnedDozeAfterLockedWake(String reason, long expectedEpoch) {
        // The identity is never chosen here - it is given by the caller and carried unchanged.
        //
        // Validating and then acting are one barrier operation, not a check followed by unguarded
        // work. cancelPendingEnterDoze() is the reason: it carries no epoch, and it cancels the
        // armed entry timer and bumps the fresh-entry token. Released early, an A continuation
        // could pass its gate, lose A, and then cancel a legitimate fresh entry that B had armed in
        // the meantime - the motion and reforce steps would correctly reject A afterwards, but B
        // would already have been disturbed.
        //
        // Nothing in here blocks: invalidateDesiredEntry only touches fields and posts diagnostics,
        // Timer.cancel() discards a queue without joining its thread, and releasing a wakelock is a
        // short binder call. cancelPendingEnterDoze() re-enters this same monitor, which Java
        // permits.
        synchronized (physicalEntryLock) {
            if (expectedEpoch == EPOCH_NONE
                    || activeSessionEpoch.get() != expectedEpoch
                    || !isActiveSessionEpoch(expectedEpoch)
                    || Utils.isScreenOn(getApplicationContext())) {
                DiagnosticLogger.i("DOZE", "owned_session_resume_skipped reason=stale_session"
                        + " expectedEpoch=" + expectedEpoch);
                return;
            }
            log("Resuming the owned Doze session (" + reason + ")");
            DiagnosticLogger.i("DOZE", "owned_session_resumed reason=" + reason);
            cancelPendingEnterDoze();
            releaseTempWakeLock();
            lastKnownState = "IDLE";
        }
        // A motion restriction postponed by the lock-screen wake belongs to this same session and
        // is applied now, once.
        applyDeferredMotionRestrictionIfDue(reason, expectedEpoch);
        // maintenance is deliberately left as it is: while a genuine window is open the flag is the
        // only record that its restored states must be re-applied when deep idle resumes.
        //
        // The caller's identity is passed straight through, so the reforce validates against the
        // session that started this continuation.
        ensureOwnedDozePhysicalState(reason, expectedEpoch);
    }

    /**
     * Queues a target state for the motion-sensor toggle. Same contract as the other physical
     * serializers: a request still queued when a newer one replaces it is completed exactly once
     * with {@link #SUPERSEDED_EXIT_CODE}, which is non-zero and therefore leaves any durable marker
     * it was carrying uncleared - the newer target owns the outcome.
     *
     * @param restrict true to restrict, false to enable
     */
    private void requestMotionSensorState(boolean restrict, Shell.OnCommandResultListener2 done,
                                          String label) {
        Shell.OnCommandResultListener2 superseded;
        boolean dispatchNow;
        synchronized (motionOpLock) {
            superseded = pendingMotionCallback;
            pendingMotionRestrict = restrict;
            pendingMotionCallback = done;
            pendingMotionLabel = label;
            dispatchNow = !motionOpInFlight;
            if (dispatchNow) {
                motionOpInFlight = true;
            }
        }
        if (superseded != null) {
            superseded.onCommandResult(0, SUPERSEDED_EXIT_CODE, new ArrayList<>(), new ArrayList<>());
        }
        if (dispatchNow) {
            dispatchPendingMotionOp();
        }
    }

    private void dispatchPendingMotionOp() {
        final boolean restrict;
        final Shell.OnCommandResultListener2 done;
        final String label;
        synchronized (motionOpLock) {
            if (pendingMotionRestrict == null) {
                motionOpInFlight = false;
                return;
            }
            restrict = pendingMotionRestrict;
            done = pendingMotionCallback;
            label = pendingMotionLabel;
            pendingMotionRestrict = null;
            pendingMotionCallback = null;
            pendingMotionLabel = null;
            motionOpInFlight = true;
        }

        final String command;
        if (!restrict) {
            command = "dumpsys sensorservice enable";
        } else if (sensorWhitelistPackage.equals("")) {
            command = "dumpsys sensorservice restrict";
        } else {
            log("Package " + sensorWhitelistPackage + " is whitelisted from sensorservice");
            log("Note: Packages that get whitelisted are supposed to request sensor access again, if the app doesn't work, email the dev of that app!");
            command = "dumpsys sensorservice restrict " + sensorWhitelistPackage;
        }
        log(restrict ? "Disabling motion sensors" : "Enabling motion sensors");

        executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
            DiagnosticLogger.i("MOTION", (restrict ? "restrict" : "enable")
                    + " label=" + label + " exit=" + exitCode);
            if (done != null) {
                done.onCommandResult(commandCode, exitCode, stdout, stderr);
            }
            dispatchPendingMotionOp();
        }, false);
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

        // Ahead of PREPARING and of every ownership mode. An unresolved owned reforce is the one
        // state in which the device may be physically forced with no accounting at all, and every
        // path below either re-forces or claims a session. Both gate on the marker as well, so the
        // asynchronous cleanup dispatched here cannot be overtaken while its callback is pending.
        if (dozeStateStore.isOwnedReforcePending()) {
            log(logPrefix + "_OWNED_REFORCE: an owned reforce is unresolved");
            DiagnosticLogger.i("RECOVERY", "RECOVERY_OWNED_REFORCE prefix=" + logPrefix);
            maybeResolveOwnedReforceDebt(reason + " (recovery)");
            return;
        }

        // Highest priority among the entry states. A PREPARING marker means a privileged
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

        // From here on the session is being continued rather than ended, and this process may not
        // be the one that started it. Adopting an identity now gives any async work it creates -
        // the maintenance network re-entry in particular - something valid to capture.
        long recoveredEpoch = ensureActiveSessionEpoch(reason);

        if (screenOn) {
            log(logPrefix + "_PARTIAL_LOCKSCREEN: session still active behind the keyguard");
            DiagnosticLogger.i("RECOVERY", logPrefix + "_PARTIAL_LOCKSCREEN screenOn=true inDoze=true");
            // Temporary, so the session keeps ownership of its exact package set.
            enforcePackageStateForLifecycle(reason);
            enforceBiometricStateForLifecycle(reason);
            enforceSensorStateForLifecycle(reason);
            // A process recreated while the session was already sitting behind a visible lock
            // screen has no ACTION_SCREEN_ON to come, so without this the device would stay
            // physically forced with nothing left to release it. Scoped to the epoch adopted above
            // rather than to whatever is active by now, and a no-op when the shared marker is
            // already pending - that case is owned by the marker-first branch at the top of this
            // method, which runs before anything here. Nothing about the session changes: same
            // epoch, same generation, no ENTER and no EXIT.
            releasePhysicalDozeForLockedWake(reason, recoveredEpoch);
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
        // does not leave restrictions applied on a device that is not actually idle. The identity
        // passed is the exact one adopted above, not a fresh read.
        ensureOwnedDozePhysicalState(reason, recoveredEpoch);
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
                // Through the serializer so an entry-side restrict cannot physically land after
                // this restore. The done callback belongs to the real ENABLE: if a newer target
                // supersedes it before it runs, it completes with a non-zero code and the durable
                // marker stays, because the debt has not actually been paid.
                // The auto-rotate workaround is fire-and-forget on its own thread and must not
                // decide whether the sensor marker can be cleared.
                requestMotionSensorState(false, done, MOTION_LABEL_FINAL);
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
            // Assigned by the SCREEN_OFF ownership claim below; EPOCH_NONE means no owned session.
            long resumeEpoch;

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
                } else {
                    // The session stays owned behind the keyguard, but it must not stay physically
                    // forced into deep idle while the user is looking at the device. This releases
                    // the force and nothing else: same session, same epoch, same package
                    // generation, no ENTER and no EXIT.
                    releasePhysicalDozeForLockedWake("screen on", EPOCH_NONE);
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
                } else if ((resumeEpoch = claimOwnedSessionForScreenOff()) != EPOCH_NONE) {
                    // Owned-session continuation after a lock-screen wake.
                    //
                    // The branch condition itself is the barrier operation: whether a session is
                    // owned and which session it is are decided together, under physicalEntryLock,
                    // and the package enforcement that races the focused-app callback runs in that
                    // same section. Testing inDoze here and reading the epoch afterwards - even
                    // under the lock - left a window in which the session that selected this branch
                    // could end and a different one appear, so the continuation named the newcomer.
                    //
                    // Everything below is scoped to the exact epoch claimed, and re-validates it,
                    // so ownership disappearing later means the continuation simply stops.
                    enforceSensorStateForLifecycle("screen off");
                    enforceBiometricStateForLifecycle("screen off");
                    resumeOwnedDozeAfterLockedWake("screen off", resumeEpoch);
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
                    // Before the ownership gate below: during PREPARING no session exists yet, so
                    // that gate would discard the very signal that proves the entry worked. This is
                    // the confirming half of the fresh-entry latch.
                    if (inDeepIdle && onDeepIdleObservedForFreshEntry()) {
                        // Consumed by a current fresh attempt; no maintenance bookkeeping applies.
                    } else if (!dozeStateStore.isInDoze()) {
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
