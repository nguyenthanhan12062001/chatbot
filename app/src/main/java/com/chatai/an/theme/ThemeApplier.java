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
}
