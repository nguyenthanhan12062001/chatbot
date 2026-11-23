package com.chatai.an.domain.model;

public class AgentStep {
    private final int stepNumber;
    private final String title;
    private final String detail;
    private final String toolName;
    private final long timestamp;

    public AgentStep(int stepNumber, String title, String detail, String toolName, long timestamp) {
        this.stepNumber = stepNumber;
        this.title = title;
        this.detail = detail != null ? detail : "";
        this.toolName = toolName != null ? toolName : "";
        this.timestamp = timestamp;
    }

    public int getStepNumber() {
        return stepNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public String getToolName() {
        return toolName;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
