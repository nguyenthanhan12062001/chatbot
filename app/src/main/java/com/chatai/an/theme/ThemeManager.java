package com.chatai.an.theme;

import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemeMode;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ThemePreset;

public final class ThemeManager {

    private ThemeManager() {
    }

    public static void applyNightMode(ThemeMode mode) {
        if (mode == null) mode = ThemeMode.SYSTEM;
        switch (mode) {
            case LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static boolean isDarkActive(Context context, ThemeConfig config) {
        if (config != null) {
            if (config.getMode() == ThemeMode.DARK) return true;
            if (config.getMode() == ThemeMode.LIGHT) return false;
        }
        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    public static ThemePalette resolvePalette(Context context, ThemeConfig config) {
        if (config == null) {
            return ThemePresetCatalog.getLightPalette(ThemePreset.SUNSET_ORANGE);
        }
        boolean dark = isDarkActive(context, config);
        ThemePalette palette = dark ? config.getDarkPalette() : config.getLightPalette();
        if (palette == null) {
            palette = dark
                    ? ThemePresetCatalog.getDarkPalette(config.getPreset())
                    : ThemePresetCatalog.getLightPalette(config.getPreset());
        }
        return palette;
    }
}
