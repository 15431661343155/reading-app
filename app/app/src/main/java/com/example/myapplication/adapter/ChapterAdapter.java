package com.example.myapplication.adapter;

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

//章节列表适配器
public class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.ChapterHolder> {
    private List<ReadActivity.Chapter> chapterList;
    private int currentChapterIndex;
    private OnChapterClickListener listener;

    public interface OnChapterClickListener {
        void onChapterClick(ReadActivity.Chapter chapter);
    }

    public ChapterAdapter(List<ReadActivity.Chapter> chapterList, int currentChapterIndex) {
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
                .inflate(R.layout.item_chapter, parent, false);
        return new ChapterHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChapterHolder holder, int position) {
        ReadActivity.Chapter chapter = chapterList.get(position);
        holder.tvChapterTitle.setText(chapter.getTitle());

        // 高亮当前阅读章节（按章节索引匹配，而非列表位置）
        if (chapter.getIndex() == currentChapterIndex) {
            holder.tvChapterTitle.setTextColor(Color.parseColor("#007AFF"));
        } else {
            holder.tvChapterTitle.setTextColor(Color.parseColor("#1D1D1F"));
        }

        // ✅ 列表项（文字/分割线/背景）跟随阅读器日/夜间模式
        // 非阅读器宿主（如书籍详情页）返回 false，保持日间配色
        ReadActivity.themeViewTree(holder.itemView,
                ReadActivity.isNightModeForContext(holder.itemView.getContext()));

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChapterClick(chapter);
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
            tvChapterTitle = itemView.findViewById(R.id.tv_chapter_title);
        }
    }
}
