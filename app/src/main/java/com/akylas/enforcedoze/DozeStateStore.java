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
    /**
     * Monotonic id of the marker currently recorded for one key. Bumped by every markApplied() and
     * deliberately never removed, so a generation can only move forward for the lifetime of the
     * install: a stale restore that returns after a newer session re-marked the key compares
     * against a number that has already moved on. Missing on installations that predate this and
     * defaults to 0, which needs no migration.
     */
    private static final String PREFIX_GENERATION = "gen.";
    private static final String KEY_APPLIED_AT = "appliedAt";
    private static final String KEY_IN_DOZE = "inDoze";
    private static final String KEY_ENTRY_PENDING = "entryPending";
    private static final String KEY_OWNED_REFORCE_PENDING = "ownedReforcePending";
    private static final String KEY_SESSION_PHYSICAL_MODE = "sessionPhysicalMode";
    private static final String KEY_FINAL_EXIT_PENDING = "finalExitPending";
    /**
     * A maintenance window temporarily restores selected Doze-owned states while the logical
     * session remains ACTIVE. This durable transaction survives service/process recreation so
     * recovery can distinguish genuine maintenance from an unexpectedly lost physical idle.
     *
     * The generation is monotonic so stale completion work from an older maintenance window cannot
     * retire a newer window's reapply debt.
     */
    private static final String KEY_MAINTENANCE_ACTIVE = "maintenanceActive";
    private static final String KEY_MAINTENANCE_GENERATION = "maintenanceGeneration";
    private static final String KEY_MAINTENANCE_REAPPLY_KEYS = "maintenanceReapplyKeys";

    /**
     * Physical ownership semantics of the ACTIVE session: does ending it owe an explicit unforce?
     * <p>
     * Meaningful only while {@link #KEY_IN_DOZE} is true. A stale value left behind with inDoze
     * false must never create work by itself; the next session commit overwrites it.
     * <pre>
     * UNKNOWN  absent, and the meaning of a session persisted by a build that never recorded this
     * TUNABLE  not currently known to own an explicit privileged force-idle
     * FORCED   may own one, so final exit must conservatively unforce
     * </pre>
     * FORCED is monotonic for the life of the session: a temporary locked-wake unforce does not
     * downgrade it, because the session can be re-forced at the next screen-off and the exit still
     * owes the undo. Absent means UNKNOWN, so no migration framework is needed - but the ambiguity
     * is real and is resolved conservatively at finalization rather than guessed at here.
     */
    public static final int SESSION_PHYSICAL_UNKNOWN = 0;
    public static final int SESSION_PHYSICAL_TUNABLE = 1;
    public static final int SESSION_PHYSICAL_FORCED = 2;
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
    public synchronized boolean markApplied(String key, boolean previousValue) {
        String preKey = PREFIX_PRE + key;
        String appliedKey = PREFIX_APPLIED + key;
        String generationKey = PREFIX_GENERATION + key;

        boolean hadPre = prefs.contains(preKey);
        boolean oldPre = prefs.getBoolean(preKey, false);
        boolean hadApplied = prefs.contains(appliedKey);
        boolean oldApplied = prefs.getBoolean(appliedKey, false);
        boolean hadGeneration = prefs.contains(generationKey);
        long oldGeneration = prefs.getLong(generationKey, 0L);
        boolean hadAppliedAt = prefs.contains(KEY_APPLIED_AT);
        long oldAppliedAt = prefs.getLong(KEY_APPLIED_AT, 0L);

        long generation = oldGeneration + 1L;
        if (prefs.edit()
                .putBoolean(preKey, previousValue)
                .putBoolean(appliedKey, true)
                .putLong(generationKey, generation)
                .putLong(KEY_APPLIED_AT, System.currentTimeMillis())
                .commit()) {
            logToLogcat(TAG, "Marked '" + key + "' as applied (pre-Doze value: " + previousValue
                    + ", generation: " + generation + ")");
            return true;
        }

        // commit() has already changed SharedPreferences' in-memory map even though the durable
        // write failed. Restore the exact previous owner locally so callers cannot act on a
        // journal entry that recovery after process death would never see.
        SharedPreferences.Editor rollback = prefs.edit();

        if (hadPre) {
            rollback.putBoolean(preKey, oldPre);
        } else {
            rollback.remove(preKey);
        }

        if (hadApplied) {
            rollback.putBoolean(appliedKey, oldApplied);
        } else {
            rollback.remove(appliedKey);
        }

        if (hadGeneration) {
            rollback.putLong(generationKey, oldGeneration);
        } else {
            rollback.remove(generationKey);
        }

        if (hadAppliedAt) {
            rollback.putLong(KEY_APPLIED_AT, oldAppliedAt);
        } else {
            rollback.remove(KEY_APPLIED_AT);
        }

        // Best effort for disk, but even another failed commit restores this process' cached view.
        rollback.commit();

        logToLogcat(TAG, "Could not durably mark '" + key
                + "' as applied; physical change must not be dispatched");
        return false;
    }

    /**
     * Everything a restore needs to own its work, read together: which value to put back, and which
     * marker it is putting back.
     */
    public static final class AppliedKeySnapshot {
        public final String key;
        public final boolean previousValue;
        public final long generation;

        AppliedKeySnapshot(String key, boolean previousValue, long generation) {
            this.key = key;
            this.previousValue = previousValue;
            this.generation = generation;
        }
    }

    /**
     * One atomic view of a pending applied key. The applied flag, the pre-Doze value and the
     * generation are read under the same monitor because separate reads can straddle a markApplied()
     * from a newer session: the restore would then put back one session's value while claiming
     * another session's generation, and the compare-and-clear that is meant to protect the newer
     * owner would be comparing the wrong number.
     *
     * @param defaultPreviousValue used only when the key has no recorded pre-Doze value, which is
     *                             possible for markers written before this store recorded one. The
     *                             caller passes the same default it has always used for that key.
     * @return null when the key is no longer applied and there is nothing to restore
     */
    public synchronized AppliedKeySnapshot getAppliedKeySnapshot(String key, boolean defaultPreviousValue) {
        if (!prefs.getBoolean(PREFIX_APPLIED + key, false)) {
            return null;
        }
        return new AppliedKeySnapshot(key,
                prefs.getBoolean(PREFIX_PRE + key, defaultPreviousValue),
                prefs.getLong(PREFIX_GENERATION + key, 0L));
    }

    /**
     * Clears an applied marker only if it is still the one the caller restored.
     * <p>
     * Device-state restores are asynchronous, and a new Doze session can begin - or a maintenance
     * window can re-apply - while one is still outstanding. The old callback would then clear a
     * marker that now records a debt the newer owner genuinely owes, and that debt would never be
     * paid. Re-reading the generation under this monitor is what makes the check and the clear one
     * operation rather than two.
     * <p>
     * The generation counter itself is left in place; only the applied flag is removed, so ids keep
     * moving forward and a later stale callback still fails the comparison.
     *
     * @return true when this exact generation was cleared; false when the key is no longer applied
     * or a newer owner has replaced it
     */
    public synchronized boolean clearAppliedIfGeneration(String key, long expectedGeneration) {
        String appliedKey = PREFIX_APPLIED + key;
        if (!prefs.getBoolean(appliedKey, false)) {
            return false;
        }

        long current = prefs.getLong(PREFIX_GENERATION + key, 0L);
        if (current != expectedGeneration) {
            logToLogcat(TAG, "Not clearing '" + key + "': generation " + current
                    + " has replaced " + expectedGeneration);
            return false;
        }

        if (prefs.edit().remove(appliedKey).commit()) {
            logToLogcat(TAG, "Cleared '" + key + "' (generation " + expectedGeneration
                    + "), nothing left to restore for it");
            return true;
        }

        // The failed remove already changed this process' SharedPreferences cache. Keep the debt
        // visible locally as well as durably so callers cannot report a restore as fully settled.
        prefs.edit().putBoolean(appliedKey, true).commit();
        logToLogcat(TAG, "Could not durably clear '" + key + "' (generation "
                + expectedGeneration + "); restore debt kept");
        return false;
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
     * Any package the previous session still owed is carried forward into the new ownership set.
     * A final un-suspend that failed - Shizuku disappeared, say - correctly leaves its record in
     * place, and simply overwriting it with the fresh blocklist would drop a package that is still
     * physically suspended and would then never be released. The union is formed here, inside the
     * monitor, so the read of the old set and the write of the new one cannot be split by another
     * writer.
     *
     * @return an atomic snapshot of the generation and the exact set it now owns; the caller must
     * submit its command from this, never by rebuilding the union afterwards
     */
    public synchronized SuspendedPackageSession beginSuspendedPackageSession(Collection<String> packages) {
        boolean hadPackages = prefs.contains(KEY_APPLIED_SUSPENDED_PACKAGES);
        Set<String> previousPackages = new LinkedHashSet<>(
                prefs.getStringSet(KEY_APPLIED_SUSPENDED_PACKAGES, new LinkedHashSet<String>()));
        boolean hadGeneration = prefs.contains(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION);
        long previousGeneration =
                prefs.getLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, 0L);

        Set<String> union = new LinkedHashSet<>(previousPackages);
        int previouslyOwed = union.size();
        if (packages != null) {
            union.addAll(packages);
        }

        long next = previousGeneration + 1L;
        if (prefs.edit()
                .putStringSet(KEY_APPLIED_SUSPENDED_PACKAGES, new LinkedHashSet<>(union))
                .putLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, next)
                .commit()) {
            logToLogcat(TAG, "Began suspended-package session generation=" + next
                    + " owned=" + union.size() + " previouslyOwed=" + previouslyOwed);
            return new SuspendedPackageSession(next, Collections.unmodifiableSet(union));
        }

        // The failed commit has already changed this process' SharedPreferences cache. Restore
        // exactly the owner that existed before the attempted generation so the caller cannot
        // submit a package suspension that recovery after process death would not know about.
        SharedPreferences.Editor rollback = prefs.edit();

        if (hadPackages) {
            rollback.putStringSet(KEY_APPLIED_SUSPENDED_PACKAGES,
                    new LinkedHashSet<>(previousPackages));
        } else {
            rollback.remove(KEY_APPLIED_SUSPENDED_PACKAGES);
        }

        if (hadGeneration) {
            rollback.putLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, previousGeneration);
        } else {
            rollback.remove(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION);
        }

        // Best effort for disk; another failed commit still restores the current process' cache.
        rollback.commit();

        logToLogcat(TAG, "Could not durably begin suspended-package session; suspension must not be dispatched");
        return null;
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

        boolean hadPackages = prefs.contains(KEY_APPLIED_SUSPENDED_PACKAGES);
        Set<String> previousPackages = new LinkedHashSet<>(
                prefs.getStringSet(KEY_APPLIED_SUSPENDED_PACKAGES, new LinkedHashSet<String>()));

        if (prefs.edit().remove(KEY_APPLIED_SUSPENDED_PACKAGES).commit()) {
            logToLogcat(TAG, "Cleared the suspended-package record for generation "
                    + expectedGeneration);
            return true;
        }

        // The failed remove already changed this process' SharedPreferences cache. Restore the
        // previous ownership locally so recovery continues to see the outstanding package debt.
        SharedPreferences.Editor rollback = prefs.edit();
        if (hadPackages) {
            rollback.putStringSet(KEY_APPLIED_SUSPENDED_PACKAGES,
                    new LinkedHashSet<>(previousPackages));
        } else {
            rollback.remove(KEY_APPLIED_SUSPENDED_PACKAGES);
        }

        // Best effort for disk; another failed commit still restores this process' cached view.
        rollback.commit();

        logToLogcat(TAG, "Could not durably clear the suspended-package record for generation "
                + expectedGeneration + "; restore debt kept");
        return false;
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
    public synchronized void setInDoze(boolean inDoze) {
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(KEY_IN_DOZE, inDoze);
        if (!inDoze) {
            editor.putBoolean(KEY_FINAL_EXIT_PENDING, false);
        }
        editor.commit();
    }

    public boolean isInDoze() {
        return prefs.getBoolean(KEY_IN_DOZE, false);
    }

    /** One durable view of the maintenance transaction currently owned by the ACTIVE session. */
    public static final class MaintenanceReapplySnapshot {
        public final long generation;
        public final Set<String> keys;

        MaintenanceReapplySnapshot(long generation, Set<String> keys) {
            this.generation = generation;
            this.keys = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
        }
    }

    /**
     * Opens a durable maintenance transaction before any temporary state restore is dispatched.
     *
     * An empty key set is valid: recovery still needs the active marker to distinguish a genuine
     * Android maintenance window from an owned session whose physical idle unexpectedly disappeared.
     *
     * @return the new maintenance generation, or 0 when the journal could not be persisted
     */
    public synchronized long beginMaintenanceReapply(Collection<String> keys) {
        if (!prefs.getBoolean(KEY_IN_DOZE, false)) {
            return 0L;
        }

        boolean hadActive = prefs.contains(KEY_MAINTENANCE_ACTIVE);
        boolean oldActive = prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false);
        boolean hadGeneration = prefs.contains(KEY_MAINTENANCE_GENERATION);
        long oldGeneration = prefs.getLong(KEY_MAINTENANCE_GENERATION, 0L);
        boolean hadKeys = prefs.contains(KEY_MAINTENANCE_REAPPLY_KEYS);
        Set<String> oldKeys = new LinkedHashSet<>(
                prefs.getStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, Collections.emptySet()));

        long generation = oldGeneration + 1L;
        Set<String> newKeys = new LinkedHashSet<>(keys);

        if (prefs.edit()
                .putBoolean(KEY_MAINTENANCE_ACTIVE, true)
                .putLong(KEY_MAINTENANCE_GENERATION, generation)
                .putStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, newKeys)
                .commit()) {
            return generation;
        }

        // commit() already changed SharedPreferences' cached map. Restore the exact previous
        // transaction locally as well as best-effort on disk, including key absence on upgrades.
        SharedPreferences.Editor rollback = prefs.edit();

        if (hadActive) {
            rollback.putBoolean(KEY_MAINTENANCE_ACTIVE, oldActive);
        } else {
            rollback.remove(KEY_MAINTENANCE_ACTIVE);
        }

        if (hadGeneration) {
            rollback.putLong(KEY_MAINTENANCE_GENERATION, oldGeneration);
        } else {
            rollback.remove(KEY_MAINTENANCE_GENERATION);
        }

        if (hadKeys) {
            rollback.putStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, oldKeys);
        } else {
            rollback.remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        }

        rollback.commit();
        return 0L;
    }

    /**
     * Returns maintenance ownership only while its logical Doze session is still ACTIVE. Stale
     * maintenance metadata beside inDoze=false must never create recovery work by itself.
     */
    public synchronized MaintenanceReapplySnapshot getMaintenanceReapplySnapshot() {
        if (!prefs.getBoolean(KEY_IN_DOZE, false)
                || !prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false)) {
            return null;
        }

        return new MaintenanceReapplySnapshot(
                prefs.getLong(KEY_MAINTENANCE_GENERATION, 0L),
                prefs.getStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, Collections.emptySet()));
    }

    /**
     * Atomically retires a maintenance key when current policy no longer wants its restriction.
     *
     * The temporary maintenance restore has already put the user's value back physically, so there
     * is no reapply command whose completion can settle the transaction. Removing the ordinary
     * applied owner and the maintenance key in separate commits would create a process-death gap.
     *
     * Both generations must still be the ones captured by the caller. A newer ordinary owner or a
     * newer maintenance window therefore wins and nothing is cleared.
     */
    public synchronized boolean retireMaintenanceReapplyKeyIfGenerations(
            String key,
            long expectedMaintenanceGeneration,
            long expectedAppliedGeneration) {
        if (!prefs.getBoolean(KEY_IN_DOZE, false)
                || !prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false)) {
            return false;
        }

        long currentMaintenanceGeneration =
                prefs.getLong(KEY_MAINTENANCE_GENERATION, 0L);
        if (currentMaintenanceGeneration != expectedMaintenanceGeneration) {
            return false;
        }

        boolean hadActive = prefs.contains(KEY_MAINTENANCE_ACTIVE);
        boolean oldActive = prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false);
        boolean hadKeys = prefs.contains(KEY_MAINTENANCE_REAPPLY_KEYS);
        Set<String> oldKeys = new LinkedHashSet<>(
                prefs.getStringSet(
                        KEY_MAINTENANCE_REAPPLY_KEYS,
                        Collections.emptySet()));

        if (!oldKeys.contains(key)) {
            return false;
        }

        String appliedKey = PREFIX_APPLIED + key;
        boolean hadApplied = prefs.contains(appliedKey);
        boolean oldApplied = prefs.getBoolean(appliedKey, false);
        if (!oldApplied) {
            return false;
        }

        long currentAppliedGeneration =
                prefs.getLong(PREFIX_GENERATION + key, 0L);
        if (currentAppliedGeneration != expectedAppliedGeneration) {
            return false;
        }

        Set<String> remaining = new LinkedHashSet<>(oldKeys);
        remaining.remove(key);

        SharedPreferences.Editor editor = prefs.edit()
                .remove(appliedKey);

        if (remaining.isEmpty()) {
            editor.putBoolean(KEY_MAINTENANCE_ACTIVE, false)
                    .remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        } else {
            editor.putBoolean(KEY_MAINTENANCE_ACTIVE, true)
                    .putStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, remaining);
        }

        if (editor.commit()) {
            return true;
        }

        // commit() has already changed the cached map. Restore exactly the two ownership records
        // this operation attempted to settle; pre-state and both generation counters were untouched.
        SharedPreferences.Editor rollback = prefs.edit();

        if (hadApplied) {
            rollback.putBoolean(appliedKey, oldApplied);
        } else {
            rollback.remove(appliedKey);
        }

        if (hadActive) {
            rollback.putBoolean(KEY_MAINTENANCE_ACTIVE, oldActive);
        } else {
            rollback.remove(KEY_MAINTENANCE_ACTIVE);
        }

        if (hadKeys) {
            rollback.putStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, oldKeys);
        } else {
            rollback.remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        }

        rollback.commit();
        return false;
    }

    /**
     * Retires one reapply obligation only when it still belongs to the exact maintenance generation
     * that dispatched the physical work. A stale callback from an older window may therefore never
     * discharge a newer window's debt.
     */
    public synchronized boolean clearMaintenanceReapplyKeyIfGeneration(
            String key, long expectedGeneration) {
        if (!prefs.getBoolean(KEY_IN_DOZE, false)
                || !prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false)) {
            return false;
        }

        long currentGeneration = prefs.getLong(KEY_MAINTENANCE_GENERATION, 0L);
        if (currentGeneration != expectedGeneration) {
            return false;
        }

        boolean hadActive = prefs.contains(KEY_MAINTENANCE_ACTIVE);
        boolean oldActive = prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false);
        boolean hadKeys = prefs.contains(KEY_MAINTENANCE_REAPPLY_KEYS);
        Set<String> oldKeys = new LinkedHashSet<>(
                prefs.getStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, Collections.emptySet()));

        if (!oldKeys.contains(key)) {
            return false;
        }

        Set<String> remaining = new LinkedHashSet<>(oldKeys);
        remaining.remove(key);

        SharedPreferences.Editor editor = prefs.edit();
        if (remaining.isEmpty()) {
            editor.putBoolean(KEY_MAINTENANCE_ACTIVE, false)
                    .remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        } else {
            editor.putBoolean(KEY_MAINTENANCE_ACTIVE, true)
                    .putStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, remaining);
        }

        if (editor.commit()) {
            return true;
        }

        // Restore the exact pre-settlement cached state. The generation itself was never changed.
        SharedPreferences.Editor rollback = prefs.edit();
        if (hadActive) {
            rollback.putBoolean(KEY_MAINTENANCE_ACTIVE, oldActive);
        } else {
            rollback.remove(KEY_MAINTENANCE_ACTIVE);
        }

        if (hadKeys) {
            rollback.putStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, oldKeys);
        } else {
            rollback.remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        }

        rollback.commit();
        return false;
    }

    /**
     * Closes a maintenance transaction that has no per-key reapply debt, but only after the caller
     * has observed the physical return to deep IDLE. Generation matching prevents an old maintenance
     * exit from closing a newer window.
     */
    public synchronized boolean finishEmptyMaintenanceReapplyIfGeneration(
            long expectedGeneration) {
        if (!prefs.getBoolean(KEY_IN_DOZE, false)
                || !prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false)) {
            return false;
        }

        long currentGeneration = prefs.getLong(KEY_MAINTENANCE_GENERATION, 0L);
        if (currentGeneration != expectedGeneration) {
            return false;
        }

        boolean hadActive = prefs.contains(KEY_MAINTENANCE_ACTIVE);
        boolean oldActive = prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false);
        boolean hadKeys = prefs.contains(KEY_MAINTENANCE_REAPPLY_KEYS);
        Set<String> oldKeys = new LinkedHashSet<>(
                prefs.getStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, Collections.emptySet()));

        if (!oldKeys.isEmpty()) {
            return false;
        }

        if (prefs.edit()
                .putBoolean(KEY_MAINTENANCE_ACTIVE, false)
                .remove(KEY_MAINTENANCE_REAPPLY_KEYS)
                .commit()) {
            return true;
        }

        // commit() changed the cached map even though persistence failed. Restore the exact old view.
        SharedPreferences.Editor rollback = prefs.edit();

        if (hadActive) {
            rollback.putBoolean(KEY_MAINTENANCE_ACTIVE, oldActive);
        } else {
            rollback.remove(KEY_MAINTENANCE_ACTIVE);
        }

        if (hadKeys) {
            rollback.putStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, oldKeys);
        } else {
            rollback.remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        }

        rollback.commit();
        return false;
    }

    /**
     * True only while an ACTIVE legacy session is waiting for its already-requested final exit to
     * become physically classifiable. Missing on older installs means false.
     */
    public boolean isFinalExitPending() {
        return prefs.getBoolean(KEY_IN_DOZE, false)
                && prefs.getBoolean(KEY_FINAL_EXIT_PENDING, false);
    }

    /**
     * Persists final-exit intent without dropping session ownership. Used only when a legacy
     * UNKNOWN session cannot yet be distinguished from natural deep idle.
     */
    public synchronized boolean markFinalExitPending() {
        if (!prefs.getBoolean(KEY_IN_DOZE, false)) {
            return false;
        }

        // Always attempt the durable write. A previous commit may have failed after SharedPreferences
        // updated its in-memory map, so seeing true locally is not proof that recovery can find it
        // after process death. On failure the local true value is intentionally retained: this
        // process must still honor the already-requested final exit while a later retry persists it.
        return prefs.edit().putBoolean(KEY_FINAL_EXIT_PENDING, true).commit();
    }

    /**
     * Marks that a privileged {@code dumpsys deviceidle force-idle deep} has been dispatched and
     * its outcome is not yet known.
     * <p>
     * A successful force is itself physical state - it leaves {@code mForceIdle=true} inside
     * DeviceIdleController - so an attempt interrupted by process death can leave the device forced
     * with nobody left who knows it. That is the one part of entry no in-memory flag can cover,
     * which is why this single bit is durable.
     * <p>
     * Together with {@link #KEY_IN_DOZE} it forms three states, and only three:
     * <pre>
     * NONE      entryPending=false inDoze=false
     * PREPARING entryPending=true  inDoze=false
     * ACTIVE    entryPending=false inDoze=true
     * </pre>
     * The fourth combination cannot occur on disk because {@link #commitDozeSession()} writes both
     * keys in one commit. Absent means false, so no migration is needed.
     */
    public boolean beginForceIdleAttempt() {
        if (prefs.edit().putBoolean(KEY_ENTRY_PENDING, true).commit()) {
            return true;
        }
        // commit() updates the in-memory map before it reports a persistence failure, so this
        // process would otherwise go on believing in a marker that no recovery could ever find -
        // and refuse fresh entries because of it. Put the local view back to NONE; best effort,
        // and if this write fails too the map still ends up reading false, which is the point.
        prefs.edit().putBoolean(KEY_ENTRY_PENDING, false).commit();
        return false;
    }

    /**
     * Ends a PREPARING attempt without a session: either the force was rejected, or a successful
     * but unwanted force has been physically undone. Never call this before the unforce has
     * actually completed - the bit is the only record of that debt.
     */
    public boolean abortForceIdleAttempt() {
        if (prefs.edit().putBoolean(KEY_ENTRY_PENDING, false).commit()) {
            return true;
        }
        // The debt is still on disk, so the local view must keep saying so. Leaving it reading
        // false would let this process start a fresh force while a stale marker still exists, and
        // would hide the debt from the recovery that is meant to settle it.
        prefs.edit().putBoolean(KEY_ENTRY_PENDING, true).commit();
        return false;
    }

    public boolean isEntryPending() {
        return prefs.getBoolean(KEY_ENTRY_PENDING, false);
    }

    /**
     * PREPARING to ACTIVE in a single synchronous commit, so a crash can never be observed with
     * both bits set. SharedPreferences writes one XML file, so the two puts land together or not
     * at all.
     */
    public synchronized boolean commitDozeSession() {
        boolean hadMaintenanceActive = prefs.contains(KEY_MAINTENANCE_ACTIVE);
        boolean previousMaintenanceActive =
                prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false);
        boolean hadMaintenanceKeys = prefs.contains(KEY_MAINTENANCE_REAPPLY_KEYS);
        Set<String> previousMaintenanceKeys = new LinkedHashSet<>(
                prefs.getStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, Collections.emptySet()));

        if (prefs.edit()
                .putBoolean(KEY_IN_DOZE, true)
                .putBoolean(KEY_ENTRY_PENDING, false)
                .putBoolean(KEY_FINAL_EXIT_PENDING, false)
                .putInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_FORCED)
                .putBoolean(KEY_MAINTENANCE_ACTIVE, false)
                .remove(KEY_MAINTENANCE_REAPPLY_KEYS)
                .commit()) {
            return true;
        }

        // Nothing reached disk, so the durable state is still the PREPARING record written before
        // the force was dispatched. Restore that ownership locally together with the exact stale
        // maintenance representation that existed before this failed fresh-session commit.
        SharedPreferences.Editor rollback = prefs.edit()
                .putBoolean(KEY_IN_DOZE, false)
                .putBoolean(KEY_ENTRY_PENDING, true)
                .putBoolean(KEY_FINAL_EXIT_PENDING, false)
                .putInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_UNKNOWN);

        if (hadMaintenanceActive) {
            rollback.putBoolean(KEY_MAINTENANCE_ACTIVE, previousMaintenanceActive);
        } else {
            rollback.remove(KEY_MAINTENANCE_ACTIVE);
        }

        if (hadMaintenanceKeys) {
            rollback.putStringSet(
                    KEY_MAINTENANCE_REAPPLY_KEYS, previousMaintenanceKeys);
        } else {
            rollback.remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        }

        rollback.commit();
        return false;
    }
    public int getSessionPhysicalMode() {
        return prefs.getInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_UNKNOWN);
    }

    /**
     * Claims an ACTIVE session that entered through the unprivileged tunable path, in one commit so
     * a session can never exist without its physical semantics recorded beside it.
     */
    public synchronized boolean beginTunableDozeSession() {
        boolean hadMaintenanceActive = prefs.contains(KEY_MAINTENANCE_ACTIVE);
        boolean previousMaintenanceActive =
                prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false);
        boolean hadMaintenanceKeys = prefs.contains(KEY_MAINTENANCE_REAPPLY_KEYS);
        Set<String> previousMaintenanceKeys = new LinkedHashSet<>(
                prefs.getStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, Collections.emptySet()));

        if (prefs.edit()
                .putBoolean(KEY_IN_DOZE, true)
                .putBoolean(KEY_FINAL_EXIT_PENDING, false)
                .putInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_TUNABLE)
                .putBoolean(KEY_MAINTENANCE_ACTIVE, false)
                .remove(KEY_MAINTENANCE_REAPPLY_KEYS)
                .commit()) {
            return true;
        }

        // Nothing reached disk, but commit() already changed the in-memory map. Restore the
        // no-session state together with the exact stale maintenance representation that existed
        // before this failed fresh-session claim.
        SharedPreferences.Editor rollback = prefs.edit()
                .putBoolean(KEY_IN_DOZE, false)
                .putBoolean(KEY_FINAL_EXIT_PENDING, false)
                .putInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_UNKNOWN);

        if (hadMaintenanceActive) {
            rollback.putBoolean(KEY_MAINTENANCE_ACTIVE, previousMaintenanceActive);
        } else {
            rollback.remove(KEY_MAINTENANCE_ACTIVE);
        }

        if (hadMaintenanceKeys) {
            rollback.putStringSet(
                    KEY_MAINTENANCE_REAPPLY_KEYS, previousMaintenanceKeys);
        } else {
            rollback.remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        }

        rollback.commit();
        return false;
    }
    /**
     * Ends an ACTIVE session and records any physical debt that ending it creates, in ONE commit.
     * <p>
     * This is the whole point of the change: previously ownership was dropped and the unforce was
     * dispatched separately, so a Shizuku death - or a process death - between them left the device
     * at mForceIdle=true with every durable flag clear and nothing able to notice. Committing the
     * marker and the ownership clear together makes that state unrepresentable.
     *
     * @param claimUnforceDebt true when this session may own an explicit force-idle
     * @return false when nothing reached disk, in which case the session is still owned and the
     * caller must not end the epoch, write an EXIT row or dispatch physical cleanup
     */
    public synchronized boolean endDozeSession(boolean claimUnforceDebt) {
        boolean previousInDoze = prefs.getBoolean(KEY_IN_DOZE, false);
        int previousMode = prefs.getInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_UNKNOWN);
        boolean previousFinalExitPending = prefs.getBoolean(KEY_FINAL_EXIT_PENDING, false);
        boolean previousDebt = prefs.getBoolean(KEY_OWNED_REFORCE_PENDING, false);

        boolean hadMaintenanceActive = prefs.contains(KEY_MAINTENANCE_ACTIVE);
        boolean previousMaintenanceActive =
                prefs.getBoolean(KEY_MAINTENANCE_ACTIVE, false);
        boolean hadMaintenanceKeys = prefs.contains(KEY_MAINTENANCE_REAPPLY_KEYS);
        Set<String> previousMaintenanceKeys = new LinkedHashSet<>(
                prefs.getStringSet(KEY_MAINTENANCE_REAPPLY_KEYS, Collections.emptySet()));

        // Never written false. A locked-wake release or a reforce cleanup may already own the
        // shared marker, and a tunable finalization has no business discharging their debt.
        boolean debt = previousDebt || claimUnforceDebt;

        if (prefs.edit()
                .putBoolean(KEY_IN_DOZE, false)
                .putInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_UNKNOWN)
                .putBoolean(KEY_FINAL_EXIT_PENDING, false)
                .putBoolean(KEY_OWNED_REFORCE_PENDING, debt)
                .putBoolean(KEY_MAINTENANCE_ACTIVE, false)
                .remove(KEY_MAINTENANCE_REAPPLY_KEYS)
                .commit()) {
            return true;
        }

        // Restore exactly what was there, not what was expected to be there: unrelated physical
        // debt and the maintenance transaction must survive a failed finalization as faithfully as
        // logical session ownership does.
        SharedPreferences.Editor rollback = prefs.edit()
                .putBoolean(KEY_IN_DOZE, previousInDoze)
                .putInt(KEY_SESSION_PHYSICAL_MODE, previousMode)
                .putBoolean(KEY_FINAL_EXIT_PENDING, previousFinalExitPending)
                .putBoolean(KEY_OWNED_REFORCE_PENDING, previousDebt);

        if (hadMaintenanceActive) {
            rollback.putBoolean(KEY_MAINTENANCE_ACTIVE, previousMaintenanceActive);
        } else {
            rollback.remove(KEY_MAINTENANCE_ACTIVE);
        }

        if (hadMaintenanceKeys) {
            rollback.putStringSet(
                    KEY_MAINTENANCE_REAPPLY_KEYS, previousMaintenanceKeys);
        } else {
            rollback.remove(KEY_MAINTENANCE_REAPPLY_KEYS);
        }

        rollback.commit();
        return false;
    }
    /**
     * Settles a successful owned reforce and records that the session now owns a physical force, in
     * ONE commit.
     * <p>
     * Clearing the marker and writing the mode separately would leave a window in which a force
     * that has just landed is owned by nobody: the marker says settled, the mode still says
     * tunable, and the eventual exit would owe no unforce.
     *
     * @return false when nothing reached disk, in which case the transaction stays unresolved and
     * the conservative resolver owns it
     */
    public synchronized boolean settleOwnedReforceAsForced() {
        int previousMode = prefs.getInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_UNKNOWN);
        if (prefs.edit()
                .putBoolean(KEY_OWNED_REFORCE_PENDING, false)
                .putInt(KEY_SESSION_PHYSICAL_MODE, SESSION_PHYSICAL_FORCED)
                .commit()) {
            return true;
        }
        prefs.edit()
                .putBoolean(KEY_OWNED_REFORCE_PENDING, true)
                .putInt(KEY_SESSION_PHYSICAL_MODE, previousMode)
                .commit();
        return false;
    }

    /**
     * Marks that a privileged force-idle has been dispatched on behalf of a session that is already
     * ACTIVE - the lock-screen resume and recovery Mode A - and that its physical outcome is not yet
     * settled.
     * <p>
     * Deliberately a separate key from {@link #KEY_ENTRY_PENDING} rather than a reuse of it. That
     * one carries PREPARING semantics and is defined over exactly three combinations, in which
     * entryPending and inDoze are never both true. An owned reforce happens precisely when inDoze
     * IS true, so overloading would produce that forbidden fourth combination - and the recovery
     * path tests entryPending first and answers it by unforcing, which would tear deep idle out
     * from under a live session.
     * <p>
     * This key is therefore a second, orthogonal axis: it may be true with inDoze either true (a
     * reforce for a session still owned) or false (a debt left behind by a session that has ended).
     * The three-state contract over the other two keys is untouched.
     * <p>
     * It exists for the same reason the PREPARING marker does. A force that succeeds leaves
     * mForceIdle=true inside DeviceIdleController, and the resume path is otherwise markerless, so
     * a process death after the force landed would leave the device forced with nothing on disk
     * recording that an undo is owed. Nothing else can be relied on to remove it: screen-on clearing
     * a forced device is an assumption about framework internals that has never been demonstrated
     * here, and the only demonstrated remedy is an explicit unforce.
     * <p>
     * Absent means false, so no migration is needed.
     * <p>
     * The name says reforce because that was its first user, but the bit means the more general
     * thing: an owned-session physical transaction of ours is unresolved. The temporary release
     * performed when a locked lock screen becomes visible sets it too, for the same reason - it also
     * changes physical state that an interrupted process would otherwise leave unaccounted. Both
     * settle it through {@link #finishOwnedReforceAttempt()}, and the recovery rule is the same for
     * either: unforce conservatively, clear, then let ordinary policy decide.
     */
    public boolean beginOwnedReforceAttempt() {
        if (prefs.edit().putBoolean(KEY_OWNED_REFORCE_PENDING, true).commit()) {
            return true;
        }
        // Nothing reached disk. commit() still updated the in-memory map, so put the local view
        // back to "no debt": believing in a marker no recovery could find would block fresh entry
        // for a transaction that is about to be abandoned. The caller must not dispatch the force.
        prefs.edit().putBoolean(KEY_OWNED_REFORCE_PENDING, false).commit();
        return false;
    }

    /**
     * Settles an owned reforce: either its outcome left nothing physical to undo, or the corrective
     * unforce has actually completed. Never call it in anticipation - while this bit is set it is
     * the only record that the device may still be forced.
     */
    public boolean finishOwnedReforceAttempt() {
        if (prefs.edit().putBoolean(KEY_OWNED_REFORCE_PENDING, false).commit()) {
            return true;
        }
        // The debt is still on disk, so the local view has to keep saying so. Reading false here
        // would let this process start a fresh session on top of an unresolved physical force and
        // would hide the debt from the recovery meant to settle it.
        prefs.edit().putBoolean(KEY_OWNED_REFORCE_PENDING, true).commit();
        return false;
    }

    public boolean isOwnedReforcePending() {
        return prefs.getBoolean(KEY_OWNED_REFORCE_PENDING, false);
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
