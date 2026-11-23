package com.chatai.an.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class AiModel {
    public enum UsabilityStatus {
        USABLE,
        NEEDS_API_KEY,
        RATE_LIMITED,
        QUOTA_EXCEEDED,
        UNAVAILABLE,
        NO_LOCAL_RUNTIME
    }

    private final String id;
    private final String name;
    private final ModelProvider provider;
    private final ModelPricingType pricingType;
    private final AccessType accessType;
    private final ProtocolType protocolType;
    private final String description;
    private final String contextBadge;
    private final int contextWindowTokens;
    private final int outputLimitTokens;
    private final boolean available;
    private final UsabilityStatus usabilityStatus;
    private final String usabilityNote;
    private final String catalogSource;
    private final long updatedAt;
    private final Map<ModelCapability, CapabilityStatus> modelCapabilities;
    private final Map<ModelCapability, CapabilityStatus> providerCapabilities;

    public AiModel(
            String id,
            String name,
            ModelProvider provider,
            ModelPricingType pricingType,
            String description,
            String contextBadge,
            Map<ModelCapability, CapabilityStatus> capabilities
    ) {
        this(
                id,
                name,
                provider,
                pricingType,
                provider != null ? provider.getDefaultAccessType() : AccessType.FREE,
                provider != null ? provider.getDefaultProtocol() : ProtocolType.OPENAI_CHAT_COMPLETIONS,
                description,
                contextBadge,
                128000,
                8192,
                true,
                UsabilityStatus.USABLE,
                "",
                "builtin",
                System.currentTimeMillis(),
                capabilities,
                capabilities
        );
    }

    public AiModel(
            String id,
            String name,
            ModelProvider provider,
            ModelPricingType pricingType,
            AccessType accessType,
            ProtocolType protocolType,
            String description,
            String contextBadge,
            int contextWindowTokens,
            int outputLimitTokens,
            boolean available,
            String catalogSource,
            long updatedAt,
            Map<ModelCapability, CapabilityStatus> modelCapabilities,
            Map<ModelCapability, CapabilityStatus> providerCapabilities
    ) {
        this(
                id,
                name,
                provider,
                pricingType,
                accessType,
                protocolType,
                description,
                contextBadge,
                contextWindowTokens,
                outputLimitTokens,
                available,
                available ? UsabilityStatus.USABLE : UsabilityStatus.UNAVAILABLE,
                "",
                catalogSource,
                updatedAt,
                modelCapabilities,
                providerCapabilities
        );
    }

    public AiModel(
            String id,
            String name,
            ModelProvider provider,
            ModelPricingType pricingType,
            AccessType accessType,
            ProtocolType protocolType,
            String description,
            String contextBadge,
            int contextWindowTokens,
            int outputLimitTokens,
            boolean available,
            UsabilityStatus usabilityStatus,
            String usabilityNote,
            String catalogSource,
            long updatedAt,
            Map<ModelCapability, CapabilityStatus> modelCapabilities,
            Map<ModelCapability, CapabilityStatus> providerCapabilities
    ) {
        this.id = id;
        this.name = name;
        this.provider = provider;
        this.pricingType = pricingType;
        this.accessType = accessType != null ? accessType
                : (provider != null ? provider.getDefaultAccessType() : AccessType.FREE);
        this.protocolType = protocolType != null ? protocolType
                : (provider != null ? provider.getDefaultProtocol() : ProtocolType.OPENAI_CHAT_COMPLETIONS);
        this.description = description != null ? description : "";
        this.contextBadge = contextBadge != null ? contextBadge : "";
        this.contextWindowTokens = contextWindowTokens > 0 ? contextWindowTokens : 32000;
        this.outputLimitTokens = outputLimitTokens > 0 ? outputLimitTokens : 4096;
        this.available = available;
        this.usabilityStatus = usabilityStatus != null ? usabilityStatus
                : (available ? UsabilityStatus.USABLE : UsabilityStatus.UNAVAILABLE);
        this.usabilityNote = usabilityNote != null ? usabilityNote : "";
        this.catalogSource = catalogSource != null ? catalogSource : "builtin";
        this.updatedAt = updatedAt;

        EnumMap<ModelCapability, CapabilityStatus> mMap = new EnumMap<>(ModelCapability.class);
        if (modelCapabilities != null) mMap.putAll(modelCapabilities);
        this.modelCapabilities = Collections.unmodifiableMap(mMap);

        EnumMap<ModelCapability, CapabilityStatus> pMap = new EnumMap<>(ModelCapability.class);
        if (providerCapabilities != null) pMap.putAll(providerCapabilities);
        else pMap.putAll(mMap);
        this.providerCapabilities = Collections.unmodifiableMap(pMap);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ModelProvider getProvider() {
        return provider;
    }

    public ModelPricingType getPricingType() {
        return pricingType;
    }

    public AccessType getAccessType() {
        return accessType;
    }

    public ProtocolType getProtocolType() {
        return protocolType;
    }

    public String getDescription() {
        return description;
    }

    public String getContextBadge() {
        return contextBadge;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public int getOutputLimitTokens() {
        return outputLimitTokens;
    }

    public boolean isAvailable() {
        return available;
    }

    public UsabilityStatus getUsabilityStatus() {
        return usabilityStatus;
    }

    public String getUsabilityNote() {
        return usabilityNote;
    }

    public String getCatalogSource() {
        return catalogSource;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public Map<ModelCapability, CapabilityStatus> getCapabilities() {
        return modelCapabilities;
    }

    public CapabilityStatus getCapabilityStatus(ModelCapability capability) {
        CapabilityStatus modelStatus = modelCapabilities.get(capability);
        if (modelStatus == null) modelStatus = CapabilityStatus.UNKNOWN;

        CapabilityStatus providerStatus = providerCapabilities.get(capability);
        if (providerStatus == null) providerStatus = CapabilityStatus.UNKNOWN;

        if (modelStatus == CapabilityStatus.UNSUPPORTED || providerStatus == CapabilityStatus.UNSUPPORTED) {
            return CapabilityStatus.UNSUPPORTED;
        }
        if (modelStatus == CapabilityStatus.UNKNOWN || providerStatus == CapabilityStatus.UNKNOWN) {
            return CapabilityStatus.UNKNOWN;
        }
        return CapabilityStatus.SUPPORTED;
    }

    public CapabilityStatus getRawModelCapability(ModelCapability capability) {
        CapabilityStatus s = modelCapabilities.get(capability);
        return s != null ? s : CapabilityStatus.UNKNOWN;
    }

    public CapabilityStatus getProviderProtocolCapability(ModelCapability capability) {
        CapabilityStatus s = providerCapabilities.get(capability);
        return s != null ? s : CapabilityStatus.UNKNOWN;
    }

    public boolean isDeepThinkingSupported() {
        return getCapabilityStatus(ModelCapability.DEEP_THINKING) == CapabilityStatus.SUPPORTED;
    }

    public boolean isImageSupported() {
        return getCapabilityStatus(ModelCapability.IMAGE) == CapabilityStatus.SUPPORTED;
    }

    public boolean isFileSupported() {
        return getCapabilityStatus(ModelCapability.FILE) == CapabilityStatus.SUPPORTED;
    }

    public boolean isStreamingSupported() {
        return getCapabilityStatus(ModelCapability.STREAMING) == CapabilityStatus.SUPPORTED;
    }

    public boolean isToolCallingSupported() {
        return getCapabilityStatus(ModelCapability.TOOL_CALLING) == CapabilityStatus.SUPPORTED;
    }
}
