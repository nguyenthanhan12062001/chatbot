package com.chatai.an.presentation.agent;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.chatai.an.ai.agent.AgentEngine;
import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AgentTask;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.repository.AgentRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.Collections;
import java.util.List;

public class AgentViewModel extends ViewModel {
    public static class PendingConfirmation {
        public final String taskId;
        public final String toolName;
        public final String argsPreview;
        public final AgentEngine.ConfirmationDecision callback;

        public PendingConfirmation(
                String taskId,
                String toolName,
                String argsPreview,
                AgentEngine.ConfirmationDecision callback
        ) {
            this.taskId = taskId;
            this.toolName = toolName;
            this.argsPreview = argsPreview;
            this.callback = callback;
        }
    }

    private final AgentEngine agentEngine;
    private final AgentRepository agentRepository;
    private final ModelRepository modelRepository;
    private final ThemeRepository themeRepository;
    private final ToolRegistry toolRegistry;
    private final WorkspaceFileManager workspaceFileManager;
    private final SettingsRepository settingsRepository;
    private final ToolExecutor toolExecutor;

    private final MutableLiveData<AgentTask> currentTask = new MutableLiveData<>(null);
    private final MutableLiveData<PendingConfirmation> pendingConfirmation = new MutableLiveData<>(null);
    private final MutableLiveData<AgentMode> agentMode = new MutableLiveData<>(AgentMode.CHAT);
    private final MutableLiveData<String> activeWorkspacePath = new MutableLiveData<>("");
    private volatile ExecutionHandle activeHandle;

    public AgentViewModel(
            AgentEngine agentEngine,
            AgentRepository agentRepository,
            ModelRepository modelRepository,
            ThemeRepository themeRepository,
            ToolRegistry toolRegistry
    ) {
        this(agentEngine, agentRepository, modelRepository, themeRepository, toolRegistry, null, null, null);
    }

    public AgentViewModel(
            AgentEngine agentEngine,
            AgentRepository agentRepository,
            ModelRepository modelRepository,
            ThemeRepository themeRepository,
            ToolRegistry toolRegistry,
            WorkspaceFileManager workspaceFileManager,
            SettingsRepository settingsRepository,
            ToolExecutor toolExecutor
    ) {
        this.agentEngine = agentEngine;
        this.agentRepository = agentRepository;
        this.modelRepository = modelRepository;
        this.themeRepository = themeRepository;
        this.toolRegistry = toolRegistry;
        this.workspaceFileManager = workspaceFileManager;
        this.settingsRepository = settingsRepository;
        this.toolExecutor = toolExecutor;

        if (settingsRepository != null) {
            this.agentMode.setValue(settingsRepository.getSettingsSnapshot().getDefaultAgentMode());
        }
        refreshWorkspacePath();
    }

    public LiveData<ThemeConfig> getActiveTheme() {
        return themeRepository.observeActiveTheme();
    }

    public LiveData<AiModel> getSelectedModel() {
        return modelRepository.observeSelectedModel();
    }

    public LiveData<List<AgentTask>> getTaskHistory() {
        return agentRepository.observeAllTasks();
    }

    public LiveData<AgentTask> getCurrentTask() {
        return currentTask;
    }

    public LiveData<PendingConfirmation> getPendingConfirmation() {
        return pendingConfirmation;
    }

    public LiveData<AgentMode> getAgentMode() {
        return agentMode;
    }

    public void setAgentMode(AgentMode mode) {
        if (mode != null) {
            agentMode.setValue(mode);
        }
    }

    public LiveData<String> getActiveWorkspacePath() {
        return activeWorkspacePath;
    }

    public void refreshWorkspacePath() {
        String ws = workspaceFileManager != null
                ? workspaceFileManager.getActiveWorkspacePath()
                : (settingsRepository != null ? settingsRepository.getSettingsSnapshot().getActiveWorkspacePath() : "");
        activeWorkspacePath.setValue(ws);
    }

    public void setWorkspacePath(String path) {
        if (settingsRepository != null) {
            settingsRepository.setActiveWorkspacePath(path != null ? path.trim() : "");
        }
        refreshWorkspacePath();
    }

    public LiveData<List<ToolExecutor.ActiveToolExecution>> getLiveToolExecutions() {
        return toolExecutor != null
                ? toolExecutor.observeExecutions()
                : new MutableLiveData<>(Collections.emptyList());
    }

    public List<ToolDefinition> getAvailableTools() {
        if (settingsRepository != null) {
            AgentMode mode = agentMode.getValue() != null ? agentMode.getValue() : AgentMode.CHAT;
            return toolRegistry.listDefinitionsForMode(mode, settingsRepository.getSettingsSnapshot());
        }
        return toolRegistry.listDefinitions();
    }

    public void runGoal(String goal) {
        if (goal == null || goal.trim().isEmpty()) return;
        stopCurrentTask();
        AiModel model = modelRepository.getSelectedModelSnapshot();
        AgentMode mode = agentMode.getValue() != null ? agentMode.getValue() : AgentMode.CHAT;
        String workspace = activeWorkspacePath.getValue() != null ? activeWorkspacePath.getValue() : "";
        activeHandle = agentEngine.runTask(goal.trim(), model, "", mode, workspace, new AgentEngine.AgentCallback() {
            @Override
            public void onTaskUpdated(AgentTask task) {
                currentTask.setValue(task);
            }

            @Override
            public void onConfirmationRequired(
                    String taskId,
                    String toolName,
                    String argsPreview,
                    AgentEngine.ConfirmationDecision decisionCallback
            ) {
                pendingConfirmation.setValue(new PendingConfirmation(taskId, toolName, argsPreview, decisionCallback));
            }
        });
    }

    public void respondToConfirmation(boolean approved) {
        PendingConfirmation pc = pendingConfirmation.getValue();
        pendingConfirmation.setValue(null);
        if (pc != null && pc.callback != null) {
            pc.callback.respond(approved);
        }
    }

    public void stopCurrentTask() {
        ExecutionHandle h = activeHandle;
        if (h != null) {
            h.cancel();
            activeHandle = null;
        }
        pendingConfirmation.setValue(null);
    }

    public void deleteTask(String taskId) {
        agentRepository.deleteTask(taskId);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        stopCurrentTask();
    }
}
