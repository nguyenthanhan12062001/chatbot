package com.chatai.an.data.local.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "conversations")
public class ConversationEntity {
    @PrimaryKey
    @NonNull
    public String id;

    @NonNull
    public String title;

    @ColumnInfo(name = "model_id")
    @NonNull
    public String modelId;

    @ColumnInfo(name = "project_id")
    @NonNull
    public String projectId;

    @ColumnInfo(name = "compaction_summary")
    @NonNull
    public String compactionSummary;

    @ColumnInfo(name = "compacted_until")
    public long compactedUntil;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @ColumnInfo(name = "last_message_preview")
    @NonNull
    public String lastMessagePreview;

    @Ignore
    public ConversationEntity(
            @NonNull String id,
            @NonNull String title,
            @NonNull String modelId,
            long createdAt,
            long updatedAt,
            @NonNull String lastMessagePreview
    ) {
        this(id, title, modelId, "", "", 0L, createdAt, updatedAt, lastMessagePreview);
    }

    public ConversationEntity(
            @NonNull String id,
            @NonNull String title,
            @NonNull String modelId,
            @NonNull String projectId,
            @NonNull String compactionSummary,
            long compactedUntil,
            long createdAt,
            long updatedAt,
            @NonNull String lastMessagePreview
    ) {
        this.id = id;
        this.title = title;
        this.modelId = modelId;
        this.projectId = projectId;
        this.compactionSummary = compactionSummary;
        this.compactedUntil = compactedUntil;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastMessagePreview = lastMessagePreview;
    }
}
