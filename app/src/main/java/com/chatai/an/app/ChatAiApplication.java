package com.chatai.an.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.theme.ThemeManager;

public class ChatAiApplication extends Application {
    private AppContainer appContainer;

    @Override
    public void onCreate() {
        super.onCreate();
        appContainer = new AppContainer(this);

        ThemeConfig activeTheme = appContainer.getThemeRepository().getActiveThemeSnapshot();
        ThemeManager.applyNightMode(activeTheme.getMode());

        AppSettings settings = appContainer.getSettingsRepository().getSettingsSnapshot();
        AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(settings.getLanguage().getTag())
        );
    }

    public AppContainer getAppContainer() {
        return appContainer;
    }
}
