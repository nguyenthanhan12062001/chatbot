package com.chatai.an.presentation.search;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chatai.an.R;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.ViewHolder> {

    public interface OnResultClickListener {
        void onResultClick(Conversation conversation);
    }

    private final List<Conversation> items = new ArrayList<>();
    private final OnResultClickListener listener;
    private ThemePalette palette;

    public SearchResultAdapter(OnResultClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<Conversation> newItems) {
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
                .inflate(R.layout.item_search_result, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Conversation conv = items.get(position);
        Context ctx = holder.itemView.getContext();

        String title = conv.getTitle().trim().isEmpty()
                ? ctx.getString(R.string.conversation_untitled)
                : conv.getTitle();
        holder.tvTitle.setText(title);
        holder.tvPreview.setText(conv.getLastMessagePreview());
        holder.tvPreview.setVisibility(conv.getLastMessagePreview().isEmpty() ? View.GONE : View.VISIBLE);
        holder.tvDate.setText(DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(conv.getUpdatedAt())));

        if (palette != null) {
            holder.card.setBackground(GradientDrawableFactory.createSurfaceCard(
                    palette.getSurfaceColor(),
                    palette.getBorderColor(),
                    ThemeApplier.dpToPxInt(ctx, 1f),
                    ThemeApplier.dpToPx(ctx, 14f)
            ));
            holder.tvTitle.setTextColor(palette.getTextPrimaryColor());
            holder.tvPreview.setTextColor(palette.getTextSecondaryColor());
            holder.tvDate.setTextColor(palette.getPrimaryColor());
        }

        holder.card.setOnClickListener(v -> {
            if (listener != null) listener.onResultClick(conv);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView tvTitle;
        final TextView tvDate;
        final TextView tvPreview;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.search_result_card);
            tvTitle = itemView.findViewById(R.id.tv_search_conv_title);
            tvDate = itemView.findViewById(R.id.tv_search_conv_date);
            tvPreview = itemView.findViewById(R.id.tv_search_conv_preview);
        }
    }
}
