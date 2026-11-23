package com.chatai.an.theme;

import android.graphics.Color;

import com.chatai.an.domain.model.GradientDirection;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemeMode;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ThemePreset;

import java.util.Arrays;

public final class ThemePresetCatalog {

    private ThemePresetCatalog() {
    }

    public static ThemeConfig createDefaultConfig() {
        return createConfigForPreset(ThemePreset.SUNSET_ORANGE, ThemeMode.SYSTEM);
    }

    public static ThemeConfig createConfigForPreset(ThemePreset preset, ThemeMode mode) {
        return new ThemeConfig(
                mode,
                preset,
                false,
                getLightPalette(preset),
                getDarkPalette(preset)
        );
    }

    public static ThemePalette getLightPalette(ThemePreset preset) {
        switch (preset) {
            case OCEAN_BLUE:
                return new ThemePalette(
                        Color.parseColor("#0284C7"),
                        Color.parseColor("#2563EB"),
                        Color.parseColor("#F4F8FC"),
                        Color.parseColor("#FFFFFF"),
                        Color.parseColor("#E6F0FA"),
                        Color.parseColor("#0EA5E9"),
                        Color.parseColor("#0F172A"),
                        Color.parseColor("#475569"),
                        Color.parseColor("#CBD5E1"),
                        Color.WHITE,
                        Arrays.asList(Color.parseColor("#0EA5E9"), Color.parseColor("#2563EB"), Color.parseColor("#4F46E5")),
                        GradientDirection.DIAGONAL_DOWN
                );
            case MIDNIGHT_PURPLE:
                return new ThemePalette(
                        Color.parseColor("#7C3AED"),
                        Color.parseColor("#4F46E5"),
                        Color.parseColor("#F7F5FC"),
                        Color.parseColor("#FFFFFF"),
                        Color.parseColor("#EDE9FE"),
                        Color.parseColor("#9333EA"),
                        Color.parseColor("#1E1B4B"),
                        Color.parseColor("#5B5478"),
                        Color.parseColor("#D8D0F0"),
                        Color.WHITE,
                        Arrays.asList(Color.parseColor("#8B5CF6"), Color.parseColor("#6366F1"), Color.parseColor("#EC4899")),
                        GradientDirection.DIAGONAL_DOWN
                );
            case EMERALD:
                return new ThemePalette(
                        Color.parseColor("#059669"),
                        Color.parseColor("#0D9488"),
                        Color.parseColor("#F3FAF7"),
                        Color.parseColor("#FFFFFF"),
                        Color.parseColor("#E2F5ED"),
                        Color.parseColor("#10B981"),
                        Color.parseColor("#062C22"),
                        Color.parseColor("#3E6358"),
                        Color.parseColor("#BDE5D6"),
                        Color.WHITE,
                        Arrays.asList(Color.parseColor("#10B981"), Color.parseColor("#0D9488"), Color.parseColor("#0284C7")),
                        GradientDirection.LEFT_TO_RIGHT
                );
            case ROSE_PINK:
                return new ThemePalette(
                        Color.parseColor("#E11D48"),
                        Color.parseColor("#EC4899"),
                        Color.parseColor("#FFF5F8"),
                        Color.parseColor("#FFFFFF"),
                        Color.parseColor("#FCE7F0"),
                        Color.parseColor("#F43F5E"),
                        Color.parseColor("#2B0E1A"),
                        Color.parseColor("#704757"),
                        Color.parseColor("#F5C6D8"),
                        Color.WHITE,
                        Arrays.asList(Color.parseColor("#FB7185"), Color.parseColor("#EC4899"), Color.parseColor("#D946EF")),
                        GradientDirection.DIAGONAL_DOWN
                );
            case MINIMAL_GRAY:
                return new ThemePalette(
                        Color.parseColor("#334155"),
                        Color.parseColor("#64748B"),
                        Color.parseColor("#F7F7F8"),
                        Color.parseColor("#FFFFFF"),
                        Color.parseColor("#ECEEF2"),
                        Color.parseColor("#1E293B"),
                        Color.parseColor("#111827"),
                        Color.parseColor("#4B5563"),
                        Color.parseColor("#D1D5DB"),
                        Color.WHITE,
                        Arrays.asList(Color.parseColor("#475569"), Color.parseColor("#1E293B")),
                        GradientDirection.LEFT_TO_RIGHT
                );
            case SUNSET_ORANGE:
            default:
                return new ThemePalette(
                        Color.parseColor("#FF5F2B"),
                        Color.parseColor("#FF3E7F"),
                        Color.parseColor("#FFF9F6"),
                        Color.parseColor("#FFFFFF"),
                        Color.parseColor("#FFF0E8"),
                        Color.parseColor("#FF7A45"),
                        Color.parseColor("#1F1410"),
                        Color.parseColor("#6B534B"),
                        Color.parseColor("#F4D6C8"),
                        Color.WHITE,
                        Arrays.asList(Color.parseColor("#FF7A3D"), Color.parseColor("#FF5154"), Color.parseColor("#FF3B8B")),
                        GradientDirection.DIAGONAL_DOWN
                );
        }
    }

    public static ThemePalette getDarkPalette(ThemePreset preset) {
        switch (preset) {
            case OCEAN_BLUE:
                return new ThemePalette(
                        Color.parseColor("#38BDF8"),
                        Color.parseColor("#60A5FA"),
                        Color.parseColor("#0B1120"),
                        Color.parseColor("#131C31"),
                        Color.parseColor("#1D2A45"),
                        Color.parseColor("#0EA5E9"),
                        Color.parseColor("#F1F5F9"),
                        Color.parseColor("#94A3B8"),
                        Color.parseColor("#283757"),
                        Color.parseColor("#08121E"),
                        Arrays.asList(Color.parseColor("#0284C7"), Color.parseColor("#2563EB"), Color.parseColor("#4338CA")),
                        GradientDirection.DIAGONAL_DOWN
                );
            case MIDNIGHT_PURPLE:
                return new ThemePalette(
                        Color.parseColor("#A78BFA"),
                        Color.parseColor("#818CF8"),
                        Color.parseColor("#0F0B1A"),
                        Color.parseColor("#19132B"),
                        Color.parseColor("#261E3F"),
                        Color.parseColor("#C084FC"),
                        Color.parseColor("#F5F3FF"),
                        Color.parseColor("#A89EC9"),
                        Color.parseColor("#342A54"),
                        Color.parseColor("#120A24"),
                        Arrays.asList(Color.parseColor("#7C3AED"), Color.parseColor("#4F46E5"), Color.parseColor("#BE185D")),
                        GradientDirection.DIAGONAL_DOWN
                );
            case EMERALD:
                return new ThemePalette(
                        Color.parseColor("#34D399"),
                        Color.parseColor("#2DD4BF"),
                        Color.parseColor("#071511"),
                        Color.parseColor("#0F231C"),
                        Color.parseColor("#173329"),
                        Color.parseColor("#10B981"),
                        Color.parseColor("#ECFDF5"),
                        Color.parseColor("#86B8A7"),
                        Color.parseColor("#21473A"),
                        Color.parseColor("#052018"),
                        Arrays.asList(Color.parseColor("#059669"), Color.parseColor("#0D9488"), Color.parseColor("#0369A1")),
                        GradientDirection.LEFT_TO_RIGHT
                );
            case ROSE_PINK:
                return new ThemePalette(
                        Color.parseColor("#FB7185"),
                        Color.parseColor("#F472B6"),
                        Color.parseColor("#190B12"),
                        Color.parseColor("#26121C"),
                        Color.parseColor("#361B29"),
                        Color.parseColor("#F43F5E"),
                        Color.parseColor("#FFF1F2"),
                        Color.parseColor("#C996A8"),
                        Color.parseColor("#4A2638"),
                        Color.parseColor("#260812"),
                        Arrays.asList(Color.parseColor("#E11D48"), Color.parseColor("#DB2777"), Color.parseColor("#A21CAF")),
                        GradientDirection.DIAGONAL_DOWN
                );
            case MINIMAL_GRAY:
                return new ThemePalette(
                        Color.parseColor("#CBD5E1"),
                        Color.parseColor("#94A3B8"),
                        Color.parseColor("#111317"),
                        Color.parseColor("#1A1D23"),
                        Color.parseColor("#252932"),
                        Color.parseColor("#E2E8F0"),
                        Color.parseColor("#F8FAFC"),
                        Color.parseColor("#9CA3AF"),
                        Color.parseColor("#313642"),
                        Color.parseColor("#0F172A"),
                        Arrays.asList(Color.parseColor("#334155"), Color.parseColor("#1E293B")),
                        GradientDirection.LEFT_TO_RIGHT
                );
            case SUNSET_ORANGE:
            default:
                return new ThemePalette(
                        Color.parseColor("#FF7A45"),
                        Color.parseColor("#FF5C93"),
                        Color.parseColor("#16100E"),
                        Color.parseColor("#221815"),
                        Color.parseColor("#2F211D"),
                        Color.parseColor("#FF8F5E"),
                        Color.parseColor("#FFF5F0"),
                        Color.parseColor("#C5A59A"),
                        Color.parseColor("#432F28"),
                        Color.parseColor("#260D05"),
                        Arrays.asList(Color.parseColor("#E85322"), Color.parseColor("#E63956"), Color.parseColor("#C8286B")),
                        GradientDirection.DIAGONAL_DOWN
                );
        }
    }
}
