package com.autousbcopier;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class DebugLog {
    static final String PREFERENCE_ENABLED = "debug_log_enabled";
    private static final String PREFERENCE_URI = "debug_log_uri";
    private static final String TAG = "AutoUsbCopier";
    private static final String FILE_NAME = "AutoUsbCopierLog.txt";
    private DebugLog() {
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(PREFERENCE_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(PREFERENCE_ENABLED, enabled).apply();
    }

    static void log(Context context, String message) {
        if (!isEnabled(context)) {
            return;
        }
        synchronized (DebugLog.class) {
            try {
                List<String> entries = readEntries(context);
                String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date()) + " - " + message;
                writeEntries(context, LogEntryBuffer.append(entries, entry));
            } catch (Exception exception) {
                Log.e(TAG, "Could not update the debug log in Downloads.", exception);
            }
        }
    }

    private static List<String> readEntries(Context context) throws Exception {
        List<String> entries = new ArrayList<>();
        InputStream input = openExistingLog(context);
        if (input == null) {
            return entries;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                entries.add(line);
                if (entries.size() > LogEntryBuffer.MAX_ENTRIES) {
                    entries.remove(0);
                }
            }
        }
        return entries;
    }

    private static InputStream openExistingLog(Context context) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = getOrCreateMediaStoreUri(context, false);
            if (uri == null) {
                return null;
            }
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) {
                throw new IllegalStateException("The existing debug log could not be opened.");
            }
            return input;
        }
        File file = getLegacyLogFile();
        return file.isFile() ? new FileInputStream(file) : null;
    }

    private static void writeEntries(Context context, List<String> entries) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = getOrCreateMediaStoreUri(context, true);
            if (uri == null) {
                throw new IllegalStateException("MediaStore did not create the debug log.");
            }
            OutputStream output = context.getContentResolver().openOutputStream(uri, "wt");
            if (output == null) {
                throw new IllegalStateException("The debug log could not be opened for writing.");
            }
            writeLines(output, entries);
            return;
        }

        File file = getLegacyLogFile();
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("The Downloads folder could not be created.");
        }
        try (OutputStream output = new FileOutputStream(file, false)) {
            writeLines(output, entries);
        }
    }

    private static void writeLines(OutputStream output, List<String> entries) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            for (String entry : entries) {
                writer.write(entry);
                writer.newLine();
            }
        }
    }

    private static Uri getOrCreateMediaStoreUri(Context context, boolean create) throws Exception {
        SharedPreferences preferences = preferences(context);
        String saved = preferences.getString(PREFERENCE_URI, null);
        if (saved != null) {
            Uri savedUri = Uri.parse(saved);
            try (Cursor cursor = context.getContentResolver().query(
                    savedUri, new String[]{BaseColumns._ID}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return savedUri;
                }
            }
            preferences.edit().remove(PREFERENCE_URI).apply();
        }
        if (!create) {
            return null;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            preferences.edit().putString(PREFERENCE_URI, uri.toString()).apply();
        }
        return uri;
    }

    private static File getLegacyLogFile() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FILE_NAME);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE);
    }
}
