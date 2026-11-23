package com.chatai.an.data.local.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "local_models")
public class LocalModelEntity {
    @PrimaryKey
    @NonNull
    public String id;

    @NonNull
    @ColumnInfo(name = "display_name")
    public String displayName;

    @NonNull
    public String format;

    @NonNull
    @ColumnInfo(name = "uri_string")
    public String uriString;

    @ColumnInfo(name = "file_size_bytes")
    public long fileSizeBytes;

    @ColumnInfo(name = "estimated_ram_mb")
    public long estimatedRamMb;

    @NonNull
    @ColumnInfo(name = "runtime_status")
    public String runtimeStatus;

    @ColumnInfo(name = "imported_at")
    public long importedAt;

    public LocalModelEntity(
            @NonNull String id,
            @NonNull String displayName,
            @NonNull String format,
            @NonNull String uriString,
            long fileSizeBytes,
            long estimatedRamMb,
            @NonNull String runtimeStatus,
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
}
