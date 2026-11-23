package com.chatai.an.domain.model;

public enum AgentTaskStatus {
    ANALYZING,
    PLANNING,
    EXECUTING_TOOL,
    WAITING_CONFIRMATION,
    SYNTHESIZING,
    COMPLETED,
    FAILED,
    CANCELLED
}
