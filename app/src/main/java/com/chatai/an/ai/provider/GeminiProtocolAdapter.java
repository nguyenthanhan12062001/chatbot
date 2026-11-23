package com.chatai.an.ai.provider;

import android.content.Context;

import com.chatai.an.ai.context.CompatHistory;
import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.data.repository.UnifiedModelRepository;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.AiResponse;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.AttachmentType;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ModelPricingType;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.model.ProviderConfig;
import com.chatai.an.domain.model.TokenUsage;
import com.chatai.an.domain.model.ToolCallRequest;
import com.chatai.an.domain.model.ToolDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * Protocol Adapter for Google Gemini Generative Language API (v1beta) with SSE streaming and thinking parts.
 */
public class GeminiProtocolAdapter implements AiProvider {
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient client;
    private final ProviderConfig config;

    public GeminiProtocolAdapter(Context context, OkHttpClient client, ProviderConfig config) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.config = config;
    }

    @Override
    public String getProviderId() {
        return config.getProviderId();
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.PERSONAL_API;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.GEMINI_API;
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
                    "Add a Google Gemini API key in Provider Settings to run this model.",
                    401,
                    0,
                    getProviderId()
            ));
            return;
        }

        listener.onStateChanged(ExecutionState.CONNECTING);
        String base = HttpErrorMapper.normalizeBaseUrl(config.getBaseUrl(), ModelProvider.GOOGLE_GEMINI.getDefaultBaseUrl());
        String wireModel = HttpErrorMapper.resolveWireModelId(request.getModel());
        boolean stream = request.isStreamingEnabled() && request.getModel().isStreamingSupported();
        String endpoint = stream
                ? base + "/v1beta/models/" + wireModel + ":streamGenerateContent?alt=sse"
                : base + "/v1beta/models/" + wireModel + ":generateContent";

        JsonObject payload = buildPayload(request, true);
        boolean sentTools = payload.has("tools");
        try {
            executePayload(endpoint, payload, wireModel, stream, apiKey, handle, listener, sentTools, request);
        } catch (IOException e) {
            if (handle != null && handle.isCancelled()) {
                listener.onError(new AiError(AiError.ErrorType.CANCELLED, "Generation stopped by user", 0, 0, getProviderId()));
            } else {
                listener.onError(new AiError(AiError.ErrorType.NETWORK_ERROR, e.getMessage(), 0, 0, getProviderId()));
            }
        } catch (Exception e) {
            listener.onError(new AiError(AiError.ErrorType.PROTOCOL_ERROR, e.getMessage(), 0, 0, getProviderId()));
        } finally {
            if (handle != null) handle.clearCall();
        }
    }

    private void executePayload(
            String endpoint,
            JsonObject payload,
            String wireModel,
            boolean stream,
            String apiKey,
            ExecutionHandle handle,
            AiStreamListener listener,
            boolean allowRetryWithoutTools,
            AiRequest originalRequest
    ) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", apiKey.trim())
                .post(RequestBody.create(payload.toString(), JSON_MEDIA))
                .build();

        Call call = client.newCall(httpRequest);
        if (handle != null) handle.attachCall(call);

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                String lowerErr = err.toLowerCase(java.util.Locale.ROOT);
                if (allowRetryWithoutTools
                        && (response.code() == 400 || response.code() == 422)
                        && (lowerErr.contains("tool") || lowerErr.contains("function")
                        || lowerErr.contains("schema") || lowerErr.contains("unsupported"))) {
                    JsonObject fallbackPayload = buildPayload(originalRequest, false);
                    executePayload(endpoint, fallbackPayload, wireModel, stream, apiKey, handle, listener, false, originalRequest);
                    return;
                }
                listener.onError(HttpErrorMapper.fromHttpResponse(response, err, getProviderId()));
                return;
            }
            if (response.body() == null) {
                listener.onError(new AiError(AiError.ErrorType.PROTOCOL_ERROR, "Empty response", response.code(), 0, getProviderId()));
                return;
            }
            if (stream) {
                parseSse(response.body().source(), wireModel, handle, listener);
            } else {
                parseSingle(response.body().string(), wireModel, listener);
            }
        }
    }

    private JsonObject buildPayload(AiRequest request, boolean includeTools) {
        JsonObject root = new JsonObject();
        if (!request.getSystemPrompt().isEmpty()) {
            JsonObject sysObj = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", request.getSystemPrompt());
            sysParts.add(part);
            sysObj.add("parts", sysParts);
            root.add("systemInstruction", sysObj);
        }

        JsonArray contents = new JsonArray();
        boolean imageSupported = request.getModel().isImageSupported();
        boolean fileSupported = request.getModel().isFileSupported();

        List<Message> normalizedMessages = CompatHistory.normalizeForProvider(
                request.getContextMessages(), getProtocolType()
        );
        for (Message msg : normalizedMessages) {
            JsonObject cObj = new JsonObject();
            cObj.addProperty("role", msg.getRole() == MessageRole.ASSISTANT ? "model" : "user");
            JsonArray parts = new JsonArray();
            if (!msg.getContent().isEmpty()) {
                JsonObject tPart = new JsonObject();
                tPart.addProperty("text", msg.getContent());
                parts.add(tPart);
            }
            if (msg.getAttachments() != null) {
                for (Attachment att : msg.getAttachments()) {
                    if (att.getType() == AttachmentType.IMAGE && imageSupported) {
                        String b64 = HttpErrorMapper.readAttachmentAsBase64(context, att);
                        if (b64 != null) {
                            JsonObject inlinePart = new JsonObject();
                            JsonObject inlineData = new JsonObject();
                            inlineData.addProperty("mimeType", att.getMimeType() != null ? att.getMimeType() : "image/jpeg");
                            inlineData.addProperty("data", b64);
                            inlinePart.add("inlineData", inlineData);
                            parts.add(inlinePart);
                        }
                    } else if (att.getType() != AttachmentType.IMAGE && fileSupported) {
                        String preview = HttpErrorMapper.readTextAttachmentPreview(context, att);
                        if (preview != null) {
                            JsonObject fPart = new JsonObject();
                            fPart.addProperty("text", preview);
                            parts.add(fPart);
                        }
                    }
                }
            }
            if (parts.size() > 0) {
                cObj.add("parts", parts);
                contents.add(cObj);
            }
        }
        root.add("contents", contents);

        if (request.isDeepThinkingEnabled() && request.getModel().isDeepThinkingSupported()) {
            JsonObject genConfig = new JsonObject();
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("includeThoughts", true);
            genConfig.add("thinkingConfig", thinkingConfig);
            root.add("generationConfig", genConfig);
        }

        if (includeTools
                && request.getTools() != null
                && !request.getTools().isEmpty()
                && request.getModel().getCapabilityStatus(ModelCapability.TOOL_CALLING) != CapabilityStatus.UNSUPPORTED) {
            JsonArray fnDeclarations = new JsonArray();
            for (ToolDefinition def : request.getTools()) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", def.getName());
                fn.addProperty("description", def.getDescription());
                fn.add("parameters", parseSchemaObject(def.getInputSchemaJson()));
                fnDeclarations.add(fn);
            }
            JsonObject toolEntry = new JsonObject();
            toolEntry.add("functionDeclarations", fnDeclarations);
            JsonArray toolsArr = new JsonArray();
            toolsArr.add(toolEntry);
            root.add("tools", toolsArr);
        }

        return root;
    }

    private static JsonObject parseSchemaObject(String schemaJson) {
        if (schemaJson != null && !schemaJson.trim().isEmpty()) {
            try {
                JsonElement el = JsonParser.parseString(schemaJson);
                if (el.isJsonObject()) return el.getAsJsonObject();
            } catch (Exception ignored) {
            }
        }
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "object");
        fallback.add("properties", new JsonObject());
        return fallback;
    }

    private void parseSse(BufferedSource source, String wireModel, ExecutionHandle handle, AiStreamListener listener) throws IOException {
        listener.onStateChanged(ExecutionState.STREAMING);
        StringBuilder fullText = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        TokenUsage usage = null;

        while (!source.exhausted()) {
            if (handle != null && handle.isCancelled()) {
                listener.onError(new AiError(AiError.ErrorType.CANCELLED, "Generation stopped", 0, 0, getProviderId()));
                return;
            }
            String line = source.readUtf8Line();
            if (line == null) break;
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;
            String jsonStr = trimmed.substring(5).trim();
            if (jsonStr.isEmpty() || "[DONE]".equals(jsonStr)) continue;

            try {
                JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
                TokenUsage u = extractUsage(obj);
                if (u != null) usage = u;
                extractCandidates(obj, fullText, fullThinking, toolCalls, listener);
            } catch (Exception ignored) {
            }
        }
        listener.onCompleted(new AiResponse(fullText.toString(), fullThinking.toString(), usage, wireModel, getProviderId(), toolCalls));
    }

    private void parseSingle(String raw, String wireModel, AiStreamListener listener) {
        listener.onStateChanged(ExecutionState.STREAMING);
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        StringBuilder fullText = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        extractCandidates(obj, fullText, fullThinking, toolCalls, listener);
        TokenUsage usage = extractUsage(obj);
        listener.onCompleted(new AiResponse(fullText.toString(), fullThinking.toString(), usage, wireModel, getProviderId(), toolCalls));
    }

    private void extractCandidates(
            JsonObject obj,
            StringBuilder fullText,
            StringBuilder fullThinking,
            List<ToolCallRequest> toolCalls,
            AiStreamListener listener
    ) {
        if (!obj.has("candidates") || !obj.get("candidates").isJsonArray()) return;
        JsonArray candidates = obj.getAsJsonArray("candidates");
        if (candidates.size() == 0) return;
        JsonObject first = candidates.get(0).getAsJsonObject();
        if (!first.has("content") || !first.get("content").isJsonObject()) return;
        JsonObject content = first.getAsJsonObject("content");
        if (!content.has("parts") || !content.get("parts").isJsonArray()) return;
        for (JsonElement pEl : content.getAsJsonArray("parts")) {
            if (!pEl.isJsonObject()) continue;
            JsonObject part = pEl.getAsJsonObject();
            if (part.has("text") && part.get("text").isJsonPrimitive()) {
                String text = part.get("text").getAsString();
                boolean isThought = part.has("thought") && part.get("thought").getAsBoolean();
                if (isThought) {
                    fullThinking.append(text);
                    listener.onThinkingDelta(text);
                } else {
                    fullText.append(text);
                    listener.onTextDelta(text);
                }
            } else if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                JsonObject fc = part.getAsJsonObject("functionCall");
                String fnName = fc.has("name") && fc.get("name").isJsonPrimitive() ? fc.get("name").getAsString() : "";
                JsonObject fnArgs = fc.has("args") && fc.get("args").isJsonObject()
                        ? fc.getAsJsonObject("args")
                        : new JsonObject();
                if (!fnName.isEmpty()) {
                    toolCalls.add(new ToolCallRequest("gemini_" + toolCalls.size(), fnName, fnArgs, true));
                }
            }
        }
    }

    private TokenUsage extractUsage(JsonObject obj) {
        if (!obj.has("usageMetadata") || !obj.get("usageMetadata").isJsonObject()) return null;
        JsonObject u = obj.getAsJsonObject("usageMetadata");
        int prompt = u.has("promptTokenCount") ? u.get("promptTokenCount").getAsInt() : 0;
        int cand = u.has("candidatesTokenCount") ? u.get("candidatesTokenCount").getAsInt() : 0;
        int total = u.has("totalTokenCount") ? u.get("totalTokenCount").getAsInt() : (prompt + cand);
        TokenUsage usage = new TokenUsage(prompt, cand, total);
        return usage.hasAnyUsage() ? usage : null;
    }

    @Override
    public List<AiModel> fetchModels(String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Missing Gemini API Key");
        }
        String base = HttpErrorMapper.normalizeBaseUrl(config.getBaseUrl(), ModelProvider.GOOGLE_GEMINI.getDefaultBaseUrl());
        Request req = new Request.Builder()
                .url(base + "/v1beta/models")
                .header("x-goog-api-key", apiKey.trim())
                .get()
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw new IOException("HTTP " + res.code() + ": " + res.message());
            }
            String raw = res.body() != null ? res.body().string() : "{}";
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray arr = root.has("models") && root.get("models").isJsonArray()
                    ? root.getAsJsonArray("models")
                    : new JsonArray();
            List<AiModel> models = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                String name = m.has("name") ? m.get("name").getAsString() : "";
                if (!name.startsWith("models/")) continue;
                String id = name.substring("models/".length());
                if (!id.contains("gemini")) continue;
                String displayName = m.has("displayName") ? m.get("displayName").getAsString() : id;
                int inputLimit = m.has("inputTokenLimit") ? m.get("inputTokenLimit").getAsInt() : 1000000;
                int outputLimit = m.has("outputTokenLimit") ? m.get("outputTokenLimit").getAsInt() : 8192;
                boolean supportsThinking = id.contains("2.5");
                models.add(new AiModel(
                        id,
                        displayName,
                        ModelProvider.GOOGLE_GEMINI,
                        ModelPricingType.API_KEY_REQUIRED,
                        AccessType.PERSONAL_API,
                        ProtocolType.GEMINI_API,
                        m.has("description") ? m.get("description").getAsString() : "Google Gemini model",
                        (inputLimit / 1000) + "K ctx",
                        inputLimit,
                        outputLimit,
                        true,
                        "remote",
                        now,
                        UnifiedModelRepository.caps(
                                CapabilityStatus.SUPPORTED,
                                CapabilityStatus.SUPPORTED,
                                CapabilityStatus.SUPPORTED,
                                supportsThinking ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNSUPPORTED,
                                CapabilityStatus.SUPPORTED,
                                CapabilityStatus.SUPPORTED,
                                CapabilityStatus.SUPPORTED
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
                if (models.size() >= 25) break;
            }
            return models;
        }
    }

    @Override
    public String testConnection(String apiKey) throws Exception {
        List<AiModel> models = fetchModels(apiKey);
        return "Gemini OK (" + models.size() + " models)";
    }
}
