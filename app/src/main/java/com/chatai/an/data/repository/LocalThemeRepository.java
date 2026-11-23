package com.chatai.an.data.repository;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.data.local.prefs.PreferencesManager;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.repository.ThemeRepository;
import com.chatai.an.theme.ThemeManager;

public class LocalThemeRepository implements ThemeRepository {
    private final PreferencesManager prefs;
    private final MutableLiveData<ThemeConfig> activeThemeLiveData;

    public LocalThemeRepository(PreferencesManager prefs) {
        this.prefs = prefs;
        ThemeConfig initial = prefs.loadActiveTheme();
        this.activeThemeLiveData = new MutableLiveData<>(initial);
    }

    @Override
    public LiveData<ThemeConfig> observeActiveTheme() {
        return activeThemeLiveData;
    }

    @Override
    public ThemeConfig getActiveThemeSnapshot() {
        ThemeConfig current = activeThemeLiveData.getValue();
        return current != null ? current.copy() : prefs.loadActiveTheme();
    }

    @Override
    public void applyTheme(ThemeConfig themeConfig) {
        if (themeConfig == null) return;
        ThemeConfig copy = themeConfig.copy();
        prefs.saveActiveTheme(copy);
        activeThemeLiveData.setValue(copy);
        ThemeManager.applyNightMode(copy.getMode());
    }
}
