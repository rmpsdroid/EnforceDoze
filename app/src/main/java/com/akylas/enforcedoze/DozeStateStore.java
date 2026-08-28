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
    private static final String KEY_ENTRY_PENDING = "entryPending";
    private static final String KEY_OWNED_REFORCE_PENDING = "ownedReforcePending";
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
        Set<String> union = new LinkedHashSet<>(
                prefs.getStringSet(KEY_APPLIED_SUSPENDED_PACKAGES, new LinkedHashSet<String>()));
        int previouslyOwed = union.size();
        if (packages != null) {
            union.addAll(packages);
        }

        long next = prefs.getLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, 0L) + 1;
        prefs.edit()
                .putStringSet(KEY_APPLIED_SUSPENDED_PACKAGES, new LinkedHashSet<>(union))
                .putLong(KEY_APPLIED_SUSPENDED_PACKAGES_GENERATION, next)
                .commit();
        logToLogcat(TAG, "Began suspended-package session generation=" + next
                + " owned=" + union.size() + " previouslyOwed=" + previouslyOwed);
        return new SuspendedPackageSession(next, Collections.unmodifiableSet(union));
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
    public boolean commitDozeSession() {
        if (prefs.edit()
                .putBoolean(KEY_IN_DOZE, true)
                .putBoolean(KEY_ENTRY_PENDING, false)
                .commit()) {
            return true;
        }
        // Nothing reached disk, so the durable state is still the PREPARING record written before
        // the force was dispatched. Rewriting exactly those values restores the local view to match
        // it and cannot destroy the record, since it is what the file should already hold. The
        // caller must not treat the session as owned.
        prefs.edit()
                .putBoolean(KEY_IN_DOZE, false)
                .putBoolean(KEY_ENTRY_PENDING, true)
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
