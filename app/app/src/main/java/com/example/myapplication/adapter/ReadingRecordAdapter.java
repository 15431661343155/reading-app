package com.example.myapplication.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ReadingRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ReadingRecordAdapter extends RecyclerView.Adapter<ReadingRecordAdapter.ViewHolder> {

    private final Context context;
    private final List<ReadingRecord> records;
    private OnItemClickListener listener;
    private boolean isEditMode = false;
    private final Set<Integer> selectedPositions = new HashSet<>();

    public interface OnItemClickListener {
        void onItemClick(ReadingRecord record);
        void onItemLongClick(ReadingRecord record);
    }

    public ReadingRecordAdapter(Context context, List<ReadingRecord> records) {
        this.context = context;
        this.records = records;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    // ========== 编辑模式方法 ==========
    public void setEditMode(boolean editMode) {
        this.isEditMode = editMode;
        notifyDataSetChanged();
    }

    public void toggleSelect(ReadingRecord record) {
        int pos = records.indexOf(record);
        if (selectedPositions.contains(pos)) {
            selectedPositions.remove(pos);
        } else {
            selectedPositions.add(pos);
        }
        notifyItemChanged(pos);
    }

    public void selectAll() {
        selectedPositions.clear();
        for (int i = 0; i < records.size(); i++) {
            selectedPositions.add(i);
        }
        notifyDataSetChanged();
    }

    public void clearSelection() {
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    public List<ReadingRecord> getSelectedItems() {
        List<ReadingRecord> list = new ArrayList<>();
        for (int pos : selectedPositions) {
            if (pos < records.size()) list.add(records.get(pos));
        }
        return list;
    }

    public int getSelectedCount() {
        return selectedPositions.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_reading_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ReadingRecord record = records.get(position);

        holder.tvBookName.setText(record.getBookName());
        holder.tvLastChapter.setText(record.getChapterTitle());
        holder.tvTime.setText(formatTime(record.getReadTime()));
        holder.tvProgress.setText("已读：" + record.getProgress() + "%");
        holder.tvProgress.setVisibility(View.VISIBLE);

        if (record.getCover() != null && !record.getCover().isEmpty()) {
            String fullCoverUrl = RetrofitClient.getFullImageUrl(record.getCover());
            Glide.with(context)
                    .load(fullCoverUrl)
                    .placeholder(R.drawable.default_book_cover)
                    .error(R.drawable.default_book_cover)
                    .transform(new RoundedCorners(24))
                    .dontAnimate()
                    .into(holder.ivCover);
        } else {
            holder.ivCover.setImageResource(R.drawable.default_book_cover);
        }

        // ========== 编辑模式选择框 ==========
        if (holder.cbSelect != null) {
            if (isEditMode) {
                holder.cbSelect.setVisibility(View.VISIBLE);
                holder.cbSelect.setChecked(selectedPositions.contains(position));
            } else {
                holder.cbSelect.setVisibility(View.GONE);
            }
        }

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(record);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onItemLongClick(record);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    private String formatTime(long timestamp) {
        if (timestamp == 0) return "";
        long now = System.currentTimeMillis();
        long diff = now - timestamp;

        if (diff < 60 * 1000) return "刚刚";
        if (diff < 60 * 60 * 1000) return (diff / (60 * 1000)) + "分钟前";
        if (diff < 24 * 60 * 60 * 1000) return (diff / (60 * 60 * 1000)) + "小时前";
        if (diff < 7 * 24 * 60 * 60 * 1000) return (diff / (24 * 60 * 60 * 1000)) + "天前";

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        TextView tvBookName;
        TextView tvLastChapter;
        TextView tvTime;
        TextView tvProgress;
        CheckBox cbSelect;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvBookName = itemView.findViewById(R.id.tv_book_name);
            tvLastChapter = itemView.findViewById(R.id.tv_last_chapter);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvProgress = itemView.findViewById(R.id.tv_progress);
            cbSelect = itemView.findViewById(R.id.cb_select);
        }
    }
}