package com.chatai.an.domain.model;

/**
 * Public metadata view of an encrypted API Key stored in CredentialStore.
 * Never holds or exposes the raw secret key string.
 */
public class ApiKeyEntry {
    private final String id;
    private final String providerId;
    private final String label;
    private final String maskedPreview;
    private final boolean enabled;
    private final boolean active;
    private final String lastError;
    private final long cooldownUntil;
    private final long createdAt;

    public ApiKeyEntry(
            String id,
            String providerId,
            String label,
            String maskedPreview,
            boolean enabled,
            boolean active,
            String lastError,
            long cooldownUntil,
            long createdAt
    ) {
        this.id = id;
        this.providerId = providerId;
        this.label = label;
        this.maskedPreview = maskedPreview;
        this.enabled = enabled;
        this.active = active;
        this.lastError = lastError != null ? lastError : "";
        this.cooldownUntil = cooldownUntil;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getLabel() {
        return label;
    }

    public String getMaskedPreview() {
        return maskedPreview;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isActive() {
        return active;
    }

    public String getLastError() {
        return lastError;
    }

    public long getCooldownUntil() {
        return cooldownUntil;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isInCooldown() {
        return cooldownUntil > System.currentTimeMillis();
    }
}
