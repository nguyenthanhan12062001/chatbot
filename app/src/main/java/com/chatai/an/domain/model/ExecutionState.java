package com.chatai.an.domain.model;

public enum ExecutionState {
    IDLE,
    PREPARING,
    CONNECTING,
    THINKING,
    STREAMING,
    EXECUTING_TOOL,
    COMPLETED,
    FAILED,
    CANCELLED
}
