package com.chatai.an.presentation.bubble;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Root container for the expanded floating chat popup that intercepts the system Back key
 * so pressing Back collapses the popup into the floating bubble instead of being swallowed.
 */
public class BubblePopupFrameLayout extends FrameLayout {

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    private OnBackPressedListener backPressedListener;

    public BubblePopupFrameLayout(@NonNull Context context) {
        super(context);
    }

    public BubblePopupFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BubblePopupFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        this.backPressedListener = listener;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP
                && !event.isCanceled()) {
            if (backPressedListener != null) {
                backPressedListener.onBackPressed();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
