package com.chatai.an.domain.model;

public class ToolResult {
    private final String toolName;
    private final boolean success;
    private final String output;
    private final String error;

    public ToolResult(String toolName, boolean success, String output, String error) {
        this.toolName = toolName;
        this.success = success;
        this.output = output != null ? output : "";
        this.error = error != null ? error : "";
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }
}
