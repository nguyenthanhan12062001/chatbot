package com.chatai.an.presentation.common;

import android.content.Context;

import com.chatai.an.R;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.ThemeMode;
import com.chatai.an.domain.model.ThemePreset;

import java.text.DateFormat;
import java.util.Date;

public final class UiFormatters {

    private UiFormatters() {
    }

    public static String formatTime(long timestampMs) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(timestampMs));
    }

    public static String formatPresetName(Context context, ThemePreset preset) {
        if (preset == null) return context.getString(R.string.preset_sunset_orange);
        switch (preset) {
            case OCEAN_BLUE:
                return context.getString(R.string.preset_ocean_blue);
            case MIDNIGHT_PURPLE:
                return context.getString(R.string.preset_midnight_purple);
            case EMERALD:
                return context.getString(R.string.preset_emerald);
            case ROSE_PINK:
                return context.getString(R.string.preset_rose_pink);
            case MINIMAL_GRAY:
                return context.getString(R.string.preset_minimal_gray);
            case SUNSET_ORANGE:
            default:
                return context.getString(R.string.preset_sunset_orange);
        }
    }

    public static String formatThemeMode(Context context, ThemeMode mode) {
        if (mode == null) return context.getString(R.string.theme_mode_system);
        switch (mode) {
            case LIGHT:
                return context.getString(R.string.theme_mode_light);
            case DARK:
                return context.getString(R.string.theme_mode_dark);
            case SYSTEM:
            default:
                return context.getString(R.string.theme_mode_system);
        }
    }

    public static String formatCapabilityStatus(Context context, CapabilityStatus status) {
        if (status == null) return context.getString(R.string.cap_status_unknown);
        switch (status) {
            case SUPPORTED:
                return context.getString(R.string.cap_status_supported);
            case UNSUPPORTED:
                return context.getString(R.string.cap_status_unsupported);
            case UNKNOWN:
            default:
                return context.getString(R.string.cap_status_unknown);
        }
    }
}
