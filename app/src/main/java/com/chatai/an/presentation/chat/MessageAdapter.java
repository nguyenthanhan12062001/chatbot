package com.chatai.an.presentation.chat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.Spannable;
import android.text.style.URLSpan;
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

import com.chatai.an.R;
import com.chatai.an.core.MarkdownRenderer;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.domain.model.TokenUsage;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.presentation.common.UiFormatters;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    public interface OnMessageActionListener {
        void onCopyMessage(Message message);
        void onEditMessage(Message message);
        void onDeleteMessage(Message message);
        void onCopyCodeBlock(String code);
        void onPreviewAttachment(Attachment attachment);
    }

    private final List<Message> messages = new ArrayList<>();
    private final Set<String> expandedThinkingIds = new HashSet<>();
    private final ModelRepository modelRepository;
    private final OnMessageActionListener listener;
    private ThemePalette palette;

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

        // Configure external thinking container & footer alignment
        LinearLayout.LayoutParams thinkingLp = (LinearLayout.LayoutParams) holder.thinkingContainer.getLayoutParams();
        thinkingLp.gravity = isUser ? Gravity.END : Gravity.START;
        thinkingLp.setMarginStart(isUser ? 0 : ThemeApplier.dpToPxInt(ctx, 2f));
        thinkingLp.setMarginEnd(isUser ? ThemeApplier.dpToPxInt(ctx, 2f) : 0);
        holder.thinkingContainer.setMaxWidthRatio(0.86f);
        holder.thinkingContainer.setMaxWidthPx(maxTabletBubblePx);
        holder.thinkingContainer.setLayoutParams(thinkingLp);

        LinearLayout.LayoutParams footerLp = (LinearLayout.LayoutParams) holder.footerRow.getLayoutParams();
        footerLp.gravity = isUser ? Gravity.END : Gravity.START;
        footerLp.setMarginStart(isUser ? 0 : ThemeApplier.dpToPxInt(ctx, 4f));
        footerLp.setMarginEnd(isUser ? ThemeApplier.dpToPxInt(ctx, 2f) : 0);
        holder.footerRow.setLayoutParams(footerLp);

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
            holder.btnEdit.setColorFilter(palette.getTextSecondaryColor());
            holder.btnDelete.setColorFilter(palette.getTextSecondaryColor());
            holder.btnCopy.setAlpha(0.76f);
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

        // Collapsible Thinking Process Pill/Drawer (outside bubble)
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

        // Markdown, Tables & Code Block rendering
        holder.markdownContainer.removeAllViews();
        LinearLayout.LayoutParams mdLp = (LinearLayout.LayoutParams) holder.markdownContainer.getLayoutParams();
        String content = message.getContent();
        boolean hasTextContent = content != null && !content.isEmpty();
        mdLp.topMargin = (hasAttachments && hasTextContent) ? ThemeApplier.dpToPxInt(ctx, 6f) : 0;
        holder.markdownContainer.setLayoutParams(mdLp);

        if (hasTextContent) {
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
            holder.markdownContainer.setVisibility(View.VISIBLE);
            TextView tvPlaceholder = new TextView(ctx);
            tvPlaceholder.setText("…");
            if (palette != null) tvPlaceholder.setTextColor(palette.getTextSecondaryColor());
            holder.markdownContainer.addView(tvPlaceholder);
        } else {
            holder.markdownContainer.setVisibility(View.GONE);
        }

        holder.btnCopy.setOnClickListener(v -> {
            if (listener != null) listener.onCopyMessage(message);
        });
        holder.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEditMessage(message);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteMessage(message);
        });
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
                        ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        Toast.makeText(ctx, url, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            }
            return false;
        });
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
        final MaxWidthLinearLayout bubbleContainer;
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
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            rowRoot = itemView.findViewById(R.id.message_row_root);
            headerRow = itemView.findViewById(R.id.layout_message_header);
            assistantAvatarBadge = itemView.findViewById(R.id.avatar_assistant_badge);
            ivAssistantAvatar = itemView.findViewById(R.id.iv_assistant_avatar);
            bubbleContainer = itemView.findViewById(R.id.message_bubble_container);
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
            btnEdit = itemView.findViewById(R.id.btn_msg_edit);
            btnDelete = itemView.findViewById(R.id.btn_msg_delete);
        }
    }
}
