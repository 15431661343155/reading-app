package com.example.myapplication.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.Book;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.BookHolder> {

    private Context context;
    private List<Book> bookList;
    private Map<Long, Integer> progressMap;
    private Map<Long, String> chapterTitleMap;
    private OnItemClickListener itemClickListener;
    private OnEditStateListener editStateListener;

    private boolean isEditMode = false;
    private boolean isGridMode = false;
    private static final int TYPE_LIST = 1;
    private static final int TYPE_GRID = 2;
    private static final int TYPE_BOOK = 0;
    @Override
    public int getItemViewType(int position) {
        if (progressMap != null) {
            return isGridMode ? TYPE_GRID : TYPE_LIST;
        }
        return TYPE_BOOK;
    }

    public void setGridMode(boolean gridMode) {
        this.isGridMode = gridMode;
        notifyDataSetChanged();
    }

    private final Set<Integer> selectedPositions = new HashSet<>();

    public interface OnLongPressListener {
        void onLongPress(int position);
    }
    private OnLongPressListener longPressListener;

    public void setOnLongPressListener(OnLongPressListener listener) {
        this.longPressListener = listener;
    }

    public interface OnItemClickListener {
        void onItemClick(Book book);
    }

    public interface OnEditStateListener {
        void onSelectionChanged(int selectedCount);
    }


    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    public void setOnEditStateListener(OnEditStateListener listener) {
        this.editStateListener = listener;
    }

    // 书城用 item_book.xml
    public BookAdapter(Context context, List<Book> list) {
        this.context = context;
        this.bookList = list;
        this.progressMap = null;
        this.chapterTitleMap = null;
    }

    // 书架用 item_shelf_book.xml
    public BookAdapter(Context context, List<Book> list, Map<Long, Integer> progressMap, Map<Long, String> chapterTitleMap) {
        this.context = context;
        this.bookList = list;
        this.progressMap = progressMap;
        this.chapterTitleMap = chapterTitleMap;
    }

    public void setEditMode(boolean editMode) {
        isEditMode = editMode;
        if (!editMode) selectedPositions.clear();
        notifyDataSetChanged();
    }

    public boolean isEditMode() {
        return isEditMode;
    }

    public void toggleSelect(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position);
        if (editStateListener != null) {
            editStateListener.onSelectionChanged(selectedPositions.size());
        }
    }

    public void selectAll() {
        selectedPositions.clear();
        for (int i = 0; i < bookList.size(); i++) {
            selectedPositions.add(i);
        }
        notifyDataSetChanged();
        if (editStateListener != null) {
            editStateListener.onSelectionChanged(selectedPositions.size());
        }
    }

    public void clearSelection() {
        selectedPositions.clear();
        notifyDataSetChanged();
        if (editStateListener != null) {
            editStateListener.onSelectionChanged(0);
        }
    }

    public List<Book> getSelectedBooks() {
        List<Book> selected = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            if (pos < bookList.size()) {
                selected.add(bookList.get(pos));
            }
        }
        return selected;
    }

    public int getSelectedCount() {
        return selectedPositions.size();
    }


    @NonNull
    @Override
    public BookHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        if (viewType == TYPE_GRID) {
            view = LayoutInflater.from(context).inflate(R.layout.item_shelf_grid, parent, false);
        } else if (viewType == TYPE_LIST) {
            view = LayoutInflater.from(context).inflate(R.layout.item_shelf_book, parent, false);
        } else {
            view = LayoutInflater.from(context).inflate(R.layout.item_book, parent, false);
        }
        return new BookHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BookHolder holder, int position) {
        Book book = bookList.get(position);
        if (holder.tvName != null) holder.tvName.setText(book.getTitle());
        if (holder.tvAuthor != null) holder.tvAuthor.setText("作者：" + book.getAuthor());
        // 加载封面图片
        String coverUrl = book.getCover();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            // 将相对路径转换为完整URL
            String fullCoverUrl = RetrofitClient.getFullImageUrl(coverUrl);
            Glide.with(context)
                    .load(fullCoverUrl)
                    .placeholder(R.drawable.default_book_cover)
                    .error(R.drawable.default_book_cover)
                    .transform(new RoundedCorners(24))
                    .dontAnimate()
                    .into(holder.ivBookCover);
            if (holder.tvCoverTitle != null) holder.tvCoverTitle.setVisibility(View.GONE);
        } else {
            holder.ivBookCover.setImageResource(R.drawable.default_book_cover);
            if (holder.tvCoverTitle != null) {
                holder.tvCoverTitle.setText(book.getTitle());
                holder.tvCoverTitle.setVisibility(View.VISIBLE);
            }
        }

        // 选择框
        if (holder.ivCheckbox != null) {
            if (isEditMode) {
                holder.ivCheckbox.setVisibility(View.VISIBLE);
                holder.ivCheckbox.setImageResource(
                        selectedPositions.contains(position) ? R.drawable.ic_checkbox_checked_orange : R.drawable.ic_checkbox_unchecked);
            } else {
                holder.ivCheckbox.setVisibility(View.GONE);
            }
        }

        // 隐藏简介
        if (holder.tvIntro != null) {
            holder.tvIntro.setVisibility(View.GONE);
        }

        // 书架显示阅读进度
        if (progressMap != null) {
            Long idBox = book.getId();
            long bookId = idBox == null ? -1L : idBox;
            Integer chapterIdx = progressMap.get(bookId);

            if (isGridMode) {
                // 宫格模式：只显示百分比
                if (holder.tvProgress != null) {
                    int total = book.getChapterCount();
                    if (chapterIdx != null && total > 0) {
                        int pct = (int) ((chapterIdx + 1) * 100f / total);
                        holder.tvProgress.setText("已读 " + pct + "%");
                    } else {
                        holder.tvProgress.setText("未读");
                    }
                    holder.tvProgress.setVisibility(View.VISIBLE);
                }
                if (holder.tvLastUpdate != null) holder.tvLastUpdate.setVisibility(View.GONE);
            } else {
                // 列表模式：显示详细信息
                if (holder.tvProgress != null) {
                    if (chapterIdx != null) {
                        String chapterTitle = null;
                        if (chapterTitleMap != null) {
                            chapterTitle = chapterTitleMap.get(bookId);
                        }
                        if (chapterTitle != null && !chapterTitle.isEmpty()) {
                            holder.tvProgress.setText("最近阅读 " + chapterTitle);
                        } else {
                            holder.tvProgress.setText("最近阅读 第" + (chapterIdx + 1) + "章");
                        }
                    } else {
                        holder.tvProgress.setText("未读");
                    }
                    holder.tvProgress.setVisibility(View.VISIBLE);
                }

                if (holder.tvLastUpdate != null) {
                    int total = book.getChapterCount();
                    if (total > 0) {
                        holder.tvLastUpdate.setText("最新更新 共" + total + "章");
                        holder.tvLastUpdate.setVisibility(View.VISIBLE);
                    } else {
                        holder.tvLastUpdate.setVisibility(View.GONE);
                    }
                }
            }
        } else {
            if (holder.tvProgress != null) holder.tvProgress.setVisibility(View.GONE);
            if (holder.tvLastUpdate != null) holder.tvLastUpdate.setVisibility(View.GONE);
        }

        // ========== 点击事件 ==========
        holder.itemView.setOnClickListener(v -> {
            if (isEditMode) {
                toggleSelect(position);
            } else if (itemClickListener != null && position < bookList.size()) {
                itemClickListener.onItemClick(bookList.get(position));
            }
        });

        // 长按事件
        holder.itemView.setOnLongClickListener(v -> {
            if (!isEditMode && longPressListener != null) {
                longPressListener.onLongPress(position);
                return true;
            }
            return false;
        });

        if (!isGridMode) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
            params.topMargin = position == 0 ? dpToPx(5) : 0;
            params.bottomMargin = position == getItemCount() - 1 ? dpToPx(20) : 0;
            holder.itemView.setLayoutParams(params);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return bookList != null ? bookList.size() : 0;
    }

    public void refreshList(List<Book> newList) {
        this.bookList = newList;
        notifyDataSetChanged();
    }

    static class BookHolder extends RecyclerView.ViewHolder {
        ImageView ivBookCover, ivCheckbox;
        TextView tvName, tvAuthor, tvIntro, tvProgress, tvLastUpdate;
        TextView tvCoverTitle;
        public BookHolder(@NonNull View itemView) {
            super(itemView);
            ivBookCover = itemView.findViewById(R.id.iv_book_cover);
            ivCheckbox = itemView.findViewById(R.id.iv_checkbox);
            tvName = itemView.findViewById(R.id.tv_book_name);
            tvAuthor = itemView.findViewById(R.id.tv_author);
            tvIntro = itemView.findViewById(R.id.tv_intro);
            tvProgress = itemView.findViewById(R.id.tv_read_progress);
            tvLastUpdate = itemView.findViewById(R.id.tv_last_update);
            tvCoverTitle = itemView.findViewById(R.id.tv_cover_title);
        }
    }
}