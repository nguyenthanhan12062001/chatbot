package com.chatai.an.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Message {
    private final String id;
    private final String conversationId;
    private final MessageRole role;
    private final String content;
    private final String thinkingContent;
    private final String modelId;
    private final String providerId;
    private final AccessType accessType;
    private final boolean deepThinkingEnabled;
    private final TokenUsage tokenUsage;
    private final boolean error;
    private final long createdAt;
    private final long updatedAt;
    private final List<Attachment> attachments;
    private final List<ToolCallRecord> toolRecords;
    private final List<String> followUpSuggestions;
    private final List<TimelineEntry> timelineEntries;
    private final List<SourceItem> sources;

    public Message(
            String id,
            String conversationId,
            MessageRole role,
            String content,
            String modelId,
            boolean deepThinkingEnabled,
            long createdAt,
            long updatedAt,
            List<Attachment> attachments
    ) {
        this(
                id,
                conversationId,
                role,
                content,
                "",
                modelId,
                "",
                null,
                deepThinkingEnabled,
                null,
                false,
                createdAt,
                updatedAt,
                attachments,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    public Message(
            String id,
            String conversationId,
            MessageRole role,
            String content,
            String thinkingContent,
            String modelId,
            String providerId,
            AccessType accessType,
            boolean deepThinkingEnabled,
            TokenUsage tokenUsage,
            boolean error,
            long createdAt,
            long updatedAt,
            List<Attachment> attachments
    ) {
        this(
                id,
                conversationId,
                role,
                content,
                thinkingContent,
                modelId,
                providerId,
                accessType,
                deepThinkingEnabled,
                tokenUsage,
                error,
                createdAt,
                updatedAt,
                attachments,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    public Message(
            String id,
            String conversationId,
            MessageRole role,
            String content,
            String thinkingContent,
            String modelId,
            String providerId,
            AccessType accessType,
            boolean deepThinkingEnabled,
            TokenUsage tokenUsage,
            boolean error,
            long createdAt,
            long updatedAt,
            List<Attachment> attachments,
            List<ToolCallRecord> toolRecords,
            List<String> followUpSuggestions
    ) {
        this(
                id,
                conversationId,
                role,
                content,
                thinkingContent,
                modelId,
                providerId,
                accessType,
                deepThinkingEnabled,
                tokenUsage,
                error,
                createdAt,
                updatedAt,
                attachments,
                toolRecords,
                followUpSuggestions,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    public Message(
            String id,
            String conversationId,
            MessageRole role,
            String content,
            String thinkingContent,
            String modelId,
            String providerId,
            AccessType accessType,
            boolean deepThinkingEnabled,
            TokenUsage tokenUsage,
            boolean error,
            long createdAt,
            long updatedAt,
            List<Attachment> attachments,
            List<ToolCallRecord> toolRecords,
            List<String> followUpSuggestions,
            List<TimelineEntry> timelineEntries,
            List<SourceItem> sources
    ) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content != null ? content : "";
        this.thinkingContent = thinkingContent != null ? thinkingContent : "";
        this.modelId = modelId != null ? modelId : "";
        this.providerId = providerId != null ? providerId : "";
        this.accessType = accessType;
        this.deepThinkingEnabled = deepThinkingEnabled;
        this.tokenUsage = tokenUsage;
        this.error = error;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.attachments = attachments == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(attachments));
        this.toolRecords = toolRecords == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(toolRecords));
        this.followUpSuggestions = followUpSuggestions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(followUpSuggestions));
        this.timelineEntries = timelineEntries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(timelineEntries));
        this.sources = sources == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(sources));
    }

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getThinkingContent() {
        return thinkingContent;
    }

    public String getModelId() {
        return modelId;
    }

    public String getProviderId() {
        return providerId;
    }

    public AccessType getAccessType() {
        return accessType;
    }

    public boolean isDeepThinkingEnabled() {
        return deepThinkingEnabled;
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public boolean isError() {
        return error;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public List<ToolCallRecord> getToolRecords() {
        return toolRecords;
    }

    public List<String> getFollowUpSuggestions() {
        return followUpSuggestions;
    }

    public List<TimelineEntry> getTimelineEntries() {
        return timelineEntries;
    }

    public List<SourceItem> getSources() {
        return sources;
    }
}
