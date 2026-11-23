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
import com.chatai.an.domain.model.SourceItem;
import com.chatai.an.domain.model.TimelineEntry;
import com.chatai.an.domain.model.ToolCallRecord;
import com.chatai.an.domain.model.ToolCallRequest;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.ProviderRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        return executeAsync(request, activeProjectId, maxToolSteps, confirmationHandler, null, listener, noticeListener);
    }

    public ExecutionHandle executeAsync(
            AiRequest request,
            String activeProjectId,
            int maxToolSteps,
            ToolExecutor.ConfirmationHandler confirmationHandler,
            ToolExecutor.ToolLifecycleListener toolLifecycleListener,
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
                toolLifecycleListener,
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
        executeWithTools(request, activeProjectId, 4, null, null, handle, outerListener, noticeListener);
    }

    public void executeWithTools(
            AiRequest request,
            String activeProjectId,
            int maxToolSteps,
            ToolExecutor.ConfirmationHandler confirmationHandler,
            ExecutionHandle handle,
            AiStreamListener outerListener,
            ExecutionNoticeListener noticeListener
    ) {
        executeWithTools(request, activeProjectId, maxToolSteps, confirmationHandler, null, handle, outerListener, noticeListener);
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
            ToolExecutor.ToolLifecycleListener toolLifecycleListener,
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
        final Map<String, ToolCallRecord> toolRecordMap = Collections.synchronizedMap(new LinkedHashMap<>());
        final List<TimelineEntry> timelineEntries = Collections.synchronizedList(new ArrayList<>());
        final Set<String> timelineToolIds = Collections.synchronizedSet(new LinkedHashSet<>());
        final Map<String, SourceItem> sourceMap = Collections.synchronizedMap(new LinkedHashMap<>());

        ToolExecutor.ToolLifecycleListener aggregatedLifecycle = record -> {
            if (record != null) {
                toolRecordMap.put(record.getExecutionId(), record);
                if (timelineToolIds.add(record.getExecutionId())) {
                    timelineEntries.add(new TimelineEntry(
                            "tool-" + record.getExecutionId(),
                            TimelineEntry.Type.TOOL_CALL,
                            record.getToolName(),
                            record.getReason(),
                            record.getExecutionId(),
                            record.getStartedAt() > 0 ? record.getStartedAt() : System.currentTimeMillis()
                    ));
                }
                outerListener.onTimelineUpdated(snapshotTimeline(timelineEntries), snapshotSources(sourceMap));
            }
            if (toolLifecycleListener != null) {
                toolLifecycleListener.onToolUpdated(record);
            }
        };

        for (int step = 1; step <= maxToolSteps; step++) {
            if (handle != null && handle.isCancelled()) {
                outerListener.onStateChanged(ExecutionState.CANCELLED);
                outerListener.onError(new AiError(AiError.ErrorType.CANCELLED, "Generation stopped", 0, 0, ""));
                return;
            }

            final int currentStep = step;
            final AtomicReference<AiResponse> stepResponse = new AtomicReference<>();
            final AtomicReference<AiError> stepError = new AtomicReference<>();
            final AtomicBoolean forwardedToUser = new AtomicBoolean(false);
            final StringBuilder suppressedBuffer = new StringBuilder();
            final StringBuilder stepThinking = new StringBuilder();
            final String stepThinkingId = "think-step-" + currentStep;

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
                        stepThinking.append(delta);
                        upsertTimelineEntry(
                                timelineEntries,
                                new TimelineEntry(
                                        stepThinkingId,
                                        TimelineEntry.Type.THINKING,
                                        "AI Thinking",
                                        stepThinking.toString(),
                                        "",
                                        System.currentTimeMillis()
                                )
                        );
                        outerListener.onTimelineUpdated(snapshotTimeline(timelineEntries), snapshotSources(sourceMap));
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
                        if (trimmed.startsWith("<") && trimmed.length() < 20) {
                            return;
                        }
                        if (trimmed.startsWith("<chatai_tool") || trimmed.startsWith("<tool_call") || trimmed.startsWith("<chatai_followups")) {
                            return;
                        }
                        forwardedToUser.set(true);
                        outerListener.onTextDelta(current);
                        suppressedBuffer.setLength(0);
                    } else {
                        String lower = current.toLowerCase(Locale.ROOT);
                        if (lower.contains("<chatai_followups") || lower.contains("<chatai_tool") || lower.contains("<tool_call")) {
                            return;
                        }
                        if (current.endsWith("<") || lower.endsWith("<c") || lower.endsWith("<t") || lower.endsWith("<chatai")) {
                            return;
                        }
                        outerListener.onTextDelta(current);
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

            // Ensure non-streamed thinkingContent from response is also captured in timeline
            if (stepThinking.length() == 0 && response.getThinkingContent() != null && !response.getThinkingContent().trim().isEmpty()) {
                String t = response.getThinkingContent().trim();
                accumulatedThinking.append(accumulatedThinking.length() > 0 ? "\n\n" : "").append(t);
                upsertTimelineEntry(
                        timelineEntries,
                        new TimelineEntry(stepThinkingId, TimelineEntry.Type.THINKING, "AI Thinking", t, "", System.currentTimeMillis())
                );
                outerListener.onTimelineUpdated(snapshotTimeline(timelineEntries), snapshotSources(sourceMap));
            }

            List<ToolInvocation> invocations = collectInvocations(response, rawContent);
            if (invocations.isEmpty()) {
                if (!forwardedToUser.get() && suppressedBuffer.length() > 0) {
                    String cleanBuffered = stripControlTags(suppressedBuffer.toString());
                    if (!cleanBuffered.isEmpty()) {
                        outerListener.onTextDelta(cleanBuffered);
                    }
                }
                List<String> followUps = FollowUpSuggestionHelper.extractSuggestions(rawContent);
                String finalText = stripControlTags(rawContent);
                if (finalText.isEmpty() && accumulatedOutputPrefix.length() > 0) {
                    finalText = accumulatedOutputPrefix.toString();
                }
                collectUrlsFromText(finalText, sourceMap);
                List<SourceItem> finalSources = snapshotSources(sourceMap);
                if (!finalSources.isEmpty()) {
                    timelineEntries.add(new TimelineEntry(
                            "sources-final",
                            TimelineEntry.Type.SOURCES,
                            "Sources (" + finalSources.size() + ")",
                            "",
                            "",
                            System.currentTimeMillis()
                    ));
                }
                List<TimelineEntry> finalTimeline = snapshotTimeline(timelineEntries);
                outerListener.onTimelineUpdated(finalTimeline, finalSources);

                List<ToolCallRecord> finalRecords = new ArrayList<>(toolRecordMap.values());
                outerListener.onStateChanged(ExecutionState.COMPLETED);
                outerListener.onCompleted(new AiResponse(
                        finalText,
                        accumulatedThinking.length() > 0 ? accumulatedThinking.toString() : response.getThinkingContent(),
                        response.getTokenUsage(),
                        response.getModelId(),
                        response.getProviderId(),
                        Collections.emptyList(),
                        finalRecords,
                        followUps,
                        finalTimeline,
                        finalSources
                ));
                return;
            }

            // Emit any clean commentary narration produced before tool invocation
            String stripped = stripControlTags(rawContent);
            if (!stripped.isEmpty()) {
                if (accumulatedOutputPrefix.length() > 0) accumulatedOutputPrefix.append("\n\n");
                accumulatedOutputPrefix.append(stripped);
                timelineEntries.add(new TimelineEntry(
                        "commentary-step-" + currentStep,
                        TimelineEntry.Type.COMMENTARY,
                        "AI Commentary",
                        stripped,
                        "",
                        System.currentTimeMillis()
                ));
                outerListener.onCommentaryDelta(stripped);
                outerListener.onTimelineUpdated(snapshotTimeline(timelineEntries), snapshotSources(sourceMap));
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
                        ? toolExecutor.executeSync(inv.toolName, args, handle, confirmationHandler, aggregatedLifecycle)
                        : toolRegistry.executeTool(inv.toolName, args);

                collectSourcesFromTool(inv.toolName, args, result, sourceMap);
                outerListener.onTimelineUpdated(snapshotTimeline(timelineEntries), snapshotSources(sourceMap));

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
                List<SourceItem> finalSources = snapshotSources(sourceMap);
                List<TimelineEntry> finalTimeline = snapshotTimeline(timelineEntries);
                List<ToolCallRecord> finalRecords = new ArrayList<>(toolRecordMap.values());
                outerListener.onTimelineUpdated(finalTimeline, finalSources);
                outerListener.onStateChanged(ExecutionState.COMPLETED);
                outerListener.onCompleted(new AiResponse(
                        stripped,
                        accumulatedThinking.length() > 0 ? accumulatedThinking.toString() : response.getThinkingContent(),
                        response.getTokenUsage(),
                        response.getModelId(),
                        response.getProviderId(),
                        Collections.emptyList(),
                        finalRecords,
                        FollowUpSuggestionHelper.extractSuggestions(rawContent),
                        finalTimeline,
                        finalSources
                ));
                return;
            }

            if (step == maxToolSteps) {
                String fallbackSummary = observations.toString().trim();
                if (fallbackSummary.isEmpty()) {
                    fallbackSummary = accumulatedOutputPrefix.toString();
                }
                outerListener.onTextDelta("\n" + fallbackSummary);
                List<SourceItem> finalSources = snapshotSources(sourceMap);
                if (!finalSources.isEmpty()) {
                    timelineEntries.add(new TimelineEntry(
                            "sources-final",
                            TimelineEntry.Type.SOURCES,
                            "Sources (" + finalSources.size() + ")",
                            "",
                            "",
                            System.currentTimeMillis()
                    ));
                }
                List<TimelineEntry> finalTimeline = snapshotTimeline(timelineEntries);
                List<ToolCallRecord> finalRecords = new ArrayList<>(toolRecordMap.values());
                outerListener.onTimelineUpdated(finalTimeline, finalSources);
                outerListener.onStateChanged(ExecutionState.COMPLETED);
                outerListener.onCompleted(new AiResponse(
                        fallbackSummary,
                        accumulatedThinking.toString(),
                        response.getTokenUsage(),
                        response.getModelId(),
                        response.getProviderId(),
                        Collections.emptyList(),
                        finalRecords,
                        Collections.emptyList(),
                        finalTimeline,
                        finalSources
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

    private static void upsertTimelineEntry(List<TimelineEntry> list, TimelineEntry entry) {
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                if (entry.getId().equals(list.get(i).getId())) {
                    list.set(i, entry);
                    return;
                }
            }
            list.add(entry);
        }
    }

    private static List<TimelineEntry> snapshotTimeline(List<TimelineEntry> list) {
        synchronized (list) {
            return list.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    private static List<SourceItem> snapshotSources(Map<String, SourceItem> map) {
        synchronized (map) {
            return map.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(map.values()));
        }
    }

    private static void collectSourcesFromTool(
            String toolName,
            JsonObject args,
            ToolResult result,
            Map<String, SourceItem> sourceMap
    ) {
        if (toolName == null || sourceMap == null) return;
        if ("web_fetch".equals(toolName) || "http_get".equals(toolName) || "http_post".equals(toolName) || "download_file".equals(toolName)) {
            String url = args != null && args.has("url") ? args.get("url").getAsString().trim() : "";
            if (!url.isEmpty()) {
                sourceMap.putIfAbsent(url, new SourceItem(url, url, SourceItem.SourceType.WEB, toolName));
            }
        } else if ("internet_search".equals(toolName) && result != null && result.isSuccess()) {
            collectUrlsFromText(result.getOutput(), sourceMap);
        } else if (toolName.startsWith("fs_") && args != null && args.has("path")) {
            String path = args.get("path").getAsString().trim();
            if (!path.isEmpty()) {
                sourceMap.putIfAbsent("file:" + path, new SourceItem(path, path, SourceItem.SourceType.FILE, toolName));
            }
        } else if ("terminal_run".equals(toolName) || "termux_run".equals(toolName) || "adb_shell".equals(toolName)) {
            String cmd = args != null && args.has("command") ? args.get("command").getAsString().trim() : "";
            if (!cmd.isEmpty()) {
                String key = "tool:" + toolName + ":" + cmd;
                sourceMap.putIfAbsent(key, new SourceItem(toolName + ": " + cmd, cmd, SourceItem.SourceType.TOOL, ""));
            }
        }
    }

    private static final Pattern URL_EXTRACT_PATTERN = Pattern.compile("https?://[^\\s<>()\\]\"']+");

    private static void collectUrlsFromText(String text, Map<String, SourceItem> sourceMap) {
        if (text == null || text.isEmpty() || sourceMap == null) return;
        Matcher m = URL_EXTRACT_PATTERN.matcher(text);
        while (m.find()) {
            String url = m.group();
            while (url.endsWith(".") || url.endsWith(",") || url.endsWith(";") || url.endsWith(")")) {
                url = url.substring(0, url.length() - 1);
            }
            if (url.length() > 10) {
                sourceMap.putIfAbsent(url, new SourceItem(url, url, SourceItem.SourceType.WEB, ""));
            }
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

    private static final Pattern CHAT_TOOL_OPEN_PATTERN = Pattern.compile(
            "<chatai_tool\\s+name\\s*=\\s*[\"']([a-zA-Z0-9_]+)[\"']\\s*>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOOL_CALL_OPEN_PATTERN = Pattern.compile(
            "<tool_call\\s*>",
            Pattern.CASE_INSENSITIVE
    );

    public static List<ToolInvocation> parseToolInvocations(String text) {
        List<ToolInvocation> list = new ArrayList<>();
        if (text == null || text.isEmpty()) return list;

        // 1. Parse <chatai_tool name="..."> { ... } </chatai_tool> using string-aware brace balancing
        Matcher m = CHAT_TOOL_OPEN_PATTERN.matcher(text);
        while (m.find()) {
            String toolName = m.group(1);
            int bodyStart = m.end();
            int[] jsonBounds = findBalancedJsonObject(text, bodyStart);
            if (jsonBounds != null) {
                String jsonArgs = text.substring(jsonBounds[0], jsonBounds[1]);
                try {
                    JsonObject obj = JsonParser.parseString(jsonArgs).getAsJsonObject();
                    list.add(new ToolInvocation(toolName.toLowerCase(Locale.ROOT), obj));
                } catch (Exception ignored) {
                }
            }
        }

        // 2. Also support <tool_call>{"name":"...","arguments":{...}}</tool_call> emitted by some open models
        Matcher tcMatcher = TOOL_CALL_OPEN_PATTERN.matcher(text);
        while (tcMatcher.find()) {
            int[] jsonBounds = findBalancedJsonObject(text, tcMatcher.end());
            if (jsonBounds != null) {
                String rawJson = text.substring(jsonBounds[0], jsonBounds[1]);
                try {
                    JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
                    String name = root.has("name") && root.get("name").isJsonPrimitive()
                            ? root.get("name").getAsString().trim()
                            : "";
                    if (!name.isEmpty()) {
                        JsonObject args = new JsonObject();
                        JsonElement argsEl = root.has("arguments") ? root.get("arguments") : root.get("args");
                        if (argsEl != null) {
                            if (argsEl.isJsonObject()) {
                                args = argsEl.getAsJsonObject();
                            } else if (argsEl.isJsonPrimitive()) {
                                JsonElement parsed = JsonParser.parseString(argsEl.getAsString());
                                if (parsed.isJsonObject()) args = parsed.getAsJsonObject();
                            }
                        }
                        list.add(new ToolInvocation(name.toLowerCase(Locale.ROOT), args));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return list;
    }

    /**
     * Scans for a balanced `{ ... }` JSON object starting at/after `searchFrom`,
     * properly skipping `{` and `}` inside quoted JSON string literals.
     */
    private static int[] findBalancedJsonObject(String text, int searchFrom) {
        int len = text.length();
        int idx = searchFrom;
        while (idx < len && Character.isWhitespace(text.charAt(idx))) {
            idx++;
        }
        if (idx >= len || text.charAt(idx) != '{') {
            return null;
        }
        int start = idx;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < len; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return new int[]{start, i + 1};
                    }
                }
            }
        }
        return null;
    }

    public static String stripToolTags(String text) {
        if (text == null || text.isEmpty()) return "";
        String result = stripNamedToolBlocks(text, "<chatai_tool", "</chatai_tool>");
        result = stripNamedToolBlocks(result, "<tool_call", "</tool_call>");
        result = result.replaceAll("\\n{3,}", "\n\n");
        return result.trim();
    }

    private static String stripNamedToolBlocks(String text, String openPrefix, String closeTag) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        while (cursor < text.length()) {
            int start = lower.indexOf(openPrefix, cursor);
            if (start < 0) {
                out.append(text, cursor, text.length());
                break;
            }
            out.append(text, cursor, start);
            int openGt = text.indexOf('>', start);
            if (openGt < 0) {
                break;
            }
            int[] jsonBounds = findBalancedJsonObject(text, openGt + 1);
            int searchCloseFrom = jsonBounds != null ? jsonBounds[1] : (openGt + 1);
            int closeStart = lower.indexOf(closeTag, searchCloseFrom);
            if (closeStart < 0) {
                // Partial/unclosed streaming tag at tail: drop from start to end
                break;
            }
            cursor = closeStart + closeTag.length();
        }
        return out.toString();
    }

    public static String stripControlTags(String text) {
        if (text == null) return "";
        return FollowUpSuggestionHelper.stripFollowUpTags(stripToolTags(text));
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
