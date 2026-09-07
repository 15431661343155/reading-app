package com.example.myapplication.adapter;
//弹窗章节适配器

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.activity.ReadActivity;

import java.util.List;

public class PopupChapterAdapter extends RecyclerView.Adapter<PopupChapterAdapter.ChapterHolder> {

    private List<ReadActivity.Chapter> chapterList;
    private int currentChapterIndex;
    private OnChapterClickListener listener;

    public interface OnChapterClickListener {
        void onChapterClick(ReadActivity.Chapter chapter, int position);
    }

    public PopupChapterAdapter(List<ReadActivity.Chapter> chapterList, int currentChapterIndex) {
        this.chapterList = chapterList;
        this.currentChapterIndex = currentChapterIndex;
    }

    public void setOnChapterClickListener(OnChapterClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ChapterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chapter_popup, parent, false);
        return new ChapterHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChapterHolder holder, int position) {
        ReadActivity.Chapter chapter = chapterList.get(position);
        holder.tvChapterTitle.setText(chapter.getTitle());

        // 高亮当前阅读章节
        if (position == currentChapterIndex) {
            holder.tvChapterTitle.setTextColor(Color.parseColor("#007AFF"));
        } else {
            holder.tvChapterTitle.setTextColor(Color.parseColor("#1D1D1F"));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChapterClick(chapter, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return chapterList != null ? chapterList.size() : 0;
    }

    static class ChapterHolder extends RecyclerView.ViewHolder {
        TextView tvChapterTitle;

        public ChapterHolder(@NonNull View itemView) {
            super(itemView);
            tvChapterTitle = itemView.findViewById(R.id.tv_popup_chapter_title);
        }
    }
}
