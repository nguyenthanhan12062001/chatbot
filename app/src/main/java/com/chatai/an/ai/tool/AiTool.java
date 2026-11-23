package com.chatai.an.ai.tool;

import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

public interface AiTool {
    ToolDefinition getDefinition();

    ToolResult execute(JsonObject arguments);
}
