package com.autousbcopier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CopyService extends Service {
    static final String ACTION_START = "com.autousbcopier.action.START_COPY";
    static final String ACTION_CANCEL = "com.autousbcopier.action.CANCEL_COPY";
    static final String ACTION_RESULT = "com.autousbcopier.action.COPY_RESULT";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_DESTINATION = "destination";
    static final String EXTRA_CUSTOM_SOURCES = "custom_sources";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_DIRECTION = "direction";
    static final String EXTRA_OVERWRITE = "overwrite";
    static final String EXTRA_SKIP_IDENTICAL = "skip_identical";
    static final String EXTRA_COPIED = "copied";
    static final String EXTRA_SKIPPED = "skipped";
    static final String EXTRA_MATCHED = "matched";
    static final String EXTRA_ERRORS = "errors";
    static final String EXTRA_CANCELLED = "cancelled";
    static final String EXTRA_NO_MEDIA = "no_media";
    static final String EXTRA_SUMMARY = "summary";

    static final int MODE_PHOTOS = 0;
    static final int MODE_PHOTOS_AND_VIDEOS = 1;
    static final int MODE_VIDEOS = 2;
    static final int MODE_EVERYTHING = 3;
    static final int MODE_CUSTOM = 4;

    private static final String CHANNEL_ID = "copy_progress";
    private static final int SERVICE_NOTIFICATION_ID = 1002;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_DIRECTORY_DEPTH = 128;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile boolean running;
    private static volatile boolean serviceRunning;
    private int mode;
    private boolean overwrite;
    private boolean skipIdentical;
    private String direction;
    private Uri sourceTree;
    private Uri destinationTree;
    private CopyStats stats;
    private List<Uri> customSources = Collections.emptyList();
    private final Map<String, Map<String, Uri>> destinationChildren = new HashMap<>();

    static boolean isRunning() {
        return serviceRunning;
    }

    private void restoreBackup(Uri parent, Uri backupUri, String backupName, String name) {
        if (backupUri == null) {
            return;
        }
        try {
            Uri restored = DocumentsContract.renameDocument(getContentResolver(), backupUri, name);
            if (restored == null) {
                stats.addError(getString(R.string.error_restore_failed, name));
            } else {
                updateDestinationCache(parent, backupName, name, restored);
            }
        } catch (Exception exception) {
            stats.addError(getString(R.string.error_restore_failed, name));
        }
    }

    private void deleteTemporary(Uri parent, Uri uri, String temporaryName, String name) {
        try {
            DocumentsContract.deleteDocument(getContentResolver(), uri);
            removeFromDestinationCache(parent, temporaryName);
        } catch (Exception exception) {
            stats.addError(getString(R.string.error_temp_cleanup, name));
        }
    }

    private void updateDestinationCache(Uri parent, String oldName, String newName, Uri uri) {
        Map<String, Uri> children = destinationChildren.get(parent.toString());
        if (children != null) {
            children.remove(oldName);
            children.put(newName, uri);
        }
    }

    private void removeFromDestinationCache(Uri parent, String name) {
        Map<String, Uri> children = destinationChildren.get(parent.toString());
        if (children != null) {
            children.remove(name);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            return START_NOT_STICKY;
        }

        if (running) {
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        sourceTree = parseUri(intent.getStringExtra(EXTRA_SOURCE));
        destinationTree = parseUri(intent.getStringExtra(EXTRA_DESTINATION));
        mode = intent.getIntExtra(EXTRA_MODE, MODE_PHOTOS);
        direction = intent.getStringExtra(EXTRA_DIRECTION);
        overwrite = intent.getBooleanExtra(EXTRA_OVERWRITE, false);
        skipIdentical = intent.getBooleanExtra(EXTRA_SKIP_IDENTICAL, true);
        customSources = parseUris(intent.getStringArrayListExtra(EXTRA_CUSTOM_SOURCES));
        if (destinationTree == null || (mode != MODE_CUSTOM && sourceTree == null)
                || (mode == MODE_CUSTOM && customSources.isEmpty())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        cancelled = false;
        running = true;
        serviceRunning = true;
        stats = new CopyStats();
        destinationChildren.clear();
        DebugLog.log(this, "Copy attempt: " + modeName(mode) + "; direction: "
                + (direction == null ? "unknown" : direction) + "; overwrite: "
                + (overwrite ? "on" : "off") + ".");
        startForeground(SERVICE_NOTIFICATION_ID, buildProgressNotification());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                performCopy();
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    private void performCopy() {
        String failure = null;
        try {
            ensureStorageAvailable();
            Uri destinationRoot = treeRoot(destinationTree);
            if (mode == MODE_CUSTOM) {
                for (Uri selected : customSources) {
                    checkCancelled();
                    copyCustomItem(selected, destinationRoot);
                }
            } else {
                Uri sourceRoot = treeRoot(sourceTree);
                if (sameDocument(sourceRoot, destinationTree, destinationRoot)) {
                    throw new IOException(getString(R.string.error_same_folder));
                }
                copyDirectoryContents(sourceTree, sourceRoot, destinationTree, destinationRoot, 0,
                        new HashSet<String>(), true);
            }
        } catch (CopyCancelledException ignored) {
            cancelled = true;
        } catch (Exception exception) {
            failure = exception.getMessage();
            if (failure == null || failure.trim().isEmpty()) {
                failure = getString(R.string.error_copy_failed);
            }
            DebugLog.log(this, exception instanceof StorageDisconnectedException
                    ? "ERROR: USB storage disconnected during copying."
                    : "ERROR: Copy failed (" + exception.getClass().getSimpleName() + ").");
            stats.addError(failure);
        } finally {
            running = false;
            serviceRunning = false;
            boolean noMedia = failure == null && stats.errors == 0
                    && mode != MODE_EVERYTHING && mode != MODE_CUSTOM && stats.matched == 0;
            String summary = buildSummary(noMedia, failure);
            sendResult(noMedia, summary);
            stopForeground(true);
            showFinishedNotification(noMedia, summary);
            stopSelf();
        }
    }

    private void copyDirectoryContents(Uri sourceTreeUri, Uri sourceDirectory, Uri destinationTreeUri,
                                       Uri destinationDirectory, int depth, Set<String> visited,
                                       boolean filterMedia)
            throws IOException, CopyCancelledException {
        checkCancelled();
        if (depth > MAX_DIRECTORY_DEPTH) {
            throw new IOException(getString(R.string.error_too_deep));
        }
        String documentId = DocumentsContract.getDocumentId(sourceDirectory);
        if (!visited.add(documentId)) {
            throw new IOException(getString(R.string.error_directory_cycle));
        }

        try {
            for (DocumentItem child : listChildren(sourceTreeUri, sourceDirectory)) {
                checkCancelled();
                ensureStorageAvailable();
                if (child.isDirectory()) {
                    Uri destinationChild = findChild(destinationTreeUri, destinationDirectory, child.name);
                    if (destinationChild != null && !isDirectory(destinationTreeUri, destinationChild)) {
                        stats.addError(getString(R.string.error_path_conflict, child.name));
                        continue;
                    }
                    if (destinationChild == null) {
                        try {
                            destinationChild = createDocument(destinationTreeUri, destinationDirectory,
                                    DocumentsContract.Document.MIME_TYPE_DIR, child.name);
                        } catch (IOException exception) {
                            stats.addError(exception.getMessage() == null
                                    ? getString(R.string.error_copy_failed) : exception.getMessage());
                            continue;
                        }
                    }
                    try {
                        copyDirectoryContents(sourceTreeUri, child.uri, destinationTreeUri, destinationChild,
                                depth + 1, visited, filterMedia);
                    } catch (StorageDisconnectedException exception) {
                        throw exception;
                    } catch (IOException exception) {
                        stats.addError(exception.getMessage() == null
                                ? getString(R.string.error_copy_failed) : exception.getMessage());
                    }
                } else if (!filterMedia || isIncluded(child.name)) {
                    stats.matched++;
                    try {
                        copyFile(sourceTreeUri, child.uri, child, destinationTreeUri, destinationDirectory);
                    } catch (StorageDisconnectedException exception) {
                        throw exception;
                    } catch (IOException exception) {
                        stats.addError(exception.getMessage() == null
                                ? getString(R.string.error_copy_failed) : exception.getMessage());
                    }
                }
            }
        } finally {
            visited.remove(documentId);
            destinationChildren.remove(destinationDirectory.toString());
        }
    }

    private void copyFile(Uri sourceTreeUri, Uri sourceFile, DocumentItem source,
                          Uri destinationTreeUri, Uri destinationDirectory)
            throws IOException, CopyCancelledException {
        checkCancelled();
        Uri existing = findChild(destinationTreeUri, destinationDirectory, source.name);
        if (existing != null) {
            if (isDirectory(destinationTreeUri, existing)) {
                stats.addError(getString(R.string.error_path_conflict, source.name));
                return;
            }
            if (!overwrite) {
                stats.skipped++;
                return;
            }
            if (skipIdentical && source.size >= 0 && source.lastModified > 0) {
                DocumentItem destinationItem = queryItem(destinationTreeUri, existing);
                if (CopyPolicy.shouldSkipExisting(true, true, source.size, source.lastModified,
                        destinationItem.size, destinationItem.lastModified)) {
                    stats.skipped++;
                    return;
                }
            }
        }

        String mimeType = source.mimeType;
        if (mimeType == null || mimeType.trim().isEmpty()
                || DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            mimeType = mimeTypeForName(source.name);
        }
        String temporaryName = ".auc-" + UUID.randomUUID().toString() + ".tmp";
        Uri outputUri = createDocument(destinationTreeUri, destinationDirectory, mimeType, temporaryName);
        boolean written = false;
        try {
            streamCopy(sourceFile, outputUri);
            written = true;
        } finally {
            if (!written) {
                try {
                    DocumentsContract.deleteDocument(getContentResolver(), outputUri);
                    removeFromDestinationCache(destinationDirectory, temporaryName);
                } catch (Exception ignored) {
                    stats.addError(getString(R.string.error_temp_cleanup, source.name));
                }
            }
        }

        Uri backupUri = null;
        String backupName = ".auc-backup-" + UUID.randomUUID().toString();
        if (existing != null) {
            try {
                backupUri = DocumentsContract.renameDocument(getContentResolver(), existing,
                        backupName);
            } catch (Exception exception) {
                deleteTemporary(destinationDirectory, outputUri, temporaryName, source.name);
                throw new IOException(getString(R.string.error_replace_failed, source.name), exception);
            }
            if (backupUri == null) {
                deleteTemporary(destinationDirectory, outputUri, temporaryName, source.name);
                throw new IOException(getString(R.string.error_replace_failed, source.name));
            }
            updateDestinationCache(destinationDirectory, source.name, backupName, backupUri);
        }
        Uri renamed;
        try {
            renamed = DocumentsContract.renameDocument(getContentResolver(), outputUri, source.name);
        } catch (Exception exception) {
            restoreBackup(destinationDirectory, backupUri, backupName, source.name);
            deleteTemporary(destinationDirectory, outputUri, temporaryName, source.name);
            throw new IOException(getString(R.string.error_rename_failed, source.name), exception);
        }
        if (renamed == null) {
            restoreBackup(destinationDirectory, backupUri, backupName, source.name);
            deleteTemporary(destinationDirectory, outputUri, temporaryName, source.name);
            throw new IOException(getString(R.string.error_rename_failed, source.name));
        }
        updateDestinationCache(destinationDirectory, temporaryName, source.name, renamed);
        if (backupUri != null) {
            try {
                DocumentsContract.deleteDocument(getContentResolver(), backupUri);
                removeFromDestinationCache(destinationDirectory, backupName);
            } catch (Exception exception) {
                stats.addError(getString(R.string.error_backup_cleanup, source.name));
            }
        }
        stats.copied++;
        if (stats.copied % 4 == 0) {
            updateProgressNotification();
        }
    }

    private void streamCopy(Uri sourceFile, Uri destinationFile)
            throws IOException, CopyCancelledException {
        ContentResolver resolver = getContentResolver();
        InputStream rawInput = openInput(resolver, sourceFile);
        if (rawInput == null) {
            throw new IOException(getString(R.string.error_open_stream));
        }
        OutputStream rawOutput;
        try {
            rawOutput = resolver.openOutputStream(destinationFile, "w");
        } catch (IOException | RuntimeException exception) {
            rawInput.close();
            throw exception;
        }
        if (rawOutput == null) {
            rawInput.close();
            throw new IOException(getString(R.string.error_open_stream));
        }
        try (InputStream input = new BufferedInputStream(rawInput);
             OutputStream output = new BufferedOutputStream(rawOutput)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            long bytesSinceCheck = 0;
            while ((read = input.read(buffer)) != -1) {
                checkCancelled();
                bytesSinceCheck += read;
                if (bytesSinceCheck >= 1024L * 1024L) {
                    ensureStorageAvailable();
                    bytesSinceCheck = 0;
                }
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private void copyCustomItem(Uri selected, Uri destinationRoot)
            throws IOException, CopyCancelledException {
        if (DocumentsContract.isTreeUri(selected)) {
            String rootId = DocumentsContract.getTreeDocumentId(selected);
            Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(selected, rootId);
            if (sameDocument(rootDocument, destinationTree, destinationRoot)) {
                throw new IOException(getString(R.string.error_same_folder));
            }
            DocumentItem root = queryItem(selected, rootDocument);
            if (root.isDirectory()) {
                Uri target = findChild(destinationTree, destinationRoot, root.name);
                if (target != null && !isDirectory(destinationTree, target)) {
                    throw new IOException(getString(R.string.error_path_conflict, root.name));
                }
                if (target == null) {
                    target = createDocument(destinationTree, destinationRoot,
                            DocumentsContract.Document.MIME_TYPE_DIR, root.name);
                }
                copyDirectoryContents(selected, rootDocument, destinationTree, target, 0,
                        new HashSet<String>(), false);
            } else {
                copyFile(selected, rootDocument, root, destinationTree, destinationRoot);
            }
            return;
        }

        DocumentItem item = queryItem(null, selected);
        if (sameDocument(selected, destinationTree, destinationRoot)) {
            throw new IOException(getString(R.string.error_same_folder));
        }
        if (item.isDirectory()) {
            Uri target = findChild(destinationTree, destinationRoot, item.name);
            if (target == null) {
                target = createDocument(destinationTree, destinationRoot,
                        DocumentsContract.Document.MIME_TYPE_DIR, item.name);
            }
            copyDirectoryContents(null, selected, destinationTree, target, 0,
                    new HashSet<String>(), false);
        } else {
            stats.matched++;
            copyFile(null, selected, item, destinationTree, destinationRoot);
        }
    }

    private List<DocumentItem> listChildren(Uri treeUri, Uri directory) throws IOException {
        Uri childrenUri;
        if (treeUri != null) {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getDocumentId(directory));
        } else {
            childrenUri = DocumentsContract.buildChildDocumentsUri(
                    directory.getAuthority(), DocumentsContract.getDocumentId(directory));
        }
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        List<DocumentItem> items = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(childrenUri, columns, null, null, null)) {
            if (cursor == null) {
                throw new IOException(getString(R.string.error_list_folder));
            }
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idColumn);
                Uri childUri = treeUri != null
                        ? DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        : DocumentsContract.buildDocumentUri(directory.getAuthority(), id);
                items.add(new DocumentItem(
                        childUri,
                        cursor.getString(nameColumn),
                        cursor.getString(mimeColumn),
                        sizeColumn < 0 || cursor.isNull(sizeColumn) ? -1 : cursor.getLong(sizeColumn),
                        modifiedColumn < 0 || cursor.isNull(modifiedColumn) ? 0 : cursor.getLong(modifiedColumn)));
            }
        } catch (SecurityException exception) {
            throw new IOException(getString(R.string.error_access_revoked), exception);
        }
        return items;
    }

    private DocumentItem queryItem(Uri treeUri, Uri documentUri) throws IOException {
        String[] columns = {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = getContentResolver().query(documentUri, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException(getString(R.string.error_read_file));
            }
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            return new DocumentItem(
                    documentUri,
                    nameColumn < 0 ? documentUri.getLastPathSegment() : cursor.getString(nameColumn),
                    mimeColumn < 0 ? null : cursor.getString(mimeColumn),
                    sizeColumn < 0 || cursor.isNull(sizeColumn) ? -1 : cursor.getLong(sizeColumn),
                    modifiedColumn < 0 || cursor.isNull(modifiedColumn) ? 0 : cursor.getLong(modifiedColumn));
        } catch (SecurityException exception) {
            throw new IOException(getString(R.string.error_access_revoked), exception);
        }
    }

    private Uri findChild(Uri treeUri, Uri parent, String name) throws IOException {
        String parentKey = parent.toString();
        Map<String, Uri> children = destinationChildren.get(parentKey);
        if (children == null) {
            children = new HashMap<>();
            for (DocumentItem child : listChildren(treeUri, parent)) {
                children.put(child.name, child.uri);
            }
            destinationChildren.put(parentKey, children);
        }
        return children.get(name);
    }

    private boolean isDirectory(Uri treeUri, Uri document) throws IOException {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(queryItem(treeUri, document).mimeType);
    }

    private Uri createDocument(Uri treeUri, Uri parent, String mimeType, String name) throws IOException {
        try {
            Uri result = DocumentsContract.createDocument(getContentResolver(), parent, mimeType, name);
            if (result == null) {
                throw new IOException(getString(R.string.error_create_document, name));
            }
            Map<String, Uri> children = destinationChildren.get(parent.toString());
            if (children != null) {
                children.put(name, result);
            }
            return result;
        } catch (SecurityException exception) {
            throw new IOException(getString(R.string.error_access_revoked), exception);
        }
    }

    private InputStream openInput(ContentResolver resolver, Uri document) throws IOException {
        try {
            return resolver.openInputStream(document);
        } catch (SecurityException exception) {
            throw new IOException(getString(R.string.error_access_revoked), exception);
        }
    }

    private boolean isIncluded(String name) {
        return MediaFileFilter.matches(mode, name);
    }

    private String modeName(int copyMode) {
        switch (copyMode) {
            case MODE_PHOTOS:
                return getString(R.string.just_photos);
            case MODE_PHOTOS_AND_VIDEOS:
                return getString(R.string.photos_and_videos);
            case MODE_VIDEOS:
                return getString(R.string.videos);
            case MODE_EVERYTHING:
                return getString(R.string.everything);
            case MODE_CUSTOM:
                return getString(R.string.custom);
            default:
                return "unknown";
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String mimeTypeForName(String name) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(name));
        return mime == null ? "application/octet-stream" : mime;
    }

    private boolean sameDocument(Uri source, Uri destinationTreeUri, Uri destination) {
        if (destinationTreeUri == null || source.getAuthority() == null
                || !source.getAuthority().equals(destination.getAuthority())) {
            return false;
        }
        String sourceId = DocumentsContract.getDocumentId(source);
        String destinationId = DocumentsContract.getDocumentId(destination);
        return sourceId.equals(destinationId)
                || sourceId.startsWith(documentIdPrefix(destinationId))
                || destinationId.startsWith(documentIdPrefix(sourceId));
    }

    private static String documentIdPrefix(String documentId) {
        return documentId.endsWith(":") ? documentId : documentId + "/";
    }

    private Uri treeRoot(Uri treeUri) throws IOException {
        if (!DocumentsContract.isTreeUri(treeUri)) {
            throw new IOException(getString(R.string.error_folder_permission));
        }
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
    }

    private void checkCancelled() throws CopyCancelledException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new CopyCancelledException();
        }
    }

    private void ensureStorageAvailable() throws StorageDisconnectedException {
        if (!UsbStorageDetector.hasMountedStorage(this)) {
            throw new StorageDisconnectedException(getString(R.string.error_usb_disconnected));
        }
    }

    private void updateProgressNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(SERVICE_NOTIFICATION_ID, buildProgressNotification());
        }
    }

    private Notification buildProgressNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            createServiceChannel(manager);
        }
        Intent cancelIntent = new Intent(this, CopyService.class);
        cancelIntent.setAction(ACTION_CANCEL);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this, 1, cancelIntent, StorageMonitorReceiver.pendingIntentFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.copy_in_progress))
                .setContentText(getString(R.string.copied_count, stats.copied))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.cancel_copy), cancelPendingIntent)
                .build();
    }

    private void showFinishedNotification(boolean noMedia, String summary) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        createServiceChannel(manager);
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.putExtra(EXTRA_NO_MEDIA, noMedia);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 2, openIntent, StorageMonitorReceiver.pendingIntentFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(noMedia
                        ? getString(R.string.no_matching_media)
                        : cancelled ? getString(R.string.copy_cancelled) : getString(R.string.copy_finished))
                .setContentText(summary)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .build();
        manager.notify(SERVICE_NOTIFICATION_ID, notification);
    }

    private void sendResult(boolean noMedia, String summary) {
        Intent result = new Intent(ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra(EXTRA_NO_MEDIA, noMedia);
        result.putExtra(EXTRA_COPIED, stats.copied);
        result.putExtra(EXTRA_SKIPPED, stats.skipped);
        result.putExtra(EXTRA_MATCHED, stats.matched);
        result.putExtra(EXTRA_ERRORS, stats.errors);
        result.putExtra(EXTRA_CANCELLED, cancelled);
        result.putExtra(EXTRA_SUMMARY, summary);
        sendBroadcast(result);
    }

    private String buildSummary(boolean noMedia, String failure) {
        if (noMedia) {
            return getString(R.string.no_matching_media_message);
        }
        if (failure != null && stats.copied == 0) {
            return failure;
        }
        if (cancelled) {
            return getString(R.string.copy_summary_cancelled, stats.copied, stats.skipped, stats.errors);
        }
        String summary = getString(R.string.copy_summary, stats.copied, stats.skipped, stats.errors);
        if (stats.firstError != null) {
            summary += "\n" + stats.firstError;
        }
        return summary;
    }

    private void createServiceChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.copy_notifications), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.copy_notifications_description));
            manager.createNotificationChannel(channel);
        }
    }

    private static Uri parseUri(String value) {
        return value == null ? null : Uri.parse(value);
    }

    private static List<Uri> parseUris(ArrayList<String> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        List<Uri> result = new ArrayList<>();
        for (String value : values) {
            if (value != null) {
                result.add(Uri.parse(value));
            }
        }
        return result;
    }

    private final class DocumentItem {
        final Uri uri;
        final String name;
        final String mimeType;
        final long size;
        final long lastModified;

        DocumentItem(Uri uri, String name, String mimeType, long size, long lastModified) {
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.mimeType = mimeType;
            this.size = size;
            this.lastModified = lastModified;
        }

        boolean isDirectory() {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        }

    }

    private final class CopyStats {
        int matched;
        int copied;
        int skipped;
        int errors;
        String firstError;

        void addError(String message) {
            errors++;
            if (firstError == null) {
                firstError = message;
            }
            android.util.Log.e("AutoUsbCopier", message);
            DebugLog.log(CopyService.this, "ERROR: A copy operation encountered an issue.");
        }
    }

    private static final class CopyCancelledException extends Exception {
    }

    private static final class StorageDisconnectedException extends IOException {
        StorageDisconnectedException(String message) {
            super(message);
        }
    }
}
