package com.chatai.an.ai.core;

import com.chatai.an.ai.provider.AiProvider;
import com.chatai.an.ai.provider.AiStreamListener;
import com.chatai.an.ai.provider.FreeAccessManager;
import com.chatai.an.ai.provider.ProviderFactory;
import com.chatai.an.ai.tool.RecallTool;
import com.chatai.an.ai.tool.RememberTool;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.AiResponse;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProviderConfig;
import com.chatai.an.domain.model.ToolCallRequest;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.ProviderRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central AI Execution Engine.
 * Orchestrates validation, provider resolution, streaming, cancellation, transient retry,
 * key rotation, safe model fallback, honest execution state transitions, and multi-step tool execution
 * (supporting both native OpenAI/Gemini/Anthropic tool calls and cross-provider <chatai_tool> tags).
 */
public class AiCore {
    public interface ExecutionNoticeListener {
        void onNotice(String notice);
    }

    public static final Pattern CHAT_TOOL_PATTERN = Pattern.compile(
            "<chatai_tool\\s+name=\"([a-zA-Z0-9_]+)\">\\s*(\\{.*?\\})\\s*</chatai_tool>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private final ProviderFactory providerFactory;
    private final ProviderRepository providerRepository;
    private final ModelRepository modelRepository;
    private final FreeAccessManager freeAccessManager;
    private final AppExecutors executors;
    private volatile ToolRegistry toolRegistry;
    private volatile ToolExecutor toolExecutor;

    public AiCore(
            ProviderFactory providerFactory,
            ProviderRepository providerRepository,
            ModelRepository modelRepository,
            FreeAccessManager freeAccessManager,
            AppExecutors executors
    ) {
        this.providerFactory = providerFactory;
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.freeAccessManager = freeAccessManager;
        this.executors = executors;
    }

    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public void setToolExecutor(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    public ExecutionHandle executeAsync(
            AiRequest request,
            AiStreamListener listener,
            ExecutionNoticeListener noticeListener
    ) {
        return executeAsync(request, "", listener, noticeListener);
    }

    public ExecutionHandle executeAsync(
            AiRequest request,
            String activeProjectId,
            AiStreamListener listener,
            ExecutionNoticeListener noticeListener
    ) {
        return executeAsync(request, activeProjectId, 4, null, listener, noticeListener);
    }

    public ExecutionHandle executeAsync(
            AiRequest request,
            String activeProjectId,
            int maxToolSteps,
            ToolExecutor.ConfirmationHandler confirmationHandler,
            AiStreamListener listener,
            ExecutionNoticeListener noticeListener
    ) {
        ExecutionHandle handle = new ExecutionHandle();
        int boundedSteps = Math.max(1, Math.min(10, maxToolSteps));
        executors.worker().execute(() -> executeWithTools(
                request,
                activeProjectId,
                boundedSteps,
                confirmationHandler,
                handle,
                listener,
                noticeListener
        ));
        return handle;
    }

    /**
     * Legacy method signature preserved for compatibility; delegates to multi-step tool loop.
     */
    public void executeWithMemoryTools(
            AiRequest request,
            String activeProjectId,
            ExecutionHandle handle,
            AiStreamListener outerListener,
            ExecutionNoticeListener noticeListener
    ) {
        executeWithTools(request, activeProjectId, 4, null, handle, outerListener, noticeListener);
    }

    /**
     * Multi-step chat tool execution loop supporting native tool_calls/functionCall/tool_use
     * as well as <chatai_tool name="...">...</chatai_tool> tags across up to maxToolSteps turns.
     */
    public void executeWithTools(
            AiRequest request,
            String activeProjectId,
            int maxToolSteps,
            ToolExecutor.ConfirmationHandler confirmationHandler,
            ExecutionHandle handle,
            AiStreamListener outerListener,
            ExecutionNoticeListener noticeListener
    ) {
        if ((toolRegistry == null && toolExecutor == null) || request == null) {
            executeBlocking(request, handle, outerListener, noticeListener);
            return;
        }

        AiRequest currentRequest = request;
        StringBuilder accumulatedThinking = new StringBuilder();
        StringBuilder accumulatedOutputPrefix = new StringBuilder();

        for (int step = 1; step <= maxToolSteps; step++) {
            if (handle != null && handle.isCancelled()) {
                outerListener.onStateChanged(ExecutionState.CANCELLED);
                outerListener.onError(new AiError(AiError.ErrorType.CANCELLED, "Generation stopped", 0, 0, ""));
                return;
            }

            final AtomicReference<AiResponse> stepResponse = new AtomicReference<>();
            final AtomicReference<AiError> stepError = new AtomicReference<>();
            final AtomicBoolean forwardedToUser = new AtomicBoolean(false);
            final StringBuilder suppressedBuffer = new StringBuilder();

            AiStreamListener interceptListener = new AiStreamListener() {
                @Override
                public void onStateChanged(ExecutionState state) {
                    if (state != ExecutionState.COMPLETED && state != ExecutionState.FAILED) {
                        outerListener.onStateChanged(state);
                    }
                }

                @Override
                public void onThinkingDelta(String delta) {
                    if (delta != null) {
                        accumulatedThinking.append(delta);
                    }
                    outerListener.onThinkingDelta(delta);
                }

                @Override
                public void onTextDelta(String delta) {
                    if (delta == null) return;
                    suppressedBuffer.append(delta);
                    String current = suppressedBuffer.toString();
                    if (!forwardedToUser.get()) {
                        String trimmed = current.trim();
                        if (trimmed.startsWith("<") && trimmed.length() < 16) {
                            return;
                        }
                        if (trimmed.startsWith("<chatai_tool")) {
                            return;
                        }
                        forwardedToUser.set(true);
                        outerListener.onTextDelta(current);
                        suppressedBuffer.setLength(0);
                    } else {
                        outerListener.onTextDelta(delta);
                        suppressedBuffer.setLength(0);
                    }
                }

                @Override
                public void onCompleted(AiResponse response) {
                    stepResponse.set(response);
                }

                @Override
                public void onError(AiError error) {
                    stepError.set(error);
                }
            };

            executeBlocking(currentRequest, handle, interceptListener, noticeListener);

            if (stepError.get() != null) {
                outerListener.onError(stepError.get());
                return;
            }

            AiResponse response = stepResponse.get();
            if (response == null) {
                return;
            }

            String rawContent = response.getContent();
            if (rawContent == null || rawContent.isEmpty()) {
                rawContent = suppressedBuffer.toString();
            }

            List<ToolInvocation> invocations = collectInvocations(response, rawContent);
            if (invocations.isEmpty()) {
                if (!forwardedToUser.get() && suppressedBuffer.length() > 0) {
                    outerListener.onTextDelta(suppressedBuffer.toString());
                }
                String finalText = stripToolTags(rawContent);
                if (accumulatedOutputPrefix.length() > 0 && !finalText.isEmpty()) {
                    finalText = accumulatedOutputPrefix + "\n\n" + finalText;
                } else if (finalText.isEmpty()) {
                    finalText = accumulatedOutputPrefix.toString();
                }
                outerListener.onStateChanged(ExecutionState.COMPLETED);
                outerListener.onCompleted(new AiResponse(
                        finalText,
                        accumulatedThinking.length() > 0 ? accumulatedThinking.toString() : response.getThinkingContent(),
                        response.getTokenUsage(),
                        response.getModelId(),
                        response.getProviderId()
                ));
                return;
            }

            // Guard against executing external/non-memory tools on models marked TOOL_CALLING == UNSUPPORTED
            boolean modelRejectsTools = currentRequest.getModel() != null
                    && currentRequest.getModel().getCapabilityStatus(ModelCapability.TOOL_CALLING) == CapabilityStatus.UNSUPPORTED;
            if (modelRejectsTools) {
                String cleanText = stripToolTags(rawContent);
                if (cleanText.isEmpty()) {
                    cleanText = "The active model (" + currentRequest.getModel().getName() + ") reports Tool Calling as Unsupported, so requested tools were not executed.";
                }
                if (noticeListener != null) {
                    noticeListener.onNotice("Model reports tool calling as Unsupported");
                }
                if (!forwardedToUser.get()) {
                    outerListener.onTextDelta(cleanText);
                }
                outerListener.onStateChanged(ExecutionState.COMPLETED);
                outerListener.onCompleted(new AiResponse(
                        cleanText,
                        accumulatedThinking.toString(),
                        response.getTokenUsage(),
                        response.getModelId(),
                        response.getProviderId()
                ));
                return;
            }

            // Emit any clean explanation text produced before tool invocation
            String stripped = stripToolTags(rawContent);
            if (!stripped.isEmpty()) {
                if (accumulatedOutputPrefix.length() > 0) accumulatedOutputPrefix.append("\n\n");
                accumulatedOutputPrefix.append(stripped);
            }

            outerListener.onStateChanged(ExecutionState.EXECUTING_TOOL);
            StringBuilder observations = new StringBuilder();
            boolean onlyMemorySave = true;

            for (ToolInvocation inv : invocations) {
                if (handle != null && handle.isCancelled()) {
                    outerListener.onStateChanged(ExecutionState.CANCELLED);
                    outerListener.onError(new AiError(AiError.ErrorType.CANCELLED, "Generation stopped", 0, 0, ""));
                    return;
                }

                if (!RememberTool.TOOL_NAME.equals(inv.toolName)) {
                    onlyMemorySave = false;
                }

                JsonObject args = inv.args != null ? inv.args.deepCopy() : new JsonObject();
                if (!args.has("_activeProjectId") && activeProjectId != null && !activeProjectId.isEmpty()) {
                    args.addProperty("_activeProjectId", activeProjectId);
                }
                if (!args.has("_activeConversationId") && currentRequest.getConversationId() != null) {
                    args.addProperty("_activeConversationId", currentRequest.getConversationId());
                }

                if (noticeListener != null) {
                    noticeListener.onNotice("Running tool: " + inv.toolName);
                }

                ToolResult result = toolExecutor != null
                        ? toolExecutor.executeSync(inv.toolName, args, handle, confirmationHandler)
                        : toolRegistry.executeTool(inv.toolName, args);

                String summary = result.isSuccess() ? result.getOutput() : ("Error: " + result.getError());
                if (summary.length() > 8000) {
                    summary = summary.substring(0, 8000) + "\n... [truncated]";
                }
                observations.append("Tool[").append(inv.toolName).append("] (")
                        .append(result.isSuccess() ? "SUCCESS" : "FAILED")
                        .append("):\n")
                        .append(summary)
                        .append("\n\n");

                if (noticeListener != null && RememberTool.TOOL_NAME.equals(inv.toolName) && result.isSuccess()) {
                    noticeListener.onNotice(result.getOutput());
                } else if (noticeListener != null && RecallTool.TOOL_NAME.equals(inv.toolName) && result.isSuccess()) {
                    noticeListener.onNotice("Recalled context from AI Memory");
                }
            }

            // Fast path: if only `remember` was called and model already gave final answer text
            if (onlyMemorySave && !stripped.isEmpty()) {
                if (!forwardedToUser.get()) {
                    outerListener.onTextDelta(stripped);
                }
                outerListener.onStateChanged(ExecutionState.COMPLETED);
                outerListener.onCompleted(new AiResponse(
                        accumulatedOutputPrefix.toString(),
                        accumulatedThinking.length() > 0 ? accumulatedThinking.toString() : response.getThinkingContent(),
                        response.getTokenUsage(),
                        response.getModelId(),
                        response.getProviderId()
                ));
                return;
            }

            if (step == maxToolSteps) {
                String fallbackSummary = accumulatedOutputPrefix.length() > 0
                        ? accumulatedOutputPrefix + "\n\n" + observations.toString().trim()
                        : observations.toString().trim();
                outerListener.onTextDelta("\n" + observations.toString().trim());
                outerListener.onStateChanged(ExecutionState.COMPLETED);
                outerListener.onCompleted(new AiResponse(
                        fallbackSummary,
                        accumulatedThinking.toString(),
                        response.getTokenUsage(),
                        response.getModelId(),
                        response.getProviderId()
                ));
                return;
            }

            // Prepare next turn with real observations
            List<Message> followUpMessages = new ArrayList<>(currentRequest.getContextMessages());
            long now = System.currentTimeMillis();
            String assistantTurnText = rawContent.isEmpty() ? ("[Requested tools: " + formatToolNames(invocations) + "]") : rawContent;
            followUpMessages.add(new Message(
                    UUID.randomUUID().toString(),
                    currentRequest.getConversationId(),
                    MessageRole.ASSISTANT,
                    assistantTurnText,
                    currentRequest.getModel().getId(),
                    false,
                    now,
                    now,
                    Collections.emptyList()
            ));
            followUpMessages.add(new Message(
                    UUID.randomUUID().toString(),
                    currentRequest.getConversationId(),
                    MessageRole.USER,
                    "Tool execution results (real device/network output):\n" + observations.toString().trim()
                            + "\n\nContinue solving the user's request. Call additional tools if more steps are required, or provide your clear final Markdown answer.",
                    currentRequest.getModel().getId(),
                    false,
                    now + 1,
                    now + 1,
                    Collections.emptyList()
            ));

            currentRequest = new AiRequest(
                    UUID.randomUUID().toString(),
                    currentRequest.getConversationId(),
                    currentRequest.getModel(),
                    currentRequest.getSystemPrompt(),
                    followUpMessages,
                    currentRequest.isDeepThinkingEnabled(),
                    currentRequest.isStreamingEnabled(),
                    currentRequest.getTools()
            );
        }
    }

    private static List<ToolInvocation> collectInvocations(AiResponse response, String rawContent) {
        List<ToolInvocation> list = new ArrayList<>();
        if (response != null && response.hasToolCalls()) {
            for (ToolCallRequest tc : response.getToolCalls()) {
                if (tc != null && !tc.getToolName().isEmpty()) {
                    list.add(new ToolInvocation(tc.getToolName().toLowerCase(Locale.ROOT), tc.getArguments()));
                }
            }
        }
        if (list.isEmpty()) {
            list.addAll(parseToolInvocations(rawContent));
        }
        return list;
    }

    private static String formatToolNames(List<ToolInvocation> invocations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < invocations.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(invocations.get(i).toolName);
        }
        return sb.toString();
    }

    public static List<ToolInvocation> parseToolInvocations(String text) {
        List<ToolInvocation> list = new ArrayList<>();
        if (text == null || text.isEmpty()) return list;
        Matcher matcher = CHAT_TOOL_PATTERN.matcher(text);
        while (matcher.find()) {
            String toolName = matcher.group(1);
            String jsonArgs = matcher.group(2);
            try {
                JsonObject obj = JsonParser.parseString(jsonArgs).getAsJsonObject();
                list.add(new ToolInvocation(toolName.toLowerCase(Locale.ROOT), obj));
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    public static String stripToolTags(String text) {
        if (text == null) return "";
        return CHAT_TOOL_PATTERN.matcher(text).replaceAll("").trim();
    }

    public void executeBlocking(
            AiRequest request,
            ExecutionHandle handle,
            AiStreamListener listener,
            ExecutionNoticeListener noticeListener
    ) {
        if (request == null || request.getModel() == null) {
            listener.onStateChanged(ExecutionState.FAILED);
            listener.onError(new AiError(AiError.ErrorType.PROTOCOL_ERROR, "No model selected", 0, 0, ""));
            return;
        }
        if (handle != null && handle.isCancelled()) {
            listener.onStateChanged(ExecutionState.CANCELLED);
            listener.onError(new AiError(AiError.ErrorType.CANCELLED, "Cancelled", 0, 0, ""));
            return;
        }

        listener.onStateChanged(ExecutionState.PREPARING);
        AiModel primaryModel = request.getModel();

        AttemptOutcome primaryOutcome = attemptWithRetryAndRotation(request, primaryModel, handle, listener, noticeListener);
        if (primaryOutcome.succeeded) {
            return;
        }
        if (handle != null && handle.isCancelled()) {
            listener.onStateChanged(ExecutionState.CANCELLED);
            listener.onError(new AiError(AiError.ErrorType.CANCELLED, "Generation stopped", 0, 0, primaryModel.getProvider().getId()));
            return;
        }

        if (!primaryOutcome.emittedAnyDelta && primaryOutcome.error != null && primaryOutcome.error.isEligibleForFallback()) {
            ProviderConfig primaryConfig = providerRepository.getProviderConfigSync(primaryModel.getProvider().getId());
            String fallbackId = primaryConfig != null ? primaryConfig.getFallbackModelId() : "";
            if (fallbackId != null && !fallbackId.trim().isEmpty() && !fallbackId.equals(primaryModel.getId())) {
                AiModel fallbackModel = modelRepository.getModelById(fallbackId.trim());
                if (fallbackModel != null && isFallbackAllowed(primaryModel, fallbackModel)) {
                    if (noticeListener != null) {
                        noticeListener.onNotice("Switching to fallback model: " + fallbackModel.getName());
                    }
                    AiRequest fallbackRequest = new AiRequest(
                            request.getRequestId(),
                            request.getConversationId(),
                            fallbackModel,
                            request.getSystemPrompt(),
                            request.getContextMessages(),
                            request.isDeepThinkingEnabled(),
                            request.isStreamingEnabled(),
                            request.getTools()
                    );
                    AttemptOutcome fbOutcome = attemptWithRetryAndRotation(fallbackRequest, fallbackModel, handle, listener, noticeListener);
                    if (fbOutcome.succeeded) {
                        return;
                    }
                    primaryOutcome = fbOutcome;
                }
            }
        }

        AiError finalError = primaryOutcome.error != null
                ? primaryOutcome.error
                : new AiError(AiError.ErrorType.UNKNOWN, "Unknown AI execution failure", 0, 0, primaryModel.getProvider().getId());
        listener.onStateChanged(finalError.getType() == AiError.ErrorType.CANCELLED ? ExecutionState.CANCELLED : ExecutionState.FAILED);
        listener.onError(finalError);
    }

    /**
     * Policy guard: Never silently switch a Free model request to a Personal API paid model.
     */
    public static boolean isFallbackAllowed(AiModel primary, AiModel fallback) {
        if (primary == null || fallback == null) return false;
        if (primary.getAccessType() == AccessType.FREE && fallback.getAccessType() == AccessType.PERSONAL_API) {
            return false;
        }
        return !primary.getId().equals(fallback.getId());
    }

    private AttemptOutcome attemptWithRetryAndRotation(
            AiRequest request,
            AiModel model,
            ExecutionHandle handle,
            AiStreamListener outerListener,
            ExecutionNoticeListener noticeListener
    ) {
        String providerId = model.getProvider().getId();
        ProviderConfig config = providerRepository.getProviderConfigSync(providerId);
        if (config != null && !config.isEnabled()) {
            return AttemptOutcome.failure(
                    new AiError(AiError.ErrorType.UNAVAILABLE, config.getDisplayName() + " is disabled in Provider Settings", 0, 0, providerId),
                    false
            );
        }

        if (model.getAccessType() == AccessType.FREE && freeAccessManager.isInCooldown(model.getProvider())) {
            return AttemptOutcome.failure(
                    new AiError(AiError.ErrorType.RATE_LIMIT, model.getProvider().getDisplayName() + " is currently rate-limited. Wait briefly or pick another model.", 429, 20, providerId),
                    false
            );
        }

        AiProvider provider = providerFactory.create(model.getProvider(), config);
        boolean acceptsKey = model.getAccessType() == AccessType.PERSONAL_API
                || model.getProvider() == ModelProvider.OPENROUTER_FREE;
        String activeKeyId = acceptsKey ? providerRepository.getActiveKeyIdSync(providerId) : null;
        String apiKey = acceptsKey ? providerRepository.getActiveDecryptedKeySync(providerId) : "";

        boolean rotationEnabled = config == null || config.isKeyRotationEnabled();
        int maxAttempts = 3;
        AttemptOutcome lastOutcome = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (handle != null && handle.isCancelled()) {
                return AttemptOutcome.failure(new AiError(AiError.ErrorType.CANCELLED, "Cancelled", 0, 0, providerId), false);
            }

            final AtomicBoolean emittedDelta = new AtomicBoolean(false);
            final AtomicBoolean completed = new AtomicBoolean(false);
            final AtomicBoolean thinkingActive = new AtomicBoolean(false);
            final AtomicBoolean respondingActive = new AtomicBoolean(false);
            final AtomicReference<AiError> attemptError = new AtomicReference<>();

            provider.execute(request, apiKey, handle, new AiStreamListener() {
                @Override
                public void onStateChanged(ExecutionState state) {
                    if (state != ExecutionState.FAILED && state != ExecutionState.STREAMING) {
                        outerListener.onStateChanged(state);
                    }
                }

                @Override
                public void onThinkingDelta(String delta) {
                    emittedDelta.set(true);
                    if (thinkingActive.compareAndSet(false, true)) {
                        outerListener.onStateChanged(ExecutionState.THINKING);
                    }
                    outerListener.onThinkingDelta(delta);
                }

                @Override
                public void onTextDelta(String delta) {
                    emittedDelta.set(true);
                    if (respondingActive.compareAndSet(false, true)) {
                        outerListener.onStateChanged(ExecutionState.STREAMING);
                    }
                    outerListener.onTextDelta(delta);
                }

                @Override
                public void onCompleted(AiResponse response) {
                    completed.set(true);
                    if (model.getAccessType() == AccessType.FREE) {
                        freeAccessManager.recordSuccess(model.getProvider(), 1);
                    }
                    outerListener.onStateChanged(ExecutionState.COMPLETED);
                    outerListener.onCompleted(response);
                }

                @Override
                public void onError(AiError error) {
                    attemptError.set(error);
                }
            });

            if (completed.get()) {
                return AttemptOutcome.success();
            }

            AiError err = attemptError.get();
            lastOutcome = AttemptOutcome.failure(err, emittedDelta.get());
            if (err == null || emittedDelta.get() || err.getType() == AiError.ErrorType.CANCELLED) {
                break;
            }

            if (model.getAccessType() == AccessType.FREE && err.getType() != AiError.ErrorType.MISSING_CREDENTIAL) {
                freeAccessManager.recordError(model.getProvider(), err);
            }

            if (acceptsKey
                    && rotationEnabled
                    && activeKeyId != null
                    && err.isRetryableByKeyRotation()) {
                long cooldownMs = Math.max(30_000L, err.getRetryAfterSeconds() * 1000L);
                String nextKey = providerRepository.rotateToNextAvailableKeySync(providerId, activeKeyId, err.getMessage(), cooldownMs);
                if (nextKey != null && !nextKey.isEmpty()) {
                    activeKeyId = providerRepository.getActiveKeyIdSync(providerId);
                    apiKey = nextKey;
                    if (noticeListener != null) {
                        noticeListener.onNotice("Rotated to backup API key for " + model.getProvider().getDisplayName());
                    }
                    continue;
                }
            }

            if (attempt == 0 && err.isRetryableByTransientRetry()) {
                if (noticeListener != null) {
                    noticeListener.onNotice("Retrying connection...");
                }
                try {
                    Thread.sleep(600L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            break;
        }

        return lastOutcome != null ? lastOutcome : AttemptOutcome.failure(
                new AiError(AiError.ErrorType.UNKNOWN, "Execution failed", 0, 0, providerId),
                false
        );
    }

    public static class ToolInvocation {
        public final String toolName;
        public final JsonObject args;

        public ToolInvocation(String toolName, JsonObject args) {
            this.toolName = toolName;
            this.args = args;
        }
    }

    private static class AttemptOutcome {
        final boolean succeeded;
        final AiError error;
        final boolean emittedAnyDelta;

        private AttemptOutcome(boolean succeeded, AiError error, boolean emittedAnyDelta) {
            this.succeeded = succeeded;
            this.error = error;
            this.emittedAnyDelta = emittedAnyDelta;
        }

        static AttemptOutcome success() {
            return new AttemptOutcome(true, null, true);
        }

        static AttemptOutcome failure(AiError error, boolean emittedAnyDelta) {
            return new AttemptOutcome(false, error, emittedAnyDelta);
        }
    }
}
