package com.chatai.an.presentation.model;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.chatai.an.R;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ModelCatalogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnModelSelectedListener {
        void onModelSelected(AiModel model);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_MODEL = 1;

    private static class Row {
        final int type;
        final ModelProvider provider;
        final AiModel model;

        Row(ModelProvider provider) {
            this.type = TYPE_HEADER;
            this.provider = provider;
            this.model = null;
        }

        Row(AiModel model) {
            this.type = TYPE_MODEL;
            this.provider = null;
            this.model = model;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final String selectedModelId;
    private final ThemePalette palette;
    private final OnModelSelectedListener listener;

    public ModelCatalogAdapter(
            Map<ModelProvider, List<AiModel>> groupedModels,
            String selectedModelId,
            ThemePalette palette,
            OnModelSelectedListener listener
    ) {
        this.selectedModelId = selectedModelId;
        this.palette = palette;
        this.listener = listener;
        submitGroupedModels(groupedModels, null);
    }

    public void submitGroupedModels(Map<ModelProvider, List<AiModel>> groupedModels, AccessType filter) {
        rows.clear();
        if (groupedModels != null) {
            for (Map.Entry<ModelProvider, List<AiModel>> entry : groupedModels.entrySet()) {
                List<AiModel> filtered = new ArrayList<>();
                if (entry.getValue() != null) {
                    for (AiModel m : entry.getValue()) {
                        if (filter == null || m.getAccessType() == filter) {
                            filtered.add(m);
                        }
                    }
                }
                if (!filtered.isEmpty()) {
                    rows.add(new Row(entry.getKey()));
                    for (AiModel m : filtered) {
                        rows.add(new Row(m));
                    }
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_model_provider_header, parent, false));
        } else {
            return new ModelHolder(inflater.inflate(R.layout.item_model_option, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        Context ctx = holder.itemView.getContext();

        if (holder instanceof HeaderHolder && row.provider != null) {
            HeaderHolder hh = (HeaderHolder) holder;
            String headerText = row.provider == ModelProvider.LOCAL
                    ? row.provider.getDisplayName() + " (" + ctx.getString(R.string.local_runtime_inactive_short) + ")"
                    : row.provider.getDisplayName();
            hh.tvHeader.setText(headerText);
            if (palette != null) {
                hh.tvHeader.setTextColor(row.provider == ModelProvider.LOCAL
                        ? palette.getTextSecondaryColor()
                        : palette.getPrimaryColor());
            }
        } else if (holder instanceof ModelHolder && row.model != null) {
            ModelHolder mh = (ModelHolder) holder;
            AiModel model = row.model;
            boolean isSelected = model.getId().equals(selectedModelId);
            AiModel.UsabilityStatus usability = model.getUsabilityStatus();
            boolean inactive = usability == AiModel.UsabilityStatus.NO_LOCAL_RUNTIME
                    || usability == AiModel.UsabilityStatus.UNAVAILABLE;

            mh.tvName.setText(model.getName());
            mh.tvContext.setText(model.getContextBadge());
            mh.tvDesc.setText(model.getDescription());
            mh.ivSelected.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            mh.card.setAlpha(inactive ? 0.65f : 1.0f);

            AccessType access = model.getAccessType();
            if (access == AccessType.FREE) {
                mh.tvPricing.setText(model.getProvider() == ModelProvider.OPENROUTER_FREE
                        ? R.string.badge_zero_price
                        : R.string.badge_free);
            } else if (access == AccessType.LOCAL) {
                mh.tvPricing.setText(R.string.badge_inactive);
            } else {
                mh.tvPricing.setText(R.string.badge_api_key);
            }

            if (usability != AiModel.UsabilityStatus.USABLE || !model.getUsabilityNote().isEmpty()) {
                mh.tvUsability.setVisibility(View.VISIBLE);
                String note = !model.getUsabilityNote().isEmpty()
                        ? model.getUsabilityNote()
                        : formatDefaultUsability(ctx, usability);
                mh.tvUsability.setText(note);
                int statusColor;
                if (usability == AiModel.UsabilityStatus.USABLE) {
                    statusColor = ContextCompat.getColor(ctx, R.color.status_supported);
                } else if (usability == AiModel.UsabilityStatus.NEEDS_API_KEY
                        || usability == AiModel.UsabilityStatus.RATE_LIMITED) {
                    statusColor = ContextCompat.getColor(ctx, R.color.status_unknown);
                } else {
                    statusColor = ContextCompat.getColor(ctx, R.color.status_unsupported);
                }
                mh.tvUsability.setTextColor(statusColor);
                mh.tvUsability.setBackground(GradientDrawableFactory.createPill(
                        ThemeApplier.blendWithAlpha(statusColor, 26),
                        ThemeApplier.blendWithAlpha(statusColor, 110),
                        ThemeApplier.dpToPxInt(ctx, 1f)
                ));
            } else {
                mh.tvUsability.setVisibility(View.GONE);
            }

            if (palette != null) {
                mh.card.setBackground(GradientDrawableFactory.createSurfaceCard(
                        isSelected ? palette.getSurfaceVariantColor() : palette.getSurfaceColor(),
                        isSelected ? palette.getPrimaryColor() : palette.getBorderColor(),
                        ThemeApplier.dpToPxInt(ctx, isSelected ? 2f : 1f),
                        ThemeApplier.dpToPx(ctx, 16f)
                ));
                mh.tvName.setTextColor(palette.getTextPrimaryColor());
                mh.tvDesc.setTextColor(palette.getTextSecondaryColor());
                mh.ivSelected.setColorFilter(palette.getPrimaryColor());

                mh.tvContext.setBackground(GradientDrawableFactory.createPill(
                        palette.getSurfaceVariantColor(),
                        palette.getBorderColor(),
                        ThemeApplier.dpToPxInt(ctx, 1f)
                ));
                mh.tvContext.setTextColor(palette.getTextSecondaryColor());

                int pricingColor;
                if (access == AccessType.LOCAL || inactive) {
                    pricingColor = ContextCompat.getColor(ctx, R.color.status_unsupported);
                } else if (usability == AiModel.UsabilityStatus.NEEDS_API_KEY) {
                    pricingColor = ContextCompat.getColor(ctx, R.color.status_unknown);
                } else if (access == AccessType.FREE) {
                    pricingColor = ContextCompat.getColor(ctx, R.color.status_supported);
                } else {
                    pricingColor = palette.getSecondaryColor();
                }
                mh.tvPricing.setBackground(GradientDrawableFactory.createPill(
                        ThemeApplier.blendWithAlpha(pricingColor, 30),
                        pricingColor,
                        ThemeApplier.dpToPxInt(ctx, 1f)
                ));
                mh.tvPricing.setTextColor(pricingColor);
            }

            bindCapabilityBadge(ctx, mh.capText, R.string.cap_text, model.getCapabilityStatus(ModelCapability.TEXT));
            bindCapabilityBadge(ctx, mh.capImage, R.string.cap_image, model.getCapabilityStatus(ModelCapability.IMAGE));
            bindCapabilityBadge(ctx, mh.capFile, R.string.cap_file, model.getCapabilityStatus(ModelCapability.FILE));
            bindCapabilityBadge(ctx, mh.capThinking, R.string.cap_thinking, model.getCapabilityStatus(ModelCapability.DEEP_THINKING));
            bindCapabilityBadge(ctx, mh.capStreaming, R.string.cap_streaming, model.getCapabilityStatus(ModelCapability.STREAMING));
            bindCapabilityBadge(ctx, mh.capTools, R.string.cap_tools, model.getCapabilityStatus(ModelCapability.TOOL_CALLING));
            bindCapabilityBadge(ctx, mh.capStructured, R.string.cap_structured, model.getCapabilityStatus(ModelCapability.STRUCTURED_OUTPUT));

            mh.card.setOnClickListener(v -> {
                if (listener != null) listener.onModelSelected(model);
            });
        }
    }

    private static String formatDefaultUsability(Context ctx, AiModel.UsabilityStatus status) {
        switch (status) {
            case NEEDS_API_KEY:
                return ctx.getString(R.string.usability_needs_key);
            case RATE_LIMITED:
                return ctx.getString(R.string.usability_rate_limited);
            case QUOTA_EXCEEDED:
                return ctx.getString(R.string.usability_quota_exceeded);
            case NO_LOCAL_RUNTIME:
                return ctx.getString(R.string.usability_no_local_runtime);
            case UNAVAILABLE:
                return ctx.getString(R.string.usability_unavailable);
            default:
                return "";
        }
    }

    private void bindCapabilityBadge(
            Context ctx,
            TextView badge,
            int labelResId,
            CapabilityStatus status
    ) {
        String label = ctx.getString(labelResId);
        int color;
        String symbol;
        if (status == CapabilityStatus.SUPPORTED) {
            color = ContextCompat.getColor(ctx, R.color.status_supported);
            symbol = "✓";
        } else if (status == CapabilityStatus.UNSUPPORTED) {
            color = ContextCompat.getColor(ctx, R.color.status_unsupported);
            symbol = "✕";
        } else {
            color = ContextCompat.getColor(ctx, R.color.status_unknown);
            symbol = "?";
        }
        badge.setText(label + " " + symbol);
        badge.setTextColor(color);
        badge.setBackground(GradientDrawableFactory.createPill(
                ThemeApplier.blendWithAlpha(color, 26),
                ThemeApplier.blendWithAlpha(color, 110),
                ThemeApplier.dpToPxInt(ctx, 1f)
        ));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView tvHeader;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tv_provider_header);
        }
    }

    static class ModelHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView tvName;
        final TextView tvContext;
        final TextView tvPricing;
        final ImageView ivSelected;
        final TextView tvDesc;
        final TextView tvUsability;
        final TextView capText;
        final TextView capImage;
        final TextView capFile;
        final TextView capThinking;
        final TextView capStreaming;
        final TextView capTools;
        final TextView capStructured;

        ModelHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.model_option_card);
            tvName = itemView.findViewById(R.id.tv_model_name);
            tvContext = itemView.findViewById(R.id.tv_model_context);
            tvPricing = itemView.findViewById(R.id.tv_model_pricing);
            ivSelected = itemView.findViewById(R.id.iv_model_selected);
            tvDesc = itemView.findViewById(R.id.tv_model_desc);
            tvUsability = itemView.findViewById(R.id.tv_model_usability);
            capText = itemView.findViewById(R.id.cap_badge_text);
            capImage = itemView.findViewById(R.id.cap_badge_image);
            capFile = itemView.findViewById(R.id.cap_badge_file);
            capThinking = itemView.findViewById(R.id.cap_badge_thinking);
            capStreaming = itemView.findViewById(R.id.cap_badge_streaming);
            capTools = itemView.findViewById(R.id.cap_badge_tools);
            capStructured = itemView.findViewById(R.id.cap_badge_structured);
        }
    }
}
