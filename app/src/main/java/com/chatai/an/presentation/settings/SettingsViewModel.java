package com.chatai.an.presentation.settings;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.chatai.an.ai.integration.IntegrationDiagnosticsManager;
import com.chatai.an.ai.integration.download.DownloadToolManager;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AppLanguage;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemeMode;
import com.chatai.an.domain.model.ThemePreset;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.repository.ConversationRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;
import com.chatai.an.theme.ThemePresetCatalog;

import java.util.Collections;
import java.util.List;

public class SettingsViewModel extends ViewModel {
    private final ThemeRepository themeRepository;
    private final ModelRepository modelRepository;
    private final SettingsRepository settingsRepository;
    private final ConversationRepository conversationRepository;
    private final IntegrationDiagnosticsManager diagnosticsManager;
    private final ToolRegistry toolRegistry;
    private final WorkspaceFileManager workspaceFileManager;
    private final DownloadToolManager downloadToolManager;
    private final AppExecutors executors;

    private final MutableLiveData<List<IntegrationDiagnosticsManager.SubsystemHealth>> subsystemsHealth =
            new MutableLiveData<>(Collections.emptyList());

    public SettingsViewModel(
            ThemeRepository themeRepository,
            ModelRepository modelRepository,
            SettingsRepository settingsRepository,
            ConversationRepository conversationRepository
    ) {
        this(
                themeRepository,
                modelRepository,
                settingsRepository,
                conversationRepository,
                null,
                null,
                null,
                null,
                null
        );
    }

    public SettingsViewModel(
            ThemeRepository themeRepository,
            ModelRepository modelRepository,
            SettingsRepository settingsRepository,
            ConversationRepository conversationRepository,
            IntegrationDiagnosticsManager diagnosticsManager,
            ToolRegistry toolRegistry,
            WorkspaceFileManager workspaceFileManager,
            DownloadToolManager downloadToolManager,
            AppExecutors executors
    ) {
        this.themeRepository = themeRepository;
        this.modelRepository = modelRepository;
        this.settingsRepository = settingsRepository;
        this.conversationRepository = conversationRepository;
        this.diagnosticsManager = diagnosticsManager;
        this.toolRegistry = toolRegistry;
        this.workspaceFileManager = workspaceFileManager;
        this.downloadToolManager = downloadToolManager;
        this.executors = executors;
    }

    public LiveData<ThemeConfig> getActiveTheme() {
        return themeRepository.observeActiveTheme();
    }

    public LiveData<AiModel> getSelectedModel() {
        return modelRepository.observeSelectedModel();
    }

    public LiveData<AppSettings> getSettings() {
        return settingsRepository.observeSettings();
    }

    public LiveData<List<IntegrationDiagnosticsManager.SubsystemHealth>> getSubsystemsHealth() {
        return subsystemsHealth;
    }

    public void refreshDiagnostics() {
        if (diagnosticsManager == null) return;
        if (executors != null) {
            executors.diskIO().execute(() -> subsystemsHealth.postValue(diagnosticsManager.inspectAll()));
        } else {
            subsystemsHealth.postValue(diagnosticsManager.inspectAll());
        }
    }

    public List<ToolDefinition> getAllTools() {
        return toolRegistry != null ? toolRegistry.listDefinitions() : Collections.emptyList();
    }

    public String getResolvedWorkspacePath() {
        return workspaceFileManager != null
                ? workspaceFileManager.getActiveWorkspacePath()
                : settingsRepository.getSettingsSnapshot().getActiveWorkspacePath();
    }

    public String getResolvedDownloadDir() {
        return downloadToolManager != null
                ? downloadToolManager.getDefaultDownloadDir().getAbsolutePath()
                : settingsRepository.getSettingsSnapshot().getDownloadDestinationPath();
    }

    public void setThemeMode(ThemeMode mode) {
        ThemeConfig current = themeRepository.getActiveThemeSnapshot();
        current.setMode(mode);
        themeRepository.applyTheme(current);
    }

    public void setThemePreset(ThemePreset preset) {
        ThemeConfig current = themeRepository.getActiveThemeSnapshot();
        ThemeConfig presetConfig = ThemePresetCatalog.createConfigForPreset(preset, current.getMode());
        themeRepository.applyTheme(presetConfig);
    }

    public void setLanguage(AppLanguage language) {
        settingsRepository.setLanguage(language);
    }

    public void setAutoTitle(boolean enabled) {
        settingsRepository.setAutoTitleFromFirstMessage(enabled);
    }

    public void setConfirmBeforeDelete(boolean enabled) {
        settingsRepository.setConfirmBeforeDelete(enabled);
    }

    public void setDefaultAgentMode(AgentMode mode) {
        settingsRepository.setDefaultAgentMode(mode);
        refreshDiagnostics();
    }

    public void setChatToolsEnabled(boolean enabled) {
        settingsRepository.setChatToolsEnabled(enabled);
        refreshDiagnostics();
    }

    public void setConfirmMutatingTools(boolean confirm) {
        settingsRepository.setConfirmMutatingTools(confirm);
    }

    public void setConfirmShellAndExternalTools(boolean confirm) {
        settingsRepository.setConfirmShellAndExternalTools(confirm);
    }

    public void setPreferredSearchEngine(String engine) {
        settingsRepository.setPreferredSearchEngine(engine);
        refreshDiagnostics();
    }

    public void setAgentMaxSteps(int maxSteps) {
        settingsRepository.setAgentMaxSteps(maxSteps);
        refreshDiagnostics();
    }

    public void setToolTimeoutSeconds(int timeoutSeconds) {
        settingsRepository.setToolTimeoutSeconds(timeoutSeconds);
        refreshDiagnostics();
    }

    public void setActiveWorkspacePath(String path) {
        settingsRepository.setActiveWorkspacePath(path);
        refreshDiagnostics();
    }

    public void setDownloadDestinationPath(String path) {
        settingsRepository.setDownloadDestinationPath(path);
        refreshDiagnostics();
    }

    public void setAllowShizukuFileFallback(boolean allowed) {
        settingsRepository.setAllowShizukuFileFallback(allowed);
        refreshDiagnostics();
    }

    public void setToolEnabled(String toolName, boolean enabled) {
        settingsRepository.setToolEnabled(toolName, enabled);
        refreshDiagnostics();
    }

    public void clearAllHistory() {
        conversationRepository.deleteAllConversations();
    }
}
