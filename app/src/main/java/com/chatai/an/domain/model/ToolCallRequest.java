package com.chatai.an.domain.model;

import com.google.gson.JsonObject;

/**
 * Unified representation of a tool call requested by any AI provider
 * (OpenAI tool_calls, Gemini functionCall, Anthropic tool_use, or structured <chatai_tool> tags).
 */
public class ToolCallRequest {
    private final String callId;
    private final String toolName;
    private final JsonObject arguments;
    private final boolean nativeProtocol;

    public ToolCallRequest(String callId, String toolName, JsonObject arguments, boolean nativeProtocol) {
        this.callId = callId != null ? callId : "";
        this.toolName = toolName != null ? toolName.trim() : "";
        this.arguments = arguments != null ? arguments : new JsonObject();
        this.nativeProtocol = nativeProtocol;
    }

    public String getCallId() {
        return callId;
    }

    public String getToolName() {
        return toolName;
    }

    public JsonObject getArguments() {
        return arguments;
    }

    public boolean isNativeProtocol() {
        return nativeProtocol;
    }
}
