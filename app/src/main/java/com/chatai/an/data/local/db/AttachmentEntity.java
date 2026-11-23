package com.chatai.an.data.local.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "attachments",
        foreignKeys = @ForeignKey(
                entity = MessageEntity.class,
                parentColumns = "id",
                childColumns = "message_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("message_id")}
)
public class AttachmentEntity {
    @PrimaryKey
    @NonNull
    public String id;

    @ColumnInfo(name = "message_id")
    @NonNull
    public String messageId;

    @ColumnInfo(name = "uri_string")
    @NonNull
    public String uriString;

    @ColumnInfo(name = "local_path")
    @Nullable
    public String localPath;

    @ColumnInfo(name = "display_name")
    @NonNull
    public String displayName;

    @ColumnInfo(name = "mime_type")
    @NonNull
    public String mimeType;

    @ColumnInfo(name = "size_bytes")
    public long sizeBytes;

    @NonNull
    public String type;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public AttachmentEntity(
            @NonNull String id,
            @NonNull String messageId,
            @NonNull String uriString,
            @Nullable String localPath,
            @NonNull String displayName,
            @NonNull String mimeType,
            long sizeBytes,
            @NonNull String type,
            long createdAt
    ) {
        this.id = id;
        this.messageId = messageId;
        this.uriString = uriString;
        this.localPath = localPath;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.type = type;
        this.createdAt = createdAt;
    }
}
