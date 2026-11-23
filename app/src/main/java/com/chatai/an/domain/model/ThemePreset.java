package com.chatai.an.domain.model;

public enum ThemePreset {
    SUNSET_ORANGE("sunset_orange"),
    OCEAN_BLUE("ocean_blue"),
    MIDNIGHT_PURPLE("midnight_purple"),
    EMERALD("emerald"),
    ROSE_PINK("rose_pink"),
    MINIMAL_GRAY("minimal_gray");

    private final String key;

    ThemePreset(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public static ThemePreset fromKey(String key) {
        if (key == null) return SUNSET_ORANGE;
        for (ThemePreset preset : values()) {
            if (preset.key.equals(key)) {
                return preset;
            }
        }
        return SUNSET_ORANGE;
    }
}
