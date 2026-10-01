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
        int dp3 = Math.round(3 * d);
        int dp2 = Math.round(2 * d), dp4 = Math.round(4 * d), dp8 = Math.round(8 * d);
        int dp12 = Math.round(12 * d), dp14 = Math.round(14 * d), dp15 = Math.round(15 * d),
            dp18 = Math.round(18 * d);
        int dp16 = Math.round(16 * d), dp20 = Math.round(20 * d), dp34 = Math.round(34 * d),
            dp60 = Math.round(60 * d);
        int dp72 = Math.round(72 * d), dp96 = Math.round(96 * d), dp114 = Math.round(114 * d);
        int dp146 = Math.round(146 * d);
        int dp100Const = Math.round(100 * d);

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
        rp.height = Math.round((grid ? 194f : 128f) * d);
        rp.topMargin = (!grid && position == 0) ? Math.round(5 * d) : 0;
        rp.bottomMargin = (!grid && position == bookList.size() - 1) ? Math.round(20 * d) : 0;
        root.setLayoutParams(rp);
        // 波纹挂根节点（有完整按压态与触点 hotspot），inset 按模式对齐卡面可视矩形，
        // mask 圆角裁切 —— 波纹止步于卡面，不漫出柔影区（旧 selectableItemBackground 铺满整个 view）
        root.setForeground(androidx.appcompat.content.res.AppCompatResources.getDrawable(context,
                grid ? R.drawable.fg_card_ripple_grid : R.drawable.fg_card_ripple_list));

        // 白卡片边距：列表(0,6,0,6) / 宫格海报卡(左右10、上下4)——卡面可视宽恰=封面宽
        ViewGroup.MarginLayoutParams cp = (ViewGroup.MarginLayoutParams) h.cardBg.getLayoutParams();
        int dp10 = Math.round(10 * d);
        cp.setMargins(grid ? dp10 : 0, grid ? dp4 : dp2, grid ? dp10 : 0, grid ? dp4 : dp2);
        h.cardBg.setLayoutParams(cp);

        // 封面盒：列表 14/15 处 72×96；宫格海报卡 (spanW-24)×133 @ (12,7)——封面贴满卡面顶/左/右。
        // ⚠️ ②列表编辑态封面不再右移让位（选择框已移到卡片右侧），恒 14dp。
        // 宫格卡面可视边界 = 白卡 margin(左右10) + drawable 内缩(左右2/上3) → 卡面恰为 (spanW-24) 宽，
        // 封面同宽同位即「贴边海报」；封面盒宽高与二次修正版完全一致，Matrix 等比裁切取景不变。
        int dp133 = Math.round(133 * d);
        int coverW = grid ? spanW - 2 * dp12 : dp72;
        int coverH = grid ? dp133 : dp96;
        int coverLeft = grid ? dp12 : dp14;
        int coverTop = grid ? (dp4 + dp3) : dp15;
        FrameLayout.LayoutParams cvp = (FrameLayout.LayoutParams) h.coverBox.getLayoutParams();
        cvp.width = coverW;
        cvp.height = coverH;
        cvp.setMargins(coverLeft, coverTop, 0, 0);
        // ⚠️ XML 里 cover_box 带 layout_marginStart=14dp：MarginLayoutParams 按方向解析时
        // startMargin 会覆盖 setMargins 写入的 leftMargin（实测封面左侧多留 4dp、右侧戳出卡面 2dp）。
        // 必须显式同步 marginStart，宫格贴边与列表编辑位移才真正生效。
        cvp.setMarginStart(coverLeft);
        h.coverBox.setLayoutParams(cvp);
        // 封面圆角：宫格海报卡封面贴满卡面顶角 → 顶部圆角须与卡面 12dp 一致、底部直角；
        // 列表为 8dp 全角（与 bg_cover_rounded 占位底一致）。形变动画按帧在两套值间插值。
        h.applyCoverCorners(grid ? 12f : 8f, grid ? 0f : 8f);

        // 文字盒（两套几何各自布置，且始终按「各自模式」的绝对尺寸——
        // 飞出的文字盒在当前模式 root 里仍落在旧位置上，形变时原位淡出，不出现竖排挤压）
        // 列表文字：left=100 top=15 h=96——编辑态左距恒 100dp（封面不再让位），
        // 仅宽度在编辑态右侧收缩 34dp 给选择框让位（选择框距卡右缘 14dp + 间隙）
        FrameLayout.LayoutParams ltp = (FrameLayout.LayoutParams) h.listTextBox.getLayoutParams();
        ltp.width = rvW - dp114 - (editAdj ? dp34 : 0);
        ltp.height = dp96;
        ltp.setMargins(dp100Const, dp15, 0, 0);
        h.listTextBox.setLayoutParams(ltp);
        // 宫格文字：随海报卡内缩——left=18 top=146 w=span-36（距封面 6dp、距卡面底 ≥6dp 不贴边）
        FrameLayout.LayoutParams gtp = (FrameLayout.LayoutParams) h.gridTextBox.getLayoutParams();
        gtp.width = Math.max(1, rvW / 3) - 2 * dp18;
        gtp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        gtp.setMargins(dp18, dp146, 0, 0);
        h.gridTextBox.setLayoutParams(gtp);

        // 可见性（静止态：只显示当前模式文字盒）
        h.listTextBox.setVisibility(grid ? View.INVISIBLE : View.VISIBLE);
        h.gridTextBox.setVisibility(grid ? View.VISIBLE : View.INVISIBLE);

        // 多选框：列表=卡片右侧垂直居中（距卡右缘 14dp），封面原位不再让位；
        // 宫格=海报卡右下角内缩——相对 item_root 为右 20/下 60，即距海报卡右下角 8/6dp，
        // 圆框完整落在封面内（修复圆框骑出封面/卡外的溢出）。
        // ⚠️ 方向解析坑（真机实测）：XML 定义了 layout_marginStart 后，只要 start/end 任一被定义，
        // resolveLayoutDirection 会用 start/end 重写 left/right——marginEnd 未定义时 rightMargin
        // 被清零（END gravity 下圆框直接顶到 item 右缘、半张骑出封面，且 bottomMargin 不受影响）。
        // 因此 setMarginStart(0) 之后必须 setMarginEnd 显式钉死右边距，两条解析路径才收敛。
        FrameLayout.LayoutParams cbp = (FrameLayout.LayoutParams) h.ivCheckbox.getLayoutParams();
        if (grid) {
            cbp.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
            cbp.setMargins(0, 0, dp20, dp60);
            cbp.setMarginEnd(dp20);
        } else {
            cbp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
            cbp.setMargins(0, 0, dp16, 0);
            cbp.setMarginEnd(dp16);
        }
        cbp.setMarginStart(0);
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
            // ⚠️ 书城/分类/搜索/本地书城走的 item_book.xml 没有 cover_box 节点（那类封面走圆角
            // transform，不依赖 matrix 裁切），coverBox 会返回 null —— 必须判空，否则
            // addOnLayoutChangeListener 直接 NPE，整页 RecyclerView 渲染崩溃（点击书城即闪退）。
            if (coverBox != null) {
                // 关键修复：封面盒每次真正完成布局（拿到真实尺寸）时用真实尺寸重算静止态 Matrix。
                // 彻底摆脱「绑定时机 / 隐藏态 getWidth=0 / 复用 holder 历史宽度 / 多次 notifyDataSetChanged」
                // 导致矩阵用错尺寸或干脆没算（新书封面缩到左上角 = identity 矩阵）的问题。
                coverBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    if (r - l > 0 && b - t > 0) {
                        BookAdapter.this.applyCoverMatrix(BookHolder.this, 1f, 1f);
                    }
                });
            }
            listTextBox = itemView.findViewById(R.id.list_text_box);
            gridTextBox = itemView.findViewById(R.id.grid_text_box);
            tvNameGrid = itemView.findViewById(R.id.tv_book_name_grid);
            tvProgressGrid = itemView.findViewById(R.id.tv_read_progress_grid);
        }

        // 封面圆角（dp）：列表 = 8dp 全角（与 bg_cover_rounded 一致）；宫格海报卡 = 顶 12dp（与
        // bg_ios_card 卡面圆角一致）/ 底 0。形变动画逐帧在两套值间插值，消除切换首尾的圆角跳变。
        float coverCornerTopDp = 8f, coverCornerBottomDp = 8f;
        final android.view.ViewOutlineProvider coverCornerOutline = new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                float dd = view.getResources().getDisplayMetrics().density;
                float rT = coverCornerTopDp * dd, rB = coverCornerBottomDp * dd;
                android.graphics.Path p = new android.graphics.Path();
                p.addRoundRect(0, 0, Math.max(view.getWidth(), 1), Math.max(view.getHeight(), 1),
                        new float[]{rT, rT, rT, rT, rB, rB, rB, rB}, android.graphics.Path.Direction.CW);
                outline.setConvexPath(p);
            }
        };

        /** 设置封面四角圆角并立即生效（applyMode 静止态 / 形变逐帧共用） */
        public void applyCoverCorners(float topDp, float bottomDp) {
            if (coverBox == null) return;
            coverCornerTopDp = topDp;
            coverCornerBottomDp = bottomDp;
            coverBox.setOutlineProvider(coverCornerOutline);
            coverBox.setClipToOutline(true);
            coverBox.invalidateOutline();
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
