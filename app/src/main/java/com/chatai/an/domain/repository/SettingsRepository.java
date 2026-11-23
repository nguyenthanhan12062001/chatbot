package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AppLanguage;
import com.chatai.an.domain.model.AppSettings;

public interface SettingsRepository {
    LiveData<AppSettings> observeSettings();

    AppSettings getSettingsSnapshot();

    void setLanguage(AppLanguage language);

    void setConfirmBeforeDelete(boolean confirm);

    void setAutoTitleFromFirstMessage(boolean autoTitle);

    void setDeepThinkingPreferred(boolean preferred);

    void setDefaultAgentMode(AgentMode mode);

    void setAgentMaxSteps(int maxSteps);

    void setToolTimeoutSeconds(int timeoutSeconds);

    void setChatToolsEnabled(boolean enabled);

    void setConfirmMutatingTools(boolean confirm);

    void setConfirmShellAndExternalTools(boolean confirm);

    void setPreferredSearchEngine(String engine);

    void setActiveWorkspacePath(String path);

    void setDownloadDestinationPath(String path);

    void setAllowShizukuFileFallback(boolean allowed);

    void setToolEnabled(String toolName, boolean enabled);
}
