package com.chatai.an.core;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.AttachmentType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

public final class AttachmentStorageHelper {
    private static final String TAG = "AttachmentStorage";
    private static final String DIR_ATTACHMENTS = "attachments";

    private AttachmentStorageHelper() {
    }

    public static File createCameraOutputFile(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), DIR_ATTACHMENTS);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create attachments directory");
        }
        return new File(dir, "cam_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6) + ".jpg");
    }

    public static Uri getUriForFile(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    public static Attachment createAttachmentFromCameraFile(Context context, File cameraFile) {
        Uri uri = getUriForFile(context, cameraFile);
        long size = cameraFile.exists() ? cameraFile.length() : 0L;
        return new Attachment(
                UUID.randomUUID().toString(),
                "",
                uri.toString(),
                cameraFile.getAbsolutePath(),
                cameraFile.getName(),
                "image/jpeg",
                size,
                AttachmentType.IMAGE,
                System.currentTimeMillis()
        );
    }

    public static Attachment importUriToLocalStorage(Context context, Uri sourceUri, AttachmentType preferredType) {
        ContentResolver resolver = context.getContentResolver();
        String mimeType = resolver.getType(sourceUri);
        String displayName = queryDisplayName(resolver, sourceUri);
        long sizeBytes = queryFileSize(resolver, sourceUri);

        if (displayName == null || displayName.trim().isEmpty()) {
            String ext = mimeType != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) : null;
            displayName = (preferredType == AttachmentType.IMAGE ? "photo_" : "file_")
                    + System.currentTimeMillis()
                    + (ext != null ? "." + ext : "");
        }

        AttachmentType resolvedType = preferredType;
        if (mimeType != null && mimeType.startsWith("image/")) {
            resolvedType = AttachmentType.IMAGE;
        }

        String localPath = null;
        File dir = new File(context.getFilesDir(), DIR_ATTACHMENTS);
        if (dir.exists() || dir.mkdirs()) {
            String safeName = UUID.randomUUID().toString().substring(0, 8) + "_" + sanitizeFilename(displayName);
            File dest = new File(dir, safeName);
            try (InputStream in = resolver.openInputStream(sourceUri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in != null) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long total = 0L;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        total += read;
                    }
                    localPath = dest.getAbsolutePath();
                    if (sizeBytes <= 0) {
                        sizeBytes = total;
                    }
                }
            } catch (Exception e) {
                AppLogger.e(TAG, "Failed to copy attachment locally, keeping URI reference", e);
            }
        }

        String finalUri = localPath != null
                ? getUriForFile(context, new File(localPath)).toString()
                : sourceUri.toString();

        return new Attachment(
                UUID.randomUUID().toString(),
                "",
                finalUri,
                localPath,
                displayName,
                mimeType != null ? mimeType : "application/octet-stream",
                sizeBytes,
                resolvedType,
                System.currentTimeMillis()
        );
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.getDefault(), "%.1f MB", mb);
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "Unable to query display name: " + e.getMessage());
        }
        return uri.getLastPathSegment();
    }

    private static long queryFileSize(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    return cursor.getLong(idx);
                }
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "Unable to query file size: " + e.getMessage());
        }
        return 0L;
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
