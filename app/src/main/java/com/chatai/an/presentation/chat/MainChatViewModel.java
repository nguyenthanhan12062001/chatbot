package com.chatai.an.presentation.chat;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.core.AiCore;
import com.chatai.an.ai.core.ExecutionHandle;
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
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThinkingState;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.repository.ConversationRepository;
import com.chatai.an.domain.repository.MemoryRepository;
import com.chatai.an.domain.repository.MessageRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MainChatViewModel extends ViewModel {
    private static final String STREAMING_MESSAGE_ID = "__streaming_assistant_preview__";

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

    private volatile ExecutionHandle activeExecutionHandle;
    private boolean deepThinkingEnabledByUser = false;

    public MainChatViewModel(
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
            AppExecutors executors
    ) {
        this(
                conversationRepository,
                messageRepository,
                modelRepository,
                themeRepository,
                settingsRepository,
                memoryRepository,
                memoryRetrievalEngine,
                conversationCompactor,
                contextManager,
                aiCore,
                null,
                null,
                null,
                executors
        );
    }

    public MainChatViewModel(
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

        this.messages.addSource(persistedMessages, list -> combineMessages(list, streamingPreview.getValue()));
        this.messages.addSource(streamingPreview, preview -> combineMessages(persistedMessages.getValue(), preview));

        this.thinkingState.addSource(selectedModel, this::recomputeThinkingState);
        recomputeThinkingState(modelRepository.getSelectedModelSnapshot());
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

    public LiveData<List<ToolExecutor.ActiveToolExecution>> getLiveToolExecutions() {
        return toolExecutor != null
                ? toolExecutor.observeExecutions()
                : new MutableLiveData<>(Collections.emptyList());
    }

    public void setAgentMode(AgentMode mode) {
        if (mode != null) {
            agentMode.setValue(mode);
        }
    }

    public void toggleAgentMode() {
        AgentMode current = agentMode.getValue();
        agentMode.setValue(current == AgentMode.CODING ? AgentMode.CHAT : AgentMode.CODING);
    }

    public void respondToToolConfirmation(boolean approved) {
        PendingToolConfirmation pending = pendingToolConfirmation.getValue();
        pendingToolConfirmation.setValue(null);
        if (pending != null && pending.callback != null) {
            pending.callback.onResult(approved);
        }
    }

    public void clearExecutionNotice() {
        executionNotice.setValue(null);
    }

    public void startNewChat() {
        stopGeneration();
        activeConversationId.setValue(null);
        activeProjectId.setValue("");
        pendingAttachments.setValue(Collections.emptyList());
        executionErrorDetail.setValue(null);
        executionState.setValue(ExecutionState.IDLE);
    }

    public void openConversation(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return;
        stopGeneration();
        activeConversationId.setValue(conversationId);
        pendingAttachments.setValue(Collections.emptyList());
        executionErrorDetail.setValue(null);
        executionState.setValue(ExecutionState.IDLE);
        executors.diskIO().execute(() -> {
            Conversation conv = conversationRepository.getConversationSync(conversationId);
            activeProjectId.postValue(conv != null ? conv.getProjectId() : "");
        });
    }

    public void setConversationProject(String projectId) {
        String clean = projectId != null ? projectId.trim() : "";
        activeProjectId.setValue(clean);
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
            activeConversationId.setValue(null);
            activeProjectId.setValue("");
        }
    }

    public void selectModel(String modelId) {
        modelRepository.selectModel(modelId);
        String currentConvId = activeConversationId.getValue();
        if (currentConvId != null && !currentConvId.isEmpty()) {
            conversationRepository.updateConversationModel(currentConvId, modelId);
        }
    }

    public void toggleDeepThinking() {
        AiModel model = modelRepository.getSelectedModelSnapshot();
        if (model == null || !model.isDeepThinkingSupported()) {
            thinkingState.setValue(ThinkingState.UNSUPPORTED);
            return;
        }
        deepThinkingEnabledByUser = !deepThinkingEnabledByUser;
        settingsRepository.setDeepThinkingPreferred(deepThinkingEnabledByUser);
        recomputeThinkingState(model);
    }

    public void addPendingAttachments(List<Attachment> items) {
        if (items == null || items.isEmpty()) return;
        List<Attachment> current = pendingAttachments.getValue();
        List<Attachment> updated = current != null ? new ArrayList<>(current) : new ArrayList<>();
        updated.addAll(items);
        pendingAttachments.setValue(updated);
    }

    public void removePendingAttachment(String attachmentId) {
        List<Attachment> current = pendingAttachments.getValue();
        if (current == null || attachmentId == null) return;
        List<Attachment> updated = new ArrayList<>();
        for (Attachment item : current) {
            if (!attachmentId.equals(item.getId())) {
                updated.add(item);
            }
        }
        pendingAttachments.setValue(updated);
    }

    public void stopGeneration() {
        ExecutionHandle handle = activeExecutionHandle;
        if (handle != null) {
            handle.cancel();
            activeExecutionHandle = null;
            streamingPreview.postValue(null);
            executionState.postValue(ExecutionState.CANCELLED);
        }
        pendingToolConfirmation.postValue(null);
    }

    public void sendMessage(String rawText) {
        String trimmed = rawText == null ? "" : rawText.trim();
        List<Attachment> attachments = pendingAttachments.getValue();
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if (trimmed.isEmpty() && !hasAttachments) {
            return;
        }

        if (activeExecutionHandle != null) {
            activeExecutionHandle.cancel();
            activeExecutionHandle = null;
        }

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

        List<Attachment> snapshotAttachments = hasAttachments
                ? new ArrayList<>(attachments)
                : Collections.emptyList();
        pendingAttachments.setValue(Collections.emptyList());

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
    }

    private void startAiGeneration(
            String convId,
            String latestUserPrompt,
            String activeProject,
            AiModel model,
            boolean deepThinking
    ) {
        executors.worker().execute(() -> {
            Conversation conversation = conversationRepository.getConversationSync(convId);
            String effectiveProject = !activeProject.isEmpty()
                    ? activeProject
                    : (conversation != null ? conversation.getProjectId() : "");
            List<Message> history = messageRepository.getMessagesSync(convId);

            // Auto-compact earlier history if approaching context limit without deleting any Room messages
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
            final long startTime = System.currentTimeMillis();

            streamingPreview.postValue(new Message(
                    STREAMING_MESSAGE_ID,
                    convId,
                    MessageRole.ASSISTANT,
                    "",
                    "",
                    model.getId(),
                    model.getProvider().getId(),
                    model.getAccessType(),
                    deepThinking,
                    null,
                    false,
                    startTime,
                    startTime,
                    Collections.emptyList()
            ));

            ToolExecutor.ConfirmationHandler confirmHandler = (executionId, toolName, reason, argsPreview, callback) ->
                    pendingToolConfirmation.postValue(
                            new PendingToolConfirmation(executionId, toolName, reason, argsPreview, callback)
                    );

            activeExecutionHandle = aiCore.executeAsync(
                    request,
                    effectiveProject,
                    currentSettings.getAgentMaxSteps(),
                    confirmHandler,
                    new AiStreamListener() {
                        @Override
                        public void onStateChanged(ExecutionState state) {
                            executionState.postValue(state);
                        }

                        @Override
                        public void onThinkingDelta(String delta) {
                            if (delta == null) return;
                            streamThinking.append(delta);
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
                                    Collections.emptyList()
                            ));
                        }

                        @Override
                        public void onTextDelta(String delta) {
                            if (delta == null) return;
                            streamText.append(delta);
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
                                    Collections.emptyList()
                            ));
                        }

                        @Override
                        public void onCompleted(AiResponse response) {
                            activeExecutionHandle = null;
                            String rawContent = response.getContent().isEmpty() ? streamText.toString() : response.getContent();
                            String finalContent = AiCore.stripToolTags(rawContent);
                            String finalThinking = response.getThinkingContent().isEmpty() ? streamThinking.toString() : response.getThinkingContent();
                            String usedProviderId = response.getProviderId() != null ? response.getProviderId() : model.getProvider().getId();

                            messageRepository.saveAssistantMessage(
                                    convId,
                                    finalContent,
                                    finalThinking,
                                    model.getId(),
                                    usedProviderId,
                                    model.getAccessType(),
                                    response.getTokenUsage(),
                                    false,
                                    saved -> {
                                        streamingPreview.postValue(null);
                                        executionState.postValue(ExecutionState.COMPLETED);
                                    }
                            );
                        }

                        @Override
                        public void onError(AiError error) {
                            activeExecutionHandle = null;
                            String partial = AiCore.stripToolTags(streamText.toString()).trim();
                            if (error.getType() == AiError.ErrorType.CANCELLED) {
                                if (!partial.isEmpty()) {
                                    messageRepository.saveAssistantMessage(
                                            convId,
                                            partial + "\n\n_(Stopped)_",
                                            streamThinking.toString(),
                                            model.getId(),
                                            model.getProvider().getId(),
                                            model.getAccessType(),
                                            null,
                                            false,
                                            saved -> {
                                                streamingPreview.postValue(null);
                                                executionState.postValue(ExecutionState.CANCELLED);
                                            }
                                    );
                                } else {
                                    streamingPreview.postValue(null);
                                    executionState.postValue(ExecutionState.CANCELLED);
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
                                    saved -> {
                                        streamingPreview.postValue(null);
                                        executionState.postValue(ExecutionState.FAILED);
                                    }
                            );
                        }
                    },
                    executionNotice::postValue
            );
        });
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
    }

    public void deleteMessage(String messageId) {
        messageRepository.deleteMessage(messageId, activeConversationId.getValue());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (activeExecutionHandle != null) {
            activeExecutionHandle.cancel();
        }
    }
}
