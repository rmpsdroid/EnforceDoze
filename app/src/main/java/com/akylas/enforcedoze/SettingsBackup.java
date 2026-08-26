package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads and writes the whole of EnforceDoze's default SharedPreferences as JSON, over a
 * Storage Access Framework {@link Uri} so no storage permission is involved.
 * <p>
 * Values are written with their type alongside them. SharedPreferences is type-strict - reading a
 * key back with the wrong getter throws ClassCastException - so an untyped {@code {"key": 30}}
 * could not tell an int delay from a long, and a restored file would crash the service on read.
 */
public final class SettingsBackup {

    public static final String MIME_TYPE = "application/json";

    private static final String TAG = "SettingsBackup";
    private static final String FORMAT = "enforcedoze-settings";
    private static final int FORMAT_VERSION = 1;

    private static final String KEY_FORMAT = "format";
    private static final String KEY_FORMAT_VERSION = "formatVersion";
    private static final String KEY_APP_VERSION_NAME = "appVersionName";
    private static final String KEY_APP_VERSION_CODE = "appVersionCode";
    private static final String KEY_EXPORTED_AT = "exportedAt";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";

    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_STRING_SET = "stringSet";

    /**
     * Keys describing <em>this</em> device rather than the user's choices. They are still exported
     * (a backup should be complete) but never written back, because restoring them onto another
     * phone is actively harmful: a stale {@code isSuAvailable=true} makes the service take root
     * code paths that silently fail on a Shizuku-only device.
     */
    private static final Set<String> DEVICE_STATE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "isSuAvailable"));

    private SettingsBackup() {
    }

    /** e.g. {@code enforcedoze-settings-2026-08-26-1430.json} */
    public static String suggestedFileName() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date());
        return "enforcedoze-settings-" + stamp + ".json";
    }

    /**
     * Writes every preference to {@code uri}.
     *
     * @return how many settings were written
     */
    public static int exportTo(Context context, Uri uri) throws IOException, JSONException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Map<String, ?> all = prefs.getAll();

        JSONObject settings = new JSONObject();
        int exported = 0;
        // Sorted so two exports of the same configuration produce comparable files.
        for (String key : new TreeSet<>(all.keySet())) {
            Object value = all.get(key);
            JSONObject entry = encode(value);
            if (entry == null) {
                logToLogcat(TAG, "Skipping '" + key + "', unsupported type "
                        + (value == null ? "null" : value.getClass().getName()));
                continue;
            }
            settings.put(key, entry);
            exported++;
        }

        JSONObject root = new JSONObject();
        root.put(KEY_FORMAT, FORMAT);
        root.put(KEY_FORMAT_VERSION, FORMAT_VERSION);
        root.put(KEY_APP_VERSION_NAME, BuildConfig.VERSION_NAME);
        root.put(KEY_APP_VERSION_CODE, BuildConfig.VERSION_CODE);
        root.put(KEY_EXPORTED_AT,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        root.put(KEY_SETTINGS, settings);

        OutputStream outputStream;
        try {
            // "wt" truncates, so overwriting a longer existing backup cannot leave trailing junk
            // behind. Not every DocumentsProvider implements it, hence the fallback.
            outputStream = context.getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            outputStream = context.getContentResolver().openOutputStream(uri, "w");
        }
        if (outputStream == null) {
            throw new IOException("Could not open the selected file for writing");
        }
        try (Writer writer = new OutputStreamWriter(outputStream, Charset.forName("UTF-8"))) {
            writer.write(root.toString(2));
        }

        logToLogcat(TAG, "Exported " + exported + " settings");
        return exported;
    }

    /**
     * Reads {@code uri} and commits every setting it contains. Keys absent from the file are left
     * alone rather than cleared, so a file from an older version cannot wipe newer preferences.
     *
     * @return how many settings were applied
     */
    public static int importFrom(Context context, Uri uri) throws IOException, JSONException {
        JSONObject root = new JSONObject(readAll(context, uri));
        if (!FORMAT.equals(root.optString(KEY_FORMAT))) {
            throw new JSONException(context.getString(R.string.settings_import_invalid_file));
        }
        if (root.optInt(KEY_FORMAT_VERSION, 0) > FORMAT_VERSION) {
            logToLogcat(TAG, "File was written by a newer version (format "
                    + root.optInt(KEY_FORMAT_VERSION, 0) + "), importing what we understand");
        }

        JSONObject settings = root.optJSONObject(KEY_SETTINGS);
        if (settings == null) {
            throw new JSONException(context.getString(R.string.settings_import_invalid_file));
        }

        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        int imported = 0;
        for (java.util.Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            if (DEVICE_STATE_KEYS.contains(key)) {
                logToLogcat(TAG, "Not importing device-specific key '" + key + "'");
                continue;
            }
            JSONObject entry = settings.optJSONObject(key);
            if (entry == null) {
                logToLogcat(TAG, "Skipping malformed entry for '" + key + "'");
                continue;
            }
            if (decodeInto(editor, key, entry)) {
                imported++;
            }
        }

        // commit(), not apply(): the caller reports the result and reloads the service straight
        // after, so the write has to have happened by the time this returns.
        if (!editor.commit()) {
            throw new IOException("Could not save the imported settings");
        }

        logToLogcat(TAG, "Imported " + imported + " settings");
        return imported;
    }

    private static String readAll(Context context, Uri uri) throws IOException {
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("Could not open the selected file");
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, Charset.forName("UTF-8")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static JSONObject encode(Object value) throws JSONException {
        JSONObject entry = new JSONObject();
        if (value instanceof Boolean) {
            entry.put(KEY_TYPE, TYPE_BOOLEAN);
            entry.put(KEY_VALUE, value);
        } else if (value instanceof Integer) {
            entry.put(KEY_TYPE, TYPE_INT);
            entry.put(KEY_VALUE, value);
        } else if (value instanceof Long) {
            entry.put(KEY_TYPE, TYPE_LONG);
            entry.put(KEY_VALUE, value);
        } else if (value instanceof Float) {
            entry.put(KEY_TYPE, TYPE_FLOAT);
            entry.put(KEY_VALUE, ((Float) value).doubleValue());
        } else if (value instanceof String) {
            entry.put(KEY_TYPE, TYPE_STRING);
            entry.put(KEY_VALUE, value);
        } else if (value instanceof Set) {
            entry.put(KEY_TYPE, TYPE_STRING_SET);
            JSONArray array = new JSONArray();
            // Sorted for a stable diff between two exports of the same blocklist.
            Set<String> sorted = new TreeSet<>();
            for (Object element : (Set<?>) value) {
                sorted.add(String.valueOf(element));
            }
            for (String element : sorted) {
                array.put(element);
            }
            entry.put(KEY_VALUE, array);
        } else {
            return null;
        }
        return entry;
    }

    private static boolean decodeInto(SharedPreferences.Editor editor, String key, JSONObject entry) {
        String type = entry.optString(KEY_TYPE);
        try {
            switch (type) {
                case TYPE_BOOLEAN:
                    editor.putBoolean(key, entry.getBoolean(KEY_VALUE));
                    return true;
                case TYPE_INT:
                    editor.putInt(key, entry.getInt(KEY_VALUE));
                    return true;
                case TYPE_LONG:
                    editor.putLong(key, entry.getLong(KEY_VALUE));
                    return true;
                case TYPE_FLOAT:
                    editor.putFloat(key, (float) entry.getDouble(KEY_VALUE));
                    return true;
                case TYPE_STRING:
                    editor.putString(key, entry.getString(KEY_VALUE));
                    return true;
                case TYPE_STRING_SET:
                    JSONArray array = entry.getJSONArray(KEY_VALUE);
                    Set<String> values = new LinkedHashSet<>();
                    for (int i = 0; i < array.length(); i++) {
                        values.add(array.getString(i));
                    }
                    editor.putStringSet(key, values);
                    return true;
                default:
                    logToLogcat(TAG, "Skipping '" + key + "', unknown type '" + type + "'");
                    return false;
            }
        } catch (JSONException e) {
            logToLogcat(TAG, "Skipping '" + key + "', value does not match type '" + type + "'");
            return false;
        }
    }
}
