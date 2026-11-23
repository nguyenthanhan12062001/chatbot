package com.chatai.an.presentation.model;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatai.an.R;
import com.chatai.an.app.AppContainer;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class ModelSelectorBottomSheet extends BottomSheetDialogFragment {

    public interface OnModelChosenListener {
        void onModelChosen(AiModel model);
    }

    private OnModelChosenListener listener;
    private AccessType selectedFilter = null;

    public void setOnModelChosenListener(OnModelChosenListener listener) {
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
        return inflater.inflate(R.layout.bottom_sheet_model_selector, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ChatAiApplication app = (ChatAiApplication) requireActivity().getApplication();
        AppContainer container = app.getAppContainer();

        ThemeConfig activeTheme = container.getThemeRepository().getActiveThemeSnapshot();
        ThemePalette palette = ThemeManager.resolvePalette(requireContext(), activeTheme);

        View root = view.findViewById(R.id.model_sheet_root);
        View handle = view.findViewById(R.id.model_sheet_handle);
        TextView tvTitle = view.findViewById(R.id.tv_model_sheet_title);
        TextView tvSubtitle = view.findViewById(R.id.tv_model_sheet_subtitle);
        TextView btnSync = view.findViewById(R.id.btn_sync_catalogs);
        TextView tabAll = view.findViewById(R.id.tab_filter_all);
        TextView tabFree = view.findViewById(R.id.tab_filter_free);
        TextView tabPersonal = view.findViewById(R.id.tab_filter_personal);
        TextView tabLocal = view.findViewById(R.id.tab_filter_local);
        RecyclerView rvCatalog = view.findViewById(R.id.rv_model_catalog);

        root.setBackgroundColor(palette.getBackgroundColor());
        handle.setBackground(GradientDrawableFactory.createPill(palette.getBorderColor(), 0, 0));
        tvTitle.setTextColor(palette.getTextPrimaryColor());
        tvSubtitle.setTextColor(palette.getTextSecondaryColor());

        btnSync.setBackground(GradientDrawableFactory.createPill(
                ThemeApplier.blendWithAlpha(palette.getPrimaryColor(), 28),
                palette.getPrimaryColor(),
                ThemeApplier.dpToPxInt(requireContext(), 1f)
        ));
        btnSync.setTextColor(palette.getPrimaryColor());

        AiModel current = container.getModelRepository().getSelectedModelSnapshot();
        ModelCatalogAdapter adapter = new ModelCatalogAdapter(
                container.getModelRepository().getGroupedModels(),
                current.getId(),
                palette,
                model -> {
                    if (model.getUsabilityStatus() == AiModel.UsabilityStatus.NO_LOCAL_RUNTIME) {
                        Toast.makeText(requireContext(), R.string.usability_no_local_runtime, Toast.LENGTH_LONG).show();
                        return;
                    }
                    container.getModelRepository().selectModel(model.getId());
                    if (model.getUsabilityStatus() == AiModel.UsabilityStatus.NEEDS_API_KEY) {
                        Toast.makeText(requireContext(), R.string.usability_needs_key, Toast.LENGTH_SHORT).show();
                    }
                    if (listener != null) {
                        listener.onModelChosen(model);
                    }
                    dismiss();
                }
        );
        rvCatalog.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvCatalog.setAdapter(adapter);

        Runnable refreshTabs = () -> {
            styleTab(tabAll, selectedFilter == null, palette);
            styleTab(tabFree, selectedFilter == AccessType.FREE, palette);
            styleTab(tabPersonal, selectedFilter == AccessType.PERSONAL_API, palette);
            styleTab(tabLocal, selectedFilter == AccessType.LOCAL, palette);
            adapter.submitGroupedModels(container.getModelRepository().getGroupedModels(), selectedFilter);
        };

        tabAll.setOnClickListener(v -> { selectedFilter = null; refreshTabs.run(); });
        tabFree.setOnClickListener(v -> { selectedFilter = AccessType.FREE; refreshTabs.run(); });
        tabPersonal.setOnClickListener(v -> { selectedFilter = AccessType.PERSONAL_API; refreshTabs.run(); });
        tabLocal.setOnClickListener(v -> { selectedFilter = AccessType.LOCAL; refreshTabs.run(); });

        btnSync.setOnClickListener(v -> {
            btnSync.setEnabled(false);
            container.getFreeAccessManager().refreshFreeCatalogs((count, err) -> {
                if (!isAdded()) return;
                btnSync.setEnabled(true);
                refreshTabs.run();
                String msg = err == null
                        ? "Synced " + count + " free models"
                        : "Synced " + count + " models (" + err + ")";
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            });
        });

        container.getModelRepository().observeAllModels().observe(getViewLifecycleOwner(), models -> refreshTabs.run());
        refreshTabs.run();
    }

    private void styleTab(TextView tab, boolean active, ThemePalette palette) {
        if (active) {
            tab.setBackground(GradientDrawableFactory.createLinearGradient(palette, 999f));
            tab.setTextColor(palette.getOnPrimaryColor());
        } else {
            tab.setBackground(GradientDrawableFactory.createPill(
                    palette.getSurfaceColor(),
                    palette.getBorderColor(),
                    ThemeApplier.dpToPxInt(requireContext(), 1f)
            ));
            tab.setTextColor(palette.getTextSecondaryColor());
        }
    }
}
