package com.chatai.an.presentation.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.chatai.an.R;
import com.chatai.an.ai.integration.termux.TermuxIntegrationManager;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.databinding.ActivityTerminalBinding;
import com.chatai.an.domain.model.IntegrationStatus;
import com.chatai.an.domain.model.TerminalBackend;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.presentation.common.AppViewModelFactory;
import com.chatai.an.presentation.settings.IntegrationsHubActivity;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

public class TerminalActivity extends AppCompatActivity {

    private static final int CONSOLE_SURFACE_COLOR = Color.parseColor("#0D1117");
    private static final int CONSOLE_BORDER_COLOR = Color.parseColor("#30363D");

    private ActivityTerminalBinding binding;
    private TerminalViewModel viewModel;
    private ThemePalette palette;
    private TerminalBackend activeBackend = TerminalBackend.APP_TERMINAL;
    private boolean isRunning = false;
    private final List<TextView> extraKeyViews = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityTerminalBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.terminalRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, Math.max(sys.bottom, ime.bottom));
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        viewModel = new ViewModelProvider(this, new AppViewModelFactory(app.getAppContainer()))
                .get(TerminalViewModel.class);

        setupTopBar();
        setupExtraKeysBar();
        setupInputControls();
        observeData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.refreshBackendStates();
        }
    }

    private void setupTopBar() {
        binding.btnTerminalBack.setOnClickListener(v -> finish());
        binding.btnBackendApp.setOnClickListener(v -> viewModel.selectBackend(TerminalBackend.APP_TERMINAL));
        binding.btnBackendTermux.setOnClickListener(v -> viewModel.selectBackend(TerminalBackend.TERMUX));

        binding.btnTerminalCopy.setOnClickListener(v -> {
            String text = viewModel.getPlainTextSnapshot();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && !text.isEmpty()) {
                clipboard.setPrimaryClip(ClipData.newPlainText("ChatAI Terminal", text));
                Toast.makeText(this, R.string.terminal_copied_msg, Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnTerminalClear.setOnClickListener(v -> {
            viewModel.clearConsole();
            Toast.makeText(this, R.string.terminal_cleared_msg, Toast.LENGTH_SHORT).show();
        });

        binding.btnTerminalOpenHub.setOnClickListener(v ->
                startActivity(new Intent(this, IntegrationsHubActivity.class))
        );
    }

    private void setupExtraKeysBar() {
        extraKeyViews.clear();
        binding.containerExtraKeys.removeAllViews();

        String[][] keys = new String[][]{
                {"CTRL+C", "__CTRL_C__"},
                {"TAB", "__TAB__"},
                {"↑", "__HIST_PREV__"},
                {"↓", "__HIST_NEXT__"},
                {"/", "/"},
                {"|", " | "},
                {"-", "-"},
                {"~", "~"},
                {"&&", " && "},
                {">", " > "},
                {"pwd", "__RUN:pwd__"},
                {"ls -la", "__RUN:ls -la__"}
        };

        int gap = ThemeApplier.dpToPxInt(this, 6f);
        int hPad = ThemeApplier.dpToPxInt(this, 12f);
        int minH = ThemeApplier.dpToPxInt(this, 40f);
        int minW = ThemeApplier.dpToPxInt(this, 44f);

        for (String[] pair : keys) {
            String label = pair[0];
            String action = pair[1];
            TextView keyBtn = new TextView(this);
            keyBtn.setText(label);
            keyBtn.setTextSize(12f);
            keyBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            keyBtn.setGravity(Gravity.CENTER);
            keyBtn.setMinHeight(minH);
            keyBtn.setMinWidth(minW);
            keyBtn.setPadding(hPad, 0, hPad, 0);
            keyBtn.setClickable(true);
            keyBtn.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.rightMargin = gap;
            keyBtn.setLayoutParams(lp);
            keyBtn.setOnClickListener(v -> handleExtraKey(action));
            binding.containerExtraKeys.addView(keyBtn);
            extraKeyViews.add(keyBtn);
        }
    }

    private void handleExtraKey(String action) {
        if ("__CTRL_C__".equals(action)) {
            viewModel.interrupt();
            return;
        }
        if ("__HIST_PREV__".equals(action)) {
            String prev = viewModel.navigateHistory(true);
            if (!prev.isEmpty()) {
                binding.etTerminalInput.setText(prev);
                binding.etTerminalInput.setSelection(prev.length());
            }
            return;
        }
        if ("__HIST_NEXT__".equals(action)) {
            String next = viewModel.navigateHistory(false);
            binding.etTerminalInput.setText(next);
            binding.etTerminalInput.setSelection(next.length());
            return;
        }
        if ("__TAB__".equals(action)) {
            String current = binding.etTerminalInput.getText() != null
                    ? binding.etTerminalInput.getText().toString()
                    : "";
            String completed = viewModel.completeInput(current);
            binding.etTerminalInput.setText(completed);
            binding.etTerminalInput.setSelection(completed.length());
            return;
        }
        if (action.startsWith("__RUN:") && action.endsWith("__")) {
            String cmd = action.substring("__RUN:".length(), action.length() - 2);
            viewModel.executeCommand(cmd);
            return;
        }
        insertIntoInput(action);
    }

    private void insertIntoInput(String textToInsert) {
        if (binding.etTerminalInput.getText() == null) return;
        int start = Math.max(binding.etTerminalInput.getSelectionStart(), 0);
        int end = Math.max(binding.etTerminalInput.getSelectionEnd(), 0);
        binding.etTerminalInput.getText().replace(Math.min(start, end), Math.max(start, end), textToInsert);
        binding.etTerminalInput.requestFocus();
    }

    private void setupInputControls() {
        binding.btnTerminalRun.setOnClickListener(v -> submitInputCommand());
        binding.btnTerminalStop.setOnClickListener(v -> viewModel.interrupt());

        binding.etTerminalInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitInputCommand();
                return true;
            }
            return false;
        });
    }

    private void submitInputCommand() {
        String cmd = binding.etTerminalInput.getText() != null
                ? binding.etTerminalInput.getText().toString()
                : "";
        if (cmd.trim().isEmpty()) return;
        binding.etTerminalInput.setText("");
        viewModel.executeCommand(cmd);
    }

    private void observeData() {
        viewModel.getActiveTheme().observe(this, cfg -> {
            boolean isDark = ThemeManager.isDarkActive(this, cfg);
            palette = ThemeManager.resolvePalette(this, cfg);
            ThemeApplier.applySystemBars(this, palette, isDark);
            applyPalette();
        });

        viewModel.getOutput().observe(this, output -> {
            binding.tvTerminalOutput.setText(output != null ? output : "");
            binding.svTerminalOutput.post(() -> binding.svTerminalOutput.fullScroll(View.FOCUS_DOWN));
        });

        viewModel.isRunning().observe(this, running -> {
            isRunning = Boolean.TRUE.equals(running);
            binding.btnTerminalStop.setVisibility(isRunning ? View.VISIBLE : View.GONE);
            binding.tvTerminalPrompt.setText(isRunning ? "▶" : "$");
            binding.etTerminalInput.setHint(isRunning ? R.string.terminal_stdin_hint : R.string.terminal_input_hint);
            updateStatusBanner();
        });

        viewModel.getWorkdir().observe(this, dir -> {
            binding.tvTerminalSubtitle.setText(dir != null ? dir : "");
            updateStatusBanner();
        });

        viewModel.getActiveBackend().observe(this, backend -> {
            activeBackend = backend != null ? backend : TerminalBackend.APP_TERMINAL;
            styleBackendToggle();
            updateStatusBanner();
        });

        viewModel.getTermuxState().observe(this, s -> updateStatusBanner());
        viewModel.getAppTerminalState().observe(this, s -> updateStatusBanner());
    }

    private void updateStatusBanner() {
        if (isRunning) {
            binding.tvTerminalStatus.setText(R.string.terminal_status_running);
            binding.btnTerminalOpenHub.setVisibility(View.GONE);
            return;
        }
        if (activeBackend == TerminalBackend.TERMUX) {
            TermuxIntegrationManager.TermuxState tState = viewModel.getTermuxState().getValue();
            if (tState != null && (tState.status == IntegrationStatus.ACTIVE || tState.status == IntegrationStatus.CONNECTED)) {
                binding.tvTerminalStatus.setText(R.string.terminal_status_termux_ready);
                binding.btnTerminalOpenHub.setVisibility(View.GONE);
            } else {
                String detail = tState != null ? tState.detail : "Setup required";
                binding.tvTerminalStatus.setText(getString(R.string.terminal_status_termux_setup, detail));
                binding.btnTerminalOpenHub.setVisibility(View.VISIBLE);
            }
        } else {
            String dir = viewModel.getWorkdir().getValue();
            binding.tvTerminalStatus.setText(getString(
                    R.string.terminal_status_app_ready,
                    dir != null ? dir : ""
            ));
            binding.btnTerminalOpenHub.setVisibility(View.GONE);
        }
    }

    private void styleBackendToggle() {
        if (palette == null) return;
        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        binding.layoutBackendSwitcher.setBackground(GradientDrawableFactory.createPill(
                palette.getSurfaceVariantColor(),
                palette.getBorderColor(),
                stroke
        ));
        boolean isApp = activeBackend == TerminalBackend.APP_TERMINAL;
        if (isApp) {
            binding.btnBackendApp.setBackground(GradientDrawableFactory.createLinearGradient(palette, 999f));
            binding.btnBackendApp.setTextColor(palette.getOnPrimaryColor());
            binding.btnBackendTermux.setBackground(null);
            binding.btnBackendTermux.setTextColor(palette.getTextSecondaryColor());
        } else {
            binding.btnBackendTermux.setBackground(GradientDrawableFactory.createLinearGradient(palette, 999f));
            binding.btnBackendTermux.setTextColor(palette.getOnPrimaryColor());
            binding.btnBackendApp.setBackground(null);
            binding.btnBackendApp.setTextColor(palette.getTextSecondaryColor());
        }
    }

    private void applyPalette() {
        if (palette == null) return;
        int stroke = ThemeApplier.dpToPxInt(this, 1f);

        binding.terminalRoot.setBackgroundColor(palette.getBackgroundColor());
        binding.terminalTopBar.setBackgroundColor(palette.getBackgroundColor());
        binding.btnTerminalBack.setColorFilter(palette.getTextPrimaryColor());
        binding.btnTerminalCopy.setColorFilter(palette.getTextPrimaryColor());
        binding.btnTerminalClear.setColorFilter(palette.getTextPrimaryColor());
        binding.tvTerminalTitle.setTextColor(palette.getTextPrimaryColor());
        binding.tvTerminalSubtitle.setTextColor(palette.getTextSecondaryColor());

        binding.terminalStatusBanner.setBackground(GradientDrawableFactory.createSurfaceCard(
                palette.getSurfaceColor(),
                palette.getBorderColor(),
                stroke,
                ThemeApplier.dpToPx(this, 12f)
        ));
        binding.tvTerminalStatus.setTextColor(palette.getTextSecondaryColor());
        binding.btnTerminalOpenHub.setBackground(GradientDrawableFactory.createPill(
                ThemeApplier.blendWithAlpha(palette.getPrimaryColor(), 28),
                palette.getPrimaryColor(),
                stroke
        ));
        binding.btnTerminalOpenHub.setTextColor(palette.getPrimaryColor());

        binding.terminalConsoleContainer.setBackground(GradientDrawableFactory.createSurfaceCard(
                CONSOLE_SURFACE_COLOR,
                CONSOLE_BORDER_COLOR,
                stroke,
                ThemeApplier.dpToPx(this, 14f)
        ));

        for (TextView keyBtn : extraKeyViews) {
            boolean isCtrlC = "CTRL+C".contentEquals(keyBtn.getText());
            int fg = isCtrlC ? ContextCompat.getColor(this, R.color.status_unsupported) : palette.getTextPrimaryColor();
            int bg = isCtrlC
                    ? ThemeApplier.blendWithAlpha(ContextCompat.getColor(this, R.color.status_unsupported), 24)
                    : palette.getSurfaceColor();
            int keyBorder = isCtrlC ? ContextCompat.getColor(this, R.color.status_unsupported) : palette.getBorderColor();
            keyBtn.setBackground(GradientDrawableFactory.createSurfaceCard(bg, keyBorder, stroke, ThemeApplier.dpToPx(this, 10f)));
            keyBtn.setTextColor(fg);
        }

        binding.terminalInputCard.setBackground(GradientDrawableFactory.createSurfaceCard(
                palette.getSurfaceColor(),
                palette.getBorderColor(),
                stroke,
                ThemeApplier.dpToPx(this, 18f)
        ));
        binding.tvTerminalPrompt.setTextColor(palette.getPrimaryColor());
        binding.etTerminalInput.setTextColor(palette.getTextPrimaryColor());
        binding.etTerminalInput.setHintTextColor(palette.getTextSecondaryColor());

        binding.btnTerminalRun.setBackground(GradientDrawableFactory.createLinearGradient(palette, 999f));
        binding.btnTerminalRun.setColorFilter(palette.getOnPrimaryColor());
        binding.btnTerminalStop.setColorFilter(ContextCompat.getColor(this, R.color.status_unsupported));

        styleBackendToggle();
    }
}
