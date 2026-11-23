package com.chatai.an.domain.model;

/**
 * Inspectable live or persisted tool execution record displayed inline inside chat messages.
 */
public class ToolCallRecord {
    public enum Status {
        WAITING_APPROVAL,
        RUNNING,
        COMPLETED,
        FAILED,
        DENIED,
        CANCELLED
    }

    private final String executionId;
    private final String toolName;
    private final String privilegeBadge;
    private final Status status;
    private final String reason;
    private final String argsPreview;
    private final String outputPreview;
    private final String errorMessage;
    private final long startedAt;
    private final long durationMs;

    public ToolCallRecord(
            String executionId,
            String toolName,
            String privilegeBadge,
            Status status,
            String reason,
            String argsPreview,
            String outputPreview,
            String errorMessage,
            long startedAt,
            long durationMs
    ) {
        this.executionId = executionId != null ? executionId : "";
        this.toolName = toolName != null ? toolName : "";
        this.privilegeBadge = privilegeBadge != null ? privilegeBadge : "";
        this.status = status != null ? status : Status.RUNNING;
        this.reason = reason != null ? reason : "";
        this.argsPreview = argsPreview != null ? argsPreview : "";
        this.outputPreview = outputPreview != null ? outputPreview : "";
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.startedAt = startedAt;
        this.durationMs = Math.max(0L, durationMs);
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getPrivilegeBadge() {
        return privilegeBadge;
    }

    public Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getArgsPreview() {
        return argsPreview;
    }

    public String getOutputPreview() {
        return outputPreview;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public ToolCallRecord withUpdated(Status nextStatus, String nextOutput, String nextError, long nextDurationMs) {
        return new ToolCallRecord(
                executionId,
                toolName,
                privilegeBadge,
                nextStatus,
                reason,
                argsPreview,
                nextOutput != null ? nextOutput : outputPreview,
                nextError != null ? nextError : errorMessage,
                startedAt,
                nextDurationMs
        );
    }
}
