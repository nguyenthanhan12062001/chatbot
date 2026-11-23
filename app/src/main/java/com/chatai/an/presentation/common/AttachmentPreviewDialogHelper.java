package com.chatai.an.presentation.common;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.chatai.an.R;
import com.chatai.an.core.AttachmentStorageHelper;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.AttachmentType;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public final class AttachmentPreviewDialogHelper {

    private AttachmentPreviewDialogHelper() {
    }

    public static void show(Context context, Attachment attachment) {
        if (context == null || attachment == null) return;
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_attachment_preview, null);
        ImageView ivImage = view.findViewById(R.id.iv_preview_image);
        TextView tvName = view.findViewById(R.id.tv_preview_file_name);
        TextView tvType = view.findViewById(R.id.tv_preview_file_type);
        TextView tvSize = view.findViewById(R.id.tv_preview_file_size);

        tvName.setText(attachment.getDisplayName());
        tvType.setText(context.getString(R.string.attachment_file_type_label, attachment.getMimeType()));
        tvSize.setText(context.getString(
                R.string.attachment_file_size_label,
                AttachmentStorageHelper.formatFileSize(attachment.getSizeBytes())
        ));

        if (attachment.getType() == AttachmentType.IMAGE) {
            ivImage.setVisibility(View.VISIBLE);
            Object model = resolveImageModel(attachment);
            Glide.with(context).load(model).into(ivImage);
        } else {
            ivImage.setVisibility(View.GONE);
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.attachment_preview_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static Object resolveImageModel(Attachment attachment) {
        if (attachment.getLocalPath() != null && !attachment.getLocalPath().isEmpty()) {
            File f = new File(attachment.getLocalPath());
            if (f.exists()) return f;
        }
        return Uri.parse(attachment.getUriString());
    }
}
