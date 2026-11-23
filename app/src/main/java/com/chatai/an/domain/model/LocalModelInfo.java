package com.chatai.an.domain.model;

public class LocalModelInfo {
    public enum RuntimeStatus {
        IMPORTED_NO_NATIVE_RUNTIME,
        COMPATIBLE_SERVER_LINKED,
        INSUFFICIENT_RAM
    }

    private final String id;
    private final String displayName;
    private final String format;
    private final String uriString;
    private final long fileSizeBytes;
    private final long estimatedRamMb;
    private final RuntimeStatus runtimeStatus;
    private final long importedAt;

    public LocalModelInfo(
            String id,
            String displayName,
            String format,
            String uriString,
            long fileSizeBytes,
            long estimatedRamMb,
            RuntimeStatus runtimeStatus,
            long importedAt
    ) {
        this.id = id;
        this.displayName = displayName;
        this.format = format;
        this.uriString = uriString;
        this.fileSizeBytes = fileSizeBytes;
        this.estimatedRamMb = estimatedRamMb;
        this.runtimeStatus = runtimeStatus;
        this.importedAt = importedAt;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFormat() {
        return format;
    }

    public String getUriString() {
        return uriString;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public long getEstimatedRamMb() {
        return estimatedRamMb;
    }

    public RuntimeStatus getRuntimeStatus() {
        return runtimeStatus;
    }

    public long getImportedAt() {
        return importedAt;
    }
}
