package com.chatai.an.domain.model;

import java.util.Objects;

public class ThemeConfig {
    private ThemeMode mode;
    private ThemePreset preset;
    private boolean custom;
    private ThemePalette lightPalette;
    private ThemePalette darkPalette;

    public ThemeConfig(
            ThemeMode mode,
            ThemePreset preset,
            boolean custom,
            ThemePalette lightPalette,
            ThemePalette darkPalette
    ) {
        this.mode = mode != null ? mode : ThemeMode.SYSTEM;
        this.preset = preset != null ? preset : ThemePreset.SUNSET_ORANGE;
        this.custom = custom;
        this.lightPalette = lightPalette;
        this.darkPalette = darkPalette;
    }

    public ThemeConfig copy() {
        return new ThemeConfig(
                mode,
                preset,
                custom,
                lightPalette != null ? lightPalette.copy() : null,
                darkPalette != null ? darkPalette.copy() : null
        );
    }

    public ThemeMode getMode() {
        return mode;
    }

    public void setMode(ThemeMode mode) {
        this.mode = mode;
    }

    public ThemePreset getPreset() {
        return preset;
    }

    public void setPreset(ThemePreset preset) {
        this.preset = preset;
    }

    public boolean isCustom() {
        return custom;
    }

    public void setCustom(boolean custom) {
        this.custom = custom;
    }

    public ThemePalette getLightPalette() {
        return lightPalette;
    }

    public void setLightPalette(ThemePalette lightPalette) {
        this.lightPalette = lightPalette;
    }

    public ThemePalette getDarkPalette() {
        return darkPalette;
    }

    public void setDarkPalette(ThemePalette darkPalette) {
        this.darkPalette = darkPalette;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ThemeConfig)) return false;
        ThemeConfig that = (ThemeConfig) o;
        return custom == that.custom
                && mode == that.mode
                && preset == that.preset
                && Objects.equals(lightPalette, that.lightPalette)
                && Objects.equals(darkPalette, that.darkPalette);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, preset, custom, lightPalette, darkPalette);
    }
}
