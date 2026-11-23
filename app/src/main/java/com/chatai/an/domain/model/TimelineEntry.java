package com.chatai.an.domain.model;

/**
 * Single chronological activity entry in an assistant turn timeline:
 * THINKING -> TOOL_CALL -> COMMENTARY -> ... -> RESPONSE -> SOURCES.
 */
public class TimelineEntry {
    public enum Type {
        THINKING,
        COMMENTARY,
        TOOL_CALL,
        SOURCES,
        RESPONSE
    }

    private final String id;
    private final Type type;
    private final String title;
    private final String content;
    private final String toolExecutionId;
    private final long timestampMs;

    public TimelineEntry(
            String id,
            Type type,
            String title,
            String content,
            String toolExecutionId,
            long timestampMs
    ) {
        this.id = id != null ? id : "";
        this.type = type != null ? type : Type.COMMENTARY;
        this.title = title != null ? title : "";
        this.content = content != null ? content : "";
        this.toolExecutionId = toolExecutionId != null ? toolExecutionId : "";
        this.timestampMs = timestampMs;
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getToolExecutionId() {
        return toolExecutionId;
    }

    public long getTimestampMs() {
        return timestampMs;
    }
}
