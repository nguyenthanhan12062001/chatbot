package com.chatai.an.domain.model;

public class TokenUsage {
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;

    public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = Math.max(0, promptTokens);
        this.completionTokens = Math.max(0, completionTokens);
        this.totalTokens = totalTokens > 0 ? totalTokens : (this.promptTokens + this.completionTokens);
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public boolean hasAnyUsage() {
        return promptTokens > 0 || completionTokens > 0 || totalTokens > 0;
    }
}
