package com.chatai.an;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.core.ChatSessionCoordinator;
import com.chatai.an.ai.core.FollowUpSuggestionHelper;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.app.MainActivity;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ModelPricingType;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.model.ThemePreset;
import com.chatai.an.domain.model.TokenUsage;
import com.chatai.an.domain.model.ToolCallRecord;
import com.chatai.an.domain.repository.ConversationRepository;
import com.chatai.an.domain.repository.MessageRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.presentation.chat.MessageAdapter;
import com.chatai.an.theme.ThemePresetCatalog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = ChatAiApplication.class, qualifiers = "vi-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ChatExperienceOptimizationTest {

    private static void layoutActivityAtPhoneViewport(MainActivity activity) {
        View root = activity.findViewById(R.id.main_root);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY);
        root.measure(widthSpec, heightSpec);
        root.layout(0, 0, 720, 1600);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    @Test
    public void modelSelector_keepsFixedHeightAndWidthAcrossLongNamesAndBadges() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ModelRepository modelRepo = app.getAppContainer().getModelRepository();
        ChatSessionCoordinator coordinator = app.getAppContainer().getChatSessionCoordinator();

        Map<ModelCapability, CapabilityStatus> caps = new EnumMap<>(ModelCapability.class);
        caps.put(ModelCapability.TEXT, CapabilityStatus.SUPPORTED);
        String longModelId = "openrouter/meta-llama/llama-3.3-70b-instruct:free";
        AiModel longModel = new AiModel(
                longModelId,
                "OpenRouter Meta Llama 3.3 70B Instruct Extended Reasoning Preview (Free)",
                ModelProvider.OPENROUTER_FREE,
                ModelPricingType.FREE,
                AccessType.FREE,
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                "Long name stress test",
                "128k",
                128000,
                8192,
                true,
                "dynamic",
                System.currentTimeMillis(),
                caps,
                caps
        );
        modelRepo.upsertDynamicModels(ModelProvider.OPENROUTER_FREE, Collections.singletonList(longModel));

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        layoutActivityAtPhoneViewport(activity);

        View btnSelector = activity.findViewById(R.id.btn_model_selector);
        TextView tvModel = activity.findViewById(R.id.tv_selected_model);
        TextView tvBadge = activity.findViewById(R.id.tv_model_pricing_badge);

        // 1. Short free model
        coordinator.selectModel("pollinations/openai");
        layoutActivityAtPhoneViewport(activity);
        int shortWidth = btnSelector.getWidth();
        int shortHeight = btnSelector.getHeight();
        assertTrue(shortWidth > 0);
        assertTrue(shortHeight > 0);
        assertTrue("Expected single line for short model name", tvModel.getLineCount() <= 1);
        assertTrue("Expected single line for badge", tvBadge.getLineCount() <= 1);

        // 2. Super-long model name (must ellipsize cleanly without resizing selector)
        coordinator.selectModel(longModelId);
        layoutActivityAtPhoneViewport(activity);
        assertEquals("Long model name must not widen selector", shortWidth, btnSelector.getWidth());
        assertEquals("Long model name must not heighten selector", shortHeight, btnSelector.getHeight());
        assertTrue("Long model name must stay on 1 line", tvModel.getLineCount() <= 1);
        assertTrue("Badge must stay on 1 line", tvBadge.getLineCount() <= 1);

        // 3. Switch to API Key model ("gemini-2.5-flash")
        coordinator.selectModel("gemini-2.5-flash");
        layoutActivityAtPhoneViewport(activity);
        assertEquals("API Key badge must not alter selector width", shortWidth, btnSelector.getWidth());
        assertEquals("API Key badge must not alter selector height", shortHeight, btnSelector.getHeight());
        assertTrue("Badge text must remain single-line", tvBadge.getLineCount() <= 1);

        controller.pause().stop().destroy();
    }

    @Test
    public void chatUiCleanup_hidesNoisyMetadataUsesOverflowMenuAndHorizontalFollowUps() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ModelRepository modelRepo = app.getAppContainer().getModelRepository();
        MessageAdapter adapter = new MessageAdapter(modelRepo, new MessageAdapter.OnMessageActionListener() {
            @Override public void onCopyMessage(Message message) {}
            @Override public void onEditMessage(Message message) {}
            @Override public void onDeleteMessage(Message message) {}
            @Override public void onCopyCodeBlock(String code) {}
            @Override public void onPreviewAttachment(com.chatai.an.domain.model.Attachment attachment) {}
        });
        adapter.setPalette(ThemePresetCatalog.getLightPalette(ThemePreset.SUNSET_ORANGE));

        Message assistantMsg = new Message(
                "msg-clean-1",
                "conv-clean",
                MessageRole.ASSISTANT,
                "Đây là câu trả lời gọn gàng.",
                "",
                "gemini-2.5-flash",
                "google",
                AccessType.PERSONAL_API,
                true,
                new TokenUsage(45, 18, 63),
                false,
                1000L,
                1000L,
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("Bước tiếp theo rất dài cần được thu gọn trong 1 dòng duy nhất", "Kiểm tra lại cấu hình")
        );
        adapter.submitList(Collections.singletonList(assistantMsg));

        Context themedCtx = new androidx.appcompat.view.ContextThemeWrapper(app, R.style.Theme_ChatAI);
        FrameLayout parent = new FrameLayout(themedCtx);
        MessageAdapter.MessageViewHolder vh = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(vh, 0);

        // Inline metadata badges & inline token text are hidden to keep bubbles spacious
        assertEquals(View.GONE, vh.itemView.findViewById(R.id.tv_message_access_badge).getVisibility());
        assertEquals(View.GONE, vh.itemView.findViewById(R.id.tv_message_deep_thinking).getVisibility());
        assertEquals(View.GONE, vh.itemView.findViewById(R.id.tv_message_tokens).getVisibility());

        // Compact copy + overflow menu button
        assertEquals(View.VISIBLE, vh.itemView.findViewById(R.id.btn_msg_copy).getVisibility());
        assertEquals(View.VISIBLE, vh.itemView.findViewById(R.id.btn_msg_more).getVisibility());

        // Follow-ups live inside HorizontalScrollView on a single horizontal row
        HorizontalScrollView hsv = vh.itemView.findViewById(R.id.hsv_follow_up_chips);
        LinearLayout followUpContainer = vh.itemView.findViewById(R.id.ll_follow_up_chips_container);
        assertEquals(View.VISIBLE, hsv.getVisibility());
        assertEquals(LinearLayout.HORIZONTAL, followUpContainer.getOrientation());
        assertEquals(2, followUpContainer.getChildCount());
        TextView firstChip = (TextView) followUpContainer.getChildAt(0);
        assertEquals(1, firstChip.getMaxLines());

        // Streaming placeholder hides overflow menu until completion
        Message streamingMsg = new Message(
                ChatSessionCoordinator.STREAMING_MESSAGE_ID,
                "conv-clean",
                MessageRole.ASSISTANT,
                "Đang trả lời...",
                "",
                "gemini-2.5-flash",
                "google",
                AccessType.PERSONAL_API,
                false,
                null,
                false,
                2000L,
                2000L,
                Collections.emptyList()
        );
        adapter.submitList(Collections.singletonList(streamingMsg));
        adapter.onBindViewHolder(vh, 0);
        assertEquals(View.GONE, vh.itemView.findViewById(R.id.btn_msg_more).getVisibility());
    }

    @Test
    public void messageAdapter_usesIncrementalUpdatesForStreamingTailWithoutFullReset() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ModelRepository modelRepo = app.getAppContainer().getModelRepository();
        MessageAdapter adapter = new MessageAdapter(modelRepo, null);

        List<Message> initial = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            initial.add(new Message(
                    "m-" + i,
                    "conv-diff",
                    i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                    "Message #" + i,
                    "",
                    "pollinations/openai",
                    "pollinations",
                    AccessType.FREE,
                    false,
                    null,
                    false,
                    1000L + i,
                    1000L + i,
                    Collections.emptyList()
            ));
        }
        initial.add(new Message(
                ChatSessionCoordinator.STREAMING_MESSAGE_ID,
                "conv-diff",
                MessageRole.ASSISTANT,
                "First chunk",
                "",
                "pollinations/openai",
                "pollinations",
                AccessType.FREE,
                false,
                null,
                false,
                2000L,
                2000L,
                Collections.emptyList()
        ));
        adapter.submitList(initial);

        AtomicInteger fullResetCount = new AtomicInteger(0);
        AtomicInteger changedPos = new AtomicInteger(-1);
        AtomicInteger changedCount = new AtomicInteger(0);
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                fullResetCount.incrementAndGet();
            }

            @Override
            public void onItemRangeChanged(int positionStart, int itemCount) {
                changedPos.set(positionStart);
                changedCount.set(itemCount);
            }
        });

        List<Message> updated = new ArrayList<>(initial.subList(0, 4));
        updated.add(new Message(
                ChatSessionCoordinator.STREAMING_MESSAGE_ID,
                "conv-diff",
                MessageRole.ASSISTANT,
                "First chunk + streaming token delta",
                "",
                "pollinations/openai",
                "pollinations",
                AccessType.FREE,
                false,
                null,
                false,
                2000L,
                2050L,
                Collections.emptyList()
        ));
        adapter.submitList(updated);

        assertEquals("Streaming tail update must NOT call notifyDataSetChanged()", 0, fullResetCount.get());
        assertEquals(4, changedPos.get());
        assertEquals(1, changedCount.get());
    }

    @Test
    public void streamingScroll_pausesAutoScrollOnScrollUpShowsNewMessagesPillAndResumesAtBottom() throws Exception {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ConversationRepository convRepo = app.getAppContainer().getConversationRepository();
        MessageRepository msgRepo = app.getAppContainer().getMessageRepository();
        ChatSessionCoordinator coordinator = app.getAppContainer().getChatSessionCoordinator();

        final String[] createdId = new String[1];
        convRepo.createConversation("Scroll Test Session", "pollinations/openai", c -> createdId[0] = c.getId());
        awaitCondition(() -> createdId[0] != null);
        String targetConvId = createdId[0];

        String longParagraph = "Đây là đoạn nội dung dài để kiểm tra cuộn danh sách tin nhắn trong lúc AI đang phản hồi. "
                + "Người dùng cần đọc lại lịch sử hội thoại phía trên mượt mà mà không bị kéo xuống cuối màn hình.";
        for (int i = 0; i < 12; i++) {
            final boolean[] saved = new boolean[1];
            if (i % 2 == 0) {
                msgRepo.sendUserMessage(targetConvId, "Câu hỏi số " + i + "\n" + longParagraph,
                        "pollinations/openai", false, Collections.emptyList(), false, m -> saved[0] = true);
            } else {
                msgRepo.saveAssistantMessage(targetConvId, "Phản hồi số " + i + "\n" + longParagraph + "\n" + longParagraph,
                        "", "pollinations/openai", "pollinations", AccessType.FREE, null, false, m -> saved[0] = true);
            }
            awaitCondition(() -> saved[0]);
        }

        coordinator.openConversation(targetConvId);

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        RecyclerView rv = activity.findViewById(R.id.rv_messages);
        TextView btnNewMessages = activity.findViewById(R.id.btn_scroll_to_bottom);

        awaitCondition(() -> rv.getAdapter() != null && rv.getAdapter().getItemCount() == 12);
        layoutActivityAtPhoneViewport(activity);
        layoutActivityAtPhoneViewport(activity);

        assertTrue("Conversation should overflow viewport height so scrolling works", rv.canScrollVertically(-1));
        assertEquals(View.GONE, btnNewMessages.getVisibility());

        // Simulate user scrolling UP while reading history
        rv.scrollBy(0, -600);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        int offsetAfterScrollUp = rv.computeVerticalScrollOffset();
        assertTrue("RecyclerView must allow scrolling up", rv.canScrollVertically(1));

        // Simulate new streaming/incoming assistant content arriving below
        final boolean[] appended = new boolean[1];
        msgRepo.saveAssistantMessage(targetConvId, "Tin nhắn mới đang stream phía dưới...", "",
                "pollinations/openai", "pollinations", AccessType.FREE, null, false, m -> appended[0] = true);
        awaitCondition(() -> appended[0]);

        awaitCondition(() -> rv.getAdapter() != null && rv.getAdapter().getItemCount() == 13);
        layoutActivityAtPhoneViewport(activity);

        // Reading position stays paused (not snapped to bottom) and floating "↓ Tin nhắn mới" pill is visible
        assertTrue("Must still have content below instead of locking/snapping to bottom", rv.canScrollVertically(1));
        assertEquals(offsetAfterScrollUp, rv.computeVerticalScrollOffset());
        assertEquals(View.VISIBLE, btnNewMessages.getVisibility());
        assertEquals(activity.getString(R.string.scroll_new_messages), btnNewMessages.getText().toString());

        // Tapping "↓ Tin nhắn mới" scrolls to bottom and hides pill
        btnNewMessages.performClick();
        layoutActivityAtPhoneViewport(activity);
        assertFalse("Clicking pill should jump to bottom", rv.canScrollVertically(1));
        assertEquals(View.GONE, btnNewMessages.getVisibility());

        // Scroll up again, then manually scroll back down to bottom -> auto-scroll re-enables and pill hides
        rv.scrollBy(0, -500);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, btnNewMessages.getVisibility());

        rv.scrollBy(0, 10000);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(rv.canScrollVertically(1));
        assertEquals(View.GONE, btnNewMessages.getVisibility());

        controller.pause().stop().destroy();
    }

    @Test
    public void aiPresentationAndTranslation_enforcesConcisenessAndSuppressesTranslationFollowUps() {
        ContextManager contextManager = new ContextManager();
        String chatSystemPrompt = contextManager.assembleSystemPrompt(
                null,
                Collections.emptyList(),
                null,
                false,
                false,
                AgentMode.CHAT,
                "",
                Collections.emptyList(),
                false
        );

        assertTrue(chatSystemPrompt.contains("Answer directly and stay strictly on-topic"));
        assertTrue(chatSystemPrompt.contains("Translation Rules (STRICT)"));
        assertTrue(chatSystemPrompt.contains("Return ONLY the direct, natural, context-appropriate translation"));
        assertTrue(chatSystemPrompt.contains("Provide ONE default translation only"));
        assertTrue(chatSystemPrompt.contains("NEVER emit <chatai_followups> for translations"));

        String codeSystemPrompt = contextManager.assembleSystemPrompt(
                null,
                Collections.emptyList(),
                null,
                false,
                false,
                AgentMode.CODING,
                "/tmp/ws",
                Collections.emptyList(),
                false
        );
        assertTrue(codeSystemPrompt.contains("Keep explanations direct and concise"));

        // Translation requests must NEVER produce follow-up chips, even if model emitted tags
        assertTrue(FollowUpSuggestionHelper.isLikelyTranslationPrompt("Dịch sang tiếng Anh: Hôm nay trời đẹp"));
        assertTrue(FollowUpSuggestionHelper.isLikelyTranslationPrompt("Translate into Vietnamese: Good morning"));
        List<String> translationFollowUps = FollowUpSuggestionHelper.resolveOrSynthesize(
                "The weather is nice today.<chatai_followups>[\"Translate another sentence?\"]</chatai_followups>",
                "The weather is nice today.",
                "Dịch sang tiếng Anh: Hôm nay trời đẹp",
                AgentMode.CHAT,
                Collections.emptyList(),
                true
        );
        assertTrue("Translation tasks should not append follow-up chips", translationFollowUps.isEmpty());

        // Short factual Chat replies should not synthesize generic filler follow-ups
        List<String> shortChatFollowUps = FollowUpSuggestionHelper.resolveOrSynthesize(
                "Thủ đô của Nhật Bản là Tokyo.",
                "Thủ đô của Nhật Bản là Tokyo.",
                "Thủ đô Nhật Bản là gì?",
                AgentMode.CHAT,
                Collections.emptyList(),
                true
        );
        assertTrue("Short factual answers should not synthesize generic follow-ups", shortChatFollowUps.isEmpty());

        // Complex Coding tasks should still preserve useful follow-up chips
        List<ToolCallRecord> codingTools = Collections.singletonList(
                new ToolCallRecord("e1", "fs_write_file", "Workspace", ToolCallRecord.Status.COMPLETED,
                        "Updated file", "{}", "ok", "", 100L, 12L)
        );
        List<String> codingFollowUps = FollowUpSuggestionHelper.resolveOrSynthesize(
                "Đã cập nhật logic xử lý cuộn.",
                "Đã cập nhật logic xử lý cuộn trong MainActivity.",
                "Sửa lỗi cuộn tin nhắn",
                AgentMode.CODING,
                codingTools,
                true
        );
        assertFalse("Coding workflow should retain next-step follow-up chips", codingFollowUps.isEmpty());
    }
}
