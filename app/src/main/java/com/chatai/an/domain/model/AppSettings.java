package com.chatai.an.domain.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class AppSettings {
    private final String selectedModelId;
    private final boolean deepThinkingPreferred;
    private final boolean confirmBeforeDelete;
    private final boolean autoTitleFromFirstMessage;
    private final AppLanguage language;

    // Agent & Tool System Settings
    private final AgentMode defaultAgentMode;
    private final int agentMaxSteps;
    private final int toolTimeoutSeconds;
    private final boolean chatToolsEnabled;
    private final boolean confirmMutatingTools;
    private final boolean confirmShellAndExternalTools;
    private final String preferredSearchEngine;
    private final String activeWorkspacePath;
    private final String downloadDestinationPath;
    private final boolean allowShizukuFileFallback;
    private final boolean bubbleEnabled;
    private final TerminalBackend preferredTerminalBackend;
    private final Set<String> disabledToolNames;

    public AppSettings(
            String selectedModelId,
            boolean deepThinkingPreferred,
            boolean confirmBeforeDelete,
            boolean autoTitleFromFirstMessage,
            AppLanguage language
    ) {
        this(
                selectedModelId,
                deepThinkingPreferred,
                confirmBeforeDelete,
                autoTitleFromFirstMessage,
                language,
                AgentMode.CHAT,
                8,
                30,
                true,
                true,
                true,
                "auto",
                "",
                "",
                false,
                false,
                TerminalBackend.APP_TERMINAL,
                Collections.emptySet()
        );
    }

    public AppSettings(
            String selectedModelId,
            boolean deepThinkingPreferred,
            boolean confirmBeforeDelete,
            boolean autoTitleFromFirstMessage,
            AppLanguage language,
            AgentMode defaultAgentMode,
            int agentMaxSteps,
            int toolTimeoutSeconds,
            boolean chatToolsEnabled,
            boolean confirmMutatingTools,
            boolean confirmShellAndExternalTools,
            String preferredSearchEngine,
            String activeWorkspacePath,
            String downloadDestinationPath,
            boolean allowShizukuFileFallback,
            Set<String> disabledToolNames
    ) {
        this(
                selectedModelId,
                deepThinkingPreferred,
                confirmBeforeDelete,
                autoTitleFromFirstMessage,
                language,
                defaultAgentMode,
                agentMaxSteps,
                toolTimeoutSeconds,
                chatToolsEnabled,
                confirmMutatingTools,
                confirmShellAndExternalTools,
                preferredSearchEngine,
                activeWorkspacePath,
                downloadDestinationPath,
                allowShizukuFileFallback,
                false,
                TerminalBackend.APP_TERMINAL,
                disabledToolNames
        );
    }

    public AppSettings(
            String selectedModelId,
            boolean deepThinkingPreferred,
            boolean confirmBeforeDelete,
            boolean autoTitleFromFirstMessage,
            AppLanguage language,
            AgentMode defaultAgentMode,
            int agentMaxSteps,
            int toolTimeoutSeconds,
            boolean chatToolsEnabled,
            boolean confirmMutatingTools,
            boolean confirmShellAndExternalTools,
            String preferredSearchEngine,
            String activeWorkspacePath,
            String downloadDestinationPath,
            boolean allowShizukuFileFallback,
            boolean bubbleEnabled,
            Set<String> disabledToolNames
    ) {
        this(
                selectedModelId,
                deepThinkingPreferred,
                confirmBeforeDelete,
                autoTitleFromFirstMessage,
                language,
                defaultAgentMode,
                agentMaxSteps,
                toolTimeoutSeconds,
                chatToolsEnabled,
                confirmMutatingTools,
                confirmShellAndExternalTools,
                preferredSearchEngine,
                activeWorkspacePath,
                downloadDestinationPath,
                allowShizukuFileFallback,
                bubbleEnabled,
                TerminalBackend.APP_TERMINAL,
                disabledToolNames
        );
    }

    public AppSettings(
            String selectedModelId,
            boolean deepThinkingPreferred,
            boolean confirmBeforeDelete,
            boolean autoTitleFromFirstMessage,
            AppLanguage language,
            AgentMode defaultAgentMode,
            int agentMaxSteps,
            int toolTimeoutSeconds,
            boolean chatToolsEnabled,
            boolean confirmMutatingTools,
            boolean confirmShellAndExternalTools,
            String preferredSearchEngine,
            String activeWorkspacePath,
            String downloadDestinationPath,
            boolean allowShizukuFileFallback,
            boolean bubbleEnabled,
            TerminalBackend preferredTerminalBackend,
            Set<String> disabledToolNames
    ) {
        this.selectedModelId = selectedModelId;
        this.deepThinkingPreferred = deepThinkingPreferred;
        this.confirmBeforeDelete = confirmBeforeDelete;
        this.autoTitleFromFirstMessage = autoTitleFromFirstMessage;
        this.language = language != null ? language : AppLanguage.ENGLISH;
        this.defaultAgentMode = defaultAgentMode != null ? defaultAgentMode : AgentMode.CHAT;
        this.agentMaxSteps = Math.max(2, Math.min(12, agentMaxSteps));
        this.toolTimeoutSeconds = Math.max(10, Math.min(120, toolTimeoutSeconds));
        this.chatToolsEnabled = chatToolsEnabled;
        this.confirmMutatingTools = confirmMutatingTools;
        this.confirmShellAndExternalTools = confirmShellAndExternalTools;
        this.preferredSearchEngine = preferredSearchEngine != null && !preferredSearchEngine.isEmpty() ? preferredSearchEngine : "auto";
        this.activeWorkspacePath = activeWorkspacePath != null ? activeWorkspacePath : "";
        this.downloadDestinationPath = downloadDestinationPath != null ? downloadDestinationPath : "";
        this.allowShizukuFileFallback = allowShizukuFileFallback;
        this.bubbleEnabled = bubbleEnabled;
        this.preferredTerminalBackend = preferredTerminalBackend != null ? preferredTerminalBackend : TerminalBackend.APP_TERMINAL;
        this.disabledToolNames = disabledToolNames == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(disabledToolNames));
    }

    public String getSelectedModelId() {
        return selectedModelId;
    }

    public boolean isDeepThinkingPreferred() {
        return deepThinkingPreferred;
    }

    public boolean isConfirmBeforeDelete() {
        return confirmBeforeDelete;
    }

    public boolean isAutoTitleFromFirstMessage() {
        return autoTitleFromFirstMessage;
    }

    public AppLanguage getLanguage() {
        return language;
    }

    public AgentMode getDefaultAgentMode() {
        return defaultAgentMode;
    }

    public int getAgentMaxSteps() {
        return agentMaxSteps;
    }

    public int getToolTimeoutSeconds() {
        return toolTimeoutSeconds;
    }

    public boolean isChatToolsEnabled() {
        return chatToolsEnabled;
    }

    public boolean isConfirmMutatingTools() {
        return confirmMutatingTools;
    }

    public boolean isConfirmShellAndExternalTools() {
        return confirmShellAndExternalTools;
    }

    public String getPreferredSearchEngine() {
        return preferredSearchEngine;
    }

    public String getActiveWorkspacePath() {
        return activeWorkspacePath;
    }

    public String getDownloadDestinationPath() {
        return downloadDestinationPath;
    }

    public boolean isAllowShizukuFileFallback() {
        return allowShizukuFileFallback;
    }

    public boolean isBubbleEnabled() {
        return bubbleEnabled;
    }

    public TerminalBackend getPreferredTerminalBackend() {
        return preferredTerminalBackend;
    }

    public Set<String> getDisabledToolNames() {
        return disabledToolNames;
    }

    public boolean isToolEnabled(String toolName) {
        return toolName != null && !disabledToolNames.contains(toolName);
    }
}
