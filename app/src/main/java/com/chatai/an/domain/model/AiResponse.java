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

    public AiResponse(
            String content,
            String thinkingContent,
            TokenUsage tokenUsage,
            String modelId,
            String providerId
    ) {
        this(content, thinkingContent, tokenUsage, modelId, providerId, Collections.emptyList());
    }

    public AiResponse(
            String content,
            String thinkingContent,
            TokenUsage tokenUsage,
            String modelId,
            String providerId,
            List<ToolCallRequest> toolCalls
    ) {
        this.content = content != null ? content : "";
        this.thinkingContent = thinkingContent != null ? thinkingContent : "";
        this.tokenUsage = tokenUsage;
        this.modelId = modelId;
        this.providerId = providerId;
        this.toolCalls = toolCalls == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
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
}
