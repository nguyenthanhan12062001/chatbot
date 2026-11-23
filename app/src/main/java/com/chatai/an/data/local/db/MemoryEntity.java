package com.chatai.an.data.local.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "memories")
public class MemoryEntity {
    @PrimaryKey
    @NonNull
    public String id;

    @NonNull
    public String category;

    @NonNull
    public String scope;

    @NonNull
    public String area;

    @ColumnInfo(name = "project_id")
    @NonNull
    public String projectId;

    @ColumnInfo(name = "conversation_id")
    public String conversationId;

    @NonNull
    public String content;

    public int importance;

    public boolean enabled;

    public boolean pinned;

    public boolean archived;

    @ColumnInfo(name = "merged_into_id")
    public String mergedIntoId;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @ColumnInfo(name = "last_used_at")
    public long lastUsedAt;

    @ColumnInfo(name = "usage_count")
    public int usageCount;

    @Ignore
    public MemoryEntity(
            @NonNull String id,
            @NonNull String category,
            String conversationId,
            @NonNull String content,
            boolean enabled,
            long createdAt,
            long updatedAt
    ) {
        this(id, category, "GLOBAL", "SHARED", "", conversationId, content, 3, enabled, false, false, null, createdAt, updatedAt, 0L, 0);
    }

    public MemoryEntity(
            @NonNull String id,
            @NonNull String category,
            @NonNull String scope,
            @NonNull String area,
            @NonNull String projectId,
            String conversationId,
            @NonNull String content,
            int importance,
            boolean enabled,
            boolean pinned,
            boolean archived,
            String mergedIntoId,
            long createdAt,
            long updatedAt,
            long lastUsedAt,
            int usageCount
    ) {
        this.id = id;
        this.category = category;
        this.scope = scope;
        this.area = area;
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.content = content;
        this.importance = importance;
        this.enabled = enabled;
        this.pinned = pinned;
        this.archived = archived;
        this.mergedIntoId = mergedIntoId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastUsedAt = lastUsedAt;
        this.usageCount = usageCount;
    }
}
