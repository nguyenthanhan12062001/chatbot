package com.chatai.an.ai.provider;

import android.content.Context;

import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.data.repository.UnifiedModelRepository;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.ModelPricingType;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.model.ProviderConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Free Cloud AI provider backed by Pollinations OpenAI-compatible gateway.
 */
public class PollinationsProvider extends OpenAiProtocolAdapter {

    public PollinationsProvider(Context context, OkHttpClient client, ProviderConfig config) {
        super(context, client, config);
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.FREE;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.POLLINATIONS_TEXT;
    }

    @Override
    protected String getChatCompletionsUrl() {
        String base = HttpErrorMapper.normalizeBaseUrl(config.getBaseUrl(), ModelProvider.POLLINATIONS.getDefaultBaseUrl());
        if (base.endsWith("/openai")) return base;
        return base + "/openai";
    }

    @Override
    protected String getModelsUrl() {
        String base = HttpErrorMapper.normalizeBaseUrl(config.getBaseUrl(), ModelProvider.POLLINATIONS.getDefaultBaseUrl());
        return base + "/models";
    }

    @Override
    public void execute(AiRequest request, String apiKey, ExecutionHandle handle, AiStreamListener listener) {
        // Pollinations free endpoint does not require a personal API key
        super.execute(request, "", handle, listener);
    }

    @Override
    public List<AiModel> fetchModels(String apiKey) throws Exception {
        Request req = new Request.Builder()
                .url(getModelsUrl())
                .get()
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw new IOException("Pollinations HTTP " + res.code());
            }
            String body = res.body() != null ? res.body().string() : "[]";
            JsonElement parsed = JsonParser.parseString(body);
            JsonArray arr = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
            List<AiModel> models = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String name = getStringOrNull(obj, "name");
                if (name == null || name.isEmpty()) continue;
                boolean audioOnly = obj.has("audio") && obj.get("audio").getAsBoolean()
                        && (!obj.has("text") || !obj.get("text").getAsBoolean());
                if (audioOnly) continue;

                String desc = getStringOrNull(obj, "description");
                boolean vision = obj.has("vision") && obj.get("vision").getAsBoolean();
                boolean reasoning = obj.has("reasoning") && obj.get("reasoning").getAsBoolean();
                boolean tools = obj.has("tools") && obj.get("tools").getAsBoolean();
                models.add(new AiModel(
                        "pollinations/" + name,
                        (desc != null && !desc.isEmpty()) ? desc + " (Free)" : (name + " (Pollinations Free)"),
                        ModelProvider.POLLINATIONS,
                        ModelPricingType.FREE,
                        AccessType.FREE,
                        ProtocolType.POLLINATIONS_TEXT,
                        "Free cloud access via Pollinations",
                        "128K ctx",
                        128000,
                        8192,
                        true,
                        "remote",
                        now,
                        UnifiedModelRepository.caps(
                                CapabilityStatus.SUPPORTED,
                                vision ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNSUPPORTED,
                                CapabilityStatus.UNSUPPORTED,
                                reasoning ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNSUPPORTED,
                                CapabilityStatus.SUPPORTED,
                                tools ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNKNOWN,
                                CapabilityStatus.SUPPORTED
                        ),
                        UnifiedModelRepository.caps(
                                CapabilityStatus.SUPPORTED,
                                CapabilityStatus.SUPPORTED,
                                CapabilityStatus.UNSUPPORTED,
                                CapabilityStatus.SUPPORTED,
                                CapabilityStatus.SUPPORTED,
                                CapabilityStatus.UNKNOWN,
                                CapabilityStatus.SUPPORTED
                        )
                ));
            }
            return models;
        }
    }
}
