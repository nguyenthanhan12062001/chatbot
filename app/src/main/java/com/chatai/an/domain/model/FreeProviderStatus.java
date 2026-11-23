package com.chatai.an.domain.model;

public class FreeProviderStatus {
    public enum State {
        AVAILABLE,
        RATE_LIMITED,
        QUOTA_EXCEEDED,
        TEMPORARILY_UNAVAILABLE
    }

    private final ModelProvider provider;
    private final State state;
    private final String statusMessage;
    private final long retryAtMillis;
    private final int modelCount;
    private final long lastUpdatedAt;

    public FreeProviderStatus(
            ModelProvider provider,
            State state,
            String statusMessage,
            long retryAtMillis,
            int modelCount,
            long lastUpdatedAt
    ) {
        this.provider = provider;
        this.state = state != null ? state : State.AVAILABLE;
        this.statusMessage = statusMessage != null ? statusMessage : "";
        this.retryAtMillis = retryAtMillis;
        this.modelCount = modelCount;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public ModelProvider getProvider() {
        return provider;
    }

    public State getState() {
        return state;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public long getRetryAtMillis() {
        return retryAtMillis;
    }

    public int getModelCount() {
        return modelCount;
    }

    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }
}
