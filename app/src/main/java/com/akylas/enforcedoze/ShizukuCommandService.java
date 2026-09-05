package com.akylas.enforcedoze;

import android.content.Context;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shizuku UserService that executes the shell commands requested by the app process.
 *
 * <p>Each command runs in its own process group. Destroying a Java Process only terminates that
 * one shell process; descendants can otherwise be re-parented and survive the app/UserService
 * process. The service records the process-group id supplied by a setsid wrapper and kills the
 * complete group when Shizuku destroys the non-daemon UserService.
 *
 * <p>stdout and stderr are drained concurrently so neither pipe can fill while the other stream is
 * being consumed.
 */
@Keep
public final class ShizukuCommandService extends IShizukuCommandService.Stub {

    private static final String TAG = "ShizukuCmdService";

    static final String RESULT_EXIT_CODE = "exitCode";
    static final String RESULT_STDOUT = "stdout";
    static final String RESULT_STDERR = "stderr";

    private static final String SYSTEM_SHELL = "/system/bin/sh";
    private static final String SYSTEM_SETSID = "/system/bin/setsid";
    private static final String SYSTEM_TOYBOX = "/system/bin/toybox";
    private static final String GROUP_PID_DIR = "/data/local/tmp";
    private static final long GROUP_PID_WAIT_TIMEOUT_MS = 1_000L;
    private static final long GROUP_PID_POLL_INTERVAL_MS = 10L;

    private static final Object PROCESS_LOCK = new Object();
    private static final Set<TrackedProcess> ACTIVE_PROCESSES =
            Collections.newSetFromMap(new ConcurrentHashMap<TrackedProcess, Boolean>());
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean(false);
    private static boolean shuttingDown = false;

    private static final class TrackedProcess {
        final Process process;
        final String groupPidFile;
        volatile int processGroupId;

        TrackedProcess(Process process, String groupPidFile) {
            this.process = process;
            this.groupPidFile = groupPidFile;
        }
    }

    @Keep
    public ShizukuCommandService() {
        installShutdownHook();
    }

    @Keep
    public ShizukuCommandService(Context context) {
        installShutdownHook();
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(
                            () -> cleanupActiveProcesses("jvm_shutdown"),
                            "EnforceDoze-ShizukuShutdown"));
        } catch (Throwable t) {
            Log.w(TAG, "Could not install shutdown hook", t);
        }
    }

    @Override
    public Bundle execute(String command) {
        ArrayList<String> stdout = new ArrayList<>();
        ArrayList<String> stderr = new ArrayList<>();
        int exitCode = -1;

        TrackedProcess trackedProcess = null;
        Process process = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        AtomicBoolean drainFailed = new AtomicBoolean(false);

        try {
            trackedProcess = startTrackedProcess(command);
            process = trackedProcess.process;

            final Process runningProcess = process;
            stdoutThread = startDrainThread(
                    runningProcess.getInputStream(),
                    stdout,
                    drainFailed,
                    "EnforceDoze-ShizukuStdout");
            stderrThread = startDrainThread(
                    runningProcess.getErrorStream(),
                    stderr,
                    drainFailed,
                    "EnforceDoze-ShizukuStderr");

            exitCode = runningProcess.waitFor();

            joinDrainThread(stdoutThread, drainFailed);
            joinDrainThread(stderrThread, drainFailed);

            if (drainFailed.get() && exitCode == 0) {
                exitCode = -1;
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "Command execution interrupted");
            if (trackedProcess != null) {
                terminateTrackedProcess(trackedProcess, "command_interrupted");
            } else if (process != null) {
                process.destroy();
            }
            drainFailed.set(true);
            exitCode = -1;
        } catch (Throwable t) {
            Log.e(TAG, "Command execution failed", t);
            if (trackedProcess != null) {
                terminateTrackedProcess(trackedProcess, "command_failure");
            } else if (process != null) {
                process.destroy();
            }
            exitCode = -1;
        } finally {
            if (trackedProcess != null) {
                finishTrackedProcess(trackedProcess);
            }
        }

        Bundle result = new Bundle();
        result.putInt(RESULT_EXIT_CODE, exitCode);
        result.putStringArrayList(RESULT_STDOUT, stdout);
        result.putStringArrayList(RESULT_STDERR, stderr);
        return result;
    }

    private static TrackedProcess startTrackedProcess(String command) throws IOException {
        final String token = UUID.randomUUID().toString();
        final String groupPidFile = GROUP_PID_DIR + "/enforcedoze_shizuku_pg_" + token;

        final String launcherScript =
                "echo $$ > \"$1\"; "
                        + "trap 'trap - TERM HUP INT; kill -KILL -$$ 2>/dev/null' TERM HUP INT; "
                        + "eval \"$2\"";

        final ProcessBuilder builder;
        if (new File(SYSTEM_SETSID).canExecute()) {
            builder = new ProcessBuilder(
                    SYSTEM_SETSID,
                    SYSTEM_SHELL,
                    "-c",
                    launcherScript,
                    "enforcedoze",
                    groupPidFile,
                    command == null ? "" : command);
        } else if (new File(SYSTEM_TOYBOX).canExecute()) {
            builder = new ProcessBuilder(
                    SYSTEM_TOYBOX,
                    "setsid",
                    SYSTEM_SHELL,
                    "-c",
                    launcherScript,
                    "enforcedoze",
                    groupPidFile,
                    command == null ? "" : command);
        } else {
            throw new IOException("No setsid-capable launcher is available");
        }

        final TrackedProcess tracked;
        synchronized (PROCESS_LOCK) {
            if (shuttingDown) {
                throw new IOException("UserService is shutting down");
            }

            Process process = builder.start();
            tracked = new TrackedProcess(process, groupPidFile);
            ACTIVE_PROCESSES.add(tracked);
        }

        try {
            tracked.processGroupId = awaitProcessGroupId(groupPidFile);
            if (tracked.processGroupId <= 1) {
                throw new IOException("Invalid command process-group id: " + tracked.processGroupId);
            }
            return tracked;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminateTrackedProcess(tracked, "group_id_wait_interrupted");
            synchronized (PROCESS_LOCK) {
                ACTIVE_PROCESSES.remove(tracked);
            }
            throw new IOException("Interrupted while reading command process-group id", e);
        } catch (IOException e) {
            terminateTrackedProcess(tracked, "group_id_unavailable");
            synchronized (PROCESS_LOCK) {
                ACTIVE_PROCESSES.remove(tracked);
            }
            throw e;
        }
    }

    private static int awaitProcessGroupId(String path)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + GROUP_PID_WAIT_TIMEOUT_MS;
        File file = new File(path);

        while (System.currentTimeMillis() < deadline) {
            if (file.isFile() && file.length() > 0L) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line = reader.readLine();
                    if (line != null) {
                        try {
                            int processGroupId = Integer.parseInt(line.trim());
                            if (processGroupId > 1) {
                                deleteQuietly(file);
                                return processGroupId;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            Thread.sleep(GROUP_PID_POLL_INTERVAL_MS);
        }

        deleteQuietly(file);
        throw new IOException("Timed out waiting for command process-group id");
    }

    private static Thread startDrainThread(
            InputStream stream,
            List<String> target,
            AtomicBoolean drainFailed,
            String name) {

        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.add(line);
                }
            } catch (IOException e) {
                drainFailed.set(true);
                Log.w(TAG, name + " drain failed: " + e.getMessage());
            }
        }, name);

        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinDrainThread(Thread thread, AtomicBoolean drainFailed)
            throws InterruptedException {
        if (thread == null) {
            return;
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            drainFailed.set(true);
            thread.interrupt();
            throw e;
        }
    }

    private static void finishTrackedProcess(TrackedProcess tracked) {
        synchronized (PROCESS_LOCK) {
            ACTIVE_PROCESSES.remove(tracked);
        }
        deleteQuietly(new File(tracked.groupPidFile));
        try {
            tracked.process.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static void terminateTrackedProcess(TrackedProcess tracked, String reason) {
        int processGroupId = tracked.processGroupId;

        if (processGroupId > 1) {
            try {
                Os.kill(-processGroupId, OsConstants.SIGKILL);
                Log.i(TAG, "Killed command process group pgid=" + processGroupId
                        + " reason=" + reason);
            } catch (Throwable t) {
                Log.w(TAG, "Could not kill command process group pgid=" + processGroupId
                        + " reason=" + reason + ": " + t.getMessage());
            }
        }

        try {
            tracked.process.destroy();
        } catch (Throwable ignored) {
        }

        deleteQuietly(new File(tracked.groupPidFile));
    }

    private static void cleanupActiveProcesses(String reason) {
        List<TrackedProcess> snapshot;
        synchronized (PROCESS_LOCK) {
            shuttingDown = true;
            snapshot = new ArrayList<>(ACTIVE_PROCESSES);
        }

        Log.i(TAG, "Cleaning up " + snapshot.size()
                + " active command process group(s), reason=" + reason);

        for (TrackedProcess tracked : snapshot) {
            terminateTrackedProcess(tracked, reason);
        }

        synchronized (PROCESS_LOCK) {
            ACTIVE_PROCESSES.clear();
        }
    }

    private static void deleteQuietly(File file) {
        try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete temporary process-group file " + file.getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void destroy() {
        cleanupActiveProcesses("destroy");
        System.exit(0);
    }
}
