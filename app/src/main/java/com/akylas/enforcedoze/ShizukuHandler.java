package com.akylas.enforcedoze;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class ShizukuHandler {
    private static final String TAG = "ShizukuHandler";

    /**
     * How long a command waits for the Shizuku binder before giving up. After a process death the
     * binder is delivered asynchronously by ShizukuProvider, so a command issued immediately on
     * service creation would otherwise be dropped.
     */
    private static final long BINDER_WAIT_TIMEOUT_MS = 2_000;
    private static final long BINDER_POLL_INTERVAL_MS = 100;

    /**
     * UserService startup is a separate asynchronous step after the Shizuku binder is ready.
     * Commands run on their own worker threads, so waiting here never blocks the UI/service thread.
     */
    private static final long USER_SERVICE_WAIT_TIMEOUT_MS = 5_000;

    /**
     * daemon(false) was added in Shizuku v12. We require it because privileged command ownership
     * must end with this app process; silently falling back to a daemon service would regress the
     * process-death guarantees established for DeviceIdle operations.
     */
    private static final int MIN_SHIZUKU_USER_SERVICE_VERSION = 12;

    private static final String USER_SERVICE_TAG = "enforcedoze-shizuku-command";
    private static final String USER_SERVICE_PROCESS_SUFFIX = "shizuku_cmd";

    /** Retained for source compatibility with the four-argument executeCommand overload. */
    private static final int DEFAULT_MAX_ATTEMPTS = 1;

    /**
     * Reported when Shizuku actively refused the command (authorisation revoked). Distinct from -1
     * "never ran", so callers do not mistake a permission failure for a normal shell failure.
     */
    public static final int REFUSED_EXIT_CODE = -2;

    private static ShizukuHandler instance;

    private final Context context;
    private volatile boolean isShizukuAvailable = false;
    private final CopyOnWriteArrayList<OnAvailibilityChange> availabilityListeners =
            new CopyOnWriteArrayList<>();

    private final Object userServiceLock = new Object();
    private final Shizuku.UserServiceArgs userServiceArgs;
    @Nullable
    private IShizukuCommandService commandService;
    private boolean userServiceBinding = false;

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
        DiagnosticLogger.i("SHIZUKU", "binder_received");
        checkShizukuAvailability();
        notifyAvailabilityListeners();
    };

    private final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = () -> {
        Log.w(TAG, "Shizuku binder died");
        DiagnosticLogger.w("SHIZUKU", "binder_dead");
        clearUserServiceReference("shizuku_binder_dead");
        setAvailable(false);
    };

    private final ServiceConnection USER_SERVICE_CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder serviceBinder) {
            IShizukuCommandService service =
                    IShizukuCommandService.Stub.asInterface(serviceBinder);

            synchronized (userServiceLock) {
                if (!isShizukuAvailable
                        || service == null
                        || !service.asBinder().isBinderAlive()) {
                    commandService = null;
                    userServiceBinding = false;
                    userServiceLock.notifyAll();
                    return;
                }

                commandService = service;
                userServiceBinding = false;
                userServiceLock.notifyAll();
            }

            Log.i(TAG, "Shizuku UserService connected");
            DiagnosticLogger.i("SHIZUKU", "user_service_connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearUserServiceReference("user_service_disconnected");
            Log.w(TAG, "Shizuku UserService disconnected");
            DiagnosticLogger.w("SHIZUKU", "user_service_disconnected");
        }
    };

    private ShizukuHandler(Context context) {
        this.context = context.getApplicationContext();

        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context, ShizukuCommandService.class))
                .daemon(false)
                .tag(USER_SERVICE_TAG)
                .version(BuildConfig.VERSION_CODE)
                .processNameSuffix(USER_SERVICE_PROCESS_SUFFIX)
                .debuggable(BuildConfig.DEBUG);

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
     * (service + UI) can observe Shizuku at the same time.
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
        if (!available) {
            clearUserServiceReference("shizuku_unavailable");
        }

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
                } else if (Shizuku.getVersion() < MIN_SHIZUKU_USER_SERVICE_VERSION) {
                    Log.w(TAG, "Shizuku v" + Shizuku.getVersion()
                            + " is too old; UserService non-daemon mode requires v"
                            + MIN_SHIZUKU_USER_SERVICE_VERSION + "+");
                    DiagnosticLogger.w(
                            "SHIZUKU",
                            "unsupported_server_version version=" + Shizuku.getVersion()
                                    + " minimum=" + MIN_SHIZUKU_USER_SERVICE_VERSION);
                } else {
                    available =
                            checkShizukuPermission() == PackageManager.PERMISSION_GRANTED;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error checking Shizuku availability: " + e.getMessage());
        }

        isShizukuAvailable = available;
        if (!available) {
            clearUserServiceReference("availability_check_failed");
        }
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
            // checkSelfPermission() throws when the binder has not been received yet.
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
        try {
            Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to remove Shizuku permission listener: " + t.getMessage());
        }
    }

    /**
     * Returns immediately when Shizuku is connected, which is the normal case. Only when the
     * binder is genuinely missing does it wait briefly for ShizukuProvider's asynchronous delivery.
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
     * Starts or waits for the one non-daemon Shizuku UserService used by every command.
     * Multiple command threads can arrive together: exactly one starts the bind and all others wait
     * for the same ServiceConnection. Once connected, Binder can service those calls concurrently,
     * preserving the existing "one worker per command" behavior.
     */
    private boolean awaitCommandService() {
        if (!awaitShizukuReady()) {
            return false;
        }

        boolean startBind = false;

        synchronized (userServiceLock) {
            if (isCommandServiceAliveLocked()) {
                return true;
            }

            commandService = null;
            if (!userServiceBinding) {
                userServiceBinding = true;
                startBind = true;
            }
        }

        if (startBind) {
            try {
                DiagnosticLogger.i("SHIZUKU", "user_service_bind_start");
                Shizuku.bindUserService(userServiceArgs, USER_SERVICE_CONNECTION);
            } catch (SecurityException e) {
                synchronized (userServiceLock) {
                    userServiceBinding = false;
                    userServiceLock.notifyAll();
                }
                Log.e(TAG, "Shizuku refused UserService bind: " + e.getMessage());
                DiagnosticLogger.e("SHIZUKU", "user_service_bind_refused");
                setAvailable(false);
                return false;
            } catch (Throwable t) {
                synchronized (userServiceLock) {
                    userServiceBinding = false;
                    userServiceLock.notifyAll();
                }
                Log.e(TAG, "Could not bind Shizuku UserService: " + t.getMessage());
                DiagnosticLogger.e("SHIZUKU", "user_service_bind_failed");
                checkShizukuAvailability();
                return false;
            }
        }

        long deadline = System.currentTimeMillis() + USER_SERVICE_WAIT_TIMEOUT_MS;

        synchronized (userServiceLock) {
            while (!isCommandServiceAliveLocked()) {
                if (!userServiceBinding || !isShizukuAvailable) {
                    return false;
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    userServiceBinding = false;
                    DiagnosticLogger.e("SHIZUKU", "user_service_bind_timeout");
                    return false;
                }

                try {
                    userServiceLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            return true;
        }
    }

    private boolean isCommandServiceAliveLocked() {
        return commandService != null
                && commandService.asBinder() != null
                && commandService.asBinder().isBinderAlive();
    }

    private void clearUserServiceReference(String reason) {
        synchronized (userServiceLock) {
            commandService = null;
            userServiceBinding = false;
            userServiceLock.notifyAll();
        }
        DiagnosticLogger.i("SHIZUKU", "user_service_reference_cleared reason=" + reason);
    }

    /**
     * Execute a shell command using Shizuku.
     */
    public void executeCommand(
            @NonNull String command,
            @NonNull OnCommandResultListener callback) {
        executeCommand(command, callback, false);
    }

    public void executeCommand(
            @NonNull String command,
            @Nullable OnCommandResultListener callback,
            boolean printOutput) {
        executeCommand(command, callback, printOutput, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Execute a shell command using the Shizuku UserService on its own app-process worker thread.
     *
     * @param maxAttempts ignored; kept so existing call sites still compile. Commands are issued
     *                    once and never retried, so a state-changing shell command cannot run twice
     *                    merely because a binder transition happened around its completion.
     */
    public void executeCommand(
            @NonNull String command,
            @Nullable OnCommandResultListener callback,
            boolean printOutput,
            int maxAttempts) {

        new Thread(() -> {
            CommandResult result;

            if (!awaitCommandService()) {
                Log.e(TAG, "Shizuku UserService is not available; command was not run");
                DiagnosticLogger.e("SHIZUKU", "command_dropped reason=user_service_unavailable");
                result = new CommandResult(-1, new ArrayList<>(), new ArrayList<>());
            } else {
                result = runCommandOnce(command, printOutput);
            }

            if (callback != null) {
                callback.onCommandResult(
                        0,
                        result.exitCode,
                        result.stdout,
                        result.stderr);
            }
        }, "ShizukuCommand").start();
    }

    private CommandResult runCommandOnce(String command, boolean printOutput) {
        IShizukuCommandService service;

        synchronized (userServiceLock) {
            if (!isCommandServiceAliveLocked()) {
                return new CommandResult(-1, new ArrayList<>(), new ArrayList<>());
            }
            service = commandService;
        }

        try {
            Bundle resultBundle = service.execute(command);
            if (resultBundle == null) {
                DiagnosticLogger.e("SHIZUKU", "command_failed reason=null_result");
                return new CommandResult(-1, new ArrayList<>(), new ArrayList<>());
            }

            int exitCode =
                    resultBundle.getInt(ShizukuCommandService.RESULT_EXIT_CODE, -1);

            ArrayList<String> stdout =
                    resultBundle.getStringArrayList(ShizukuCommandService.RESULT_STDOUT);
            ArrayList<String> stderr =
                    resultBundle.getStringArrayList(ShizukuCommandService.RESULT_STDERR);

            if (stdout == null) {
                stdout = new ArrayList<>();
            } else {
                stdout = new ArrayList<>(stdout);
            }

            if (stderr == null) {
                stderr = new ArrayList<>();
            } else {
                stderr = new ArrayList<>(stderr);
            }

            if (printOutput) {
                for (String line : stdout) {
                    Log.i(TAG, line);
                }
                for (String line : stderr) {
                    Log.e(TAG, line);
                }
            }

            DiagnosticLogger.i(
                    "SHIZUKU",
                    "command_finished exit=" + exitCode
                            + " stdoutLines=" + stdout.size()
                            + " stderrLines=" + stderr.size());

            return new CommandResult(exitCode, stdout, stderr);

        } catch (SecurityException e) {
            Log.e(TAG, "Shizuku refused the command, permission was revoked: " + e.getMessage());
            DiagnosticLogger.e("SHIZUKU", "command_refused reason=permission_revoked");
            clearUserServiceReference("permission_revoked");
            setAvailable(false);
            return new CommandResult(
                    REFUSED_EXIT_CODE,
                    new ArrayList<>(),
                    new ArrayList<>());

        } catch (DeadObjectException e) {
            Log.w(TAG, "Shizuku UserService died while command was running");
            DiagnosticLogger.w("SHIZUKU", "command_failed reason=user_service_dead");
            clearUserServiceReference("command_dead_object");
            checkShizukuAvailability();
            return new CommandResult(-1, new ArrayList<>(), new ArrayList<>());

        } catch (RemoteException e) {
            Log.e(TAG, "Shizuku UserService remote error: " + e.getMessage());
            DiagnosticLogger.e("SHIZUKU", "command_failed reason=remote_exception");
            clearUserServiceReference("command_remote_exception");
            checkShizukuAvailability();
            return new CommandResult(-1, new ArrayList<>(), new ArrayList<>());

        } catch (Throwable t) {
            Log.e(TAG, "Error executing Shizuku command: " + t.getMessage(), t);
            DiagnosticLogger.e("SHIZUKU", "command_failed reason=unexpected");
            checkShizukuAvailability();
            return new CommandResult(-1, new ArrayList<>(), new ArrayList<>());
        }
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

    public interface OnCommandResultListener {
        void onCommandResult(
                int commandCode,
                int exitCode,
                List<String> stdout,
                List<String> stderr);
    }
}
