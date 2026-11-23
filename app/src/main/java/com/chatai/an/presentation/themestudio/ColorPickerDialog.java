package com.chatai.an.presentation.themestudio;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chatai.an.R;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

public final class ColorPickerDialog {

    public interface OnColorPickedListener {
        void onColorPicked(int color);
    }

    private static final int[] QUICK_SWATCHES = new int[]{
            0xFFFF5F2B, 0xFFFF3E7F, 0xFF0EA5E9, 0xFF7C3AED,
            0xFF10B981, 0xFFF43F5E, 0xFFF59E0B, 0xFF334155
    };

    private ColorPickerDialog() {
    }

    public static void show(Context context, String title, int initialColor, OnColorPickedListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null);
        View preview = view.findViewById(R.id.view_picker_preview);
        EditText etHex = view.findViewById(R.id.et_picker_hex);
        TextView tvR = view.findViewById(R.id.tv_slider_r);
        TextView tvG = view.findViewById(R.id.tv_slider_g);
        TextView tvB = view.findViewById(R.id.tv_slider_b);
        Slider sliderR = view.findViewById(R.id.slider_red);
        Slider sliderG = view.findViewById(R.id.slider_green);
        Slider sliderB = view.findViewById(R.id.slider_blue);
        LinearLayout swatchesRow = view.findViewById(R.id.quick_swatches_row);

        final int[] selectedColor = new int[]{initialColor};
        final boolean[] updatingFromCode = new boolean[]{false};

        Runnable syncUi = () -> {
            updatingFromCode[0] = true;
            int r = Color.red(selectedColor[0]);
            int g = Color.green(selectedColor[0]);
            int b = Color.blue(selectedColor[0]);
            sliderR.setValue(r);
            sliderG.setValue(g);
            sliderB.setValue(b);
            tvR.setText("R: " + r);
            tvG.setText("G: " + g);
            tvB.setText("B: " + b);
            preview.setBackground(GradientDrawableFactory.createPill(
                    selectedColor[0],
                    0x44888888,
                    ThemeApplier.dpToPxInt(context, 1.5f)
            ));
            String hex = String.format("#%06X", (0xFFFFFF & selectedColor[0]));
            if (!hex.equalsIgnoreCase(etHex.getText().toString())) {
                etHex.setText(hex);
            }
            updatingFromCode[0] = false;
        };

        Slider.OnChangeListener sliderListener = (slider, value, fromUser) -> {
            if (updatingFromCode[0] || !fromUser) return;
            selectedColor[0] = Color.rgb(
                    Math.round(sliderR.getValue()),
                    Math.round(sliderG.getValue()),
                    Math.round(sliderB.getValue())
            );
            syncUi.run();
        };

        sliderR.addOnChangeListener(sliderListener);
        sliderG.addOnChangeListener(sliderListener);
        sliderB.addOnChangeListener(sliderListener);

        etHex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updatingFromCode[0] || s == null) return;
                String raw = s.toString().trim();
                if (raw.length() == 7 && raw.startsWith("#")) {
                    try {
                        selectedColor[0] = Color.parseColor(raw);
                        syncUi.run();
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        int swatchSize = ThemeApplier.dpToPxInt(context, 30f);
        int swatchMargin = ThemeApplier.dpToPxInt(context, 6f);
        for (int swatchColor : QUICK_SWATCHES) {
            View swatch = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(swatchSize, swatchSize);
            lp.setMargins(0, 0, swatchMargin, 0);
            swatch.setLayoutParams(lp);
            swatch.setBackground(GradientDrawableFactory.createPill(swatchColor, 0x44000000, 1));
            swatch.setOnClickListener(v -> {
                selectedColor[0] = swatchColor;
                syncUi.run();
            });
            swatchesRow.addView(swatch);
        }

        syncUi.run();

        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    if (listener != null) {
                        listener.onColorPicked(selectedColor[0]);
                    }
                })
                .show();
    }
}
