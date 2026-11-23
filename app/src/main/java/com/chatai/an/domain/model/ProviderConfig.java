package com.chatai.an.domain.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ProviderConfig {
    private final String providerId;
    private final String displayName;
    private final ModelProvider providerType;
    private final ProtocolType protocolType;
    private final String baseUrl;
    private final boolean enabled;
    private final boolean keyRotationEnabled;
    private final String fallbackModelId;
    private final Map<String, String> customHeaders;
    private final String lastConnectionStatus;
    private final long lastTestedAt;

    public ProviderConfig(
            String providerId,
            String displayName,
            ModelProvider providerType,
            ProtocolType protocolType,
            String baseUrl,
            boolean enabled,
            boolean keyRotationEnabled,
            String fallbackModelId,
            Map<String, String> customHeaders,
            String lastConnectionStatus,
            long lastTestedAt
    ) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.providerType = providerType;
        this.protocolType = protocolType;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.keyRotationEnabled = keyRotationEnabled;
        this.fallbackModelId = fallbackModelId != null ? fallbackModelId : "";
        this.customHeaders = customHeaders == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(customHeaders));
        this.lastConnectionStatus = lastConnectionStatus != null ? lastConnectionStatus : "";
        this.lastTestedAt = lastTestedAt;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ModelProvider getProviderType() {
        return providerType;
    }

    public ProtocolType getProtocolType() {
        return protocolType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isKeyRotationEnabled() {
        return keyRotationEnabled;
    }

    public String getFallbackModelId() {
        return fallbackModelId;
    }

    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }

    public String getLastConnectionStatus() {
        return lastConnectionStatus;
    }

    public long getLastTestedAt() {
        return lastTestedAt;
    }
}
