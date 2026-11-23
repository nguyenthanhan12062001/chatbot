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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatai.an.R;
import com.chatai.an.ai.core.ChatSessionCoordinator;
import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.app.AppContainer;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.app.MainActivity;
import com.chatai.an.core.AppLogger;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.IntegrationStatus;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.ThinkingState;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;
import com.chatai.an.presentation.chat.MessageAdapter;
import com.chatai.an.presentation.settings.IntegrationsHubActivity;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Messenger-style floating Chat Bubble ("Chat Head") service synchronized 1:1 with
 * MainActivity through the shared {@link ChatSessionCoordinator}.
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
    private ChatSessionCoordinator coordinator;
    private ShizukuIntegrationManager shizukuManager;
    private ModelRepository modelRepository;
    private ThemeRepository themeRepository;
    private SettingsRepository settingsRepository;

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
    private LinearLayout modeToggleGroup;
    private TextView btnModeChat;
    private TextView btnModeCode;
    private LinearLayout btnShizuku;
    private View viewShizukuDot;
    private TextView tvShizuku;
    private LinearLayout btnThinking;
    private ImageView ivThinking;
    private TextView tvThinking;
    private RecyclerView rvMessages;
    private View emptyStateView;
    private TextView tvEmptyTitle;
    private TextView tvEmptySub;
    private View statusBar;
    private ProgressBar pbStatus;
    private TextView tvStatus;
    private LinearLayout queueContainer;
    private TextView tvQueueHeader;
    private LinearLayout queueItems;
    private LinearLayout slashPopup;
    private View composerDivider;
    private EditText etInput;
    private ImageButton btnStop;
    private ImageButton btnSend;
    private WindowManager.LayoutParams popupParams;

    private MessageAdapter messageAdapter;
    private ThemePalette activePalette;
    private AiModel currentModel;
    private AgentMode currentAgentMode = AgentMode.CHAT;
    private ThinkingState currentThinkingState = ThinkingState.DISABLED;
    private ExecutionState currentExecutionState = ExecutionState.IDLE;
    private ShizukuIntegrationManager.ShizukuState currentShizukuState;
    private List<Message> currentMessages = Collections.emptyList();

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
        currentMessages = list != null ? list : Collections.emptyList();
        renderMessages();
    };

    private final Observer<AgentMode> modeObserver = mode -> {
        currentAgentMode = mode != null ? mode : AgentMode.CHAT;
        if (messageAdapter != null) {
            messageAdapter.setAgentMode(currentAgentMode);
        }
        styleModeToggle();
    };

    private final Observer<ThinkingState> thinkingObserver = state -> {
        currentThinkingState = state != null ? state : ThinkingState.DISABLED;
        styleThinkingPill();
    };

    private final Observer<ExecutionState> executionStateObserver = state -> {
        ExecutionState prev = currentExecutionState;
        updateExecutionStatus(state, coordinator != null ? coordinator.getExecutionErrorDetail().getValue() : null);
        if (state == ExecutionState.COMPLETED && prev != ExecutionState.COMPLETED && !isExpanded) {
            hasUnreadReply = true;
            updateUnreadBadge();
        }
    };

    private final Observer<List<ChatSessionCoordinator.QueuedMessage>> queueObserver = this::renderFollowUpQueue;

    private final Observer<ShizukuIntegrationManager.ShizukuState> shizukuObserver = state -> {
        currentShizukuState = state;
        styleShizukuPill();
    };

    private final Observer<String> noticeObserver = notice -> {
        if (notice != null && !notice.isEmpty() && isExpanded) {
            Toast.makeText(themedContext, notice, Toast.LENGTH_SHORT).show();
            coordinator.clearExecutionNotice();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;

        ChatAiApplication app = (ChatAiApplication) getApplication();
        appContainer = app.getAppContainer();
        coordinator = appContainer.getChatSessionCoordinator();
        shizukuManager = appContainer.getShizukuIntegrationManager();
        modelRepository = appContainer.getModelRepository();
        themeRepository = appContainer.getThemeRepository();
        settingsRepository = appContainer.getSettingsRepository();

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
        coordinator.getSelectedModel().observeForever(modelObserver);
        coordinator.getMessages().observeForever(messagesObserver);
        coordinator.getAgentMode().observeForever(modeObserver);
        coordinator.getThinkingState().observeForever(thinkingObserver);
        coordinator.getExecutionState().observeForever(executionStateObserver);
        coordinator.getFollowUpQueue().observeForever(queueObserver);
        coordinator.getExecutionNotice().observeForever(noticeObserver);
        shizukuManager.observeState().observeForever(shizukuObserver);
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
            String currentActiveId = coordinator.getActiveConversationId().getValue();
            if (incomingConvId != null && !incomingConvId.isEmpty()
                    && !incomingConvId.equals(currentActiveId)
                    && !coordinator.isAiBusy()) {
                coordinator.openConversation(incomingConvId);
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
        removeObserverSafely(themeRepository.observeActiveTheme(), themeObserver);
        removeObserverSafely(coordinator.getSelectedModel(), modelObserver);
        removeObserverSafely(coordinator.getMessages(), messagesObserver);
        removeObserverSafely(coordinator.getAgentMode(), modeObserver);
        removeObserverSafely(coordinator.getThinkingState(), thinkingObserver);
        removeObserverSafely(coordinator.getExecutionState(), executionStateObserver);
        removeObserverSafely(coordinator.getFollowUpQueue(), queueObserver);
        removeObserverSafely(coordinator.getExecutionNotice(), noticeObserver);
        removeObserverSafely(shizukuManager.observeState(), shizukuObserver);

        removeViewSafely(popupView);
        removeViewSafely(bubbleView);
        removeViewSafely(dismissView);
        super.onDestroy();
    }

    private <T> void removeObserverSafely(@Nullable androidx.lifecycle.LiveData<T> liveData, Observer<T> observer) {
        if (liveData == null || observer == null) return;
        try {
            liveData.removeObserver(observer);
        } catch (Exception ignored) {
        }
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

        modeToggleGroup = popupView.findViewById(R.id.bubble_mode_toggle);
        btnModeChat = popupView.findViewById(R.id.btn_bubble_mode_chat);
        btnModeCode = popupView.findViewById(R.id.btn_bubble_mode_code);
        btnShizuku = popupView.findViewById(R.id.btn_bubble_shizuku);
        viewShizukuDot = popupView.findViewById(R.id.view_bubble_shizuku_dot);
        tvShizuku = popupView.findViewById(R.id.tv_bubble_shizuku);
        btnThinking = popupView.findViewById(R.id.btn_bubble_thinking);
        ivThinking = popupView.findViewById(R.id.iv_bubble_thinking);
        tvThinking = popupView.findViewById(R.id.tv_bubble_thinking);

        rvMessages = popupView.findViewById(R.id.rv_bubble_messages);
        emptyStateView = popupView.findViewById(R.id.bubble_empty_state);
        tvEmptyTitle = popupView.findViewById(R.id.tv_bubble_empty_title);
        tvEmptySub = popupView.findViewById(R.id.tv_bubble_empty_sub);
        statusBar = popupView.findViewById(R.id.bubble_status_bar);
        pbStatus = popupView.findViewById(R.id.pb_bubble_status);
        tvStatus = popupView.findViewById(R.id.tv_bubble_status);
        queueContainer = popupView.findViewById(R.id.ll_bubble_queue_container);
        tvQueueHeader = popupView.findViewById(R.id.tv_bubble_queue_header);
        queueItems = popupView.findViewById(R.id.ll_bubble_queue_items);
        slashPopup = popupView.findViewById(R.id.ll_bubble_slash_popup);
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
                if (message != null) {
                    coordinator.deleteMessage(message.getId());
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

            @Override
            public void onRegenerateMessage(Message message) {
                coordinator.regenerateMessage(message);
            }

            @Override
            public void onHandoverToCode(Message message) {
                coordinator.handoverToCode(null);
            }

            @Override
            public void onFollowUpClicked(String suggestion) {
                coordinator.sendOrQueueMessage(suggestion);
            }

            @Override
            public void onApproveToolCall(String executionId, boolean allowForSession) {
                coordinator.respondToToolConfirmation(executionId, true, allowForSession);
            }

            @Override
            public void onDenyToolCall(String executionId) {
                coordinator.respondToToolConfirmation(executionId, false, false);
            }

            @Override
            public void onCopyToolOutput(String text) {
                copyToClipboard(text);
            }

            @Override
            public void onPreviewImage(String title, String imageSource) {
                if (imageSource != null && (imageSource.startsWith("http://") || imageSource.startsWith("https://"))) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(imageSource));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return;
                    } catch (Exception ignored) {
                    }
                }
                openFullApp();
            }

            @Override
            public void onOpenSourceUrl(String urlOrPath) {
                if (urlOrPath == null || urlOrPath.trim().isEmpty()) return;
                String target = urlOrPath.trim();
                if (target.startsWith("http://") || target.startsWith("https://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return;
                    } catch (Exception ignored) {
                    }
                }
                copyToClipboard(target);
            }
        });

        LinearLayoutManager lm = new LinearLayoutManager(themedContext);
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(messageAdapter);

        btnNewChat.setOnClickListener(v -> coordinator.startNewChat());
        btnOpenApp.setOnClickListener(v -> openFullApp());
        btnCollapse.setOnClickListener(v -> collapsePopup());
        btnClose.setOnClickListener(v -> {
            settingsRepository.setBubbleEnabled(false);
            Toast.makeText(themedContext, R.string.bubble_stopped_notice, Toast.LENGTH_SHORT).show();
            stopSelf();
        });

        btnModeChat.setOnClickListener(v -> coordinator.setAgentMode(AgentMode.CHAT));
        btnModeCode.setOnClickListener(v -> coordinator.setAgentMode(AgentMode.CODING));
        btnThinking.setOnClickListener(v -> coordinator.toggleDeepThinking());
        btnShizuku.setOnClickListener(v -> handleBubbleShizukuTap());

        btnStop.setOnClickListener(v -> coordinator.stopGeneration());
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
                updateBubbleSlashPopup(s != null ? s.toString() : "");
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

    private void handleBubbleShizukuTap() {
        ShizukuIntegrationManager.QuickActionOutcome outcome = shizukuManager.requestOrConnect(themedContext);
        if (outcome == ShizukuIntegrationManager.QuickActionOutcome.SHOW_RATIONALE_NEEDED
                || outcome == ShizukuIntegrationManager.QuickActionOutcome.ALREADY_ACTIVE) {
            collapsePopup();
            Intent hubIntent = new Intent(this, IntegrationsHubActivity.class);
            hubIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(hubIntent);
        } else if (outcome == ShizukuIntegrationManager.QuickActionOutcome.PERMISSION_REQUESTED) {
            Toast.makeText(themedContext, R.string.hub_shizuku_perm, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateBubbleSlashPopup(String rawInput) {
        if (slashPopup == null) return;
        String trimmed = rawInput != null ? rawInput.trim() : "";
        if (!trimmed.startsWith("/") || trimmed.contains("\n") || trimmed.contains(" ")) {
            slashPopup.setVisibility(View.GONE);
            slashPopup.removeAllViews();
            return;
        }
        String query = trimmed.toLowerCase(Locale.ROOT);
        List<String[]> cmds = new ArrayList<>();
        cmds.add(new String[]{"/code", getString(R.string.slash_cmd_code_desc)});
        cmds.add(new String[]{"/chat", getString(R.string.slash_cmd_chat_desc)});
        cmds.add(new String[]{"/shizuku", getString(R.string.slash_cmd_shizuku_desc)});
        cmds.add(new String[]{"/compact", getString(R.string.context_compact_now)});
        cmds.add(new String[]{"/new", getString(R.string.slash_cmd_new_desc)});
        cmds.add(new String[]{"/clear", getString(R.string.slash_cmd_clear_desc)});

        slashPopup.removeAllViews();
        int matches = 0;
        for (String[] cmd : cmds) {
            if (!cmd[0].startsWith(query)) continue;
            matches++;
            LinearLayout row = new LinearLayout(themedContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(
                    ThemeApplier.dpToPxInt(themedContext, 8f),
                    ThemeApplier.dpToPxInt(themedContext, 6f),
                    ThemeApplier.dpToPxInt(themedContext, 8f),
                    ThemeApplier.dpToPxInt(themedContext, 6f)
            );
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);

            TextView tvCmd = new TextView(themedContext);
            tvCmd.setText(cmd[0]);
            tvCmd.setTextSize(11.5f);
            tvCmd.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            if (activePalette != null) tvCmd.setTextColor(activePalette.getPrimaryColor());

            TextView tvDesc = new TextView(themedContext);
            tvDesc.setText(" · " + cmd[1]);
            tvDesc.setTextSize(11f);
            if (activePalette != null) tvDesc.setTextColor(activePalette.getTextSecondaryColor());

            row.addView(tvCmd);
            row.addView(tvDesc);
            final String chosen = cmd[0];
            row.setOnClickListener(v -> {
                slashPopup.setVisibility(View.GONE);
                etInput.setText("");
                if ("/shizuku".equals(chosen)) {
                    handleBubbleShizukuTap();
                } else {
                    coordinator.sendOrQueueMessage(chosen);
                }
            });
            slashPopup.addView(row);
        }
        slashPopup.setVisibility(matches > 0 ? View.VISIBLE : View.GONE);
    }

    private void renderFollowUpQueue(List<ChatSessionCoordinator.QueuedMessage> queue) {
        if (queueContainer == null || queueItems == null) return;
        queueItems.removeAllViews();
        if (queue == null || queue.isEmpty()) {
            queueContainer.setVisibility(View.GONE);
            return;
        }
        queueContainer.setVisibility(View.VISIBLE);
        tvQueueHeader.setText(getString(R.string.queue_header_title, queue.size()));
        for (ChatSessionCoordinator.QueuedMessage item : queue) {
            View row = themedInflater.inflate(R.layout.item_queued_message, queueItems, false);
            TextView tvText = row.findViewById(R.id.tv_queued_text);
            TextView btnSendNow = row.findViewById(R.id.btn_queued_send_now);
            View btnRemove = row.findViewById(R.id.btn_queued_remove);

            tvText.setText(!item.text.isEmpty() ? item.text : "(" + item.attachments.size() + " attachment)");
            if (activePalette != null) {
                row.setBackground(GradientDrawableFactory.createSurfaceCard(
                        activePalette.getSurfaceColor(),
                        ThemeApplier.blendWithAlpha(activePalette.getBorderColor(), 110),
                        ThemeApplier.dpToPxInt(themedContext, 0.75f),
                        ThemeApplier.dpToPx(themedContext, 10f)
                ));
                tvText.setTextColor(activePalette.getTextPrimaryColor());
                btnSendNow.setBackground(GradientDrawableFactory.createPill(
                        ThemeApplier.blendWithAlpha(activePalette.getPrimaryColor(), 24),
                        activePalette.getPrimaryColor(),
                        ThemeApplier.dpToPxInt(themedContext, 1f)
                ));
                btnSendNow.setTextColor(activePalette.getPrimaryColor());
            }
            btnSendNow.setOnClickListener(v -> coordinator.sendQueuedFollowUpNow(item.id));
            btnRemove.setOnClickListener(v -> coordinator.removeQueuedFollowUp(item.id));
            queueItems.addView(row);
        }
    }

    private void updatePopupDimensions() {
        int maxPopupWidth = ThemeApplier.dpToPxInt(themedContext, 440f);
        popupParams.width = Math.min((int) (screenWidth * 0.94f), maxPopupWidth);
        popupParams.y = statusBarHeight + ThemeApplier.dpToPxInt(themedContext, 12f);

        int desiredCardHeight = Math.min(
                ThemeApplier.dpToPxInt(themedContext, 500f),
                Math.max(ThemeApplier.dpToPxInt(themedContext, 320f), (int) (screenHeight * 0.62f))
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
        shizukuManager.refreshState();

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

    private void openFullApp() {
        collapsePopup();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String activeConvId = coordinator.getActiveConversationId().getValue();
        if (activeConvId != null && !activeConvId.isEmpty()) {
            intent.putExtra(MainActivity.EXTRA_OPEN_CONVERSATION_ID, activeConvId);
        }
        startActivity(intent);
    }

    private void sendBubbleMessage() {
        if (etInput == null) return;
        String trimmed = etInput.getText() != null ? etInput.getText().toString().trim() : "";
        if (trimmed.isEmpty()) return;
        etInput.setText("");
        if ("/shizuku".equalsIgnoreCase(trimmed)) {
            handleBubbleShizukuTap();
            return;
        }
        coordinator.sendOrQueueMessage(trimmed);
    }

    private void renderMessages() {
        if (messageAdapter == null) return;
        messageAdapter.submitList(currentMessages);
        boolean hasMsgs = currentMessages != null && !currentMessages.isEmpty();
        if (rvMessages != null && emptyStateView != null) {
            rvMessages.setVisibility(hasMsgs ? View.VISIBLE : View.GONE);
            emptyStateView.setVisibility(hasMsgs ? View.GONE : View.VISIBLE);
            if (hasMsgs) {
                rvMessages.scrollToPosition(currentMessages.size() - 1);
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

        if (messageAdapter != null) messageAdapter.setAiBusy(active);
        if (btnStop != null) btnStop.setVisibility(active ? View.VISIBLE : View.GONE);
        if (btnSend != null) {
            btnSend.setVisibility(View.VISIBLE);
            btnSend.setContentDescription(getString(active ? R.string.cd_queue_send : R.string.cd_send));
        }
        if (etInput != null) {
            etInput.setHint(active ? R.string.composer_queue_hint : R.string.bubble_input_hint);
        }

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
                || currentExecutionState == ExecutionState.CONNECTING
                || currentExecutionState == ExecutionState.EXECUTING_TOOL) {
            tvPopupSubtitle.setText(modelName + " • " + getString(R.string.execution_state_streaming));
        } else {
            tvPopupSubtitle.setText(modelName);
        }
    }

    private void updateUnreadBadge() {
        if (viewBubbleBadge == null) return;
        viewBubbleBadge.setVisibility(hasUnreadReply ? View.VISIBLE : View.GONE);
    }

    private void styleModeToggle() {
        if (activePalette == null || modeToggleGroup == null) return;
        int stroke = ThemeApplier.dpToPxInt(themedContext, 1f);
        modeToggleGroup.setBackground(GradientDrawableFactory.createPill(
                activePalette.getSurfaceVariantColor(),
                activePalette.getBorderColor(),
                stroke
        ));
        if (currentAgentMode == AgentMode.CODING) {
            btnModeCode.setBackground(GradientDrawableFactory.createLinearGradient(activePalette, 999f));
            btnModeCode.setTextColor(activePalette.getOnPrimaryColor());
            btnModeChat.setBackground(null);
            btnModeChat.setTextColor(activePalette.getTextSecondaryColor());
        } else {
            btnModeChat.setBackground(GradientDrawableFactory.createLinearGradient(activePalette, 999f));
            btnModeChat.setTextColor(activePalette.getOnPrimaryColor());
            btnModeCode.setBackground(null);
            btnModeCode.setTextColor(activePalette.getTextSecondaryColor());
        }
    }

    private void styleShizukuPill() {
        if (tvShizuku == null || btnShizuku == null) return;
        ShizukuIntegrationManager.ShizukuState s = currentShizukuState != null ? currentShizukuState : shizukuManager.inspectState();
        tvShizuku.setText(shizukuManager.getShortStatusBadge());

        int supported = ContextCompat.getColor(themedContext, R.color.status_supported);
        int pending = ContextCompat.getColor(themedContext, R.color.status_unknown);
        int unsupported = ContextCompat.getColor(themedContext, R.color.status_unsupported);
        int neutral = activePalette != null ? activePalette.getTextSecondaryColor() : 0xFF6B5B53;

        int stateColor = neutral;
        if (s.status == IntegrationStatus.ACTIVE || s.status == IntegrationStatus.CONNECTED) {
            stateColor = supported;
        } else if (s.status == IntegrationStatus.PERMISSION_REQUIRED) {
            stateColor = pending;
        } else if (s.status == IntegrationStatus.NOT_CONNECTED || s.status == IntegrationStatus.ERROR) {
            stateColor = unsupported;
        }

        viewShizukuDot.setBackground(GradientDrawableFactory.createPill(stateColor, 0, 0));
        if (activePalette != null) {
            btnShizuku.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(stateColor, 20),
                    ThemeApplier.blendWithAlpha(stateColor, 90),
                    ThemeApplier.dpToPxInt(themedContext, 1f)
            ));
            tvShizuku.setTextColor(activePalette.getTextPrimaryColor());
        }
    }

    private void styleThinkingPill() {
        if (activePalette == null || btnThinking == null) return;
        int stroke = ThemeApplier.dpToPxInt(themedContext, 1f);
        if (currentThinkingState == ThinkingState.ENABLED) {
            btnThinking.setBackground(GradientDrawableFactory.createLinearGradient(activePalette, 999f));
            ivThinking.setColorFilter(activePalette.getOnPrimaryColor());
            tvThinking.setTextColor(activePalette.getOnPrimaryColor());
            btnThinking.setAlpha(1.0f);
        } else if (currentThinkingState == ThinkingState.UNSUPPORTED) {
            btnThinking.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(), activePalette.getBorderColor(), stroke
            ));
            ivThinking.setColorFilter(activePalette.getTextSecondaryColor());
            tvThinking.setTextColor(activePalette.getTextSecondaryColor());
            btnThinking.setAlpha(0.45f);
        } else {
            btnThinking.setBackground(GradientDrawableFactory.createPill(
                    activePalette.getSurfaceVariantColor(), activePalette.getBorderColor(), stroke
            ));
            ivThinking.setColorFilter(activePalette.getTextPrimaryColor());
            tvThinking.setTextColor(activePalette.getTextPrimaryColor());
            btnThinking.setAlpha(1.0f);
        }
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

        if (queueContainer != null) {
            queueContainer.setBackground(GradientDrawableFactory.createSurfaceCard(
                    activePalette.getSurfaceVariantColor(),
                    activePalette.getBorderColor(),
                    ThemeApplier.dpToPxInt(themedContext, 1f),
                    ThemeApplier.dpToPx(themedContext, 12f)
            ));
        }
        if (tvQueueHeader != null) tvQueueHeader.setTextColor(activePalette.getTextPrimaryColor());
        if (slashPopup != null) {
            slashPopup.setBackground(GradientDrawableFactory.createSurfaceCard(
                    activePalette.getSurfaceColor(),
                    activePalette.getBorderColor(),
                    ThemeApplier.dpToPxInt(themedContext, 1f),
                    ThemeApplier.dpToPx(themedContext, 12f)
            ));
        }

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
        styleModeToggle();
        styleShizukuPill();
        styleThinkingPill();
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
