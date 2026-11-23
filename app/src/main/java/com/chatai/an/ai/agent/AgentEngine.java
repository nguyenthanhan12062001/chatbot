package com.chatai.an.ai.agent;

import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.core.AiCore;
import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.ai.provider.AiStreamListener;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AgentStep;
import com.chatai.an.domain.model.AgentTask;
import com.chatai.an.domain.model.AgentTaskStatus;
import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.AiResponse;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ToolCallRequest;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.AgentRepository;
import com.chatai.an.domain.repository.MemoryRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates multi-step Agent tasks for both Chat Agent and Coding Agent modes:
 * Analyze -> Plan -> Tool Exec (via ToolExecutor with confirmation/timeout/cancellation) -> Synthesize.
 */
public class AgentEngine {
    public interface AgentCallback {
        void onTaskUpdated(AgentTask task);

        void onConfirmationRequired(String taskId, String toolName, String argsPreview, ConfirmationDecision decisionCallback);
    }

    public interface ConfirmationDecision {
        void respond(boolean approved);
    }

    private final AiCore aiCore;
    private final ContextManager contextManager;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final MemoryRepository memoryRepository;
    private final AgentRepository agentRepository;
    private final SettingsRepository settingsRepository;
    private final AppExecutors executors;

    public AgentEngine(
            AiCore aiCore,
            ContextManager contextManager,
            ToolRegistry toolRegistry,
            MemoryRepository memoryRepository,
            AgentRepository agentRepository,
            AppExecutors executors
    ) {
        this(aiCore, contextManager, toolRegistry, null, memoryRepository, agentRepository, null, executors);
    }

    public AgentEngine(
            AiCore aiCore,
            ContextManager contextManager,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            MemoryRepository memoryRepository,
            AgentRepository agentRepository,
            SettingsRepository settingsRepository,
            AppExecutors executors
    ) {
        this.aiCore = aiCore;
        this.contextManager = contextManager;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.memoryRepository = memoryRepository;
        this.agentRepository = agentRepository;
        this.settingsRepository = settingsRepository;
        this.executors = executors;
    }

    public ExecutionHandle runTask(String goal, AiModel model, String conversationId, AgentCallback callback) {
        AppSettings settings = settingsRepository != null ? settingsRepository.getSettingsSnapshot() : null;
        AgentMode mode = settings != null ? settings.getDefaultAgentMode() : AgentMode.CHAT;
        String workspace = settings != null ? settings.getActiveWorkspacePath() : "";
        return runTask(goal, model, conversationId, mode, workspace, callback);
    }

    public ExecutionHandle runTask(
            String goal,
            AiModel model,
            String conversationId,
            AgentMode mode,
            String workspacePath,
            AgentCallback callback
    ) {
        ExecutionHandle handle = new ExecutionHandle();
        executors.worker().execute(() -> runBlocking(goal, model, conversationId, mode, workspacePath, handle, callback));
        return handle;
    }

    public void runBlocking(
            String goal,
            AiModel model,
            String conversationId,
            ExecutionHandle handle,
            AgentCallback callback
    ) {
        runBlocking(goal, model, conversationId, AgentMode.CHAT, "", handle, callback);
    }

    public void runBlocking(
            String goal,
            AiModel model,
            String conversationId,
            AgentMode mode,
            String workspacePath,
            ExecutionHandle handle,
            AgentCallback callback
    ) {
        long createdAt = System.currentTimeMillis();
        String taskId = UUID.randomUUID().toString();
        List<AgentStep> steps = new ArrayList<>();
        AgentMode effectiveMode = mode != null ? mode : AgentMode.CHAT;

        AgentTask task = new AgentTask(
                taskId,
                goal,
                conversationId,
                model != null ? model.getId() : "",
                AgentTaskStatus.ANALYZING,
                steps,
                "",
                "",
                createdAt,
                createdAt
        );
        persistAndNotify(task, callback);

        if (model == null) {
            failTask(task, steps, "No AI model selected.", callback);
            return;
        }
        if (model.getCapabilityStatus(ModelCapability.TOOL_CALLING) == CapabilityStatus.UNSUPPORTED) {
            failTask(task, steps, "Selected model (" + model.getName() + ") reports Tool Calling as UNSUPPORTED. Switch to a tool-capable model in Model Selector.", callback);
            return;
        }

        AppSettings settings = settingsRepository != null ? settingsRepository.getSettingsSnapshot() : null;
        int maxSteps = settings != null ? settings.getAgentMaxSteps() : 8;
        String effectiveWorkspace = (workspacePath != null && !workspacePath.trim().isEmpty())
                ? workspacePath.trim()
                : (settings != null ? settings.getActiveWorkspacePath() : "");
        List<ToolDefinition> allowedTools = toolRegistry.listDefinitionsForMode(effectiveMode, settings);

        List<Message> scratchConversation = new ArrayList<>();
        scratchConversation.add(new Message(
                UUID.randomUUID().toString(),
                taskId,
                MessageRole.USER,
                "Mode: " + effectiveMode.getTitle()
                        + (effectiveWorkspace.isEmpty() ? "" : ("\nWorkspace: " + effectiveWorkspace))
                        + "\nGoal: " + goal,
                model.getId(),
                false,
                createdAt,
                createdAt,
                Collections.emptyList()
        ));

        String systemPrompt = buildAgentSystemPrompt(effectiveMode, effectiveWorkspace, allowedTools);
        com.chatai.an.ai.memory.MemoryRetrievalEngine retrievalEngine =
                new com.chatai.an.ai.memory.MemoryRetrievalEngine(memoryRepository);
        List<MemoryItem> memories = retrievalEngine.selectContextualMemories(goal, "", conversationId, 600, 8);

        for (int turn = 1; turn <= maxSteps; turn++) {
            if (handle != null && handle.isCancelled()) {
                cancelTask(task, steps, callback);
                return;
            }

            AgentTaskStatus phase = turn == 1 ? AgentTaskStatus.PLANNING : AgentTaskStatus.SYNTHESIZING;
            task = updateTask(task, phase, steps, "", "", callback);

            AiRequest req = new AiRequest(
                    UUID.randomUUID().toString(),
                    taskId,
                    model,
                    contextManager.assembleSystemPrompt(systemPrompt, memories, false),
                    scratchConversation,
                    false,
                    false,
                    allowedTools
            );

            AtomicReference<AiResponse> responseRef = new AtomicReference<>();
            AtomicReference<AiError> errorRef = new AtomicReference<>();
            aiCore.executeBlocking(req, handle, new AiStreamListener() {
                @Override
                public void onStateChanged(ExecutionState state) {
                }

                @Override
                public void onThinkingDelta(String delta) {
                }

                @Override
                public void onTextDelta(String delta) {
                }

                @Override
                public void onCompleted(AiResponse response) {
                    responseRef.set(response);
                }

                @Override
                public void onError(AiError error) {
                    errorRef.set(error);
                }
            }, null);

            if (handle != null && handle.isCancelled()) {
                cancelTask(task, steps, callback);
                return;
            }

            if (errorRef.get() != null) {
                failTask(task, steps, errorRef.get().getMessage(), callback);
                return;
            }

            AiResponse resp = responseRef.get();
            String rawText = resp != null ? resp.getContent().trim() : "";

            // Prefer native protocol tool calls if returned; otherwise parse directive JSON or <chatai_tool>
            List<AgentDirective> directivesToExecute = new ArrayList<>();
            if (resp != null && resp.hasToolCalls()) {
                for (ToolCallRequest tc : resp.getToolCalls()) {
                    directivesToExecute.add(new AgentDirective("Native tool call: " + tc.getToolName(), tc.getToolName(), tc.getArguments(), ""));
                }
            } else {
                List<AiCore.ToolInvocation> tagCalls = AiCore.parseToolInvocations(rawText);
                if (!tagCalls.isEmpty()) {
                    for (AiCore.ToolInvocation inv : tagCalls) {
                        directivesToExecute.add(new AgentDirective("Tool tag: " + inv.toolName, inv.toolName, inv.args, ""));
                    }
                } else {
                    directivesToExecute.add(parseDirective(rawText));
                }
            }

            if (directivesToExecute.size() == 1 && "final_answer".equals(directivesToExecute.get(0).action)) {
                AgentDirective finalDir = directivesToExecute.get(0);
                if (finalDir.isExplicitJson || turn >= maxSteps) {
                    steps.add(new AgentStep(
                            steps.size() + 1,
                            "Step " + turn + ": Synthesize Answer",
                            finalDir.thought.isEmpty() ? "Completed reasoning" : finalDir.thought,
                            "",
                            System.currentTimeMillis()
                    ));
                    String finalAns = !finalDir.answer.isEmpty() ? finalDir.answer : rawText;
                    task = updateTask(task, AgentTaskStatus.COMPLETED, steps, finalAns, "", callback);
                    return;
                } else {
                    steps.add(new AgentStep(
                            steps.size() + 1,
                            "Step " + turn + ": Intermediate Plan / Thought",
                            rawText,
                            "",
                            System.currentTimeMillis()
                    ));
                    scratchConversation.add(new Message(
                            UUID.randomUUID().toString(), taskId, MessageRole.ASSISTANT,
                            rawText,
                            model.getId(), false, System.currentTimeMillis(), System.currentTimeMillis(), Collections.emptyList()
                    ));
                    scratchConversation.add(new Message(
                            UUID.randomUUID().toString(), taskId, MessageRole.USER,
                            "Please continue executing the goal. Output either the next tool invocation JSON (e.g. {\"thought\":\"...\",\"action\":\"tool_name\",\"args\":{...}}) or when fully completed, output {\"thought\":\"...\",\"action\":\"final_answer\",\"answer\":\"...\"}.",
                            model.getId(), false, System.currentTimeMillis(), System.currentTimeMillis(), Collections.emptyList()
                    ));
                    continue;
                }
            }

            StringBuilder turnObservations = new StringBuilder();
            final AgentTask[] taskHolder = new AgentTask[]{task};

            for (AgentDirective directive : directivesToExecute) {
                if (handle != null && handle.isCancelled()) {
                    cancelTask(taskHolder[0], steps, callback);
                    return;
                }
                if (toolRegistry.getTool(directive.action) == null) {
                    steps.add(new AgentStep(
                            steps.size() + 1,
                            "Step " + turn + ": Model Message / Unknown Tool",
                            rawText,
                            "",
                            System.currentTimeMillis()
                    ));
                    scratchConversation.add(new Message(
                            UUID.randomUUID().toString(), taskId, MessageRole.ASSISTANT,
                            rawText,
                            model.getId(), false, System.currentTimeMillis(), System.currentTimeMillis(), Collections.emptyList()
                    ));
                    scratchConversation.add(new Message(
                            UUID.randomUUID().toString(), taskId, MessageRole.USER,
                            "Warning: Tool '" + directive.action + "' does not exist or model responded in prose. Please use a valid tool from the catalog or output final_answer JSON.",
                            model.getId(), false, System.currentTimeMillis(), System.currentTimeMillis(), Collections.emptyList()
                    ));
                    taskHolder[0] = updateTask(taskHolder[0], AgentTaskStatus.SYNTHESIZING, steps, "", "", callback);
                    task = taskHolder[0];
                    continue;
                }

                steps.add(new AgentStep(
                        steps.size() + 1,
                        "Step " + turn + ": Plan Tool " + directive.action,
                        directive.thought.isEmpty() ? "Executing " + directive.action : directive.thought,
                        directive.action,
                        System.currentTimeMillis()
                ));
                taskHolder[0] = updateTask(taskHolder[0], AgentTaskStatus.EXECUTING_TOOL, steps, "", "", callback);

                ToolExecutor.ConfirmationHandler confirmBridge = callback == null ? null : (execId, tName, reason, argsPreview, cb) -> {
                    taskHolder[0] = updateTask(taskHolder[0], AgentTaskStatus.WAITING_CONFIRMATION, steps, "", "", callback);
                    callback.onConfirmationRequired(taskId, tName, reason + "\n\nArgs: " + argsPreview, approved -> {
                        cb.onResult(approved);
                    });
                };

                ToolResult toolResult = toolExecutor != null
                        ? toolExecutor.executeSync(directive.action, directive.args, handle, confirmBridge)
                        : toolRegistry.executeTool(directive.action, directive.args);

                if (handle != null && handle.isCancelled()) {
                    cancelTask(taskHolder[0], steps, callback);
                    return;
                }

                String resultSummary = toolResult.isSuccess() ? toolResult.getOutput() : ("Error: " + toolResult.getError());
                steps.add(new AgentStep(
                        steps.size() + 1,
                        "Tool Result: " + directive.action + (toolResult.isSuccess() ? " (OK)" : " (Error)"),
                        resultSummary,
                        directive.action,
                        System.currentTimeMillis()
                ));
                taskHolder[0] = updateTask(taskHolder[0], AgentTaskStatus.EXECUTING_TOOL, steps, "", "", callback);

                turnObservations.append("Observation from ").append(directive.action).append(":\n")
                        .append(resultSummary)
                        .append("\n\n");
            }
            task = taskHolder[0];

            scratchConversation.add(new Message(
                    UUID.randomUUID().toString(), taskId, MessageRole.ASSISTANT,
                    rawText.isEmpty() ? "[Executed tools]" : rawText,
                    model.getId(), false, System.currentTimeMillis(), System.currentTimeMillis(), Collections.emptyList()
            ));
            scratchConversation.add(new Message(
                    UUID.randomUUID().toString(), taskId, MessageRole.USER,
                    turnObservations.toString().trim()
                            + "\n\nProduce either the next tool JSON or {\"thought\":\"...\",\"action\":\"final_answer\",\"answer\":\"...\"}.",
                    model.getId(), false, System.currentTimeMillis(), System.currentTimeMillis(), Collections.emptyList()
            ));
        }

        failTask(task, steps, "Reached maximum reasoning steps (" + maxSteps + ") without final_answer.", callback);
    }

    private String buildAgentSystemPrompt(AgentMode mode, String workspacePath, List<ToolDefinition> allowedTools) {
        StringBuilder sb = new StringBuilder();
        if (mode == AgentMode.CODING) {
            sb.append("Your name is AnAI (Coding Agent Engine). Inspect files, search codebases, make surgical edits, and run Termux or ADB diagnostics to complete software engineering goals accurately.\n");
            if (!workspacePath.isEmpty()) {
                sb.append("Active Workspace: ").append(workspacePath).append("\n");
            }
        } else {
            sb.append("Your name is AnAI. Solve the user's goal step by step using the enabled tools when needed.\n");
        }
        sb.append("Available tools:\n")
                .append(ToolRegistry.formatCatalogSummary(allowedTools))
                .append("\nRespond ONLY with a single JSON object in one of these exact forms (or emit native tool calls):\n")
                .append("1) To invoke a tool: {\"thought\":\"why\",\"action\":\"tool_name\",\"args\":{...}}\n")
                .append("2) When ready with final response: {\"thought\":\"summary\",\"action\":\"final_answer\",\"answer\":\"markdown response\"}");
        return sb.toString();
    }

    public static AgentDirective parseDirective(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return new AgentDirective("", "final_answer", new JsonObject(), "", false);
        }
        String candidate = rawText.trim();
        int firstBrace = candidate.indexOf('{');
        int lastBrace = candidate.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String jsonSlice = candidate.substring(firstBrace, lastBrace + 1);
            try {
                JsonObject obj = JsonParser.parseString(jsonSlice).getAsJsonObject();
                String thought = obj.has("thought") ? obj.get("thought").getAsString() : "";
                String action = obj.has("action") ? obj.get("action").getAsString() : "final_answer";
                JsonObject args = obj.has("args") && obj.get("args").isJsonObject()
                        ? obj.getAsJsonObject("args")
                        : new JsonObject();
                String answer = obj.has("answer") ? obj.get("answer").getAsString() : "";
                return new AgentDirective(thought, action, args, answer, true);
            } catch (Exception ignored) {
            }
        }
        return new AgentDirective("Direct model response", "final_answer", new JsonObject(), rawText, false);
    }

    private AgentTask updateTask(
            AgentTask prev,
            AgentTaskStatus status,
            List<AgentStep> steps,
            String finalResult,
            String errorMsg,
            AgentCallback callback
    ) {
        AgentTask next = new AgentTask(
                prev.getId(),
                prev.getGoal(),
                prev.getConversationId(),
                prev.getModelId(),
                status,
                steps,
                finalResult,
                errorMsg,
                prev.getCreatedAt(),
                System.currentTimeMillis()
        );
        persistAndNotify(next, callback);
        return next;
    }

    private void failTask(AgentTask prev, List<AgentStep> steps, String errorMessage, AgentCallback callback) {
        updateTask(prev, AgentTaskStatus.FAILED, steps, "", errorMessage, callback);
    }

    private void cancelTask(AgentTask prev, List<AgentStep> steps, AgentCallback callback) {
        updateTask(prev, AgentTaskStatus.CANCELLED, steps, "", "Stopped by user", callback);
    }

    private void persistAndNotify(AgentTask task, AgentCallback callback) {
        agentRepository.saveTask(task);
        if (callback != null) {
            executors.postToMain(() -> callback.onTaskUpdated(task));
        }
    }

    public static class AgentDirective {
        public final String thought;
        public final String action;
        public final JsonObject args;
        public final String answer;
        public final boolean isExplicitJson;

        public AgentDirective(String thought, String action, JsonObject args, String answer, boolean isExplicitJson) {
            this.thought = thought != null ? thought : "";
            this.action = action != null ? action : "final_answer";
            this.args = args != null ? args : new JsonObject();
            this.answer = answer != null ? answer : "";
            this.isExplicitJson = isExplicitJson;
        }

        public AgentDirective(String thought, String action, JsonObject args, String answer) {
            this(thought, action, args, answer, true);
        }
    }
}
