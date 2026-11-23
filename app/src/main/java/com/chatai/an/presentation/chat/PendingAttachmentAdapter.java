package com.chatai.an.presentation.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.chatai.an.R;
import com.chatai.an.core.AttachmentStorageHelper;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.AttachmentType;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.presentation.common.AttachmentPreviewDialogHelper;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;

import java.util.ArrayList;
import java.util.List;

public class PendingAttachmentAdapter extends RecyclerView.Adapter<PendingAttachmentAdapter.ViewHolder> {

    public interface OnAttachmentInteractionListener {
        void onPreview(Attachment attachment);
        void onRemove(Attachment attachment);
    }

    private final List<Attachment> items = new ArrayList<>();
    private final OnAttachmentInteractionListener listener;
    private ThemePalette palette;

    public PendingAttachmentAdapter(OnAttachmentInteractionListener listener) {
        this.listener = listener;
    }

    public void submitList(List<Attachment> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public void setPalette(ThemePalette palette) {
        this.palette = palette;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attachment_chip, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Attachment item = items.get(position);
        Context ctx = holder.itemView.getContext();

        if (palette != null) {
            holder.card.setBackground(GradientDrawableFactory.createSurfaceCard(
                    palette.getSurfaceVariantColor(),
                    palette.getBorderColor(),
                    ThemeApplier.dpToPxInt(ctx, 1f),
                    ThemeApplier.dpToPx(ctx, 12f)
            ));
            holder.btnRemove.setBackground(GradientDrawableFactory.createPill(
                    palette.getPrimaryColor(),
                    0,
                    0
            ));
            holder.btnRemove.setColorFilter(palette.getOnPrimaryColor());
            holder.tvFileName.setTextColor(palette.getTextPrimaryColor());
            holder.tvFileSize.setTextColor(palette.getTextSecondaryColor());
            holder.ivFileIcon.setColorFilter(palette.getPrimaryColor());
        }

        if (item.getType() == AttachmentType.IMAGE) {
            holder.ivThumb.setVisibility(View.VISIBLE);
            holder.fileInfoBox.setVisibility(View.GONE);
            Glide.with(ctx)
                    .load(AttachmentPreviewDialogHelper.resolveImageModel(item))
                    .centerCrop()
                    .into(holder.ivThumb);
        } else {
            holder.ivThumb.setVisibility(View.GONE);
            holder.fileInfoBox.setVisibility(View.VISIBLE);
            holder.tvFileName.setText(item.getDisplayName());
            holder.tvFileSize.setText(AttachmentStorageHelper.formatFileSize(item.getSizeBytes()));
        }

        holder.card.setOnClickListener(v -> {
            if (listener != null) listener.onPreview(item);
        });
        holder.btnRemove.setOnClickListener(v -> {
            if (listener != null) listener.onRemove(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final ImageView ivThumb;
        final LinearLayout fileInfoBox;
        final ImageView ivFileIcon;
        final TextView tvFileName;
        final TextView tvFileSize;
        final ImageButton btnRemove;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.attachment_chip_card);
            ivThumb = itemView.findViewById(R.id.iv_attachment_thumb);
            fileInfoBox = itemView.findViewById(R.id.file_info_box);
            ivFileIcon = itemView.findViewById(R.id.iv_file_icon);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvFileSize = itemView.findViewById(R.id.tv_file_size);
            btnRemove = itemView.findViewById(R.id.btn_remove_attachment);
        }
    }
}
