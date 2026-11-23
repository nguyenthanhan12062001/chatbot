package com.chatai.an.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ThemePalette {
    private int primaryColor;
    private int secondaryColor;
    private int backgroundColor;
    private int surfaceColor;
    private int surfaceVariantColor;
    private int accentColor;
    private int textPrimaryColor;
    private int textSecondaryColor;
    private int borderColor;
    private int onPrimaryColor;
    private List<Integer> gradientColors;
    private GradientDirection gradientDirection;

    public ThemePalette(
            int primaryColor,
            int secondaryColor,
            int backgroundColor,
            int surfaceColor,
            int surfaceVariantColor,
            int accentColor,
            int textPrimaryColor,
            int textSecondaryColor,
            int borderColor,
            int onPrimaryColor,
            List<Integer> gradientColors,
            GradientDirection gradientDirection
    ) {
        this.primaryColor = primaryColor;
        this.secondaryColor = secondaryColor;
        this.backgroundColor = backgroundColor;
        this.surfaceColor = surfaceColor;
        this.surfaceVariantColor = surfaceVariantColor;
        this.accentColor = accentColor;
        this.textPrimaryColor = textPrimaryColor;
        this.textSecondaryColor = textSecondaryColor;
        this.borderColor = borderColor;
        this.onPrimaryColor = onPrimaryColor;
        this.gradientColors = gradientColors == null
                ? new ArrayList<>()
                : new ArrayList<>(gradientColors);
        this.gradientDirection = gradientDirection != null
                ? gradientDirection
                : GradientDirection.DIAGONAL_DOWN;
    }

    public ThemePalette copy() {
        return new ThemePalette(
                primaryColor,
                secondaryColor,
                backgroundColor,
                surfaceColor,
                surfaceVariantColor,
                accentColor,
                textPrimaryColor,
                textSecondaryColor,
                borderColor,
                onPrimaryColor,
                new ArrayList<>(gradientColors),
                gradientDirection
        );
    }

    public int getPrimaryColor() {
        return primaryColor;
    }

    public void setPrimaryColor(int primaryColor) {
        this.primaryColor = primaryColor;
    }

    public int getSecondaryColor() {
        return secondaryColor;
    }

    public void setSecondaryColor(int secondaryColor) {
        this.secondaryColor = secondaryColor;
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public int getSurfaceColor() {
        return surfaceColor;
    }

    public void setSurfaceColor(int surfaceColor) {
        this.surfaceColor = surfaceColor;
    }

    public int getSurfaceVariantColor() {
        return surfaceVariantColor;
    }

    public void setSurfaceVariantColor(int surfaceVariantColor) {
        this.surfaceVariantColor = surfaceVariantColor;
    }

    public int getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(int accentColor) {
        this.accentColor = accentColor;
    }

    public int getTextPrimaryColor() {
        return textPrimaryColor;
    }

    public void setTextPrimaryColor(int textPrimaryColor) {
        this.textPrimaryColor = textPrimaryColor;
    }

    public int getTextSecondaryColor() {
        return textSecondaryColor;
    }

    public void setTextSecondaryColor(int textSecondaryColor) {
        this.textSecondaryColor = textSecondaryColor;
    }

    public int getBorderColor() {
        return borderColor;
    }

    public void setBorderColor(int borderColor) {
        this.borderColor = borderColor;
    }

    public int getOnPrimaryColor() {
        return onPrimaryColor;
    }

    public void setOnPrimaryColor(int onPrimaryColor) {
        this.onPrimaryColor = onPrimaryColor;
    }

    public List<Integer> getGradientColors() {
        return Collections.unmodifiableList(gradientColors);
    }

    public void setGradientColors(List<Integer> gradientColors) {
        this.gradientColors = gradientColors == null
                ? new ArrayList<>()
                : new ArrayList<>(gradientColors);
    }

    public GradientDirection getGradientDirection() {
        return gradientDirection;
    }

    public void setGradientDirection(GradientDirection gradientDirection) {
        this.gradientDirection = gradientDirection;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ThemePalette)) return false;
        ThemePalette that = (ThemePalette) o;
        return primaryColor == that.primaryColor
                && secondaryColor == that.secondaryColor
                && backgroundColor == that.backgroundColor
                && surfaceColor == that.surfaceColor
                && surfaceVariantColor == that.surfaceVariantColor
                && accentColor == that.accentColor
                && textPrimaryColor == that.textPrimaryColor
                && textSecondaryColor == that.textSecondaryColor
                && borderColor == that.borderColor
                && onPrimaryColor == that.onPrimaryColor
                && Objects.equals(gradientColors, that.gradientColors)
                && gradientDirection == that.gradientDirection;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                primaryColor, secondaryColor, backgroundColor, surfaceColor,
                surfaceVariantColor, accentColor, textPrimaryColor, textSecondaryColor,
                borderColor, onPrimaryColor, gradientColors, gradientDirection
        );
    }
}
