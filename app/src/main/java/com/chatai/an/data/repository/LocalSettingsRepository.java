package com.chatai.an.data.repository;

import android.os.Looper;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.data.local.prefs.PreferencesManager;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AppLanguage;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.TerminalBackend;
import com.chatai.an.domain.repository.SettingsRepository;

public class LocalSettingsRepository implements SettingsRepository {
    private final PreferencesManager prefs;
    private final MutableLiveData<AppSettings> settingsLiveData;
    private volatile AppSettings cachedSettings;

    public LocalSettingsRepository(PreferencesManager prefs) {
        this.prefs = prefs;
        AppSettings initial = prefs.loadSettings();
        this.cachedSettings = initial;
        this.settingsLiveData = new MutableLiveData<>(initial);
    }

    @Override
    public LiveData<AppSettings> observeSettings() {
        return settingsLiveData;
    }

    @Override
    public AppSettings getSettingsSnapshot() {
        AppSettings snapshot = cachedSettings;
        return snapshot != null ? snapshot : prefs.loadSettings();
    }

    @Override
    public void setLanguage(AppLanguage language) {
        if (language == null) return;
        prefs.setLanguage(language);
        publishUpdate();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.getTag()));
    }

    @Override
    public void setConfirmBeforeDelete(boolean confirm) {
        prefs.setConfirmBeforeDelete(confirm);
        publishUpdate();
    }

    @Override
    public void setAutoTitleFromFirstMessage(boolean autoTitle) {
        prefs.setAutoTitleFromFirstMessage(autoTitle);
        publishUpdate();
    }

    @Override
    public void setDeepThinkingPreferred(boolean preferred) {
        prefs.setDeepThinkingPreferred(preferred);
        publishUpdate();
    }

    @Override
    public void setDefaultAgentMode(AgentMode mode) {
        prefs.setDefaultAgentMode(mode);
        publishUpdate();
    }

    @Override
    public void setAgentMaxSteps(int maxSteps) {
        prefs.setAgentMaxSteps(maxSteps);
        publishUpdate();
    }

    @Override
    public void setToolTimeoutSeconds(int timeoutSeconds) {
        prefs.setToolTimeoutSeconds(timeoutSeconds);
        publishUpdate();
    }

    @Override
    public void setChatToolsEnabled(boolean enabled) {
        prefs.setChatToolsEnabled(enabled);
        publishUpdate();
    }

    @Override
    public void setConfirmMutatingTools(boolean confirm) {
        prefs.setConfirmMutatingTools(confirm);
        publishUpdate();
    }

    @Override
    public void setConfirmShellAndExternalTools(boolean confirm) {
        prefs.setConfirmShellAndExternalTools(confirm);
        publishUpdate();
    }

    @Override
    public void setPreferredSearchEngine(String engine) {
        prefs.setPreferredSearchEngine(engine);
        publishUpdate();
    }

    @Override
    public void setActiveWorkspacePath(String path) {
        prefs.setActiveWorkspacePath(path);
        publishUpdate();
    }

    @Override
    public void setDownloadDestinationPath(String path) {
        prefs.setDownloadDestinationPath(path);
        publishUpdate();
    }

    @Override
    public void setAllowShizukuFileFallback(boolean allowed) {
        prefs.setAllowShizukuFileFallback(allowed);
        publishUpdate();
    }

    @Override
    public void setBubbleEnabled(boolean enabled) {
        prefs.setBubbleEnabled(enabled);
        publishUpdate();
    }

    @Override
    public void setPreferredTerminalBackend(TerminalBackend backend) {
        prefs.setPreferredTerminalBackend(backend);
        publishUpdate();
    }

    @Override
    public void setToolEnabled(String toolName, boolean enabled) {
        prefs.setToolEnabled(toolName, enabled);
        publishUpdate();
    }

    private void publishUpdate() {
        AppSettings updated = prefs.loadSettings();
        this.cachedSettings = updated;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            settingsLiveData.setValue(updated);
        } else {
            settingsLiveData.postValue(updated);
        }
    }
}
