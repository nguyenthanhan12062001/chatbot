package com.chatai.an.domain.model;

public class Attachment {
    private final String id;
    private final String messageId;
    private final String uriString;
    private final String localPath;
    private final String displayName;
    private final String mimeType;
    private final long sizeBytes;
    private final AttachmentType type;
    private final long createdAt;

    public Attachment(
            String id,
            String messageId,
            String uriString,
            String localPath,
            String displayName,
            String mimeType,
            long sizeBytes,
            AttachmentType type,
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

    public String getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getUriString() {
        return uriString;
    }

    public String getLocalPath() {
        return localPath;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public AttachmentType getType() {
        return type;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
