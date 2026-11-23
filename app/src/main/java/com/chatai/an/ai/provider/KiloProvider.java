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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Free Cloud AI provider backed by Kilo Gateway.
 * Verifies free availability using real pricing metadata (prompt == 0 && completion == 0),
 * never relying solely on a ":free" suffix.
 */
public class KiloProvider extends OpenAiProtocolAdapter {

    public KiloProvider(Context context, OkHttpClient client, ProviderConfig config) {
        super(context, client, config);
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.FREE;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.KILO_FREE;
    }

    @Override
    public void execute(AiRequest request, String apiKey, ExecutionHandle handle, AiStreamListener listener) {
        super.execute(request, apiKey != null ? apiKey : "", handle, listener);
    }

    @Override
    public List<AiModel> fetchModels(String apiKey) throws Exception {
        Request req = new Request.Builder()
                .url(getModelsUrl())
                .get()
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw new IOException("Kilo HTTP " + res.code());
            }
            String body = res.body() != null ? res.body().string() : "{}";
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray arr = root.has("data") && root.get("data").isJsonArray()
                    ? root.getAsJsonArray("data")
                    : new JsonArray();
            List<AiModel> freeModels = parseVerifiedFreeModels(arr);
            return freeModels;
        }
    }

    public static List<AiModel> parseVerifiedFreeModels(JsonArray arr) {
        List<AiModel> result = new ArrayList<>();
        if (arr == null) return result;
        long now = System.currentTimeMillis();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (!isVerifiedZeroPricing(obj)) {
                continue;
            }
            String id = obj.has("id") && obj.get("id").isJsonPrimitive() ? obj.get("id").getAsString() : "";
            if (id.isEmpty()) continue;
            String name = obj.has("name") && obj.get("name").isJsonPrimitive() ? obj.get("name").getAsString() : id;
            int ctx = obj.has("context_length") && obj.get("context_length").isJsonPrimitive()
                    ? obj.get("context_length").getAsInt() : 64000;
            boolean reasoning = id.toLowerCase().contains("r1")
                    || id.toLowerCase().contains("reason")
                    || id.toLowerCase().contains("think");

            result.add(new AiModel(
                    "kilo/" + id,
                    name + " (Kilo Free)",
                    ModelProvider.KILO,
                    ModelPricingType.FREE,
                    AccessType.FREE,
                    ProtocolType.KILO_FREE,
                    "Verified zero-cost model on Kilo Gateway",
                    (ctx / 1000) + "K ctx",
                    ctx,
                    8192,
                    true,
                    "remote",
                    now,
                    UnifiedModelRepository.caps(
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.UNKNOWN,
                            CapabilityStatus.UNSUPPORTED,
                            reasoning ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNKNOWN,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.UNKNOWN,
                            CapabilityStatus.UNKNOWN
                    ),
                    UnifiedModelRepository.caps(
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.UNSUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED
                    )
            ));
            if (result.size() >= 30) break;
        }
        return result;
    }

    /**
     * Checks real pricing metadata: both pricing.prompt and pricing.completion must equal zero.
     */
    public static boolean isVerifiedZeroPricing(JsonObject modelObj) {
        if (modelObj == null || !modelObj.has("pricing") || !modelObj.get("pricing").isJsonObject()) {
            return false;
        }
        JsonObject pricing = modelObj.getAsJsonObject("pricing");
        return isZeroValue(pricing.get("prompt")) && isZeroValue(pricing.get("completion"));
    }

    private static boolean isZeroValue(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return false;
        try {
            BigDecimal val = new BigDecimal(element.getAsString().trim());
            return val.compareTo(BigDecimal.ZERO) == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
