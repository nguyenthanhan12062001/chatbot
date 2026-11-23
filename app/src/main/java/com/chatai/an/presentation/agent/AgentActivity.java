package com.chatai.an.presentation.agent;

import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.chatai.an.R;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.databinding.ActivityAgentBinding;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AgentStep;
import com.chatai.an.domain.model.AgentTask;
import com.chatai.an.domain.model.AgentTaskStatus;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.presentation.common.AppViewModelFactory;
import com.chatai.an.presentation.model.ModelSelectorBottomSheet;
import com.chatai.an.presentation.terminal.TerminalActivity;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class AgentActivity extends AppCompatActivity {

    private ActivityAgentBinding binding;
    private AgentViewModel viewModel;
    private ThemePalette palette;
    private AgentMode currentMode = AgentMode.CHAT;

    private final ActivityResultLauncher<Uri> safTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    ChatAiApplication app = (ChatAiApplication) getApplication();
                    String safPath = app.getAppContainer().getWorkspaceFileManager().registerSafTreeUri(uri);
                    viewModel.setWorkspacePath(safPath);
                    Toast.makeText(this, "Workspace set to SAF folder: " + safPath, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityAgentBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.agentRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, Math.max(sys.bottom, ime.bottom));
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        viewModel = new ViewModelProvider(this, new AppViewModelFactory(app.getAppContainer()))
                .get(AgentViewModel.class);

        binding.btnAgentBack.setOnClickListener(v -> finish());
        binding.btnAgentTerminal.setOnClickListener(v ->
                startActivity(new Intent(this, TerminalActivity.class))
        );
        binding.btnAgentModel.setOnClickListener(v -> {
            ModelSelectorBottomSheet sheet = new ModelSelectorBottomSheet();
            sheet.show(getSupportFragmentManager(), "agent_model_selector");
        });

        binding.btnModeChat.setOnClickListener(v -> viewModel.setAgentMode(AgentMode.CHAT));
        binding.btnModeCoding.setOnClickListener(v -> viewModel.setAgentMode(AgentMode.CODING));
        binding.btnAgentWorkspace.setOnClickListener(v -> showWorkspaceDialog());

        binding.btnAgentRun.setOnClickListener(v -> {
            String goal = binding.etAgentGoal.getText() != null ? binding.etAgentGoal.getText().toString().trim() : "";
            if (!goal.isEmpty()) {
                viewModel.runGoal(goal);
            }
        });
        binding.btnAgentStop.setOnClickListener(v -> viewModel.stopCurrentTask());

        bindToolsSummary();
        observeData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.refreshWorkspacePath();
        bindToolsSummary();
    }

    private void bindToolsSummary() {
        StringBuilder sb = new StringBuilder(getString(R.string.agent_tools_available))
                .append(" (").append(currentMode.getTitle()).append("): ");
        List<ToolDefinition> tools = viewModel.getAvailableTools();
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(tools.get(i).getName());
        }
        binding.tvAgentToolsSummary.setText(sb.toString());
    }

    private void observeData() {
        viewModel.getActiveTheme().observe(this, cfg -> {
            boolean isDark = ThemeManager.isDarkActive(this, cfg);
            palette = ThemeManager.resolvePalette(this, cfg);
            ThemeApplier.applySystemBars(this, palette, isDark);
            applyPalette();
        });

        viewModel.getSelectedModel().observe(this, model -> {
            if (model != null) {
                binding.btnAgentModel.setText(model.getName());
            }
        });

        viewModel.getAgentMode().observe(this, mode -> {
            currentMode = mode != null ? mode : AgentMode.CHAT;
            bindToolsSummary();
            styleModePills();
        });

        viewModel.getActiveWorkspacePath().observe(this, path -> {
            String clean = (path != null && !path.isEmpty()) ? path : "default";
            binding.btnAgentWorkspace.setText(getString(R.string.agent_workspace_label, clean));
        });

        viewModel.getLiveToolExecutions().observe(this, execs -> {
            if (execs == null || execs.isEmpty()) {
                binding.tvAgentLiveTool.setVisibility(View.GONE);
                return;
            }
            ToolExecutor.ActiveToolExecution latest = execs.get(0);
            if (latest.status == ToolExecutor.ToolExecStatus.RUNNING
                    || latest.status == ToolExecutor.ToolExecStatus.WAITING_CONFIRMATION
                    || latest.status == ToolExecutor.ToolExecStatus.REQUESTED) {
                binding.tvAgentLiveTool.setVisibility(View.VISIBLE);
                binding.tvAgentLiveTool.setText("Tool: " + latest.toolName + " • " + latest.status.name() + " (" + latest.detail + ")");
            } else {
                binding.tvAgentLiveTool.setVisibility(View.VISIBLE);
                binding.tvAgentLiveTool.setText("Last tool: " + latest.toolName + " • " + latest.status.name());
            }
        });

        viewModel.getCurrentTask().observe(this, this::renderActiveTask);

        viewModel.getPendingConfirmation().observe(this, pc -> {
            if (pc == null) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.agent_confirm_tool_title)
                    .setMessage("Tool: " + pc.toolName + "\nArgs: " + pc.argsPreview)
                    .setCancelable(false)
                    .setNegativeButton(R.string.agent_confirm_deny, (d, w) -> viewModel.respondToConfirmation(false))
                    .setPositiveButton(R.string.agent_confirm_allow, (d, w) -> viewModel.respondToConfirmation(true))
                    .show();
        });

        viewModel.getTaskHistory().observe(this, this::renderHistory);
    }

    private void showWorkspaceDialog() {
        List<WorkspaceFileManager.GrantedFolder> safFolders = viewModel.getGrantedSafFolders();
        List<String> optionsList = new ArrayList<>();
        List<Runnable> actionsList = new ArrayList<>();

        String defaultWs = viewModel.getDefaultWorkspacePath();
        optionsList.add("Default App Workspace\n(" + defaultWs + ")");
        actionsList.add(() -> viewModel.setWorkspacePath(defaultWs));

        for (WorkspaceFileManager.GrantedFolder f : safFolders) {
            optionsList.add("SAF Folder: " + f.alias + "\nsaf://" + f.alias);
            actionsList.add(() -> viewModel.setWorkspacePath("saf://" + f.alias));
        }

        optionsList.add("📁 Grant New SAF Folder...");
        actionsList.add(() -> safTreeLauncher.launch(null));

        optionsList.add("✏️ Enter Custom Path Manually...");
        actionsList.add(() -> showCustomPathInputDialog());

        String[] choices = optionsList.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Select Coding Agent Workspace")
                .setItems(choices, (dialog, which) -> {
                    if (which >= 0 && which < actionsList.size()) {
                        actionsList.get(which).run();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showCustomPathInputDialog() {
        EditText input = new EditText(this);
        String current = viewModel.getActiveWorkspacePath().getValue();
        input.setText(current != null ? current : "");
        input.setHint("/storage/emulated/0/Download/MyProject");
        int pad = ThemeApplier.dpToPxInt(this, 16f);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Enter Custom Workspace Path")
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String path = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!path.isEmpty()) {
                        viewModel.setWorkspacePath(path);
                        Toast.makeText(this, "Workspace updated", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void renderActiveTask(AgentTask task) {
        if (task == null) {
            binding.cardActiveTask.setVisibility(View.GONE);
            binding.btnAgentStop.setVisibility(View.GONE);
            return;
        }
        binding.cardActiveTask.setVisibility(View.VISIBLE);
        boolean running = task.getStatus() == AgentTaskStatus.ANALYZING
                || task.getStatus() == AgentTaskStatus.PLANNING
                || task.getStatus() == AgentTaskStatus.EXECUTING_TOOL
                || task.getStatus() == AgentTaskStatus.WAITING_CONFIRMATION
                || task.getStatus() == AgentTaskStatus.SYNTHESIZING;
        binding.btnAgentStop.setVisibility(running ? View.VISIBLE : View.GONE);
        binding.tvActiveTaskStatus.setText("Status: " + task.getStatus().name() + " (" + currentMode.getTitle() + ")");

        binding.containerActiveSteps.removeAllViews();
        for (AgentStep step : task.getSteps()) {
            TextView tvStep = new TextView(this);
            tvStep.setText(step.getStepNumber() + ". " + step.getTitle() + "\n   " + step.getDetail());
            tvStep.setTextSize(12f);
            tvStep.setPadding(0, ThemeApplier.dpToPxInt(this, 4f), 0, ThemeApplier.dpToPxInt(this, 4f));
            if (palette != null) tvStep.setTextColor(palette.getTextSecondaryColor());
            binding.containerActiveSteps.addView(tvStep);
        }

        if (!task.getFinalResult().isEmpty()) {
            binding.tvActiveTaskResult.setVisibility(View.VISIBLE);
            binding.tvActiveTaskResult.setText(task.getFinalResult());
        } else if (!task.getErrorMessage().isEmpty()) {
            binding.tvActiveTaskResult.setVisibility(View.VISIBLE);
            binding.tvActiveTaskResult.setText("Error: " + task.getErrorMessage());
        } else {
            binding.tvActiveTaskResult.setVisibility(View.GONE);
        }
    }

    private void renderHistory(List<AgentTask> tasks) {
        binding.containerTaskHistory.removeAllViews();
        if (tasks == null || tasks.isEmpty()) return;

        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        float radius = ThemeApplier.dpToPx(this, 14f);
        for (AgentTask t : tasks) {
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
                card.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));
            }

            TextView tvGoal = new TextView(this);
            tvGoal.setText(t.getGoal());
            tvGoal.setTextSize(14f);
            tvGoal.setTypeface(null, android.graphics.Typeface.BOLD);
            if (palette != null) tvGoal.setTextColor(palette.getTextPrimaryColor());
            card.addView(tvGoal);

            TextView tvMeta = new TextView(this);
            String preview = !t.getFinalResult().isEmpty() ? t.getFinalResult() : t.getErrorMessage();
            tvMeta.setText(t.getStatus().name() + " • " + t.getSteps().size() + " steps"
                    + (!preview.isEmpty() ? "\n" + preview : ""));
            tvMeta.setTextSize(12f);
            tvMeta.setMaxLines(4);
            if (palette != null) tvMeta.setTextColor(palette.getTextSecondaryColor());
            card.addView(tvMeta);

            TextView btnDel = new TextView(this);
            btnDel.setText(R.string.action_delete);
            btnDel.setTextSize(11f);
            btnDel.setPadding(0, ThemeApplier.dpToPxInt(this, 6f), 0, 0);
            if (palette != null) btnDel.setTextColor(palette.getPrimaryColor());
            btnDel.setOnClickListener(v -> viewModel.deleteTask(t.getId()));
            card.addView(btnDel);

            binding.containerTaskHistory.addView(card);
        }
    }

    private void styleModePills() {
        if (palette == null) return;
        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        boolean isChat = currentMode == AgentMode.CHAT;
        binding.btnModeChat.setBackground(isChat
                ? GradientDrawableFactory.createLinearGradient(palette, 999f)
                : GradientDrawableFactory.createPill(palette.getSurfaceColor(), palette.getBorderColor(), stroke));
        binding.btnModeChat.setTextColor(isChat ? palette.getOnPrimaryColor() : palette.getTextPrimaryColor());

        binding.btnModeCoding.setBackground(!isChat
                ? GradientDrawableFactory.createLinearGradient(palette, 999f)
                : GradientDrawableFactory.createPill(palette.getSurfaceColor(), palette.getBorderColor(), stroke));
        binding.btnModeCoding.setTextColor(!isChat ? palette.getOnPrimaryColor() : palette.getTextPrimaryColor());
    }

    private void applyPalette() {
        if (palette == null) return;
        binding.agentRoot.setBackgroundColor(palette.getBackgroundColor());
        binding.agentTopBar.setBackgroundColor(palette.getBackgroundColor());
        binding.btnAgentBack.setColorFilter(palette.getTextPrimaryColor());
        binding.btnAgentTerminal.setColorFilter(palette.getPrimaryColor());
        binding.tvAgentTitle.setTextColor(palette.getTextPrimaryColor());
        binding.tvAgentSubtitle.setTextColor(palette.getTextSecondaryColor());
        binding.tvAgentToolsSummary.setTextColor(palette.getTextSecondaryColor());
        binding.tvAgentHistoryHeader.setTextColor(palette.getPrimaryColor());

        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        float radius = ThemeApplier.dpToPx(this, 16f);
        binding.btnAgentModel.setBackground(GradientDrawableFactory.createPill(palette.getSurfaceColor(), palette.getBorderColor(), stroke));
        binding.btnAgentModel.setTextColor(palette.getTextPrimaryColor());

        binding.btnAgentWorkspace.setBackground(GradientDrawableFactory.createPill(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke));
        binding.btnAgentWorkspace.setTextColor(palette.getPrimaryColor());

        binding.cardAgentInput.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));
        binding.etAgentGoal.setTextColor(palette.getTextPrimaryColor());
        binding.etAgentGoal.setHintTextColor(palette.getTextSecondaryColor());

        binding.btnAgentRun.setBackground(GradientDrawableFactory.createLinearGradient(palette, ThemeApplier.dpToPx(this, 12f)));
        binding.btnAgentRun.setTextColor(palette.getOnPrimaryColor());

        binding.btnAgentStop.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceVariantColor(), palette.getBorderColor(), stroke, ThemeApplier.dpToPx(this, 12f)));
        binding.btnAgentStop.setTextColor(palette.getTextPrimaryColor());

        binding.cardActiveTask.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));
        binding.tvActiveTaskStatus.setTextColor(palette.getPrimaryColor());
        binding.tvAgentLiveTool.setBackground(GradientDrawableFactory.createPill(
                ThemeApplier.blendWithAlpha(palette.getPrimaryColor(), 26), palette.getPrimaryColor(), stroke
        ));
        binding.tvAgentLiveTool.setTextColor(palette.getPrimaryColor());
        binding.tvActiveTaskResult.setTextColor(palette.getTextPrimaryColor());

        styleModePills();
    }
}
