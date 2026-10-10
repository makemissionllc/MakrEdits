package com.makemission.makredits;

import android.Manifest;
import android.app.NativeActivity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android SDK bridge for SAF, runtime permissions, notifications, and MediaStore. */
public final class MainActivity extends NativeActivity {
    private static final String TAG = "FilmCraftBridge";
    private static final String CHANNEL_ID = "filmcraft_exports";
    private static final int NOTIFICATION_ID = 731;
    private static final int PICKER_REQUEST = 740;
    private static final int MIC_PERMISSION_REQUEST = 741;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 742;
    private static final long MAX_PICKED_FILE_BYTES = 16L * 1024L * 1024L * 1024L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, SaveWatch> saveWatches = new HashMap<>();
    private final Set<String> publishing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private File bridgeDir;
    private File importsDir;
    private SharedPreferences preferences;
    private boolean pickerOpen;
    private boolean permissionDialogOpen;
    private boolean micRequestPending;
    private boolean notificationRequestPending;
    private boolean exportWasActive;
    private int[] lastSafeInsets = new int[]{-1, -1, -1, -1};

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            try {
                pollBridge();
            } catch (RuntimeException error) {
                Log.e(TAG, "Bridge poll failed; the editor will keep running", error);
            } finally {
                handler.postDelayed(this, 300);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridgeDir = new File(getFilesDir(), "filmcraft-bridge");
        importsDir = new File(bridgeDir, "picked-files");
        preferences = getSharedPreferences("filmcraft-bridge", MODE_PRIVATE);
        if (!bridgeDir.isDirectory() && !bridgeDir.mkdirs()) {
            Log.e(TAG, "Could not create the private bridge directory");
        }
        if (!importsDir.isDirectory() && !importsDir.mkdirs()) {
            Log.e(TAG, "Could not create the private import directory");
        }
        installSafeAreaTracking();
        createNotificationChannel();
        handler.post(poller);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poller);
        io.shutdown();
        super.onDestroy();
    }

    private void pollBridge() {
        if (!pickerOpen) consumePickerRequest();
        if (!permissionDialogOpen) {
            consumeMicrophoneRequest();
            consumeNotificationRequest();
        }
        reportExportProgress();
        watchSavedProjects();
    }

    private void installSafeAreaTracking() {
        View decor = getWindow().getDecorView();
        decor.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = insets.left;
                top = insets.top;
                right = insets.right;
                bottom = insets.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }
            int[] next = new int[]{left, top, right, bottom};
            if (!java.util.Arrays.equals(lastSafeInsets, next)) {
                lastSafeInsets = next;
                try {
                    io.execute(() -> writeSafeArea(next));
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not queue updated system insets", error);
                }
            }
            return windowInsets;
        });
        decor.requestApplyInsets();
    }

    private void writeSafeArea(int[] insets) {
        JSONObject value = new JSONObject();
        try {
            value.put("left", insets[0]);
            value.put("top", insets[1]);
            value.put("right", insets[2]);
            value.put("bottom", insets[3]);
            writeEvent("safe-area.json", value);
        } catch (Exception error) {
            Log.w(TAG, "Could not publish system insets", error);
        }
    }

    private void consumePickerRequest() {
        File request = new File(bridgeDir, "picker-request.json");
        JSONObject json = takeJson(request);
        if (json == null) return;
        pickerOpen = true;
        String id = json.optString("request_id", "");
        String mode = json.optString("mode", "open");
        writeString(new File(bridgeDir, "active-picker-id.txt"), id);
        writeString(new File(bridgeDir, "active-picker-mode.txt"), mode);
        Intent intent = new Intent("save".equals(mode) ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        JSONArray mimeTypes = json.optJSONArray("mime_types");
        if (mimeTypes != null && mimeTypes.length() > 0) {
            String[] values = new String[Math.min(mimeTypes.length(), 64)];
            for (int i = 0; i < values.length; i++) values[i] = mimeTypes.optString(i, "*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, values);
        }
        if ("save".equals(mode)) {
            intent.putExtra(Intent.EXTRA_TITLE, json.optString("suggested_name", "FilmCraft.fcproj"));
        } else {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, json.optBoolean("multiple", false));
        }
        try {
            startActivityForResult(intent, PICKER_REQUEST);
        } catch (Exception error) {
            pickerOpen = false;
            writePickerResult(id, new JSONArray(), "Android could not open the file picker: " + safeMessage(error), "");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICKER_REQUEST) return;
        pickerOpen = false;
        String id = readString(new File(bridgeDir, "active-picker-id.txt"), "");
        if (resultCode != RESULT_OK || data == null) {
            writePickerResult(id, new JSONArray(), "", "");
            return;
        }
        // Some document providers return an intent without preserving extras, so recover the id
        // from the small active marker written immediately before launching the picker.
        final String requestId = id;
        final boolean saving = data.getBooleanExtra("filmcraft_save", false)
                || readString(new File(bridgeDir, "active-picker-mode.txt"), "open").equals("save");
        final int grantedFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        final ArrayList<Uri> uris = pickedUris(data);
        io.execute(() -> finishPicker(requestId, saving, uris, grantedFlags));
    }

    private ArrayList<Uri> pickedUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = Math.min(data.getClipData().getItemCount(), 64);
            for (int i = 0; i < count; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void finishPicker(String id, boolean saving, ArrayList<Uri> uris, int grantedFlags) {
        JSONArray paths = new JSONArray();
        String errorMessage = "";
        String warningMessage = "";
        try {
            if (uris.isEmpty()) {
                writePickerResult(id, paths, "", "");
                return;
            }
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                try {
                    boolean persistent = takePersistableGrant(uri, grantedFlags);
                    if (saving && (!persistent || (grantedFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)) {
                        throw new SecurityException("this document provider did not grant persistent write access");
                    }
                    String displayName = safeName(displayName(uri));
                    if (saving) {
                        File target = new File(importsDir, safeId(id) + "_" + displayName);
                        String key = "save_target:" + target.getAbsolutePath();
                        preferences.edit().putString(key, uri.toString()).apply();
                        paths.put(target.getAbsolutePath());
                    } else {
                        File target = new File(importsDir, safeId(id) + "_" + i + "_" + displayName);
                        if (displayName.toLowerCase(java.util.Locale.ROOT).endsWith(".fcproj")) {
                            if (persistent && (grantedFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                                preferences.edit().putString("save_target:" + target.getAbsolutePath(), uri.toString()).apply();
                            } else {
                                warningMessage = "Project opened from a private copy. This provider did not grant write access; use Save As to update the original document.";
                            }
                        }
                        copyUriToFile(uri, target);
                        paths.put(target.getAbsolutePath());
                    }
                } catch (Exception error) {
                    errorMessage = "Could not read the selected document: " + safeMessage(error);
                    break;
                }
            }
        } catch (Exception error) {
            errorMessage = "Could not use the selected document: " + safeMessage(error);
        }
        if (!errorMessage.isEmpty()) {
            for (int i = 0; i < paths.length(); i++) {
                File partial = new File(paths.optString(i, ""));
                if (partial.isFile() && !preferences.contains("save_target:" + partial.getAbsolutePath())) partial.delete();
            }
            paths = new JSONArray();
        }
        writePickerResult(id, paths, errorMessage, errorMessage.isEmpty() ? warningMessage : "");
    }

    private boolean takePersistableGrant(Uri uri, int grantedFlags) {
        if ((grantedFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) == 0) return false;
        try {
            getContentResolver().takePersistableUriPermission(uri, grantedFlags);
            return true;
        } catch (SecurityException ignored) {
            // The selected file has already been copied to private storage for reading.
            return false;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception error) {
            Log.w(TAG, "Document provider did not return a display name", error);
        }
        return "SelectedFile";
    }

    private void copyUriToFile(Uri uri, File target) throws Exception {
        try (InputStream input = new BufferedInputStream(getContentResolver().openInputStream(uri));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            if (input == null) throw new IllegalStateException("the document provider returned no file data");
            copyLimited(input, output);
        } catch (Exception error) {
            target.delete();
            throw error;
        }
    }

    private void consumeMicrophoneRequest() {
        File request = new File(bridgeDir, "microphone-request.json");
        if (!request.isFile()) return;
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            takeJson(request);
            writePermissionResult("microphone", true, "");
            return;
        }
        takeJson(request);
        permissionDialogOpen = true;
        micRequestPending = true;
        try {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
        } catch (RuntimeException error) {
            permissionDialogOpen = false;
            micRequestPending = false;
            writePermissionResult("microphone", false, "Android could not request microphone access: " + safeMessage(error));
        }
    }

    private void consumeNotificationRequest() {
        File request = new File(bridgeDir, "notification-start.json");
        if (!request.isFile()) return;
        takeJson(request);
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            writePermissionResult("notifications", true, "");
            return;
        }
        if (preferences.getBoolean("notification_prompted", false)) {
            writePermissionResult("notifications", false, "");
            return;
        }
        preferences.edit().putBoolean("notification_prompted", true).apply();
        permissionDialogOpen = true;
        notificationRequestPending = true;
        try {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        } catch (RuntimeException error) {
            permissionDialogOpen = false;
            notificationRequestPending = false;
            writePermissionResult("notifications", false, "Android could not request notifications: " + safeMessage(error));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MIC_PERMISSION_REQUEST && requestCode != NOTIFICATION_PERMISSION_REQUEST) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        permissionDialogOpen = false;
        if (requestCode == MIC_PERMISSION_REQUEST && micRequestPending) {
            micRequestPending = false;
            writePermissionResult("microphone", granted, "");
        }
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST && notificationRequestPending) {
            notificationRequestPending = false;
            writePermissionResult("notifications", granted, "");
        }
    }

    private void reportExportProgress() {
        JSONObject progress = readJson(new File(bridgeDir, "export-progress.json"));
        if (progress == null) return;
        JSONArray items = progress.optJSONArray("items");
        if (items == null) return;
        int active = 0;
        int complete = 0;
        int percent = 0;
        String currentName = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String status = item.optString("status", "");
            if ("encoding".equals(status)) {
                active++;
                percent = Math.max(percent, Math.min(100, (int) Math.round(item.optDouble("progress", 0.0) * 100.0)));
                currentName = safeName(item.optString("name", "Export"));
            } else if ("done".equals(status)) {
                complete++;
                enqueueMediaStorePublish(item);
            }
        }
        if (active > 0) {
            exportWasActive = true;
            postExportNotification("Exporting " + currentName, percent + "% complete", true);
        } else if (exportWasActive) {
            exportWasActive = false;
            postExportNotification("Export complete", complete + " file(s) saved", false);
            handler.postDelayed(() -> {
                try {
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not dismiss the completed export notification", error);
                }
            }, 5000);
        }
    }

    private void postExportNotification(String title, String message, boolean ongoing) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setOnlyAlertOnce(true)
                    .setOngoing(ongoing)
                    .setProgress(100, ongoing ? Math.max(0, Math.min(100, Integer.parseInt(message.split("%", 2)[0]))) : 0, false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not update the export notification", error);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "FilmCraft exports", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress and completion for video exports");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void enqueueMediaStorePublish(JSONObject item) {
        String path = item.optString("path", "");
        File source = new File(path);
        if (!source.isFile()) return;
        String signature = source.length() + "_" + source.lastModified();
        String key = "published:" + path;
        if (signature.equals(preferences.getString(key, "")) || !publishing.add(path)) return;
        io.execute(() -> {
            try {
                publishToMediaStore(source);
                preferences.edit().putString(key, signature).apply();
            } catch (Exception error) {
                Log.e(TAG, "Could not save export to MediaStore", error);
                writeMessageEvent("Export finished but could not be added to MediaStore: " + safeMessage(error));
            } finally {
                publishing.remove(path);
            }
        });
    }

    private void publishToMediaStore(File source) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("MediaStore export requires Android 10 or later");
        String name = safeName(source.getName());
        String mime = mimeType(name);
        Uri collection;
        String relative;
        if (mime.startsWith("video/")) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relative = "Movies/FilmCraft";
        } else if (mime.startsWith("audio/")) {
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relative = "Music/FilmCraft";
        } else if (mime.startsWith("image/")) {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relative = "Pictures/FilmCraft";
        } else {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relative = "Download/FilmCraft";
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = getContentResolver().insert(collection, values);
        if (destination == null) throw new IllegalStateException("Android did not create an export file");
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(getContentResolver().openOutputStream(destination, "w"))) {
            if (output == null) throw new IllegalStateException("Android did not open the export file");
            copyLimited(input, output);
        } catch (Exception error) {
            getContentResolver().delete(destination, null, null);
            throw error;
        }
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(destination, ready, null, null);
    }

    private void watchSavedProjects() {
        Map<String, ?> all = preferences.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!entry.getKey().startsWith("save_target:") || !(entry.getValue() instanceof String)) continue;
            String path = entry.getKey().substring("save_target:".length());
            File file = new File(path);
            if (!file.isFile()) continue;
            String signature = file.length() + "_" + file.lastModified();
            SaveWatch state = saveWatches.computeIfAbsent(path, ignored -> new SaveWatch());
            if (!signature.equals(state.seen)) {
                state.seen = signature;
                state.stablePolls = 0;
            } else {
                state.stablePolls = Math.min(state.stablePolls + 1, 10);
            }
            if (state.stablePolls >= 2 && !signature.equals(state.synced) && !state.copying) {
                state.copying = true;
                String uriString = (String) entry.getValue();
                io.execute(() -> {
                    try {
                        copyFileToUri(file, Uri.parse(uriString));
                        state.synced = signature;
                    } catch (Exception error) {
                        Log.e(TAG, "Could not update the selected project document", error);
                        writeMessageEvent("Project saved locally, but Android could not update the selected document: " + safeMessage(error));
                    } finally {
                        state.copying = false;
                    }
                });
            }
        }
    }

    private void copyFileToUri(File source, Uri uri) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(getContentResolver().openOutputStream(uri, "wt"))) {
            if (output == null) throw new IllegalStateException("Android did not open the selected document");
            copyLimited(input, output);
        }
    }

    private void copyLimited(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_PICKED_FILE_BYTES) throw new IllegalStateException("document is larger than 16 GiB");
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private JSONObject takeJson(File file) {
        JSONObject value = readJson(file);
        if (value != null && !file.delete()) Log.w(TAG, "Could not remove request " + file.getName());
        return value;
    }

    private JSONObject readJson(File file) {
        if (!file.isFile() || file.length() > 4L * 1024L * 1024L) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            return new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
        } catch (Exception error) {
            Log.w(TAG, "Could not read bridge request " + file.getName(), error);
            return null;
        }
    }

    private String readString(File file, String fallback) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[Math.min((int) file.length(), 4096)];
            int count = input.read(bytes);
            return count > 0 ? new String(bytes, 0, count, StandardCharsets.UTF_8) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void writePickerResult(String id, JSONArray paths, String error, String warning) {
        JSONObject result = new JSONObject();
        try {
            result.put("request_id", id);
            result.put("paths", paths);
            result.put("error", error);
            result.put("warning", warning);
            writeEvent("picker-result.json", result);
        } catch (Exception ignored) {
            Log.e(TAG, "Could not write picker result");
        }
    }

    private void writePermissionResult(String kind, boolean granted, String error) {
        JSONObject result = new JSONObject();
        try {
            result.put("kind", kind);
            result.put("granted", granted);
            result.put("error", error);
            writeEvent(kind.equals("microphone") ? "microphone-result.json" : "notification-result.json", result);
        } catch (Exception ignored) {
            Log.e(TAG, "Could not write permission result");
        }
    }

    private void writeMessageEvent(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("message", message);
            writeEvent("export-error.json", result);
        } catch (Exception ignored) {
            Log.e(TAG, "Could not write error event");
        }
    }

    private void writeString(File file, String value) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "Could not save picker state", error);
        }
    }

    private void writeEvent(String fileName, JSONObject value) {
        if (bridgeDir == null) return;
        File temp = new File(bridgeDir, fileName + ".tmp");
        File destination = new File(bridgeDir, fileName);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "Could not write bridge event " + fileName, error);
            temp.delete();
            return;
        }
        if (destination.exists() && !destination.delete()) {
            temp.delete();
            Log.e(TAG, "Could not replace bridge event " + fileName);
            return;
        }
        if (!temp.renameTo(destination)) {
            temp.delete();
            Log.e(TAG, "Could not publish bridge event " + fileName);
        }
    }

    private String safeName(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < 120; i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || c < 32 || c == ':' || c == '*') out.append('_');
            else out.append(c);
        }
        String result = out.toString().trim();
        return result.isEmpty() || result.equals(".") || result.equals("..") ? "SelectedFile" : result;
    }

    private String safeId(String value) {
        if (value == null || value.isEmpty()) return "request";
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String mimeType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static final class SaveWatch {
        String seen = "";
        volatile String synced = "";
        int stablePolls;
        volatile boolean copying;
    }
}
