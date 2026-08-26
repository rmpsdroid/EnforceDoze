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
 * The two are combined into one latched "a call is happening" boolean, so a WhatsApp call and a
 * cellular call produce the same single transition and nothing double-fires.
 * <p>
 * No polling, and no new permissions: READ_PHONE_STATE is already declared and already used by
 * {@link Utils#isUserInCall(Context)}. If it has not been granted yet the telephony registration
 * fails harmlessly and the caller's screen-on fallback still covers the case.
 */
public class CallStateWatcher {

    public interface Listener {
        /** A call became active. {@code reason} is a state name only, never caller information. */
        void onCallActive(String reason);

        void onCallEnded();
    }

    private static final String TAG = "CallStateWatcher";

    private final AtomicBoolean callActive = new AtomicBoolean(false);
    private final List<Object> telephonyCallbacks = new ArrayList<>();
    private final List<TelephonyManager> callbackOwners = new ArrayList<>();

    private Context appContext;
    private Listener listener;
    private PhoneStateListener legacyListener;
    private TelephonyManager legacyManager;
    private AudioManager.OnModeChangedListener modeChangedListener;
    private AudioManager audioManager;
    /** Last known telephony state, kept so the audio mode cannot mask an ongoing cellular call. */
    private volatile boolean telephonyBusy = false;
    private volatile boolean audioBusy = false;

    public void start(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;

        // Seed from the current state: a call may already be in progress when the service starts.
        telephonyBusy = Utils.isUserInCall(appContext);
        audioBusy = Utils.isUserInCommunicationCall(appContext);
        callActive.set(telephonyBusy || audioBusy);

        registerTelephony();
        registerAudioMode();
        logToLogcat(TAG, "Call watcher started, callActiveAtStart=" + callActive.get());
    }

    public void stop() {
        unregisterTelephony();
        unregisterAudioMode();
        listener = null;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Register on the default manager plus every active subscription. A dual-SIM device
            // reports call state per subscription, and a call on the non-default SIM would
            // otherwise go unnoticed.
            for (TelephonyManager manager : getManagersToWatch(base)) {
                try {
                    TelephonyCallback callback = new ModernCallStateCallback();
                    manager.registerTelephonyCallback(appContext.getMainExecutor(), callback);
                    telephonyCallbacks.add(callback);
                    callbackOwners.add(manager);
                } catch (Exception e) {
                    // Most likely READ_PHONE_STATE has not been granted yet.
                    Log.w(TAG, "Could not register telephony callback: " + e.getMessage());
                }
            }
        } else {
            try {
                legacyManager = base;
                legacyListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        // phoneNumber is deliberately ignored and never logged.
                        onTelephonyState(state);
                    }
                };
                legacyManager.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE);
            } catch (Exception e) {
                Log.w(TAG, "Could not register legacy phone state listener: " + e.getMessage());
                legacyListener = null;
            }
        }
    }

    /** The default manager, plus one per active subscription, de-duplicated by subscription id. */
    private List<TelephonyManager> getManagersToWatch(TelephonyManager base) {
        List<TelephonyManager> managers = new ArrayList<>();
        managers.add(base);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (int i = 0; i < telephonyCallbacks.size(); i++) {
                try {
                    callbackOwners.get(i).unregisterTelephonyCallback(
                            (TelephonyCallback) telephonyCallbacks.get(i));
                } catch (Exception e) {
                    Log.w(TAG, "Could not unregister telephony callback: " + e.getMessage());
                }
            }
            telephonyCallbacks.clear();
            callbackOwners.clear();
        } else if (legacyListener != null && legacyManager != null) {
            try {
                legacyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception e) {
                Log.w(TAG, "Could not unregister legacy listener: " + e.getMessage());
            }
            legacyListener = null;
            legacyManager = null;
        }
    }

    private class ModernCallStateCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            onTelephonyState(state);
        }
    }

    private void onTelephonyState(int state) {
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
        DiagnosticLogger.i("CALL", "state=" + name);
        telephonyBusy = state != TelephonyManager.CALL_STATE_IDLE;
        evaluate(name);
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
     * Collapses both sources into one latched transition. compareAndSet means a cellular call that
     * also raises the audio mode produces a single onCallActive, and the listener is only told the
     * call ended once both sources are clear.
     */
    private void evaluate(String reason) {
        boolean busy = telephonyBusy || audioBusy;
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
