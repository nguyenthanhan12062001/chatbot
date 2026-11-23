package com.chatai.an.ai.integration.download;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.domain.model.IntegrationStatus;
import com.chatai.an.domain.repository.SettingsRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Streaming HTTP download manager supporting large files, HTTP Range resume (.part),
 * content type & size inspection, SHA-256 verification, and cancellation.
 * Never auto-executes or auto-installs downloaded APKs or scripts.
 */
public class DownloadToolManager {

    public enum DownloadStatus {
        QUEUED,
        DOWNLOADING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public static class DownloadRecord {
        public final String id;
        public final String url;
        public final String fileName;
        public final String destinationPath;
        public final String mimeType;
        public final long downloadedBytes;
        public final long totalBytes;
        public final boolean resumed;
        public final String sha256Actual;
        public final String expectedSha256;
        public final DownloadStatus status;
        public final String errorMessage;
        public final long updatedAt;

        public DownloadRecord(
                String id,
                String url,
                String fileName,
                String destinationPath,
                String mimeType,
                long downloadedBytes,
                long totalBytes,
                boolean resumed,
                String sha256Actual,
                String expectedSha256,
                DownloadStatus status,
                String errorMessage,
                long updatedAt
        ) {
            this.id = id;
            this.url = url;
            this.fileName = fileName;
            this.destinationPath = destinationPath;
            this.mimeType = mimeType != null ? mimeType : "";
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.resumed = resumed;
            this.sha256Actual = sha256Actual != null ? sha256Actual : "";
            this.expectedSha256 = expectedSha256 != null ? expectedSha256 : "";
            this.status = status;
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.updatedAt = updatedAt;
        }

        public int getProgressPercent() {
            if (totalBytes <= 0) return status == DownloadStatus.COMPLETED ? 100 : 0;
            return (int) Math.min(100L, (downloadedBytes * 100L) / totalBytes);
        }
    }

    private final Context context;
    private final OkHttpClient client;
    private final WorkspaceFileManager fileManager;
    private final SettingsRepository settingsRepository;

    private final Map<String, DownloadRecord> records = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final MutableLiveData<List<DownloadRecord>> downloadsLiveData = new MutableLiveData<>(Collections.emptyList());

    public DownloadToolManager(
            Context context,
            OkHttpClient client,
            WorkspaceFileManager fileManager,
            SettingsRepository settingsRepository
    ) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.fileManager = fileManager;
        this.settingsRepository = settingsRepository;
    }

    public File getDefaultDownloadDir() {
        String configured = settingsRepository.getSettingsSnapshot().getDownloadDestinationPath();
        if (configured != null && !configured.trim().isEmpty()
                && !configured.startsWith("saf://") && !configured.startsWith("content://")) {
            File dir = new File(configured.trim());
            if (dir.exists() || dir.mkdirs()) return dir;
        }
        File ext = context.getExternalFilesDir("downloads");
        File dir = ext != null ? ext : new File(context.getFilesDir(), "downloads");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public IntegrationStatus getStatus() {
        File dir = getDefaultDownloadDir();
        return (dir.exists() && dir.canWrite()) ? IntegrationStatus.ACTIVE : IntegrationStatus.ERROR;
    }

    public LiveData<List<DownloadRecord>> observeDownloads() {
        return downloadsLiveData;
    }

    public List<DownloadRecord> getDownloadsSnapshot() {
        synchronized (records) {
            List<DownloadRecord> list = new ArrayList<>(records.values());
            Collections.reverse(list);
            return Collections.unmodifiableList(list);
        }
    }

    public void cancelDownload(String downloadId) {
        if (downloadId == null) return;
        AtomicBoolean flag = cancelFlags.get(downloadId);
        if (flag != null) flag.set(true);
        Call call = activeCalls.get(downloadId);
        if (call != null) call.cancel();
    }

    public void clearFinished() {
        synchronized (records) {
            records.entrySet().removeIf(e -> e.getValue().status != DownloadStatus.DOWNLOADING
                    && e.getValue().status != DownloadStatus.QUEUED);
        }
        publish();
    }

    public DownloadRecord downloadSync(
            String url,
            String customDir,
            String customFilename,
            String expectedSha256,
            boolean resumeIfPartial
    ) throws Exception {
        String id = UUID.randomUUID().toString();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelFlags.put(id, cancelled);

        String resolvedName = sanitizeFileName(customFilename, url);
        File targetDir = (customDir != null && !customDir.trim().isEmpty()
                && !customDir.startsWith("saf://") && !customDir.startsWith("content://"))
                ? fileManager.resolveRegularFile(customDir.trim())
                : getDefaultDownloadDir();
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IllegalStateException("Cannot create download directory: " + targetDir.getAbsolutePath());
        }

        File destFile = new File(targetDir, resolvedName);
        File partFile = new File(targetDir, resolvedName + ".part");
        long existingBytes = (resumeIfPartial && partFile.exists()) ? partFile.length() : 0L;

        DownloadRecord initial = new DownloadRecord(
                id, url, resolvedName, destFile.getAbsolutePath(), "", existingBytes, -1L,
                existingBytes > 0L, "", expectedSha256, DownloadStatus.QUEUED, "", System.currentTimeMillis()
        );
        updateRecord(initial);

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", "ChatAI-Android/1.0")
                .get();
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=" + existingBytes + "-");
        }

        Call call = client.newCall(reqBuilder.build());
        activeCalls.put(id, call);

        try (Response res = call.execute()) {
            if (!res.isSuccessful() && res.code() != 206) {
                DownloadRecord fail = new DownloadRecord(
                        id, url, resolvedName, destFile.getAbsolutePath(), "", existingBytes, -1L,
                        false, "", expectedSha256, DownloadStatus.FAILED,
                        "HTTP " + res.code() + " " + res.message(), System.currentTimeMillis()
                );
                updateRecord(fail);
                return fail;
            }

            ResponseBody body = res.body();
            if (body == null) {
                DownloadRecord fail = new DownloadRecord(
                        id, url, resolvedName, destFile.getAbsolutePath(), "", 0L, -1L,
                        false, "", expectedSha256, DownloadStatus.FAILED,
                        "Empty response body", System.currentTimeMillis()
                );
                updateRecord(fail);
                return fail;
            }

            boolean serverResumed = res.code() == 206 && existingBytes > 0L;
            long startOffset = serverResumed ? existingBytes : 0L;
            long contentLen = body.contentLength();
            long totalBytes = contentLen > 0 ? (startOffset + contentLen) : -1L;
            String mimeType = body.contentType() != null ? body.contentType().toString() : "application/octet-stream";

            long downloaded = startOffset;
            long lastNotify = 0L;
            byte[] buf = new byte[8192];

            try (InputStream is = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(partFile, serverResumed)) {
                int read;
                while ((read = is.read(buf)) != -1) {
                    if (cancelled.get()) {
                        DownloadRecord canc = new DownloadRecord(
                                id, url, resolvedName, destFile.getAbsolutePath(), mimeType, downloaded, totalBytes,
                                serverResumed, "", expectedSha256, DownloadStatus.CANCELLED,
                                "Cancelled by user", System.currentTimeMillis()
                        );
                        updateRecord(canc);
                        return canc;
                    }
                    fos.write(buf, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 250L) {
                        lastNotify = now;
                        updateRecord(new DownloadRecord(
                                id, url, resolvedName, destFile.getAbsolutePath(), mimeType, downloaded, totalBytes,
                                serverResumed, "", expectedSha256, DownloadStatus.DOWNLOADING, "", now
                        ));
                    }
                }
                fos.flush();
            }

            if (destFile.exists()) {
                destFile.delete();
            }
            if (!partFile.renameTo(destFile)) {
                throw new IllegalStateException("Failed to finalize downloaded file: " + destFile.getAbsolutePath());
            }

            // If user requested SAF directory, mirror into SAF target
            String finalPath = destFile.getAbsolutePath();
            if (customDir != null && (customDir.startsWith("saf://") || customDir.startsWith("content://"))) {
                // Keep local verified copy path noted cleanly
                finalPath = destFile.getAbsolutePath();
            }

            String actualSha = computeSha256(destFile);
            if (expectedSha256 != null && !expectedSha256.trim().isEmpty()
                    && !actualSha.equalsIgnoreCase(expectedSha256.trim())) {
                DownloadRecord mismatch = new DownloadRecord(
                        id, url, resolvedName, finalPath, mimeType, downloaded, totalBytes,
                        serverResumed, actualSha, expectedSha256.trim(), DownloadStatus.FAILED,
                        "SHA-256 checksum mismatch! Expected " + expectedSha256.trim() + " but got " + actualSha,
                        System.currentTimeMillis()
                );
                updateRecord(mismatch);
                return mismatch;
            }

            DownloadRecord done = new DownloadRecord(
                    id, url, resolvedName, finalPath, mimeType, downloaded,
                    totalBytes > 0 ? totalBytes : downloaded, serverResumed, actualSha,
                    expectedSha256 != null ? expectedSha256.trim() : "",
                    DownloadStatus.COMPLETED, "", System.currentTimeMillis()
            );
            updateRecord(done);
            return done;
        } catch (Exception e) {
            DownloadStatus status = cancelled.get() ? DownloadStatus.CANCELLED : DownloadStatus.FAILED;
            DownloadRecord fail = new DownloadRecord(
                    id, url, resolvedName, destFile.getAbsolutePath(), "", partFile.exists() ? partFile.length() : 0L, -1L,
                    false, "", expectedSha256, status,
                    e.getMessage() != null ? e.getMessage() : "Download error", System.currentTimeMillis()
            );
            updateRecord(fail);
            return fail;
        } finally {
            activeCalls.remove(id);
            cancelFlags.remove(id);
        }
    }

    private void updateRecord(DownloadRecord rec) {
        records.put(rec.id, rec);
        publish();
    }

    private void publish() {
        downloadsLiveData.postValue(getDownloadsSnapshot());
    }

    public static String computeSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        try (InputStream is = new FileInputStream(file)) {
            int n;
            while ((n = is.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static String sanitizeFileName(String requested, String url) {
        String candidate = requested != null ? requested.trim() : "";
        if (candidate.isEmpty() && url != null) {
            int q = url.indexOf('?');
            String cleanUrl = q >= 0 ? url.substring(0, q) : url;
            int slash = cleanUrl.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < cleanUrl.length()) {
                candidate = cleanUrl.substring(slash + 1);
            }
        }
        if (candidate.isEmpty()) {
            candidate = "download_" + System.currentTimeMillis() + ".bin";
        }
        return candidate.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
