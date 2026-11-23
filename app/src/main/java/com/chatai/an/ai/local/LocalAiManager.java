package com.chatai.an.ai.local;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.chatai.an.domain.model.DeviceCompatibility;
import com.chatai.an.domain.model.LocalModelInfo;
import com.chatai.an.domain.repository.LocalModelRepository;

import java.util.UUID;

public class LocalAiManager {
    private final Context context;
    private final DeviceCapabilityChecker capabilityChecker;
    private final LocalModelRepository repository;

    public LocalAiManager(
            Context context,
            DeviceCapabilityChecker capabilityChecker,
            LocalModelRepository repository
    ) {
        this.context = context.getApplicationContext();
        this.capabilityChecker = capabilityChecker;
        this.repository = repository;
    }

    public DeviceCompatibility inspectDevice() {
        return capabilityChecker.inspect();
    }

    public LocalModelInfo importModelFromUri(Uri uri) {
        if (uri == null) return null;
        String displayName = "local-model.gguf";
        long sizeBytes = 0L;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) {
                    String n = cursor.getString(nameIdx);
                    if (n != null && !n.trim().isEmpty()) displayName = n.trim();
                }
                if (sizeIdx >= 0) {
                    sizeBytes = Math.max(0L, cursor.getLong(sizeIdx));
                }
            }
        } catch (Exception ignored) {
        }

        String lower = displayName.toLowerCase();
        String format = lower.endsWith(".tflite") ? "TFLite"
                : (lower.endsWith(".task") ? "MediaPipe Task"
                : (lower.endsWith(".onnx") ? "ONNX" : "GGUF"));

        long fileMb = Math.max(64L, sizeBytes / (1024L * 1024L));
        long estimatedRamMb = Math.round(fileMb * 1.25f);
        DeviceCompatibility compat = capabilityChecker.inspect();
        LocalModelInfo.RuntimeStatus status = compat.canFitModelMb(estimatedRamMb)
                ? LocalModelInfo.RuntimeStatus.IMPORTED_NO_NATIVE_RUNTIME
                : LocalModelInfo.RuntimeStatus.INSUFFICIENT_RAM;

        LocalModelInfo info = new LocalModelInfo(
                UUID.randomUUID().toString(),
                displayName,
                format,
                uri.toString(),
                sizeBytes,
                estimatedRamMb,
                status,
                System.currentTimeMillis()
        );
        repository.saveLocalModel(info);
        return info;
    }
}
