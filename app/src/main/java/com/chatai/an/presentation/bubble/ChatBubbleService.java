package com.chatai.an.presentation.bubble;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatai.an.R;
import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.core.AiCore;
import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.memory.ConversationCompactor;
import com.chatai.an.ai.memory.MemoryRetrievalEngine;
import com.chatai.an.ai.provider.AiStreamListener;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.app.AppContainer;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.app.MainActivity;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.core.AppLogger;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.AiResponse;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.repository.ConversationRepository;
import com.chatai.an.domain.repository.MemoryRepository;
import com.chatai.an.domain.repository.MessageRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;
import com.chatai.an.presentation.chat.MessageAdapter;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Messenger-style floating Chat Bubble ("Chat Head") service.
 * Provides a draggable, edge-snapping circular bubble with bottom X dismiss target
 * and an expandable floating mini-chat window powered by AnAI and shared Room history.
 */
public class ChatBubbleService extends Service {
    private static final String TAG = "ChatBubbleService";

    public static final String ACTION_START = "com.chatai.an.action.BUBBLE_START";
    public static final String ACTION_EXPAND = "com.chatai.an.action.BUBBLE_EXPAND";
    public static final String ACTION_COLLAPSE = "com.chatai.an.action.BUBBLE_COLLAPSE";
    public static final String ACTION_STOP = "com.chatai.an.action.BUBBLE_STOP";

    public static final String EXTRA_CONVERSATION_ID = "extra_bubble_conversation_id";
    public static final String EXTRA_EXPAND_IMMEDIATELY = "extra_bubble_expand";

    private static final String CHANNEL_ID = "chatai_bubble_channel";
    private static final int NOTIFICATION_ID = 7401;
    private static final String STREAMING_PREVIEW_ID = "__bubble_streaming_preview__";

    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    public static boolean canDrawOverlays(Context context) {
        if (context == null) return false;
        return Settings.canDrawOverlays(context);
    }

    public static void start(Context context, @Nullable String conversationId, boolean expandImmediately) {
        if (context == null || !canDrawOverlays(context)) return;
        Intent intent = new Intent(context, ChatBubbleService.class);
        intent.setAction(ACTION_START);
        if (conversationId != null && !conversationId.isEmpty()) {
            intent.putExtra(EXTRA_CONVERSATION_ID, conversationId);
        }
        intent.putExtra(EXTRA_EXPAND_IMMEDIATELY, expandImmediately);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, ChatBubbleService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception e) {
            context.stopService(new Intent(context, ChatBubbleService.class));
        }
    }

    private WindowManager windowManager;
    private Context themedContext;
    private LayoutInflater themedInflater;

    private AppContainer appContainer;
    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private ModelRepository modelRepository;
    private ThemeRepository themeRepository;
    private SettingsRepository settingsRepository;
    private MemoryRepository memoryRepository;
    private MemoryRetrievalEngine memoryRetrievalEngine;
    private ConversationCompactor conversationCompactor;
    private ContextManager contextManager;
    private AiCore aiCore;
    private ToolRegistry toolRegistry;
    private WorkspaceFileManager workspaceFileManager;
    private AppExecutors executors;

    // Overlay Views & Params
    private View bubbleView;
    private FrameLayout bubbleAvatarCircle;
    private ImageView ivBubbleAvatar;
    private View viewBubbleBadge;
    private WindowManager.LayoutParams bubbleParams;

    private View dismissView;
    private FrameLayout dismissCircle;
    private ImageView ivDismissIcon;
    private WindowManager.LayoutParams dismissParams;
    private boolean dismissVisible = false;
    private boolean hoveringDismiss = false;

    private BubblePopupFrameLayout popupView;
    private LinearLayout popupCard;
    private FrameLayout popupHeaderAvatar;
    private ImageView ivPopupHeaderIcon;
    private TextView tvPopupTitle;
    private TextView tvPopupSubtitle;
    private ImageButton btnNewChat;
    private ImageButton btnOpenApp;
    private ImageButton btnCollapse;
    private ImageButton btnClose;
    private View headerDivider;
    private RecyclerView rvMessages;
    private View emptyStateView;
    private TextView tvEmptyTitle;
    private TextView tvEmptySub;
    private View statusBar;
    private ProgressBar pbStatus;
    private TextView tvStatus;
    private View composerDivider;
    private EditText etInput;
    private ImageButton btnStop;
    private ImageButton btnSend;
    private WindowManager.LayoutParams popupParams;

    private MessageAdapter messageAdapter;
    private ThemePalette activePalette;
    private AiModel currentModel;

    private String currentConversationId;
    private LiveData<List<Message>> observedMessagesLiveData;
    private List<Message> baseMessages = Collections.emptyList();
    private Message streamingPreview = null;
    private volatile ExecutionHandle activeExecutionHandle;
    private ExecutionState currentExecutionState = ExecutionState.IDLE;

    private boolean isExpanded = false;
    private boolean hasUnreadReply = false;
    private int screenWidth = 1080;
    private int screenHeight = 1920;
    private int statusBarHeight = 0;
    private int navBarHeight = 0;
    private int bubbleSizePx = 0;
    private int edgeMarginPx = 0;
    private ValueAnimator snapAnimator;

    private final Observer<ThemeConfig> themeObserver = config -> {
        if (config == null) return;
        activePalette = ThemeManager.resolvePalette(themedContext, config);
        applyTheme();
    };

    private final Observer<AiModel> modelObserver = model -> {
        currentModel = model;
        updateHeaderSubtitle();
    };

    private final Observer<List<Message>> messagesObserver = list -> {
        baseMessages = list != null ? list : Collections.emptyList();
        renderMessages();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;

        ChatAiApplication app = (ChatAiApplication) getApplication();
        appContainer = app.getAppContainer();
        conversationRepository = appContainer.getConversationRepository();
        messageRepository = appContainer.getMessageRepository();
        modelRepository = appContainer.getModelRepository();
        themeRepository = appContainer.getThemeRepository();
        settingsRepository = appContainer.getSettingsRepository();
        memoryRepository = appContainer.getMemoryRepository();
        memoryRetrievalEngine = appContainer.getMemoryRetrievalEngine();
        conversationCompactor = appContainer.getConversationCompactor();
        contextManager = appContainer.getContextManager();
        aiCore = appContainer.getAiCore();
        toolRegistry = appContainer.getToolRegistry();
        workspaceFileManager = appContainer.getWorkspaceFileManager();
        executors = appContainer.getExecutors();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        themedContext = new ContextThemeWrapper(this, R.style.Theme_ChatAI);
        themedInflater = LayoutInflater.from(themedContext);

        bubbleSizePx = ThemeApplier.dpToPxInt(themedContext, 68f);
        edgeMarginPx = ThemeApplier.dpToPxInt(themedContext, 8f);
        updateScreenBounds();

        createNotificationChannel();
        startAsForegroundService();

        initDismissOverlay();
        initBubbleOverlay();
        initPopupOverlay();

        themeRepository.observeActiveTheme().observeForever(themeObserver);
        modelRepository.observeSelectedModel().observeForever(modelObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            settingsRepository.setBubbleEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!canDrawOverlays(this)) {
            settingsRepository.setBubbleEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.hasExtra(EXTRA_CONVERSATION_ID)) {
            String incomingConvId = intent.getStringExtra(EXTRA_CONVERSATION_ID);
            if (incomingConvId != null && !incomingConvId.isEmpty() && !incomingConvId.equals(currentConversationId)) {
                bindConversation(incomingConvId);
            }
        }

        if (ACTION_EXPAND.equals(action)) {
            expandPopup();
        } else if (ACTION_COLLAPSE.equals(action)) {
            collapsePopup();
        } else if (intent != null && intent.getBooleanExtra(EXTRA_EXPAND_IMMEDIATELY, false)) {
            expandPopup();
        }

        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenBounds();
        if (bubbleView != null && bubbleParams != null && bubbleView.isAttachedToWindow()) {
            clampBubblePosition();
            try {
                windowManager.updateViewLayout(bubbleView, bubbleParams);
            } catch (Exception ignored) {
            }
        }
        if (isExpanded && popupView != null && popupParams != null && popupView.isAttachedToWindow()) {
            updatePopupDimensions();
            try {
                windowManager.updateViewLayout(popupView, popupParams);
            } catch (Exception ignored) {
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (snapAnimator != null) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
        if (activeExecutionHandle != null) {
            activeExecutionHandle.cancel();
            activeExecutionHandle = null;
        }
        try {
            themeRepository.observeActiveTheme().removeObserver(themeObserver);
        } catch (Exception ignored) {
        }
        try {
            modelRepository.observeSelectedModel().removeObserver(modelObserver);
        } catch (Exception ignored) {
        }
        if (observedMessagesLiveData != null) {
            try {
                observedMessagesLiveData.removeObserver(messagesObserver);
            } catch (Exception ignored) {
            }
            observedMessagesLiveData = null;
        }

        removeViewSafely(popupView);
        removeViewSafely(bubbleView);
        removeViewSafely(dismissView);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.bubble_notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription(getString(R.string.bubble_notif_channel_desc));
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startAsForegroundService() {
        Intent expandIntent = new Intent(this, ChatBubbleService.class);
        expandIntent.setAction(ACTION_EXPAND);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent expandPi = PendingIntent.getService(this, 101, expandIntent, piFlags);

        Intent stopIntent = new Intent(this, ChatBubbleService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 102, stopIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bubble_chat)
                .setContentTitle(getString(R.string.bubble_notif_title))
                .setContentText(getString(R.string.bubble_notif_text))
                .setContentIntent(expandPi)
                .addAction(R.drawable.ic_bubble_chat, getString(R.string.bubble_notif_action_open), expandPi)
                .addAction(R.drawable.ic_close, getString(R.string.bubble_notif_action_close), stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateScreenBounds() {
        if (windowManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
            android.graphics.Insets sysInsets = metrics.getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            statusBarHeight = sysInsets.top;
            navBarHeight = sysInsets.bottom;
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(dm);
            screenWidth = dm.widthPixels;
            screenHeight = dm.heightPixels;
            statusBarHeight = ThemeApplier.dpToPxInt(themedContext, 24f);
            navBarHeight = ThemeApplier.dpToPxInt(themedContext, 24f);
        }
    }

    private int getOverlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void initDismissOverlay() {
        dismissView = themedInflater.inflate(R.layout.overlay_bubble_dismiss, null);
        dismissCircle = dismissView.findViewById(R.id.dismiss_circle);
        ivDismissIcon = dismissView.findViewById(R.id.iv_dismiss_icon);

        dismissCircle.setBackground(createCircleDrawable(0xCC1E1E1E, 0x66FFFFFF, ThemeApplier.dpToPxInt(themedContext, 1.5f)));
        ivDismissIcon.setColorFilter(Color.WHITE);

        dismissParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        dismissParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dismissParams.y = navBarHeight + ThemeApplier.dpToPxInt(themedContext, 28f);
        dismissView.setVisibility(View.GONE);
        dismissView.setAlpha(0f);

        try {
            windowManager.addView(dismissView, dismissParams);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to attach dismiss overlay", e);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initBubbleOverlay() {
        bubbleView = themedInflater.inflate(R.layout.overlay_chat_bubble, null);
        bubbleAvatarCircle = bubbleView.findViewById(R.id.bubble_avatar_circle);
        ivBubbleAvatar = bubbleView.findViewById(R.id.iv_bubble_avatar);
        viewBubbleBadge = bubbleView.findViewById(R.id.view_bubble_badge);

        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = screenWidth - bubbleSizePx - edgeMarginPx;
        bubbleParams.y = Math.max(statusBarHeight + edgeMarginPx, screenHeight / 4);

        final int touchSlop = ViewConfiguration.get(themedContext).getScaledTouchSlop();
        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (snapAnimator != null) {
                            snapAnimator.cancel();
                        }
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        dragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!dragging && (dx * dx + dy * dy > touchSlop * touchSlop)) {
                            dragging = true;
                            showDismissTarget();
                        }
                        if (dragging) {
                            bubbleParams.x = (int) (initialX + dx);
                            bubbleParams.y = (int) (initialY + dy);
                            clampBubbleVerticalPosition();
                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams);
                            } catch (Exception ignored) {
                            }
                            updateDismissProximity(event.getRawX(), event.getRawY());
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) {
                            expandPopup();
                        } else {
                            boolean shouldDismiss = hoveringDismiss;
                            hideDismissTarget();
                            if (shouldDismiss) {
                                settingsRepository.setBubbleEnabled(false);
                                Toast.makeText(themedContext, R.string.bubble_stopped_notice, Toast.LENGTH_SHORT).show();
                                stopSelf();
                            } else {
                                snapBubbleToNearestEdge();
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to attach floating bubble", e);
        }
    }

    private void initPopupOverlay() {
        popupView = (BubblePopupFrameLayout) themedInflater.inflate(R.layout.overlay_chat_popup, null);
        popupView.setOnBackPressedListener(this::collapsePopup);
        popupView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                collapsePopup();
                return true;
            }
            return false;
        });

        popupCard = popupView.findViewById(R.id.bubble_popup_card);
        popupHeaderAvatar = popupView.findViewById(R.id.bubble_header_avatar);
        ivPopupHeaderIcon = popupView.findViewById(R.id.iv_bubble_header_icon);
        tvPopupTitle = popupView.findViewById(R.id.tv_bubble_title);
        tvPopupSubtitle = popupView.findViewById(R.id.tv_bubble_subtitle);
        btnNewChat = popupView.findViewById(R.id.btn_bubble_new_chat);
        btnOpenApp = popupView.findViewById(R.id.btn_bubble_open_app);
        btnCollapse = popupView.findViewById(R.id.btn_bubble_collapse);
        btnClose = popupView.findViewById(R.id.btn_bubble_close);
        headerDivider = popupView.findViewById(R.id.bubble_header_divider);
        rvMessages = popupView.findViewById(R.id.rv_bubble_messages);
        emptyStateView = popupView.findViewById(R.id.bubble_empty_state);
        tvEmptyTitle = popupView.findViewById(R.id.tv_bubble_empty_title);
        tvEmptySub = popupView.findViewById(R.id.tv_bubble_empty_sub);
        statusBar = popupView.findViewById(R.id.bubble_status_bar);
        pbStatus = popupView.findViewById(R.id.pb_bubble_status);
        tvStatus = popupView.findViewById(R.id.tv_bubble_status);
        composerDivider = popupView.findViewById(R.id.bubble_composer_divider);
        etInput = popupView.findViewById(R.id.et_bubble_input);
        btnStop = popupView.findViewById(R.id.btn_bubble_stop);
        btnSend = popupView.findViewById(R.id.btn_bubble_send);

        messageAdapter = new MessageAdapter(modelRepository, new MessageAdapter.OnMessageActionListener() {
            @Override
            public void onCopyMessage(Message message) {
                copyToClipboard(message.getContent());
            }

            @Override
            public void onEditMessage(Message message) {
                openFullApp();
            }

            @Override
            public void onDeleteMessage(Message message) {
                if (message != null && currentConversationId != null) {
                    messageRepository.deleteMessage(message.getId(), currentConversationId);
                }
            }

            @Override
            public void onCopyCodeBlock(String code) {
                copyToClipboard(code);
            }

            @Override
            public void onPreviewAttachment(Attachment attachment) {
                openFullApp();
            }
        });

        LinearLayoutManager lm = new LinearLayoutManager(themedContext);
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(messageAdapter);

        btnNewChat.setOnClickListener(v -> startNewConversation());
        btnOpenApp.setOnClickListener(v -> openFullApp());
        btnCollapse.setOnClickListener(v -> collapsePopup());
        btnClose.setOnClickListener(v -> {
            settingsRepository.setBubbleEnabled(false);
            Toast.makeText(themedContext, R.string.bubble_stopped_notice, Toast.LENGTH_SHORT).show();
            stopSelf();
        });

        btnStop.setOnClickListener(v -> stopGeneration());
        btnSend.setOnClickListener(v -> sendBubbleMessage());
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendBubbleMessage();
                return true;
            }
            return false;
        });
        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSendButtonState();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        popupParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        popupParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        popupParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        updatePopupDimensions();
    }

    private void updatePopupDimensions() {
        int maxPopupWidth = ThemeApplier.dpToPxInt(themedContext, 440f);
        popupParams.width = Math.min((int) (screenWidth * 0.94f), maxPopupWidth);
        popupParams.y = statusBarHeight + ThemeApplier.dpToPxInt(themedContext, 12f);

        int desiredCardHeight = Math.min(
                ThemeApplier.dpToPxInt(themedContext, 460f),
                Math.max(ThemeApplier.dpToPxInt(themedContext, 300f), (int) (screenHeight * 0.56f))
        );
        ViewGroup.LayoutParams cardLp = popupCard.getLayoutParams();
        if (cardLp != null) {
            cardLp.height = desiredCardHeight;
            popupCard.setLayoutParams(cardLp);
        }
    }

    private void expandPopup() {
        if (isExpanded) return;
        isExpanded = true;
        hasUnreadReply = false;
        updateUnreadBadge();
        updateScreenBounds();
        updatePopupDimensions();

        if (bubbleView != null) {
            bubbleView.setVisibility(View.GONE);
        }
        try {
            if (!popupView.isAttachedToWindow()) {
                windowManager.addView(popupView, popupParams);
            }
            popupView.setScaleX(0.92f);
            popupView.setScaleY(0.92f);
            popupView.setAlpha(0f);
            popupView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(180L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            renderMessages();
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to expand bubble popup", e);
            isExpanded = false;
            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
        }
    }

    private void collapsePopup() {
        if (!isExpanded) return;
        isExpanded = false;
        hideKeyboard();
        removeViewSafely(popupView);
        if (bubbleView != null) {
            bubbleView.setVisibility(View.VISIBLE);
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && etInput != null) {
                imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void showDismissTarget() {
        if (dismissView == null || dismissVisible) return;
        dismissVisible = true;
        hoveringDismiss = false;
        dismissView.setVisibility(View.VISIBLE);
        dismissView.animate().alpha(1f).setDuration(140L).start();
        updateDismissVisual(false);
    }

    private void hideDismissTarget() {
        if (dismissView == null || !dismissVisible) return;
        dismissVisible = false;
        hoveringDismiss = false;
        dismissView.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction(() -> {
                    if (!dismissVisible && dismissView != null) {
                        dismissView.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void updateDismissProximity(float rawX, float rawY) {
        float dismissCenterX = screenWidth / 2f;
        float dismissCenterY = screenHeight - (navBarHeight + ThemeApplier.dpToPx(themedContext, 28f) + ThemeApplier.dpToPx(themedContext, 48f));
        float dx = rawX - dismissCenterX;
        float dy = rawY - dismissCenterY;
        float radius = ThemeApplier.dpToPx(themedContext, 72f);
        boolean inside = (dx * dx + dy * dy) <= (radius * radius);
        if (inside != hoveringDismiss) {
            hoveringDismiss = inside;
            updateDismissVisual(inside);
        }
    }

    private void updateDismissVisual(boolean active) {
        if (dismissCircle == null) return;
        int fill = active ? 0xFFE53935 : 0xCC1E1E1E;
        int border = active ? 0xFFFFFFFF : 0x66FFFFFF;
        dismissCircle.setBackground(createCircleDrawable(fill, border, ThemeApplier.dpToPxInt(themedContext, 1.5f)));
        float targetScale = active ? 1.22f : 1.0f;
        dismissCircle.animate().scaleX(targetScale).scaleY(targetScale).setDuration(110L).start();
    }

    private void clampBubbleVerticalPosition() {
        int minY = statusBarHeight + edgeMarginPx;
        int maxY = Math.max(minY, screenHeight - navBarHeight - bubbleSizePx - edgeMarginPx);
        bubbleParams.y = Math.max(minY, Math.min(maxY, bubbleParams.y));
    }

    private void clampBubblePosition() {
        clampBubbleVerticalPosition();
        int leftX = edgeMarginPx;
        int rightX = Math.max(leftX, screenWidth - bubbleSizePx - edgeMarginPx);
        bubbleParams.x = (bubbleParams.x + bubbleSizePx / 2 < screenWidth / 2) ? leftX : rightX;
    }

    private void snapBubbleToNearestEdge() {
        if (bubbleView == null || !bubbleView.isAttachedToWindow()) return;
        int startX = bubbleParams.x;
        int leftX = edgeMarginPx;
        int rightX = Math.max(leftX, screenWidth - bubbleSizePx - edgeMarginPx);
        int targetX = (startX + bubbleSizePx / 2 < screenWidth / 2) ? leftX : rightX;

        if (snapAnimator != null) snapAnimator.cancel();
        snapAnimator = ValueAnimator.ofInt(startX, targetX);
        snapAnimator.setDuration(220L);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(anim -> {
            if (bubbleView != null && bubbleView.isAttachedToWindow()) {
                bubbleParams.x = (int) anim.getAnimatedValue();
                try {
                    windowManager.updateViewLayout(bubbleView, bubbleParams);
                } catch (Exception ignored) {
                }
            }
        });
        snapAnimator.start();
    }

    private void bindConversation(@Nullable String convId) {
        if (observedMessagesLiveData != null) {
            observedMessagesLiveData.removeObserver(messagesObserver);
            observedMessagesLiveData = null;
        }
        currentConversationId = convId;
        baseMessages = Collections.emptyList();
        streamingPreview = null;
        if (convId != null && !convId.isEmpty()) {
            observedMessagesLiveData = messageRepository.observeMessages(convId);
            observedMessagesLiveData.observeForever(messagesObserver);
        } else {
            renderMessages();
        }
    }

    private void startNewConversation() {
        stopGeneration();
        bindConversation(null);
        updateExecutionStatus(ExecutionState.IDLE, null);
    }

    private void openFullApp() {
        collapsePopup();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (currentConversationId != null && !currentConversationId.isEmpty()) {
            intent.putExtra(MainActivity.EXTRA_OPEN_CONVERSATION_ID, currentConversationId);
        }
        startActivity(intent);
    }

    private void sendBubbleMessage() {
        if (etInput == null) return;
        String trimmed = etInput.getText() != null ? etInput.getText().toString().trim() : "";
        if (trimmed.isEmpty()) return;
        etInput.setText("");

        if (activeExecutionHandle != null) {
            activeExecutionHandle.cancel();
            activeExecutionHandle = null;
        }

        AiModel model = currentModel != null ? currentModel : modelRepository.getSelectedModelSnapshot();
        AppSettings appSettings = settingsRepository.getSettingsSnapshot();
        boolean deepThinking = appSettings.isDeepThinkingPreferred() && model.isDeepThinkingSupported();

        if (currentConversationId == null || currentConversationId.isEmpty()) {
            String newId = UUID.randomUUID().toString();
            bindConversation(newId);
        }
        final String targetConvId = currentConversationId;

        updateExecutionStatus(ExecutionState.PREPARING, null);
        messageRepository.sendUserMessage(
                targetConvId,
                trimmed,
                model.getId(),
                deepThinking,
                Collections.emptyList(),
                appSettings.isAutoTitleFromFirstMessage(),
                savedUser -> startAiGeneration(targetConvId, trimmed, model, deepThinking)
        );
    }

    private void startAiGeneration(String convId, String latestPrompt, AiModel model, boolean deepThinking) {
        executors.worker().execute(() -> {
            Conversation conversation = conversationRepository.getConversationSync(convId);
            String projectId = conversation != null ? conversation.getProjectId() : "";
            List<Message> history = messageRepository.getMessagesSync(convId);

            if (conversation != null && conversationCompactor != null) {
                conversation = conversationCompactor.compactIfNeededSync(conversation, model, history);
            }

            List<MemoryItem> selectedMemories = memoryRetrievalEngine != null
                    ? memoryRetrievalEngine.selectContextualMemories(latestPrompt, projectId, convId, 750, 10)
                    : memoryRepository.getActiveMemoriesForConversationSync(convId);

            AppSettings currentSettings = settingsRepository.getSettingsSnapshot();
            AgentMode mode = currentSettings.getDefaultAgentMode();
            String workspacePath = workspaceFileManager != null
                    ? workspaceFileManager.getActiveWorkspacePath()
                    : currentSettings.getActiveWorkspacePath();
            List<ToolDefinition> enabledTools = (currentSettings.isChatToolsEnabled() && toolRegistry != null)
                    ? toolRegistry.listDefinitionsForMode(mode, currentSettings)
                    : Collections.emptyList();

            AiRequest request = contextManager.buildRequest(
                    conversation,
                    convId,
                    model,
                    history,
                    selectedMemories,
                    true,
                    deepThinking,
                    null,
                    mode,
                    workspacePath,
                    enabledTools
            );

            final StringBuilder streamText = new StringBuilder();
            final StringBuilder streamThinking = new StringBuilder();
            final long startTime = System.currentTimeMillis();

            executors.postToMain(() -> {
                streamingPreview = new Message(
                        STREAMING_PREVIEW_ID,
                        convId,
                        MessageRole.ASSISTANT,
                        "",
                        "",
                        model.getId(),
                        model.getProvider().getId(),
                        model.getAccessType(),
                        deepThinking,
                        null,
                        false,
                        startTime,
                        startTime,
                        Collections.emptyList()
                );
                renderMessages();
            });

            ToolExecutor.ConfirmationHandler confirmHandler = (executionId, toolName, reason, argsPreview, callback) -> {
                if (callback != null) callback.onResult(false);
            };

            activeExecutionHandle = aiCore.executeAsync(
                    request,
                    projectId,
                    currentSettings.getAgentMaxSteps(),
                    confirmHandler,
                    new AiStreamListener() {
                        @Override
                        public void onStateChanged(ExecutionState state) {
                            executors.postToMain(() -> updateExecutionStatus(state, null));
                        }

                        @Override
                        public void onThinkingDelta(String delta) {
                            if (delta == null) return;
                            streamThinking.append(delta);
                            postStreamPreview(convId, model, deepThinking, streamText.toString(), streamThinking.toString(), startTime);
                        }

                        @Override
                        public void onTextDelta(String delta) {
                            if (delta == null) return;
                            streamText.append(delta);
                            postStreamPreview(convId, model, deepThinking, streamText.toString(), streamThinking.toString(), startTime);
                        }

                        @Override
                        public void onCompleted(AiResponse response) {
                            activeExecutionHandle = null;
                            String rawContent = response.getContent().isEmpty() ? streamText.toString() : response.getContent();
                            String finalContent = AiCore.stripToolTags(rawContent);
                            String finalThinking = response.getThinkingContent().isEmpty() ? streamThinking.toString() : response.getThinkingContent();
                            String usedProviderId = response.getProviderId() != null ? response.getProviderId() : model.getProvider().getId();

                            messageRepository.saveAssistantMessage(
                                    convId,
                                    finalContent,
                                    finalThinking,
                                    model.getId(),
                                    usedProviderId,
                                    model.getAccessType(),
                                    response.getTokenUsage(),
                                    false,
                                    saved -> executors.postToMain(() -> {
                                        streamingPreview = null;
                                        renderMessages();
                                        updateExecutionStatus(ExecutionState.COMPLETED, null);
                                        if (!isExpanded) {
                                            hasUnreadReply = true;
                                            updateUnreadBadge();
                                        }
                                    })
                            );
                        }

                        @Override
                        public void onError(AiError error) {
                            activeExecutionHandle = null;
                            if (error != null && error.getType() == AiError.ErrorType.CANCELLED) {
                                executors.postToMain(() -> {
                                    streamingPreview = null;
                                    renderMessages();
                                    updateExecutionStatus(ExecutionState.CANCELLED, null);
                                });
                                return;
                            }
                            String errMsg = error != null && error.getMessage() != null
                                    ? error.getMessage()
                                    : getString(R.string.execution_state_failed);
                            messageRepository.saveAssistantMessage(
                                    convId,
                                    "**" + model.getProvider().getDisplayName() + " error:** " + errMsg,
                                    streamThinking.toString(),
                                    model.getId(),
                                    model.getProvider().getId(),
                                    model.getAccessType(),
                                    null,
                                    true,
                                    saved -> executors.postToMain(() -> {
                                        streamingPreview = null;
                                        renderMessages();
                                        updateExecutionStatus(ExecutionState.FAILED, errMsg);
                                    })
                            );
                        }
                    },
                    notice -> {
                        if (notice != null && !notice.isEmpty()) {
                            executors.postToMain(() -> Toast.makeText(themedContext, notice, Toast.LENGTH_SHORT).show());
                        }
                    }
            );
        });
    }

    private void postStreamPreview(
            String convId,
            AiModel model,
            boolean deepThinking,
            String content,
            String thinking,
            long startTime
    ) {
        executors.postToMain(() -> {
            if (!convId.equals(currentConversationId)) return;
            streamingPreview = new Message(
                    STREAMING_PREVIEW_ID,
                    convId,
                    MessageRole.ASSISTANT,
                    content,
                    thinking,
                    model.getId(),
                    model.getProvider().getId(),
                    model.getAccessType(),
                    deepThinking,
                    null,
                    false,
                    startTime,
                    System.currentTimeMillis(),
                    Collections.emptyList()
            );
            renderMessages();
        });
    }

    private void stopGeneration() {
        ExecutionHandle handle = activeExecutionHandle;
        if (handle != null) {
            handle.cancel();
            activeExecutionHandle = null;
            streamingPreview = null;
            renderMessages();
            updateExecutionStatus(ExecutionState.CANCELLED, null);
        }
    }

    private void renderMessages() {
        List<Message> combined = new ArrayList<>(baseMessages);
        if (streamingPreview != null && currentConversationId != null
                && currentConversationId.equals(streamingPreview.getConversationId())) {
            combined.add(streamingPreview);
        }
        messageAdapter.submitList(combined);
        boolean hasMsgs = !combined.isEmpty();
        if (rvMessages != null && emptyStateView != null) {
            rvMessages.setVisibility(hasMsgs ? View.VISIBLE : View.GONE);
            emptyStateView.setVisibility(hasMsgs ? View.GONE : View.VISIBLE);
            if (hasMsgs) {
                rvMessages.scrollToPosition(combined.size() - 1);
            }
        }
    }

    private void updateExecutionStatus(ExecutionState state, @Nullable String errorDetail) {
        currentExecutionState = state != null ? state : ExecutionState.IDLE;
        boolean active = currentExecutionState == ExecutionState.PREPARING
                || currentExecutionState == ExecutionState.CONNECTING
                || currentExecutionState == ExecutionState.THINKING
                || currentExecutionState == ExecutionState.STREAMING
                || currentExecutionState == ExecutionState.EXECUTING_TOOL;

        if (btnStop != null) btnStop.setVisibility(active ? View.VISIBLE : View.GONE);
        if (btnSend != null) btnSend.setVisibility(active ? View.GONE : View.VISIBLE);

        if (statusBar != null) {
            boolean showBar = currentExecutionState != ExecutionState.IDLE && currentExecutionState != ExecutionState.COMPLETED;
            statusBar.setVisibility(showBar ? View.VISIBLE : View.GONE);
            if (pbStatus != null) pbStatus.setVisibility(active ? View.VISIBLE : View.GONE);
            if (tvStatus != null && showBar) {
                if (currentExecutionState == ExecutionState.PREPARING) {
                    tvStatus.setText(R.string.execution_state_preparing);
                } else if (currentExecutionState == ExecutionState.CONNECTING) {
                    tvStatus.setText(R.string.execution_state_connecting);
                } else if (currentExecutionState == ExecutionState.THINKING) {
                    tvStatus.setText(R.string.execution_state_thinking);
                } else if (currentExecutionState == ExecutionState.EXECUTING_TOOL) {
                    tvStatus.setText(R.string.execution_state_tool);
                } else if (currentExecutionState == ExecutionState.STREAMING) {
                    tvStatus.setText(R.string.execution_state_streaming);
                } else if (currentExecutionState == ExecutionState.CANCELLED) {
                    tvStatus.setText(R.string.execution_state_stopped);
                } else if (currentExecutionState == ExecutionState.FAILED) {
                    tvStatus.setText(errorDetail != null && !errorDetail.isEmpty()
                            ? errorDetail
                            : getString(R.string.execution_state_failed));
                }
            }
        }
        updateHeaderSubtitle();
    }

    private void updateHeaderSubtitle() {
        if (tvPopupSubtitle == null) return;
        String modelName = currentModel != null ? currentModel.getName() : "AnAI";
        if (currentExecutionState == ExecutionState.STREAMING
                || currentExecutionState == ExecutionState.THINKING
                || currentExecutionState == ExecutionState.CONNECTING) {
            tvPopupSubtitle.setText(modelName + " • " + getString(R.string.execution_state_streaming));
        } else {
            tvPopupSubtitle.setText(modelName);
        }
    }

    private void updateUnreadBadge() {
        if (viewBubbleBadge == null) return;
        viewBubbleBadge.setVisibility(hasUnreadReply ? View.VISIBLE : View.GONE);
    }

    private void applyTheme() {
        if (activePalette == null) return;
        int stroke = ThemeApplier.dpToPxInt(themedContext, 1.2f);

        if (bubbleAvatarCircle != null) {
            GradientDrawable bg = GradientDrawableFactory.createLinearGradient(activePalette, 999f);
            bg.setStroke(ThemeApplier.dpToPxInt(themedContext, 1.5f), activePalette.getOnPrimaryColor());
            bubbleAvatarCircle.setBackground(bg);
        }
        if (ivBubbleAvatar != null) {
            ivBubbleAvatar.setColorFilter(activePalette.getOnPrimaryColor());
        }
        if (viewBubbleBadge != null) {
            viewBubbleBadge.setBackground(createCircleDrawable(
                    ContextCompat.getColor(themedContext, R.color.status_supported),
                    activePalette.getBackgroundColor(),
                    ThemeApplier.dpToPxInt(themedContext, 2f)
            ));
        }

        if (popupCard != null) {
            popupCard.setBackground(GradientDrawableFactory.createSurfaceCard(
                    activePalette.getBackgroundColor(),
                    activePalette.getBorderColor(),
                    stroke,
                    ThemeApplier.dpToPx(themedContext, 22f)
            ));
        }
        if (popupHeaderAvatar != null) {
            popupHeaderAvatar.setBackground(GradientDrawableFactory.createLinearGradient(activePalette, 999f));
        }
        if (ivPopupHeaderIcon != null) {
            ivPopupHeaderIcon.setColorFilter(activePalette.getOnPrimaryColor());
        }
        if (tvPopupTitle != null) tvPopupTitle.setTextColor(activePalette.getTextPrimaryColor());
        if (tvPopupSubtitle != null) tvPopupSubtitle.setTextColor(activePalette.getTextSecondaryColor());
        if (btnNewChat != null) btnNewChat.setColorFilter(activePalette.getTextPrimaryColor());
        if (btnOpenApp != null) btnOpenApp.setColorFilter(activePalette.getTextPrimaryColor());
        if (btnCollapse != null) btnCollapse.setColorFilter(activePalette.getTextPrimaryColor());
        if (btnClose != null) btnClose.setColorFilter(activePalette.getTextSecondaryColor());
        if (headerDivider != null) headerDivider.setBackgroundColor(activePalette.getBorderColor());
        if (composerDivider != null) composerDivider.setBackgroundColor(activePalette.getBorderColor());

        if (tvEmptyTitle != null) tvEmptyTitle.setTextColor(activePalette.getTextPrimaryColor());
        if (tvEmptySub != null) tvEmptySub.setTextColor(activePalette.getTextSecondaryColor());
        if (statusBar != null) {
            statusBar.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(),
                    ThemeApplier.blendWithAlpha(activePalette.getBorderColor(), 110),
                    ThemeApplier.dpToPxInt(themedContext, 0.75f)
            ));
        }
        if (tvStatus != null) tvStatus.setTextColor(activePalette.getPrimaryColor());

        if (etInput != null) {
            etInput.setTextColor(activePalette.getTextPrimaryColor());
            etInput.setHintTextColor(activePalette.getTextSecondaryColor());
        }
        if (btnStop != null) {
            int errColor = ContextCompat.getColor(themedContext, R.color.status_unsupported);
            btnStop.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(errColor, 32), errColor, ThemeApplier.dpToPxInt(themedContext, 1f)
            ));
            btnStop.setColorFilter(errColor);
        }
        if (messageAdapter != null) {
            messageAdapter.setPalette(activePalette);
        }
        updateSendButtonState();
    }

    private void updateSendButtonState() {
        if (btnSend == null || etInput == null || activePalette == null) return;
        String txt = etInput.getText() != null ? etInput.getText().toString().trim() : "";
        boolean canSend = !txt.isEmpty();
        btnSend.setEnabled(canSend);
        if (canSend) {
            btnSend.setBackground(GradientDrawableFactory.createLinearGradient(activePalette, 999f));
            btnSend.setColorFilter(activePalette.getOnPrimaryColor());
            btnSend.setAlpha(1.0f);
        } else {
            btnSend.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(),
                    activePalette.getBorderColor(),
                    ThemeApplier.dpToPxInt(themedContext, 1f)
            ));
            btnSend.setColorFilter(activePalette.getTextSecondaryColor());
            btnSend.setAlpha(0.55f);
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("AnAI", text));
            Toast.makeText(themedContext, R.string.msg_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void removeViewSafely(@Nullable View view) {
        if (view != null && windowManager != null && view.isAttachedToWindow()) {
            try {
                windowManager.removeView(view);
            } catch (Exception ignored) {
            }
        }
    }

    private static GradientDrawable createCircleDrawable(int fillColor, int strokeColor, int strokeWidthPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fillColor);
        if (strokeWidthPx > 0) {
            d.setStroke(strokeWidthPx, strokeColor);
        }
        return d;
    }
}
