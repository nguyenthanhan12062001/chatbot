package com.chatai.an.presentation.provider;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;

import com.chatai.an.R;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.core.AttachmentStorageHelper;
import com.chatai.an.databinding.ActivityProviderSettingsBinding;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.ApiKeyEntry;
import com.chatai.an.domain.model.DeviceCompatibility;
import com.chatai.an.domain.model.FreeProviderStatus;
import com.chatai.an.domain.model.LocalModelInfo;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProviderConfig;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.presentation.common.AppViewModelFactory;
import com.chatai.an.presentation.common.UiFormatters;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProviderSettingsActivity extends AppCompatActivity {

    private ActivityProviderSettingsBinding binding;
    private ProviderSettingsViewModel viewModel;
    private ThemePalette palette;
    private List<ProviderConfig> allConfigs = new ArrayList<>();
    private String selectedProviderId = ModelProvider.GOOGLE_GEMINI.getId();
    private LiveData<List<ApiKeyEntry>> currentKeysLiveData;

    private final ActivityResultLauncher<String[]> pickLocalModelLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                    viewModel.importLocalModel(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityProviderSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.providerRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, Math.max(sys.bottom, ime.bottom));
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        viewModel = new ViewModelProvider(this, new AppViewModelFactory(app.getAppContainer()))
                .get(ProviderSettingsViewModel.class);

        binding.btnProviderBack.setOnClickListener(v -> finish());
        binding.btnRefreshFreeCatalogs.setOnClickListener(v -> viewModel.refreshFreeCatalogs());
        binding.rowSelectProvider.setOnClickListener(v -> showProviderPicker());

        binding.btnSaveProvider.setOnClickListener(v -> viewModel.updateProviderConfig(
                selectedProviderId,
                binding.etProviderBaseUrl.getText() != null ? binding.etProviderBaseUrl.getText().toString() : "",
                binding.switchProviderEnabled.isChecked(),
                binding.switchProviderRotation.isChecked(),
                binding.etProviderFallbackModel.getText() != null ? binding.etProviderFallbackModel.getText().toString() : ""
        ));

        binding.btnTestProvider.setOnClickListener(v -> {
            viewModel.updateProviderConfig(
                    selectedProviderId,
                    binding.etProviderBaseUrl.getText() != null ? binding.etProviderBaseUrl.getText().toString() : "",
                    binding.switchProviderEnabled.isChecked(),
                    binding.switchProviderRotation.isChecked(),
                    binding.etProviderFallbackModel.getText() != null ? binding.etProviderFallbackModel.getText().toString() : ""
            );
            viewModel.testProviderConnection(selectedProviderId);
        });

        binding.btnAddApiKey.setOnClickListener(v -> showAddKeyDialog());
        binding.btnImportLocalModel.setOnClickListener(v -> pickLocalModelLauncher.launch(new String[]{"*/*"}));

        bindDeviceCompat();
        observeData();
    }

    private void bindDeviceCompat() {
        DeviceCompatibility dc = viewModel.inspectDeviceCompatibility();
        binding.tvDeviceCompatSummary.setText(
                "Available RAM: " + dc.getAvailableRamMb() + " MB / " + dc.getTotalRamMb()
                        + " MB | CPU Cores: " + dc.getCpuCores()
                        + " | ABI: " + dc.getPrimaryAbi()
                        + (dc.isArm64Supported() ? " (arm64 ready)" : " (non-arm64)")
        );
    }

    private void observeData() {
        viewModel.getActiveTheme().observe(this, cfg -> {
            boolean isDark = ThemeManager.isDarkActive(this, cfg);
            palette = ThemeManager.resolvePalette(this, cfg);
            ThemeApplier.applySystemBars(this, palette, isDark);
            applyPalette();
        });

        viewModel.isBusy().observe(this, busy ->
                binding.pbProviderBusy.setVisibility(Boolean.TRUE.equals(busy) ? View.VISIBLE : View.GONE)
        );

        viewModel.getStatusMessage().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                viewModel.clearStatusMessage();
            }
        });

        viewModel.getFreeProviderStatuses().observe(this, this::renderFreeStatuses);

        viewModel.getProviderConfigs().observe(this, configs -> {
            allConfigs = configs != null ? configs : new ArrayList<>();
            bindSelectedProviderConfig();
        });

        viewModel.getSelectedProviderId().observe(this, id -> {
            if (id != null) {
                selectedProviderId = id;
                bindSelectedProviderConfig();
                observeKeys(id);
            }
        });

        viewModel.getLocalModels().observe(this, this::renderLocalModels);
    }

    private void observeKeys(String providerId) {
        if (currentKeysLiveData != null) {
            currentKeysLiveData.removeObservers(this);
        }
        currentKeysLiveData = viewModel.observeKeysForProvider(providerId);
        currentKeysLiveData.observe(this, this::renderApiKeys);
    }

    private void renderFreeStatuses(Map<ModelProvider, FreeProviderStatus> map) {
        if (map == null) return;
        FreeProviderStatus poll = map.get(ModelProvider.POLLINATIONS);
        FreeProviderStatus kilo = map.get(ModelProvider.KILO);
        FreeProviderStatus orFree = map.get(ModelProvider.OPENROUTER_FREE);
        if (poll != null) {
            binding.tvPollinationsStatus.setText("Pollinations (Free): " + poll.getState().name()
                    + " • " + poll.getModelCount() + " models"
                    + (!poll.getStatusMessage().isEmpty() ? " (" + poll.getStatusMessage() + ")" : ""));
        }
        if (kilo != null) {
            binding.tvKiloStatus.setText("Kilo (Free): " + kilo.getState().name()
                    + " • " + kilo.getModelCount() + " models"
                    + (!kilo.getStatusMessage().isEmpty() ? " (" + kilo.getStatusMessage() + ")" : ""));
        }
        if (orFree != null) {
            binding.tvOpenrouterFreeStatus.setText("OpenRouter (Free $0): " + orFree.getState().name()
                    + " • " + orFree.getModelCount() + " models"
                    + (!orFree.getStatusMessage().isEmpty() ? " (" + orFree.getStatusMessage() + ")" : ""));
        }
    }

    private void bindSelectedProviderConfig() {
        ProviderConfig found = null;
        for (ProviderConfig c : allConfigs) {
            if (c.getProviderId().equals(selectedProviderId)) {
                found = c;
                break;
            }
        }
        ModelProvider mp = ModelProvider.fromId(selectedProviderId);
        binding.tvSelectedProviderName.setText(found != null ? found.getDisplayName() : mp.getDisplayName());
        if (found != null) {
            binding.etProviderBaseUrl.setText(found.getBaseUrl());
            binding.etProviderFallbackModel.setText(found.getFallbackModelId());
            binding.switchProviderEnabled.setChecked(found.isEnabled());
            binding.switchProviderRotation.setChecked(found.isKeyRotationEnabled());
            if (!found.getLastConnectionStatus().isEmpty()) {
                binding.tvProviderLastTest.setVisibility(View.VISIBLE);
                binding.tvProviderLastTest.setText("Last test: " + found.getLastConnectionStatus());
            } else {
                binding.tvProviderLastTest.setVisibility(View.GONE);
            }
        }
    }

    private void showProviderPicker() {
        List<ModelProvider> configurableProviders = new ArrayList<>();
        configurableProviders.add(ModelProvider.OPENROUTER_FREE);
        for (ModelProvider mp : ModelProvider.values()) {
            if (mp.getDefaultAccessType() == AccessType.PERSONAL_API) {
                configurableProviders.add(mp);
            }
        }
        String[] labels = new String[configurableProviders.size()];
        int checked = 0;
        for (int i = 0; i < configurableProviders.size(); i++) {
            ModelProvider mp = configurableProviders.get(i);
            labels[i] = mp == ModelProvider.OPENROUTER_FREE
                    ? mp.getDisplayName() + " (Free Key Pool)"
                    : mp.getDisplayName();
            if (mp.getId().equals(selectedProviderId)) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.provider_section_personal)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    viewModel.selectProvider(configurableProviders.get(which).getId());
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showAddKeyDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = ThemeApplier.dpToPxInt(this, 20f);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText etLabel = new EditText(this);
        etLabel.setHint(R.string.provider_key_label_hint);
        container.addView(etLabel);

        EditText etKey = new EditText(this);
        etKey.setHint(R.string.provider_key_value_hint);
        container.addView(etKey);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.provider_add_key)
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String label = etLabel.getText() != null ? etLabel.getText().toString() : "";
                    String rawKey = etKey.getText() != null ? etKey.getText().toString() : "";
                    viewModel.addApiKey(selectedProviderId, label, rawKey);
                })
                .show();
    }

    private void renderApiKeys(List<ApiKeyEntry> keys) {
        binding.containerApiKeys.removeAllViews();
        boolean empty = keys == null || keys.isEmpty();
        binding.tvNoKeysHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) return;

        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        float radius = ThemeApplier.dpToPx(this, 12f);

        for (ApiKeyEntry entry : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            int pad = ThemeApplier.dpToPxInt(this, 12f);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = ThemeApplier.dpToPxInt(this, 8f);
            row.setLayoutParams(lp);
            if (palette != null) {
                row.setBackground(GradientDrawableFactory.createSurfaceCard(
                        palette.getSurfaceVariantColor(),
                        entry.isActive() ? palette.getPrimaryColor() : palette.getBorderColor(),
                        stroke,
                        radius
                ));
            }

            TextView tvTitle = new TextView(this);
            String title = entry.getLabel() + "  (" + entry.getMaskedPreview() + ")"
                    + (entry.isActive() ? "  [ACTIVE]" : "")
                    + (!entry.isEnabled() ? "  [DISABLED]" : "")
                    + (entry.isInCooldown() ? "  [COOLDOWN]" : "");
            tvTitle.setText(title);
            tvTitle.setTextSize(13f);
            if (palette != null) tvTitle.setTextColor(palette.getTextPrimaryColor());
            row.addView(tvTitle);

            if (!entry.getLastError().isEmpty()) {
                TextView tvErr = new TextView(this);
                tvErr.setText("Last error: " + entry.getLastError());
                tvErr.setTextSize(11f);
                tvErr.setTextColor(ContextCompat.getColor(this, R.color.status_unsupported));
                row.addView(tvErr);
            }

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, ThemeApplier.dpToPxInt(this, 6f), 0, 0);

            TextView btnUse = makeActionLink("Set Active", v -> viewModel.setActiveKey(selectedProviderId, entry.getId()));
            TextView btnToggle = makeActionLink(entry.isEnabled() ? "Disable" : "Enable",
                    v -> viewModel.setKeyEnabled(entry.getId(), !entry.isEnabled()));
            TextView btnDelete = makeActionLink("Delete", v -> viewModel.deleteKey(entry.getId()));

            actions.addView(btnUse);
            actions.addView(btnToggle);
            actions.addView(btnDelete);
            row.addView(actions);

            binding.containerApiKeys.addView(row);
        }
    }

    private void renderLocalModels(List<LocalModelInfo> models) {
        binding.containerLocalModels.removeAllViews();
        boolean empty = models == null || models.isEmpty();
        binding.tvLocalNoModels.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) return;

        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        float radius = ThemeApplier.dpToPx(this, 12f);

        for (LocalModelInfo info : models) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = ThemeApplier.dpToPxInt(this, 12f);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = ThemeApplier.dpToPxInt(this, 8f);
            card.setLayoutParams(lp);
            if (palette != null) {
                card.setBackground(GradientDrawableFactory.createSurfaceCard(
                        palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, radius
                ));
            }

            TextView tvName = new TextView(this);
            tvName.setText(info.getDisplayName() + " [" + info.getFormat() + "]");
            tvName.setTextSize(14f);
            if (palette != null) tvName.setTextColor(palette.getTextPrimaryColor());
            card.addView(tvName);

            TextView tvMeta = new TextView(this);
            tvMeta.setText("Size: " + AttachmentStorageHelper.formatFileSize(info.getFileSizeBytes())
                    + " • Est RAM: ~" + info.getEstimatedRamMb() + " MB"
                    + " • Status: " + info.getRuntimeStatus().name());
            tvMeta.setTextSize(11f);
            if (palette != null) tvMeta.setTextColor(palette.getTextSecondaryColor());
            card.addView(tvMeta);

            TextView btnRemove = makeActionLink("Remove", v -> viewModel.deleteLocalModel(info.getId()));
            card.addView(btnRemove);
            binding.containerLocalModels.addView(card);
        }
    }

    private TextView makeActionLink(String label, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12f);
        tv.setPadding(0, ThemeApplier.dpToPxInt(this, 6f), ThemeApplier.dpToPxInt(this, 16f), ThemeApplier.dpToPxInt(this, 6f));
        if (palette != null) tv.setTextColor(palette.getPrimaryColor());
        tv.setOnClickListener(click);
        return tv;
    }

    private void applyPalette() {
        if (palette == null) return;
        binding.providerRoot.setBackgroundColor(palette.getBackgroundColor());
        binding.providerTopBar.setBackgroundColor(palette.getBackgroundColor());
        binding.btnProviderBack.setColorFilter(palette.getTextPrimaryColor());
        binding.tvProviderTitle.setTextColor(palette.getTextPrimaryColor());

        int sectionColor = palette.getPrimaryColor();
        binding.tvSectionFree.setTextColor(sectionColor);
        binding.tvSectionPersonal.setTextColor(sectionColor);
        binding.tvSectionLocal.setTextColor(sectionColor);

        float radius = ThemeApplier.dpToPx(this, 18f);
        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        binding.cardFreeProviders.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));
        binding.cardPersonalProvider.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));
        binding.cardLocalAi.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));

        binding.tvPollinationsStatus.setTextColor(palette.getTextPrimaryColor());
        binding.tvKiloStatus.setTextColor(palette.getTextPrimaryColor());
        binding.tvOpenrouterFreeStatus.setTextColor(palette.getTextPrimaryColor());
        binding.btnRefreshFreeCatalogs.setBackground(GradientDrawableFactory.createLinearGradient(palette, ThemeApplier.dpToPx(this, 12f)));
        binding.btnRefreshFreeCatalogs.setTextColor(palette.getOnPrimaryColor());

        binding.rowSelectProvider.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, ThemeApplier.dpToPx(this, 12f)));
        binding.tvSelectedProviderName.setTextColor(palette.getTextPrimaryColor());
        binding.ivProviderChevron.setColorFilter(palette.getTextSecondaryColor());

        binding.tvLabelBaseUrl.setTextColor(palette.getTextSecondaryColor());
        binding.tvLabelFallbackModel.setTextColor(palette.getTextSecondaryColor());
        binding.etProviderBaseUrl.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, ThemeApplier.dpToPx(this, 10f)));
        binding.etProviderBaseUrl.setTextColor(palette.getTextPrimaryColor());
        binding.etProviderFallbackModel.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, ThemeApplier.dpToPx(this, 10f)));
        binding.etProviderFallbackModel.setTextColor(palette.getTextPrimaryColor());
        binding.etProviderFallbackModel.setHintTextColor(palette.getTextSecondaryColor());

        binding.tvProviderEnabledLabel.setTextColor(palette.getTextPrimaryColor());
        binding.tvProviderRotationLabel.setTextColor(palette.getTextPrimaryColor());
        binding.tvProviderLastTest.setTextColor(palette.getTextSecondaryColor());
        binding.tvKeyPoolHeader.setTextColor(palette.getTextPrimaryColor());
        binding.tvNoKeysHint.setTextColor(palette.getTextSecondaryColor());

        binding.btnSaveProvider.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, ThemeApplier.dpToPx(this, 12f)));
        binding.btnSaveProvider.setTextColor(palette.getTextPrimaryColor());
        binding.btnTestProvider.setBackground(GradientDrawableFactory.createLinearGradient(palette, ThemeApplier.dpToPx(this, 12f)));
        binding.btnTestProvider.setTextColor(palette.getOnPrimaryColor());
        binding.btnAddApiKey.setBackground(GradientDrawableFactory.createPill(ThemeApplier.blendWithAlpha(sectionColor, 28), sectionColor, stroke));
        binding.btnAddApiKey.setTextColor(sectionColor);

        binding.tvLocalRuntimeStatus.setTextColor(ContextCompat.getColor(this, R.color.status_unsupported));
        binding.tvDeviceCompatSummary.setTextColor(palette.getTextPrimaryColor());
        binding.btnImportLocalModel.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, ThemeApplier.dpToPx(this, 12f)));
        binding.btnImportLocalModel.setTextColor(palette.getTextPrimaryColor());
        binding.tvLocalNoModels.setTextColor(palette.getTextSecondaryColor());
    }
}
