package com.chatai.an.presentation.memory;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatai.an.R;
import com.chatai.an.ai.memory.MemoryMergeService;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.databinding.ActivityMemoryManagementBinding;
import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.presentation.common.AppViewModelFactory;
import com.chatai.an.presentation.common.UiFormatters;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MemoryManagementActivity extends AppCompatActivity {

    private enum FilterMode {
        ALL, GLOBAL, PROJECT, PINNED, ARCHIVED
    }

    private ActivityMemoryManagementBinding binding;
    private MemoryViewModel viewModel;
    private MemoryAdapter adapter;
    private ThemePalette palette;
    private List<MemoryItem> allMemories = new ArrayList<>();
    private final Set<String> mergedParentIds = new HashSet<>();
    private FilterMode currentFilter = FilterMode.ALL;
    private String searchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMemoryManagementBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.memoryRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, Math.max(sys.bottom, ime.bottom));
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        viewModel = new ViewModelProvider(this, new AppViewModelFactory(app.getAppContainer()))
                .get(MemoryViewModel.class);

        binding.btnMemoryBack.setOnClickListener(v -> finish());
        binding.btnMemoryAdd.setOnClickListener(v -> showMemoryDialog(null));
        binding.btnMemoryMerge.setOnClickListener(v -> startMergeFlow());

        binding.etMemorySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        binding.tabMemAll.setOnClickListener(v -> setFilter(FilterMode.ALL));
        binding.tabMemGlobal.setOnClickListener(v -> setFilter(FilterMode.GLOBAL));
        binding.tabMemProject.setOnClickListener(v -> setFilter(FilterMode.PROJECT));
        binding.tabMemPinned.setOnClickListener(v -> setFilter(FilterMode.PINNED));
        binding.tabMemArchived.setOnClickListener(v -> setFilter(FilterMode.ARCHIVED));

        adapter = new MemoryAdapter();
        binding.rvMemories.setLayoutManager(new LinearLayoutManager(this));
        binding.rvMemories.setAdapter(adapter);

        viewModel.getActiveTheme().observe(this, cfg -> {
            boolean isDark = ThemeManager.isDarkActive(this, cfg);
            palette = ThemeManager.resolvePalette(this, cfg);
            ThemeApplier.applySystemBars(this, palette, isDark);
            applyPalette();
            adapter.notifyDataSetChanged();
        });

        viewModel.getStatusMessage().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                viewModel.clearStatusMessage();
            }
        });

        viewModel.getAllMemories().observe(this, items -> {
            allMemories = items != null ? items : new ArrayList<>();
            mergedParentIds.clear();
            for (MemoryItem m : allMemories) {
                if (m.isArchived() && m.getMergedIntoId() != null && !m.getMergedIntoId().isEmpty()) {
                    mergedParentIds.add(m.getMergedIntoId());
                }
            }
            applyFilter();
        });
    }

    private void setFilter(FilterMode mode) {
        currentFilter = mode;
        styleFilterTabs();
        applyFilter();
    }

    private void applyFilter() {
        List<MemoryItem> filtered = new ArrayList<>();
        for (MemoryItem item : allMemories) {
            if (currentFilter == FilterMode.ARCHIVED) {
                if (!item.isArchived()) continue;
            } else {
                if (item.isArchived()) continue;
                if (currentFilter == FilterMode.GLOBAL && item.getScope() != MemoryScope.GLOBAL) continue;
                if (currentFilter == FilterMode.PROJECT && item.getScope() != MemoryScope.PROJECT) continue;
                if (currentFilter == FilterMode.PINNED && !item.isPinned()) continue;
            }
            if (!searchQuery.isEmpty()) {
                boolean match = item.getContent().toLowerCase(Locale.ROOT).contains(searchQuery)
                        || item.getCategory().getLabel().toLowerCase(Locale.ROOT).contains(searchQuery)
                        || item.getProjectId().toLowerCase(Locale.ROOT).contains(searchQuery);
                if (!match) continue;
            }
            filtered.add(item);
        }
        adapter.submit(filtered);
        boolean empty = filtered.isEmpty();
        binding.tvMemoryEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.rvMemories.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showMemoryDialog(MemoryItem existing) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = ThemeApplier.dpToPxInt(this, 18f);
        container.setPadding(pad, pad / 2, pad, 0);

        Spinner spCategory = new Spinner(this);
        MemoryCategory[] categories = MemoryCategory.values();
        String[] catLabels = new String[categories.length];
        int catIdx = 0;
        for (int i = 0; i < categories.length; i++) {
            catLabels[i] = categories[i].getLabel();
            if (existing != null && existing.getCategory() == categories[i]) catIdx = i;
        }
        spCategory.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, catLabels));
        spCategory.setSelection(catIdx);
        container.addView(spCategory);

        Spinner spScope = new Spinner(this);
        MemoryScope[] scopes = MemoryScope.values();
        String[] scopeLabels = new String[scopes.length];
        int scopeIdx = 0;
        for (int i = 0; i < scopes.length; i++) {
            scopeLabels[i] = "Scope: " + scopes[i].getLabel();
            if (existing != null && existing.getScope() == scopes[i]) scopeIdx = i;
        }
        spScope.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, scopeLabels));
        spScope.setSelection(scopeIdx);
        container.addView(spScope);

        Spinner spArea = new Spinner(this);
        MemoryArea[] areas = MemoryArea.values();
        String[] areaLabels = new String[areas.length];
        int areaIdx = 0;
        for (int i = 0; i < areas.length; i++) {
            areaLabels[i] = "Area: " + areas[i].getLabel();
            if (existing != null && existing.getArea() == areas[i]) areaIdx = i;
        }
        spArea.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, areaLabels));
        spArea.setSelection(areaIdx);
        container.addView(spArea);

        EditText etProject = new EditText(this);
        etProject.setHint(R.string.memory_project_hint);
        if (existing != null && !existing.getProjectId().isEmpty()) {
            etProject.setText(existing.getProjectId());
        }
        container.addView(etProject);

        Spinner spImportance = new Spinner(this);
        String[] impLabels = {"Importance: 1 (Low)", "Importance: 2", "Importance: 3 (Normal)", "Importance: 4 (High)", "Importance: 5 (Critical)"};
        spImportance.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, impLabels));
        spImportance.setSelection(existing != null ? Math.max(0, Math.min(4, existing.getImportance() - 1)) : 2);
        container.addView(spImportance);

        CheckBox cbPinned = new CheckBox(this);
        cbPinned.setText(R.string.memory_pin_label);
        cbPinned.setChecked(existing != null && existing.isPinned());
        container.addView(cbPinned);

        EditText etContent = new EditText(this);
        etContent.setHint(R.string.memory_content_hint);
        etContent.setMinLines(2);
        if (existing != null) etContent.setText(existing.getContent());
        container.addView(etContent);

        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.memory_add : R.string.action_edit)
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    MemoryCategory chosenCat = categories[spCategory.getSelectedItemPosition()];
                    MemoryScope chosenScope = scopes[spScope.getSelectedItemPosition()];
                    MemoryArea chosenArea = areas[spArea.getSelectedItemPosition()];
                    String project = etProject.getText() != null ? etProject.getText().toString().trim() : "";
                    if (chosenScope == MemoryScope.PROJECT && project.isEmpty()) {
                        project = "default-project";
                    }
                    int imp = spImportance.getSelectedItemPosition() + 1;
                    boolean pin = cbPinned.isChecked();
                    String text = etContent.getText() != null ? etContent.getText().toString().trim() : "";
                    if (!text.isEmpty()) {
                        if (existing == null) {
                            viewModel.addMemoryFull(chosenCat, chosenScope, chosenArea, project, text, imp, pin);
                        } else {
                            viewModel.updateMemoryFull(existing.getId(), chosenCat, chosenScope, chosenArea, project, text, imp, existing.isEnabled(), pin);
                        }
                    }
                })
                .show();
    }

    private void showDetailsDialog(MemoryItem item) {
        StringBuilder details = new StringBuilder();
        details.append("ID: ").append(item.getId()).append("\n");
        details.append("Category: ").append(item.getCategory().getLabel()).append("\n");
        details.append("Scope: ").append(item.getScope().getLabel());
        if (item.getScope() == MemoryScope.PROJECT && !item.getProjectId().isEmpty()) {
            details.append(" (").append(item.getProjectId()).append(")");
        }
        details.append("\n");
        details.append("Area: ").append(item.getArea().getLabel()).append("\n");
        details.append("Importance: ").append(item.getImportance()).append("/5\n");
        details.append("Pinned: ").append(item.isPinned() ? "Yes" : "No").append("\n");
        details.append("Usage Count: ").append(item.getUsageCount()).append("\n");
        details.append("Created: ").append(UiFormatters.formatTime(item.getCreatedAt())).append("\n");
        details.append("Updated: ").append(UiFormatters.formatTime(item.getUpdatedAt())).append("\n");
        if (item.getLastUsedAt() > 0) {
            details.append("Last Used: ").append(UiFormatters.formatTime(item.getLastUsedAt())).append("\n");
        }
        if (item.isArchived()) {
            details.append("Status: Archived");
            if (item.getMergedIntoId() != null) {
                details.append(" -> Merged into ").append(item.getMergedIntoId());
            }
            details.append("\n");
        }
        details.append("\n").append(item.getContent());

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.memory_details_title)
                .setMessage(details.toString())
                .setPositiveButton(android.R.string.ok, null);

        if (mergedParentIds.contains(item.getId())) {
            builder.setNeutralButton(R.string.memory_restore_originals, (d, w) -> viewModel.restoreMerged(item.getId()));
        } else if (item.isArchived() && item.getMergedIntoId() != null && !item.getMergedIntoId().isEmpty()) {
            builder.setNeutralButton(R.string.memory_restore_originals, (d, w) -> viewModel.restoreMerged(item.getMergedIntoId()));
        }
        builder.show();
    }

    private void startMergeFlow() {
        viewModel.loadMergeProposals(proposals -> {
            if (proposals == null || proposals.isEmpty()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.memory_merge_btn)
                        .setMessage(R.string.memory_merge_none)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            if (proposals.size() == 1) {
                showMergePreviewDialog(proposals.get(0));
                return;
            }
            String[] labels = new String[proposals.size()];
            for (int i = 0; i < proposals.size(); i++) {
                MemoryMergeService.MergeProposal p = proposals.get(i);
                labels[i] = "[" + p.getCategory().getLabel() + " • " + p.getSources().size() + " items] " + p.getProposedContent();
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.memory_merge_pick_cluster)
                    .setItems(labels, (d, idx) -> showMergePreviewDialog(proposals.get(idx)))
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        });
    }

    private void showMergePreviewDialog(MemoryMergeService.MergeProposal proposal) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = ThemeApplier.dpToPxInt(this, 18f);
        container.setPadding(pad, pad / 2, pad, 0);

        TextView tvSources = new TextView(this);
        StringBuilder sb = new StringBuilder("Consolidating ").append(proposal.getSources().size()).append(" memories:\n");
        for (MemoryItem src : proposal.getSources()) {
            sb.append("• ").append(src.getContent()).append("\n");
        }
        sb.append("\nOriginals remain archived safely and can be restored.");
        tvSources.setText(sb.toString());
        tvSources.setTextSize(12f);
        container.addView(tvSources);

        EditText etPreview = new EditText(this);
        etPreview.setText(proposal.getProposedContent());
        etPreview.setMinLines(3);
        container.addView(etPreview);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.memory_merge_preview_title)
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.memory_merge_confirm, (d, w) ->
                        viewModel.applyMerge(proposal, etPreview.getText() != null ? etPreview.getText().toString() : "")
                )
                .show();
    }

    private void applyPalette() {
        if (palette == null) return;
        binding.memoryRoot.setBackgroundColor(palette.getBackgroundColor());
        binding.memoryTopBar.setBackgroundColor(palette.getBackgroundColor());
        binding.btnMemoryBack.setColorFilter(palette.getTextPrimaryColor());
        binding.btnMemoryAdd.setColorFilter(palette.getPrimaryColor());
        binding.tvMemoryTitle.setTextColor(palette.getTextPrimaryColor());
        binding.tvMemorySubtitle.setTextColor(palette.getTextSecondaryColor());
        binding.tvMemoryEmpty.setTextColor(palette.getTextSecondaryColor());

        binding.btnMemoryMerge.setBackground(GradientDrawableFactory.createPill(
                ThemeApplier.blendWithAlpha(palette.getPrimaryColor(), 26),
                palette.getPrimaryColor(),
                ThemeApplier.dpToPxInt(this, 1f)
        ));
        binding.btnMemoryMerge.setTextColor(palette.getPrimaryColor());

        binding.etMemorySearch.setBackground(GradientDrawableFactory.createSurfaceCard(
                palette.getSurfaceColor(),
                palette.getBorderColor(),
                ThemeApplier.dpToPxInt(this, 1f),
                ThemeApplier.dpToPx(this, 14f)
        ));
        binding.etMemorySearch.setTextColor(palette.getTextPrimaryColor());
        binding.etMemorySearch.setHintTextColor(palette.getTextSecondaryColor());

        styleFilterTabs();
    }

    private void styleFilterTabs() {
        if (palette == null) return;
        styleTab(binding.tabMemAll, currentFilter == FilterMode.ALL);
        styleTab(binding.tabMemGlobal, currentFilter == FilterMode.GLOBAL);
        styleTab(binding.tabMemProject, currentFilter == FilterMode.PROJECT);
        styleTab(binding.tabMemPinned, currentFilter == FilterMode.PINNED);
        styleTab(binding.tabMemArchived, currentFilter == FilterMode.ARCHIVED);
    }

    private void styleTab(TextView tab, boolean active) {
        if (active) {
            tab.setBackground(GradientDrawableFactory.createLinearGradient(palette, 999f));
            tab.setTextColor(palette.getOnPrimaryColor());
        } else {
            tab.setBackground(GradientDrawableFactory.createPill(
                    palette.getSurfaceColor(),
                    palette.getBorderColor(),
                    ThemeApplier.dpToPxInt(this, 1f)
            ));
            tab.setTextColor(palette.getTextSecondaryColor());
        }
    }

    private class MemoryAdapter extends RecyclerView.Adapter<MemoryAdapter.MemoryHolder> {
        private final List<MemoryItem> items = new ArrayList<>();

        void submit(List<MemoryItem> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public MemoryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = ThemeApplier.dpToPxInt(parent.getContext(), 14f);
            card.setPadding(pad, pad, pad, pad);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = ThemeApplier.dpToPxInt(parent.getContext(), 10f);
            card.setLayoutParams(lp);
            return new MemoryHolder(card);
        }

        @Override
        public void onBindViewHolder(@NonNull MemoryHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class MemoryHolder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final TextView tvCategory;
            final TextView tvMeta;
            final MaterialSwitch switchEnabled;
            final TextView tvContent;
            final TextView btnPin;
            final TextView btnEdit;
            final TextView btnDetails;
            final TextView btnDelete;

            MemoryHolder(@NonNull View itemView) {
                super(itemView);
                card = (LinearLayout) itemView;

                LinearLayout top = new LinearLayout(itemView.getContext());
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(android.view.Gravity.CENTER_VERTICAL);
                tvCategory = new TextView(itemView.getContext());
                tvCategory.setTextSize(11f);
                tvCategory.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams catLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                top.addView(tvCategory, catLp);
                switchEnabled = new MaterialSwitch(itemView.getContext());
                top.addView(switchEnabled);
                card.addView(top);

                tvMeta = new TextView(itemView.getContext());
                tvMeta.setTextSize(11f);
                card.addView(tvMeta);

                tvContent = new TextView(itemView.getContext());
                tvContent.setTextSize(14f);
                tvContent.setPadding(0, ThemeApplier.dpToPxInt(itemView.getContext(), 6f), 0, ThemeApplier.dpToPxInt(itemView.getContext(), 8f));
                card.addView(tvContent);

                LinearLayout actions = new LinearLayout(itemView.getContext());
                actions.setOrientation(LinearLayout.HORIZONTAL);
                btnPin = makeAction(itemView, R.string.memory_action_pin);
                btnEdit = makeAction(itemView, R.string.action_edit);
                btnDetails = makeAction(itemView, R.string.memory_action_details);
                btnDelete = makeAction(itemView, R.string.action_delete);
                actions.addView(btnPin);
                actions.addView(btnEdit);
                actions.addView(btnDetails);
                actions.addView(btnDelete);
                card.addView(actions);
            }

            private TextView makeAction(View parent, int textRes) {
                TextView tv = new TextView(parent.getContext());
                tv.setText(textRes);
                tv.setTextSize(12f);
                tv.setPadding(0, ThemeApplier.dpToPxInt(parent.getContext(), 6f), ThemeApplier.dpToPxInt(parent.getContext(), 16f), ThemeApplier.dpToPxInt(parent.getContext(), 6f));
                return tv;
            }

            void bind(MemoryItem item) {
                String scopeBadge = item.getScope() == MemoryScope.PROJECT && !item.getProjectId().isEmpty()
                        ? "PROJECT:" + item.getProjectId()
                        : item.getScope().getLabel();
                tvCategory.setText(item.getCategory().getLabel()
                        + " · " + scopeBadge
                        + " · " + item.getArea().getLabel()
                        + (item.isPinned() ? " · PINNED" : ""));

                tvMeta.setText("Importance " + item.getImportance() + "/5 · Used " + item.getUsageCount() + "x"
                        + (item.isArchived() ? " · ARCHIVED" : "")
                        + (mergedParentIds.contains(item.getId()) ? " · CONSOLIDATED" : ""));

                tvContent.setText(item.getContent());

                switchEnabled.setOnCheckedChangeListener(null);
                switchEnabled.setVisibility(item.isArchived() ? View.GONE : View.VISIBLE);
                switchEnabled.setChecked(item.isEnabled());
                switchEnabled.setOnCheckedChangeListener((btn, checked) -> viewModel.setEnabled(item.getId(), checked));

                btnPin.setVisibility(item.isArchived() ? View.GONE : View.VISIBLE);
                btnPin.setText(item.isPinned() ? R.string.memory_action_unpin : R.string.memory_action_pin);
                btnPin.setOnClickListener(v -> viewModel.setPinned(item.getId(), !item.isPinned()));

                btnEdit.setVisibility(item.isArchived() ? View.GONE : View.VISIBLE);
                btnEdit.setOnClickListener(v -> showMemoryDialog(item));

                btnDetails.setOnClickListener(v -> showDetailsDialog(item));

                if (mergedParentIds.contains(item.getId()) || (item.isArchived() && item.getMergedIntoId() != null)) {
                    btnDelete.setText(R.string.memory_action_restore);
                    String targetMergedId = mergedParentIds.contains(item.getId()) ? item.getId() : item.getMergedIntoId();
                    btnDelete.setOnClickListener(v -> viewModel.restoreMerged(targetMergedId));
                } else {
                    btnDelete.setText(R.string.action_delete);
                    btnDelete.setOnClickListener(v -> viewModel.deleteMemory(item.getId()));
                }

                if (palette != null) {
                    card.setBackground(GradientDrawableFactory.createSurfaceCard(
                            palette.getSurfaceColor(),
                            item.isPinned() ? palette.getPrimaryColor() : palette.getBorderColor(),
                            ThemeApplier.dpToPxInt(itemView.getContext(), item.isPinned() ? 1.5f : 1f),
                            ThemeApplier.dpToPx(itemView.getContext(), 16f)
                    ));
                    tvCategory.setTextColor(palette.getPrimaryColor());
                    tvMeta.setTextColor(palette.getTextSecondaryColor());
                    tvContent.setTextColor(palette.getTextPrimaryColor());
                    btnPin.setTextColor(palette.getPrimaryColor());
                    btnEdit.setTextColor(palette.getPrimaryColor());
                    btnDetails.setTextColor(palette.getTextSecondaryColor());
                    btnDelete.setTextColor(palette.getTextSecondaryColor());
                }
            }
        }
    }
}
