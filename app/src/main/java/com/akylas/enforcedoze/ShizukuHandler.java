package com.akylas.enforcedoze;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.DeadObjectException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ShizukuHandler {
    private static final String TAG = "ShizukuHandler";

    /**
     * How long a command waits for the Shizuku binder before giving up. After a process death the
     * binder is delivered asynchronously by ShizukuProvider, so a command issued immediately on
     * service creation would otherwise be dropped with "Shizuku is not available".
     */
    private static final long BINDER_WAIT_TIMEOUT_MS = 2_000;
    private static final long BINDER_POLL_INTERVAL_MS = 100;
    /** Retained for source compatibility with the four-argument executeCommand overload. */
    private static final int DEFAULT_MAX_ATTEMPTS = 1;
    /**
     * Reported when Shizuku actively refused the command (authorisation revoked). Distinct from -1
     * "never ran", so the retry loop stops instead of hammering a permission we no longer hold.
     */
    public static final int REFUSED_EXIT_CODE = -2;

    private static ShizukuHandler instance;
    private Context context;
    private volatile boolean isShizukuAvailable = false;
    private final CopyOnWriteArrayList<OnAvailibilityChange> availabilityListeners = new CopyOnWriteArrayList<>();

    interface OnAvailibilityChange {
        void onChange(Boolean value);
    }

    private final Shizuku.OnRequestPermissionResultListener REQUEST_PERMISSION_RESULT_LISTENER =
            (requestCode, grantResult) -> {
                boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                Log.i(TAG, "Shizuku permission result: " + granted);
                setAvailable(granted);
            };

    /**
     * Shizuku's binder arrives asynchronously and can die and come back (the user restarts the
     * Shizuku service, ADB reconnects, ...). Without these listeners the cached availability flag
     * stayed false forever after the first miss and every shell command silently no-opped.
     */
    private final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = () -> {
        Log.i(TAG, "Shizuku binder received");
        checkShizukuAvailability();
        notifyAvailabilityListeners();
    };

    private final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = () -> {
        Log.w(TAG, "Shizuku binder died");
        setAvailable(false);
    };

    private ShizukuHandler(Context context) {
        this.context = context.getApplicationContext();
        try {
            Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER);
            Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to register Shizuku binder listeners: " + t.getMessage());
        }
        checkShizukuAvailability();
    }

    public static synchronized ShizukuHandler getInstance(Context context) {
        if (instance == null) {
            instance = new ShizukuHandler(context);
        }
        return instance;
    }

    /**
     * @deprecated kept for existing call sites; prefer
     * {@link #addOnAvailabilityChangeListener(OnAvailibilityChange)} so that several components
     * (service + UI) can observe Shizuku at the same time. The old setter replaced the single
     * listener field, which meant opening Settings silently stopped ForceDozeService from ever
     * learning that Shizuku had become available again.
     */
    @Deprecated
    public void setOnAvailibilityChangeListener(OnAvailibilityChange listener) {
        addOnAvailabilityChangeListener(listener);
    }

    public void addOnAvailabilityChangeListener(OnAvailibilityChange listener) {
        if (listener != null && !availabilityListeners.contains(listener)) {
            availabilityListeners.add(listener);
        }
    }

    public void removeOnAvailabilityChangeListener(OnAvailibilityChange listener) {
        availabilityListeners.remove(listener);
    }

    private void setAvailable(boolean available) {
        if (isShizukuAvailable != available) {
            isShizukuAvailable = available;
            notifyAvailabilityListeners();
        }
    }

    private void notifyAvailabilityListeners() {
        for (OnAvailibilityChange listener : availabilityListeners) {
            try {
                listener.onChange(isShizukuAvailable);
            } catch (Exception e) {
                Log.e(TAG, "Availability listener failed: " + e.getMessage());
            }
        }
    }

    public void checkShizukuAvailability() {
        boolean available = false;
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.isPreV11()) {
                    Log.w(TAG, "Shizuku pre-v11 is not supported");
                } else {
                    available = checkShizukuPermission() == PackageManager.PERMISSION_GRANTED;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking Shizuku availability: " + e.getMessage());
        }
        isShizukuAvailable = available;
    }

    public boolean isShizukuAvailable() {
        return isShizukuAvailable;
    }

    public int checkShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                return PackageManager.PERMISSION_DENIED;
            }
            return Shizuku.checkSelfPermission();
        } catch (Exception e) {
            // checkSelfPermission() throws when the binder has not been received yet
            Log.w(TAG, "Unable to read Shizuku permission: " + e.getMessage());
            return PackageManager.PERMISSION_DENIED;
        }
    }

    public void requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku pre-v11 does not support runtime permission");
                return;
            }

            if (checkShizukuPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);
                Shizuku.requestPermission(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to request Shizuku permission: " + e.getMessage());
        }
    }

    public void removePermissionResultListener() {
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);
    }

    /**
     * Returns immediately when Shizuku is connected, which is the normal case and therefore costs
     * a wake-up nothing. Only when the binder is genuinely missing does it wait briefly - the
     * binder arrives asynchronously after a process start, and without this a command issued in
     * that window is dropped outright. Each command has its own thread, so this never holds
     * another command up.
     */
    private boolean awaitShizukuReady() {
        if (isShizukuAvailable) {
            return true;
        }
        long deadline = System.currentTimeMillis() + BINDER_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            checkShizukuAvailability();
            if (isShizukuAvailable) {
                notifyAvailabilityListeners();
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(BINDER_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isShizukuAvailable;
    }

    /**
     * Execute a shell command using Shizuku
     *
     * @param command  The command to execute
     * @param callback Callback to receive the output
     */
    public void executeCommand(@NonNull String command, @NonNull OnCommandResultListener callback) {
        executeCommand(command, callback, false);
    }

    /**
     * Execute a shell command using Shizuku
     *
     * @param command     The command to execute
     * @param callback    Callback to receive the output
     * @param printOutput Whether to print the output to logs
     */
    public void executeCommand(@NonNull String command, @Nullable OnCommandResultListener callback, boolean printOutput) {
        executeCommand(command, callback, printOutput, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Execute a shell command using Shizuku on its own thread.
     *
     * @param maxAttempts ignored; kept so existing call sites still compile. Commands are issued
     *                    once and never retried, so nothing delays the caller.
     */
    public void executeCommand(@NonNull String command, @Nullable OnCommandResultListener callback,
                               boolean printOutput, int maxAttempts) {
        // One thread per command, as upstream did. Commands issued together - which is what a
        // wake-up does - therefore run at the same time instead of queueing behind each other.
        new Thread(() -> {
            CommandResult result;
            if (!awaitShizukuReady()) {
                Log.e(TAG, "Shizuku is not available, cannot run: " + command);
                result = new CommandResult(-1, new ArrayList<>(), new ArrayList<>());
            } else {
                result = runCommandOnce(command, printOutput);
            }

            if (callback != null) {
                callback.onCommandResult(0, result.exitCode, result.stdout, result.stderr);
            }
        }, "ShizukuCommand").start();
    }

    Method shizukuNewProcessMethod = null;

    private CommandResult runCommandOnce(String command, boolean printOutput) {
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        int exitCode = -1;

        try {
            if (shizukuNewProcessMethod == null) {
                Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
                shizukuNewProcessMethod = clazz.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                shizukuNewProcessMethod.setAccessible(true);
            }
            String[] cmd = new String[]{"sh", "-c", command};
            Object[] invokeArgs = new Object[]{cmd, null, null};

            ShizukuRemoteProcess process = (ShizukuRemoteProcess) shizukuNewProcessMethod.invoke(null, invokeArgs);

            // Read stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.add(line);
                    if (printOutput) {
                        Log.i(TAG, line);
                    }
                }
            }

            // Read stderr
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.add(line);
                    if (printOutput) {
                        Log.e(TAG, line);
                    }
                }
            }

            exitCode = process.waitFor();
            process.destroy();
        } catch (Exception e) {
            Throwable cause = unwrap(e);
            if (cause instanceof SecurityException) {
                // The user revoked EnforceDoze's Shizuku authorisation. Retrying cannot help and
                // would just burn the retry budget, so report it as permanently refused.
                Log.e(TAG, "Shizuku refused the command, permission was revoked: " + cause.getMessage());
                setAvailable(false);
                return new CommandResult(REFUSED_EXIT_CODE, stdout, stderr);
            }
            if (cause instanceof DeadObjectException) {
                // Shizuku's server went away (restarted, or stopped by the user). Mark it gone so
                // the next attempt waits for the binder to come back instead of failing instantly.
                Log.w(TAG, "Shizuku binder is dead, will wait for it to return");
            } else {
                Log.e(TAG, "Error executing command: " + e.getMessage());
                e.printStackTrace();
            }
            checkShizukuAvailability();
            return new CommandResult(-1, stdout, stderr);
        }

        return new CommandResult(exitCode, stdout, stderr);
    }

    /** Reflection reports the real failure as the cause of an InvocationTargetException. */
    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static class CommandResult {
        final int exitCode;
        final List<String> stdout;
        final List<String> stderr;

        CommandResult(int exitCode, List<String> stdout, List<String> stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }


    }

    /**
     * Callback interface for command execution results
     */
    public interface OnCommandResultListener {
        void onCommandResult(int commandCode, int exitCode, List<String> stdout, List<String> stderr);
    }
}
