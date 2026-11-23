package com.chatai.an.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatai.an.R;
import com.chatai.an.ai.core.ChatSessionCoordinator;
import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.core.AppLogger;
import com.chatai.an.core.AttachmentStorageHelper;
import com.chatai.an.databinding.ActivityMainBinding;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.AttachmentType;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.IntegrationStatus;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ThinkingState;
import com.chatai.an.presentation.agent.AgentActivity;
import com.chatai.an.presentation.attachment.AttachmentPickerBottomSheet;
import com.chatai.an.presentation.bubble.ChatBubbleService;
import com.chatai.an.presentation.chat.MainChatViewModel;
import com.chatai.an.presentation.chat.MessageAdapter;
import com.chatai.an.presentation.chat.PendingAttachmentAdapter;
import com.chatai.an.presentation.common.AppViewModelFactory;
import com.chatai.an.presentation.common.AttachmentPreviewDialogHelper;
import com.chatai.an.presentation.common.UiFormatters;
import com.chatai.an.presentation.memory.MemoryManagementActivity;
import com.chatai.an.presentation.model.ModelSelectorBottomSheet;
import com.chatai.an.presentation.provider.ProviderSettingsActivity;
import com.chatai.an.presentation.search.ConversationSearchActivity;
import com.chatai.an.presentation.settings.IntegrationsHubActivity;
import com.chatai.an.presentation.settings.SettingsActivity;
import com.chatai.an.presentation.sidebar.SidebarHistoryAdapter;
import com.chatai.an.presentation.terminal.TerminalActivity;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    public static final String EXTRA_OPEN_CONVERSATION_ID = "extra_open_conversation_id";

    private ActivityMainBinding binding;
    private MainChatViewModel viewModel;
    private AppContainer appContainer;

    private MessageAdapter messageAdapter;
    private PendingAttachmentAdapter pendingAttachmentAdapter;
    private SidebarHistoryAdapter sidebarAdapter;

    private ThemePalette activePalette;
    private boolean activeIsDark = false;
    private AiModel currentModel;
    private AppSettings currentSettings;
    private ThinkingState currentThinkingState = ThinkingState.DISABLED;
    private AgentMode currentAgentMode = AgentMode.CHAT;
    private ChatSessionCoordinator.ContextMetrics currentContextMetrics = ChatSessionCoordinator.ContextMetrics.empty();
    private ShizukuIntegrationManager.ShizukuState currentShizukuState;
    private boolean userScrolledUp = false;

    private File pendingCameraFile;
    private boolean pendingStartBubbleAfterOverlayPerm = false;

    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (pendingStartBubbleAfterOverlayPerm) {
                    pendingStartBubbleAfterOverlayPerm = false;
                    if (ChatBubbleService.canDrawOverlays(this)) {
                        launchChatBubble();
                    }
                }
                updateBubbleButtonTint();
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (ChatBubbleService.canDrawOverlays(this)) {
                    startBubbleServiceNow();
                }
            });

    private final ActivityResultLauncher<Intent> searchLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String convId = result.getData().getStringExtra(ConversationSearchActivity.EXTRA_CONVERSATION_ID);
                    if (convId != null && !convId.isEmpty()) {
                        viewModel.openConversation(convId);
                        binding.drawerLayout.closeDrawer(GravityCompat.START);
                    }
                }
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchCameraCapture();
                } else {
                    Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && pendingCameraFile != null && pendingCameraFile.exists()) {
                    Attachment att = AttachmentStorageHelper.createAttachmentFromCameraFile(this, pendingCameraFile);
                    viewModel.addPendingAttachments(Collections.singletonList(att));
                }
                pendingCameraFile = null;
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> pickPhotosLauncher =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(5), uris -> {
                if (uris == null || uris.isEmpty()) return;
                importPickedUris(uris, AttachmentType.IMAGE);
            });

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                for (Uri uri : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                }
                importPickedUris(uris, AttachmentType.FILE);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ChatAiApplication app = (ChatAiApplication) getApplication();
        appContainer = app.getAppContainer();
        viewModel = new ViewModelProvider(this, new AppViewModelFactory(appContainer))
                .get(MainChatViewModel.class);

        setupInsets();
        setupRecyclerViews();
        setupInteractions();
        observeState();
        handleIntentConversation(getIntent());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentConversation(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBubbleButtonTint();
        appContainer.getShizukuIntegrationManager().refreshState();
        viewModel.refreshContextMetrics();
    }

    private void handleIntentConversation(Intent intent) {
        if (intent == null) return;
        String convId = intent.getStringExtra(EXTRA_OPEN_CONVERSATION_ID);
        if (convId != null && !convId.isEmpty() && viewModel != null) {
            viewModel.openConversation(convId);
        }
    }

    private void setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomPad = Math.max(sysBars.bottom, ime.bottom);
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, bottomPad);
            return insets;
        });

        ViewCompat.setOnApplyWindowInsetsListener(binding.sidebarContainer, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sysBars.left, sysBars.top, 0, sysBars.bottom);
            return insets;
        });
    }

    private void setupRecyclerViews() {
        messageAdapter = new MessageAdapter(appContainer.getModelRepository(), new MessageAdapter.OnMessageActionListener() {
            @Override
            public void onCopyMessage(Message message) {
                copyToClipboard(message.getContent());
            }

            @Override
            public void onEditMessage(Message message) {
                showEditMessageDialog(message);
            }

            @Override
            public void onDeleteMessage(Message message) {
                handleDeleteMessage(message);
            }

            @Override
            public void onCopyCodeBlock(String code) {
                copyToClipboard(code);
            }

            @Override
            public void onPreviewAttachment(Attachment attachment) {
                AttachmentPreviewDialogHelper.show(MainActivity.this, attachment);
            }

            @Override
            public void onRegenerateMessage(Message message) {
                viewModel.regenerateMessage(message);
            }

            @Override
            public void onHandoverToCode(Message message) {
                viewModel.handoverToCode(null);
            }

            @Override
            public void onFollowUpClicked(String suggestion) {
                viewModel.sendOrQueueMessage(suggestion);
            }

            @Override
            public void onApproveToolCall(String executionId, boolean allowForSession) {
                viewModel.respondToToolConfirmation(executionId, true, allowForSession);
            }

            @Override
            public void onDenyToolCall(String executionId) {
                viewModel.respondToToolConfirmation(executionId, false, false);
            }

            @Override
            public void onCopyToolOutput(String text) {
                copyToClipboard(text);
            }

            @Override
            public void onPreviewImage(String title, String imageSource) {
                showImageSourcePreviewDialog(title, imageSource);
            }

            @Override
            public void onOpenSourceUrl(String urlOrPath) {
                handleSourceNavigation(urlOrPath);
            }
        });
        LinearLayoutManager msgLayoutManager = new LinearLayoutManager(this);
        msgLayoutManager.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(msgLayoutManager);
        binding.rvMessages.setAdapter(messageAdapter);
        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                boolean canScrollDown = recyclerView.canScrollVertically(1);
                userScrolledUp = canScrollDown;
                binding.btnScrollToBottom.setVisibility(
                        canScrollDown && messageAdapter.getItemCount() > 0 ? View.VISIBLE : View.GONE
                );
            }
        });

        pendingAttachmentAdapter = new PendingAttachmentAdapter(new PendingAttachmentAdapter.OnAttachmentInteractionListener() {
            @Override
            public void onPreview(Attachment attachment) {
                AttachmentPreviewDialogHelper.show(MainActivity.this, attachment);
            }

            @Override
            public void onRemove(Attachment attachment) {
                viewModel.removePendingAttachment(attachment.getId());
            }
        });
        binding.rvPendingAttachments.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        );
        binding.rvPendingAttachments.setAdapter(pendingAttachmentAdapter);

        sidebarAdapter = new SidebarHistoryAdapter(new SidebarHistoryAdapter.OnConversationActionListener() {
            @Override
            public void onOpenConversation(Conversation conversation) {
                viewModel.openConversation(conversation.getId());
                binding.drawerLayout.closeDrawer(GravityCompat.START);
            }

            @Override
            public void onRenameConversation(Conversation conversation) {
                showRenameConversationDialog(conversation);
            }

            @Override
            public void onDeleteConversation(Conversation conversation) {
                handleDeleteConversation(conversation);
            }
        });
        binding.rvSidebarHistory.setLayoutManager(new LinearLayoutManager(this));
        binding.rvSidebarHistory.setAdapter(sidebarAdapter);
    }

    private void setupInteractions() {
        binding.btnMenu.setOnClickListener(v -> binding.drawerLayout.openDrawer(GravityCompat.START));
        binding.btnCloseSidebar.setOnClickListener(v -> binding.drawerLayout.closeDrawer(GravityCompat.START));

        binding.btnChatBubble.setOnClickListener(v -> toggleChatBubble());
        binding.btnTopNewChat.setOnClickListener(v -> viewModel.startNewChat());
        binding.btnSidebarNewChat.setOnClickListener(v -> {
            viewModel.startNewChat();
            binding.drawerLayout.closeDrawer(GravityCompat.START);
        });

        binding.btnSidebarSearch.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            searchLauncher.launch(new Intent(this, ConversationSearchActivity.class));
        });

        binding.btnSidebarAgent.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, AgentActivity.class));
        });

        binding.btnSidebarMemory.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, MemoryManagementActivity.class));
        });

        binding.btnSidebarProviders.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, ProviderSettingsActivity.class));
        });

        binding.btnSidebarTerminal.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, TerminalActivity.class));
        });

        binding.btnSidebarHub.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, IntegrationsHubActivity.class));
        });

        binding.btnSidebarSettings.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, SettingsActivity.class));
        });

        binding.btnModelSelector.setOnClickListener(v -> {
            ModelSelectorBottomSheet sheet = new ModelSelectorBottomSheet();
            sheet.setOnModelChosenListener(model -> viewModel.selectModel(model.getId()));
            sheet.show(getSupportFragmentManager(), "model_selector_main");
        });

        binding.btnModeChat.setOnClickListener(v -> viewModel.setAgentMode(AgentMode.CHAT));
        binding.btnModeCode.setOnClickListener(v -> viewModel.setAgentMode(AgentMode.CODING));
        binding.btnWorkspaceChip.setOnClickListener(v -> showProjectTagDialog());
        binding.btnShizukuPill.setOnClickListener(v -> handleShizukuQuickTap());
        binding.btnContextMeter.setOnClickListener(v -> showContextInspectorDialog());

        binding.btnScrollToBottom.setOnClickListener(v -> {
            userScrolledUp = false;
            int count = messageAdapter.getItemCount();
            if (count > 0) {
                binding.rvMessages.smoothScrollToPosition(count - 1);
            }
            binding.btnScrollToBottom.setVisibility(View.GONE);
        });

        binding.btnQueueClear.setOnClickListener(v -> viewModel.clearFollowUpQueue());

        binding.btnAttach.setOnClickListener(v -> {
            AttachmentPickerBottomSheet sheet = new AttachmentPickerBottomSheet();
            sheet.setOnAttachmentActionListener(new AttachmentPickerBottomSheet.OnAttachmentActionListener() {
                @Override
                public void onPickCamera() {
                    requestCameraAndCapture();
                }

                @Override
                public void onPickPhotos() {
                    pickPhotosLauncher.launch(
                            new PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                    .build()
                    );
                }

                @Override
                public void onPickFiles() {
                    openDocumentLauncher.launch(new String[]{"*/*"});
                }
            });
            sheet.show(getSupportFragmentManager(), "attachment_picker");
        });

        binding.btnAgentMode.setOnClickListener(v -> viewModel.toggleAgentMode());

        binding.btnDeepThinking.setOnClickListener(v -> {
            if (currentThinkingState == ThinkingState.UNSUPPORTED && currentModel != null) {
                CapabilityStatus status = currentModel.getCapabilityStatus(ModelCapability.DEEP_THINKING);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.deep_thinking_unsupported_title)
                        .setMessage(getString(
                                R.string.deep_thinking_unsupported_msg,
                                currentModel.getName(),
                                UiFormatters.formatCapabilityStatus(this, status)
                        ))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } else {
                viewModel.toggleDeepThinking();
            }
        });

        binding.btnProjectTag.setOnClickListener(v -> showProjectTagDialog());

        binding.btnStop.setOnClickListener(v -> viewModel.stopGeneration());

        binding.btnSend.setOnClickListener(v -> {
            String text = binding.etMessageInput.getText() != null
                    ? binding.etMessageInput.getText().toString()
                    : "";
            if (handleUiOnlySlashCommand(text.trim())) {
                binding.etMessageInput.setText("");
                return;
            }
            viewModel.sendOrQueueMessage(text);
            binding.etMessageInput.setText("");
        });

        binding.etMessageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSendButtonState();
                updateSlashCommandPopup(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        binding.cardSuggestion1.setOnClickListener(v -> populateSuggestion(R.string.suggestion_1_prompt));
        binding.cardSuggestion2.setOnClickListener(v -> populateSuggestion(R.string.suggestion_2_prompt));
        binding.cardSuggestion3.setOnClickListener(v -> populateSuggestion(R.string.suggestion_3_prompt));
    }

    private void populateSuggestion(int promptResId) {
        String prompt = getString(promptResId);
        binding.etMessageInput.setText(prompt);
        binding.etMessageInput.setSelection(prompt.length());
        binding.etMessageInput.requestFocus();
    }

    private boolean handleUiOnlySlashCommand(String trimmed) {
        if ("/context".equalsIgnoreCase(trimmed)) {
            showContextInspectorDialog();
            return true;
        }
        if ("/shizuku".equalsIgnoreCase(trimmed)) {
            handleShizukuQuickTap();
            return true;
        }
        if ("/review".equalsIgnoreCase(trimmed)) {
            viewModel.setAgentMode(AgentMode.CODING);
            viewModel.sendOrQueueMessage("Explore the active workspace files, check for bugs or improvements, and provide a concise review.");
            return true;
        }
        return false;
    }

    private void updateSlashCommandPopup(String rawInput) {
        String trimmed = rawInput != null ? rawInput.trim() : "";
        if (!trimmed.startsWith("/") || trimmed.contains("\n") || trimmed.contains(" ")) {
            binding.llSlashCommandPopup.setVisibility(View.GONE);
            binding.llSlashCommandPopup.removeAllViews();
            return;
        }
        String query = trimmed.toLowerCase(Locale.ROOT);
        List<String[]> commands = new ArrayList<>();
        commands.add(new String[]{"/code", getString(R.string.slash_cmd_code_desc)});
        commands.add(new String[]{"/chat", getString(R.string.slash_cmd_chat_desc)});
        commands.add(new String[]{"/context", getString(R.string.slash_cmd_context_desc)});
        commands.add(new String[]{"/shizuku", getString(R.string.slash_cmd_shizuku_desc)});
        commands.add(new String[]{"/review", getString(R.string.slash_cmd_review_desc)});
        commands.add(new String[]{"/new", getString(R.string.slash_cmd_new_desc)});
        commands.add(new String[]{"/clear", getString(R.string.slash_cmd_clear_desc)});

        binding.llSlashCommandPopup.removeAllViews();
        int matchCount = 0;
        for (String[] cmd : commands) {
            if (!cmd[0].startsWith(query)) continue;
            matchCount++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(
                    ThemeApplier.dpToPxInt(this, 10f),
                    ThemeApplier.dpToPxInt(this, 8f),
                    ThemeApplier.dpToPxInt(this, 10f),
                    ThemeApplier.dpToPxInt(this, 8f)
            );
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);

            TextView tvCmd = new TextView(this);
            tvCmd.setText(cmd[0]);
            tvCmd.setTextSize(12.5f);
            tvCmd.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            if (activePalette != null) tvCmd.setTextColor(activePalette.getPrimaryColor());

            TextView tvDesc = new TextView(this);
            tvDesc.setText("  ·  " + cmd[1]);
            tvDesc.setTextSize(12f);
            if (activePalette != null) tvDesc.setTextColor(activePalette.getTextSecondaryColor());

            row.addView(tvCmd);
            row.addView(tvDesc);
            final String chosenCmd = cmd[0];
            row.setOnClickListener(v -> {
                binding.llSlashCommandPopup.setVisibility(View.GONE);
                if ("/code".equals(chosenCmd)) {
                    viewModel.setAgentMode(AgentMode.CODING);
                    binding.etMessageInput.setText("");
                } else if ("/chat".equals(chosenCmd)) {
                    viewModel.setAgentMode(AgentMode.CHAT);
                    binding.etMessageInput.setText("");
                } else if ("/context".equals(chosenCmd)) {
                    binding.etMessageInput.setText("");
                    showContextInspectorDialog();
                } else if ("/shizuku".equals(chosenCmd)) {
                    binding.etMessageInput.setText("");
                    handleShizukuQuickTap();
                } else if ("/review".equals(chosenCmd)) {
                    binding.etMessageInput.setText("");
                    handleUiOnlySlashCommand("/review");
                } else if ("/new".equals(chosenCmd) || "/clear".equals(chosenCmd)) {
                    binding.etMessageInput.setText("");
                    viewModel.sendOrQueueMessage(chosenCmd);
                }
            });
            binding.llSlashCommandPopup.addView(row);
        }
        binding.llSlashCommandPopup.setVisibility(matchCount > 0 ? View.VISIBLE : View.GONE);
    }

    private void observeState() {
        viewModel.getActiveTheme().observe(this, this::applyThemeConfig);

        viewModel.getSettings().observe(this, settings -> {
            currentSettings = settings;
            if (settings != null && settings.isBubbleEnabled()
                    && ChatBubbleService.canDrawOverlays(this)
                    && !ChatBubbleService.isRunning()) {
                ChatBubbleService.start(this, viewModel.getActiveConversationId().getValue(), false);
            }
            updateBubbleButtonTint();
            updateWorkspaceChipText();
        });

        viewModel.getSelectedModel().observe(this, model -> {
            currentModel = model;
            if (model != null) {
                binding.tvSelectedModel.setText(model.getName());
                if (model.getAccessType() == AccessType.FREE) {
                    binding.tvModelPricingBadge.setText(R.string.badge_free);
                } else if (model.getAccessType() == AccessType.LOCAL) {
                    binding.tvModelPricingBadge.setText(R.string.badge_local);
                } else {
                    binding.tvModelPricingBadge.setText(R.string.badge_api_key);
                }
                styleModelPill();
            }
        });

        viewModel.getAgentMode().observe(this, mode -> {
            currentAgentMode = mode != null ? mode : AgentMode.CHAT;
            boolean isCoding = currentAgentMode == AgentMode.CODING;
            binding.tvAgentMode.setText(isCoding ? R.string.mode_coding_short : R.string.mode_chat_short);
            binding.ivAgentMode.setImageResource(isCoding ? R.drawable.ic_build : R.drawable.ic_chat_spark);
            messageAdapter.setAgentMode(currentAgentMode);
            styleAgentModePill();
        });

        appContainer.getShizukuIntegrationManager().observeState().observe(this, state -> {
            currentShizukuState = state;
            styleShizukuPill();
        });

        viewModel.getContextMetrics().observe(this, metrics -> {
            currentContextMetrics = metrics != null ? metrics : ChatSessionCoordinator.ContextMetrics.empty();
            styleContextMeterPill();
        });

        viewModel.getPendingToolConfirmation().observe(this, pending -> {
            if (pending != null && messageAdapter.getItemCount() > 0 && !userScrolledUp) {
                binding.rvMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
            }
        });

        viewModel.getLiveToolExecutions().observe(this, executions -> {
            if (viewModel.getExecutionState().getValue() == ExecutionState.EXECUTING_TOOL
                    && executions != null && !executions.isEmpty()) {
                ToolExecutor.ActiveToolExecution latest = executions.get(0);
                binding.tvExecutionStatus.setText("Running tool: " + latest.toolName + " (" + latest.status.name() + ")");
            }
        });

        viewModel.getFollowUpQueue().observe(this, this::renderFollowUpQueue);

        viewModel.getThinkingState().observe(this, state -> {
            currentThinkingState = state != null ? state : ThinkingState.DISABLED;
            styleDeepThinkingPill();
        });

        viewModel.getActiveProjectId().observe(this, projectId -> {
            if (projectId != null && !projectId.trim().isEmpty()) {
                binding.btnProjectTag.setText(getString(R.string.project_tag_active, projectId.trim()));
            } else {
                binding.btnProjectTag.setText(R.string.project_tag_none);
            }
            updateWorkspaceChipText();
            styleProjectTagPill();
        });

        viewModel.getExecutionState().observe(this, state -> {
            boolean active = state == ExecutionState.PREPARING
                    || state == ExecutionState.CONNECTING
                    || state == ExecutionState.THINKING
                    || state == ExecutionState.STREAMING
                    || state == ExecutionState.EXECUTING_TOOL;
            boolean showBanner = state != null && state != ExecutionState.IDLE;

            messageAdapter.setAiBusy(active);
            binding.btnStop.setVisibility(active ? View.VISIBLE : View.GONE);
            binding.btnSend.setVisibility(View.VISIBLE);
            binding.btnSend.setContentDescription(getString(active ? R.string.cd_queue_send : R.string.cd_send));
            binding.etMessageInput.setHint(active ? R.string.composer_queue_hint : R.string.composer_hint);
            binding.executionStatusBanner.setVisibility(showBanner ? View.VISIBLE : View.GONE);
            binding.pbExecutionStatus.setVisibility(active ? View.VISIBLE : View.GONE);

            if (showBanner) {
                if (state == ExecutionState.PREPARING) {
                    binding.tvExecutionStatus.setText(R.string.execution_state_preparing);
                } else if (state == ExecutionState.CONNECTING) {
                    binding.tvExecutionStatus.setText(R.string.execution_state_connecting);
                } else if (state == ExecutionState.THINKING) {
                    binding.tvExecutionStatus.setText(R.string.execution_state_thinking);
                } else if (state == ExecutionState.EXECUTING_TOOL) {
                    binding.tvExecutionStatus.setText(R.string.execution_state_tool);
                } else if (state == ExecutionState.STREAMING) {
                    binding.tvExecutionStatus.setText(R.string.execution_state_streaming);
                } else if (state == ExecutionState.COMPLETED) {
                    binding.tvExecutionStatus.setText(R.string.execution_state_completed);
                } else if (state == ExecutionState.CANCELLED) {
                    binding.tvExecutionStatus.setText(R.string.execution_state_stopped);
                } else if (state == ExecutionState.FAILED) {
                    String detail = viewModel.getExecutionErrorDetail().getValue();
                    binding.tvExecutionStatus.setText(detail != null && !detail.isEmpty()
                            ? detail
                            : getString(R.string.execution_state_failed));
                }
                if (activePalette != null) {
                    int statusColor = state == ExecutionState.FAILED
                            ? ContextCompat.getColor(this, R.color.status_unsupported)
                            : (state == ExecutionState.COMPLETED
                            ? ContextCompat.getColor(this, R.color.status_supported)
                            : activePalette.getPrimaryColor());
                    binding.tvExecutionStatus.setTextColor(statusColor);
                }
            }
        });

        viewModel.getExecutionNotice().observe(this, notice -> {
            if (notice != null && !notice.isEmpty()) {
                Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
                viewModel.clearExecutionNotice();
            }
        });

        viewModel.getPendingAttachments().observe(this, attachments -> {
            pendingAttachmentAdapter.submitList(attachments);
            boolean hasItems = attachments != null && !attachments.isEmpty();
            binding.rvPendingAttachments.setVisibility(hasItems ? View.VISIBLE : View.GONE);
            updateSendButtonState();
        });

        viewModel.getMessages().observe(this, messages -> {
            messageAdapter.submitList(messages);
            boolean hasMessages = messages != null && !messages.isEmpty();
            binding.emptyChatContainer.setVisibility(hasMessages ? View.GONE : View.VISIBLE);
            binding.rvMessages.setVisibility(hasMessages ? View.VISIBLE : View.GONE);
            if (!hasMessages) {
                binding.btnScrollToBottom.setVisibility(View.GONE);
            } else if (!userScrolledUp) {
                binding.rvMessages.scrollToPosition(messages.size() - 1);
            }
        });

        viewModel.getAllConversations().observe(this, conversations -> {
            sidebarAdapter.submitConversations(conversations, viewModel.getActiveConversationId().getValue());
            boolean empty = conversations == null || conversations.isEmpty();
            binding.tvSidebarEmptyHistory.setVisibility(empty ? View.VISIBLE : View.GONE);
            binding.rvSidebarHistory.setVisibility(empty ? View.GONE : View.VISIBLE);
        });

        viewModel.getActiveConversationId().observe(this, activeId ->
                sidebarAdapter.setActiveConversationId(activeId)
        );
    }

    private void renderFollowUpQueue(List<ChatSessionCoordinator.QueuedMessage> queue) {
        binding.llFollowUpQueueItems.removeAllViews();
        if (queue == null || queue.isEmpty()) {
            binding.llFollowUpQueueContainer.setVisibility(View.GONE);
            return;
        }
        binding.llFollowUpQueueContainer.setVisibility(View.VISIBLE);
        binding.tvQueueHeader.setText(getString(R.string.queue_header_title, queue.size()));

        LayoutInflater inflater = LayoutInflater.from(this);
        for (ChatSessionCoordinator.QueuedMessage item : queue) {
            View row = inflater.inflate(R.layout.item_queued_message, binding.llFollowUpQueueItems, false);
            TextView tvText = row.findViewById(R.id.tv_queued_text);
            TextView btnSendNow = row.findViewById(R.id.btn_queued_send_now);
            View btnRemove = row.findViewById(R.id.btn_queued_remove);

            String label = !item.text.isEmpty() ? item.text : "(" + item.attachments.size() + " attachment)";
            tvText.setText(label);

            if (activePalette != null) {
                row.setBackground(GradientDrawableFactory.createSurfaceCard(
                        activePalette.getSurfaceColor(),
                        ThemeApplier.blendWithAlpha(activePalette.getBorderColor(), 110),
                        ThemeApplier.dpToPxInt(this, 0.75f),
                        ThemeApplier.dpToPx(this, 10f)
                ));
                tvText.setTextColor(activePalette.getTextPrimaryColor());
                btnSendNow.setBackground(GradientDrawableFactory.createPill(
                        ThemeApplier.blendWithAlpha(activePalette.getPrimaryColor(), 26),
                        activePalette.getPrimaryColor(),
                        ThemeApplier.dpToPxInt(this, 1f)
                ));
                btnSendNow.setTextColor(activePalette.getPrimaryColor());
            }

            btnSendNow.setOnClickListener(v -> viewModel.sendQueuedFollowUpNow(item.id));
            btnRemove.setOnClickListener(v -> viewModel.removeQueuedFollowUp(item.id));
            binding.llFollowUpQueueItems.addView(row);
        }
    }

    private void handleShizukuQuickTap() {
        ShizukuIntegrationManager shizuku = appContainer.getShizukuIntegrationManager();
        ShizukuIntegrationManager.ShizukuState state = shizuku.inspectState();
        if (state.status == IntegrationStatus.ACTIVE || state.status == IntegrationStatus.CONNECTED) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.terminal_backend_shizuku) + " · " + state.privilegeLevel.getLabel())
                    .setMessage(state.detail)
                    .setNeutralButton(R.string.terminal_open_hub, (d, w) ->
                            startActivity(new Intent(this, IntegrationsHubActivity.class)))
                    .setNegativeButton(R.string.sidebar_terminal, (d, w) ->
                            startActivity(new Intent(this, TerminalActivity.class)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        ShizukuIntegrationManager.QuickActionOutcome outcome = shizuku.requestOrConnect(this);
        if (outcome == ShizukuIntegrationManager.QuickActionOutcome.SHOW_RATIONALE_NEEDED) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.shizuku_rationale_title)
                    .setMessage(R.string.shizuku_rationale_message)
                    .setNeutralButton(R.string.shizuku_open_manager, (d, w) -> shizuku.openShizukuApp(this))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.tool_confirm_allow, (d, w) -> shizuku.requestPermission())
                    .show();
        } else if (outcome == ShizukuIntegrationManager.QuickActionOutcome.PERMISSION_REQUESTED) {
            Toast.makeText(this, R.string.hub_shizuku_perm, Toast.LENGTH_SHORT).show();
        }
    }

    private void showContextInspectorDialog() {
        ChatSessionCoordinator.ContextMetrics m = currentContextMetrics != null
                ? currentContextMetrics
                : ChatSessionCoordinator.ContextMetrics.empty();
        String modelName = currentModel != null ? currentModel.getName() : "AnAI";
        String summary = getString(
                R.string.context_inspector_summary,
                modelName,
                m.windowTokens,
                m.fillPercent,
                m.systemTokens,
                m.memoryTokens,
                m.summaryTokens,
                m.messageCount,
                m.historyTokens,
                m.totalTokens
        );
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.context_inspector_title)
                .setMessage(summary)
                .setNeutralButton(R.string.context_compact_now, (d, w) -> {
                    viewModel.compactConversationNow();
                    Toast.makeText(this, R.string.context_compacted_done, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void applyThemeConfig(ThemeConfig config) {
        activeIsDark = ThemeManager.isDarkActive(this, config);
        activePalette = ThemeManager.resolvePalette(this, config);
        ThemeApplier.applySystemBars(this, activePalette, activeIsDark);

        binding.mainRoot.setBackgroundColor(activePalette.getBackgroundColor());
        binding.topAppBar.setBackgroundColor(activePalette.getBackgroundColor());
        binding.btnMenu.setColorFilter(activePalette.getTextPrimaryColor());
        binding.btnTopNewChat.setColorFilter(activePalette.getTextPrimaryColor());
        updateBubbleButtonTint();

        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        binding.btnScrollToBottom.setBackground(GradientDrawableFactory.createPill(
                activePalette.getSurfaceColor(),
                activePalette.getBorderColor(),
                stroke
        ));
        binding.btnScrollToBottom.setColorFilter(activePalette.getPrimaryColor());

        binding.llFollowUpQueueContainer.setBackground(GradientDrawableFactory.createSurfaceCard(
                activePalette.getSurfaceVariantColor(),
                activePalette.getBorderColor(),
                stroke,
                ThemeApplier.dpToPx(this, 14f)
        ));
        binding.tvQueueHeader.setTextColor(activePalette.getTextPrimaryColor());
        binding.btnQueueClear.setTextColor(activePalette.getTextSecondaryColor());

        binding.llSlashCommandPopup.setBackground(GradientDrawableFactory.createSurfaceCard(
                activePalette.getSurfaceColor(),
                activePalette.getBorderColor(),
                stroke,
                ThemeApplier.dpToPx(this, 14f)
        ));

        // Empty State
        binding.emptySparkBadge.setBackground(
                GradientDrawableFactory.createLinearGradient(activePalette, ThemeApplier.dpToPx(this, 20f))
        );
        binding.ivEmptySpark.setColorFilter(activePalette.getOnPrimaryColor());
        binding.tvGreetingTitle.setTextColor(activePalette.getTextPrimaryColor());
        binding.tvGreetingSubtitle.setTextColor(activePalette.getTextSecondaryColor());
        binding.tvPhase1Badge.setBackground(GradientDrawableFactory.createPill(
                ThemeApplier.blendWithAlpha(activePalette.getPrimaryColor(), 28),
                activePalette.getPrimaryColor(),
                stroke
        ));
        binding.tvPhase1Badge.setTextColor(activePalette.getPrimaryColor());
        binding.executionStatusBanner.setBackground(GradientDrawableFactory.createPill(
                activePalette.getSurfaceVariantColor(),
                ThemeApplier.blendWithAlpha(activePalette.getBorderColor(), 110),
                ThemeApplier.dpToPxInt(this, 0.75f)
        ));
        binding.tvExecutionStatus.setTextColor(activePalette.getPrimaryColor());

        float cardRadius = ThemeApplier.dpToPx(this, 16f);
        View[] suggestionCards = {binding.cardSuggestion1, binding.cardSuggestion2, binding.cardSuggestion3};
        for (View card : suggestionCards) {
            card.setBackground(GradientDrawableFactory.createSurfaceCard(
                    activePalette.getSurfaceColor(), activePalette.getBorderColor(), stroke, cardRadius
            ));
        }
        binding.tvSuggestion1Title.setTextColor(activePalette.getTextPrimaryColor());
        binding.tvSuggestion2Title.setTextColor(activePalette.getTextPrimaryColor());
        binding.tvSuggestion3Title.setTextColor(activePalette.getTextPrimaryColor());
        binding.tvSuggestion1Sub.setTextColor(activePalette.getTextSecondaryColor());
        binding.tvSuggestion2Sub.setTextColor(activePalette.getTextSecondaryColor());
        binding.tvSuggestion3Sub.setTextColor(activePalette.getTextSecondaryColor());

        // Composer
        binding.composerCard.setBackground(GradientDrawableFactory.createSurfaceCard(
                activePalette.getSurfaceColor(),
                activePalette.getBorderColor(),
                stroke,
                ThemeApplier.dpToPx(this, 22f)
        ));
        binding.etMessageInput.setTextColor(activePalette.getTextPrimaryColor());
        binding.etMessageInput.setHintTextColor(activePalette.getTextSecondaryColor());
        binding.btnAttach.setColorFilter(activePalette.getTextPrimaryColor());

        // Sidebar
        binding.sidebarContainer.setBackgroundColor(activePalette.getBackgroundColor());
        binding.sidebarBrandIconBg.setBackground(
                GradientDrawableFactory.createLinearGradient(activePalette, ThemeApplier.dpToPx(this, 10f))
        );
        binding.ivSidebarBrand.setColorFilter(activePalette.getOnPrimaryColor());
        binding.tvSidebarBrandName.setTextColor(activePalette.getTextPrimaryColor());
        binding.btnCloseSidebar.setColorFilter(activePalette.getTextSecondaryColor());

        binding.btnSidebarNewChat.setBackground(
                GradientDrawableFactory.createLinearGradient(activePalette, ThemeApplier.dpToPx(this, 14f))
        );
        binding.ivSidebarNewChat.setColorFilter(activePalette.getOnPrimaryColor());
        binding.tvSidebarNewChat.setTextColor(activePalette.getOnPrimaryColor());

        binding.btnSidebarSearch.setBackground(GradientDrawableFactory.createSurfaceCard(
                activePalette.getSurfaceColor(),
                activePalette.getBorderColor(),
                stroke,
                ThemeApplier.dpToPx(this, 14f)
        ));
        binding.ivSidebarSearch.setColorFilter(activePalette.getPrimaryColor());
        binding.tvSidebarSearch.setTextColor(activePalette.getTextPrimaryColor());

        View[] quickChips = {
                binding.btnSidebarAgent, binding.btnSidebarMemory, binding.btnSidebarProviders,
                binding.btnSidebarTerminal, binding.btnSidebarHub
        };
        for (View chip : quickChips) {
            chip.setBackground(GradientDrawableFactory.createSurfaceCard(
                    activePalette.getSurfaceColor(),
                    activePalette.getBorderColor(),
                    stroke,
                    ThemeApplier.dpToPx(this, 12f)
            ));
        }
        binding.ivSidebarAgent.setColorFilter(activePalette.getPrimaryColor());
        binding.tvSidebarAgent.setTextColor(activePalette.getTextPrimaryColor());
        binding.ivSidebarMemory.setColorFilter(activePalette.getSecondaryColor());
        binding.tvSidebarMemory.setTextColor(activePalette.getTextPrimaryColor());
        binding.ivSidebarProviders.setColorFilter(activePalette.getPrimaryColor());
        binding.tvSidebarProviders.setTextColor(activePalette.getTextPrimaryColor());
        binding.ivSidebarTerminal.setColorFilter(activePalette.getPrimaryColor());
        binding.tvSidebarTerminal.setTextColor(activePalette.getTextPrimaryColor());
        binding.ivSidebarHub.setColorFilter(activePalette.getSecondaryColor());
        binding.tvSidebarHub.setTextColor(activePalette.getTextPrimaryColor());

        binding.tvSidebarEmptyHistory.setTextColor(activePalette.getTextSecondaryColor());
        binding.sidebarDivider.setBackgroundColor(activePalette.getBorderColor());
        binding.ivSidebarSettings.setColorFilter(activePalette.getPrimaryColor());
        binding.tvSidebarSettings.setTextColor(activePalette.getTextPrimaryColor());
        binding.ivSidebarSettingsArrow.setColorFilter(activePalette.getTextSecondaryColor());

        styleModelPill();
        styleAgentModePill();
        styleShizukuPill();
        styleContextMeterPill();
        styleDeepThinkingPill();
        styleProjectTagPill();
        updateSendButtonState();

        messageAdapter.setPalette(activePalette);
        pendingAttachmentAdapter.setPalette(activePalette);
        sidebarAdapter.setPalette(activePalette);
    }

    private void updateWorkspaceChipText() {
        String proj = viewModel != null ? viewModel.getActiveProjectId().getValue() : "";
        if (proj != null && !proj.trim().isEmpty()) {
            binding.tvWorkspaceChip.setText(proj.trim());
        } else if (currentSettings != null && !currentSettings.getActiveWorkspacePath().trim().isEmpty()) {
            String path = currentSettings.getActiveWorkspacePath().trim();
            int idx = path.lastIndexOf('/');
            binding.tvWorkspaceChip.setText(idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : getString(R.string.workspace_chip_default));
        } else {
            binding.tvWorkspaceChip.setText(R.string.workspace_chip_default);
        }
    }

    private void styleAgentModePill() {
        if (activePalette == null) return;
        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        boolean isCoding = currentAgentMode == AgentMode.CODING;

        binding.harnessModeToggle.setBackground(GradientDrawableFactory.createPill(
                activePalette.getSurfaceVariantColor(),
                activePalette.getBorderColor(),
                stroke
        ));
        if (isCoding) {
            binding.btnModeCode.setBackground(GradientDrawableFactory.createLinearGradient(activePalette, 999f));
            binding.btnModeCode.setTextColor(activePalette.getOnPrimaryColor());
            binding.btnModeChat.setBackground(null);
            binding.btnModeChat.setTextColor(activePalette.getTextSecondaryColor());
        } else {
            binding.btnModeChat.setBackground(GradientDrawableFactory.createLinearGradient(activePalette, 999f));
            binding.btnModeChat.setTextColor(activePalette.getOnPrimaryColor());
            binding.btnModeCode.setBackground(null);
            binding.btnModeCode.setTextColor(activePalette.getTextSecondaryColor());
        }

        if (isCoding) {
            binding.btnAgentMode.setBackground(
                    GradientDrawableFactory.createLinearGradient(activePalette, 999f)
            );
            binding.ivAgentMode.setColorFilter(activePalette.getOnPrimaryColor());
            binding.tvAgentMode.setTextColor(activePalette.getOnPrimaryColor());
        } else {
            binding.btnAgentMode.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(),
                    activePalette.getBorderColor(),
                    stroke
            ));
            binding.ivAgentMode.setColorFilter(activePalette.getPrimaryColor());
            binding.tvAgentMode.setTextColor(activePalette.getTextPrimaryColor());
        }
    }

    private void styleShizukuPill() {
        ShizukuIntegrationManager shizuku = appContainer.getShizukuIntegrationManager();
        ShizukuIntegrationManager.ShizukuState s = currentShizukuState != null ? currentShizukuState : shizuku.inspectState();
        binding.tvShizukuPill.setText(shizuku.getShortStatusBadge());

        int supported = ContextCompat.getColor(this, R.color.status_supported);
        int pending = ContextCompat.getColor(this, R.color.status_unknown);
        int unsupported = ContextCompat.getColor(this, R.color.status_unsupported);
        int neutral = activePalette != null ? activePalette.getTextSecondaryColor() : 0xFF6B5B53;

        int stateColor = neutral;
        if (s.status == IntegrationStatus.ACTIVE || s.status == IntegrationStatus.CONNECTED) {
            stateColor = supported;
        } else if (s.status == IntegrationStatus.PERMISSION_REQUIRED) {
            stateColor = pending;
        } else if (s.status == IntegrationStatus.NOT_CONNECTED || s.status == IntegrationStatus.ERROR) {
            stateColor = unsupported;
        }

        binding.viewShizukuDot.setBackground(GradientDrawableFactory.createPill(stateColor, 0, 0));
        if (activePalette != null) {
            binding.btnShizukuPill.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(stateColor, 20),
                    ThemeApplier.blendWithAlpha(stateColor, 90),
                    ThemeApplier.dpToPxInt(this, 1f)
            ));
            binding.tvShizukuPill.setTextColor(activePalette.getTextPrimaryColor());
        }
    }

    private void styleContextMeterPill() {
        int pct = currentContextMetrics != null ? currentContextMetrics.fillPercent : 0;
        binding.tvContextMeter.setText(getString(R.string.context_meter_label, pct));
        if (activePalette == null) return;
        int meterColor = pct >= 80
                ? ContextCompat.getColor(this, R.color.status_unsupported)
                : (pct >= 55 ? ContextCompat.getColor(this, R.color.status_unknown) : activePalette.getPrimaryColor());
        binding.btnContextMeter.setBackground(GradientDrawableFactory.createPill(
                activePalette.getSurfaceVariantColor(),
                activePalette.getBorderColor(),
                ThemeApplier.dpToPxInt(this, 1f)
        ));
        binding.tvContextMeter.setTextColor(meterColor);
    }

    private void styleModelPill() {
        if (activePalette == null) return;
        binding.btnModelSelector.setBackground(GradientDrawableFactory.createPill(
                activePalette.getSurfaceColor(),
                activePalette.getBorderColor(),
                ThemeApplier.dpToPxInt(this, 1f)
        ));
        binding.viewModelDot.setBackground(
                GradientDrawableFactory.createLinearGradient(activePalette, 999f)
        );
        binding.tvSelectedModel.setTextColor(activePalette.getTextPrimaryColor());
        binding.ivModelChevron.setColorFilter(activePalette.getTextSecondaryColor());

        boolean isFree = currentModel != null && currentModel.getAccessType() == AccessType.FREE;
        int badgeColor = isFree
                ? ContextCompat.getColor(this, R.color.status_supported)
                : activePalette.getPrimaryColor();
        binding.tvModelPricingBadge.setBackground(GradientDrawableFactory.createPill(
                ThemeApplier.blendWithAlpha(badgeColor, 28),
                0,
                0
        ));
        binding.tvModelPricingBadge.setTextColor(badgeColor);
        binding.btnStop.setBackground(GradientDrawableFactory.createPill(
                ThemeApplier.blendWithAlpha(ContextCompat.getColor(this, R.color.status_unsupported), 32),
                ContextCompat.getColor(this, R.color.status_unsupported),
                ThemeApplier.dpToPxInt(this, 1f)
        ));
        binding.btnStop.setColorFilter(ContextCompat.getColor(this, R.color.status_unsupported));
    }

    private void styleDeepThinkingPill() {
        if (activePalette == null) return;
        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        if (currentThinkingState == ThinkingState.ENABLED) {
            binding.btnDeepThinking.setBackground(
                    GradientDrawableFactory.createLinearGradient(activePalette, 999f)
            );
            binding.ivDeepThinking.setColorFilter(activePalette.getOnPrimaryColor());
            binding.tvDeepThinking.setTextColor(activePalette.getOnPrimaryColor());
            binding.btnDeepThinking.setAlpha(1.0f);
        } else if (currentThinkingState == ThinkingState.UNSUPPORTED) {
            binding.btnDeepThinking.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(),
                    activePalette.getBorderColor(),
                    stroke
            ));
            binding.ivDeepThinking.setColorFilter(activePalette.getTextSecondaryColor());
            binding.tvDeepThinking.setTextColor(activePalette.getTextSecondaryColor());
            binding.btnDeepThinking.setAlpha(0.45f);
        } else {
            binding.btnDeepThinking.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(),
                    activePalette.getBorderColor(),
                    stroke
            ));
            binding.ivDeepThinking.setColorFilter(activePalette.getTextPrimaryColor());
            binding.tvDeepThinking.setTextColor(activePalette.getTextPrimaryColor());
            binding.btnDeepThinking.setAlpha(1.0f);
        }
    }

    private void styleProjectTagPill() {
        if (activePalette == null) return;
        String proj = viewModel.getActiveProjectId().getValue();
        boolean hasProject = proj != null && !proj.trim().isEmpty();
        int stroke = ThemeApplier.dpToPxInt(this, 1f);
        if (hasProject) {
            binding.btnProjectTag.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(activePalette.getPrimaryColor(), 30),
                    activePalette.getPrimaryColor(),
                    stroke
            ));
            binding.btnProjectTag.setTextColor(activePalette.getPrimaryColor());
            binding.btnWorkspaceChip.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(activePalette.getPrimaryColor(), 24),
                    activePalette.getPrimaryColor(),
                    stroke
            ));
            binding.ivWorkspaceChip.setColorFilter(activePalette.getPrimaryColor());
            binding.tvWorkspaceChip.setTextColor(activePalette.getPrimaryColor());
        } else {
            binding.btnProjectTag.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(),
                    activePalette.getBorderColor(),
                    stroke
            ));
            binding.btnProjectTag.setTextColor(activePalette.getTextSecondaryColor());
            binding.btnWorkspaceChip.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(),
                    activePalette.getBorderColor(),
                    stroke
            ));
            binding.ivWorkspaceChip.setColorFilter(activePalette.getTextSecondaryColor());
            binding.tvWorkspaceChip.setTextColor(activePalette.getTextPrimaryColor());
        }
    }

    private void showProjectTagDialog() {
        EditText input = new EditText(this);
        String current = viewModel.getActiveProjectId().getValue();
        input.setText(current != null ? current : "");
        input.setHint(R.string.memory_project_hint);
        input.setSelection(input.getText().length());
        int pad = ThemeApplier.dpToPxInt(this, 20f);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        String workspacePath = appContainer.getWorkspaceFileManager().getActiveWorkspacePath();
        String msg = getString(R.string.project_dialog_message) + "\n\nWorkspace: " + workspacePath;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.project_dialog_title)
                .setMessage(msg)
                .setView(input)
                .setNeutralButton(R.string.project_clear, (d, w) -> viewModel.setConversationProject(""))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) ->
                        viewModel.setConversationProject(input.getText() != null ? input.getText().toString() : "")
                )
                .show();
    }

    private void updateSendButtonState() {
        String text = binding.etMessageInput.getText() != null
                ? binding.etMessageInput.getText().toString().trim()
                : "";
        List<Attachment> pending = viewModel.getPendingAttachments().getValue();
        boolean hasPending = pending != null && !pending.isEmpty();
        boolean canSend = !text.isEmpty() || hasPending;

        binding.btnSend.setEnabled(canSend);
        if (activePalette != null) {
            if (canSend) {
                binding.btnSend.setBackground(GradientDrawableFactory.createLinearGradient(activePalette, 999f));
                binding.btnSend.setColorFilter(activePalette.getOnPrimaryColor());
                binding.btnSend.setAlpha(1.0f);
            } else {
                binding.btnSend.setBackground(GradientDrawableFactory.createPill(
                        activePalette.getSurfaceVariantColor(),
                        activePalette.getBorderColor(),
                        ThemeApplier.dpToPxInt(this, 1f)
                ));
                binding.btnSend.setColorFilter(activePalette.getTextSecondaryColor());
                binding.btnSend.setAlpha(0.55f);
            }
        }
    }

    private void toggleChatBubble() {
        if (ChatBubbleService.isRunning()) {
            appContainer.getSettingsRepository().setBubbleEnabled(false);
            ChatBubbleService.stop(this);
            updateBubbleButtonTint();
            Toast.makeText(this, R.string.bubble_stopped_notice, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!ChatBubbleService.canDrawOverlays(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bubble_permission_title)
                    .setMessage(R.string.bubble_permission_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.bubble_permission_grant, (d, w) -> {
                        pendingStartBubbleAfterOverlayPerm = true;
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        );
                        overlayPermissionLauncher.launch(intent);
                    })
                    .show();
            return;
        }

        launchChatBubble();
    }

    private void launchChatBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        startBubbleServiceNow();
    }

    private void startBubbleServiceNow() {
        appContainer.getSettingsRepository().setBubbleEnabled(true);
        String activeConvId = viewModel != null ? viewModel.getActiveConversationId().getValue() : null;
        ChatBubbleService.start(this, activeConvId, false);
        updateBubbleButtonTint();
        Toast.makeText(this, R.string.bubble_started_notice, Toast.LENGTH_LONG).show();
    }

    private void updateBubbleButtonTint() {
        if (binding == null || activePalette == null) return;
        boolean active = ChatBubbleService.isRunning();
        binding.btnChatBubble.setColorFilter(
                active ? activePalette.getPrimaryColor() : activePalette.getTextPrimaryColor()
        );
    }

    private void requestCameraAndCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCameraCapture() {
        try {
            pendingCameraFile = AttachmentStorageHelper.createCameraOutputFile(this);
            Uri uri = AttachmentStorageHelper.getUriForFile(this, pendingCameraFile);
            takePictureLauncher.launch(uri);
        } catch (IOException e) {
            AppLogger.e(TAG, "Failed to prepare camera file", e);
        }
    }

    private void importPickedUris(List<Uri> uris, AttachmentType preferredType) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            List<Attachment> imported = new ArrayList<>();
            for (Uri uri : uris) {
                imported.add(AttachmentStorageHelper.importUriToLocalStorage(this, uri, preferredType));
            }
            AppExecutors.getInstance().postToMain(() -> viewModel.addPendingAttachments(imported));
        });
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("ChatAI", text));
            Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void showEditMessageDialog(Message message) {
        EditText input = new EditText(this);
        input.setText(message.getContent());
        input.setSelection(input.getText().length());
        int pad = ThemeApplier.dpToPxInt(this, 20f);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_edit_message_title)
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String updated = input.getText().toString().trim();
                    if (!updated.isEmpty()) {
                        viewModel.editMessage(message.getId(), updated);
                    }
                })
                .show();
    }

    private void handleDeleteMessage(Message message) {
        boolean confirm = currentSettings == null || currentSettings.isConfirmBeforeDelete();
        if (!confirm) {
            viewModel.deleteMessage(message.getId());
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_message_title)
                .setMessage(R.string.dialog_delete_message_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm_delete, (d, w) -> viewModel.deleteMessage(message.getId()))
                .show();
    }

    private void showRenameConversationDialog(Conversation conversation) {
        EditText input = new EditText(this);
        input.setText(conversation.getTitle());
        input.setHint(R.string.dialog_rename_hint);
        input.setSelection(input.getText().length());
        int pad = ThemeApplier.dpToPxInt(this, 20f);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_rename_title)
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String newTitle = input.getText().toString().trim();
                    if (!newTitle.isEmpty()) {
                        viewModel.renameConversation(conversation.getId(), newTitle);
                    }
                })
                .show();
    }

    private void handleDeleteConversation(Conversation conversation) {
        boolean confirm = currentSettings == null || currentSettings.isConfirmBeforeDelete();
        if (!confirm) {
            viewModel.deleteConversation(conversation.getId());
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_conversation_title)
                .setMessage(R.string.dialog_delete_conversation_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm_delete, (d, w) -> viewModel.deleteConversation(conversation.getId()))
                .show();
    }

    private void showImageSourcePreviewDialog(String title, String imageSource) {
        if (imageSource == null || imageSource.trim().isEmpty()) return;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_attachment_preview, null);
        ImageView iv = view.findViewById(R.id.iv_preview_image);
        View fileInfo = view.findViewById(R.id.preview_file_info_container);
        if (fileInfo != null) fileInfo.setVisibility(View.GONE);
        if (iv != null) {
            iv.setVisibility(View.VISIBLE);
            String src = imageSource.trim();
            if (src.startsWith("data:image/")) {
                Bitmap bmp = MessageAdapter.decodeDataUriBitmap(src);
                if (bmp != null) {
                    iv.setImageBitmap(bmp);
                } else {
                    iv.setImageResource(R.drawable.ic_photo);
                }
            } else if (src.startsWith("/") || src.startsWith("file://")) {
                String path = src.startsWith("file://") ? src.substring("file://".length()) : src;
                File file = new File(path);
                if (file.exists()) {
                    Glide.with(this).load(file).into(iv);
                } else {
                    Glide.with(this).load(Uri.parse(src)).into(iv);
                }
            } else {
                Glide.with(this).load(src).into(iv);
            }
        }
        String dialogTitle = (title != null && !title.trim().isEmpty())
                ? title.trim()
                : getString(R.string.image_preview_title);
        new MaterialAlertDialogBuilder(this)
                .setTitle(dialogTitle)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void handleSourceNavigation(String urlOrPath) {
        if (urlOrPath == null || urlOrPath.trim().isEmpty()) return;
        String target = urlOrPath.trim();
        if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                return;
            } catch (Exception ignored) {
            }
        }
        copyToClipboard(target);
    }
}
