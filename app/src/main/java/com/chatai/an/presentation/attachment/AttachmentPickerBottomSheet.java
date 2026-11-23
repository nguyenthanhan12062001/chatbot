package com.chatai.an.presentation.attachment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.chatai.an.R;
import com.chatai.an.app.AppContainer;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.presentation.common.UiFormatters;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class AttachmentPickerBottomSheet extends BottomSheetDialogFragment {

    public interface OnAttachmentActionListener {
        void onPickCamera();
        void onPickPhotos();
        void onPickFiles();
    }

    private OnAttachmentActionListener listener;

    public void setOnAttachmentActionListener(OnAttachmentActionListener listener) {
        this.listener = listener;
    }

    @Override
    public int getTheme() {
        return R.style.ThemeOverlay_ChatAI_BottomSheet;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.bottom_sheet_attachment_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ChatAiApplication app = (ChatAiApplication) requireActivity().getApplication();
        AppContainer container = app.getAppContainer();

        ThemeConfig themeConfig = container.getThemeRepository().getActiveThemeSnapshot();
        ThemePalette palette = ThemeManager.resolvePalette(requireContext(), themeConfig);
        AiModel model = container.getModelRepository().getSelectedModelSnapshot();

        View root = view.findViewById(R.id.attachment_sheet_root);
        View handle = view.findViewById(R.id.attachment_sheet_handle);
        TextView tvTitle = view.findViewById(R.id.tv_attachment_sheet_title);
        TextView tvNote = view.findViewById(R.id.tv_attachment_capability_note);
        TextView tvWarning = view.findViewById(R.id.tv_attachment_model_warning);

        LinearLayout btnCamera = view.findViewById(R.id.btn_pick_camera);
        LinearLayout btnPhotos = view.findViewById(R.id.btn_pick_photos);
        LinearLayout btnFiles = view.findViewById(R.id.btn_pick_files);

        ImageView ivCamera = view.findViewById(R.id.iv_pick_camera);
        ImageView ivPhotos = view.findViewById(R.id.iv_pick_photos);
        ImageView ivFiles = view.findViewById(R.id.iv_pick_files);

        TextView tvCam = view.findViewById(R.id.tv_pick_camera);
        TextView tvCamSub = view.findViewById(R.id.tv_pick_camera_sub);
        TextView tvPho = view.findViewById(R.id.tv_pick_photos);
        TextView tvPhoSub = view.findViewById(R.id.tv_pick_photos_sub);
        TextView tvFil = view.findViewById(R.id.tv_pick_files);
        TextView tvFilSub = view.findViewById(R.id.tv_pick_files_sub);

        root.setBackgroundColor(palette.getBackgroundColor());
        handle.setBackground(GradientDrawableFactory.createPill(palette.getBorderColor(), 0, 0));
        tvTitle.setTextColor(palette.getTextPrimaryColor());
        tvNote.setTextColor(palette.getTextSecondaryColor());

        float radius = ThemeApplier.dpToPx(requireContext(), 16f);
        int stroke = ThemeApplier.dpToPxInt(requireContext(), 1f);
        btnCamera.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));
        btnPhotos.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));
        btnFiles.setBackground(GradientDrawableFactory.createSurfaceCard(palette.getSurfaceColor(), palette.getBorderColor(), stroke, radius));

        ivCamera.setColorFilter(palette.getPrimaryColor());
        ivPhotos.setColorFilter(palette.getSecondaryColor());
        ivFiles.setColorFilter(palette.getAccentColor());

        tvCam.setTextColor(palette.getTextPrimaryColor());
        tvCamSub.setTextColor(palette.getTextSecondaryColor());
        tvPho.setTextColor(palette.getTextPrimaryColor());
        tvPhoSub.setTextColor(palette.getTextSecondaryColor());
        tvFil.setTextColor(palette.getTextPrimaryColor());
        tvFilSub.setTextColor(palette.getTextSecondaryColor());

        // Distinguish device picking from model capability status (SUPPORTED vs UNSUPPORTED vs UNKNOWN)
        CapabilityStatus imgCap = model.getCapabilityStatus(ModelCapability.IMAGE);
        CapabilityStatus fileCap = model.getCapabilityStatus(ModelCapability.FILE);
        StringBuilder warnBuilder = new StringBuilder();
        if (imgCap != CapabilityStatus.SUPPORTED) {
            warnBuilder.append(getString(
                    R.string.model_capability_note_image,
                    model.getName(),
                    UiFormatters.formatCapabilityStatus(requireContext(), imgCap)
            ));
        }
        if (fileCap != CapabilityStatus.SUPPORTED) {
            if (warnBuilder.length() > 0) warnBuilder.append("\n");
            warnBuilder.append(getString(
                    R.string.model_capability_note_file,
                    model.getName(),
                    UiFormatters.formatCapabilityStatus(requireContext(), fileCap)
            ));
        }

        if (warnBuilder.length() > 0) {
            int warnColor = ContextCompat.getColor(requireContext(), R.color.status_unknown);
            tvWarning.setVisibility(View.VISIBLE);
            tvWarning.setText(warnBuilder.toString());
            tvWarning.setTextColor(palette.getTextPrimaryColor());
            tvWarning.setBackground(GradientDrawableFactory.createSurfaceCard(
                    ThemeApplier.blendWithAlpha(warnColor, 28),
                    warnColor,
                    stroke,
                    ThemeApplier.dpToPx(requireContext(), 12f)
            ));
        } else {
            tvWarning.setVisibility(View.GONE);
        }

        btnCamera.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onPickCamera();
        });
        btnPhotos.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onPickPhotos();
        });
        btnFiles.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onPickFiles();
        });
    }
}
