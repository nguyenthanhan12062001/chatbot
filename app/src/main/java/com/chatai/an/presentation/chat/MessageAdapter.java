package com.chatai.an.presentation.chat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
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

        // Distinct User (right-aligned bubble) vs AI (left-aligned naturally wide editorial block)
        LinearLayout.LayoutParams containerLp = (LinearLayout.LayoutParams) holder.bubbleContainer.getLayoutParams();
        if (isUser) {
            holder.rowRoot.setGravity(Gravity.END);
            containerLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            containerLp.gravity = Gravity.END;
            containerLp.setMarginStart(ThemeApplier.dpToPxInt(ctx, 56f));
            containerLp.setMarginEnd(0);
            int padH = ThemeApplier.dpToPxInt(ctx, 14f);
            int padV = ThemeApplier.dpToPxInt(ctx, 11f);
            holder.bubbleContainer.setPadding(padH, padV, padH, padV);
            holder.tvModel.setVisibility(View.GONE);
        } else {
            holder.rowRoot.setGravity(Gravity.START);
            containerLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            containerLp.gravity = Gravity.START;
            containerLp.setMarginStart(0);
            containerLp.setMarginEnd(0);
            int padH = ThemeApplier.dpToPxInt(ctx, 6f);
            int padV = ThemeApplier.dpToPxInt(ctx, 8f);
            holder.bubbleContainer.setPadding(padH, padV, padH, padV);
            holder.tvModel.setVisibility(View.VISIBLE);
        }
        holder.bubbleContainer.setLayoutParams(containerLp);

        holder.tvRole.setText(isUser ? R.string.role_user : R.string.role_assistant);

        AiModel model = modelRepository.getModelById(message.getModelId());
        holder.tvModel.setText(model != null ? model.getName() : message.getModelId());

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
            holder.spacerFooter.setVisibility(View.GONE);
            holder.tvTokens.setText(usage.getPromptTokens() + " in · " + usage.getCompletionTokens() + " out (" + usage.getTotalTokens() + ")");
        } else {
            holder.tvTokens.setVisibility(View.GONE);
            holder.spacerFooter.setVisibility(View.VISIBLE);
        }

        if (palette != null) {
            int borderCol = message.isError()
                    ? ContextCompat.getColor(ctx, R.color.status_unsupported)
                    : palette.getBorderColor();
            if (isUser) {
                holder.bubbleContainer.setBackground(GradientDrawableFactory.createSurfaceCard(
                        palette.getSurfaceVariantColor(),
                        palette.getBorderColor(),
                        ThemeApplier.dpToPxInt(ctx, 1f),
                        ThemeApplier.dpToPx(ctx, 20f)
                ));
                holder.tvRole.setTextColor(palette.getPrimaryColor());
            } else if (message.isError()) {
                holder.bubbleContainer.setBackground(GradientDrawableFactory.createSurfaceCard(
                        ThemeApplier.blendWithAlpha(ContextCompat.getColor(ctx, R.color.status_unsupported), 18),
                        borderCol,
                        ThemeApplier.dpToPxInt(ctx, 1f),
                        ThemeApplier.dpToPx(ctx, 14f)
                ));
                holder.tvRole.setTextColor(ContextCompat.getColor(ctx, R.color.status_unsupported));
            } else {
                // Clean natural wide layout for AI response without constraining bubble border
                holder.bubbleContainer.setBackground(null);
                holder.tvRole.setTextColor(palette.getSecondaryColor());
            }
            holder.tvModel.setTextColor(palette.getTextSecondaryColor());
            holder.tvTime.setTextColor(palette.getTextSecondaryColor());
            holder.tvTokens.setTextColor(palette.getTextSecondaryColor());
            holder.tvEdited.setTextColor(palette.getTextSecondaryColor());
            holder.tvDeepThinking.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(palette.getPrimaryColor(), 36),
                    palette.getPrimaryColor(),
                    ThemeApplier.dpToPxInt(ctx, 1f)
            ));
            holder.tvDeepThinking.setTextColor(palette.getPrimaryColor());

            int accessColor = accessType == AccessType.FREE
                    ? ContextCompat.getColor(ctx, R.color.status_supported)
                    : palette.getPrimaryColor();
            holder.tvAccessBadge.setBackground(GradientDrawableFactory.createPill(
                    ThemeApplier.blendWithAlpha(accessColor, 26),
                    0,
                    0
            ));
            holder.tvAccessBadge.setTextColor(accessColor);

            holder.thinkingContainer.setBackground(GradientDrawableFactory.createSurfaceCard(
                    palette.getSurfaceVariantColor(),
                    palette.getBorderColor(),
                    ThemeApplier.dpToPxInt(ctx, 1f),
                    ThemeApplier.dpToPx(ctx, 12f)
            ));
            holder.ivThinkingIcon.setColorFilter(palette.getPrimaryColor());
            holder.tvThinkingHeader.setTextColor(palette.getPrimaryColor());
            holder.tvThinkingToggle.setTextColor(palette.getTextSecondaryColor());
            holder.tvThinkingContent.setTextColor(palette.getTextSecondaryColor());

            holder.btnCopy.setColorFilter(palette.getTextSecondaryColor());
            holder.btnEdit.setColorFilter(palette.getTextSecondaryColor());
            holder.btnDelete.setColorFilter(palette.getTextSecondaryColor());
        }

        // Collapsible Thinking Process
        String thinking = message.getThinkingContent();
        if (thinking != null && !thinking.trim().isEmpty()) {
            holder.thinkingContainer.setVisibility(View.VISIBLE);
            boolean expanded = expandedThinkingIds.contains(message.getId());
            holder.tvThinkingContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
            holder.tvThinkingToggle.setText(expanded
                    ? R.string.thinking_process_toggle_hide
                    : R.string.thinking_process_toggle_show);
            holder.tvThinkingContent.setText(thinking.trim());
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
        if (attachments != null && !attachments.isEmpty()) {
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
        String content = message.getContent();
        if (content != null && !content.isEmpty()) {
            holder.markdownContainer.setVisibility(View.VISIBLE);
            LayoutInflater inflater = LayoutInflater.from(ctx);
            int primaryColor = palette != null ? palette.getPrimaryColor() : 0xFFFF5F2B;
            int inlineCodeBg = ThemeApplier.blendWithAlpha(primaryColor, 32);

            List<MarkdownRenderer.MarkdownSegment> segments = MarkdownRenderer.parseSegments(content);
            for (MarkdownRenderer.MarkdownSegment segment : segments) {
                if (segment.getType() == MarkdownRenderer.SegmentType.CODE_BLOCK) {
                    View codeView = inflater.inflate(R.layout.item_markdown_code_block, holder.markdownContainer, false);
                    LinearLayout codeRoot = codeView.findViewById(R.id.code_block_root);
                    LinearLayout codeHeader = codeView.findViewById(R.id.code_block_header);
                    TextView tvLang = codeView.findViewById(R.id.tv_code_language);
                    TextView tvCode = codeView.findViewById(R.id.tv_code_content);
                    View btnCopyCode = codeView.findViewById(R.id.btn_copy_code);

                    codeRoot.setBackground(GradientDrawableFactory.createSurfaceCard(
                            ContextCompat.getColor(ctx, R.color.code_block_bg),
                            palette != null ? palette.getBorderColor() : 0xFF3A3533,
                            ThemeApplier.dpToPxInt(ctx, 1f),
                            ThemeApplier.dpToPx(ctx, 12f)
                    ));
                    codeHeader.setBackgroundColor(ContextCompat.getColor(ctx, R.color.code_block_header));
                    tvLang.setText(segment.getLanguage().isEmpty() ? "code" : segment.getLanguage());
                    tvCode.setText(segment.getContent());
                    btnCopyCode.setOnClickListener(v -> {
                        if (listener != null) listener.onCopyCodeBlock(segment.getContent());
                    });

                    holder.markdownContainer.addView(codeView);
                } else if (segment.getType() == MarkdownRenderer.SegmentType.TABLE) {
                    holder.markdownContainer.addView(buildTableView(ctx, segment, inlineCodeBg, primaryColor));
                } else {
                    View textViewItem = inflater.inflate(R.layout.item_markdown_text, holder.markdownContainer, false);
                    TextView tvText = textViewItem.findViewById(R.id.tv_markdown_segment);
                    if (palette != null) {
                        tvText.setTextColor(palette.getTextPrimaryColor());
                        tvText.setLinkTextColor(palette.getPrimaryColor());
                    }
                    tvText.setText(MarkdownRenderer.formatMarkdownText(segment.getContent(), inlineCodeBg, primaryColor));
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
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

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout rowRoot;
        final LinearLayout bubbleContainer;
        final TextView tvRole;
        final TextView tvModel;
        final TextView tvAccessBadge;
        final TextView tvDeepThinking;
        final TextView tvEdited;
        final LinearLayout thinkingContainer;
        final LinearLayout btnToggleThinking;
        final ImageView ivThinkingIcon;
        final TextView tvThinkingHeader;
        final TextView tvThinkingToggle;
        final TextView tvThinkingContent;
        final RecyclerView rvAttachments;
        final LinearLayout markdownContainer;
        final TextView tvTime;
        final TextView tvTokens;
        final View spacerFooter;
        final ImageButton btnCopy;
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            rowRoot = itemView.findViewById(R.id.message_row_root);
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
            tvTime = itemView.findViewById(R.id.tv_message_time);
            tvTokens = itemView.findViewById(R.id.tv_message_tokens);
            spacerFooter = itemView.findViewById(R.id.spacer_footer_actions);
            btnCopy = itemView.findViewById(R.id.btn_msg_copy);
            btnEdit = itemView.findViewById(R.id.btn_msg_edit);
            btnDelete = itemView.findViewById(R.id.btn_msg_delete);
        }
    }
}
