package com.chatai.an.presentation.settings;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import com.chatai.an.R;
import com.chatai.an.ai.integration.download.DownloadToolManager;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.integration.net.InternetAccessManager;
import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.ai.integration.termux.TermuxIntegrationManager;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.app.AppContainer;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.databinding.ActivityIntegrationsHubBinding;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.IntegrationStatus;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.util.List;

public class IntegrationsHubActivity extends AppCompatActivity {

    private ActivityIntegrationsHubBinding binding;
    private AppContainer appContainer;
    private ThemePalette palette;

    private final ActivityResultLauncher<Uri> safTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    WorkspaceFileManager wfm = appContainer.getWorkspaceFileManager();
                    String safPath = wfm.registerSafTreeUri(uri);
                    Toast.makeText(this, "SAF Folder granted: " + safPath, Toast.LENGTH_SHORT).show();
                    refreshAllStates();
                }
            });

    private final ActivityResultLauncher<String> termuxPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Toast.makeText(this, granted ? "Termux permission granted" : "Termux permission denied", Toast.LENGTH_SHORT).show();
                refreshAllStates();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityIntegrationsHubBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.hubRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        appContainer = app.getAppContainer();

        binding.btnHubBack.setOnClickListener(v -> finish());

        setupCategoryChips();
        setupTermuxActions();
        setupShizukuActions();
        setupFileActions();
        setupNetAndDownloads();
        setupToolMatrix();

        appContainer.getThemeRepository().observeActiveTheme().observe(this, cfg -> {
            boolean isDark = ThemeManager.isDarkActive(this, cfg);
            palette = ThemeManager.resolvePalette(this, cfg);
            ThemeApplier.applySystemBars(this, palette, isDark);
            applyPalette();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAllStates();
    }

    private void refreshAllStates() {
        AppExecutors.getInstance().diskIO().execute(() -> {
            appContainer.getTermuxIntegrationManager().refreshState();
            appContainer.getShizukuIntegrationManager().refreshState();
            appContainer.getInternetAccessManager().refreshStatus();
            appContainer.getWorkspaceFileManager().refreshGrantedFolders();
            AppExecutors.getInstance().postToMain(this::bindDynamicStates);
        });
    }

    private void bindDynamicStates() {
        // Termux State
        TermuxIntegrationManager.TermuxState tState = appContainer.getTermuxIntegrationManager().inspectState();
        binding.tvTermuxStatusDetail.setText("Status: " + tState.status.name() + " • " + tState.detail);

        // Shizuku State
        ShizukuIntegrationManager.ShizukuState sState = appContainer.getShizukuIntegrationManager().inspectState();
        binding.tvShizukuStatusDetail.setText("Status: " + sState.status.name() + " • " + sState.detail + " • UID: " + sState.serviceUid + " (" + sState.privilegeLevel.getLabel() + ")");
        binding.switchShizukuFallback.setChecked(appContainer.getSettingsRepository().getSettingsSnapshot().isAllowShizukuFileFallback());

        // File Workspace Path & SAF Folders
        WorkspaceFileManager wfm = appContainer.getWorkspaceFileManager();
        binding.tvFilesWorkspacePath.setText("Active Workspace: " + wfm.getActiveWorkspacePath());
        renderSafFolders(wfm.refreshGrantedFolders());

        // Internet & Downloads
        InternetAccessManager.NetworkHealth net = appContainer.getInternetAccessManager().inspectSystemNetwork();
        binding.tvNetStatusDetail.setText("Network: " + net.transportLabel + " • " + net.detail);
        binding.tvDownloadsDir.setText("Directory: " + appContainer.getDownloadToolManager().getDefaultDownloadDir().getAbsolutePath());
        renderDownloadRecords(appContainer.getDownloadToolManager().getDownloadsSnapshot());
    }

    private void setupCategoryChips() {
        binding.chipHubAll.setOnClickListener(v -> filterCards("all"));
        binding.chipHubShell.setOnClickListener(v -> filterCards("shell"));
        binding.chipHubFiles.setOnClickListener(v -> filterCards("files"));
        binding.chipHubNet.setOnClickListener(v -> filterCards("net"));
        binding.chipHubTools.setOnClickListener(v -> filterCards("tools"));
    }

    private void filterCards(String cat) {
        boolean all = "all".equals(cat);
        binding.cardHubTermux.setVisibility(all || "shell".equals(cat) ? View.VISIBLE : View.GONE);
        binding.cardHubShizuku.setVisibility(all || "shell".equals(cat) ? View.VISIBLE : View.GONE);
        binding.cardHubFiles.setVisibility(all || "files".equals(cat) ? View.VISIBLE : View.GONE);
        binding.cardHubNet.setVisibility(all || "net".equals(cat) ? View.VISIBLE : View.GONE);
        binding.cardHubTools.setVisibility(all || "tools".equals(cat) ? View.VISIBLE : View.GONE);
    }

    private void setupTermuxActions() {
        binding.btnTermuxPerm.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, TermuxIntegrationManager.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Termux RUN_COMMAND permission already granted.", Toast.LENGTH_SHORT).show();
            } else {
                termuxPermLauncher.launch(TermuxIntegrationManager.PERMISSION_RUN_COMMAND);
            }
        });

        binding.btnTermuxTest.setOnClickListener(v -> AppExecutors.getInstance().worker().execute(() -> {
            TermuxIntegrationManager.TermuxState st = appContainer.getTermuxIntegrationManager().testConnectionSync();
            AppExecutors.getInstance().postToMain(() -> {
                Toast.makeText(this, "Termux Test: " + st.detail, Toast.LENGTH_LONG).show();
                refreshAllStates();
            });
        }));

        binding.btnTermuxCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Termux Props", "allow-external-apps = true"));
                Toast.makeText(this, "Copied 'allow-external-apps = true' to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupShizukuActions() {
        binding.btnShizukuPerm.setOnClickListener(v -> {
            boolean requested = appContainer.getShizukuIntegrationManager().requestPermission();
            Toast.makeText(this, requested ? "Requested Shizuku permission. Check Shizuku app prompt." : "Shizuku not running or unavailable.", Toast.LENGTH_SHORT).show();
            refreshAllStates();
        });

        binding.btnShizukuBind.setOnClickListener(v -> AppExecutors.getInstance().worker().execute(() -> {
            ShizukuIntegrationManager.ShizukuState st = appContainer.getShizukuIntegrationManager().bindAndVerifySync();
            AppExecutors.getInstance().postToMain(() -> {
                Toast.makeText(this, "Shizuku Bind: " + st.detail, Toast.LENGTH_LONG).show();
                refreshAllStates();
            });
        }));

        binding.btnShizukuTest.setOnClickListener(v -> {
            AppExecutors.getInstance().worker().execute(() -> {
                try {
                    ShizukuIntegrationManager.ShellResult res = appContainer.getShizukuIntegrationManager().executeShell("id && uname -a", null, 15);
                    AppExecutors.getInstance().postToMain(() -> {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("ADB / Shizuku Shell Probe")
                                .setMessage("Exit code: " + res.exitCode + "\nUID: " + res.uid + "\n\nOutput:\n" + res.stdout + (!res.stderr.isEmpty() ? "\nStderr:\n" + res.stderr : ""))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    });
                } catch (Exception e) {
                    AppExecutors.getInstance().postToMain(() -> {
                        Toast.makeText(this, "Shizuku test failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        binding.switchShizukuFallback.setOnCheckedChangeListener((btn, checked) -> {
            if (btn.isPressed()) {
                appContainer.getSettingsRepository().setAllowShizukuFileFallback(checked);
            }
        });
    }

    private void setupFileActions() {
        binding.btnFilesGrantSaf.setOnClickListener(v -> safTreeLauncher.launch(null));

        binding.btnFilesSetWorkspace.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setText(appContainer.getWorkspaceFileManager().getActiveWorkspacePath());
            int pad = ThemeApplier.dpToPxInt(this, 16f);
            input.setPadding(pad, pad / 2, pad, pad / 2);

            new MaterialAlertDialogBuilder(this)
                    .setTitle("Set Active Workspace Path")
                    .setView(input)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_save, (d, w) -> {
                        String path = input.getText() != null ? input.getText().toString().trim() : "";
                        if (!path.isEmpty()) {
                            appContainer.getWorkspaceFileManager();
                            appContainer.getSettingsRepository().setActiveWorkspacePath(path);
                            refreshAllStates();
                            Toast.makeText(this, "Workspace path updated", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        });
    }

    private void renderSafFolders(List<WorkspaceFileManager.GrantedFolder> folders) {
        binding.containerSafFolders.removeAllViews();
        if (folders == null || folders.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("No SAF folders granted yet. Tap Grant SAF Folder to allow file access across shared storage.");
            tv.setTextSize(12f);
            if (palette != null) tv.setTextColor(palette.getTextSecondaryColor());
            binding.containerSafFolders.addView(tv);
            return;
        }

        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        for (WorkspaceFileManager.GrantedFolder folder : folders) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = ThemeApplier.dpToPxInt(this, 8f);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = ThemeApplier.dpToPxInt(this, 6f);
            row.setLayoutParams(lp);
            if (palette != null) {
                row.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, ThemeApplier.dpToPx(this, 8f)));
            }

            TextView tvInfo = new TextView(this);
            tvInfo.setText("Alias: " + folder.alias + "\n" + (folder.valid ? "Active & Permitted" : "Permission Expired"));
            tvInfo.setTextSize(12f);
            tvInfo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (palette != null) tvInfo.setTextColor(palette.getTextPrimaryColor());
            row.addView(tvInfo);

            TextView btnRevoke = new TextView(this);
            btnRevoke.setText("Revoke");
            btnRevoke.setTextSize(12f);
            btnRevoke.setPadding(pad, pad, pad, pad);
            if (palette != null) btnRevoke.setTextColor(ContextCompat.getColor(this, R.color.status_unsupported));
            btnRevoke.setOnClickListener(v -> {
                appContainer.getWorkspaceFileManager().revokeSafFolder(folder.alias);
                refreshAllStates();
            });
            row.addView(btnRevoke);

            binding.containerSafFolders.addView(row);
        }
    }

    private void setupNetAndDownloads() {
        binding.btnNetProbe.setOnClickListener(v -> AppExecutors.getInstance().worker().execute(() -> {
            InternetAccessManager.NetworkHealth h = appContainer.getInternetAccessManager().probeConnectivitySync();
            AppExecutors.getInstance().postToMain(() -> {
                Toast.makeText(this, "Network Probe: " + h.detail, Toast.LENGTH_LONG).show();
                refreshAllStates();
            });
        }));

        binding.btnDownloadsTest.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setText("https://github.com/Sanuu7/AndroidHarness/archive/refs/tags/v4.0-release.zip");
            int pad = ThemeApplier.dpToPxInt(this, 16f);
            input.setPadding(pad, pad / 2, pad, pad / 2);

            new MaterialAlertDialogBuilder(this)
                    .setTitle("Test Download URL")
                    .setView(input)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton("Download", (d, w) -> {
                        String url = input.getText() != null ? input.getText().toString().trim() : "";
                        if (!url.isEmpty()) {
                            AppExecutors.getInstance().worker().execute(() -> {
                                try {
                                    DownloadToolManager.DownloadRecord rec = appContainer.getDownloadToolManager().downloadSync(
                                            url, null, null, null, true
                                    );
                                    AppExecutors.getInstance().postToMain(() -> {
                                        Toast.makeText(this, "Download finished: " + rec.fileName + " (" + rec.status.name() + ")", Toast.LENGTH_LONG).show();
                                        refreshAllStates();
                                    });
                                } catch (Exception e) {
                                    AppExecutors.getInstance().postToMain(() ->
                                            Toast.makeText(this, "Download error: " + e.getMessage(), Toast.LENGTH_LONG).show()
                                    );
                                }
                            });
                        }
                    })
                    .show();
        });
    }

    private void renderDownloadRecords(List<DownloadToolManager.DownloadRecord> records) {
        binding.containerDownloadRecords.removeAllViews();
        if (records == null || records.isEmpty()) return;

        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        for (DownloadToolManager.DownloadRecord rec : records) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = ThemeApplier.dpToPxInt(this, 10f);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = ThemeApplier.dpToPxInt(this, 6f);
            card.setLayoutParams(lp);
            if (palette != null) {
                card.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, ThemeApplier.dpToPx(this, 8f)));
            }

            TextView tv = new TextView(this);
            tv.setText(rec.fileName + " • " + rec.status.name() + " (" + rec.getProgressPercent() + "%)\n" + (rec.errorMessage.isEmpty() ? rec.destinationPath : rec.errorMessage));
            tv.setTextSize(12f);
            if (palette != null) tv.setTextColor(palette.getTextPrimaryColor());
            card.addView(tv);

            if (rec.status == DownloadToolManager.DownloadStatus.DOWNLOADING) {
                TextView btnCancel = new TextView(this);
                btnCancel.setText("Cancel");
                btnCancel.setTextSize(11f);
                if (palette != null) btnCancel.setTextColor(ContextCompat.getColor(this, R.color.status_unsupported));
                btnCancel.setOnClickListener(v -> {
                    appContainer.getDownloadToolManager().cancelDownload(rec.id);
                    refreshAllStates();
                });
                card.addView(btnCancel);
            }

            binding.containerDownloadRecords.addView(card);
        }
    }

    private void setupToolMatrix() {
        binding.containerToolSwitches.removeAllViews();
        ToolRegistry registry = appContainer.getToolRegistry();
        AppSettings settings = appContainer.getSettingsRepository().getSettingsSnapshot();
        List<ToolDefinition> defs = registry.listDefinitions();

        for (ToolDefinition def : defs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = ThemeApplier.dpToPxInt(this, 8f);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = ThemeApplier.dpToPxInt(this, 4f);
            row.setLayoutParams(lp);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvName = new TextView(this);
            tvName.setText(def.getName() + " [" + def.getCategory().getLabel() + "]");
            tvName.setTextSize(13f);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            if (palette != null) tvName.setTextColor(palette.getTextPrimaryColor());
            info.addView(tvName);

            TextView tvDesc = new TextView(this);
            tvDesc.setText(def.getDescription());
            tvDesc.setTextSize(11f);
            if (palette != null) tvDesc.setTextColor(palette.getTextSecondaryColor());
            info.addView(tvDesc);

            row.addView(info);

            MaterialSwitch sw = new MaterialSwitch(this);
            sw.setChecked(settings.isToolEnabled(def.getName()));
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    appContainer.getSettingsRepository().setToolEnabled(def.getName(), isChecked);
                }
            });
            row.addView(sw);

            binding.containerToolSwitches.addView(row);
        }
    }

    private void applyPalette() {
        if (palette == null) return;
        binding.hubRoot.setBackgroundColor(palette.getBackgroundColor());
        binding.hubTopBar.setBackgroundColor(palette.getBackgroundColor());
        binding.btnHubBack.setColorFilter(palette.getTextPrimaryColor());
        binding.tvHubTitle.setTextColor(palette.getTextPrimaryColor());

        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        float cardRadius = ThemeApplier.dpToPx(this, 16f);
        int surface = palette.getSurfaceColor();
        int border = palette.getBorderColor();

        binding.cardHubTermux.setBackground(GradientDrawableFactory.createSurfaceCard(surface, border, stroke, cardRadius));
        binding.cardHubShizuku.setBackground(GradientDrawableFactory.createSurfaceCard(surface, border, stroke, cardRadius));
        binding.cardHubFiles.setBackground(GradientDrawableFactory.createSurfaceCard(surface, border, stroke, cardRadius));
        binding.cardHubNet.setBackground(GradientDrawableFactory.createSurfaceCard(surface, border, stroke, cardRadius));
        binding.cardHubTools.setBackground(GradientDrawableFactory.createSurfaceCard(surface, border, stroke, cardRadius));

        TextView[] titles = {
                binding.tvTermuxCardTitle, binding.tvShizukuCardTitle,
                binding.tvFilesCardTitle, binding.tvNetCardTitle, binding.tvToolsCardTitle
        };
        for (TextView tv : titles) {
            tv.setTextColor(palette.getTextPrimaryColor());
        }

        TextView[] details = {
                binding.tvTermuxStatusDetail, binding.tvShizukuStatusDetail,
                binding.tvFilesWorkspacePath, binding.tvNetStatusDetail, binding.tvDownloadsDir,
                binding.tvToolsSubtitle, binding.tvShizukuFallbackLabel
        };
        for (TextView tv : details) {
            if (tv != null) tv.setTextColor(palette.getTextSecondaryColor());
        }

        TextView[] buttons = {
                binding.btnTermuxPerm, binding.btnTermuxTest, binding.btnTermuxCopy,
                binding.btnShizukuPerm, binding.btnShizukuBind, binding.btnShizukuTest,
                binding.btnFilesGrantSaf, binding.btnFilesSetWorkspace,
                binding.btnNetProbe, binding.btnDownloadsTest
        };
        for (TextView btn : buttons) {
            if (btn != null) {
                btn.setBackground(GradientDrawableFactory.createPill(palette.getSurfaceVariantColor(), border, stroke));
                btn.setTextColor(palette.getPrimaryColor());
            }
        }

        TextView[] chips = {
                binding.chipHubAll, binding.chipHubShell, binding.chipHubFiles,
                binding.chipHubNet, binding.chipHubTools
        };
        for (TextView chip : chips) {
            if (chip != null) {
                chip.setBackground(GradientDrawableFactory.createPill(palette.getSurfaceVariantColor(), border, stroke));
                chip.setTextColor(palette.getTextPrimaryColor());
            }
        }
    }
}
