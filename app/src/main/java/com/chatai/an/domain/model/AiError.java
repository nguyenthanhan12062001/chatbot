package com.chatai.an.domain.model;

public class AiError {

    public enum ErrorType {
        AUTH_ERROR,
        QUOTA_EXCEEDED,
        RATE_LIMIT,
        NETWORK_ERROR,
        PROTOCOL_ERROR,
        CAPABILITY_UNSUPPORTED,
        MISSING_CREDENTIAL,
        UNAVAILABLE,
        CANCELLED,
        UNKNOWN
    }

    private final ErrorType type;
    private final String message;
    private final int httpCode;
    private final long retryAfterSeconds;
    private final String providerId;

    public AiError(ErrorType type, String message, int httpCode, long retryAfterSeconds, String providerId) {
        this.type = type != null ? type : ErrorType.UNKNOWN;
        this.message = message != null ? message : "";
        this.httpCode = httpCode;
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
        this.providerId = providerId != null ? providerId : "";
    }

    public ErrorType getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpCode() {
        return httpCode;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getProviderId() {
        return providerId;
    }

    public boolean isRetryableByKeyRotation() {
        return type == ErrorType.AUTH_ERROR
                || type == ErrorType.QUOTA_EXCEEDED
                || type == ErrorType.RATE_LIMIT;
    }

    public boolean isRetryableByTransientRetry() {
        return type == ErrorType.NETWORK_ERROR || (httpCode >= 500 && httpCode < 600);
    }

    public boolean isEligibleForFallback() {
        return type != ErrorType.CANCELLED && type != ErrorType.CAPABILITY_UNSUPPORTED;
    }
}
