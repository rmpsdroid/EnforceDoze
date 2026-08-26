package com.akylas.enforcedoze;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App-private rotating diagnostic log, so an overnight run can be exported and audited without ADB.
 * <p>
 * Everything a caller does is: capture two clock values, build a small record object, and post it.
 * All formatting, file access and rotation happen on one dedicated {@link HandlerThread}. Nothing
 * on the wake path ever touches the disk, waits on a lock, or blocks on a queue - {@code post()} on
 * an unbounded MessageQueue does not block, so a logging call adds a few microseconds at most and
 * can never delay a package un-suspend or a Doze reversion.
 * <p>
 * Failures are swallowed by design. Diagnostics must never be able to crash EnforceDoze, so an I/O
 * problem disables file logging for the rest of the process rather than propagating.
 */
public final class DiagnosticLogger {

    /** Preference key for the user-facing switch. Defaults to on for this testing fork. */
    public static final String PREF_ENABLED = "diagnosticLoggingEnabled";
    public static final boolean DEFAULT_ENABLED = true;

    private static final String TAG = "DiagnosticLogger";
    private static final String DIR_NAME = "diagnostics";
    private static final String CURRENT_NAME = "enforcedoze-diagnostic.log";
    private static final String ROTATED_1 = "enforcedoze-diagnostic.1.log";
    private static final String ROTATED_2 = "enforcedoze-diagnostic.2.log";
    /** Per-file cap; three files means roughly 3 MB of app-private storage in total. */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    private static final Object INIT_LOCK = new Object();
    private static volatile DiagnosticLogger instance;

    private final Context appContext;
    private final Handler handler;
    /** Read on every log call, so it is cached rather than fetched from preferences each time. */
    private volatile boolean enabled;
    /** Set after an I/O failure so a broken filesystem does not produce an error per event. */
    private volatile boolean brokenForThisProcess = false;

    // --- owned by the logger thread only ---------------------------------------------------
    private BufferedWriter writer;
    private long currentBytes;
    private final SimpleDateFormat timestampFormat =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);

    private DiagnosticLogger(Context context) {
        this.appContext = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("EnforceDozeDiagnostics");
        thread.start();
        this.handler = new Handler(thread.getLooper());
        this.enabled = PreferenceManager.getDefaultSharedPreferences(appContext)
                .getBoolean(PREF_ENABLED, DEFAULT_ENABLED);
    }

    /**
     * Safe to call repeatedly and from any process entry point. Writes the session header on the
     * logger thread.
     */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        if (instance == null) {
            synchronized (INIT_LOCK) {
                if (instance == null) {
                    instance = new DiagnosticLogger(context);
                    instance.postSessionHeader();
                }
            }
        }
    }

    private static DiagnosticLogger get() {
        return instance;
    }

    public static void i(String tag, String message) {
        log("I", tag, message);
    }

    public static void w(String tag, String message) {
        log("W", tag, message);
    }

    public static void e(String tag, String message) {
        log("E", tag, message);
    }

    private static void log(String level, String tag, String message) {
        DiagnosticLogger logger = get();
        if (logger == null || !logger.enabled || logger.brokenForThisProcess) {
            return;
        }
        // Timestamps are captured here so the record reflects when the event happened rather than
        // when the logger thread got round to writing it. Formatting happens on that thread.
        final long wallClock = System.currentTimeMillis();
        final long elapsed = SystemClock.elapsedRealtime();
        try {
            logger.handler.post(() -> logger.writeRecord(wallClock, elapsed, level, tag, message));
        } catch (Throwable t) {
            // A rejected post must not take the caller down with it.
            Log.w(TAG, "Could not enqueue diagnostic record: " + t.getMessage());
        }
    }

    /** Applied immediately; the switch in Settings calls this so the change does not need a restart. */
    public static void setEnabled(Context context, boolean value) {
        init(context);
        DiagnosticLogger logger = get();
        if (logger == null) {
            return;
        }
        logger.enabled = value;
        if (value) {
            logger.postSessionHeader();
        }
    }

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLED, DEFAULT_ENABLED);
    }

    // -------------------------------------------------------------------- logger thread work

    private void postSessionHeader() {
        handler.post(this::writeSessionHeader);
    }

    private void writeSessionHeader() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
            String executionMode = prefs.getString("executionMode", "root");
            StringBuilder header = new StringBuilder();
            header.append("\n==============================\n");
            header.append("ENFORCEDOZE DIAGNOSTIC SESSION\n");
            header.append("timestamp=").append(timestampFormat.format(new Date())).append('\n');
            header.append("appVersion=").append(BuildConfig.VERSION_NAME).append('\n');
            header.append("versionCode=").append(BuildConfig.VERSION_CODE).append('\n');
            header.append("applicationId=").append(BuildConfig.APPLICATION_ID).append('\n');
            header.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');
            // Model and manufacturer only: no serial, no Android ID, nothing device-unique.
            header.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
            header.append("executionMode=").append(executionMode).append('\n');
            header.append("==============================\n");
            writeRaw(header.toString());
        } catch (Throwable t) {
            markBroken("session header", t);
        }
    }

    private void writeRecord(long wallClock, long elapsed, String level, String tag, String message) {
        try {
            String line = timestampFormat.format(new Date(wallClock))
                    + " | elapsed=" + elapsed
                    + " | " + level
                    + " | " + tag
                    + " | " + message
                    + "\n";
            writeRaw(line);
        } catch (Throwable t) {
            markBroken("write", t);
        }
    }

    /** Logger thread only. */
    private void writeRaw(String text) throws IOException {
        if (brokenForThisProcess) {
            return;
        }
        if (writer == null) {
            openWriter();
        }
        writer.write(text);
        // flush(), never sync(): this hands the bytes to the OS so they survive a process kill,
        // which is the failure mode that matters here, without paying for a disk barrier.
        writer.flush();
        currentBytes += text.length();
        if (currentBytes >= MAX_FILE_BYTES) {
            rotate();
        }
    }

    /** Logger thread only. */
    private void openWriter() throws IOException {
        File dir = getDirectory();
        File current = new File(dir, CURRENT_NAME);
        currentBytes = current.exists() ? current.length() : 0L;
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(current, true), Charset.forName("UTF-8")));
    }

    /** Logger thread only. current -> .1 -> .2, oldest discarded. */
    private void rotate() {
        try {
            closeWriterQuietly();
            File dir = getDirectory();
            File current = new File(dir, CURRENT_NAME);
            File first = new File(dir, ROTATED_1);
            File second = new File(dir, ROTATED_2);

            if (second.exists() && !second.delete()) {
                Log.w(TAG, "Could not delete the oldest diagnostic file");
            }
            if (first.exists() && !first.renameTo(second)) {
                Log.w(TAG, "Could not rotate the diagnostic file to .2");
            }
            if (current.exists() && !current.renameTo(first)) {
                Log.w(TAG, "Could not rotate the current diagnostic file");
            }
            currentBytes = 0L;
            openWriter();
        } catch (Throwable t) {
            markBroken("rotate", t);
        }
    }

    private File getDirectory() throws IOException {
        File dir = new File(appContext.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir.getAbsolutePath());
        }
        return dir;
    }

    private void closeWriterQuietly() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
            writer = null;
        }
    }

    private void markBroken(String stage, Throwable t) {
        brokenForThisProcess = true;
        closeWriterQuietly();
        Log.e(TAG, "Diagnostic logging disabled for this process after a failure in " + stage
                + ": " + t.getMessage());
    }

    // ------------------------------------------------------------------------- export / clear

    public interface OperationCallback {
        void onFinished(boolean success, String detail);
    }

    /** Suggested name for the exported file, e.g. EnforceDoze-diagnostic-20260827-081500.txt */
    public static String suggestedExportName() {
        return "EnforceDoze-diagnostic-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
    }

    /**
     * Concatenates the rotated files oldest-first into {@code uri}, producing one chronological
     * text file. Runs on the logger thread, which serialises it against active writes without any
     * locking on the caller.
     */
    public static void exportAsync(Context context, Uri uri, OperationCallback callback) {
        init(context);
        DiagnosticLogger logger = get();
        if (logger == null) {
            callback.onFinished(false, "logger unavailable");
            return;
        }
        logger.handler.post(() -> {
            long written = 0L;
            try {
                // Flush what is buffered so the export includes everything logged so far.
                if (logger.writer != null) {
                    logger.writer.flush();
                }
                File dir = logger.getDirectory();
                OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
                if (out == null) {
                    callback.onFinished(false, "could not open the selected file");
                    return;
                }
                try {
                    // Oldest first so the result reads chronologically top to bottom.
                    for (String name : new String[]{ROTATED_2, ROTATED_1, CURRENT_NAME}) {
                        written += copyInto(new File(dir, name), out);
                    }
                    out.flush();
                } finally {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
                callback.onFinished(true, String.valueOf(written));
            } catch (Throwable t) {
                Log.e(TAG, "Diagnostic export failed: " + t.getMessage());
                callback.onFinished(false, String.valueOf(t.getMessage()));
            }
        });
    }

    private static long copyInto(File source, OutputStream out) throws IOException {
        if (!source.exists()) {
            return 0L;
        }
        long total = 0L;
        try (InputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    /**
     * Deletes every diagnostic file. Posted to the logger thread, so it cannot race a write in
     * progress; a new file and session header are started immediately afterwards.
     */
    public static void clearAsync(Context context, OperationCallback callback) {
        init(context);
        DiagnosticLogger logger = get();
        if (logger == null) {
            if (callback != null) {
                callback.onFinished(false, "logger unavailable");
            }
            return;
        }
        logger.handler.post(() -> {
            try {
                logger.closeWriterQuietly();
                File dir = logger.getDirectory();
                for (String name : new String[]{CURRENT_NAME, ROTATED_1, ROTATED_2}) {
                    File file = new File(dir, name);
                    if (file.exists() && !file.delete()) {
                        Log.w(TAG, "Could not delete " + name);
                    }
                }
                logger.currentBytes = 0L;
                // A cleared log with no header would be confusing to read later.
                logger.writeSessionHeader();
                if (callback != null) {
                    callback.onFinished(true, null);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Diagnostic clear failed: " + t.getMessage());
                if (callback != null) {
                    callback.onFinished(false, String.valueOf(t.getMessage()));
                }
            }
        });
    }

    /** Total bytes currently held on disk, for the Settings summary. Cheap: metadata only. */
    public static long getTotalSizeBytes(Context context) {
        try {
            File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
            long total = 0L;
            for (String name : new String[]{CURRENT_NAME, ROTATED_1, ROTATED_2}) {
                File file = new File(dir, name);
                if (file.exists()) {
                    total += file.length();
                }
            }
            return total;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
