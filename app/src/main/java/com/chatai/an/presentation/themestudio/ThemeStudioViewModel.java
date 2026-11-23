package com.chatai.an.presentation.themestudio;

import android.graphics.Color;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.chatai.an.domain.model.GradientDirection;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ThemePreset;
import com.chatai.an.domain.repository.ThemeRepository;
import com.chatai.an.theme.ThemePresetCatalog;

import java.util.ArrayList;
import java.util.List;

public class ThemeStudioViewModel extends ViewModel {

    public enum ColorToken {
        PRIMARY,
        SECONDARY,
        BACKGROUND,
        SURFACE,
        ACCENT,
        TEXT_PRIMARY,
        GRADIENT_STOP_1,
        GRADIENT_STOP_2,
        GRADIENT_STOP_3
    }

    private final ThemeRepository themeRepository;
    private final ThemeConfig initialActiveTheme;
    private final MutableLiveData<ThemeConfig> draftThemeLiveData;
    private final MutableLiveData<Boolean> editingDarkVariant = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> hasUnsavedChanges = new MutableLiveData<>(false);

    public ThemeStudioViewModel(ThemeRepository themeRepository) {
        this.themeRepository = themeRepository;
        this.initialActiveTheme = themeRepository.getActiveThemeSnapshot();
        this.draftThemeLiveData = new MutableLiveData<>(initialActiveTheme.copy());
    }

    private void setDraft(ThemeConfig draft) {
        draftThemeLiveData.setValue(draft);
        hasUnsavedChanges.setValue(!initialActiveTheme.equals(draft));
    }

    public ThemeConfig getInitialActiveTheme() {
        return initialActiveTheme;
    }

    public LiveData<ThemeConfig> getDraftTheme() {
        return draftThemeLiveData;
    }

    public LiveData<Boolean> isEditingDarkVariant() {
        return editingDarkVariant;
    }

    public LiveData<Boolean> getHasUnsavedChanges() {
        return hasUnsavedChanges;
    }

    public void setEditingDarkVariant(boolean dark) {
        editingDarkVariant.setValue(dark);
    }

    public ThemePalette getEditingPalette() {
        ThemeConfig draft = draftThemeLiveData.getValue();
        if (draft == null) return initialActiveTheme.getLightPalette();
        boolean dark = Boolean.TRUE.equals(editingDarkVariant.getValue());
        return dark ? draft.getDarkPalette() : draft.getLightPalette();
    }

    public void selectPreset(ThemePreset preset) {
        ThemeConfig currentDraft = draftThemeLiveData.getValue();
        if (currentDraft == null) return;
        ThemeConfig updated = ThemePresetCatalog.createConfigForPreset(preset, currentDraft.getMode());
        setDraft(updated);
    }

    public void setGradientStopCount(int count) {
        ThemeConfig draft = copyDraft();
        if (draft == null) return;
        ThemePalette palette = targetPalette(draft);
        List<Integer> current = new ArrayList<>(palette.getGradientColors());
        if (count == 2) {
            while (current.size() > 2) {
                current.remove(current.size() - 1);
            }
            while (current.size() < 2) {
                current.add(palette.getSecondaryColor());
            }
        } else {
            while (current.size() < 3) {
                current.add(current.isEmpty() ? palette.getAccentColor() : blendColors(current.get(current.size() - 1), palette.getAccentColor()));
            }
        }
        palette.setGradientColors(current);
        draft.setCustom(true);
        setDraft(draft);
    }

    public void setGradientDirection(GradientDirection direction) {
        ThemeConfig draft = copyDraft();
        if (draft == null) return;
        ThemePalette palette = targetPalette(draft);
        palette.setGradientDirection(direction);
        draft.setCustom(true);
        setDraft(draft);
    }

    public void updateColorToken(ColorToken token, int color) {
        ThemeConfig draft = copyDraft();
        if (draft == null) return;
        ThemePalette palette = targetPalette(draft);

        List<Integer> grad = new ArrayList<>(palette.getGradientColors());
        while (grad.size() < 2) {
            grad.add(palette.getSecondaryColor());
        }

        switch (token) {
            case PRIMARY:
                palette.setPrimaryColor(color);
                palette.setOnPrimaryColor(isColorLight(color) ? Color.parseColor("#111111") : Color.WHITE);
                break;
            case SECONDARY:
                palette.setSecondaryColor(color);
                break;
            case BACKGROUND:
                palette.setBackgroundColor(color);
                break;
            case SURFACE:
                palette.setSurfaceColor(color);
                palette.setSurfaceVariantColor(adjustSurfaceVariant(color));
                break;
            case ACCENT:
                palette.setAccentColor(color);
                break;
            case TEXT_PRIMARY:
                palette.setTextPrimaryColor(color);
                palette.setTextSecondaryColor(adjustSecondaryText(color));
                break;
            case GRADIENT_STOP_1:
                grad.set(0, color);
                palette.setGradientColors(grad);
                break;
            case GRADIENT_STOP_2:
                grad.set(1, color);
                palette.setGradientColors(grad);
                break;
            case GRADIENT_STOP_3:
                if (grad.size() >= 3) {
                    grad.set(2, color);
                } else {
                    grad.add(color);
                }
                palette.setGradientColors(grad);
                break;
        }
        draft.setCustom(true);
        setDraft(draft);
    }

    public void applyDraftTheme() {
        ThemeConfig draft = draftThemeLiveData.getValue();
        if (draft != null) {
            themeRepository.applyTheme(draft);
        }
    }

    private ThemeConfig copyDraft() {
        ThemeConfig current = draftThemeLiveData.getValue();
        return current != null ? current.copy() : null;
    }

    private ThemePalette targetPalette(ThemeConfig config) {
        boolean dark = Boolean.TRUE.equals(editingDarkVariant.getValue());
        return dark ? config.getDarkPalette() : config.getLightPalette();
    }

    private static boolean isColorLight(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.65;
    }

    private static int adjustSurfaceVariant(int surfaceColor) {
        if (isColorLight(surfaceColor)) {
            return Color.rgb(
                    Math.max(0, Color.red(surfaceColor) - 14),
                    Math.max(0, Color.green(surfaceColor) - 14),
                    Math.max(0, Color.blue(surfaceColor) - 14)
            );
        } else {
            return Color.rgb(
                    Math.min(255, Color.red(surfaceColor) + 14),
                    Math.min(255, Color.green(surfaceColor) + 14),
                    Math.min(255, Color.blue(surfaceColor) + 14)
            );
        }
    }

    private static int adjustSecondaryText(int primaryTextColor) {
        return Color.argb(
                185,
                Color.red(primaryTextColor),
                Color.green(primaryTextColor),
                Color.blue(primaryTextColor)
        );
    }

    private static int blendColors(int c1, int c2) {
        int r = (Color.red(c1) + Color.red(c2)) / 2;
        int g = (Color.green(c1) + Color.green(c2)) / 2;
        int b = (Color.blue(c1) + Color.blue(c2)) / 2;
        return Color.rgb(r, g, b);
    }
}
