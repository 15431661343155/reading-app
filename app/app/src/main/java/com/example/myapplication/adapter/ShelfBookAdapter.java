package com.example.myapplication.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
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
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.Book;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unused")
public class ShelfBookAdapter extends RecyclerView.Adapter<ShelfBookAdapter.ShelfBookHolder> {

    private final Context context;
    private List<Book> bookList = new ArrayList<>();
    private OnBookLongClickListener longClickListener;
    private boolean isEditMode = false;
    private final Set<Integer> selectedPositions = new HashSet<>();

    public interface OnBookLongClickListener {
        void onLongClick(Book book, int position);
    }

    public ShelfBookAdapter(Context context) {
        this.context = context;
    }

    public void setOnBookLongClickListener(OnBookLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setBookList(List<Book> books) {
        this.bookList = books != null ? books : new ArrayList<>();
        if (!this.bookList.isEmpty()) {
            notifyItemRangeChanged(0, this.bookList.size());
        } else {
            notifyDataSetChanged();
        }
    }

    public void setEditMode(boolean editMode) {
        if (this.isEditMode == editMode) return;
        this.isEditMode = editMode;
        if (!editMode) selectedPositions.clear();
        notifyItemRangeChanged(0, bookList.size());
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
    }

    public void selectAll() {
        selectedPositions.clear();
        for (int i = 0; i < bookList.size(); i++) {
            selectedPositions.add(i);
        }
        notifyItemRangeChanged(0, bookList.size());
    }

    public void clearSelection() {
        selectedPositions.clear();
        notifyItemRangeChanged(0, bookList.size());
    }

    public List<Book> getSelectedBooks() {
        List<Book> selected = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            selected.add(bookList.get(pos));
        }
        return selected;
    }

    public int getSelectedCount() {
        return selectedPositions.size();
    }

    @NonNull
    @Override
    public ShelfBookHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_shelf_book, parent, false);
        return new ShelfBookHolder(view);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ShelfBookHolder holder, int position) {
        Book book = bookList.get(position);

        holder.tvName.setText(book.getTitle());
        holder.tvAuthor.setText("作者：" + book.getAuthor());

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
                    .into(holder.ivCover);
        } else {
            holder.ivCover.setImageResource(R.drawable.default_book_cover);
        }

        // 选择框
        if (isEditMode) {
            holder.ivCheckbox.setVisibility(View.VISIBLE);
            holder.ivCheckbox.setImageResource(
                    selectedPositions.contains(position) ? R.drawable.ic_checkbox_checked : R.drawable.ic_checkbox_unchecked);
        } else {
            holder.ivCheckbox.setVisibility(View.GONE);
        }

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (isEditMode) {
                toggleSelect(position);
                if (longClickListener != null) longClickListener.onLongClick(book, position);
            } else {
                Intent intent = new Intent(context, ReadActivity.class);
                intent.putExtra("book", book);
                context.startActivity(intent);
            }
        });

        // 长按事件
        holder.itemView.setOnLongClickListener(v -> {
            if (!isEditMode && longClickListener != null) {
                longClickListener.onLongClick(book, position);
                return true;
            }
            return false;
        });

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
        params.topMargin = position == 0 ? dpToPx(10) : 0;
        params.bottomMargin = position == getItemCount() - 1 ? dpToPx(20) : 0;
        holder.itemView.setLayoutParams(params);
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return bookList.size();
    }

    public static class ShelfBookHolder extends RecyclerView.ViewHolder {
        ImageView ivCover, ivCheckbox;
        TextView tvName, tvAuthor;

        public ShelfBookHolder(@NonNull View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.iv_book_cover);
            ivCheckbox = itemView.findViewById(R.id.iv_checkbox);
            tvName = itemView.findViewById(R.id.tv_book_name);
            tvAuthor = itemView.findViewById(R.id.tv_author);
        }
    }
}