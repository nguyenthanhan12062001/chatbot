package com.chatai.an.ai.provider;

import android.content.Context;

import com.chatai.an.ai.local.DeviceCapabilityChecker;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.model.ProviderConfig;
import com.chatai.an.domain.repository.LocalModelRepository;

import okhttp3.OkHttpClient;

public class ProviderFactory {
    private final Context context;
    private final OkHttpClient okHttpClient;
    private final LocalModelRepository localModelRepository;
    private final DeviceCapabilityChecker deviceCapabilityChecker;

    public ProviderFactory(
            Context context,
            OkHttpClient okHttpClient,
            LocalModelRepository localModelRepository,
            DeviceCapabilityChecker deviceCapabilityChecker
    ) {
        this.context = context.getApplicationContext();
        this.okHttpClient = okHttpClient;
        this.localModelRepository = localModelRepository;
        this.deviceCapabilityChecker = deviceCapabilityChecker;
    }

    public AiProvider create(ModelProvider providerType, ProviderConfig config) {
        if (providerType == ModelProvider.LOCAL || (config != null && config.getProtocolType() == ProtocolType.LOCAL_RUNTIME)) {
            return new LocalModelProvider(localModelRepository, deviceCapabilityChecker);
        }
        if (providerType == ModelProvider.POLLINATIONS) {
            return new PollinationsProvider(context, okHttpClient, config);
        }
        if (providerType == ModelProvider.KILO) {
            return new KiloProvider(context, okHttpClient, config);
        }
        if (providerType == ModelProvider.OPENROUTER_FREE) {
            return new OpenRouterFreeProvider(context, okHttpClient, config);
        }
        ProtocolType protocol = config != null ? config.getProtocolType() : providerType.getDefaultProtocol();
        if (protocol == ProtocolType.GEMINI_API) {
            return new GeminiProtocolAdapter(context, okHttpClient, config);
        }
        if (protocol == ProtocolType.ANTHROPIC_MESSAGES) {
            return new AnthropicProtocolAdapter(context, okHttpClient, config);
        }
        return new OpenAiProtocolAdapter(context, okHttpClient, config);
    }
}
