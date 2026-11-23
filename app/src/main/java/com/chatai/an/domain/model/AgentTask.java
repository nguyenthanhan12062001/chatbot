package com.chatai.an.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AgentTask {
    private final String id;
    private final String goal;
    private final String conversationId;
    private final String modelId;
    private final AgentTaskStatus status;
    private final List<AgentStep> steps;
    private final String finalResult;
    private final String errorMessage;
    private final long createdAt;
    private final long updatedAt;

    public AgentTask(
            String id,
            String goal,
            String conversationId,
            String modelId,
            AgentTaskStatus status,
            List<AgentStep> steps,
            String finalResult,
            String errorMessage,
            long createdAt,
            long updatedAt
    ) {
        this.id = id;
        this.goal = goal;
        this.conversationId = conversationId != null ? conversationId : "";
        this.modelId = modelId != null ? modelId : "";
        this.status = status != null ? status : AgentTaskStatus.ANALYZING;
        this.steps = steps == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(steps));
        this.finalResult = finalResult != null ? finalResult : "";
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getGoal() {
        return goal;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getModelId() {
        return modelId;
    }

    public AgentTaskStatus getStatus() {
        return status;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public String getFinalResult() {
        return finalResult;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }
}
