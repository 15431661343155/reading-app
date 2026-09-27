package com.example.myapplication.adapter;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
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
    private static final int TYPE_SHELF = 1;   // 书架：统一双模式布局（列表⇄宫格共用节点）
    private static final int TYPE_BOOK = 0;    // 书城：item_book.xml

    @Override
    public int getItemViewType(int position) {
        if (progressMap != null) {
            return TYPE_SHELF;
        }
        return TYPE_BOOK;
    }

    /** 仅切换模式标记，不触发 notifyDataSetChanged（供形变动画使用，避免重绑/重载） */
    public void setGridModeSilently(boolean gridMode) {
        this.isGridMode = gridMode;
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

    // 书架用 item_shelf_morph.xml（双模式）
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
        if (viewType == TYPE_SHELF) {
            view = LayoutInflater.from(context).inflate(R.layout.item_shelf_morph, parent, false);
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
        if (holder.tvNameGrid != null) holder.tvNameGrid.setText(book.getTitle());

        // ========== 书架双模式几何（不重绑也能切模式；复位变换残留） ==========
        applyMode(holder, position);

        // 加载封面图片（书架：不用位图圆角，由 cover_box 裁切；加载完成后重设 Matrix 等比裁切）
        String coverUrl = book.getCover();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            // 将相对路径转换为完整URL
            String fullCoverUrl = RetrofitClient.getFullImageUrl(coverUrl);
            if (holder.coverBox != null) {
                Glide.with(context)
                        .load(fullCoverUrl)
                        .placeholder(R.drawable.default_book_cover)
                        .error(R.drawable.default_book_cover)
                        .override(600, 800)
                        .dontAnimate()
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                                holder.applyCoverMatrixNow();
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                                holder.applyCoverMatrixNow();
                                return false;
                            }
                        })
                        .into(holder.ivBookCover);
                // into() 返回时占位图（或缓存命中图）已就位：立即为当前 drawable 计算
                // Matrix，否则旧图 Matrix（按 override 600×800 位图算的）会把 1773×2364
                // 的占位图放大近 3 倍绘制一两帧 —— 即下拉刷新时「封面放大一下」
                holder.applyCoverMatrixNow();
                holder.tvCoverTitle.setVisibility(View.GONE);
            } else {
                Glide.with(context)
                        .load(fullCoverUrl)
                        .placeholder(R.drawable.default_book_cover)
                        .error(R.drawable.default_book_cover)
                        .transform(new RoundedCorners(24))
                        .dontAnimate()
                        .into(holder.ivBookCover);
            }
        } else {
            holder.ivBookCover.setImageResource(R.drawable.default_book_cover);
            holder.applyCoverMatrixNow();
            if (holder.tvCoverTitle != null) {
                holder.tvCoverTitle.setText(book.getTitle());
                // 宫格模式无封面时显示书名遮罩；列表模式仅显示默认封面
                holder.tvCoverTitle.setVisibility(isGridMode ? View.VISIBLE : View.GONE);
            }
        }

        // 选择框（可见性/图标；位置由 applyMode 按模式布置）
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

        // 书架显示阅读进度（列表/宫格两种文案，切换模式时随 applyMode 一并刷新）
        if (progressMap != null) {
            bindProgressTexts(holder, position);
        } else {
            if (holder.tvProgress != null) holder.tvProgress.setVisibility(View.GONE);
            if (holder.tvLastUpdate != null) holder.tvLastUpdate.setVisibility(View.GONE);
            if (holder.tvProgressGrid != null) holder.tvProgressGrid.setVisibility(View.GONE);
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
    }

    /** 列表/宫格两种进度文案（applyMode 与 onBind 共用，切模式不重绑也能刷新文案） */
    private void bindProgressTexts(BookHolder holder, int position) {
        Book book = bookList.get(position);
        Long idBox = book.getId();
        long bookId = idBox == null ? -1L : idBox;
        Integer chapterIdx = progressMap.get(bookId);
        int total = book.getChapterCount();

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
            if (total > 0) {
                holder.tvLastUpdate.setText("最新更新 共" + total + "章");
                holder.tvLastUpdate.setVisibility(View.VISIBLE);
            } else {
                holder.tvLastUpdate.setVisibility(View.GONE);
            }
        }

        if (holder.tvProgressGrid != null) {
            if (chapterIdx != null && total > 0) {
                int pct = (int) ((chapterIdx + 1) * 100f / total);
                holder.tvProgressGrid.setText("已读 " + pct + "%");
            } else {
                holder.tvProgressGrid.setText("未读");
            }
            holder.tvProgressGrid.setVisibility(View.VISIBLE);
        }
    }

    // ==================================================================
    // 双模式几何布置：列表 ⇄ 宫格（同一批节点，仅改 LayoutParams/可见性/Matrix）
    // 几何常量与效果稿 preview_shelf_morph.html 对齐
    // ==================================================================
    public void applyMode(RecyclerView.ViewHolder vh, int position) {
        if (!(vh instanceof BookHolder)) return;
        BookHolder h = (BookHolder) vh;
        if (h.cardBg == null) return; // 书城 item 无双模式结构
        float d = context.getResources().getDisplayMetrics().density;
        int dp4 = Math.round(4 * d), dp6 = Math.round(6 * d), dp8 = Math.round(8 * d);
        int dp12 = Math.round(12 * d), dp14 = Math.round(14 * d), dp18 = Math.round(18 * d);
        int dp34 = Math.round(34 * d), dp48 = Math.round(48 * d), dp54 = Math.round(54 * d);
        int dp72 = Math.round(72 * d), dp96 = Math.round(96 * d), dp114 = Math.round(114 * d);
        int dp138 = Math.round(138 * d), dp158 = Math.round(158 * d);

        View root = h.itemView;
        boolean grid = isGridMode;

        // 复位形变残留（中断的动画 / 复用的 holder）
        root.setAlpha(1f);
        root.setTranslationX(0f);
        root.setTranslationY(0f);
        h.cardBg.setTranslationX(0f);
        h.cardBg.setTranslationY(0f);
        h.cardBg.setScaleX(1f);
        h.cardBg.setScaleY(1f);
        h.coverBox.setTranslationX(0f);
        h.coverBox.setTranslationY(0f);
        h.coverBox.setScaleX(1f);
        h.coverBox.setScaleY(1f);
        h.listTextBox.setAlpha(1f);
        h.listTextBox.setTranslationX(0f);
        h.listTextBox.setTranslationY(0f);
        h.gridTextBox.setAlpha(1f);
        h.gridTextBox.setTranslationX(0f);
        h.gridTextBox.setTranslationY(0f);
        if (h.ivCheckbox != null) {
            h.ivCheckbox.setTranslationX(0f);
            h.ivCheckbox.setTranslationY(0f);
        }

        // span 宽度：优先取 RecyclerView 实际宽（已布局时），未布局回退屏幕宽
        int rvW = 0;
        if (root.getParent() instanceof RecyclerView) {
            rvW = ((RecyclerView) root.getParent()).getWidth();
        }
        if (rvW <= 0) rvW = rvWidthFallback();
        int spanW = grid ? Math.max(1, rvW / 3) : rvW;
        boolean editAdj = isEditMode;

        // 根：高度 + 首尾边距
        ViewGroup.MarginLayoutParams rp = (ViewGroup.MarginLayoutParams) root.getLayoutParams();
        rp.height = Math.round((grid ? 198f : 132f) * d);
        rp.topMargin = (!grid && position == 0) ? Math.round(5 * d) : 0;
        rp.bottomMargin = (!grid && position == bookList.size() - 1) ? Math.round(20 * d) : 0;
        root.setLayoutParams(rp);

        // 白卡片边距：列表(0,6,0,6) / 宫格(4,4,4,4)
        ViewGroup.MarginLayoutParams cp = (ViewGroup.MarginLayoutParams) h.cardBg.getLayoutParams();
        cp.setMargins(0, grid ? dp4 : dp6, 0, grid ? dp4 : dp6);
        h.cardBg.setLayoutParams(cp);

        // 封面盒：列表 14/18 处 72×96；宫格 12/12 处 (spanW-24)×138
        int coverW = grid ? spanW - 2 * dp12 : dp72;
        int coverH = grid ? dp138 : dp96;
        int coverLeft = grid ? dp12 : (editAdj ? dp48 : dp14);
        int coverTop = grid ? dp12 : dp18;
        FrameLayout.LayoutParams cvp = (FrameLayout.LayoutParams) h.coverBox.getLayoutParams();
        cvp.width = coverW;
        cvp.height = coverH;
        cvp.setMargins(coverLeft, coverTop, 0, 0);
        h.coverBox.setLayoutParams(cvp);

        // 文字盒（两套几何各自布置，且始终按「各自模式」的绝对尺寸——
        // 飞出的文字盒在当前模式 root 里仍落在旧位置上，形变时原位淡出，不出现竖排挤压）
        // 列表文字：left=100(+34) top=18 h=96 w=rvW-114(-34)
        FrameLayout.LayoutParams ltp = (FrameLayout.LayoutParams) h.listTextBox.getLayoutParams();
        ltp.width = rvW - dp114 - (editAdj ? dp34 : 0);
        ltp.height = dp96;
        ltp.setMargins(dp100(editAdj), dp18, 0, 0);
        h.listTextBox.setLayoutParams(ltp);
        // 宫格文字：left=12 top=158 w=宫格span-24
        FrameLayout.LayoutParams gtp = (FrameLayout.LayoutParams) h.gridTextBox.getLayoutParams();
        gtp.width = Math.max(1, rvW / 3) - 2 * dp12;
        gtp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        gtp.setMargins(dp12, dp158, 0, 0);
        h.gridTextBox.setLayoutParams(gtp);

        // 可见性（静止态：只显示当前模式文字盒）
        h.listTextBox.setVisibility(grid ? View.INVISIBLE : View.VISIBLE);
        h.gridTextBox.setVisibility(grid ? View.VISIBLE : View.INVISIBLE);

        // 多选框：列表=封面左侧垂直居中(14,54)；宫格=封面右下角
        FrameLayout.LayoutParams cbp = (FrameLayout.LayoutParams) h.ivCheckbox.getLayoutParams();
        if (grid) {
            cbp.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
            cbp.setMargins(0, 0, dp18, dp54);
        } else {
            cbp.gravity = android.view.Gravity.START | android.view.Gravity.TOP;
            cbp.setMargins(dp14, dp54, 0, 0);
        }
        h.ivCheckbox.setLayoutParams(cbp);

        // 无封面遮罩：宫格显示书名蒙版、列表不显示。
        // ⚠️ 不能只在「当前已可见」时切换——列表绑定时蒙版是 GONE，
        //    旧写法导致 列表⇄宫格 切换后蒙版永远点不亮（刷新重绑才恢复）。
        if (h.tvCoverTitle != null && position >= 0 && position < bookList.size()) {
            Book b = bookList.get(position);
            boolean noCover = b == null || b.getCover() == null || b.getCover().isEmpty();
            h.tvCoverTitle.setVisibility(noCover && grid ? View.VISIBLE : View.GONE);
            h.tvCoverTitle.setAlpha(1f);
        }

        // 封面静止态 Matrix（等比 cover 裁切）：同步应用——新 holder 未布局时用
        // LayoutParams 兜底尺寸计算，杜绝首帧 identity 矩阵的占位图放大帧；
        // post 再校正一次（布局实测尺寸就绪后）
        h.applyCoverMatrixNow();
        h.postApplyCoverMatrix();
    }

    private int dp100(boolean editAdj) {
        float d = context.getResources().getDisplayMetrics().density;
        return Math.round((100 + (editAdj ? 34 : 0)) * d);
    }

    private int rvWidthFallback() {
        return context.getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * 封面等比裁切 Matrix：把位图按 cover-fit 映射进「渲染后」的封面盒。
     * sx/sy 为封面盒当前叠加的缩放（静止=1）。净效果 = 位图均匀缩放 u 并居中裁切，
     * 任何中间尺寸下图片都不挤压。
     */
    public void applyCoverMatrix(BookHolder h, float sx, float sy) {
        if (h == null || h.ivBookCover == null || h.coverBox == null) return;
        Drawable dw = h.ivBookCover.getDrawable();
        // 尺寸取「渲染后」的封面盒：已测量用实测值；未布局（新 holder 首次 bind）
        // 用 applyMode 刚写进 LayoutParams 的目标值兜底——否则首帧 Matrix 是
        // identity，1773×2364 的占位图按原始尺寸绘制 = 「封面放大一瞬」。
        int vw = h.coverBox.getWidth();
        int vh = h.coverBox.getHeight();
        if (vw <= 0 || vh <= 0) {
            ViewGroup.LayoutParams lp = h.coverBox.getLayoutParams();
            if (lp != null && lp.width > 0 && lp.height > 0) {
                vw = lp.width;
                vh = lp.height;
            }
        }
        if (dw == null || vw <= 0 || vh <= 0) return;
        int nw = dw.getIntrinsicWidth();
        int nh = dw.getIntrinsicHeight();
        if (nw <= 0 || nh <= 0) return;
        float rw = sx * vw;
        float rh = sy * vh;
        float u = Math.max(rw / nw, rh / nh);
        Matrix m = new Matrix();
        m.setScale(u / sx, u / sy);
        float tx = vw / 2f - nw * (u / sx) / 2f;
        float ty = vh / 2f - nh * (u / sy) / 2f;
        m.postTranslate(tx, ty);
        h.ivBookCover.setImageMatrix(m);
        h.ivBookCover.invalidate();
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

    public class BookHolder extends RecyclerView.ViewHolder {
        public ImageView ivBookCover, ivCheckbox;
        public TextView tvName, tvAuthor, tvIntro, tvProgress, tvLastUpdate;
        public TextView tvCoverTitle;
        // 双模式形变布局专属
        public View cardBg;
        public FrameLayout coverBox;
        public LinearLayout listTextBox, gridTextBox;
        public TextView tvNameGrid, tvProgressGrid;

        BookHolder(@NonNull View itemView) {
            super(itemView);
            ivBookCover = itemView.findViewById(R.id.iv_book_cover);
            ivCheckbox = itemView.findViewById(R.id.iv_checkbox);
            tvName = itemView.findViewById(R.id.tv_book_name);
            tvAuthor = itemView.findViewById(R.id.tv_author);
            tvIntro = itemView.findViewById(R.id.tv_intro);
            tvProgress = itemView.findViewById(R.id.tv_read_progress);
            tvLastUpdate = itemView.findViewById(R.id.tv_last_update);
            tvCoverTitle = itemView.findViewById(R.id.tv_cover_title);
            cardBg = itemView.findViewById(R.id.card_bg);
            coverBox = itemView.findViewById(R.id.cover_box);
            listTextBox = itemView.findViewById(R.id.list_text_box);
            gridTextBox = itemView.findViewById(R.id.grid_text_box);
            tvNameGrid = itemView.findViewById(R.id.tv_book_name_grid);
            tvProgressGrid = itemView.findViewById(R.id.tv_read_progress_grid);
        }

        /** 布局完成后重设封面静止态 Matrix（尺寸就绪后调用） */
        void postApplyCoverMatrix() {
            if (coverBox == null) return;
            coverBox.post(() -> {
                if (coverBox.getWidth() > 0) {
                    applyCoverMatrix(this, 1f, 1f);
                } else {
                    coverBox.postDelayed(() -> applyCoverMatrix(this, 1f, 1f), 16);
                }
            });
        }

        /**
         * Matrix 立即生效：封面盒已布局用实测尺寸，未布局用 LayoutParams 兜底尺寸
         * （占位图/新图一换上就是正确等比裁切，不存在「旧 Matrix × 新 drawable」
         * 或 identity 矩阵的放大帧）。
         */
        void applyCoverMatrixNow() {
            applyCoverMatrix(this, 1f, 1f);
        }
    }
}
