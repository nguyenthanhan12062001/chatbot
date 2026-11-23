package com.chatai.an.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiResponse {
    private final String content;
    private final String thinkingContent;
    private final TokenUsage tokenUsage;
    private final String modelId;
    private final String providerId;
    private final List<ToolCallRequest> toolCalls;
    private final List<ToolCallRecord> toolRecords;
    private final List<String> followUpSuggestions;
    private final List<TimelineEntry> timelineEntries;
    private final List<SourceItem> sources;

    public AiResponse(
            String content,
            String thinkingContent,
            TokenUsage tokenUsage,
            String modelId,
            String providerId
    ) {
        this(content, thinkingContent, tokenUsage, modelId, providerId, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public AiResponse(
            String content,
            String thinkingContent,
            TokenUsage tokenUsage,
            String modelId,
            String providerId,
            List<ToolCallRequest> toolCalls
    ) {
        this(content, thinkingContent, tokenUsage, modelId, providerId, toolCalls, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public AiResponse(
            String content,
            String thinkingContent,
            TokenUsage tokenUsage,
            String modelId,
            String providerId,
            List<ToolCallRequest> toolCalls,
            List<ToolCallRecord> toolRecords,
            List<String> followUpSuggestions
    ) {
        this(content, thinkingContent, tokenUsage, modelId, providerId, toolCalls, toolRecords, followUpSuggestions, Collections.emptyList(), Collections.emptyList());
    }

    public AiResponse(
            String content,
            String thinkingContent,
            TokenUsage tokenUsage,
            String modelId,
            String providerId,
            List<ToolCallRequest> toolCalls,
            List<ToolCallRecord> toolRecords,
            List<String> followUpSuggestions,
            List<TimelineEntry> timelineEntries,
            List<SourceItem> sources
    ) {
        this.content = content != null ? content : "";
        this.thinkingContent = thinkingContent != null ? thinkingContent : "";
        this.tokenUsage = tokenUsage;
        this.modelId = modelId;
        this.providerId = providerId;
        this.toolCalls = toolCalls == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
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

    public String getContent() {
        return content;
    }

    public String getThinkingContent() {
        return thinkingContent;
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public String getModelId() {
        return modelId;
    }

    public String getProviderId() {
        return providerId;
    }

    public List<ToolCallRequest> getToolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
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
