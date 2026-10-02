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

    /**
     * 是否跟随阅读器背景的派生配色。仅阅读器「目录」浮窗开启。
     * 本 Adapter 同时被书城 / 在线目录 / 书籍详情等外部页面复用，
     * 这些页面不属于阅读器，必须保持自身配色，不能被阅读器的调色板带跑。
     */
    private boolean followReaderTheme = false;

    public void setFollowReaderTheme(boolean follow) {
        this.followReaderTheme = follow;
    }

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
            holder.tvChapterTitle.setTextColor(followReaderTheme ? ReadActivity.getAccentColor()
                    : holder.itemView.getContext().getColor(R.color.ios_blue));
        } else {
            holder.tvChapterTitle.setTextColor(followReaderTheme ? ReadActivity.getTextPrimaryColor()
                    : holder.itemView.getContext().getColor(R.color.ios_text_primary));
        }

        // 列表项（文字/分割线/背景）跟随阅读器背景派生配色；
        // 非阅读器宿主（书城 / 在线目录 / 书籍详情）保持自身配色
        if (followReaderTheme) {
            ReadActivity.themeViewTree(holder.itemView);
        }

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
