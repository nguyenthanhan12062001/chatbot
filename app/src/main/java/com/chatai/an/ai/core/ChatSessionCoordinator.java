package com.chatai.an.ai.core;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.memory.ConversationCompactor;
import com.chatai.an.ai.memory.MemoryRetrievalEngine;
import com.chatai.an.ai.provider.AiStreamListener;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.AiResponse;
import com.chatai.an.domain.model.AppLanguage;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.SourceItem;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThinkingState;
import com.chatai.an.domain.model.TimelineEntry;
import com.chatai.an.domain.model.ToolCallRecord;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.repository.ConversationRepository;
import com.chatai.an.domain.repository.MemoryRepository;
import com.chatai.an.domain.repository.MessageRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared singleton reactive coordinator for chat sessions across MainActivity and ChatBubbleService.
 * Guarantees live 1:1 synchronization of conversation selection, Chat/Code mode, deep thinking,
 * streaming chunks, inline tool execution cards & confirmations, follow-up queue, and context metrics.
 */
public class ChatSessionCoordinator {
    public static final String STREAMING_MESSAGE_ID = "__streaming_assistant_preview__";

    public static class PendingToolConfirmation {
        public final String executionId;
        public final String toolName;
        public final String reason;
        public final String argsPreview;
        public final ToolExecutor.ConfirmationCallback callback;

        public PendingToolConfirmation(
                String executionId,
                String toolName,
                String reason,
                String argsPreview,
                ToolExecutor.ConfirmationCallback callback
        ) {
            this.executionId = executionId;
            this.toolName = toolName;
            this.reason = reason;
            this.argsPreview = argsPreview;
            this.callback = callback;
        }
    }

    public static class QueuedMessage {
        public final String id;
        public final String text;
        public final List<Attachment> attachments;
        public final long queuedAt;

        public QueuedMessage(String id, String text, List<Attachment> attachments, long queuedAt) {
            this.id = id;
            this.text = text != null ? text : "";
            this.attachments = attachments == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(attachments));
            this.queuedAt = queuedAt;
        }
    }

    public static class ContextMetrics {
        public final int systemTokens;
        public final int memoryTokens;
        public final int summaryTokens;
        public final int historyTokens;
        public final int totalTokens;
        public final int windowTokens;
        public final int fillPercent;
        public final int messageCount;
        public final boolean compacted;

        public ContextMetrics(
                int systemTokens,
                int memoryTokens,
                int summaryTokens,
                int historyTokens,
                int totalTokens,
                int windowTokens,
                int fillPercent,
                int messageCount,
                boolean compacted
        ) {
            this.systemTokens = systemTokens;
            this.memoryTokens = memoryTokens;
            this.summaryTokens = summaryTokens;
            this.historyTokens = historyTokens;
            this.totalTokens = totalTokens;
            this.windowTokens = windowTokens;
            this.fillPercent = fillPercent;
            this.messageCount = messageCount;
            this.compacted = compacted;
        }

        public static ContextMetrics empty() {
            return new ContextMetrics(0, 0, 0, 0, 0, 32000, 0, 0, false);
        }
    }

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ModelRepository modelRepository;
    private final ThemeRepository themeRepository;
    private final SettingsRepository settingsRepository;
    private final MemoryRepository memoryRepository;
    private final MemoryRetrievalEngine memoryRetrievalEngine;
    private final ConversationCompactor conversationCompactor;
    private final ContextManager contextManager;
    private final AiCore aiCore;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final WorkspaceFileManager workspaceFileManager;
    private final AppExecutors executors;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<String> activeConversationId = new MutableLiveData<>(null);
    private final MutableLiveData<String> activeProjectId = new MutableLiveData<>("");
    private final MutableLiveData<AgentMode> agentMode = new MutableLiveData<>(AgentMode.CHAT);
    private final MutableLiveData<PendingToolConfirmation> pendingToolConfirmation = new MutableLiveData<>(null);
    private final LiveData<List<Message>> persistedMessages;
    private final MediatorLiveData<List<Message>> messages = new MediatorLiveData<>(Collections.emptyList());
    private final LiveData<List<Conversation>> allConversations;
    private final LiveData<AiModel> selectedModel;
    private final LiveData<ThemeConfig> activeTheme;
    private final LiveData<AppSettings> settings;
    private final MutableLiveData<List<Attachment>> pendingAttachments = new MutableLiveData<>(Collections.emptyList());
    private final MediatorLiveData<ThinkingState> thinkingState = new MediatorLiveData<>();

    private final MutableLiveData<ExecutionState> executionState = new MutableLiveData<>(ExecutionState.IDLE);
    private final MutableLiveData<String> executionErrorDetail = new MutableLiveData<>(null);
    private final MutableLiveData<String> executionNotice = new MutableLiveData<>(null);
    private final MutableLiveData<Message> streamingPreview = new MutableLiveData<>(null);
    private final MutableLiveData<List<QueuedMessage>> followUpQueue = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<ContextMetrics> contextMetrics = new MutableLiveData<>(ContextMetrics.empty());

    private final ConcurrentHashMap<String, PendingToolConfirmation> pendingConfirmationsById = new ConcurrentHashMap<>();
    private final List<QueuedMessage> queueBacking = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong activeRunSequence = new AtomicLong(0L);
    private final AtomicReference<QueuedMessage> interruptAndSendItem = new AtomicReference<>(null);

    private volatile ExecutionHandle activeExecutionHandle;
    private volatile boolean deepThinkingEnabledByUser;
    private volatile boolean suppressAutoDrainForCurrentRun = false;

    public ChatSessionCoordinator(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            ModelRepository modelRepository,
            ThemeRepository themeRepository,
            SettingsRepository settingsRepository,
            MemoryRepository memoryRepository,
            MemoryRetrievalEngine memoryRetrievalEngine,
            ConversationCompactor conversationCompactor,
            ContextManager contextManager,
            AiCore aiCore,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            WorkspaceFileManager workspaceFileManager,
            AppExecutors executors
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.modelRepository = modelRepository;
        this.themeRepository = themeRepository;
        this.settingsRepository = settingsRepository;
        this.memoryRepository = memoryRepository;
        this.memoryRetrievalEngine = memoryRetrievalEngine;
        this.conversationCompactor = conversationCompactor;
        this.contextManager = contextManager;
        this.aiCore = aiCore;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.workspaceFileManager = workspaceFileManager;
        this.executors = executors;

        this.allConversations = conversationRepository.observeAllConversations();
        this.selectedModel = modelRepository.observeSelectedModel();
        this.activeTheme = themeRepository.observeActiveTheme();
        this.settings = settingsRepository.observeSettings();
        AppSettings snapshot = settingsRepository.getSettingsSnapshot();
        this.deepThinkingEnabledByUser = snapshot.isDeepThinkingPreferred();
        this.agentMode.setValue(snapshot.getDefaultAgentMode());

        this.persistedMessages = Transformations.switchMap(activeConversationId, convId -> {
            if (convId == null || convId.isEmpty()) {
                return new MutableLiveData<>(Collections.emptyList());
            }
            return messageRepository.observeMessages(convId);
        });

        this.messages.addSource(persistedMessages, list -> {
            combineMessages(list, streamingPreview.getValue());
            refreshContextMetrics();
        });
        this.messages.addSource(streamingPreview, preview -> combineMessages(persistedMessages.getValue(), preview));

        this.thinkingState.addSource(selectedModel, model -> {
            recomputeThinkingState(model);
            refreshContextMetrics();
        });
        recomputeThinkingState(modelRepository.getSelectedModelSnapshot());
        refreshContextMetrics();
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private void combineMessages(List<Message> base, Message preview) {
        List<Message> combined = base != null ? new ArrayList<>(base) : new ArrayList<>();
        String currentConv = activeConversationId.getValue();
        if (preview != null && currentConv != null && currentConv.equals(preview.getConversationId())) {
            combined.add(preview);
        }
        messages.setValue(combined);
    }

    private void recomputeThinkingState(AiModel model) {
        if (model == null || !model.isDeepThinkingSupported()) {
            thinkingState.setValue(ThinkingState.UNSUPPORTED);
        } else {
            thinkingState.setValue(deepThinkingEnabledByUser ? ThinkingState.ENABLED : ThinkingState.DISABLED);
        }
    }

    public LiveData<String> getActiveConversationId() {
        return activeConversationId;
    }

    public LiveData<String> getActiveProjectId() {
        return activeProjectId;
    }

    public LiveData<List<Message>> getMessages() {
        return messages;
    }

    public LiveData<List<Conversation>> getAllConversations() {
        return allConversations;
    }

    public LiveData<AiModel> getSelectedModel() {
        return selectedModel;
    }

    public LiveData<ThemeConfig> getActiveTheme() {
        return activeTheme;
    }

    public LiveData<AppSettings> getSettings() {
        return settings;
    }

    public LiveData<List<Attachment>> getPendingAttachments() {
        return pendingAttachments;
    }

    public LiveData<ThinkingState> getThinkingState() {
        return thinkingState;
    }

    public LiveData<ExecutionState> getExecutionState() {
        return executionState;
    }

    public LiveData<String> getExecutionErrorDetail() {
        return executionErrorDetail;
    }

    public LiveData<String> getExecutionNotice() {
        return executionNotice;
    }

    public LiveData<AgentMode> getAgentMode() {
        return agentMode;
    }

    public LiveData<PendingToolConfirmation> getPendingToolConfirmation() {
        return pendingToolConfirmation;
    }

    public LiveData<List<QueuedMessage>> getFollowUpQueue() {
        return followUpQueue;
    }

    public LiveData<ContextMetrics> getContextMetrics() {
        return contextMetrics;
    }

    public LiveData<List<ToolExecutor.ActiveToolExecution>> getLiveToolExecutions() {
        return toolExecutor != null
                ? toolExecutor.observeExecutions()
                : new MutableLiveData<>(Collections.emptyList());
    }

    public boolean isAiBusy() {
        ExecutionState st = executionState.getValue();
        return activeExecutionHandle != null
                || st == ExecutionState.PREPARING
                || st == ExecutionState.THINKING
                || st == ExecutionState.STREAMING
                || st == ExecutionState.EXECUTING_TOOL;
    }

    public void setAgentMode(AgentMode mode) {
        if (mode != null) {
            runOnMain(() -> {
                agentMode.setValue(mode);
                refreshContextMetrics();
            });
        }
    }

    public void toggleAgentMode() {
        runOnMain(() -> {
            AgentMode current = agentMode.getValue();
            agentMode.setValue(current == AgentMode.CODING ? AgentMode.CHAT : AgentMode.CODING);
            refreshContextMetrics();
        });
    }

    public void respondToToolConfirmation(boolean approved) {
        PendingToolConfirmation pending = pendingToolConfirmation.getValue();
        respondToToolConfirmation(pending != null ? pending.executionId : null, approved, false);
    }

    public void respondToToolConfirmation(String executionId, boolean approved, boolean allowForSession) {
        PendingToolConfirmation target = null;
        if (executionId != null && !executionId.isEmpty()) {
            target = pendingConfirmationsById.remove(executionId);
        }
        if (target == null) {
            target = pendingToolConfirmation.getValue();
            if (target != null && target.executionId != null) {
                pendingConfirmationsById.remove(target.executionId);
            }
        }
        PendingToolConfirmation next = pendingConfirmationsById.values().stream().findFirst().orElse(null);
        pendingToolConfirmation.postValue(next);
        if (target != null) {
            if (approved && allowForSession && toolExecutor != null) {
                toolExecutor.allowToolForSession(target.toolName);
            }
            if (target.callback != null) {
                target.callback.onResult(approved);
            }
        }
    }

    public void clearExecutionNotice() {
        executionNotice.postValue(null);
    }

    public void startNewChat() {
        stopGeneration();
        clearFollowUpQueue();
        if (toolExecutor != null) {
            toolExecutor.clearSessionAllowlist();
        }
        runOnMain(() -> {
            activeConversationId.setValue(null);
            activeProjectId.setValue("");
            pendingAttachments.setValue(Collections.emptyList());
            executionErrorDetail.setValue(null);
            executionState.setValue(ExecutionState.IDLE);
            refreshContextMetrics();
        });
    }

    public void openConversation(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return;
        stopGeneration();
        clearFollowUpQueue();
        runOnMain(() -> {
            activeConversationId.setValue(conversationId);
            pendingAttachments.setValue(Collections.emptyList());
            executionErrorDetail.setValue(null);
            executionState.setValue(ExecutionState.IDLE);
        });
        executors.diskIO().execute(() -> {
            Conversation conv = conversationRepository.getConversationSync(conversationId);
            activeProjectId.postValue(conv != null ? conv.getProjectId() : "");
            refreshContextMetrics();
        });
    }

    public void setConversationProject(String projectId) {
        String clean = projectId != null ? projectId.trim() : "";
        runOnMain(() -> activeProjectId.setValue(clean));
        String currentConvId = activeConversationId.getValue();
        if (currentConvId != null && !currentConvId.isEmpty()) {
            conversationRepository.updateConversationProject(currentConvId, clean);
        }
    }

    public void renameConversation(String conversationId, String newTitle) {
        conversationRepository.renameConversation(conversationId, newTitle);
    }

    public void deleteConversation(String conversationId) {
        if (conversationId == null) return;
        conversationRepository.deleteConversation(conversationId);
        if (conversationId.equals(activeConversationId.getValue())) {
            runOnMain(() -> {
                activeConversationId.setValue(null);
                activeProjectId.setValue("");
                refreshContextMetrics();
            });
        }
    }

    public void selectModel(String modelId) {
        modelRepository.selectModel(modelId);
        String currentConvId = activeConversationId.getValue();
        if (currentConvId != null && !currentConvId.isEmpty()) {
            conversationRepository.updateConversationModel(currentConvId, modelId);
        }
        refreshContextMetrics();
    }

    public void toggleDeepThinking() {
        AiModel model = modelRepository.getSelectedModelSnapshot();
        if (model == null || !model.isDeepThinkingSupported()) {
            runOnMain(() -> thinkingState.setValue(ThinkingState.UNSUPPORTED));
            return;
        }
        deepThinkingEnabledByUser = !deepThinkingEnabledByUser;
        settingsRepository.setDeepThinkingPreferred(deepThinkingEnabledByUser);
        runOnMain(() -> recomputeThinkingState(model));
    }

    public void addPendingAttachments(List<Attachment> items) {
        if (items == null || items.isEmpty()) return;
        runOnMain(() -> {
            List<Attachment> current = pendingAttachments.getValue();
            List<Attachment> updated = current != null ? new ArrayList<>(current) : new ArrayList<>();
            updated.addAll(items);
            pendingAttachments.setValue(updated);
        });
    }

    public void removePendingAttachment(String attachmentId) {
        if (attachmentId == null) return;
        runOnMain(() -> {
            List<Attachment> current = pendingAttachments.getValue();
            if (current == null) return;
            List<Attachment> updated = new ArrayList<>();
            for (Attachment item : current) {
                if (!attachmentId.equals(item.getId())) {
                    updated.add(item);
                }
            }
            pendingAttachments.setValue(updated);
        });
    }

    public void stopGeneration() {
        suppressAutoDrainForCurrentRun = true;
        interruptAndSendItem.set(null);
        ExecutionHandle handle = activeExecutionHandle;
        denyAllPendingConfirmations();
        if (handle != null) {
            handle.cancel();
            activeExecutionHandle = null;
            streamingPreview.postValue(null);
            executionState.postValue(ExecutionState.CANCELLED);
        }
    }

    private void denyAllPendingConfirmations() {
        List<PendingToolConfirmation> snapshot = new ArrayList<>(pendingConfirmationsById.values());
        pendingConfirmationsById.clear();
        pendingToolConfirmation.postValue(null);
        for (PendingToolConfirmation item : snapshot) {
            if (item != null && item.callback != null) {
                item.callback.onResult(false);
            }
        }
    }

    public void sendMessage(String rawText) {
        sendOrQueueMessage(rawText);
    }

    public void sendOrQueueMessage(String rawText) {
        String trimmed = rawText == null ? "" : rawText.trim();
        if (handleSlashCommandIfMatched(trimmed)) {
            return;
        }
        List<Attachment> attachments = pendingAttachments.getValue();
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if (trimmed.isEmpty() && !hasAttachments) {
            return;
        }
        List<Attachment> snapshotAttachments = hasAttachments
                ? new ArrayList<>(attachments)
                : Collections.emptyList();
        runOnMain(() -> pendingAttachments.setValue(Collections.emptyList()));

        if (isAiBusy()) {
            QueuedMessage queued = new QueuedMessage(
                    UUID.randomUUID().toString(),
                    trimmed,
                    snapshotAttachments,
                    System.currentTimeMillis()
            );
            queueBacking.add(queued);
            publishQueue();
            return;
        }
        dispatchUserPrompt(trimmed, snapshotAttachments);
    }

    private boolean handleSlashCommandIfMatched(String trimmed) {
        if (trimmed == null || !trimmed.startsWith("/")) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if ("/new".equals(lower) || "/clear".equals(lower)) {
            startNewChat();
            return true;
        }
        if ("/compact".equals(lower)) {
            compactConversationNow();
            return true;
        }
        if ("/chat".equals(lower) || lower.startsWith("/chat ")) {
            setAgentMode(AgentMode.CHAT);
            String remainder = trimmed.length() > 5 ? trimmed.substring(5).trim() : "";
            if (!remainder.isEmpty()) {
                sendOrQueueMessage(remainder);
            } else {
                executionNotice.postValue("Switched to Chat mode");
            }
            return true;
        }
        if ("/code".equals(lower) || lower.startsWith("/code ")) {
            setAgentMode(AgentMode.CODING);
            String remainder = trimmed.length() > 5 ? trimmed.substring(5).trim() : "";
            if (!remainder.isEmpty()) {
                sendOrQueueMessage(remainder);
            } else {
                executionNotice.postValue("Switched to Code Agent mode");
            }
            return true;
        }
        return false;
    }

    public void sendQueuedFollowUpNow(String queueId) {
        QueuedMessage removed = removeQueuedInternal(queueId);
        if (removed == null) return;
        if (!isAiBusy()) {
            dispatchUserPrompt(removed.text, removed.attachments);
            return;
        }
        interruptAndSendItem.set(removed);
        suppressAutoDrainForCurrentRun = true;
        denyAllPendingConfirmations();
        ExecutionHandle handle = activeExecutionHandle;
        if (handle != null) {
            handle.cancel();
            activeExecutionHandle = null;
        } else {
            QueuedMessage fallback = interruptAndSendItem.getAndSet(null);
            if (fallback != null) {
                dispatchUserPrompt(fallback.text, fallback.attachments);
            }
        }
    }

    public void removeQueuedFollowUp(String queueId) {
        removeQueuedInternal(queueId);
    }

    public void clearFollowUpQueue() {
        queueBacking.clear();
        publishQueue();
    }

    private QueuedMessage removeQueuedInternal(String queueId) {
        if (queueId == null) return null;
        QueuedMessage removed = null;
        synchronized (queueBacking) {
            for (int i = 0; i < queueBacking.size(); i++) {
                if (queueId.equals(queueBacking.get(i).id)) {
                    removed = queueBacking.remove(i);
                    break;
                }
            }
        }
        publishQueue();
        return removed;
    }

    private void publishQueue() {
        List<QueuedMessage> copy;
        synchronized (queueBacking) {
            copy = new ArrayList<>(queueBacking);
        }
        followUpQueue.postValue(Collections.unmodifiableList(copy));
    }

    private void dispatchUserPrompt(String trimmed, List<Attachment> snapshotAttachments) {
        runOnMain(() -> {
            AiModel model = modelRepository.getSelectedModelSnapshot();
            AppSettings currentSettings = settingsRepository.getSettingsSnapshot();
            boolean deepThinking = thinkingState.getValue() == ThinkingState.ENABLED;

            String convId = activeConversationId.getValue();
            if (convId == null || convId.isEmpty()) {
                convId = UUID.randomUUID().toString();
                activeConversationId.setValue(convId);
            }
            final String targetConvId = convId;
            final String projectSnapshot = activeProjectId.getValue() != null ? activeProjectId.getValue() : "";

            executionErrorDetail.setValue(null);
            executionState.setValue(ExecutionState.PREPARING);
            executionNotice.setValue(null);

            messageRepository.sendUserMessage(
                    targetConvId,
                    trimmed,
                    model.getId(),
                    deepThinking,
                    snapshotAttachments,
                    currentSettings.isAutoTitleFromFirstMessage(),
                    savedUserMessage -> {
                        if (!projectSnapshot.isEmpty()) {
                            conversationRepository.updateConversationProject(targetConvId, projectSnapshot);
                        }
                        startAiGeneration(targetConvId, trimmed, projectSnapshot, model, deepThinking);
                    }
            );
        });
    }

    public void regenerateMessage(Message targetMessage) {
        if (targetMessage == null) return;
        String convId = targetMessage.getConversationId();
        if (convId == null || convId.isEmpty()) {
            convId = activeConversationId.getValue();
        }
        if (convId == null || convId.isEmpty()) return;

        stopGeneration();
        final String targetConvId = convId;
        executors.diskIO().execute(() -> {
            List<Message> all = messageRepository.getMessagesSync(targetConvId);
            if (all == null || all.isEmpty()) return;

            Message userPromptMsg = null;
            long deleteFromTimestamp = targetMessage.getCreatedAt();
            if (targetMessage.getRole() == MessageRole.USER) {
                userPromptMsg = targetMessage;
                deleteFromTimestamp = targetMessage.getCreatedAt() + 1L;
            } else {
                for (int i = all.size() - 1; i >= 0; i--) {
                    Message m = all.get(i);
                    if (m == null) continue;
                    if (m.getRole() == MessageRole.USER && m.getCreatedAt() <= targetMessage.getCreatedAt()) {
                        userPromptMsg = m;
                        break;
                    }
                }
            }
            if (userPromptMsg == null) return;

            final String promptContent = userPromptMsg.getContent();
            final String projectSnapshot = activeProjectId.getValue() != null ? activeProjectId.getValue() : "";
            final AiModel model = modelRepository.getSelectedModelSnapshot();
            final boolean deepThinking = thinkingState.getValue() == ThinkingState.ENABLED;

            messageRepository.deleteMessagesFromTimestamp(targetConvId, deleteFromTimestamp, () -> {
                executionErrorDetail.postValue(null);
                executionState.postValue(ExecutionState.PREPARING);
                startAiGeneration(targetConvId, promptContent, projectSnapshot, model, deepThinking);
            });
        });
    }

    public void handoverToCode(String customFollowUpPrompt) {
        setAgentMode(AgentMode.CODING);
        String prompt = customFollowUpPrompt != null ? customFollowUpPrompt.trim() : "";
        if (prompt.isEmpty()) {
            AppSettings current = settingsRepository.getSettingsSnapshot();
            boolean vi = current.getLanguage() == AppLanguage.VIETNAMESE;
            prompt = vi
                    ? "Tiếp tục ở chế độ Code và thực thi/áp dụng kế hoạch vừa trao đổi."
                    : "Continue in Code mode and implement/execute what we just discussed.";
        }
        sendOrQueueMessage(prompt);
    }

    public void compactConversationNow() {
        String convId = activeConversationId.getValue();
        if (convId == null || convId.isEmpty()) {
            return;
        }
        executors.worker().execute(() -> {
            Conversation conv = conversationRepository.getConversationSync(convId);
            List<Message> history = messageRepository.getMessagesSync(convId);
            AiModel model = modelRepository.getSelectedModelSnapshot();
            if (conv == null || history == null || history.size() <= 4) {
                executionNotice.postValue("Conversation is already compact.");
                return;
            }
            executionNotice.postValue("Compacting earlier conversation context...");
            Conversation compacted = conversationCompactor != null
                    ? conversationCompactor.compactIfNeededSync(conv, model, history)
                    : conv;
            if (compacted != null && compacted.getCompactedUntil() > conv.getCompactedUntil()) {
                executionNotice.postValue("Conversation context compacted cleanly.");
                refreshContextMetrics();
                return;
            }
            // Manual compaction even below auto-threshold: summarize oldest slice excluding latest 4
            int keepRecent = 4;
            List<Message> valid = new ArrayList<>();
            for (Message m : history) {
                if (m != null && !m.isError() && m.getCreatedAt() > conv.getCompactedUntil()) {
                    valid.add(m);
                }
            }
            if (valid.size() <= keepRecent) {
                executionNotice.postValue("No earlier messages left to compact.");
                return;
            }
            List<Message> slice = valid.subList(0, valid.size() - keepRecent);
            long newCutoff = slice.get(slice.size() - 1).getCreatedAt();
            StringBuilder transcript = new StringBuilder();
            if (!conv.getCompactionSummary().isEmpty()) {
                transcript.append("Existing summary:\n").append(conv.getCompactionSummary()).append("\n\n");
            }
            for (Message m : slice) {
                String roleLabel = m.getRole() == MessageRole.USER ? "User" : "Assistant";
                String c = m.getContent();
                if (c.length() > 800) c = c.substring(0, 800) + "…";
                transcript.append(roleLabel).append(": ").append(c).append("\n");
            }
            Message sumMsg = new Message(
                    UUID.randomUUID().toString(),
                    conv.getId(),
                    MessageRole.USER,
                    transcript.toString(),
                    model.getId(),
                    false,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    Collections.emptyList()
            );
            AiRequest req = new AiRequest(
                    UUID.randomUUID().toString(),
                    conv.getId(),
                    model,
                    "Summarize into concise bullet points preserving files, commands, decisions, and pending goals.",
                    Collections.singletonList(sumMsg),
                    false,
                    false
            );
            AtomicReference<String> summaryRef = new AtomicReference<>();
            aiCore.executeBlocking(req, new ExecutionHandle(), new AiStreamListener() {
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
                    if (response != null && response.getContent() != null) {
                        summaryRef.set(response.getContent().trim());
                    }
                }

                @Override
                public void onError(AiError error) {
                }
            }, null);
            String summary = summaryRef.get();
            if (summary != null && !summary.isEmpty()) {
                conversationRepository.updateConversationCompaction(conv.getId(), summary, newCutoff);
                executionNotice.postValue("Conversation context compacted.");
                refreshContextMetrics();
            } else {
                executionNotice.postValue("Unable to compact context right now.");
            }
        });
    }

    public void refreshContextMetrics() {
        String convId = activeConversationId.getValue();
        AiModel model = modelRepository.getSelectedModelSnapshot();
        AgentMode mode = agentMode.getValue() != null ? agentMode.getValue() : AgentMode.CHAT;
        executors.diskIO().execute(() -> {
            int window = model != null ? Math.max(2048, model.getContextWindowTokens()) : 32000;
            Conversation conv = (convId != null && !convId.isEmpty())
                    ? conversationRepository.getConversationSync(convId)
                    : null;
            List<Message> history = (convId != null && !convId.isEmpty())
                    ? messageRepository.getMessagesSync(convId)
                    : Collections.emptyList();
            AppSettings currentSettings = settingsRepository.getSettingsSnapshot();
            String workspacePath = workspaceFileManager != null
                    ? workspaceFileManager.getActiveWorkspacePath()
                    : currentSettings.getActiveWorkspacePath();
            String projectId = activeProjectId.getValue() != null ? activeProjectId.getValue() : "";
            List<MemoryItem> memories = memoryRetrievalEngine != null
                    ? memoryRetrievalEngine.selectContextualMemories("", projectId, convId, 750, 10)
                    : Collections.emptyList();
            List<ToolDefinition> tools = (currentSettings.isChatToolsEnabled() && toolRegistry != null)
                    ? toolRegistry.listDefinitionsForMode(mode, currentSettings)
                    : Collections.emptyList();

            String systemOnlyPrompt = contextManager.assembleSystemPrompt(
                    null,
                    Collections.emptyList(),
                    null,
                    true,
                    thinkingState.getValue() == ThinkingState.ENABLED,
                    mode,
                    workspacePath,
                    tools,
                    false
            );
            int sysTokens = ContextManager.estimateTokens(systemOnlyPrompt);
            int memTokens = 0;
            if (memories != null) {
                for (MemoryItem item : memories) {
                    if (item != null && item.isEnabled() && !item.isArchived()) {
                        memTokens += ContextManager.estimateTokens(item.getContent()) + 8;
                    }
                }
            }
            int summaryTokens = conv != null ? ContextManager.estimateTokens(conv.getCompactionSummary()) : 0;
            long cutoff = conv != null ? conv.getCompactedUntil() : 0L;
            int histTokens = 0;
            int count = 0;
            if (history != null) {
                for (Message m : history) {
                    if (m == null) continue;
                    count++;
                    if (cutoff <= 0L || m.getCreatedAt() > cutoff) {
                        histTokens += ContextManager.estimateTokens(m.getContent()) + 16;
                    }
                }
            }
            int total = sysTokens + memTokens + summaryTokens + histTokens;
            int fillPct = Math.min(100, Math.max(0, (int) Math.round((total * 100.0) / window)));
            contextMetrics.postValue(new ContextMetrics(
                    sysTokens,
                    memTokens,
                    summaryTokens,
                    histTokens,
                    total,
                    window,
                    fillPct,
                    count,
                    summaryTokens > 0
            ));
        });
    }

    private void startAiGeneration(
            String convId,
            String latestUserPrompt,
            String activeProject,
            AiModel model,
            boolean deepThinking
    ) {
        final long runId = activeRunSequence.incrementAndGet();
        suppressAutoDrainForCurrentRun = false;

        executors.worker().execute(() -> {
            Conversation conversation = conversationRepository.getConversationSync(convId);
            String effectiveProject = !activeProject.isEmpty()
                    ? activeProject
                    : (conversation != null ? conversation.getProjectId() : "");
            List<Message> history = messageRepository.getMessagesSync(convId);

            if (conversation != null && conversationCompactor != null) {
                conversation = conversationCompactor.compactIfNeededSync(conversation, model, history);
            }

            List<MemoryItem> selectedMemories = memoryRetrievalEngine != null
                    ? memoryRetrievalEngine.selectContextualMemories(latestUserPrompt, effectiveProject, convId, 750, 10)
                    : memoryRepository.getActiveMemoriesForConversationSync(convId);

            AppSettings currentSettings = settingsRepository.getSettingsSnapshot();
            AgentMode effectiveMode = agentMode.getValue() != null
                    ? agentMode.getValue()
                    : currentSettings.getDefaultAgentMode();
            String workspacePath = workspaceFileManager != null
                    ? workspaceFileManager.getActiveWorkspacePath()
                    : currentSettings.getActiveWorkspacePath();
            List<ToolDefinition> enabledTools = (currentSettings.isChatToolsEnabled() && toolRegistry != null)
                    ? toolRegistry.listDefinitionsForMode(effectiveMode, currentSettings)
                    : Collections.emptyList();

            AiRequest request = contextManager.buildRequest(
                    conversation,
                    convId,
                    model,
                    history,
                    selectedMemories,
                    true,
                    deepThinking,
                    null,
                    effectiveMode,
                    workspacePath,
                    enabledTools
            );

            final StringBuilder streamText = new StringBuilder();
            final StringBuilder streamThinking = new StringBuilder();
            final Map<String, ToolCallRecord> liveToolRecords = Collections.synchronizedMap(new LinkedHashMap<>());
            final List<TimelineEntry> liveTimeline = Collections.synchronizedList(new ArrayList<>());
            final List<SourceItem> liveSources = Collections.synchronizedList(new ArrayList<>());
            final long startTime = System.currentTimeMillis();

            Runnable pushPreview = () -> {
                if (activeRunSequence.get() != runId) return;
                List<ToolCallRecord> recordsSnapshot;
                synchronized (liveToolRecords) {
                    recordsSnapshot = liveToolRecords.isEmpty()
                            ? Collections.emptyList()
                            : new ArrayList<>(liveToolRecords.values());
                }
                List<TimelineEntry> timelineSnapshot;
                synchronized (liveTimeline) {
                    timelineSnapshot = liveTimeline.isEmpty()
                            ? Collections.emptyList()
                            : new ArrayList<>(liveTimeline);
                }
                List<SourceItem> sourcesSnapshot;
                synchronized (liveSources) {
                    sourcesSnapshot = liveSources.isEmpty()
                            ? Collections.emptyList()
                            : new ArrayList<>(liveSources);
                }
                streamingPreview.postValue(new Message(
                        STREAMING_MESSAGE_ID,
                        convId,
                        MessageRole.ASSISTANT,
                        streamText.toString(),
                        streamThinking.toString(),
                        model.getId(),
                        model.getProvider().getId(),
                        model.getAccessType(),
                        deepThinking,
                        null,
                        false,
                        startTime,
                        System.currentTimeMillis(),
                        Collections.emptyList(),
                        recordsSnapshot,
                        Collections.emptyList(),
                        timelineSnapshot,
                        sourcesSnapshot
                ));
            };

            pushPreview.run();

            ToolExecutor.ConfirmationHandler confirmHandler = (executionId, toolName, reason, argsPreview, callback) -> {
                PendingToolConfirmation item = new PendingToolConfirmation(executionId, toolName, reason, argsPreview, callback);
                pendingConfirmationsById.put(executionId, item);
                pendingToolConfirmation.postValue(item);
            };

            ToolExecutor.ToolLifecycleListener toolLifecycleListener = record -> {
                if (record == null || activeRunSequence.get() != runId) return;
                synchronized (liveToolRecords) {
                    liveToolRecords.put(record.getExecutionId(), record);
                }
                pushPreview.run();
            };

            activeExecutionHandle = aiCore.executeAsync(
                    request,
                    effectiveProject,
                    currentSettings.getAgentMaxSteps(),
                    confirmHandler,
                    toolLifecycleListener,
                    new AiStreamListener() {
                        @Override
                        public void onStateChanged(ExecutionState state) {
                            if (activeRunSequence.get() == runId) {
                                executionState.postValue(state);
                            }
                        }

                        @Override
                        public void onThinkingDelta(String delta) {
                            if (delta == null || activeRunSequence.get() != runId) return;
                            streamThinking.append(delta);
                            pushPreview.run();
                        }

                        @Override
                        public void onTextDelta(String delta) {
                            if (delta == null || activeRunSequence.get() != runId) return;
                            streamText.append(delta);
                            pushPreview.run();
                        }

                        @Override
                        public void onCommentaryDelta(String commentary) {
                            if (activeRunSequence.get() != runId) return;
                            // Previous step's text was promoted to a COMMENTARY timeline entry; clear bubble for next step's response
                            streamText.setLength(0);
                            pushPreview.run();
                        }

                        @Override
                        public void onTimelineUpdated(List<TimelineEntry> timeline, List<SourceItem> sources) {
                            if (activeRunSequence.get() != runId) return;
                            synchronized (liveTimeline) {
                                liveTimeline.clear();
                                if (timeline != null) liveTimeline.addAll(timeline);
                            }
                            synchronized (liveSources) {
                                liveSources.clear();
                                if (sources != null) liveSources.addAll(sources);
                            }
                            pushPreview.run();
                        }

                        @Override
                        public void onCompleted(AiResponse response) {
                            activeExecutionHandle = null;
                            pendingConfirmationsById.clear();
                            pendingToolConfirmation.postValue(null);

                            String rawContent = response.getContent().isEmpty() ? streamText.toString() : response.getContent();
                            String finalContent = AiCore.stripControlTags(rawContent);
                            String finalThinking = response.getThinkingContent().isEmpty() ? streamThinking.toString() : response.getThinkingContent();
                            String usedProviderId = response.getProviderId() != null ? response.getProviderId() : model.getProvider().getId();

                            List<ToolCallRecord> finalRecords = response.getToolRecords();
                            if (finalRecords == null || finalRecords.isEmpty()) {
                                synchronized (liveToolRecords) {
                                    finalRecords = new ArrayList<>(liveToolRecords.values());
                                }
                            }
                            List<TimelineEntry> finalTimeline = response.getTimelineEntries();
                            if (finalTimeline == null || finalTimeline.isEmpty()) {
                                synchronized (liveTimeline) {
                                    finalTimeline = new ArrayList<>(liveTimeline);
                                }
                            }
                            List<SourceItem> finalSources = response.getSources();
                            if (finalSources == null || finalSources.isEmpty()) {
                                synchronized (liveSources) {
                                    finalSources = new ArrayList<>(liveSources);
                                }
                            }

                            boolean vi = settingsRepository.getSettingsSnapshot().getLanguage() == AppLanguage.VIETNAMESE;
                            List<String> followUps = response.getFollowUpSuggestions();
                            if (followUps == null || followUps.isEmpty()) {
                                followUps = FollowUpSuggestionHelper.resolveOrSynthesize(
                                        rawContent,
                                        finalContent,
                                        latestUserPrompt,
                                        effectiveMode,
                                        finalRecords,
                                        vi
                                );
                            }

                            messageRepository.saveAssistantMessage(
                                    convId,
                                    finalContent,
                                    finalThinking,
                                    model.getId(),
                                    usedProviderId,
                                    model.getAccessType(),
                                    response.getTokenUsage(),
                                    false,
                                    finalRecords,
                                    followUps,
                                    finalTimeline,
                                    finalSources,
                                    saved -> {
                                        streamingPreview.postValue(null);
                                        executionState.postValue(ExecutionState.COMPLETED);
                                        refreshContextMetrics();
                                        drainNextQueuedFollowUpIfReady();
                                    }
                            );
                        }

                        @Override
                        public void onError(AiError error) {
                            activeExecutionHandle = null;
                            pendingConfirmationsById.clear();
                            pendingToolConfirmation.postValue(null);

                            List<ToolCallRecord> capturedRecords;
                            synchronized (liveToolRecords) {
                                capturedRecords = new ArrayList<>(liveToolRecords.values());
                            }
                            List<TimelineEntry> capturedTimeline;
                            synchronized (liveTimeline) {
                                capturedTimeline = new ArrayList<>(liveTimeline);
                            }
                            List<SourceItem> capturedSources;
                            synchronized (liveSources) {
                                capturedSources = new ArrayList<>(liveSources);
                            }
                            String partial = AiCore.stripControlTags(streamText.toString()).trim();
                            if (error.getType() == AiError.ErrorType.CANCELLED) {
                                Runnable afterCancel = () -> {
                                    QueuedMessage interrupted = interruptAndSendItem.getAndSet(null);
                                    if (interrupted != null) {
                                        dispatchUserPrompt(interrupted.text, interrupted.attachments);
                                    } else if (!suppressAutoDrainForCurrentRun) {
                                        drainNextQueuedFollowUpIfReady();
                                    }
                                };
                                if (!partial.isEmpty() || !capturedTimeline.isEmpty()) {
                                    String contentToSave = partial.isEmpty()
                                            ? "_(Stopped)_"
                                            : (partial + "\n\n_(Stopped)_");
                                    messageRepository.saveAssistantMessage(
                                            convId,
                                            contentToSave,
                                            streamThinking.toString(),
                                            model.getId(),
                                            model.getProvider().getId(),
                                            model.getAccessType(),
                                            null,
                                            false,
                                            capturedRecords,
                                            Collections.emptyList(),
                                            capturedTimeline,
                                            capturedSources,
                                            saved -> {
                                                streamingPreview.postValue(null);
                                                executionState.postValue(ExecutionState.CANCELLED);
                                                refreshContextMetrics();
                                                afterCancel.run();
                                            }
                                    );
                                } else {
                                    streamingPreview.postValue(null);
                                    executionState.postValue(ExecutionState.CANCELLED);
                                    afterCancel.run();
                                }
                                return;
                            }

                            String readableError = formatErrorMessage(model, error);
                            executionErrorDetail.postValue(error.getMessage());
                            messageRepository.saveAssistantMessage(
                                    convId,
                                    readableError,
                                    streamThinking.toString(),
                                    model.getId(),
                                    model.getProvider().getId(),
                                    model.getAccessType(),
                                    null,
                                    true,
                                    capturedRecords,
                                    Collections.emptyList(),
                                    capturedTimeline,
                                    capturedSources,
                                    saved -> {
                                        streamingPreview.postValue(null);
                                        executionState.postValue(ExecutionState.FAILED);
                                        refreshContextMetrics();
                                        drainNextQueuedFollowUpIfReady();
                                    }
                            );
                        }
                    },
                    executionNotice::postValue
            );
        });
    }

    private void drainNextQueuedFollowUpIfReady() {
        if (suppressAutoDrainForCurrentRun) {
            return;
        }
        QueuedMessage next = null;
        synchronized (queueBacking) {
            if (!queueBacking.isEmpty()) {
                next = queueBacking.remove(0);
            }
        }
        if (next != null) {
            publishQueue();
            dispatchUserPrompt(next.text, next.attachments);
        }
    }

    private String formatErrorMessage(AiModel model, AiError error) {
        if (error == null) return "AI request failed.";
        if (error.getType() == AiError.ErrorType.MISSING_CREDENTIAL) {
            if (model.getProvider() == ModelProvider.OPENROUTER_FREE) {
                return "**" + model.getProvider().getDisplayName() + "** ($0 per token) requires a free OpenRouter API key.\n\n"
                        + "Open **Providers & Keys → OpenRouter (Free)** to add your key, or switch to **Pollinations / Kilo** for keyless free access.";
            }
            return "API Key required for **" + model.getProvider().getDisplayName() + "**.\n\n"
                    + "Open **Settings → Providers & API Keys** to add your personal API key, "
                    + "or switch to a **Free Cloud AI** model (Pollinations / Kilo) in the Model Selector.";
        }
        if (error.getType() == AiError.ErrorType.AUTH_ERROR) {
            return "Authentication failed (" + model.getProvider().getDisplayName() + "): " + error.getMessage()
                    + "\n\nCheck or rotate your API key in **Settings → Providers & API Keys**.";
        }
        if (error.getType() == AiError.ErrorType.RATE_LIMIT || error.getType() == AiError.ErrorType.QUOTA_EXCEEDED) {
            return "Rate limit or quota reached for **" + model.getProvider().getDisplayName() + "**: "
                    + error.getMessage();
        }
        return "**" + model.getProvider().getDisplayName() + " error:** " + error.getMessage();
    }

    public void editMessage(String messageId, String newContent) {
        messageRepository.editMessage(messageId, newContent);
        refreshContextMetrics();
    }

    public void deleteMessage(String messageId) {
        messageRepository.deleteMessage(messageId, activeConversationId.getValue());
        refreshContextMetrics();
    }
}
