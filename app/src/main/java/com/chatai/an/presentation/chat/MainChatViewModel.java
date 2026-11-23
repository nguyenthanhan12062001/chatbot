package com.chatai.an.presentation.chat;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.core.AiCore;
import com.chatai.an.ai.core.ChatSessionCoordinator;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.memory.ConversationCompactor;
import com.chatai.an.ai.memory.MemoryRetrievalEngine;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThinkingState;
import com.chatai.an.domain.repository.ConversationRepository;
import com.chatai.an.domain.repository.MemoryRepository;
import com.chatai.an.domain.repository.MessageRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.List;

/**
 * ViewModel wrapper around the shared {@link ChatSessionCoordinator} so MainActivity and
 * ChatBubbleService share identical live state, streaming output, tool approvals, and follow-up queues.
 */
public class MainChatViewModel extends ViewModel {

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

    private final ChatSessionCoordinator coordinator;
    private final LiveData<PendingToolConfirmation> pendingToolConfirmation;

    public MainChatViewModel(ChatSessionCoordinator coordinator) {
        this.coordinator = coordinator;
        this.pendingToolConfirmation = Transformations.map(
                coordinator.getPendingToolConfirmation(),
                p -> p == null ? null : new PendingToolConfirmation(
                        p.executionId, p.toolName, p.reason, p.argsPreview, p.callback
                )
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
        this(new ChatSessionCoordinator(
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
                toolRegistry,
                toolExecutor,
                workspaceFileManager,
                executors
        ));
    }

    public ChatSessionCoordinator getCoordinator() {
        return coordinator;
    }

    public LiveData<String> getActiveConversationId() {
        return coordinator.getActiveConversationId();
    }

    public LiveData<String> getActiveProjectId() {
        return coordinator.getActiveProjectId();
    }

    public LiveData<List<Message>> getMessages() {
        return coordinator.getMessages();
    }

    public LiveData<List<Conversation>> getAllConversations() {
        return coordinator.getAllConversations();
    }

    public LiveData<AiModel> getSelectedModel() {
        return coordinator.getSelectedModel();
    }

    public LiveData<ThemeConfig> getActiveTheme() {
        return coordinator.getActiveTheme();
    }

    public LiveData<AppSettings> getSettings() {
        return coordinator.getSettings();
    }

    public LiveData<List<Attachment>> getPendingAttachments() {
        return coordinator.getPendingAttachments();
    }

    public LiveData<ThinkingState> getThinkingState() {
        return coordinator.getThinkingState();
    }

    public LiveData<ExecutionState> getExecutionState() {
        return coordinator.getExecutionState();
    }

    public LiveData<String> getExecutionErrorDetail() {
        return coordinator.getExecutionErrorDetail();
    }

    public LiveData<String> getExecutionNotice() {
        return coordinator.getExecutionNotice();
    }

    public LiveData<AgentMode> getAgentMode() {
        return coordinator.getAgentMode();
    }

    public LiveData<PendingToolConfirmation> getPendingToolConfirmation() {
        return pendingToolConfirmation;
    }

    public LiveData<List<ChatSessionCoordinator.QueuedMessage>> getFollowUpQueue() {
        return coordinator.getFollowUpQueue();
    }

    public LiveData<ChatSessionCoordinator.ContextMetrics> getContextMetrics() {
        return coordinator.getContextMetrics();
    }

    public LiveData<List<ToolExecutor.ActiveToolExecution>> getLiveToolExecutions() {
        return coordinator.getLiveToolExecutions();
    }

    public boolean isAiBusy() {
        return coordinator.isAiBusy();
    }

    public void setAgentMode(AgentMode mode) {
        coordinator.setAgentMode(mode);
    }

    public void toggleAgentMode() {
        coordinator.toggleAgentMode();
    }

    public void respondToToolConfirmation(boolean approved) {
        coordinator.respondToToolConfirmation(approved);
    }

    public void respondToToolConfirmation(String executionId, boolean approved, boolean allowForSession) {
        coordinator.respondToToolConfirmation(executionId, approved, allowForSession);
    }

    public void clearExecutionNotice() {
        coordinator.clearExecutionNotice();
    }

    public void startNewChat() {
        coordinator.startNewChat();
    }

    public void openConversation(String conversationId) {
        coordinator.openConversation(conversationId);
    }

    public void setConversationProject(String projectId) {
        coordinator.setConversationProject(projectId);
    }

    public void renameConversation(String conversationId, String newTitle) {
        coordinator.renameConversation(conversationId, newTitle);
    }

    public void deleteConversation(String conversationId) {
        coordinator.deleteConversation(conversationId);
    }

    public void selectModel(String modelId) {
        coordinator.selectModel(modelId);
    }

    public void toggleDeepThinking() {
        coordinator.toggleDeepThinking();
    }

    public void addPendingAttachments(List<Attachment> items) {
        coordinator.addPendingAttachments(items);
    }

    public void removePendingAttachment(String attachmentId) {
        coordinator.removePendingAttachment(attachmentId);
    }

    public void stopGeneration() {
        coordinator.stopGeneration();
    }

    public void sendMessage(String rawText) {
        coordinator.sendOrQueueMessage(rawText);
    }

    public void sendOrQueueMessage(String rawText) {
        coordinator.sendOrQueueMessage(rawText);
    }

    public void sendQueuedFollowUpNow(String queueId) {
        coordinator.sendQueuedFollowUpNow(queueId);
    }

    public void removeQueuedFollowUp(String queueId) {
        coordinator.removeQueuedFollowUp(queueId);
    }

    public void clearFollowUpQueue() {
        coordinator.clearFollowUpQueue();
    }

    public void regenerateMessage(Message targetMessage) {
        coordinator.regenerateMessage(targetMessage);
    }

    public void handoverToCode(String optionalPrompt) {
        coordinator.handoverToCode(optionalPrompt);
    }

    public void compactConversationNow() {
        coordinator.compactConversationNow();
    }

    public void refreshContextMetrics() {
        coordinator.refreshContextMetrics();
    }

    public void editMessage(String messageId, String newContent) {
        coordinator.editMessage(messageId, newContent);
    }

    public void deleteMessage(String messageId) {
        coordinator.deleteMessage(messageId);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // Do NOT cancel coordinator execution here so minimized chat keeps streaming seamlessly in ChatBubbleService.
    }
}
