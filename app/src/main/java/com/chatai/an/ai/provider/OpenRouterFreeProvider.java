package com.chatai.an.ai.provider;

import android.content.Context;

import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.data.repository.UnifiedModelRepository;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiError;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Independent Free Cloud AI source backed by OpenRouter (/api/v1/models & /chat/completions).
 * Dynamically fetches OpenRouter's live catalog (never hardcoded), verifies zero pricing
 * (prompt == 0 && completion == 0), and explicitly distinguishes $0 pricing from actual
 * usability (such as requiring a free OpenRouter API key, rate limits, quota, or availability).
 */
public class OpenRouterFreeProvider extends OpenAiProtocolAdapter {
    public static final String MODEL_PREFIX = "openrouter_free/";

    public OpenRouterFreeProvider(Context context, OkHttpClient client, ProviderConfig config) {
        super(context, client, config);
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.FREE;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.OPENROUTER_FREE;
    }

    @Override
    public void execute(AiRequest request, String apiKey, ExecutionHandle handle, AiStreamListener listener) {
        if (handle != null && handle.isCancelled()) {
            listener.onError(new AiError(AiError.ErrorType.CANCELLED, "Cancelled", 0, 0, getProviderId()));
            return;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            listener.onError(new AiError(
                    AiError.ErrorType.MISSING_CREDENTIAL,
                    "This OpenRouter model is $0 per token, but OpenRouter still requires a free OpenRouter API key to execute requests. Add a key under Providers → OpenRouter (Free), or use Pollinations / Kilo.",
                    401,
                    0,
                    getProviderId()
            ));
            return;
        }
        super.execute(request, apiKey, handle, listener);
    }

    @Override
    public List<AiModel> fetchModels(String apiKey) throws Exception {
        Request req = new Request.Builder()
                .url(getModelsUrl())
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String snippet = res.body() != null ? res.body().string() : "";
                if (res.code() == 429) {
                    throw new IOException("OpenRouter rate limit (HTTP 429)");
                }
                throw new IOException("OpenRouter HTTP " + res.code() + (snippet.isEmpty() ? "" : ": " + HttpErrorMapper.trimSnippet(snippet)));
            }
            String body = res.body() != null ? res.body().string() : "{}";
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray arr = root.has("data") && root.get("data").isJsonArray()
                    ? root.getAsJsonArray("data")
                    : new JsonArray();
            boolean hasKey = apiKey != null && !apiKey.trim().isEmpty();
            return parseDynamicFreeModels(arr, hasKey);
        }
    }

    public static List<AiModel> parseDynamicFreeModels(JsonArray arr, boolean hasApiKey) {
        List<AiModel> result = new ArrayList<>();
        if (arr == null) return result;
        long now = System.currentTimeMillis();

        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (!isVerifiedZeroPricing(obj)) {
                continue;
            }
            String id = obj.has("id") && obj.get("id").isJsonPrimitive() ? obj.get("id").getAsString().trim() : "";
            if (id.isEmpty()) continue;

            String name = obj.has("name") && obj.get("name").isJsonPrimitive()
                    ? obj.get("name").getAsString().trim()
                    : id;
            String rawDesc = obj.has("description") && obj.get("description").isJsonPrimitive()
                    ? obj.get("description").getAsString().trim()
                    : "";
            int ctx = obj.has("context_length") && obj.get("context_length").isJsonPrimitive()
                    ? obj.get("context_length").getAsInt()
                    : 32768;
            int outLimit = 8192;
            if (obj.has("top_provider") && obj.get("top_provider").isJsonObject()) {
                JsonObject tp = obj.getAsJsonObject("top_provider");
                if (tp.has("max_completion_tokens") && tp.get("max_completion_tokens").isJsonPrimitive()) {
                    int parsedOut = tp.get("max_completion_tokens").getAsInt();
                    if (parsedOut > 0) outLimit = parsedOut;
                }
            }

            Set<String> inputModalities = readStringSet(
                    obj.has("architecture") && obj.get("architecture").isJsonObject()
                            ? obj.getAsJsonObject("architecture").getAsJsonArray("input_modalities")
                            : null
            );
            Set<String> supportedParams = readStringSet(
                    obj.has("supported_parameters") && obj.get("supported_parameters").isJsonArray()
                            ? obj.getAsJsonArray("supported_parameters")
                            : null
            );

            CapabilityStatus imageCap = inputModalities.isEmpty()
                    ? CapabilityStatus.UNKNOWN
                    : (inputModalities.contains("image") ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNSUPPORTED);
            CapabilityStatus fileCap = inputModalities.contains("file")
                    ? CapabilityStatus.SUPPORTED
                    : CapabilityStatus.UNSUPPORTED;
            CapabilityStatus reasoningCap = (supportedParams.contains("reasoning") || supportedParams.contains("include_reasoning"))
                    ? CapabilityStatus.SUPPORTED
                    : (id.toLowerCase().contains("r1") || id.toLowerCase().contains("think") || id.toLowerCase().contains("reason")
                    ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNKNOWN);
            CapabilityStatus toolsCap = supportedParams.isEmpty()
                    ? CapabilityStatus.UNKNOWN
                    : (supportedParams.contains("tools") ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNSUPPORTED);
            CapabilityStatus structuredCap = supportedParams.isEmpty()
                    ? CapabilityStatus.UNKNOWN
                    : ((supportedParams.contains("structured_outputs") || supportedParams.contains("response_format"))
                    ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNSUPPORTED);

            AiModel.UsabilityStatus usability = hasApiKey
                    ? AiModel.UsabilityStatus.USABLE
                    : AiModel.UsabilityStatus.NEEDS_API_KEY;
            String usabilityNote = hasApiKey
                    ? "$0 pricing • Ready with your OpenRouter key (free-tier rate limits apply)"
                    : "$0 pricing • Requires free OpenRouter API key to send messages";

            String summaryDesc = rawDesc.isEmpty()
                    ? "OpenRouter $0 model (" + id + ")"
                    : (rawDesc.length() > 140 ? rawDesc.substring(0, 140) + "…" : rawDesc);

            result.add(new AiModel(
                    MODEL_PREFIX + id,
                    name,
                    ModelProvider.OPENROUTER_FREE,
                    ModelPricingType.FREE,
                    AccessType.FREE,
                    ProtocolType.OPENROUTER_FREE,
                    summaryDesc,
                    (ctx >= 1000 ? (ctx / 1000) + "K ctx" : ctx + " ctx"),
                    ctx,
                    outLimit,
                    hasApiKey,
                    usability,
                    usabilityNote,
                    "openrouter_live",
                    now,
                    UnifiedModelRepository.caps(
                            CapabilityStatus.SUPPORTED,
                            imageCap,
                            fileCap,
                            reasoningCap,
                            CapabilityStatus.SUPPORTED,
                            toolsCap,
                            structuredCap
                    ),
                    UnifiedModelRepository.caps(
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED,
                            CapabilityStatus.SUPPORTED
                    )
            ));
            if (result.size() >= 60) break;
        }
        return result;
    }

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

    private static Set<String> readStringSet(JsonArray array) {
        Set<String> set = new HashSet<>();
        if (array == null) return set;
        for (JsonElement el : array) {
            if (el != null && el.isJsonPrimitive()) {
                set.add(el.getAsString().trim().toLowerCase());
            }
        }
        return set;
    }
}
