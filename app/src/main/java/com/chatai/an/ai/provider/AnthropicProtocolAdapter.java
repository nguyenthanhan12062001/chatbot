package com.chatai.an.ai.provider;

import android.content.Context;

import com.chatai.an.ai.context.CompatHistory;
import com.chatai.an.ai.core.ExecutionHandle;
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
import java.util.Collections;
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
 * Protocol Adapter for Anthropic Messages API (/v1/messages) with SSE delta & thinking block support.
 */
public class AnthropicProtocolAdapter implements AiProvider {
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient client;
    private final ProviderConfig config;

    public AnthropicProtocolAdapter(Context context, OkHttpClient client, ProviderConfig config) {
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
        return ProtocolType.ANTHROPIC_MESSAGES;
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
                    "Add an Anthropic API key in Provider Settings to run Claude models.",
                    401,
                    0,
                    getProviderId()
            ));
            return;
        }

        listener.onStateChanged(ExecutionState.CONNECTING);
        String base = HttpErrorMapper.normalizeBaseUrl(config.getBaseUrl(), ModelProvider.ANTHROPIC.getDefaultBaseUrl());
        String wireModel = HttpErrorMapper.resolveWireModelId(request.getModel());
        boolean stream = request.isStreamingEnabled() && request.getModel().isStreamingSupported();

        JsonObject payload = buildPayload(request, wireModel, stream, true);
        boolean sentTools = payload.has("tools");
        try {
            executePayload(base + "/messages", payload, wireModel, stream, apiKey, handle, listener, sentTools, request);
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
            String url,
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
                .url(url)
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", "2023-06-01")
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
                        && (lowerErr.contains("tool") || lowerErr.contains("schema") || lowerErr.contains("unsupported"))) {
                    JsonObject fallbackPayload = buildPayload(originalRequest, wireModel, stream, false);
                    executePayload(url, fallbackPayload, wireModel, stream, apiKey, handle, listener, false, originalRequest);
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

    private JsonObject buildPayload(AiRequest request, String wireModel, boolean stream, boolean includeTools) {
        JsonObject root = new JsonObject();
        root.addProperty("model", wireModel);
        root.addProperty("max_tokens", Math.min(8192, request.getModel().getOutputLimitTokens()));
        root.addProperty("stream", stream);
        if (!request.getSystemPrompt().isEmpty()) {
            root.addProperty("system", request.getSystemPrompt());
        }

        JsonArray messages = new JsonArray();
        boolean imageSupported = request.getModel().isImageSupported();
        List<Message> normalizedMessages = CompatHistory.normalizeForProvider(
                request.getContextMessages(), getProtocolType()
        );
        for (Message msg : normalizedMessages) {
            JsonObject mObj = new JsonObject();
            mObj.addProperty("role", msg.getRole() == MessageRole.ASSISTANT ? "assistant" : "user");
            JsonArray blocks = new JsonArray();
            if (msg.getAttachments() != null && imageSupported) {
                for (Attachment att : msg.getAttachments()) {
                    if (att.getType() == AttachmentType.IMAGE) {
                        String b64 = HttpErrorMapper.readAttachmentAsBase64(context, att);
                        if (b64 != null) {
                            JsonObject imgBlock = new JsonObject();
                            imgBlock.addProperty("type", "image");
                            JsonObject source = new JsonObject();
                            source.addProperty("type", "base64");
                            source.addProperty("media_type", att.getMimeType() != null ? att.getMimeType() : "image/jpeg");
                            source.addProperty("data", b64);
                            imgBlock.add("source", source);
                            blocks.add(imgBlock);
                        }
                    }
                }
            }
            if (!msg.getContent().isEmpty()) {
                JsonObject tBlock = new JsonObject();
                tBlock.addProperty("type", "text");
                tBlock.addProperty("text", msg.getContent());
                blocks.add(tBlock);
            }
            if (blocks.size() > 0) {
                mObj.add("content", blocks);
                messages.add(mObj);
            }
        }
        root.add("messages", messages);

        if (includeTools
                && request.getTools() != null
                && !request.getTools().isEmpty()
                && request.getModel().getCapabilityStatus(ModelCapability.TOOL_CALLING) != CapabilityStatus.UNSUPPORTED) {
            JsonArray toolsArr = new JsonArray();
            for (ToolDefinition def : request.getTools()) {
                JsonObject tObj = new JsonObject();
                tObj.addProperty("name", def.getName());
                tObj.addProperty("description", def.getDescription());
                tObj.add("input_schema", parseSchemaObject(def.getInputSchemaJson()));
                toolsArr.add(tObj);
            }
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

    private static class PendingToolUse {
        String id = "";
        String name = "";
        final StringBuilder inputJson = new StringBuilder();
    }

    private void parseSse(BufferedSource source, String wireModel, ExecutionHandle handle, AiStreamListener listener) throws IOException {
        listener.onStateChanged(ExecutionState.STREAMING);
        StringBuilder fullText = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        Map<Integer, PendingToolUse> toolUseBlocks = new LinkedHashMap<>();
        int inputTokens = 0;
        int outputTokens = 0;

        while (!source.exhausted()) {
            if (handle != null && handle.isCancelled()) {
                listener.onError(new AiError(AiError.ErrorType.CANCELLED, "Generation stopped", 0, 0, getProviderId()));
                return;
            }
            String line = source.readUtf8Line();
            if (line == null) break;
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;
            String json = trimmed.substring(5).trim();
            if (json.isEmpty()) continue;

            try {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                String type = obj.has("type") ? obj.get("type").getAsString() : "";
                if ("message_start".equals(type) && obj.has("message")) {
                    JsonObject msg = obj.getAsJsonObject("message");
                    if (msg.has("usage")) {
                        inputTokens = msg.getAsJsonObject("usage").get("input_tokens").getAsInt();
                    }
                } else if ("message_delta".equals(type) && obj.has("usage")) {
                    outputTokens = obj.getAsJsonObject("usage").get("output_tokens").getAsInt();
                } else if ("content_block_start".equals(type) && obj.has("content_block")) {
                    int idx = obj.has("index") ? obj.get("index").getAsInt() : toolUseBlocks.size();
                    JsonObject cb = obj.getAsJsonObject("content_block");
                    String cbType = cb.has("type") ? cb.get("type").getAsString() : "";
                    if ("tool_use".equals(cbType)) {
                        PendingToolUse p = new PendingToolUse();
                        p.id = cb.has("id") ? cb.get("id").getAsString() : ("anthropic_" + idx);
                        p.name = cb.has("name") ? cb.get("name").getAsString() : "";
                        toolUseBlocks.put(idx, p);
                    }
                } else if ("content_block_delta".equals(type) && obj.has("delta")) {
                    int idx = obj.has("index") ? obj.get("index").getAsInt() : 0;
                    JsonObject delta = obj.getAsJsonObject("delta");
                    String dType = delta.has("type") ? delta.get("type").getAsString() : "";
                    if ("thinking_delta".equals(dType) && delta.has("thinking")) {
                        String t = delta.get("thinking").getAsString();
                        fullThinking.append(t);
                        listener.onThinkingDelta(t);
                    } else if ("text_delta".equals(dType) && delta.has("text")) {
                        String t = delta.get("text").getAsString();
                        fullText.append(t);
                        listener.onTextDelta(t);
                    } else if ("input_json_delta".equals(dType) && delta.has("partial_json")) {
                        PendingToolUse p = toolUseBlocks.get(idx);
                        if (p != null) {
                            p.inputJson.append(delta.get("partial_json").getAsString());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        for (PendingToolUse p : toolUseBlocks.values()) {
            if (!p.name.isEmpty()) {
                JsonObject args = new JsonObject();
                String rawJson = p.inputJson.toString().trim();
                if (!rawJson.isEmpty()) {
                    try {
                        JsonElement el = JsonParser.parseString(rawJson);
                        if (el.isJsonObject()) args = el.getAsJsonObject();
                    } catch (Exception ignored) {
                    }
                }
                toolCalls.add(new ToolCallRequest(p.id, p.name, args, true));
            }
        }
        TokenUsage usage = (inputTokens > 0 || outputTokens > 0)
                ? new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens)
                : null;
        listener.onCompleted(new AiResponse(fullText.toString(), fullThinking.toString(), usage, wireModel, getProviderId(), toolCalls));
    }

    private void parseSingle(String raw, String wireModel, AiStreamListener listener) {
        listener.onStateChanged(ExecutionState.STREAMING);
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        StringBuilder fullText = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        if (obj.has("content") && obj.get("content").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("content")) {
                if (!el.isJsonObject()) continue;
                JsonObject b = el.getAsJsonObject();
                String t = b.has("type") ? b.get("type").getAsString() : "";
                if ("thinking".equals(t) && b.has("thinking")) {
                    String s = b.get("thinking").getAsString();
                    fullThinking.append(s);
                    listener.onThinkingDelta(s);
                } else if ("text".equals(t) && b.has("text")) {
                    String s = b.get("text").getAsString();
                    fullText.append(s);
                    listener.onTextDelta(s);
                } else if ("tool_use".equals(t)) {
                    String id = b.has("id") ? b.get("id").getAsString() : ("anthropic_" + toolCalls.size());
                    String name = b.has("name") ? b.get("name").getAsString() : "";
                    JsonObject input = b.has("input") && b.get("input").isJsonObject()
                            ? b.getAsJsonObject("input")
                            : new JsonObject();
                    if (!name.isEmpty()) {
                        toolCalls.add(new ToolCallRequest(id, name, input, true));
                    }
                }
            }
        }
        TokenUsage usage = null;
        if (obj.has("usage") && obj.get("usage").isJsonObject()) {
            JsonObject u = obj.getAsJsonObject("usage");
            int in = u.has("input_tokens") ? u.get("input_tokens").getAsInt() : 0;
            int out = u.has("output_tokens") ? u.get("output_tokens").getAsInt() : 0;
            if (in > 0 || out > 0) usage = new TokenUsage(in, out, in + out);
        }
        listener.onCompleted(new AiResponse(fullText.toString(), fullThinking.toString(), usage, wireModel, getProviderId(), toolCalls));
    }

    @Override
    public List<AiModel> fetchModels(String apiKey) {
        return Collections.emptyList();
    }

    @Override
    public String testConnection(String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Missing Anthropic API Key");
        }
        String base = HttpErrorMapper.normalizeBaseUrl(config.getBaseUrl(), ModelProvider.ANTHROPIC.getDefaultBaseUrl());
        Request req = new Request.Builder()
                .url(base + "/models")
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", "2023-06-01")
                .get()
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw new IOException("HTTP " + res.code() + ": " + res.message());
            }
            return "Anthropic API OK";
        }
    }
}
