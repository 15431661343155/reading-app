package com.example.myapplication.adapter;
//书签适配器
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.bean.Bookmark;
import java.text.SimpleDateFormat;
import java.util.Locale;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class BookmarkAdapter extends RecyclerView.Adapter<BookmarkAdapter.BookmarkHolder> {

    private List<Bookmark> bookmarkList;
    private OnBookmarkClickListener clickListener;
    private OnBookmarkLongClickListener longClickListener;

    /**
     * 是否跟随阅读器背景的派生配色，仅阅读器「书签」浮窗开启。
     */
    private boolean followReaderTheme = false;

    public void setFollowReaderTheme(boolean follow) {
        this.followReaderTheme = follow;
    }

    public interface OnBookmarkClickListener {
        void onBookmarkClick(Bookmark bookmark);
    }

    public interface OnBookmarkLongClickListener {
        void onBookmarkLongClick(Bookmark bookmark, int position);
    }

    public BookmarkAdapter(List<Bookmark> bookmarkList) {
        this.bookmarkList = bookmarkList != null ? bookmarkList : new ArrayList<>();
    }

    public void setOnBookmarkClickListener(OnBookmarkClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnBookmarkLongClickListener(OnBookmarkLongClickListener listener) {
        this.longClickListener = listener;
    }

    @NonNull
    @Override
    public BookmarkHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bookmark, parent, false);
        return new BookmarkHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BookmarkHolder holder, int position) {
        Bookmark bookmark = bookmarkList.get(position);
        // 备注
        if (bookmark.getNote() != null && !bookmark.getNote().isEmpty()) {
            holder.tvNote.setText(bookmark.getNote());
            holder.tvNote.setVisibility(View.VISIBLE);
        } else {
            holder.tvNote.setVisibility(View.GONE);
        }
        holder.tvChapter.setText(bookmark.getChapterTitle());
        holder.tvPreview.setText(bookmark.getPreviewText());
        holder.tvTime.setText(formatTime(bookmark.getCreatedAt()));

        // 书签条目（文字/分割线/背景）跟随阅读器背景派生配色；非阅读器宿主保持自身配色
        if (followReaderTheme) {
            ReadActivity.themeViewTree(holder.itemView);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onBookmarkClick(bookmark);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                int currentPosition = holder.getBindingAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION) {
                    longClickListener.onBookmarkLongClick(bookmark, currentPosition);
                }
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return bookmarkList != null ? bookmarkList.size() : 0;
    }

    public void updateList(List<Bookmark> newList) {
        this.bookmarkList = newList != null ? newList : new ArrayList<>();
        notifyDataSetChanged();
    }

    private String formatTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return "";
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
            SimpleDateFormat output = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
            Date date = input.parse(timeStr);
            return output.format(date);
        } catch (Exception e) {
            return timeStr.replace("T", " ");
        }
    }

    static class BookmarkHolder extends RecyclerView.ViewHolder {
        TextView tvChapter, tvPreview, tvTime, tvNote;
        public BookmarkHolder(@NonNull View itemView) {
            super(itemView);
            tvChapter = itemView.findViewById(R.id.tv_bookmark_chapter);
            tvPreview = itemView.findViewById(R.id.tv_bookmark_preview);
            tvTime = itemView.findViewById(R.id.tv_bookmark_time);
            tvNote = itemView.findViewById(R.id.tv_bookmark_note);
        }
    }
}