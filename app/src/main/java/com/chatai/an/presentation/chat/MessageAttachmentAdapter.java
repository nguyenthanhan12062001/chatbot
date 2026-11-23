package com.chatai.an.presentation.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.chatai.an.R;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.AttachmentType;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.presentation.common.AttachmentPreviewDialogHelper;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;

import java.util.List;

public class MessageAttachmentAdapter extends RecyclerView.Adapter<MessageAttachmentAdapter.ViewHolder> {

    public interface OnAttachmentClickListener {
        void onAttachmentClick(Attachment attachment);
    }

    private final List<Attachment> items;
    private final ThemePalette palette;
    private final OnAttachmentClickListener listener;

    public MessageAttachmentAdapter(
            List<Attachment> items,
            ThemePalette palette,
            OnAttachmentClickListener listener
    ) {
        this.items = items;
        this.palette = palette;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message_attachment, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Attachment item = items.get(position);
        Context ctx = holder.itemView.getContext();

        if (palette != null) {
            holder.card.setBackground(GradientDrawableFactory.createSurfaceCard(
                    palette.getSurfaceColor(),
                    palette.getBorderColor(),
                    ThemeApplier.dpToPxInt(ctx, 1f),
                    ThemeApplier.dpToPx(ctx, 10f)
            ));
            holder.tvFileName.setTextColor(palette.getTextPrimaryColor());
            holder.ivFileIcon.setColorFilter(palette.getPrimaryColor());
        }

        if (item.getType() == AttachmentType.IMAGE) {
            holder.ivThumb.setVisibility(View.VISIBLE);
            holder.fileBox.setVisibility(View.GONE);
            Glide.with(ctx)
                    .load(AttachmentPreviewDialogHelper.resolveImageModel(item))
                    .centerCrop()
                    .into(holder.ivThumb);
        } else {
            holder.ivThumb.setVisibility(View.GONE);
            holder.fileBox.setVisibility(View.VISIBLE);
            holder.tvFileName.setText(item.getDisplayName());
        }

        holder.card.setOnClickListener(v -> {
            if (listener != null) listener.onAttachmentClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final ImageView ivThumb;
        final LinearLayout fileBox;
        final ImageView ivFileIcon;
        final TextView tvFileName;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.msg_attachment_card);
            ivThumb = itemView.findViewById(R.id.iv_msg_att_thumb);
            fileBox = itemView.findViewById(R.id.msg_att_file_box);
            ivFileIcon = itemView.findViewById(R.id.iv_msg_att_icon);
            tvFileName = itemView.findViewById(R.id.tv_msg_att_name);
        }
    }
}
