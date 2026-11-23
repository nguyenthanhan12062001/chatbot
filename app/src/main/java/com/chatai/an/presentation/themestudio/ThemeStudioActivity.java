package com.chatai.an.presentation.themestudio;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.chatai.an.R;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.databinding.ActivityThemeStudioBinding;
import com.chatai.an.domain.model.GradientDirection;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ThemePreset;
import com.chatai.an.presentation.common.AppViewModelFactory;
import com.chatai.an.presentation.common.UiFormatters;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.chatai.an.theme.ThemePresetCatalog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class ThemeStudioActivity extends AppCompatActivity {

    private ActivityThemeStudioBinding binding;
    private ThemeStudioViewModel viewModel;
    private ThemePalette activeChromePalette;
    private boolean activeChromeIsDark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityThemeStudioBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.themeStudioRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        viewModel = new ViewModelProvider(this, new AppViewModelFactory(app.getAppContainer()))
                .get(ThemeStudioViewModel.class);

        ThemeConfig active = viewModel.getInitialActiveTheme();
        activeChromeIsDark = ThemeManager.isDarkActive(this, active);
        activeChromePalette = ThemeManager.resolvePalette(this, active);
        viewModel.setEditingDarkVariant(activeChromeIsDark);

        applyOuterChromeWithActiveTheme();

        setupListeners();
        observeDraft();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleExitAttempt();
            }
        });
    }

    private void setupListeners() {
        binding.btnStudioBack.setOnClickListener(v -> handleExitAttempt());
        binding.btnStudioCancel.setOnClickListener(v -> handleExitAttempt());

        binding.btnStudioApply.setOnClickListener(v -> {
            viewModel.applyDraftTheme();
            Toast.makeText(this, R.string.theme_studio_applied_msg, Toast.LENGTH_SHORT).show();
            finish();
        });

        binding.btnVariantLight.setOnClickListener(v -> viewModel.setEditingDarkVariant(false));
        binding.btnVariantDark.setOnClickListener(v -> viewModel.setEditingDarkVariant(true));

        binding.btnGrad2Colors.setOnClickListener(v -> viewModel.setGradientStopCount(2));
        binding.btnGrad3Colors.setOnClickListener(v -> viewModel.setGradientStopCount(3));

        binding.btnDirTopBottom.setOnClickListener(v -> viewModel.setGradientDirection(GradientDirection.TOP_TO_BOTTOM));
        binding.btnDirLeftRight.setOnClickListener(v -> viewModel.setGradientDirection(GradientDirection.LEFT_TO_RIGHT));
        binding.btnDirDiagDown.setOnClickListener(v -> viewModel.setGradientDirection(GradientDirection.DIAGONAL_DOWN));
        binding.btnDirDiagUp.setOnClickListener(v -> viewModel.setGradientDirection(GradientDirection.DIAGONAL_UP));

        binding.btnGradStop1.setOnClickListener(v -> {
            List<Integer> grads = viewModel.getEditingPalette().getGradientColors();
            int current = grads.size() > 0 ? grads.get(0) : viewModel.getEditingPalette().getPrimaryColor();
            openPicker(R.string.color_token_grad_1, current, ThemeStudioViewModel.ColorToken.GRADIENT_STOP_1);
        });
        binding.btnGradStop2.setOnClickListener(v -> {
            List<Integer> grads = viewModel.getEditingPalette().getGradientColors();
            int current = grads.size() > 1 ? grads.get(1) : viewModel.getEditingPalette().getSecondaryColor();
            openPicker(R.string.color_token_grad_2, current, ThemeStudioViewModel.ColorToken.GRADIENT_STOP_2);
        });
        binding.btnGradStop3.setOnClickListener(v -> {
            List<Integer> grads = viewModel.getEditingPalette().getGradientColors();
            int current = grads.size() > 2 ? grads.get(2) : viewModel.getEditingPalette().getAccentColor();
            openPicker(R.string.color_token_grad_3, current, ThemeStudioViewModel.ColorToken.GRADIENT_STOP_3);
        });

        binding.btnColorPrimary.setOnClickListener(v ->
                openPicker(R.string.color_token_primary, viewModel.getEditingPalette().getPrimaryColor(), ThemeStudioViewModel.ColorToken.PRIMARY)
        );
        binding.btnColorSecondary.setOnClickListener(v ->
                openPicker(R.string.color_token_secondary, viewModel.getEditingPalette().getSecondaryColor(), ThemeStudioViewModel.ColorToken.SECONDARY)
        );
        binding.btnColorBackground.setOnClickListener(v ->
                openPicker(R.string.color_token_background, viewModel.getEditingPalette().getBackgroundColor(), ThemeStudioViewModel.ColorToken.BACKGROUND)
        );
        binding.btnColorSurface.setOnClickListener(v ->
                openPicker(R.string.color_token_surface, viewModel.getEditingPalette().getSurfaceColor(), ThemeStudioViewModel.ColorToken.SURFACE)
        );
        binding.btnColorAccent.setOnClickListener(v ->
                openPicker(R.string.color_token_accent, viewModel.getEditingPalette().getAccentColor(), ThemeStudioViewModel.ColorToken.ACCENT)
        );
        binding.btnColorText.setOnClickListener(v ->
                openPicker(R.string.color_token_text, viewModel.getEditingPalette().getTextPrimaryColor(), ThemeStudioViewModel.ColorToken.TEXT_PRIMARY)
        );
    }

    private void openPicker(int titleRes, int currentColor, ThemeStudioViewModel.ColorToken token) {
        ColorPickerDialog.show(this, getString(titleRes), currentColor, picked ->
                viewModel.updateColorToken(token, picked)
        );
    }

    private void observeDraft() {
        viewModel.getDraftTheme().observe(this, draft -> renderDraft(draft));
        viewModel.isEditingDarkVariant().observe(this, dark -> {
            ThemeConfig draft = viewModel.getDraftTheme().getValue();
            if (draft != null) {
                renderDraft(draft);
            }
        });
    }

    private void renderDraft(ThemeConfig draft) {
        if (draft == null) return;
        boolean editingDark = Boolean.TRUE.equals(viewModel.isEditingDarkVariant().getValue());
        ThemePalette draftPalette = editingDark ? draft.getDarkPalette() : draft.getLightPalette();

        // 1. Update Live Preview strictly with DraftTheme palette
        float cardRadius = ThemeApplier.dpToPx(this, 18f);
        int stroke = ThemeApplier.dpToPxInt(this, 1.5f);
        binding.previewScreenCard.setBackground(GradientDrawableFactory.createSurfaceCard(
                draftPalette.getBackgroundColor(),
                draftPalette.getBorderColor(),
                stroke,
                cardRadius
        ));
        binding.previewBrand.setTextColor(draftPalette.getTextPrimaryColor());
        binding.previewGradientPill.setBackground(
                GradientDrawableFactory.createLinearGradient(draftPalette, 999f)
        );
        binding.previewGradientPill.setTextColor(draftPalette.getOnPrimaryColor());

        binding.previewUserBubble.setBackground(GradientDrawableFactory.createSurfaceCard(
                draftPalette.getSurfaceVariantColor(),
                draftPalette.getBorderColor(),
                ThemeApplier.dpToPxInt(this, 1f),
                ThemeApplier.dpToPx(this, 14f)
        ));
        binding.tvPreviewUserText.setTextColor(draftPalette.getTextPrimaryColor());

        binding.previewAssistantBox.setBackground(GradientDrawableFactory.createSurfaceCard(
                draftPalette.getSurfaceColor(),
                draftPalette.getBorderColor(),
                ThemeApplier.dpToPxInt(this, 1f),
                ThemeApplier.dpToPx(this, 14f)
        ));
        binding.tvPreviewAssistantText.setTextColor(draftPalette.getTextPrimaryColor());

        binding.previewComposerBar.setBackground(GradientDrawableFactory.createPill(
                draftPalette.getSurfaceColor(),
                draftPalette.getBorderColor(),
                ThemeApplier.dpToPxInt(this, 1f)
        ));
        binding.tvPreviewComposerHint.setTextColor(draftPalette.getTextSecondaryColor());
        binding.previewSendCircle.setBackground(
                GradientDrawableFactory.createLinearGradient(draftPalette, 999f)
        );

        // 2. Variant toggle styling
        styleToggleChip(binding.btnVariantLight, !editingDark);
        styleToggleChip(binding.btnVariantDark, editingDark);

        // 3. Presets Row (showing all 6 presets)
        renderPresetChips(draft, editingDark);

        // 4. Gradient controls
        List<Integer> grads = draftPalette.getGradientColors();
        boolean has3Stops = grads.size() >= 3;
        styleToggleChip(binding.btnGrad2Colors, !has3Stops);
        styleToggleChip(binding.btnGrad3Colors, has3Stops);

        binding.btnGradStop3.setVisibility(has3Stops ? View.VISIBLE : View.GONE);
        int g1 = grads.size() > 0 ? grads.get(0) : draftPalette.getPrimaryColor();
        int g2 = grads.size() > 1 ? grads.get(1) : draftPalette.getSecondaryColor();
        int g3 = grads.size() > 2 ? grads.get(2) : draftPalette.getAccentColor();

        setSwatchColor(binding.swatchGrad1, g1);
        setSwatchColor(binding.swatchGrad2, g2);
        setSwatchColor(binding.swatchGrad3, g3);

        GradientDirection dir = draftPalette.getGradientDirection();
        styleToggleChip(binding.btnDirTopBottom, dir == GradientDirection.TOP_TO_BOTTOM);
        styleToggleChip(binding.btnDirLeftRight, dir == GradientDirection.LEFT_TO_RIGHT);
        styleToggleChip(binding.btnDirDiagDown, dir == GradientDirection.DIAGONAL_DOWN);
        styleToggleChip(binding.btnDirDiagUp, dir == GradientDirection.DIAGONAL_UP);

        // 5. Custom Color token swatches
        setSwatchColor(binding.swatchPrimary, draftPalette.getPrimaryColor());
        setSwatchColor(binding.swatchSecondary, draftPalette.getSecondaryColor());
        setSwatchColor(binding.swatchBackground, draftPalette.getBackgroundColor());
        setSwatchColor(binding.swatchSurface, draftPalette.getSurfaceColor());
        setSwatchColor(binding.swatchAccent, draftPalette.getAccentColor());
        setSwatchColor(binding.swatchText, draftPalette.getTextPrimaryColor());
    }

    private void renderPresetChips(ThemeConfig draft, boolean editingDark) {
        binding.studioPresetsContainer.removeAllViews();
        int marginEnd = ThemeApplier.dpToPxInt(this, 8f);
        int padH = ThemeApplier.dpToPxInt(this, 12f);
        int padV = ThemeApplier.dpToPxInt(this, 10f);
        int swatchSize = ThemeApplier.dpToPxInt(this, 20f);

        for (ThemePreset preset : ThemePreset.values()) {
            boolean selected = !draft.isCustom() && draft.getPreset() == preset;
            ThemePalette p = editingDark
                    ? ThemePresetCatalog.getDarkPalette(preset)
                    : ThemePresetCatalog.getLightPalette(preset);

            LinearLayout chip = new LinearLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, marginEnd, 0);
            chip.setLayoutParams(lp);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setPadding(padH, padV, padH, padV);

            chip.setBackground(GradientDrawableFactory.createSurfaceCard(
                    selected ? activeChromePalette.getSurfaceVariantColor() : activeChromePalette.getSurfaceColor(),
                    selected ? activeChromePalette.getPrimaryColor() : activeChromePalette.getBorderColor(),
                    ThemeApplier.dpToPxInt(this, selected ? 2f : 1f),
                    ThemeApplier.dpToPx(this, 14f)
            ));

            View swatch = new View(this);
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(swatchSize, swatchSize);
            swLp.setMargins(0, 0, ThemeApplier.dpToPxInt(this, 8f), 0);
            swatch.setLayoutParams(swLp);
            swatch.setBackground(GradientDrawableFactory.createLinearGradient(p, 999f));

            TextView tv = new TextView(this);
            tv.setText(UiFormatters.formatPresetName(this, preset));
            tv.setTextSize(13f);
            tv.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setTextColor(selected ? activeChromePalette.getPrimaryColor() : activeChromePalette.getTextPrimaryColor());

            chip.addView(swatch);
            chip.addView(tv);
            chip.setOnClickListener(v -> viewModel.selectPreset(preset));

            binding.studioPresetsContainer.addView(chip);
        }
    }

    private void setSwatchColor(View swatch, int color) {
        swatch.setBackground(GradientDrawableFactory.createPill(
                color,
                activeChromePalette.getBorderColor(),
                ThemeApplier.dpToPxInt(this, 1f)
        ));
    }

    private void styleToggleChip(TextView chip, boolean selected) {
        if (selected) {
            chip.setBackground(GradientDrawableFactory.createLinearGradient(activeChromePalette, 999f));
            chip.setTextColor(activeChromePalette.getOnPrimaryColor());
        } else {
            chip.setBackground(GradientDrawableFactory.createPill(
                    activeChromePalette.getSurfaceVariantColor(),
                    activeChromePalette.getBorderColor(),
                    ThemeApplier.dpToPxInt(this, 1f)
            ));
            chip.setTextColor(activeChromePalette.getTextPrimaryColor());
        }
    }

    private void applyOuterChromeWithActiveTheme() {
        ThemeApplier.applySystemBars(this, activeChromePalette, activeChromeIsDark);
        binding.themeStudioRoot.setBackgroundColor(activeChromePalette.getBackgroundColor());
        binding.themeStudioTopBar.setBackgroundColor(activeChromePalette.getBackgroundColor());
        binding.btnStudioBack.setColorFilter(activeChromePalette.getTextPrimaryColor());
        binding.tvStudioTitle.setTextColor(activeChromePalette.getTextPrimaryColor());
        binding.btnStudioCancel.setTextColor(activeChromePalette.getTextSecondaryColor());
        binding.btnStudioApply.setBackground(
                GradientDrawableFactory.createLinearGradient(activeChromePalette, 999f)
        );
        binding.btnStudioApply.setTextColor(activeChromePalette.getOnPrimaryColor());

        int sectionColor = activeChromePalette.getPrimaryColor();
        binding.tvLivePreviewLabel.setTextColor(sectionColor);
        binding.tvStudioPresetsLabel.setTextColor(sectionColor);
        binding.tvStudioGradientLabel.setTextColor(sectionColor);
        binding.tvStudioColorsLabel.setTextColor(sectionColor);

        binding.tvEditingVariantLabel.setTextColor(activeChromePalette.getTextPrimaryColor());
        binding.tvGradientStopsLabel.setTextColor(activeChromePalette.getTextPrimaryColor());
        binding.tvDirectionLabel.setTextColor(activeChromePalette.getTextPrimaryColor());

        float cardRadius = ThemeApplier.dpToPx(this, 18f);
        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        binding.cardStudioGradient.setBackground(GradientDrawableFactory.createSurfaceCard(
                activeChromePalette.getSurfaceColor(), activeChromePalette.getBorderColor(), stroke, cardRadius
        ));
        binding.cardStudioColors.setBackground(GradientDrawableFactory.createSurfaceCard(
                activeChromePalette.getSurfaceColor(), activeChromePalette.getBorderColor(), stroke, cardRadius
        ));

        int btnFill = activeChromePalette.getSurfaceVariantColor();
        float btnRadius = ThemeApplier.dpToPx(this, 12f);
        View[] tokenButtons = {
                binding.btnGradStop1, binding.btnGradStop2, binding.btnGradStop3,
                binding.btnColorPrimary, binding.btnColorSecondary, binding.btnColorBackground,
                binding.btnColorSurface, binding.btnColorAccent, binding.btnColorText
        };
        for (View btn : tokenButtons) {
            btn.setBackground(GradientDrawableFactory.createSurfaceCard(
                    btnFill, activeChromePalette.getBorderColor(), stroke, btnRadius
            ));
        }

        int labelColor = activeChromePalette.getTextPrimaryColor();
        TextView[] labels = {
                binding.tvGrad1Label, binding.tvGrad2Label, binding.tvGrad3Label,
                binding.tvLabelPrimary, binding.tvLabelSecondary, binding.tvLabelBackground,
                binding.tvLabelSurface, binding.tvLabelAccent, binding.tvLabelText
        };
        for (TextView tv : labels) {
            tv.setTextColor(labelColor);
        }
    }

    private void handleExitAttempt() {
        boolean dirty = Boolean.TRUE.equals(viewModel.getHasUnsavedChanges().getValue());
        if (!dirty) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.theme_studio_discard_title)
                .setMessage(R.string.theme_studio_discard_body)
                .setNegativeButton(R.string.theme_studio_continue_action, null)
                .setPositiveButton(R.string.theme_studio_discard_action, (dialog, which) -> finish())
                .show();
    }
}
