package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reports when a call starts and ends, so an active Doze session can be released for it.
 * <p>
 * Two independent sources are watched, because neither covers everything:
 * <ul>
 *     <li>telephony call state - cellular calls, via TelephonyCallback on API 31+ and the
 *     deprecated PhoneStateListener below that;</li>
 *     <li>audio mode - VoIP and other communication apps, which never change the telephony call
 *     state at all, via AudioManager.OnModeChangedListener on API 31+.</li>
 * </ul>
 * Call state is tracked <em>per telephony source</em> rather than in one shared flag. Each
 * registered subscription reports only its own state, and a listener also receives an immediate
 * callback when it registers, so a single shared boolean let SIM2's initial IDLE overwrite an
 * active call on SIM1 and end the session underneath it. The aggregate is "any source is
 * non-IDLE".
 * <p>
 * No polling, and no new permissions: READ_PHONE_STATE is already declared and already used by
 * {@link Utils#isUserInCall(Context)}. Telephony registration is skipped while that permission is
 * missing and can be retried later through {@link #ensureTelephonyRegistered()} once the service
 * has granted it; registration is idempotent, so retrying never produces duplicate callbacks.
 */
public class CallStateWatcher {

    public interface Listener {
        /** A call became active. {@code reason} is a state name only, never caller information. */
        void onCallActive(String reason);

        void onCallEnded();
    }

    private static final String TAG = "CallStateWatcher";

    /** One registered telephony subscription and the last state it reported for itself. */
    private static final class TelephonySource {
        final TelephonyManager manager;
        volatile boolean busy;
        Object callback;
        PhoneStateListener legacyListener;

        TelephonySource(TelephonyManager manager) {
            this.manager = manager;
        }
    }

    private final AtomicBoolean callActive = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<TelephonySource> telephonySources = new CopyOnWriteArrayList<>();

    private Context appContext;
    private volatile Listener listener;
    private AudioManager.OnModeChangedListener modeChangedListener;
    private AudioManager audioManager;
    private volatile boolean audioBusy = false;

    /**
     * Must only be called once the service is fully initialised. A call callback leads to
     * exitDoze(), which touches the settings, the blocklists, the statistics and the shell
     * backend, so starting the watcher earlier in onCreate() risked all of those being null.
     */
    public void start(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;

        // Audio mode needs no permission, so it is always safe to watch.
        registerAudioMode();
        audioBusy = Utils.isUserInCommunicationCall(appContext);

        ensureTelephonyRegistered();

        // Report a call that was already in progress when the service started. The old code set
        // the latch directly, which suppressed the notification entirely: the first telephony
        // callback then saw the latch already true and never told the service. Going through
        // evaluate() means the listener is notified exactly once, here or on the first callback,
        // whichever observes the call first.
        evaluate("initial");
        logToLogcat(TAG, "Call watcher started, telephonySources=" + telephonySources.size()
                + " callActive=" + callActive.get());
    }

    /**
     * Registers the telephony listeners if they are not registered already and the permission is
     * available. Idempotent: safe to call from start() and again from a later permission grant.
     */
    public synchronized void ensureTelephonyRegistered() {
        if (appContext == null || !telephonySources.isEmpty()) {
            return;
        }
        if (!Utils.isReadPhoneStatePermissionGranted(appContext)) {
            logToLogcat(TAG, "READ_PHONE_STATE not granted yet, telephony watching deferred");
            return;
        }
        registerTelephony();
        if (!telephonySources.isEmpty()) {
            logToLogcat(TAG, "Telephony watching active on " + telephonySources.size() + " source(s)");
        }
    }

    public void stop() {
        listener = null;
        unregisterTelephony();
        unregisterAudioMode();
        logToLogcat(TAG, "Call watcher stopped");
    }

    /** Live check, used as the screen-on safety fallback. */
    public boolean isCallActive() {
        if (appContext == null) {
            return false;
        }
        return callActive.get()
                || Utils.isUserInCall(appContext)
                || Utils.isUserInCommunicationCall(appContext);
    }

    // ------------------------------------------------------------------------------ telephony

    private void registerTelephony() {
        TelephonyManager base = (TelephonyManager) appContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (base == null) {
            return;
        }
        for (TelephonyManager manager : getManagersToWatch(base)) {
            TelephonySource source = new TelephonySource(manager);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ModernCallStateCallback callback = new ModernCallStateCallback(source);
                    manager.registerTelephonyCallback(appContext.getMainExecutor(), callback);
                    source.callback = callback;
                } else {
                    // Registered per subscription too on API 24-30, so multi-SIM behaves the same
                    // way there. On API 23 getManagersToWatch() yields the default manager only,
                    // because createForSubscriptionId does not exist - a documented limitation of
                    // that one API level.
                    PhoneStateListener legacy = new PhoneStateListener() {
                        @Override
                        public void onCallStateChanged(int state, String phoneNumber) {
                            // phoneNumber is deliberately ignored and never logged.
                            onTelephonyState(source, state);
                        }
                    };
                    manager.listen(legacy, PhoneStateListener.LISTEN_CALL_STATE);
                    source.legacyListener = legacy;
                }
                telephonySources.add(source);
            } catch (Exception e) {
                // Most commonly a SecurityException because READ_PHONE_STATE was revoked between
                // the check above and here.
                Log.w(TAG, "Could not register telephony listener: " + e.getMessage());
            }
        }
    }

    /** The default manager, plus one per active subscription, de-duplicated by subscription id. */
    private List<TelephonyManager> getManagersToWatch(TelephonyManager base) {
        List<TelephonyManager> managers = new ArrayList<>();
        managers.add(base);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return managers;
        }
        try {
            SubscriptionManager subscriptionManager =
                    (SubscriptionManager) appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subscriptionManager == null) {
                return managers;
            }
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptions == null) {
                return managers;
            }
            Set<Integer> seen = new HashSet<>();
            // The base manager already covers the default subscription.
            seen.add(SubscriptionManager.getDefaultSubscriptionId());
            for (SubscriptionInfo info : subscriptions) {
                int subId = info.getSubscriptionId();
                if (seen.add(subId)) {
                    managers.add(base.createForSubscriptionId(subId));
                }
            }
        } catch (Exception e) {
            // Without READ_PHONE_STATE this throws; the default manager alone is still useful.
            Log.w(TAG, "Could not enumerate subscriptions: " + e.getMessage());
        }
        return managers;
    }

    private void unregisterTelephony() {
        for (TelephonySource source : telephonySources) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (source.callback instanceof TelephonyCallback) {
                        source.manager.unregisterTelephonyCallback((TelephonyCallback) source.callback);
                    }
                } else if (source.legacyListener != null) {
                    source.manager.listen(source.legacyListener, PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not unregister telephony listener: " + e.getMessage());
            }
        }
        telephonySources.clear();
    }

    private class ModernCallStateCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        private final TelephonySource source;

        ModernCallStateCallback(TelephonySource source) {
            this.source = source;
        }

        @Override
        public void onCallStateChanged(int state) {
            onTelephonyState(source, state);
        }
    }

    /** Updates only the reporting source, then recomputes the aggregate. */
    private void onTelephonyState(TelephonySource source, int state) {
        String name;
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                name = "RINGING";
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                name = "OFFHOOK";
                break;
            default:
                name = "IDLE";
                break;
        }
        boolean busy = state != TelephonyManager.CALL_STATE_IDLE;
        if (source.busy != busy) {
            source.busy = busy;
            // No subscription identifier is logged, only the state name.
            DiagnosticLogger.i("CALL", "state=" + name);
        }
        evaluate(name);
    }

    private boolean anyTelephonyBusy() {
        for (TelephonySource source : telephonySources) {
            if (source.busy) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------------------- audio mode

    private void registerAudioMode() {
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Below API 31 there is no mode-change callback. VoIP calls are then only noticed by
            // the caller's screen-on fallback, which is why that fallback is kept.
            return;
        }
        try {
            Executor executor = appContext.getMainExecutor();
            modeChangedListener = mode -> {
                boolean busy = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
                if (busy != audioBusy) {
                    audioBusy = busy;
                    DiagnosticLogger.i("CALL", "state=" + (busy ? "AUDIO_IN_COMMUNICATION" : "AUDIO_IDLE"));
                    evaluate(busy ? "AUDIO_IN_COMMUNICATION" : "AUDIO_IDLE");
                }
            };
            audioManager.addOnModeChangedListener(executor, modeChangedListener);
        } catch (Exception e) {
            Log.w(TAG, "Could not register audio mode listener: " + e.getMessage());
            modeChangedListener = null;
        }
    }

    private void unregisterAudioMode() {
        if (modeChangedListener != null && audioManager != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.removeOnModeChangedListener(modeChangedListener);
            } catch (Exception e) {
                Log.w(TAG, "Could not unregister audio mode listener: " + e.getMessage());
            }
        }
        modeChangedListener = null;
        audioManager = null;
    }

    // ------------------------------------------------------------------------------- combining

    /**
     * Collapses every source into one latched transition. compareAndSet means a cellular call that
     * also raises the audio mode produces a single onCallActive, and the listener is only told the
     * call ended once every source is clear.
     */
    private void evaluate(String reason) {
        boolean busy = anyTelephonyBusy() || audioBusy;
        Listener current = listener;
        if (current == null) {
            callActive.set(busy);
            return;
        }
        if (busy) {
            if (callActive.compareAndSet(false, true)) {
                current.onCallActive(reason);
            }
        } else {
            if (callActive.compareAndSet(true, false)) {
                current.onCallEnded();
            }
        }
    }
}
