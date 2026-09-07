package com.example.myapplication.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.bean.Message;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 消息中心列表适配器
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {

    private final Context context;
    private final List<Message> messages;
    private OnMessageClickListener listener;

    public interface OnMessageClickListener {
        void onMessageClick(Message message);
    }

    public MessageAdapter(Context context, List<Message> messages) {
        this.context = context;
        this.messages = messages;
    }

    public void setOnMessageClickListener(OnMessageClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Message msg = messages.get(position);

        holder.tvTitle.setText(msg.getTitle());
        holder.tvContent.setText(msg.getContent());
        holder.tvTime.setText(formatTime(msg.getCreateTime()));

        // 消息类型图标
        holder.ivType.setImageResource(getTypeIcon(msg.getType()));

        // 未读红点
        holder.viewUnread.setVisibility(msg.isRead() ? View.GONE : View.VISIBLE);

        // 未读消息标题加粗，已读消息标题常规
        holder.tvTitle.setTextAppearance(
                msg.isRead() ? R.style.TextAppearance_MessageRead : R.style.TextAppearance_MessageUnread);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMessageClick(msg);
        });
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    /**
     * 根据消息类型返回对应图标
     */
    private int getTypeIcon(String type) {
        if (type == null) return R.drawable.ic_notification;
        switch (type) {
            case "system":
                return R.drawable.ic_notification;
            case "reply":
                return R.drawable.ic_feedback;
            case "comment":
                return R.drawable.ic_comment;
            case "like":
                return R.drawable.ic_like;
            case "follow":
                return R.drawable.ic_follow;
            case "activity":
                return R.drawable.ic_bookstore;
            case "interaction":
                return R.drawable.ic_discussion;
            default:
                return R.drawable.ic_notification;
        }
    }

    /**
     * 格式化时间戳为友好显示
     */
    private String formatTime(long timestamp) {
        if (timestamp <= 0) return "";
        long now = System.currentTimeMillis();
        long diff = now - timestamp;

        if (diff < 60_000) {
            return "刚刚";
        } else if (diff < 3600_000) {
            return (diff / 60_000) + "分钟前";
        } else if (diff < 86400_000) {
            return (diff / 3600_000) + "小时前";
        } else if (diff < 7 * 86400_000L) {
            return (diff / 86400_000) + "天前";
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivType;
        TextView tvTitle, tvContent, tvTime;
        View viewUnread;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivType    = itemView.findViewById(R.id.iv_msg_type);
            tvTitle   = itemView.findViewById(R.id.tv_msg_title);
            tvContent = itemView.findViewById(R.id.tv_msg_content);
            tvTime    = itemView.findViewById(R.id.tv_msg_time);
            viewUnread = itemView.findViewById(R.id.view_unread_dot);
        }
    }
}
