package com.chatai.an.presentation.chat;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.Spannable;
import android.text.style.URLSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.chatai.an.R;
import com.chatai.an.ai.core.ChatSessionCoordinator;
import com.chatai.an.core.MarkdownRenderer;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.SourceItem;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.TimelineEntry;
import com.chatai.an.domain.model.TokenUsage;
import com.chatai.an.domain.model.ToolCallRecord;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.presentation.common.UiFormatters;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    public interface OnMessageActionListener {
        void onCopyMessage(Message message);
        void onEditMessage(Message message);
        void onDeleteMessage(Message message);
        void onCopyCodeBlock(String code);
        void onPreviewAttachment(Attachment attachment);

        default void onRegenerateMessage(Message message) {}
        default void onHandoverToCode(Message message) {}
        default void onFollowUpClicked(String suggestion) {}
        default void onApproveToolCall(String executionId, boolean allowForSession) {}
        default void onDenyToolCall(String executionId) {}
        default void onCopyToolOutput(String text) {}
        default void onPreviewImage(String title, String imageSource) {}
        default void onOpenSourceUrl(String urlOrPath) {}
    }

    private final List<Message> messages = new ArrayList<>();
    private final Set<String> expandedThinkingIds = new HashSet<>();
    private final Set<String> expandedToolIds = new HashSet<>();
    private final Set<String> collapsedToolIds = new HashSet<>();
    private final Set<String> expandedTimelineIds = new HashSet<>();
    private final Set<String> expandedSourcesMessageIds = new HashSet<>();
    private final ModelRepository modelRepository;
    private final OnMessageActionListener listener;
    private ThemePalette palette;
    private AgentMode currentAgentMode = AgentMode.CHAT;
    private boolean aiBusy = false;

    public MessageAdapter(ModelRepository modelRepository, OnMessageActionListener listener) {
        this.modelRepository = modelRepository;
        this.listener = listener;
    }

    public void submitList(List<Message> newMessages) {
        messages.clear();
        if (newMessages != null) {
            messages.addAll(newMessages);
        }
        notifyDataSetChanged();
    }

    public void setPalette(ThemePalette palette) {
        this.palette = palette;
        notifyDataSetChanged();
    }

    public void setAgentMode(AgentMode mode) {
        AgentMode resolved = mode != null ? mode : AgentMode.CHAT;
        if (this.currentAgentMode != resolved) {
            this.currentAgentMode = resolved;
            notifyDataSetChanged();
        }
    }

    public void setAiBusy(boolean busy) {
        if (this.aiBusy != busy) {
            this.aiBusy = busy;
            notifyDataSetChanged();
        }
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        Context ctx = holder.itemView.getContext();
        boolean isUser = message.getRole() == MessageRole.USER;
        boolean isStreaming = ChatSessionCoordinator.STREAMING_MESSAGE_ID.equals(message.getId());
        boolean isLastMessage = position == messages.size() - 1;

        int maxTabletBubblePx = ThemeApplier.dpToPxInt(ctx, 540f);
        float r = ThemeApplier.dpToPx(ctx, 18f);
        float tailR = ThemeApplier.dpToPx(ctx, 5f);

        holder.rowRoot.setGravity(isUser ? Gravity.END : Gravity.START);

        // Configure dynamic content-hugging bubble dimensions & alignment
        LinearLayout.LayoutParams bubbleLp = (LinearLayout.LayoutParams) holder.bubbleContainer.getLayoutParams();
        bubbleLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        bubbleLp.gravity = isUser ? Gravity.END : Gravity.START;
        holder.bubbleContainer.setMaxWidthRatio(isUser ? 0.78f : 0.86f);
        holder.bubbleContainer.setMaxWidthPx(maxTabletBubblePx);

        if (isUser) {
            bubbleLp.setMarginStart(0);
            bubbleLp.setMarginEnd(ThemeApplier.dpToPxInt(ctx, 2f));
            holder.bubbleContainer.setPadding(
                    ThemeApplier.dpToPxInt(ctx, 14f),
                    ThemeApplier.dpToPxInt(ctx, 9f),
                    ThemeApplier.dpToPxInt(ctx, 14f),
                    ThemeApplier.dpToPxInt(ctx, 9f)
            );
            holder.headerRow.setVisibility(View.GONE);
        } else {
            bubbleLp.setMarginStart(ThemeApplier.dpToPxInt(ctx, 2f));
            bubbleLp.setMarginEnd(0);
            holder.bubbleContainer.setPadding(
                    ThemeApplier.dpToPxInt(ctx, 14f),
                    ThemeApplier.dpToPxInt(ctx, 10f),
                    ThemeApplier.dpToPxInt(ctx, 14f),
                    ThemeApplier.dpToPxInt(ctx, 10f)
            );
            holder.headerRow.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams headerLp = (LinearLayout.LayoutParams) holder.headerRow.getLayoutParams();
            headerLp.gravity = Gravity.START;
            holder.headerRow.setLayoutParams(headerLp);
        }
        holder.bubbleContainer.setLayoutParams(bubbleLp);

        // Configure external thinking container, tool cards container, timeline, sources, footer & post actions alignment
        LinearLayout.LayoutParams thinkingLp = (LinearLayout.LayoutParams) holder.thinkingContainer.getLayoutParams();
        thinkingLp.gravity = isUser ? Gravity.END : Gravity.START;
        thinkingLp.setMarginStart(isUser ? 0 : ThemeApplier.dpToPxInt(ctx, 2f));
        thinkingLp.setMarginEnd(isUser ? ThemeApplier.dpToPxInt(ctx, 2f) : 0);
        holder.thinkingContainer.setMaxWidthRatio(0.86f);
        holder.thinkingContainer.setMaxWidthPx(maxTabletBubblePx);
        holder.thinkingContainer.setLayoutParams(thinkingLp);

        LinearLayout.LayoutParams toolCardsLp = (LinearLayout.LayoutParams) holder.toolCardsContainer.getLayoutParams();
        toolCardsLp.gravity = Gravity.START;
        toolCardsLp.setMarginStart(ThemeApplier.dpToPxInt(ctx, 2f));
        toolCardsLp.setMarginEnd(0);
        holder.toolCardsContainer.setMaxWidthRatio(0.86f);
        holder.toolCardsContainer.setMaxWidthPx(maxTabletBubblePx);
        holder.toolCardsContainer.setLayoutParams(toolCardsLp);

        LinearLayout.LayoutParams timelineLp = (LinearLayout.LayoutParams) holder.timelineContainer.getLayoutParams();
        timelineLp.gravity = Gravity.START;
        timelineLp.setMarginStart(ThemeApplier.dpToPxInt(ctx, 2f));
        timelineLp.setMarginEnd(0);
        holder.timelineContainer.setMaxWidthRatio(0.86f);
        holder.timelineContainer.setMaxWidthPx(maxTabletBubblePx);
        holder.timelineContainer.setLayoutParams(timelineLp);

        LinearLayout.LayoutParams sourcesLp = (LinearLayout.LayoutParams) holder.sourcesContainer.getLayoutParams();
        sourcesLp.gravity = Gravity.START;
        sourcesLp.setMarginStart(ThemeApplier.dpToPxInt(ctx, 2f));
        sourcesLp.setMarginEnd(0);
        holder.sourcesContainer.setMaxWidthRatio(0.86f);
        holder.sourcesContainer.setMaxWidthPx(maxTabletBubblePx);
        holder.sourcesContainer.setLayoutParams(sourcesLp);

        LinearLayout.LayoutParams footerLp = (LinearLayout.LayoutParams) holder.footerRow.getLayoutParams();
        footerLp.gravity = isUser ? Gravity.END : Gravity.START;
        footerLp.setMarginStart(isUser ? 0 : ThemeApplier.dpToPxInt(ctx, 4f));
        footerLp.setMarginEnd(isUser ? ThemeApplier.dpToPxInt(ctx, 2f) : 0);
        holder.footerRow.setLayoutParams(footerLp);

        LinearLayout.LayoutParams postActionsLp = (LinearLayout.LayoutParams) holder.postActionsContainer.getLayoutParams();
        postActionsLp.gravity = Gravity.START;
        postActionsLp.setMarginStart(ThemeApplier.dpToPxInt(ctx, 4f));
        holder.postActionsContainer.setMaxWidthRatio(0.86f);
        holder.postActionsContainer.setMaxWidthPx(maxTabletBubblePx);
        holder.postActionsContainer.setLayoutParams(postActionsLp);

        // Role / Identity: always present assistant as AnAI
        holder.tvRole.setText(isUser ? R.string.role_user : R.string.assistant_name_anai);

        AiModel model = modelRepository.getModelById(message.getModelId());
        String modelLabel = model != null ? model.getName() : message.getModelId();
        holder.tvModel.setText(modelLabel != null ? modelLabel : "");
        holder.tvModel.setVisibility(!isUser && modelLabel != null && !modelLabel.isEmpty() ? View.VISIBLE : View.GONE);

        AccessType accessType = message.getAccessType();
        if (!isUser && accessType != null) {
            holder.tvAccessBadge.setVisibility(View.VISIBLE);
            if (accessType == AccessType.FREE) {
                holder.tvAccessBadge.setText(R.string.badge_free);
            } else if (accessType == AccessType.LOCAL) {
                holder.tvAccessBadge.setText(R.string.badge_local);
            } else {
                holder.tvAccessBadge.setText(R.string.badge_api_key);
            }
        } else {
            holder.tvAccessBadge.setVisibility(View.GONE);
        }

        holder.tvDeepThinking.setVisibility(message.isDeepThinkingEnabled() ? View.VISIBLE : View.GONE);
        holder.tvEdited.setVisibility(message.getUpdatedAt() > message.getCreatedAt() + 1000L ? View.VISIBLE : View.GONE);
        holder.tvTime.setText(UiFormatters.formatTime(message.getCreatedAt()));

        TokenUsage usage = message.getTokenUsage();
        if (usage != null && usage.hasAnyUsage()) {
            holder.tvTokens.setVisibility(View.VISIBLE);
            holder.tvTokens.setText(usage.getPromptTokens() + "·" + usage.getCompletionTokens() + " (" + usage.getTotalTokens() + ")");
        } else {
            holder.tvTokens.setVisibility(View.GONE);
        }

        int userBubbleTextColor = Color.WHITE;
        int assistantTextColor = palette != null ? palette.getTextPrimaryColor() : 0xFF1F1410;
        int accentColor = palette != null ? palette.getPrimaryColor() : 0xFFFF5F2B;

        if (palette != null) {
            int subtleBorder = ThemeApplier.blendWithAlpha(palette.getBorderColor(), 80);
            int hairlinePx = Math.max(1, ThemeApplier.dpToPxInt(ctx, 0.75f));

            if (isUser) {
                float[] userRadii = new float[]{r, r, r, r, tailR, tailR, r, r};
                holder.bubbleContainer.setBackground(
                        GradientDrawableFactory.createLinearGradientWithRadii(palette, userRadii)
                );
                int midBg = ThemeApplier.estimateGradientMidColor(palette);
                userBubbleTextColor = ThemeApplier.pickHighContrastTextColor(midBg);
            } else if (message.isError()) {
                int errColor = ContextCompat.getColor(ctx, R.color.status_unsupported);
                float[] assistantRadii = new float[]{tailR, tailR, r, r, r, r, r, r};
                holder.bubbleContainer.setBackground(GradientDrawableFactory.createSurfaceCardWithRadii(
                        ThemeApplier.blendWithAlpha(errColor, 20),
                        ThemeApplier.blendWithAlpha(errColor, 140),
                        ThemeApplier.dpToPxInt(ctx, 1f),
                        assistantRadii
                ));
            } else {
                float[] assistantRadii = new float[]{tailR, tailR, r, r, r, r, r, r};
                holder.bubbleContainer.setBackground(GradientDrawableFactory.createSurfaceCardWithRadii(
                        palette.getSurfaceVariantColor(),
                        subtleBorder,
                        hairlinePx,
                        assistantRadii
                ));
            }

            holder.assistantAvatarBadge.setBackground(GradientDrawableFactory.createLinearGradient(palette, 999f));
            holder.ivAssistantAvatar.setColorFilter(
                    ThemeApplier.pickHighContrastTextColor(ThemeApplier.estimateGradientMidColor(palette))
            );
            holder.tvRole.setTextColor(palette.getTextPrimaryColor());
            holder.tvModel.setTextColor(palette.getTextSecondaryColor());
            holder.tvTime.setTextColor(palette.getTextSecondaryColor());
            holder.tvTokens.setTextColor(palette.getTextSecondaryColor());
            holder.tvEdited.setTextColor(palette.getTextSecondaryColor());
            holder.tvTime.setAlpha(0.78f);
            holder.tvTokens.setAlpha(0.75f);
            holder.tvEdited.setAlpha(0.78f);

            holder.tvDeepThinking.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(palette.getPrimaryColor(), 28),
                    0,
                    0
            ));
            holder.tvDeepThinking.setTextColor(palette.getPrimaryColor());

            int accessColor = accessType == AccessType.FREE
                    ? ContextCompat.getColor(ctx, R.color.status_supported)
                    : palette.getPrimaryColor();
            holder.tvAccessBadge.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(accessColor, 24),
                    0,
                    0
            ));
            holder.tvAccessBadge.setTextColor(accessColor);

            holder.ivThinkingIcon.setColorFilter(palette.getPrimaryColor());
            holder.tvThinkingHeader.setTextColor(palette.getTextPrimaryColor());
            holder.tvThinkingToggle.setTextColor(palette.getPrimaryColor());
            holder.tvThinkingContent.setTextColor(palette.getTextSecondaryColor());

            holder.btnCopy.setColorFilter(palette.getTextSecondaryColor());
            holder.btnRegenerate.setColorFilter(palette.getTextSecondaryColor());
            holder.btnEdit.setColorFilter(palette.getTextSecondaryColor());
            holder.btnDelete.setColorFilter(palette.getTextSecondaryColor());
            holder.btnCopy.setAlpha(0.76f);
            holder.btnRegenerate.setAlpha(0.76f);
            holder.btnEdit.setAlpha(0.76f);
            holder.btnDelete.setAlpha(0.76f);
        } else {
            float[] userRadii = new float[]{r, r, r, r, tailR, tailR, r, r};
            float[] assistantRadii = new float[]{tailR, tailR, r, r, r, r, r, r};
            if (isUser) {
                holder.bubbleContainer.setBackground(GradientDrawableFactory.createSurfaceCardWithRadii(
                        0xFFFF5F2B, 0, 0, userRadii
                ));
                userBubbleTextColor = Color.WHITE;
            } else {
                holder.bubbleContainer.setBackground(GradientDrawableFactory.createSurfaceCardWithRadii(
                        0xFFFFF0E8, 0xFFE6D4CB, 1, assistantRadii
                ));
            }
        }

        // Chronological Timeline (Thinking -> Tool Calls -> Commentary) vs Legacy Fallback
        List<TimelineEntry> timelineEntries = message.getTimelineEntries();
        boolean useChronologicalTimeline = !isUser && timelineEntries != null && !timelineEntries.isEmpty();
        if (useChronologicalTimeline) {
            holder.thinkingContainer.setVisibility(View.GONE);
            holder.toolCardsContainer.setVisibility(View.GONE);
            bindChronologicalTimeline(holder, ctx, message, timelineEntries);
        } else {
            holder.timelineContainer.removeAllViews();
            holder.timelineContainer.setVisibility(View.GONE);
            bindLegacyThinking(holder, ctx, message);
            bindToolCards(holder, ctx, message.getToolRecords(), isUser);
        }

        // Attachments
        List<Attachment> attachments = message.getAttachments();
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if (hasAttachments) {
            holder.rvAttachments.setVisibility(View.VISIBLE);
            holder.rvAttachments.setLayoutManager(
                    new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            );
            holder.rvAttachments.setAdapter(new MessageAttachmentAdapter(
                    attachments,
                    palette,
                    attachment -> {
                        if (listener != null) listener.onPreviewAttachment(attachment);
                    }
            ));
        } else {
            holder.rvAttachments.setVisibility(View.GONE);
        }

        // Markdown, Tables, LaTeX, Images & Code Block rendering
        holder.markdownContainer.removeAllViews();
        LinearLayout.LayoutParams mdLp = (LinearLayout.LayoutParams) holder.markdownContainer.getLayoutParams();
        String content = message.getContent();
        boolean hasTextContent = content != null && !content.isEmpty();
        mdLp.topMargin = (hasAttachments && hasTextContent) ? ThemeApplier.dpToPxInt(ctx, 6f) : 0;
        holder.markdownContainer.setLayoutParams(mdLp);

        if (hasTextContent) {
            holder.bubbleContainer.setVisibility(View.VISIBLE);
            holder.markdownContainer.setVisibility(View.VISIBLE);
            LayoutInflater inflater = LayoutInflater.from(ctx);

            int textColor = isUser ? userBubbleTextColor : assistantTextColor;
            int linkColor = isUser ? userBubbleTextColor : accentColor;
            int quoteAccent = isUser ? userBubbleTextColor : accentColor;
            int inlineCodeBg = isUser
                    ? ThemeApplier.blendWithAlpha(userBubbleTextColor, 46)
                    : ThemeApplier.blendWithAlpha(accentColor, 30);

            List<MarkdownRenderer.MarkdownSegment> segments = MarkdownRenderer.parseSegments(content);
            for (MarkdownRenderer.MarkdownSegment segment : segments) {
                if (segment.getType() == MarkdownRenderer.SegmentType.CODE_BLOCK) {
                    View codeView = inflater.inflate(R.layout.item_markdown_code_block, holder.markdownContainer, false);
                    LinearLayout codeRoot = codeView.findViewById(R.id.code_block_root);
                    LinearLayout codeHeader = codeView.findViewById(R.id.code_block_header);
                    TextView tvLang = codeView.findViewById(R.id.tv_code_language);
                    TextView tvCode = codeView.findViewById(R.id.tv_code_content);
                    View btnCopyCode = codeView.findViewById(R.id.btn_copy_code);

                    float codeR = ThemeApplier.dpToPx(ctx, 11f);
                    codeRoot.setBackground(GradientDrawableFactory.createSurfaceCard(
                            ContextCompat.getColor(ctx, R.color.code_block_bg),
                            palette != null ? ThemeApplier.blendWithAlpha(palette.getBorderColor(), 110) : 0xFF3A3533,
                            ThemeApplier.dpToPxInt(ctx, 1f),
                            codeR
                    ));
                    codeHeader.setBackground(GradientDrawableFactory.createSurfaceCardWithRadii(
                            ContextCompat.getColor(ctx, R.color.code_block_header),
                            0,
                            0,
                            new float[]{codeR, codeR, codeR, codeR, 0f, 0f, 0f, 0f}
                    ));
                    tvLang.setText(segment.getLanguage().isEmpty() ? "code" : segment.getLanguage());
                    tvCode.setText(segment.getContent());
                    btnCopyCode.setOnClickListener(v -> {
                        if (listener != null) listener.onCopyCodeBlock(segment.getContent());
                    });

                    holder.markdownContainer.addView(codeView);
                } else if (segment.getType() == MarkdownRenderer.SegmentType.TABLE) {
                    holder.markdownContainer.addView(buildTableView(ctx, segment, inlineCodeBg, accentColor));
                } else if (segment.getType() == MarkdownRenderer.SegmentType.LATEX_BLOCK) {
                    holder.markdownContainer.addView(buildLatexBlockView(ctx, inflater, holder.markdownContainer, segment, accentColor, textColor));
                } else if (segment.getType() == MarkdownRenderer.SegmentType.IMAGE) {
                    holder.markdownContainer.addView(buildMarkdownImageView(ctx, inflater, holder.markdownContainer, segment, textColor));
                } else {
                    View textViewItem = inflater.inflate(R.layout.item_markdown_text, holder.markdownContainer, false);
                    TextView tvText = textViewItem.findViewById(R.id.tv_markdown_segment);
                    tvText.setTextColor(textColor);
                    tvText.setLinkTextColor(linkColor);
                    tvText.setText(MarkdownRenderer.formatMarkdownText(segment.getContent(), inlineCodeBg, quoteAccent));
                    enableUrlSpanClicks(ctx, tvText);
                    holder.markdownContainer.addView(textViewItem);
                }
            }
        } else if (!isUser) {
            holder.bubbleContainer.setVisibility(View.VISIBLE);
            holder.markdownContainer.setVisibility(View.VISIBLE);
            TextView tvPlaceholder = new TextView(ctx);
            tvPlaceholder.setText("…");
            if (palette != null) tvPlaceholder.setTextColor(palette.getTextSecondaryColor());
            holder.markdownContainer.addView(tvPlaceholder);
        } else {
            holder.markdownContainer.setVisibility(View.GONE);
        }

        // Expandable Sources Accordion (below assistant bubble)
        bindSourcesAccordion(holder, ctx, message, isUser);

        // Footer actions (hide edit/regenerate/delete while active streaming placeholder)
        holder.btnCopy.setOnClickListener(v -> {
            if (listener != null) listener.onCopyMessage(message);
        });
        holder.btnRegenerate.setVisibility(isStreaming ? View.GONE : View.VISIBLE);
        holder.btnRegenerate.setOnClickListener(v -> {
            if (listener != null) listener.onRegenerateMessage(message);
        });
        holder.btnEdit.setVisibility(isStreaming ? View.GONE : View.VISIBLE);
        holder.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEditMessage(message);
        });
        holder.btnDelete.setVisibility(isStreaming ? View.GONE : View.VISIBLE);
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteMessage(message);
        });

        // Continue in Code & Follow-Up Chips (only on latest completed non-error assistant message)
        bindPostMessageActions(holder, ctx, message, isUser, isStreaming, isLastMessage);
    }

    private void bindLegacyThinking(MessageViewHolder holder, Context ctx, Message message) {
        String thinking = message.getThinkingContent();
        if (thinking != null && !thinking.trim().isEmpty()) {
            holder.thinkingContainer.setVisibility(View.VISIBLE);
            boolean expanded = expandedThinkingIds.contains(message.getId());
            holder.tvThinkingContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
            holder.tvThinkingToggle.setText(expanded
                    ? R.string.thinking_process_toggle_hide
                    : R.string.thinking_process_toggle_show);
            holder.tvThinkingContent.setText(thinking.trim());

            if (palette != null) {
                int subtleBorder = ThemeApplier.blendWithAlpha(palette.getBorderColor(), 85);
                int hairlinePx = Math.max(1, ThemeApplier.dpToPxInt(ctx, 0.75f));
                if (expanded) {
                    holder.thinkingContainer.setBackground(GradientDrawableFactory.createSurfaceCard(
                            palette.getSurfaceColor(),
                            subtleBorder,
                            hairlinePx,
                            ThemeApplier.dpToPx(ctx, 14f)
                    ));
                } else {
                    holder.thinkingContainer.setBackground(GradientDrawableFactory.createPill(
                            palette.getSurfaceVariantColor(),
                            subtleBorder,
                            hairlinePx
                    ));
                }
            }

            holder.btnToggleThinking.setOnClickListener(v -> {
                if (expandedThinkingIds.contains(message.getId())) {
                    expandedThinkingIds.remove(message.getId());
                } else {
                    expandedThinkingIds.add(message.getId());
                }
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos);
                }
            });
        } else {
            holder.thinkingContainer.setVisibility(View.GONE);
        }
    }

    private void bindChronologicalTimeline(
            MessageViewHolder holder,
            Context ctx,
            Message message,
            List<TimelineEntry> entries
    ) {
        holder.timelineContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(ctx);

        Map<String, ToolCallRecord> recordById = new HashMap<>();
        List<ToolCallRecord> allRecords = message.getToolRecords();
        if (allRecords != null) {
            for (ToolCallRecord r : allRecords) {
                if (r != null && r.getExecutionId() != null) {
                    recordById.put(r.getExecutionId(), r);
                }
            }
        }

        Set<String> renderedToolIds = new HashSet<>();
        int renderedSteps = 0;

        for (TimelineEntry entry : entries) {
            if (entry == null) continue;
            TimelineEntry.Type type = entry.getType();
            if (type == TimelineEntry.Type.SOURCES || type == TimelineEntry.Type.RESPONSE) {
                // RESPONSE renders in the main assistant bubble; SOURCES renders in the accordion below the bubble
                continue;
            }
            if (type == TimelineEntry.Type.TOOL_CALL) {
                ToolCallRecord rec = entry.getToolExecutionId() != null
                        ? recordById.get(entry.getToolExecutionId())
                        : null;
                if (rec != null) {
                    renderedToolIds.add(rec.getExecutionId());
                    holder.timelineContainer.addView(inflateAndBindToolCard(holder, ctx, inflater, holder.timelineContainer, rec));
                    renderedSteps++;
                }
                continue;
            }
            if (type == TimelineEntry.Type.THINKING || type == TimelineEntry.Type.COMMENTARY) {
                String body = entry.getContent() != null ? entry.getContent().trim() : "";
                if (body.isEmpty()) continue;
                holder.timelineContainer.addView(buildTimelineStepView(holder, ctx, inflater, entry, body));
                renderedSteps++;
            }
        }

        // Ensure any tool records not referenced in timelineEntries (e.g., pending approvals) still appear
        if (allRecords != null) {
            for (ToolCallRecord rec : allRecords) {
                if (rec != null && !renderedToolIds.contains(rec.getExecutionId())) {
                    holder.timelineContainer.addView(inflateAndBindToolCard(holder, ctx, inflater, holder.timelineContainer, rec));
                    renderedSteps++;
                }
            }
        }

        holder.timelineContainer.setVisibility(renderedSteps > 0 ? View.VISIBLE : View.GONE);
    }

    private View buildTimelineStepView(
            MessageViewHolder holder,
            Context ctx,
            LayoutInflater inflater,
            TimelineEntry entry,
            String body
    ) {
        View stepView = inflater.inflate(R.layout.item_timeline_step, holder.timelineContainer, false);
        LinearLayout root = stepView.findViewById(R.id.timeline_step_root);
        View header = stepView.findViewById(R.id.ll_timeline_step_header);
        ImageView ivIcon = stepView.findViewById(R.id.iv_timeline_step_icon);
        TextView tvTitle = stepView.findViewById(R.id.tv_timeline_step_title);
        TextView tvPreview = stepView.findViewById(R.id.tv_timeline_step_preview);
        TextView tvToggle = stepView.findViewById(R.id.tv_timeline_step_toggle);
        TextView tvBody = stepView.findViewById(R.id.tv_timeline_step_body);

        boolean isThinking = entry.getType() == TimelineEntry.Type.THINKING;
        ivIcon.setImageResource(isThinking ? R.drawable.ic_psychology : R.drawable.ic_chat_spark);
        if (entry.getTitle() != null && !entry.getTitle().trim().isEmpty()) {
            tvTitle.setText(entry.getTitle().trim());
        } else {
            tvTitle.setText(isThinking ? R.string.timeline_thinking_title : R.string.timeline_commentary_title);
        }

        String stepKey = entry.getId();
        boolean expanded = expandedTimelineIds.contains(stepKey);
        tvPreview.setText(firstLineSnippet(body));
        tvPreview.setVisibility(expanded ? View.GONE : View.VISIBLE);
        tvBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        tvBody.setText(body);
        tvToggle.setText(expanded ? R.string.timeline_hide_details : R.string.timeline_show_details);

        int accentColor = palette != null ? palette.getPrimaryColor() : 0xFFFF5F2B;
        int surfaceColor = palette != null ? palette.getSurfaceColor() : 0xFFFFFFFF;
        int surfaceVarColor = palette != null ? palette.getSurfaceVariantColor() : 0xFFFFF4EE;
        int borderColor = palette != null ? palette.getBorderColor() : 0xFFE6D4CB;
        int textPrimary = palette != null ? palette.getTextPrimaryColor() : 0xFF1F1410;
        int textSecondary = palette != null ? palette.getTextSecondaryColor() : 0xFF6B5B53;

        root.setBackground(GradientDrawableFactory.createSurfaceCard(
                expanded ? surfaceColor : surfaceVarColor,
                ThemeApplier.blendWithAlpha(borderColor, 115),
                ThemeApplier.dpToPxInt(ctx, 0.85f),
                ThemeApplier.dpToPx(ctx, 12f)
        ));
        ivIcon.setColorFilter(accentColor);
        tvTitle.setTextColor(textPrimary);
        tvPreview.setTextColor(textSecondary);
        tvToggle.setTextColor(accentColor);
        tvBody.setTextColor(textSecondary);

        header.setOnClickListener(v -> {
            if (expandedTimelineIds.contains(stepKey)) {
                expandedTimelineIds.remove(stepKey);
            } else {
                expandedTimelineIds.add(stepKey);
            }
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos);
            }
        });
        return stepView;
    }

    private void bindSourcesAccordion(
            MessageViewHolder holder,
            Context ctx,
            Message message,
            boolean isUser
    ) {
        holder.sourcesContainer.removeAllViews();
        List<SourceItem> sources = message.getSources();
        if (isUser || sources == null || sources.isEmpty()) {
            holder.sourcesContainer.setVisibility(View.GONE);
            return;
        }
        holder.sourcesContainer.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        View accordion = inflater.inflate(R.layout.item_sources_accordion, holder.sourcesContainer, false);

        LinearLayout root = accordion.findViewById(R.id.sources_accordion_root);
        View header = accordion.findViewById(R.id.ll_sources_header);
        ImageView ivIcon = accordion.findViewById(R.id.iv_sources_icon);
        TextView tvTitle = accordion.findViewById(R.id.tv_sources_title);
        TextView tvToggle = accordion.findViewById(R.id.tv_sources_toggle);
        LinearLayout listBox = accordion.findViewById(R.id.ll_sources_list);

        boolean expanded = expandedSourcesMessageIds.contains(message.getId());
        tvTitle.setText(ctx.getString(R.string.timeline_sources_count, sources.size()));
        tvToggle.setText(expanded ? R.string.timeline_hide_sources : R.string.timeline_show_sources);
        listBox.setVisibility(expanded ? View.VISIBLE : View.GONE);

        int accentColor = palette != null ? palette.getPrimaryColor() : 0xFFFF5F2B;
        int surfaceColor = palette != null ? palette.getSurfaceColor() : 0xFFFFFFFF;
        int surfaceVarColor = palette != null ? palette.getSurfaceVariantColor() : 0xFFFFF4EE;
        int borderColor = palette != null ? palette.getBorderColor() : 0xFFE6D4CB;
        int textPrimary = palette != null ? palette.getTextPrimaryColor() : 0xFF1F1410;
        int textSecondary = palette != null ? palette.getTextSecondaryColor() : 0xFF6B5B53;

        root.setBackground(GradientDrawableFactory.createSurfaceCard(
                expanded ? surfaceColor : surfaceVarColor,
                ThemeApplier.blendWithAlpha(borderColor, 120),
                ThemeApplier.dpToPxInt(ctx, 0.85f),
                ThemeApplier.dpToPx(ctx, 12f)
        ));
        ivIcon.setColorFilter(accentColor);
        tvTitle.setTextColor(textPrimary);
        tvToggle.setTextColor(accentColor);

        if (expanded) {
            for (SourceItem src : sources) {
                if (src == null) continue;
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(
                        ThemeApplier.dpToPxInt(ctx, 8f),
                        ThemeApplier.dpToPxInt(ctx, 6f),
                        ThemeApplier.dpToPxInt(ctx, 8f),
                        ThemeApplier.dpToPxInt(ctx, 6f)
                );
                row.setBackground(GradientDrawableFactory.createSurfaceCard(
                        surfaceVarColor,
                        ThemeApplier.blendWithAlpha(borderColor, 70),
                        ThemeApplier.dpToPxInt(ctx, 0.5f),
                        ThemeApplier.dpToPx(ctx, 8f)
                ));
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rowLp.bottomMargin = ThemeApplier.dpToPxInt(ctx, 4f);
                row.setLayoutParams(rowLp);
                row.setClickable(true);
                row.setFocusable(true);

                TextView tvType = new TextView(ctx);
                tvType.setText(src.getType().name());
                tvType.setTextSize(9.5f);
                tvType.setTypeface(null, Typeface.BOLD);
                tvType.setTextColor(accentColor);
                tvType.setBackground(GradientDrawableFactory.createPill(
                        ThemeApplier.blendWithAlpha(accentColor, 26), 0, 0
                ));
                tvType.setPadding(
                        ThemeApplier.dpToPxInt(ctx, 6f),
                        ThemeApplier.dpToPxInt(ctx, 2f),
                        ThemeApplier.dpToPxInt(ctx, 6f),
                        ThemeApplier.dpToPxInt(ctx, 2f)
                );

                LinearLayout infoCol = new LinearLayout(ctx);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                );
                colLp.setMarginStart(ThemeApplier.dpToPxInt(ctx, 8f));
                infoCol.setLayoutParams(colLp);

                TextView tvSrcTitle = new TextView(ctx);
                tvSrcTitle.setText(src.getTitle());
                tvSrcTitle.setTextSize(12f);
                tvSrcTitle.setTypeface(null, Typeface.BOLD);
                tvSrcTitle.setTextColor(textPrimary);
                tvSrcTitle.setMaxLines(1);
                tvSrcTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);

                TextView tvSrcTarget = new TextView(ctx);
                tvSrcTarget.setText(src.getUrlOrPath());
                tvSrcTarget.setTextSize(11f);
                tvSrcTarget.setTextColor(textSecondary);
                tvSrcTarget.setMaxLines(1);
                tvSrcTarget.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

                infoCol.addView(tvSrcTitle);
                if (!src.getUrlOrPath().isEmpty() && !src.getUrlOrPath().equals(src.getTitle())) {
                    infoCol.addView(tvSrcTarget);
                }

                row.addView(tvType);
                row.addView(infoCol);

                row.setOnClickListener(v -> {
                    String target = src.getUrlOrPath();
                    if (listener != null) {
                        listener.onOpenSourceUrl(target);
                    } else if (target.startsWith("http://") || target.startsWith("https://")) {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(ctx, target, Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        copyTextDirect(ctx, target);
                    }
                });
                listBox.addView(row);
            }
        }

        header.setOnClickListener(v -> {
            if (expandedSourcesMessageIds.contains(message.getId())) {
                expandedSourcesMessageIds.remove(message.getId());
            } else {
                expandedSourcesMessageIds.add(message.getId());
            }
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos);
            }
        });

        holder.sourcesContainer.addView(accordion);
    }

    private void bindToolCards(
            MessageViewHolder holder,
            Context ctx,
            List<ToolCallRecord> records,
            boolean isUser
    ) {
        holder.toolCardsContainer.removeAllViews();
        if (isUser || records == null || records.isEmpty()) {
            holder.toolCardsContainer.setVisibility(View.GONE);
            return;
        }
        holder.toolCardsContainer.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (ToolCallRecord rec : records) {
            if (rec == null) continue;
            holder.toolCardsContainer.addView(
                    inflateAndBindToolCard(holder, ctx, inflater, holder.toolCardsContainer, rec)
            );
        }
    }

    private View inflateAndBindToolCard(
            MessageViewHolder holder,
            Context ctx,
            LayoutInflater inflater,
            ViewGroup parent,
            ToolCallRecord rec
    ) {
        int supportedColor = ContextCompat.getColor(ctx, R.color.status_supported);
        int unsupportedColor = ContextCompat.getColor(ctx, R.color.status_unsupported);
        int pendingColor = ContextCompat.getColor(ctx, R.color.status_unknown);
        int accentColor = palette != null ? palette.getPrimaryColor() : 0xFFFF5F2B;
        int surfaceColor = palette != null ? palette.getSurfaceColor() : 0xFFFFFFFF;
        int surfaceVarColor = palette != null ? palette.getSurfaceVariantColor() : 0xFFFFF4EE;
        int borderColor = palette != null ? palette.getBorderColor() : 0xFFE6D4CB;
        int textPrimary = palette != null ? palette.getTextPrimaryColor() : 0xFF1F1410;
        int textSecondary = palette != null ? palette.getTextSecondaryColor() : 0xFF6B5B53;

        View card = inflater.inflate(R.layout.item_tool_card, parent, false);
        LinearLayout cardRoot = card.findViewById(R.id.tool_card_root);
        View header = card.findViewById(R.id.ll_tool_card_header);
        ImageView ivIcon = card.findViewById(R.id.iv_tool_icon);
        TextView tvName = card.findViewById(R.id.tv_tool_name);
        TextView tvPrivBadge = card.findViewById(R.id.tv_tool_privilege_badge);
        TextView tvStatus = card.findViewById(R.id.tv_tool_status);
        TextView tvDuration = card.findViewById(R.id.tv_tool_duration);
        ImageView ivExpand = card.findViewById(R.id.iv_tool_expand);
        TextView tvReason = card.findViewById(R.id.tv_tool_reason);
        View detailsBox = card.findViewById(R.id.ll_tool_details);
        TextView tvInputLabel = card.findViewById(R.id.tv_tool_input_label);
        TextView tvArgs = card.findViewById(R.id.tv_tool_args);
        TextView tvOutputLabel = card.findViewById(R.id.tv_tool_output_label);
        TextView tvOutput = card.findViewById(R.id.tv_tool_output);
        TextView btnCopyOut = card.findViewById(R.id.btn_tool_copy_output);
        View confirmBar = card.findViewById(R.id.ll_tool_confirmation_bar);
        TextView btnDeny = card.findViewById(R.id.btn_tool_deny);
        TextView btnAllowSession = card.findViewById(R.id.btn_tool_allow_session);
        TextView btnAllowOnce = card.findViewById(R.id.btn_tool_allow_once);

        ToolCallRecord.Status status = rec.getStatus();
        int statusColor = accentColor;
        int statusLabelRes = R.string.tool_status_running;
        if (status == ToolCallRecord.Status.WAITING_APPROVAL) {
            statusColor = pendingColor;
            statusLabelRes = R.string.tool_status_waiting;
        } else if (status == ToolCallRecord.Status.COMPLETED) {
            statusColor = supportedColor;
            statusLabelRes = R.string.tool_status_completed;
        } else if (status == ToolCallRecord.Status.FAILED) {
            statusColor = unsupportedColor;
            statusLabelRes = R.string.tool_status_failed;
        } else if (status == ToolCallRecord.Status.DENIED) {
            statusColor = unsupportedColor;
            statusLabelRes = R.string.tool_status_denied;
        } else if (status == ToolCallRecord.Status.CANCELLED) {
            statusColor = textSecondary;
            statusLabelRes = R.string.tool_status_cancelled;
        }

        int cardStrokeColor = status == ToolCallRecord.Status.WAITING_APPROVAL
                ? statusColor
                : ThemeApplier.blendWithAlpha(borderColor, 130);
        cardRoot.setBackground(GradientDrawableFactory.createSurfaceCard(
                surfaceColor,
                cardStrokeColor,
                ThemeApplier.dpToPxInt(ctx, status == ToolCallRecord.Status.WAITING_APPROVAL ? 1.5f : 1f),
                ThemeApplier.dpToPx(ctx, 14f)
        ));

        ivIcon.setColorFilter(statusColor);
        tvName.setText(rec.getToolName());
        tvName.setTextColor(textPrimary);

        String badge = rec.getPrivilegeBadge();
        if (badge != null && !badge.trim().isEmpty()) {
            tvPrivBadge.setVisibility(View.VISIBLE);
            tvPrivBadge.setText(badge.trim().toUpperCase(Locale.ROOT));
            int privColor = badge.trim().toLowerCase(Locale.ROOT).contains("shizuku") ? supportedColor : accentColor;
            tvPrivBadge.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(privColor, 26),
                    0,
                    0
            ));
            tvPrivBadge.setTextColor(privColor);
        } else {
            tvPrivBadge.setVisibility(View.GONE);
        }

        tvStatus.setText(statusLabelRes);
        tvStatus.setTextColor(statusColor);

        if (rec.getDurationMs() > 0 && status != ToolCallRecord.Status.WAITING_APPROVAL) {
            tvDuration.setVisibility(View.VISIBLE);
            tvDuration.setText(rec.getDurationMs() < 1000
                    ? rec.getDurationMs() + "ms"
                    : String.format(Locale.US, "%.1fs", rec.getDurationMs() / 1000.0));
            tvDuration.setTextColor(textSecondary);
        } else {
            tvDuration.setVisibility(View.GONE);
        }

        if (!rec.getReason().isEmpty()) {
            tvReason.setVisibility(View.VISIBLE);
            tvReason.setText(rec.getReason());
            tvReason.setTextColor(textSecondary);
        } else {
            tvReason.setVisibility(View.GONE);
        }

        boolean defaultExpanded = status == ToolCallRecord.Status.WAITING_APPROVAL
                || status == ToolCallRecord.Status.FAILED;
        boolean isExpanded = expandedToolIds.contains(rec.getExecutionId())
                || (defaultExpanded && !collapsedToolIds.contains(rec.getExecutionId()));

        boolean hasArgs = !rec.getArgsPreview().isEmpty();
        String combinedOut = !rec.getOutputPreview().isEmpty() ? rec.getOutputPreview() : rec.getErrorMessage();
        boolean hasOutput = !combinedOut.isEmpty();

        detailsBox.setVisibility(isExpanded && (hasArgs || hasOutput) ? View.VISIBLE : View.GONE);
        ivExpand.setRotation(isExpanded ? 180f : 0f);
        ivExpand.setColorFilter(textSecondary);

        if (hasArgs) {
            if (tvInputLabel != null) {
                tvInputLabel.setVisibility(View.VISIBLE);
                tvInputLabel.setTextColor(textSecondary);
            }
            tvArgs.setVisibility(View.VISIBLE);
            tvArgs.setMaxLines(Integer.MAX_VALUE);
            tvArgs.setText(rec.getArgsPreview());
            tvArgs.setBackground(GradientDrawableFactory.createSurfaceCard(
                    surfaceVarColor, 0, 0, ThemeApplier.dpToPx(ctx, 8f)
            ));
            tvArgs.setTextColor(textSecondary);
        } else {
            if (tvInputLabel != null) tvInputLabel.setVisibility(View.GONE);
            tvArgs.setVisibility(View.GONE);
        }

        if (hasOutput) {
            if (tvOutputLabel != null) {
                tvOutputLabel.setVisibility(View.VISIBLE);
                tvOutputLabel.setTextColor(textSecondary);
            }
            tvOutput.setVisibility(View.VISIBLE);
            tvOutput.setMaxLines(Integer.MAX_VALUE);
            tvOutput.setText(combinedOut);
            tvOutput.setBackground(GradientDrawableFactory.createSurfaceCard(
                    ContextCompat.getColor(ctx, R.color.code_block_bg),
                    0,
                    0,
                    ThemeApplier.dpToPx(ctx, 8f)
            ));
            tvOutput.setTextColor(0xFFECEFF4);
            btnCopyOut.setVisibility(View.VISIBLE);
            btnCopyOut.setTextColor(accentColor);
            btnCopyOut.setOnClickListener(v -> {
                if (listener != null) listener.onCopyToolOutput(combinedOut);
            });
        } else {
            if (tvOutputLabel != null) tvOutputLabel.setVisibility(View.GONE);
            tvOutput.setVisibility(View.GONE);
            btnCopyOut.setVisibility(View.GONE);
        }

        header.setOnClickListener(v -> {
            String execId = rec.getExecutionId();
            if (isExpanded) {
                expandedToolIds.remove(execId);
                collapsedToolIds.add(execId);
            } else {
                collapsedToolIds.remove(execId);
                expandedToolIds.add(execId);
            }
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos);
            }
        });

        if (status == ToolCallRecord.Status.WAITING_APPROVAL) {
            confirmBar.setVisibility(View.VISIBLE);
            int pillStroke = ThemeApplier.dpToPxInt(ctx, 1f);
            btnDeny.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(unsupportedColor, 22),
                    unsupportedColor,
                    pillStroke
            ));
            btnDeny.setTextColor(unsupportedColor);

            btnAllowSession.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(accentColor, 20),
                    accentColor,
                    pillStroke
            ));
            btnAllowSession.setTextColor(accentColor);

            btnAllowOnce.setBackground(GradientDrawableFactory.createPill(
                    supportedColor,
                    0,
                    0
            ));
            btnAllowOnce.setTextColor(Color.WHITE);

            btnDeny.setOnClickListener(v -> {
                if (listener != null) listener.onDenyToolCall(rec.getExecutionId());
            });
            btnAllowSession.setOnClickListener(v -> {
                if (listener != null) listener.onApproveToolCall(rec.getExecutionId(), true);
            });
            btnAllowOnce.setOnClickListener(v -> {
                if (listener != null) listener.onApproveToolCall(rec.getExecutionId(), false);
            });
        } else {
            confirmBar.setVisibility(View.GONE);
        }

        return card;
    }

    private View buildLatexBlockView(
            Context ctx,
            LayoutInflater inflater,
            ViewGroup parent,
            MarkdownRenderer.MarkdownSegment segment,
            int accentColor,
            int textColor
    ) {
        View latexView = inflater.inflate(R.layout.item_markdown_latex, parent, false);
        LinearLayout root = latexView.findViewById(R.id.latex_block_root);
        LinearLayout header = latexView.findViewById(R.id.latex_block_header);
        TextView tvLabel = latexView.findViewById(R.id.tv_latex_label);
        View btnCopy = latexView.findViewById(R.id.btn_copy_latex);
        ImageView ivCopy = latexView.findViewById(R.id.iv_copy_latex);
        TextView tvCopy = latexView.findViewById(R.id.tv_copy_latex);
        TextView tvRendered = latexView.findViewById(R.id.tv_latex_rendered);

        float r = ThemeApplier.dpToPx(ctx, 11f);
        int bgColor = palette != null ? palette.getSurfaceColor() : 0xFFFFFFFF;
        int headerBg = ThemeApplier.blendWithAlpha(accentColor, 22);
        int border = palette != null ? ThemeApplier.blendWithAlpha(palette.getBorderColor(), 120) : 0xFFD9CFC8;

        root.setBackground(GradientDrawableFactory.createSurfaceCard(
                bgColor, border, ThemeApplier.dpToPxInt(ctx, 1f), r
        ));
        header.setBackground(GradientDrawableFactory.createSurfaceCardWithRadii(
                headerBg, 0, 0, new float[]{r, r, r, r, 0f, 0f, 0f, 0f}
        ));
        tvLabel.setTextColor(accentColor);
        ivCopy.setColorFilter(accentColor);
        tvCopy.setTextColor(accentColor);
        tvRendered.setTextColor(textColor);
        tvRendered.setText(MarkdownRenderer.formatLatexExpression(segment.getContent()));

        btnCopy.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCopyCodeBlock(segment.getContent());
            } else {
                copyTextDirect(ctx, segment.getContent());
            }
        });
        return latexView;
    }

    private View buildMarkdownImageView(
            Context ctx,
            LayoutInflater inflater,
            ViewGroup parent,
            MarkdownRenderer.MarkdownSegment segment,
            int textColor
    ) {
        View imgView = inflater.inflate(R.layout.item_markdown_image, parent, false);
        LinearLayout root = imgView.findViewById(R.id.markdown_image_root);
        ImageView iv = imgView.findViewById(R.id.iv_markdown_image);
        TextView tvCaption = imgView.findViewById(R.id.tv_markdown_image_caption);

        int bg = palette != null ? palette.getSurfaceColor() : 0xFFFFFFFF;
        int border = palette != null ? ThemeApplier.blendWithAlpha(palette.getBorderColor(), 110) : 0xFFE0D6D0;
        root.setBackground(GradientDrawableFactory.createSurfaceCard(
                bg, border, ThemeApplier.dpToPxInt(ctx, 1f), ThemeApplier.dpToPx(ctx, 12f)
        ));

        String src = segment.getContent();
        String alt = segment.getImageAlt();
        if (alt != null && !alt.isEmpty()) {
            tvCaption.setVisibility(View.VISIBLE);
            tvCaption.setText(alt);
            tvCaption.setTextColor(palette != null ? palette.getTextSecondaryColor() : textColor);
        } else {
            tvCaption.setVisibility(View.GONE);
        }

        if (src.startsWith("data:image/")) {
            Bitmap bmp = decodeDataUriBitmap(src);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
            } else {
                iv.setImageResource(R.drawable.ic_photo);
            }
        } else if (src.startsWith("/") || src.startsWith("file://")) {
            String path = src.startsWith("file://") ? src.substring("file://".length()) : src;
            File file = new File(path);
            if (file.exists()) {
                Glide.with(ctx).load(file).placeholder(R.drawable.ic_photo).error(R.drawable.ic_photo).into(iv);
            } else {
                Glide.with(ctx).load(Uri.parse(src)).placeholder(R.drawable.ic_photo).error(R.drawable.ic_photo).into(iv);
            }
        } else {
            Glide.with(ctx).load(src).placeholder(R.drawable.ic_photo).error(R.drawable.ic_photo).into(iv);
        }

        root.setClickable(true);
        root.setFocusable(true);
        root.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPreviewImage(alt, src);
            } else if (src.startsWith("http://") || src.startsWith("https://")) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(src));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception ignored) {
                }
            }
        });
        return imgView;
    }

    public static Bitmap decodeDataUriBitmap(String dataUri) {
        if (dataUri == null) return null;
        int comma = dataUri.indexOf(',');
        if (comma < 0 || comma + 1 >= dataUri.length()) return null;
        try {
            byte[] decoded = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
        } catch (Exception e) {
            return null;
        }
    }

    private void bindPostMessageActions(
            MessageViewHolder holder,
            Context ctx,
            Message message,
            boolean isUser,
            boolean isStreaming,
            boolean isLastMessage
    ) {
        holder.followUpChipsContainer.removeAllViews();
        if (isUser || isStreaming || !isLastMessage || aiBusy || listener == null) {
            holder.postActionsContainer.setVisibility(View.GONE);
            holder.btnHandoverCode.setVisibility(View.GONE);
            holder.followUpChipsContainer.setVisibility(View.GONE);
            return;
        }

        boolean showHandover = currentAgentMode == AgentMode.CHAT && !message.isError();
        List<String> suggestions = message.getFollowUpSuggestions();
        boolean showSuggestions = suggestions != null && !suggestions.isEmpty() && !message.isError();

        if (!showHandover && !showSuggestions) {
            holder.postActionsContainer.setVisibility(View.GONE);
            return;
        }
        holder.postActionsContainer.setVisibility(View.VISIBLE);

        int accentColor = palette != null ? palette.getPrimaryColor() : 0xFFFF5F2B;
        int surfaceColor = palette != null ? palette.getSurfaceColor() : 0xFFFFFFFF;
        int borderColor = palette != null ? palette.getBorderColor() : 0xFFE6D4CB;
        int textPrimary = palette != null ? palette.getTextPrimaryColor() : 0xFF1F1410;
        int stroke = ThemeApplier.dpToPxInt(ctx, 1f);

        if (showHandover) {
            holder.btnHandoverCode.setVisibility(View.VISIBLE);
            holder.btnHandoverCode.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(accentColor, 26),
                    accentColor,
                    stroke
            ));
            holder.ivHandoverCode.setColorFilter(accentColor);
            holder.tvHandoverCode.setTextColor(accentColor);
            holder.btnHandoverCode.setOnClickListener(v -> listener.onHandoverToCode(message));
        } else {
            holder.btnHandoverCode.setVisibility(View.GONE);
        }

        if (showSuggestions) {
            holder.followUpChipsContainer.setVisibility(View.VISIBLE);
            for (String suggestion : suggestions) {
                if (suggestion == null || suggestion.trim().isEmpty()) continue;
                TextView chip = new TextView(ctx);
                chip.setText(suggestion.trim());
                chip.setTextSize(12f);
                chip.setTextColor(textPrimary);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setMinHeight(ThemeApplier.dpToPxInt(ctx, 34f));
                chip.setPadding(
                        ThemeApplier.dpToPxInt(ctx, 12f),
                        ThemeApplier.dpToPxInt(ctx, 6f),
                        ThemeApplier.dpToPxInt(ctx, 12f),
                        ThemeApplier.dpToPxInt(ctx, 6f)
                );
                chip.setBackground(GradientDrawableFactory.createPill(
                        surfaceColor,
                        ThemeApplier.blendWithAlpha(borderColor, 140),
                        stroke
                ));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.bottomMargin = ThemeApplier.dpToPxInt(ctx, 4f);
                chip.setLayoutParams(lp);
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> listener.onFollowUpClicked(suggestion.trim()));
                holder.followUpChipsContainer.addView(chip);
            }
        } else {
            holder.followUpChipsContainer.setVisibility(View.GONE);
        }
    }

    private View buildTableView(Context ctx, MarkdownRenderer.MarkdownSegment segment, int inlineCodeBg, int accentColor) {
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scrollLp.topMargin = ThemeApplier.dpToPxInt(ctx, 6f);
        scrollLp.bottomMargin = ThemeApplier.dpToPxInt(ctx, 6f);
        scroll.setLayoutParams(scrollLp);
        scroll.setHorizontalScrollBarEnabled(true);

        TableLayout table = new TableLayout(ctx);
        table.setBackground(GradientDrawableFactory.createSurfaceCard(
                palette != null ? palette.getSurfaceColor() : 0xFFFFFFFF,
                palette != null ? palette.getBorderColor() : 0xFFE0D6D0,
                ThemeApplier.dpToPxInt(ctx, 1f),
                ThemeApplier.dpToPx(ctx, 10f)
        ));
        int cellPadH = ThemeApplier.dpToPxInt(ctx, 10f);
        int cellPadV = ThemeApplier.dpToPxInt(ctx, 7f);

        // Header Row
        TableRow headerRow = new TableRow(ctx);
        headerRow.setBackgroundColor(palette != null
                ? ThemeApplier.blendWithAlpha(accentColor, 26)
                : 0x1AFF5F2B);
        for (String header : segment.getTableHeaders()) {
            TextView tv = new TextView(ctx);
            tv.setText(MarkdownRenderer.formatMarkdownText(header, inlineCodeBg, accentColor));
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextSize(13f);
            tv.setPadding(cellPadH, cellPadV, cellPadH, cellPadV);
            if (palette != null) tv.setTextColor(palette.getTextPrimaryColor());
            headerRow.addView(tv);
        }
        table.addView(headerRow);

        // Body Rows
        List<List<String>> rows = segment.getTableRows();
        for (int r = 0; r < rows.size(); r++) {
            TableRow tr = new TableRow(ctx);
            if (r % 2 == 1 && palette != null) {
                tr.setBackgroundColor(ThemeApplier.blendWithAlpha(palette.getSurfaceVariantColor(), 130));
            }
            for (String cell : rows.get(r)) {
                TextView tv = new TextView(ctx);
                tv.setText(MarkdownRenderer.formatMarkdownText(cell, inlineCodeBg, accentColor));
                tv.setTextSize(13f);
                tv.setPadding(cellPadH, cellPadV, cellPadH, cellPadV);
                if (palette != null) {
                    tv.setTextColor(palette.getTextPrimaryColor());
                    tv.setLinkTextColor(accentColor);
                }
                enableUrlSpanClicks(ctx, tv);
                tr.addView(tv);
            }
            table.addView(tr);
        }

        scroll.addView(table);
        return scroll;
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void enableUrlSpanClicks(Context ctx, TextView tv) {
        tv.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP) {
                return false;
            }
            CharSequence text = tv.getText();
            if (!(text instanceof Spannable)) {
                return false;
            }
            Spannable buffer = (Spannable) text;
            int x = (int) event.getX() - tv.getTotalPaddingLeft() + tv.getScrollX();
            int y = (int) event.getY() - tv.getTotalPaddingTop() + tv.getScrollY();
            Layout layout = tv.getLayout();
            if (layout == null) return false;
            int line = layout.getLineForVertical(y);
            int off = layout.getOffsetForHorizontal(line, x);
            URLSpan[] links = buffer.getSpans(off, off, URLSpan.class);
            if (links != null && links.length > 0) {
                String url = links[0].getURL();
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(ctx, url, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private static String firstLineSnippet(String text) {
        if (text == null) return "";
        String single = text.trim().replace('\n', ' ').replaceAll("\\s+", " ");
        return single.length() > 70 ? single.substring(0, 70) + "…" : single;
    }

    private static void copyTextDirect(Context ctx, String value) {
        ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("ChatAI", value));
            Toast.makeText(ctx, R.string.msg_copied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public static class MessageViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout rowRoot;
        final LinearLayout headerRow;
        final FrameLayout assistantAvatarBadge;
        final ImageView ivAssistantAvatar;
        final MaxWidthLinearLayout toolCardsContainer;
        final MaxWidthLinearLayout timelineContainer;
        final MaxWidthLinearLayout bubbleContainer;
        final MaxWidthLinearLayout sourcesContainer;
        final TextView tvRole;
        final TextView tvModel;
        final TextView tvAccessBadge;
        final TextView tvDeepThinking;
        final TextView tvEdited;
        final MaxWidthLinearLayout thinkingContainer;
        final LinearLayout btnToggleThinking;
        final ImageView ivThinkingIcon;
        final TextView tvThinkingHeader;
        final TextView tvThinkingToggle;
        final TextView tvThinkingContent;
        final RecyclerView rvAttachments;
        final LinearLayout markdownContainer;
        final LinearLayout footerRow;
        final TextView tvTime;
        final TextView tvTokens;
        final View spacerFooter;
        final ImageButton btnCopy;
        final ImageButton btnRegenerate;
        final ImageButton btnEdit;
        final ImageButton btnDelete;
        final MaxWidthLinearLayout postActionsContainer;
        final LinearLayout btnHandoverCode;
        final ImageView ivHandoverCode;
        final TextView tvHandoverCode;
        final LinearLayout followUpChipsContainer;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            rowRoot = itemView.findViewById(R.id.message_row_root);
            headerRow = itemView.findViewById(R.id.layout_message_header);
            assistantAvatarBadge = itemView.findViewById(R.id.avatar_assistant_badge);
            ivAssistantAvatar = itemView.findViewById(R.id.iv_assistant_avatar);
            toolCardsContainer = itemView.findViewById(R.id.ll_tool_cards_container);
            timelineContainer = itemView.findViewById(R.id.ll_timeline_container);
            bubbleContainer = itemView.findViewById(R.id.message_bubble_container);
            sourcesContainer = itemView.findViewById(R.id.ll_sources_container);
            tvRole = itemView.findViewById(R.id.tv_message_role);
            tvModel = itemView.findViewById(R.id.tv_message_model);
            tvAccessBadge = itemView.findViewById(R.id.tv_message_access_badge);
            tvDeepThinking = itemView.findViewById(R.id.tv_message_deep_thinking);
            tvEdited = itemView.findViewById(R.id.tv_message_edited);
            thinkingContainer = itemView.findViewById(R.id.thinking_process_container);
            btnToggleThinking = itemView.findViewById(R.id.btn_toggle_thinking);
            ivThinkingIcon = itemView.findViewById(R.id.iv_thinking_icon);
            tvThinkingHeader = itemView.findViewById(R.id.tv_thinking_header);
            tvThinkingToggle = itemView.findViewById(R.id.tv_thinking_toggle);
            tvThinkingContent = itemView.findViewById(R.id.tv_thinking_content);
            rvAttachments = itemView.findViewById(R.id.rv_message_attachments);
            markdownContainer = itemView.findViewById(R.id.markdown_segments_container);
            footerRow = itemView.findViewById(R.id.layout_message_footer);
            tvTime = itemView.findViewById(R.id.tv_message_time);
            tvTokens = itemView.findViewById(R.id.tv_message_tokens);
            spacerFooter = itemView.findViewById(R.id.spacer_footer_actions);
            btnCopy = itemView.findViewById(R.id.btn_msg_copy);
            btnRegenerate = itemView.findViewById(R.id.btn_msg_regenerate);
            btnEdit = itemView.findViewById(R.id.btn_msg_edit);
            btnDelete = itemView.findViewById(R.id.btn_msg_delete);
            postActionsContainer = itemView.findViewById(R.id.ll_post_message_actions);
            btnHandoverCode = itemView.findViewById(R.id.btn_msg_handover_code);
            ivHandoverCode = itemView.findViewById(R.id.iv_handover_code);
            tvHandoverCode = itemView.findViewById(R.id.tv_handover_code);
            followUpChipsContainer = itemView.findViewById(R.id.ll_follow_up_chips_container);
        }
    }
}
