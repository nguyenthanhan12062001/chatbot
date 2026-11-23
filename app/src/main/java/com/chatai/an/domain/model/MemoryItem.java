package com.chatai.an.domain.model;

public class MemoryItem {
    private final String id;
    private final MemoryCategory category;
    private final MemoryScope scope;
    private final MemoryArea area;
    private final String projectId;
    private final String conversationId;
    private final String content;
    private final int importance;
    private final boolean enabled;
    private final boolean pinned;
    private final boolean archived;
    private final String mergedIntoId;
    private final long createdAt;
    private final long updatedAt;
    private final long lastUsedAt;
    private final int usageCount;

    public MemoryItem(
            String id,
            MemoryCategory category,
            String conversationId,
            String content,
            boolean enabled,
            long createdAt,
            long updatedAt
    ) {
        this(
                id,
                category,
                MemoryScope.GLOBAL,
                MemoryArea.SHARED,
                "",
                conversationId,
                content,
                3,
                enabled,
                false,
                false,
                null,
                createdAt,
                updatedAt,
                0L,
                0
        );
    }

    public MemoryItem(
            String id,
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String conversationId,
            String content,
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
        this.category = category != null ? category : MemoryCategory.FACT;
        this.scope = scope != null ? scope : MemoryScope.GLOBAL;
        this.area = area != null ? area : MemoryArea.SHARED;
        this.projectId = projectId != null ? projectId.trim() : "";
        this.conversationId = conversationId;
        this.content = content != null ? content : "";
        this.importance = Math.max(1, Math.min(5, importance));
        this.enabled = enabled;
        this.pinned = pinned;
        this.archived = archived;
        this.mergedIntoId = mergedIntoId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastUsedAt = lastUsedAt;
        this.usageCount = Math.max(0, usageCount);
    }

    public String getId() {
        return id;
    }

    public MemoryCategory getCategory() {
        return category;
    }

    public MemoryScope getScope() {
        return scope;
    }

    public MemoryArea getArea() {
        return area;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getContent() {
        return content;
    }

    public int getImportance() {
        return importance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPinned() {
        return pinned;
    }

    public boolean isArchived() {
        return archived;
    }

    public String getMergedIntoId() {
        return mergedIntoId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public long getLastUsedAt() {
        return lastUsedAt;
    }

    public int getUsageCount() {
        return usageCount;
    }
}
