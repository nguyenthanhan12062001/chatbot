package com.chatai.an.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiRequest {
    private final String requestId;
    private final String conversationId;
    private final AiModel model;
    private final String systemPrompt;
    private final List<Message> contextMessages;
    private final boolean deepThinkingEnabled;
    private final boolean streamingEnabled;
    private final List<ToolDefinition> tools;

    public AiRequest(
            String requestId,
            String conversationId,
            AiModel model,
            String systemPrompt,
            List<Message> contextMessages,
            boolean deepThinkingEnabled,
            boolean streamingEnabled
    ) {
        this(requestId, conversationId, model, systemPrompt, contextMessages, deepThinkingEnabled, streamingEnabled, Collections.emptyList());
    }

    public AiRequest(
            String requestId,
            String conversationId,
            AiModel model,
            String systemPrompt,
            List<Message> contextMessages,
            boolean deepThinkingEnabled,
            boolean streamingEnabled,
            List<ToolDefinition> tools
    ) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.model = model;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.contextMessages = contextMessages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(contextMessages));
        this.deepThinkingEnabled = deepThinkingEnabled;
        this.streamingEnabled = streamingEnabled;
        this.tools = tools == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tools));
    }

    public String getRequestId() {
        return requestId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public AiModel getModel() {
        return model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<Message> getContextMessages() {
        return contextMessages;
    }

    public boolean isDeepThinkingEnabled() {
        return deepThinkingEnabled;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }
}
