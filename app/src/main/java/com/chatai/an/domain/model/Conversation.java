package com.chatai.an.domain.model;

public class Conversation {
    private final String id;
    private final String title;
    private final String modelId;
    private final String projectId;
    private final String compactionSummary;
    private final long compactedUntil;
    private final long createdAt;
    private final long updatedAt;
    private final String lastMessagePreview;

    public Conversation(
            String id,
            String title,
            String modelId,
            long createdAt,
            long updatedAt,
            String lastMessagePreview
    ) {
        this(id, title, modelId, "", "", 0L, createdAt, updatedAt, lastMessagePreview);
    }

    public Conversation(
            String id,
            String title,
            String modelId,
            String projectId,
            String compactionSummary,
            long compactedUntil,
            long createdAt,
            long updatedAt,
            String lastMessagePreview
    ) {
        this.id = id;
        this.title = title;
        this.modelId = modelId;
        this.projectId = projectId != null ? projectId : "";
        this.compactionSummary = compactionSummary != null ? compactionSummary : "";
        this.compactedUntil = compactedUntil;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastMessagePreview = lastMessagePreview;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getModelId() {
        return modelId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getCompactionSummary() {
        return compactionSummary;
    }

    public long getCompactedUntil() {
        return compactedUntil;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }
}
