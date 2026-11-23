package com.chatai.an.theme;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowInsetsControllerCompat;

import com.chatai.an.domain.model.ThemePalette;

public final class ThemeApplier {

    private ThemeApplier() {
    }

    public static float dpToPx(Context context, float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        );
    }

    public static int dpToPxInt(Context context, float dp) {
        return Math.round(dpToPx(context, dp));
    }

    public static void applySystemBars(Activity activity, ThemePalette palette, boolean isDark) {
        Window window = activity.getWindow();
        if (window == null) return;
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        View decor = window.getDecorView();
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(window, decor);
        controller.setAppearanceLightStatusBars(!isDark);
        controller.setAppearanceLightNavigationBars(!isDark);
    }

    public static int blendWithAlpha(int color, int alpha) {
        return ColorUtils.setAlphaComponent(color, alpha);
    }

    public static ColorStateList singleColorStateList(int color) {
        return ColorStateList.valueOf(color);
    }

    public static int estimateGradientMidColor(ThemePalette palette) {
        if (palette == null) return 0xFFFF5F2B;
        java.util.List<Integer> grads = palette.getGradientColors();
        if (grads == null || grads.isEmpty()) {
            return palette.getPrimaryColor();
        }
        return grads.get(grads.size() / 2);
    }

    public static int pickHighContrastTextColor(int backgroundColor) {
        double whiteContrast = ColorUtils.calculateContrast(Color.WHITE, backgroundColor);
        int darkText = 0xFF141218;
        double darkContrast = ColorUtils.calculateContrast(darkText, backgroundColor);
        return whiteContrast >= darkContrast ? Color.WHITE : darkText;
    }
}
