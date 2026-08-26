package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Disk-backed store for the device state that was captured right before entering Doze.
 * <p>
 * ForceDozeService used to keep this in plain instance fields (wasAirplaneOn, wasWiFiTurnedOn, ...).
 * Android kills background services aggressively (One UI in particular), and when the service is
 * recreated through START_STICKY those fields are back to their default of {@code false}. The
 * restore code then reads "WiFi was off before Doze" and leaves the radios/sensors disabled
 * forever. Persisting here means a restore is still possible after the process was killed.
 * <p>
 * For every managed toggle we store two things:
 * <ul>
 *     <li>{@code pre.<key>} - the value the toggle had before we touched it.</li>
 *     <li>{@code applied.<key>} - true while we owe the user a revert of that toggle.</li>
 * </ul>
 * Writes use {@link SharedPreferences.Editor#commit()} rather than {@code apply()} on purpose:
 * {@code apply()} only guarantees the write once the process gets a chance to flush it, and the
 * whole point of this class is to survive a process death that can happen at any moment.
 */
public class DozeStateStore {

    public static final String KEY_AIRPLANE = "airplane";
    public static final String KEY_BLUETOOTH = "bluetooth";
    public static final String KEY_GPS = "gps";
    public static final String KEY_WIFI = "wifi";
    public static final String KEY_MOBILE_DATA = "mobileData";
    public static final String KEY_BATTERY_SAVER = "batterySaver";
    public static final String KEY_ALL_SENSORS = "allSensors";
    public static final String KEY_BIOMETRICS = "biometrics";
    public static final String KEY_MOTION_SENSORS = "motionSensors";
    /** Recorded for the enter-Doze decisions only, never restored. */
    public static final String KEY_HOTSPOT = "hotspot";

    private static final String TAG = "DozeStateStore";
    /**
     * A private store of its own, deliberately not the default SharedPreferences. That keeps this
     * transient recovery state out of the JSON settings backup (which exports the default file),
     * where restoring another device's in-flight Doze session would be meaningless or harmful.
     */
    private static final String PREFS_NAME = "enforcedoze_doze_state";
    private static final String PREFIX_PRE = "pre.";
    private static final String PREFIX_APPLIED = "applied.";
    private static final String KEY_APPLIED_AT = "appliedAt";
    private static final String KEY_IN_DOZE = "inDoze";
    private static final String KEY_APPLIED_SUSPENDED_PACKAGES = "appliedSuspendedPackages";
    /**
     * Monotonic id of the Doze session that owns the recorded package set. Only a genuinely fresh
     * session allocates one; temporary lock-screen suspend/unsuspend never does. Missing on
     * installations that predate this, which default to 0 with no migration needed.
     */
    private static final String KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION =
            "appliedSuspendedPackagesGeneration";

    private static volatile DozeStateStore instance;

    private final SharedPreferences prefs;

    private DozeStateStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static DozeStateStore getInstance(Context context) {
        if (instance == null) {
            synchronized (DozeStateStore.class) {
                if (instance == null) {
                    instance = new DozeStateStore(context);
                }
            }
        }
        return instance;
    }

    /** Remembers the value a toggle had before Doze, without claiming we changed it. */
    public void recordPreDozeValue(String key, boolean value) {
        prefs.edit().putBoolean(PREFIX_PRE + key, value).commit();
    }

    public boolean getPreDozeValue(String key, boolean defaultValue) {
        return prefs.getBoolean(PREFIX_PRE + key, defaultValue);
    }

    /**
     * Marks that we changed {@code key} and therefore owe a revert back to {@code previousValue}.
     * Must be called <em>before</em> the shell command is issued, so that a process death between
     * the mark and the command still leaves us with a revert to perform (reverting something that
     * was never changed is harmless, forgetting a revert is not).
     */
    public void markApplied(String key, boolean previousValue) {
        prefs.edit()
                .putBoolean(PREFIX_PRE + key, previousValue)
                .putBoolean(PREFIX_APPLIED + key, true)
                .putLong(KEY_APPLIED_AT, System.currentTimeMillis())
                .commit();
        logToLogcat(TAG, "Marked '" + key + "' as applied (pre-Doze value: " + previousValue + ")");
    }

    public boolean isApplied(String key) {
        return prefs.getBoolean(PREFIX_APPLIED + key, false);
    }

    /** Called once a toggle has been reverted. */
    public void clearApplied(String key) {
        prefs.edit().remove(PREFIX_APPLIED + key).commit();
        logToLogcat(TAG, "Cleared '" + key + "', nothing left to restore for it");
    }

    /**
     * Clears a whole set in one synchronous write. The wake-up path clears every toggle it just
     * reverted, and doing that one key at a time meant one blocking disk write each on the very
     * thread the user is waiting on.
     */
    public void clearApplied(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : keys) {
            editor.remove(PREFIX_APPLIED + key);
        }
        editor.commit();
        logToLogcat(TAG, "Cleared " + keys + ", nothing left to restore for them");
    }

    /**
     * An atomic view of "which packages does the current Doze session own, and which session is
     * that". Generation and set must be read together: two independent reads can straddle a
     * beginSuspendedPackageSession() commit and produce an operation that claims the new
     * generation while carrying the old session's packages.
     */
    public static final class SuspendedPackageSession {
        public final long generation;
        /** Unmodifiable copy - never the live SharedPreferences instance. */
        public final Set<String> packages;

        SuspendedPackageSession(long generation, Set<String> packages) {
            this.generation = generation;
            this.packages = packages;
        }

        public boolean isEmpty() {
            return packages.isEmpty();
        }
    }

    /**
     * Starts a new package session: records the exact set and allocates the next generation in a
     * single commit, so a process kill can never leave a set owned by a stale generation.
     * <p>
     * Called only when a fresh Doze session captures its package set. The temporary lock-screen
     * unsuspend and the screen-off re-suspend operate on the existing generation and never
     * allocate a new one.
     *
     * @return the generation now owning the set
     */
    public synchronized long beginSuspendedPackageSession(Collection<String> packages) {
        long next = prefs.getLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, 0L) + 1;
        prefs.edit()
                .putStringSet(KEY_APPLIED_SUSPENDED_PACKAGES, new LinkedHashSet<>(packages))
                .putLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, next)
                .commit();
        logToLogcat(TAG, "Began suspended-package session generation=" + next
                + " count=" + packages.size());
        return next;
    }

    /**
     * One synchronized read of both fields, on the same monitor as
     * {@link #beginSuspendedPackageSession} and
     * {@link #clearAppliedSuspendedPackagesIfGeneration}, so no writer can interleave.
     */
    public synchronized SuspendedPackageSession getSuspendedPackageSession() {
        long generation = prefs.getLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, 0L);
        // Defensive copy: SharedPreferences hands back its live cached instance.
        Set<String> packages = new LinkedHashSet<>(
                prefs.getStringSet(KEY_APPLIED_SUSPENDED_PACKAGES, new LinkedHashSet<String>()));
        return new SuspendedPackageSession(generation, Collections.unmodifiableSet(packages));
    }

    /** A copy: SharedPreferences forbids mutating the set it hands back. */
    public Set<String> getAppliedSuspendedPackages() {
        return getSuspendedPackageSession().packages;
    }

    public boolean hasAppliedSuspendedPackages() {
        return !getSuspendedPackageSession().isEmpty();
    }

    /**
     * Compare-and-clear. The generation is checked inside this monitor rather than by the caller,
     * so a newer session cannot install itself between the check and the write - which is what an
     * old session's final un-suspend callback would otherwise wipe out.
     * <p>
     * The generation counter is deliberately left in place: it must keep increasing so that a
     * later comparison against a stale generation cannot accidentally match.
     *
     * @return true when ownership was cleared, false when the record belongs to a newer session
     */
    public synchronized boolean clearAppliedSuspendedPackagesIfGeneration(long expectedGeneration) {
        long current = prefs.getLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, 0L);
        if (current != expectedGeneration) {
            logToLogcat(TAG, "Not clearing the suspended-package record: expected generation "
                    + expectedGeneration + " but the current owner is " + current);
            return false;
        }
        prefs.edit().remove(KEY_APPLIED_SUSPENDED_PACKAGES).commit();
        logToLogcat(TAG, "Cleared the suspended-package record for generation " + expectedGeneration);
        return true;
    }

    /** The toggles that are still waiting to be reverted. */
    public Set<String> getAppliedKeys() {
        Set<String> result = new LinkedHashSet<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String prefKey = entry.getKey();
            if (prefKey.startsWith(PREFIX_APPLIED) && Boolean.TRUE.equals(entry.getValue())) {
                result.add(prefKey.substring(PREFIX_APPLIED.length()));
            }
        }
        return result;
    }

    public boolean hasPendingRestore() {
        return !getAppliedKeys().isEmpty();
    }

    /** When the currently pending toggles were applied, or 0 if nothing is pending. */
    public long getAppliedAt() {
        return prefs.getLong(KEY_APPLIED_AT, 0L);
    }

    /**
     * Survives process death so a recreated service knows it was mid-Doze and has to restore,
     * even when the screen turned back on while the process was gone.
     */
    public void setInDoze(boolean inDoze) {
        prefs.edit().putBoolean(KEY_IN_DOZE, inDoze).commit();
    }

    public boolean isInDoze() {
        return prefs.getBoolean(KEY_IN_DOZE, false);
    }

    /** Drops every pending revert. Only for a full reset - normal flow clears keys one by one. */
    public void clearAllApplied() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : getAppliedKeys()) {
            editor.remove(PREFIX_APPLIED + key);
        }
        editor.remove(KEY_APPLIED_AT);
        editor.commit();
    }
}
