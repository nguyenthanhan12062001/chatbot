package com.chatai.an.presentation.sidebar;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chatai.an.R;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ConversationGroup;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class SidebarHistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnConversationActionListener {
        void onOpenConversation(Conversation conversation);
        void onRenameConversation(Conversation conversation);
        void onDeleteConversation(Conversation conversation);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private static class Row {
        final int type;
        final ConversationGroup group;
        final Conversation conversation;

        Row(ConversationGroup group) {
            this.type = TYPE_HEADER;
            this.group = group;
            this.conversation = null;
        }

        Row(Conversation conversation) {
            this.type = TYPE_ITEM;
            this.group = null;
            this.conversation = conversation;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final OnConversationActionListener listener;
    private String activeConversationId;
    private ThemePalette palette;

    public SidebarHistoryAdapter(OnConversationActionListener listener) {
        this.listener = listener;
    }

    public void submitConversations(List<Conversation> conversations, String activeConversationId) {
        this.activeConversationId = activeConversationId;
        rows.clear();

        if (conversations != null && !conversations.isEmpty()) {
            long now = System.currentTimeMillis();
            long todayStart = startOfDay(now, 0);
            long yesterdayStart = startOfDay(now, -1);
            long sevenDaysStart = startOfDay(now, -7);

            Map<ConversationGroup, List<Conversation>> grouped = new EnumMap<>(ConversationGroup.class);
            for (ConversationGroup g : ConversationGroup.values()) {
                grouped.put(g, new ArrayList<>());
            }

            for (Conversation c : conversations) {
                long ts = c.getUpdatedAt();
                ConversationGroup target;
                if (ts >= todayStart) {
                    target = ConversationGroup.TODAY;
                } else if (ts >= yesterdayStart) {
                    target = ConversationGroup.YESTERDAY;
                } else if (ts >= sevenDaysStart) {
                    target = ConversationGroup.PREVIOUS_7_DAYS;
                } else {
                    target = ConversationGroup.OLDER;
                }
                List<Conversation> bucket = grouped.get(target);
                if (bucket != null) {
                    bucket.add(c);
                }
            }

            for (ConversationGroup group : ConversationGroup.values()) {
                List<Conversation> bucket = grouped.get(group);
                if (bucket != null && !bucket.isEmpty()) {
                    rows.add(new Row(group));
                    for (Conversation c : bucket) {
                        rows.add(new Row(c));
                    }
                }
            }
        }
        notifyDataSetChanged();
    }

    public void setActiveConversationId(String activeConversationId) {
        this.activeConversationId = activeConversationId;
        notifyDataSetChanged();
    }

    public void setPalette(ThemePalette palette) {
        this.palette = palette;
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
            View v = inflater.inflate(R.layout.item_sidebar_header, parent, false);
            return new HeaderViewHolder(v);
        } else {
            View v = inflater.inflate(R.layout.item_sidebar_conversation, parent, false);
            return new ItemViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        Context ctx = holder.itemView.getContext();

        if (holder instanceof HeaderViewHolder) {
            HeaderViewHolder h = (HeaderViewHolder) holder;
            h.tvHeader.setText(getGroupTitle(ctx, row.group));
            if (palette != null) {
                h.tvHeader.setTextColor(palette.getTextSecondaryColor());
            }
        } else if (holder instanceof ItemViewHolder && row.conversation != null) {
            ItemViewHolder itemHolder = (ItemViewHolder) holder;
            Conversation conv = row.conversation;
            String title = conv.getTitle().trim().isEmpty()
                    ? ctx.getString(R.string.conversation_untitled)
                    : conv.getTitle();
            itemHolder.tvTitle.setText(title);

            String preview = conv.getLastMessagePreview();
            if (preview != null && !preview.isEmpty()) {
                itemHolder.tvSubtitle.setVisibility(View.VISIBLE);
                itemHolder.tvSubtitle.setText(preview);
            } else {
                itemHolder.tvSubtitle.setVisibility(View.GONE);
            }

            boolean isSelected = conv.getId().equals(activeConversationId);
            if (palette != null) {
                if (isSelected) {
                    itemHolder.root.setBackground(GradientDrawableFactory.createSurfaceCard(
                            palette.getSurfaceVariantColor(),
                            palette.getPrimaryColor(),
                            ThemeApplier.dpToPxInt(ctx, 1f),
                            ThemeApplier.dpToPx(ctx, 12f)
                    ));
                    itemHolder.tvTitle.setTextColor(palette.getPrimaryColor());
                } else {
                    itemHolder.root.setBackground(null);
                    itemHolder.tvTitle.setTextColor(palette.getTextPrimaryColor());
                }
                itemHolder.tvSubtitle.setTextColor(palette.getTextSecondaryColor());
                itemHolder.btnMore.setColorFilter(palette.getTextSecondaryColor());
            }

            itemHolder.root.setOnClickListener(v -> {
                if (listener != null) listener.onOpenConversation(conv);
            });
            itemHolder.root.setOnLongClickListener(v -> {
                showConversationMenu(itemHolder.btnMore, conv);
                return true;
            });
            itemHolder.btnMore.setOnClickListener(v -> showConversationMenu(v, conv));
        }
    }

    private void showConversationMenu(View anchor, Conversation conversation) {
        PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
        MenuInflater inflater = popup.getMenuInflater();
        popup.getMenu().add(0, 1, 0, R.string.action_open);
        popup.getMenu().add(0, 2, 1, R.string.action_rename);
        popup.getMenu().add(0, 3, 2, R.string.action_delete);
        popup.setOnMenuItemClickListener(item -> {
            if (listener == null) return false;
            if (item.getItemId() == 1) {
                listener.onOpenConversation(conversation);
                return true;
            } else if (item.getItemId() == 2) {
                listener.onRenameConversation(conversation);
                return true;
            } else if (item.getItemId() == 3) {
                listener.onDeleteConversation(conversation);
                return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private static String getGroupTitle(Context ctx, ConversationGroup group) {
        if (group == null) return "";
        switch (group) {
            case TODAY:
                return ctx.getString(R.string.sidebar_group_today);
            case YESTERDAY:
                return ctx.getString(R.string.sidebar_group_yesterday);
            case PREVIOUS_7_DAYS:
                return ctx.getString(R.string.sidebar_group_prev_7_days);
            case OLDER:
            default:
                return ctx.getString(R.string.sidebar_group_older);
        }
    }

    private static long startOfDay(long now, int dayOffset) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        return cal.getTimeInMillis();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView tvHeader;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tv_group_header);
        }
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final TextView tvTitle;
        final TextView tvSubtitle;
        final ImageButton btnMore;

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.item_conversation_root);
            tvTitle = itemView.findViewById(R.id.tv_conversation_title);
            tvSubtitle = itemView.findViewById(R.id.tv_conversation_subtitle);
            btnMore = itemView.findViewById(R.id.btn_conversation_more);
        }
    }
}
