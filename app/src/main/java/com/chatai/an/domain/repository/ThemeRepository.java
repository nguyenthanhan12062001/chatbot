package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.ThemeConfig;

public interface ThemeRepository {
    LiveData<ThemeConfig> observeActiveTheme();

    ThemeConfig getActiveThemeSnapshot();

    void applyTheme(ThemeConfig themeConfig);
}
