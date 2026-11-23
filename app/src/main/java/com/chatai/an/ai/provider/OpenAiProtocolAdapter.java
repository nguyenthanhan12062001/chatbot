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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * Protocol Adapter for OpenAI-compatible Chat Completions & SSE streaming.
 * Supports OpenAI, OpenRouter, Groq, DeepSeek, Together, Mistral, Ollama, LM Studio, and Custom endpoints.
 */
public class OpenAiProtocolAdapter implements AiProvider {
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    protected final Context context;
    protected final OkHttpClient client;
    protected final ProviderConfig config;

    public OpenAiProtocolAdapter(Context context, OkHttpClient client, ProviderConfig config) {
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
        return config.getProviderType().getDefaultAccessType();
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.OPENAI_CHAT_COMPLETIONS;
    }

    protected String getChatCompletionsUrl() {
        String base = HttpErrorMapper.normalizeBaseUrl(config.getBaseUrl(), config.getProviderType().getDefaultBaseUrl());
        if (base.endsWith("/chat/completions")) return base;
        return base + "/chat/completions";
    }

    protected String getModelsUrl() {
        String base = HttpErrorMapper.normalizeBaseUrl(config.getBaseUrl(), config.getProviderType().getDefaultBaseUrl());
        if (base.endsWith("/models")) return base;
        return base + "/models";
    }

    @Override
    public void execute(AiRequest request, String apiKey, ExecutionHandle handle, AiStreamListener listener) {
        if (handle != null && handle.isCancelled()) {
            listener.onError(new AiError(AiError.ErrorType.CANCELLED, "Cancelled", 0, 0, getProviderId()));
            return;
        }
        if (config.getProviderType().requiresApiKey() && (apiKey == null || apiKey.trim().isEmpty())) {
            listener.onError(new AiError(
                    AiError.ErrorType.MISSING_CREDENTIAL,
                    "Missing API key for " + config.getDisplayName(),
                    401,
                    0,
                    getProviderId()
            ));
            return;
        }

        listener.onStateChanged(ExecutionState.CONNECTING);
        String wireModel = HttpErrorMapper.resolveWireModelId(request.getModel());
        boolean stream = request.isStreamingEnabled() && request.getModel().isStreamingSupported();

        JsonObject payload = buildRequestPayload(request, wireModel, stream, true);
        boolean sentTools = payload.has("tools");
        try {
            executePayload(payload, wireModel, stream, apiKey, handle, listener, sentTools, request);
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
            JsonObject payload,
            String wireModel,
            boolean stream,
            String apiKey,
            ExecutionHandle handle,
            AiStreamListener listener,
            boolean allowRetryWithoutTools,
            AiRequest originalRequest
    ) throws IOException {
        Request.Builder reqBuilder = new Request.Builder()
                .url(getChatCompletionsUrl())
                .post(RequestBody.create(payload.toString(), JSON_MEDIA))
                .header("Accept", stream ? "text/event-stream" : "application/json");

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }
        for (Map.Entry<String, String> h : config.getCustomHeaders().entrySet()) {
            if (h.getKey() != null && h.getValue() != null) {
                reqBuilder.header(h.getKey(), h.getValue());
            }
        }
        if (config.getProviderType() == ModelProvider.OPENROUTER
                || config.getProviderType() == ModelProvider.OPENROUTER_FREE) {
            reqBuilder.header("X-Title", "ChatAI Android");
        }

        Call call = client.newCall(reqBuilder.build());
        if (handle != null) handle.attachCall(call);

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                String lowerErr = errBody.toLowerCase(java.util.Locale.ROOT);
                if (allowRetryWithoutTools
                        && (response.code() == 400 || response.code() == 422)
                        && (lowerErr.contains("tool") || lowerErr.contains("function")
                        || lowerErr.contains("schema") || lowerErr.contains("unsupported")
                        || lowerErr.contains("invalid"))) {
                    JsonObject fallbackPayload = buildRequestPayload(originalRequest, wireModel, stream, false);
                    executePayload(fallbackPayload, wireModel, stream, apiKey, handle, listener, false, originalRequest);
                    return;
                }
                listener.onError(HttpErrorMapper.fromHttpResponse(response, errBody, getProviderId()));
                return;
            }
            if (response.body() == null) {
                listener.onError(new AiError(AiError.ErrorType.PROTOCOL_ERROR, "Empty response body", response.code(), 0, getProviderId()));
                return;
            }

            if (stream) {
                parseSseStream(response.body().source(), wireModel, handle, listener);
            } else {
                parseSingleJson(response.body().string(), wireModel, listener);
            }
        }
    }

    private JsonObject buildRequestPayload(AiRequest request, String wireModel, boolean stream, boolean includeTools) {
        JsonObject root = new JsonObject();
        root.addProperty("model", wireModel);
        root.addProperty("stream", stream);
        if (stream) {
            JsonObject streamOpts = new JsonObject();
            streamOpts.addProperty("include_usage", true);
            root.add("stream_options", streamOpts);
        }

        JsonArray messages = new JsonArray();
        if (!request.getSystemPrompt().isEmpty()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", request.getSystemPrompt());
            messages.add(sys);
        }

        boolean imageSupported = request.getModel().isImageSupported();
        boolean fileSupported = request.getModel().isFileSupported();

        List<Message> normalizedMessages = CompatHistory.normalizeForProvider(
                request.getContextMessages(), getProtocolType()
        );
        for (Message msg : normalizedMessages) {
            JsonObject mObj = new JsonObject();
            String roleStr = msg.getRole() == MessageRole.ASSISTANT ? "assistant" : "user";
            mObj.addProperty("role", roleStr);

            boolean hasImagePart = false;
            JsonArray contentParts = new JsonArray();
            StringBuilder textAccumulator = new StringBuilder(msg.getContent());

            if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
                for (Attachment att : msg.getAttachments()) {
                    if (att.getType() == AttachmentType.IMAGE && imageSupported) {
                        String b64 = HttpErrorMapper.readAttachmentAsBase64(context, att);
                        if (b64 != null) {
                            hasImagePart = true;
                            JsonObject imgPart = new JsonObject();
                            imgPart.addProperty("type", "image_url");
                            JsonObject urlObj = new JsonObject();
                            String mime = (att.getMimeType() != null && !att.getMimeType().isEmpty()) ? att.getMimeType() : "image/jpeg";
                            urlObj.addProperty("url", "data:" + mime + ";base64," + b64);
                            imgPart.add("image_url", urlObj);
                            contentParts.add(imgPart);
                        }
                    } else if (att.getType() != AttachmentType.IMAGE && fileSupported) {
                        String preview = HttpErrorMapper.readTextAttachmentPreview(context, att);
                        if (preview != null) {
                            if (textAccumulator.length() > 0) textAccumulator.append("\n\n");
                            textAccumulator.append(preview);
                        }
                    }
                }
            }

            if (hasImagePart) {
                if (textAccumulator.length() > 0) {
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("type", "text");
                    textPart.addProperty("text", textAccumulator.toString());
                    contentParts.add(textPart);
                }
                mObj.add("content", contentParts);
            } else {
                mObj.addProperty("content", textAccumulator.toString());
            }
            messages.add(mObj);
        }

        root.add("messages", messages);

        if (includeTools
                && request.getTools() != null
                && !request.getTools().isEmpty()
                && request.getModel().getCapabilityStatus(ModelCapability.TOOL_CALLING) != CapabilityStatus.UNSUPPORTED) {
            JsonArray toolsArr = new JsonArray();
            for (ToolDefinition def : request.getTools()) {
                JsonObject fnObj = new JsonObject();
                fnObj.addProperty("name", def.getName());
                fnObj.addProperty("description", def.getDescription());
                fnObj.add("parameters", parseSchemaObject(def.getInputSchemaJson()));

                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("type", "function");
                wrapper.add("function", fnObj);
                toolsArr.add(wrapper);
            }
            root.add("tools", toolsArr);
            root.addProperty("tool_choice", "auto");
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

    private static class PendingToolCall {
        String id = "";
        final StringBuilder name = new StringBuilder();
        final StringBuilder argsJson = new StringBuilder();
    }

    private void parseSseStream(BufferedSource source, String wireModel, ExecutionHandle handle, AiStreamListener listener) throws IOException {
        listener.onStateChanged(ExecutionState.STREAMING);
        StringBuilder fullText = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        Map<Integer, PendingToolCall> pendingCalls = new LinkedHashMap<>();
        TokenUsage usage = null;

        while (!source.exhausted()) {
            if (handle != null && handle.isCancelled()) {
                listener.onError(new AiError(AiError.ErrorType.CANCELLED, "Generation stopped", 0, 0, getProviderId()));
                return;
            }
            String line = source.readUtf8Line();
            if (line == null) break;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(":")) continue;
            if (!trimmed.startsWith("data:")) continue;
            String data = trimmed.substring(5).trim();
            if ("[DONE]".equals(data)) break;

            try {
                JsonObject obj = JsonParser.parseString(data).getAsJsonObject();
                TokenUsage parsedUsage = extractUsage(obj);
                if (parsedUsage != null) {
                    usage = parsedUsage;
                }
                if (obj.has("choices") && obj.get("choices").isJsonArray()) {
                    JsonArray choices = obj.getAsJsonArray("choices");
                    if (choices.size() > 0) {
                        JsonObject choice = choices.get(0).getAsJsonObject();
                        JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                                ? choice.getAsJsonObject("delta")
                                : null;
                        if (delta != null) {
                            String reasoning = getStringOrNull(delta, "reasoning_content");
                            if (reasoning == null) reasoning = getStringOrNull(delta, "reasoning");
                            if (reasoning != null && !reasoning.isEmpty()) {
                                fullThinking.append(reasoning);
                                listener.onThinkingDelta(reasoning);
                            }
                            String content = getStringOrNull(delta, "content");
                            if (content != null && !content.isEmpty()) {
                                fullText.append(content);
                                listener.onTextDelta(content);
                            }
                            if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                                for (JsonElement tcEl : delta.getAsJsonArray("tool_calls")) {
                                    if (!tcEl.isJsonObject()) continue;
                                    JsonObject tcObj = tcEl.getAsJsonObject();
                                    int idx = tcObj.has("index") && tcObj.get("index").isJsonPrimitive()
                                            ? tcObj.get("index").getAsInt() : 0;
                                    PendingToolCall pending = pendingCalls.computeIfAbsent(idx, k -> new PendingToolCall());
                                    String id = getStringOrNull(tcObj, "id");
                                    if (id != null && !id.isEmpty()) pending.id = id;
                                    if (tcObj.has("function") && tcObj.get("function").isJsonObject()) {
                                        JsonObject fn = tcObj.getAsJsonObject("function");
                                        String fnName = getStringOrNull(fn, "name");
                                        if (fnName != null) pending.name.append(fnName);
                                        String fnArgs = getStringOrNull(fn, "arguments");
                                        if (fnArgs != null) pending.argsJson.append(fnArgs);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        List<ToolCallRequest> parsedToolCalls = new ArrayList<>();
        for (PendingToolCall p : pendingCalls.values()) {
            String tName = p.name.toString().trim();
            if (tName.isEmpty()) continue;
            parsedToolCalls.add(new ToolCallRequest(p.id, tName, parseArgsObject(p.argsJson.toString()), true));
        }

        listener.onCompleted(new AiResponse(
                fullText.toString(),
                fullThinking.toString(),
                usage,
                wireModel,
                getProviderId(),
                parsedToolCalls
        ));
    }

    private void parseSingleJson(String raw, String wireModel, AiStreamListener listener) {
        listener.onStateChanged(ExecutionState.STREAMING);
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        if (obj.has("choices") && obj.get("choices").isJsonArray()) {
            JsonArray choices = obj.getAsJsonArray("choices");
            if (choices.size() > 0) {
                JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (msg != null) {
                    String r = getStringOrNull(msg, "reasoning_content");
                    if (r == null) r = getStringOrNull(msg, "reasoning");
                    if (r != null && !r.isEmpty()) {
                        thinking.append(r);
                        listener.onThinkingDelta(r);
                    }
                    String c = getStringOrNull(msg, "content");
                    if (c != null && !c.isEmpty()) {
                        text.append(c);
                        listener.onTextDelta(c);
                    }
                    if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
                        for (JsonElement tcEl : msg.getAsJsonArray("tool_calls")) {
                            if (!tcEl.isJsonObject()) continue;
                            JsonObject tcObj = tcEl.getAsJsonObject();
                            String id = getStringOrNull(tcObj, "id");
                            if (tcObj.has("function") && tcObj.get("function").isJsonObject()) {
                                JsonObject fn = tcObj.getAsJsonObject("function");
                                String fnName = getStringOrNull(fn, "name");
                                String fnArgs = getStringOrNull(fn, "arguments");
                                if (fnName != null && !fnName.trim().isEmpty()) {
                                    toolCalls.add(new ToolCallRequest(id, fnName, parseArgsObject(fnArgs), true));
                                }
                            }
                        }
                    }
                }
            }
        }
        TokenUsage usage = extractUsage(obj);
        listener.onCompleted(new AiResponse(text.toString(), thinking.toString(), usage, wireModel, getProviderId(), toolCalls));
    }

    private static JsonObject parseArgsObject(String raw) {
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JsonElement el = JsonParser.parseString(raw.trim());
                if (el.isJsonObject()) return el.getAsJsonObject();
            } catch (Exception ignored) {
            }
        }
        return new JsonObject();
    }

    protected TokenUsage extractUsage(JsonObject obj) {
        if (obj == null || !obj.has("usage") || !obj.get("usage").isJsonObject()) return null;
        JsonObject u = obj.getAsJsonObject("usage");
        int prompt = u.has("prompt_tokens") ? u.get("prompt_tokens").getAsInt() : 0;
        int completion = u.has("completion_tokens") ? u.get("completion_tokens").getAsInt() : 0;
        int total = u.has("total_tokens") ? u.get("total_tokens").getAsInt() : (prompt + completion);
        TokenUsage usage = new TokenUsage(prompt, completion, total);
        return usage.hasAnyUsage() ? usage : null;
    }

    protected String getStringOrNull(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    @Override
    public List<AiModel> fetchModels(String apiKey) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(getModelsUrl())
                .get();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            List<AiModel> result = new ArrayList<>();
            long now = System.currentTimeMillis();
            JsonArray arr = root.has("data") && root.get("data").isJsonArray()
                    ? root.getAsJsonArray("data")
                    : new JsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject mObj = el.getAsJsonObject();
                String id = getStringOrNull(mObj, "id");
                if (id == null || id.isEmpty()) continue;
                String displayName = getStringOrNull(mObj, "name");
                if (displayName == null || displayName.isEmpty()) displayName = id;
                String storedId = config.getProviderType() == ModelProvider.OLLAMA ? "ollama/" + id
                        : (config.getProviderType() == ModelProvider.OPENROUTER ? "openrouter/" + id : id);
                int ctx = mObj.has("context_length") && mObj.get("context_length").isJsonPrimitive()
                        ? mObj.get("context_length").getAsInt() : 64000;
                result.add(new AiModel(
                        storedId,
                        displayName,
                        config.getProviderType(),
                        config.getProviderType().requiresApiKey() ? ModelPricingType.API_KEY_REQUIRED : ModelPricingType.FREE,
                        config.getProviderType().getDefaultAccessType(),
                        ProtocolType.OPENAI_CHAT_COMPLETIONS,
                        "Fetched from " + config.getDisplayName() + " /models",
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
                                CapabilityStatus.UNKNOWN,
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
                if (result.size() >= 40) break;
            }
            return result;
        }
    }

    @Override
    public String testConnection(String apiKey) throws Exception {
        if (config.getProviderType().requiresApiKey() && (apiKey == null || apiKey.trim().isEmpty())) {
            throw new IllegalStateException("Missing API Key");
        }
        List<AiModel> models = fetchModels(apiKey);
        return "Connected (" + models.size() + " models listed)";
    }
}
