package com.chatai.an.theme;

import android.graphics.drawable.GradientDrawable;

import com.chatai.an.domain.model.GradientDirection;
import com.chatai.an.domain.model.ThemePalette;

import java.util.List;

public final class GradientDrawableFactory {

    private GradientDrawableFactory() {
    }

    public static GradientDrawable createLinearGradient(ThemePalette palette, float cornerRadiusPx) {
        GradientDrawable drawable = new GradientDrawable(toOrientation(palette.getGradientDirection()), extractGradientColors(palette));
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(cornerRadiusPx);
        return drawable;
    }

    public static GradientDrawable createLinearGradientWithRadii(ThemePalette palette, float[] cornerRadiiPx) {
        GradientDrawable drawable = new GradientDrawable(toOrientation(palette.getGradientDirection()), extractGradientColors(palette));
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadii(cornerRadiiPx);
        return drawable;
    }

    private static int[] extractGradientColors(ThemePalette palette) {
        List<Integer> colorsList = palette.getGradientColors();
        if (colorsList == null || colorsList.isEmpty()) {
            return new int[]{palette.getPrimaryColor(), palette.getSecondaryColor()};
        } else if (colorsList.size() == 1) {
            return new int[]{colorsList.get(0), palette.getSecondaryColor()};
        } else {
            int[] colors = new int[colorsList.size()];
            for (int i = 0; i < colorsList.size(); i++) {
                colors[i] = colorsList.get(i);
            }
            return colors;
        }
    }

    public static GradientDrawable createSurfaceCard(int fillColor, int strokeColor, int strokeWidthPx, float cornerRadiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(cornerRadiusPx);
        if (strokeWidthPx > 0) {
            drawable.setStroke(strokeWidthPx, strokeColor);
        }
        return drawable;
    }

    public static GradientDrawable createSurfaceCardWithRadii(int fillColor, int strokeColor, int strokeWidthPx, float[] cornerRadiiPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadii(cornerRadiiPx);
        if (strokeWidthPx > 0) {
            drawable.setStroke(strokeWidthPx, strokeColor);
        }
        return drawable;
    }

    public static GradientDrawable createPill(int fillColor, int strokeColor, int strokeWidthPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(999f);
        if (strokeWidthPx > 0) {
            drawable.setStroke(strokeWidthPx, strokeColor);
        }
        return drawable;
    }

    public static GradientDrawable.Orientation toOrientation(GradientDirection direction) {
        if (direction == null) return GradientDrawable.Orientation.TL_BR;
        switch (direction) {
            case TOP_TO_BOTTOM:
                return GradientDrawable.Orientation.TOP_BOTTOM;
            case LEFT_TO_RIGHT:
                return GradientDrawable.Orientation.LEFT_RIGHT;
            case DIAGONAL_UP:
                return GradientDrawable.Orientation.BL_TR;
            case DIAGONAL_DOWN:
            default:
                return GradientDrawable.Orientation.TL_BR;
        }
    }
}
