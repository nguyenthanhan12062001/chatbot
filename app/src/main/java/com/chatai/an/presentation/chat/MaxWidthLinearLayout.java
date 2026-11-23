package com.chatai.an.presentation.chat;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * A LinearLayout that behaves like wrap_content for short content while capping
 * horizontal expansion to a configurable percentage of the parent's available width
 * and an optional pixel ceiling (for tablets/wide screens).
 */
public class MaxWidthLinearLayout extends LinearLayout {

    private float maxWidthRatio = 0.86f;
    private int maxWidthPx = 0;

    public MaxWidthLinearLayout(Context context) {
        super(context);
    }

    public MaxWidthLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxWidthLinearLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxWidthRatio(float ratio) {
        float clamped = Math.max(0.2f, Math.min(1.0f, ratio));
        if (Math.abs(this.maxWidthRatio - clamped) > 0.001f) {
            this.maxWidthRatio = clamped;
            requestLayout();
        }
    }

    public float getMaxWidthRatio() {
        return maxWidthRatio;
    }

    public void setMaxWidthPx(int maxWidthPx) {
        if (this.maxWidthPx != maxWidthPx) {
            this.maxWidthPx = Math.max(0, maxWidthPx);
            requestLayout();
        }
    }

    public int getMaxWidthPx() {
        return maxWidthPx;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        int cap = Integer.MAX_VALUE;
        if (widthMode != MeasureSpec.UNSPECIFIED && widthSize > 0 && maxWidthRatio > 0f && maxWidthRatio < 1.0f) {
            cap = Math.min(cap, Math.round(widthSize * maxWidthRatio));
        }
        if (maxWidthPx > 0) {
            cap = Math.min(cap, maxWidthPx);
        }

        if (cap != Integer.MAX_VALUE) {
            if (widthMode == MeasureSpec.UNSPECIFIED) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
            } else if (widthSize > cap) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
