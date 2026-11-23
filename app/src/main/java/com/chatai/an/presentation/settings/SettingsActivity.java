package com.chatai.an.presentation.settings;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.chatai.an.BuildConfig;
import com.chatai.an.R;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.databinding.ActivitySettingsBinding;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AppLanguage;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemeMode;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ThemePreset;
import com.chatai.an.presentation.agent.AgentActivity;
import com.chatai.an.presentation.common.AppViewModelFactory;
import com.chatai.an.presentation.common.UiFormatters;
import com.chatai.an.presentation.memory.MemoryManagementActivity;
import com.chatai.an.presentation.model.ModelSelectorBottomSheet;
import com.chatai.an.presentation.provider.ProviderSettingsActivity;
import com.chatai.an.presentation.themestudio.ThemeStudioActivity;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SettingsViewModel viewModel;
    private ThemeConfig currentThemeConfig;
    private AppLanguage currentLanguage = AppLanguage.ENGLISH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        viewModel = new ViewModelProvider(this, new AppViewModelFactory(app.getAppContainer()))
                .get(SettingsViewModel.class);

        binding.btnSettingsBack.setOnClickListener(v -> finish());
        binding.tvVersionValue.setText(BuildConfig.VERSION_NAME);

        binding.rowProviders.setOnClickListener(v ->
                startActivity(new Intent(this, ProviderSettingsActivity.class))
        );
        binding.rowMemory.setOnClickListener(v ->
                startActivity(new Intent(this, MemoryManagementActivity.class))
        );
        binding.rowAgent.setOnClickListener(v ->
                startActivity(new Intent(this, AgentActivity.class))
        );
        binding.rowIntegrationsHub.setOnClickListener(v ->
                startActivity(new Intent(this, IntegrationsHubActivity.class))
        );

        binding.rowThemeMode.setOnClickListener(v -> showThemeModeDialog());
        binding.rowThemePreset.setOnClickListener(v -> showThemePresetDialog());
        binding.rowThemeStudio.setOnClickListener(v ->
                startActivity(new Intent(this, ThemeStudioActivity.class))
        );

        binding.rowActiveModel.setOnClickListener(v -> {
            ModelSelectorBottomSheet sheet = new ModelSelectorBottomSheet();
            sheet.show(getSupportFragmentManager(), "model_selector_settings");
        });

        binding.switchAutoTitle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                viewModel.setAutoTitle(isChecked);
            }
        });
        binding.switchConfirmDelete.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                viewModel.setConfirmBeforeDelete(isChecked);
            }
        });

        binding.rowClearHistory.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dialog_clear_history_title)
                        .setMessage(R.string.dialog_clear_history_body)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_confirm_delete, (d, w) -> {
                            viewModel.clearAllHistory();
                            Toast.makeText(this, R.string.settings_cleared_msg, Toast.LENGTH_SHORT).show();
                        })
                        .show()
        );

        binding.rowLanguage.setOnClickListener(v -> showLanguageDialog());
        binding.rowAbout.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.settings_about)
                        .setMessage(R.string.about_dialog_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
        );

        observeData();
    }

    private void observeData() {
        viewModel.getActiveTheme().observe(this, config -> {
            currentThemeConfig = config;
            boolean isDark = ThemeManager.isDarkActive(this, config);
            ThemePalette palette = ThemeManager.resolvePalette(this, config);

            binding.tvThemeModeValue.setText(UiFormatters.formatThemeMode(this, config.getMode()));
            String presetLabel = config.isCustom()
                    ? getString(R.string.preset_custom)
                    : UiFormatters.formatPresetName(this, config.getPreset());
            binding.tvThemePresetValue.setText(presetLabel);

            applyPalette(palette, isDark);
        });

        viewModel.getSelectedModel().observe(this, this::bindModelInfo);

        viewModel.getSettings().observe(this, settings -> {
            currentLanguage = settings.getLanguage();
            binding.tvLanguageValue.setText(currentLanguage.getNativeName());
            binding.switchAutoTitle.setChecked(settings.isAutoTitleFromFirstMessage());
            binding.switchConfirmDelete.setChecked(settings.isConfirmBeforeDelete());
        });
    }

    private void bindModelInfo(AiModel model) {
        if (model == null) return;
        binding.tvActiveModelValue.setText(model.getName() + " • " + model.getProvider().getDisplayName());
        String summary = getString(R.string.cap_text) + ": " + UiFormatters.formatCapabilityStatus(this, model.getCapabilityStatus(ModelCapability.TEXT))
                + "  •  " + getString(R.string.cap_image) + ": " + UiFormatters.formatCapabilityStatus(this, model.getCapabilityStatus(ModelCapability.IMAGE))
                + "  •  " + getString(R.string.cap_file) + ": " + UiFormatters.formatCapabilityStatus(this, model.getCapabilityStatus(ModelCapability.FILE))
                + "\n" + getString(R.string.cap_thinking) + ": " + UiFormatters.formatCapabilityStatus(this, model.getCapabilityStatus(ModelCapability.DEEP_THINKING))
                + "  •  " + getString(R.string.cap_streaming) + ": " + UiFormatters.formatCapabilityStatus(this, model.getCapabilityStatus(ModelCapability.STREAMING));
        binding.tvModelCapsSummary.setText(summary);
    }

    private void showThemeModeDialog() {
        ThemeMode[] modes = ThemeMode.values();
        String[] labels = new String[modes.length];
        int checked = 0;
        ThemeMode currentMode = currentThemeConfig != null ? currentThemeConfig.getMode() : ThemeMode.SYSTEM;
        for (int i = 0; i < modes.length; i++) {
            labels[i] = UiFormatters.formatThemeMode(this, modes[i]);
            if (modes[i] == currentMode) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_theme_mode)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    viewModel.setThemeMode(modes[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showThemePresetDialog() {
        ThemePreset[] presets = ThemePreset.values();
        String[] labels = new String[presets.length];
        int checked = 0;
        ThemePreset currentPreset = currentThemeConfig != null ? currentThemeConfig.getPreset() : ThemePreset.SUNSET_ORANGE;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = UiFormatters.formatPresetName(this, presets[i]);
            if (presets[i] == currentPreset) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_theme_preset)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    viewModel.setThemePreset(presets[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showLanguageDialog() {
        AppLanguage[] languages = AppLanguage.values();
        String[] labels = new String[languages.length];
        int checked = 0;
        for (int i = 0; i < languages.length; i++) {
            labels[i] = languages[i].getNativeName();
            if (languages[i] == currentLanguage) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    viewModel.setLanguage(languages[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void applyPalette(ThemePalette palette, boolean isDark) {
        ThemeApplier.applySystemBars(this, palette, isDark);
        binding.settingsRoot.setBackgroundColor(palette.getBackgroundColor());
        binding.settingsTopBar.setBackgroundColor(palette.getBackgroundColor());
        binding.btnSettingsBack.setColorFilter(palette.getTextPrimaryColor());
        binding.tvSettingsTitle.setTextColor(palette.getTextPrimaryColor());

        int sectionColor = palette.getPrimaryColor();
        binding.tvGroupAiPlatform.setTextColor(sectionColor);
        binding.tvGroupAppearance.setTextColor(sectionColor);
        binding.tvGroupModels.setTextColor(sectionColor);
        binding.tvGroupConversations.setTextColor(sectionColor);
        binding.tvGroupGeneral.setTextColor(sectionColor);

        float cardRadius = ThemeApplier.dpToPx(this, 18f);
        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        binding.cardAiPlatform.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, cardRadius));
        binding.cardAppearance.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, cardRadius));
        binding.cardModels.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, cardRadius));
        binding.cardConversations.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, cardRadius));
        binding.cardGeneral.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, cardRadius));

        int border = palette.getBorderColor();
        binding.divPlatform1.setBackgroundColor(border);
        binding.divPlatform2.setBackgroundColor(border);
        binding.divPlatform3.setBackgroundColor(border);
        binding.divAppearance1.setBackgroundColor(border);
        binding.divAppearance2.setBackgroundColor(border);
        binding.divModels1.setBackgroundColor(border);
        binding.divConv1.setBackgroundColor(border);
        binding.divConv2.setBackgroundColor(border);
        binding.divGeneral1.setBackgroundColor(border);
        binding.divGeneral2.setBackgroundColor(border);

        int primaryText = palette.getTextPrimaryColor();
        int secondaryText = palette.getTextSecondaryColor();

        binding.tvProvidersLabel.setTextColor(primaryText);
        binding.tvProvidersDesc.setTextColor(secondaryText);
        binding.tvMemoryLabel.setTextColor(primaryText);
        binding.tvMemoryDesc.setTextColor(secondaryText);
        binding.tvAgentLabel.setTextColor(primaryText);
        binding.tvAgentDesc.setTextColor(secondaryText);
        binding.tvHubLabel.setTextColor(primaryText);
        binding.tvHubDesc.setTextColor(secondaryText);
        binding.ivProvidersIcon.setColorFilter(sectionColor);
        binding.ivMemoryIcon.setColorFilter(palette.getSecondaryColor());
        binding.ivAgentIcon.setColorFilter(sectionColor);
        binding.ivHubIcon.setColorFilter(palette.getSecondaryColor());
        binding.ivProvidersArrow.setColorFilter(secondaryText);
        binding.ivMemoryArrow.setColorFilter(secondaryText);
        binding.ivAgentArrow.setColorFilter(secondaryText);
        binding.ivHubArrow.setColorFilter(secondaryText);

        binding.tvThemeModeLabel.setTextColor(primaryText);
        binding.tvThemeModeValue.setTextColor(secondaryText);
        binding.tvThemePresetLabel.setTextColor(primaryText);
        binding.tvThemePresetValue.setTextColor(secondaryText);
        binding.tvThemeStudioLabel.setTextColor(primaryText);
        binding.tvThemeStudioDesc.setTextColor(secondaryText);

        binding.tvActiveModelLabel.setTextColor(primaryText);
        binding.tvActiveModelValue.setTextColor(secondaryText);
        binding.tvModelCapsLabel.setTextColor(primaryText);
        binding.tvModelCapsSummary.setTextColor(secondaryText);

        binding.tvAutoTitleLabel.setTextColor(primaryText);
        binding.tvAutoTitleDesc.setTextColor(secondaryText);
        binding.tvConfirmDeleteLabel.setTextColor(primaryText);
        binding.tvConfirmDeleteDesc.setTextColor(secondaryText);
        binding.tvClearHistoryDesc.setTextColor(secondaryText);

        binding.tvLanguageLabel.setTextColor(primaryText);
        binding.tvLanguageValue.setTextColor(secondaryText);
        binding.tvAboutLabel.setTextColor(primaryText);
        binding.tvAboutDesc.setTextColor(secondaryText);
        binding.tvVersionLabel.setTextColor(primaryText);
        binding.tvVersionValue.setTextColor(secondaryText);

        binding.ivThemeModeArrow.setColorFilter(secondaryText);
        binding.ivThemePresetArrow.setColorFilter(secondaryText);
        binding.ivThemeStudioArrow.setColorFilter(secondaryText);
        binding.ivActiveModelArrow.setColorFilter(secondaryText);
        binding.ivLanguageArrow.setColorFilter(secondaryText);
        binding.ivAboutArrow.setColorFilter(palette.getPrimaryColor());

        binding.viewPresetSwatch.setBackground(
                GradientDrawableFactory.createLinearGradient(palette, ThemeApplier.dpToPx(this, 10f))
        );
        binding.themeStudioIconBadge.setBackground(
                GradientDrawableFactory.createLinearGradient(palette, ThemeApplier.dpToPx(this, 10f))
        );
        binding.ivThemeStudioIcon.setColorFilter(palette.getOnPrimaryColor());
    }
}
