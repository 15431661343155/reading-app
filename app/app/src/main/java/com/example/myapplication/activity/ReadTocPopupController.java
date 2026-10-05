package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.example.myapplication.R;
import com.example.myapplication.fragment.PopupBookmarkFragment;
import com.example.myapplication.fragment.PopupChapterFragment;
import com.example.myapplication.utils.Hint;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.utils.VolumeDeriver;
import com.example.myapplication.widget.InkBookCover;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录/书签抽屉控制器：底部抽屉浮窗（分段控件 + ViewPager2 双页）、书籍信息头、
 * 目录切章与书签跳转回调、分卷推导、下拉收起。
 * 阅读状态（chapterList / currentChapterIndex / currentBook 等）由宿主持有，本类只读访问。
 */
class ReadTocPopupController {

    /** 抽屉高度占弹窗窗口高（=「目录/夜间/设置」行上沿以上）的比例 */
    private static final float PANEL_HEIGHT_RATIO = 0.85f;
    /** 遮罩颜色，与「我的」页清理缓存抽屉的 v_cache_dim 取同一个值 */
    private static final int TOC_DIM_COLOR = 0x73000000;
    /**
     * 入场弹起的越程高度（dp）：面板升起时越过终点这么多再回落。
     * 面板底部会同步多伸出窗口下沿同样的量（见 onPreDraw 里的 bottomMargin），
     * 否则上弹那一瞬底部会露出一条缝。
     */
    private static final float TOC_BOUNCE_DP = 18f;

    private final ReadActivity activity;
    /** 抽屉面板本身，收起动画要移动它 */
    private View tocPanel;
    /** 遮罩独立 View：入场淡入、退场淡出、跟手下拉时同步变淡 */
    private View tocDim;
    /** 遮罩当前的淡出进度：0 = 满遮罩，1 = 完全透明 */
    private float tocDimP;
    /** 收起动画已在进行中，避免重复触发 */
    private boolean tocDismissing;
    /** 入场弹起第二段（回落归位）是否还该播：中途被手按住下拉就不播，免得跟手时把面板拽回去 */
    private boolean tocBouncePending;

    ReadTocPopupController(ReadActivity activity) { this.activity = activity; }


    /**
     * 目录/书签浮窗顶部的书籍信息头：封面 / 书名 / 作者。
     * 书名作者优先取 currentBook（从书架/详情/阅读记录带过来），缺失时回落到本地书 SP 记录；
     * 封面取 currentBook 的封面，其次本地封面文件，最后本地书里的封面串（可能是 base64 或 URL）。
     */
    private void bindPopupBookInfoHeader(View popupView) {
        try {
            ImageView ivCover = popupView.findViewById(R.id.iv_popup_book_cover);
            TextView tvTitle = popupView.findViewById(R.id.tv_popup_book_title);
            TextView tvAuthor = popupView.findViewById(R.id.tv_popup_book_author);
            if (ivCover == null || tvTitle == null || tvAuthor == null) return;

            String title = (activity.currentBook == null || activity.currentBook.getTitle() == null)
                    ? "" : activity.currentBook.getTitle().trim();
            if (title.isEmpty() && activity.currentBook != null && activity.currentBook.getBookName() != null) {
                title = activity.currentBook.getBookName().trim();
            }
            if (title.isEmpty() && activity.localBookName != null) title = activity.localBookName.trim();
            tvTitle.setText(title.isEmpty() ? "未知书名" : title);

            String author = (activity.currentBook == null || activity.currentBook.getAuthor() == null)
                    ? "" : activity.currentBook.getAuthor().trim();
            if (author.isEmpty() && activity.localBookAuthor != null) author = activity.localBookAuthor.trim();
            tvAuthor.setText(author.isEmpty() ? "未知作者" : author);

            // 封面优先级：currentBook.cover → 本地封面文件 → 本地封面串
            String cover = (activity.currentBook == null || activity.currentBook.getCover() == null)
                    ? "" : activity.currentBook.getCover().trim();
            if (cover.isEmpty() && activity.localBookCoverPath != null && !activity.localBookCoverPath.isEmpty()
                    && new java.io.File(activity.localBookCoverPath).exists()) {
                cover = activity.localBookCoverPath;
            }
            if (cover.isEmpty() && activity.localBookCover != null) cover = activity.localBookCover.trim();

            if (cover.isEmpty()) {
                ivCover.setImageDrawable(InkBookCover.of(activity, title));
            } else {
                Glide.with(activity)
                        .load(RetrofitClient.getFullImageUrl(cover))
                        .placeholder(InkBookCover.of(activity, title))
                        .error(InkBookCover.of(activity, title))
                        .into(ivCover);
            }
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "目录浮窗书籍信息头填充失败", t);
        }
    }

    @SuppressLint("InflateParams")
    void showChapterPopup() {
        try {
            if (activity.chapterList == null || activity.chapterList.isEmpty()) {
                Hint.show(activity, "章节列表还在加载中，请稍候");
                return;
            }
            // 抽屉已经开着：再点一次目录 = 收起，避免叠出第二个浮窗
            if (isChapterPopupShowing()) {
                dismissChapterPopup();
                return;
            }

            View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_chapter_list, null);
            ViewPager2 vpPopup = popupView.findViewById(R.id.view_pager_popup);

            // 顶部书籍信息头（封面 / 书名 / 作者），目录与书签共用
            bindPopupBookInfoHeader(popupView);

            vpPopup.setAdapter(createPopupPagerAdapter());
            setupSegTabs(popupView, vpPopup);

            // 抽屉外壳（信息头、分段控件）跟随日/夜间配色；列表项由各 Fragment/Adapter 自行着色
            ReadThemeController.themeViewTree(popupView);

            // 与设置浮窗同位：窗口下沿落在「目录/夜间/设置」行上沿，抽屉因此盖住进度条那一行，
            // 并从该行上沿滑出/收回。裁剪交给窗口边界——面板越过下沿的部分必然被裁掉，
            // 这是本项目里唯一稳定生效的裁剪线（整窗位移会看成从屏幕底飞入飞出）。
            FrameLayout host = new FrameLayout(activity);
            // 遮罩独立成 View（不挂窗口 dim）：跟手下拉时能直接 setAlpha 跟着变淡，
            // 颜色与清理缓存抽屉一致（#73000000）；打 tag_keep_own_color 免得被派生配色改色。
            View dim = new View(activity);
            dim.setBackgroundColor(TOC_DIM_COLOR);
            dim.setAlpha(0f);
            dim.setTag(R.id.tag_keep_own_color, Boolean.TRUE);
            dim.setOnClickListener(v -> dismissChapterPopup());
            host.addView(dim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // 面板真实高度要等窗口收缩到该行上沿才知道，先给 MATCH_PARENT 兜底，
            // 由下面的 OnPreDraw 在收缩那一帧一并改写（此时面板仍不可见，不会闪）。
            popupView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.BOTTOM));
            host.addView(popupView);
            tocPanel = popupView;
            tocDim = dim;

            SlideOutPopupWindow popup = new SlideOutPopupWindow(host,
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
            activity.chapterPopupWindow = popup;
            // 入场/收起都用代码里的 translationY + alpha 动画，所以关掉窗口动画样式
            popup.setAnimationStyle(0);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);
            // 返回键由 PopupWindow 的 DecorView 直接消费并 dismiss()，内容视图上的按键监听收不到，
            // 所以接管 dismiss() 本身：改走与遮罩点击同一条滑回动画。
            popup.setSlideOut(this::dismissChapterPopup);
            // 底栏那一行落在窗口之外：点它时事件到不了 Activity 的按钮，而是被当成「点弹窗外」。
            // 抢在 PopupWindow 自行无动画 dismiss 之前接管，统一走带动画的收回。
            activity.chapterPopupWindow.setTouchInterceptor((v, ev) -> {
                boolean outside = ev.getAction() == MotionEvent.ACTION_OUTSIDE;
                if (!outside && ev.getAction() == MotionEvent.ACTION_DOWN) {
                    int x = (int) ev.getX();
                    int y = (int) ev.getY();
                    outside = x < 0 || y < 0 || x >= v.getWidth() || y >= v.getHeight();
                }
                if (outside) {
                    // 底栏那一行虽在窗口之外，但点它不等于「点别处」：命中「目录/夜间/设置」
                    // 时收起之后还要补发该按钮的功能，于是设置浮窗能直接顶替目录抽屉。
                    activity.forwardOutsideTouchToNavButton(v, ev, this::dismissChapterPopup);
                    return true;
                }
                return false;
            });
            activity.chapterPopupWindow.setOnDismissListener(() -> {
                tocPanel = null;
                tocDim = null;
                tocDismissing = false;
                tocBouncePending = false;
                activity.setCatalogNavActive(false);
                activity.resetAutoHideTimer();
                activity.runPendingNavAction();
            });
            activity.chapterPopupWindow.showAtLocation(activity.layoutBottomNav, Gravity.TOP, 0, 0);
            activity.setCatalogNavActive(true);

            setupDragToDismiss(popupView);

            // ViewPager2 里的 Fragment 在 attach 之后才走 onCreateView，首帧着色覆盖不到；
            // 这里 post 一次整体重染，并保证分段胶囊按真实高度画圆角。
            popupView.post(() -> ReadThemeController.themeViewTree(popupView));

            // 入场：① 首帧绘制前把窗口下沿收缩到该行上沿，并按新窗口高定下面板高度；
            // ② 收缩生效后的那一帧再让面板从窗口下沿滑上来、遮罩同时淡入。
            popupView.setVisibility(View.INVISIBLE);
            host.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        private boolean windowSized = false;

                        @Override
                        public boolean onPreDraw() {
                            if (!windowSized) {
                                windowSized = true;
                                int targetH = activity.measureBottomRowTopInRoot(host);
                                if (targetH <= 0) return true;
                                ViewGroup.LayoutParams lp = popupView.getLayoutParams();
                                // 多给一个越程高度并让底部探出窗口下沿同样的量：
                                // 弹起（面板越过终点）时底部仍被窗口裁住，不会露出底缝
                                int bounce = bouncePx();
                                lp.height = (int) (targetH * PANEL_HEIGHT_RATIO) + bounce;
                                if (lp instanceof FrameLayout.LayoutParams) {
                                    ((FrameLayout.LayoutParams) lp).bottomMargin = -bounce;
                                }
                                popupView.setLayoutParams(lp);
                                try {
                                    activity.chapterPopupWindow.update(0, 0,
                                            ViewGroup.LayoutParams.MATCH_PARENT, targetH);
                                } catch (Throwable ignored) { }
                                return true;    // 等新尺寸生效后下一帧再播入场
                            }
                            host.getViewTreeObserver().removeOnPreDrawListener(this);
                            startTocEnter();
                            return true;
                        }
                    });

            activity.mainHandler.removeCallbacks(activity.hideNavRunnable);
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "showChapterPopup 崩溃", t);
            showPopupErrorDialog("目录弹窗无法打开：" + t.getMessage());
        }
    }

    /**
     * 「目录 / 书签」分段控件接给 ViewPager2：点页签翻页，翻页后按新选中项重画胶囊。
     * 选中序号记在 seg 容器的 tag 上，派生配色重着色时（{@link ReadThemeController#themeViewTree}）
     * 会读到同一个 tag，日夜切换后仍是当前页高亮。
     */
    private void setupSegTabs(View popupView, ViewPager2 vpPopup) {
        View seg = popupView.findViewById(R.id.seg_toc_tabs);
        if (seg instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) seg;
            for (int i = 0; i < group.getChildCount(); i++) {
                final int page = i;
                group.getChildAt(i).setOnClickListener(v -> vpPopup.setCurrentItem(page, true));
            }
        }
        ReadThemeController.selectSegTab(popupView, 0);
        vpPopup.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                popupView.post(() -> {
                    ReadThemeController.selectSegTab(popupView, position);
                    ReadThemeController.themeViewTree(popupView);
                });
            }
        });
    }

    /**
     * 按住抽屉顶部（指示条 + 书籍信息头）下拉：面板跟手的同时遮罩按比例变淡，
     * 松手位移超过面板高 30% 即收起，否则弹回（遮罩同步补回满值）。
     * 只挂在顶部这块区域，正文列表的滚动不受影响。
     */
    private void setupDragToDismiss(View popupView) {
        View dragArea = popupView.findViewById(R.id.toc_drag_area);
        if (dragArea == null) return;
        dragArea.setOnTouchListener(new View.OnTouchListener() {
            float downRawY;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = ev.getRawY();
                        tocBouncePending = false;   // 入场弹起还没落定就被按住：放弃回落段，交还给手
                        if (popupView.animate() != null) popupView.animate().cancel();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = ev.getRawY() - downRawY;
                        if (dy > 0) {
                            popupView.setTranslationY(dy);
                            applyTocDim(dy / panelHeight(popupView));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float released = Math.max(0f, ev.getRawY() - downRawY);
                        if (released > panelHeight(popupView) * 0.30f) {
                            dismissChapterPopup();      // 从当前位置继续滑出
                        } else {
                            popupView.animate().translationY(0f).setDuration(200)
                                    .setInterpolator(new AccelerateDecelerateInterpolator()).start();
                            animateTocDim(tocDimP, 0f, 200);   // 弹回时把遮罩补满
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
        // 顶部这块整片都要能吃下拉手势，指示条与信息头自身不再吞掉事件
        dragArea.setClickable(false);
        dragArea.setFocusable(false);
    }

    private static float panelHeight(View panel) {
        return panel.getHeight() > 0 ? panel.getHeight() : 1f;
    }

    /**
     * 遮罩只原地改 alpha、位置纹丝不动（与清理缓存抽屉的 applySheetDim 同一套）。
     *
     * @param p 淡出进度：0 = 满遮罩，1 = 完全透明
     */
    private void applyTocDim(float p) {
        tocDimP = Math.min(1f, Math.max(0f, p));
        if (tocDim != null) tocDim.setAlpha(1f - tocDimP);
    }

    private void animateTocDim(float from, float to, long duration) {
        ValueAnimator va = ValueAnimator.ofFloat(from, to);
        va.setDuration(duration);
        va.setInterpolator(new AccelerateDecelerateInterpolator());
        va.addUpdateListener(a -> applyTocDim((float) a.getAnimatedValue()));
        va.start();
    }

    private int bouncePx() {
        return (int) (TOC_BOUNCE_DP * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * 抽屉入场：先慢后快地升起（Accelerate），越过终点一小段后再减速回落归位（弹一下），
     * 遮罩淡入铺满整段时长。
     */
    private void startTocEnter() {
        View panel = tocPanel;
        if (panel == null) return;
        panel.setVisibility(View.VISIBLE);
        int h = panel.getHeight();
        if (h > 0) {
            float bounce = bouncePx();
            tocBouncePending = true;
            panel.setTranslationY(h);
            panel.animate().translationY(-bounce).setDuration(260)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (!tocBouncePending) return;
                        panel.animate().translationY(0f).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator())
                                .withEndAction(() -> tocBouncePending = false).start();
                    }).start();
        }
        applyTocDim(1f);
        animateTocDim(1f, 0f, 440);
    }

    private boolean isChapterPopupShowing() {
        return activity.chapterPopupWindow != null && activity.chapterPopupWindow.isShowing();
    }

    /** 收起抽屉（带滑回动画）。返回键、遮罩点击、目录按钮再点一次共用这一条路径。 */
    void dismissChapterPopup() {
        if (!isChapterPopupShowing() || tocDismissing) return;
        View panel = tocPanel;
        if (panel == null) {
            SlideOutPopupWindow.dismissImmediate(activity.chapterPopupWindow);
            return;
        }
        tocDismissing = true;
        float h = panel.getHeight() > 0 ? panel.getHeight()
                : activity.getResources().getDisplayMetrics().heightPixels;
        panel.animate().translationY(h).setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() ->
                        SlideOutPopupWindow.dismissImmediate(activity.chapterPopupWindow))
                .start();
        // 面板下滑的同时把遮罩淡掉，否则会看到阴影孤零零留到最后
        animateTocDim(tocDimP, 1f, 220);
    }

    private ReadActivity.PopupPagerAdapter createPopupPagerAdapter() {
        List<Fragment> fragments = new ArrayList<>();
        // 防御性拷贝：避免底层 chapterList 在加载完服务器新列表时被 replaceAll 清空导致 Popup 渲染崩溃/空白
        final List<ReadActivity.Chapter> safeCopy = new ArrayList<>(activity.chapterList);
        final int safeIndex = Math.max(0, Math.min(activity.currentChapterIndex, safeCopy.size() - 1));
        // 分卷结构按「当前 chapterList」推得，保证与 safeCopy 下标严格一致
        final List<LocalBookParser.VolumeInfo> safeVolumes = new ArrayList<>(currentVolumes());
        PopupChapterFragment chapterFragment = new PopupChapterFragment(safeCopy, safeIndex, safeVolumes);
        chapterFragment.setOnChapterSelectedListener(chapterIndex -> {
            dismissChapterPopup();
            if (chapterIndex < 0 || chapterIndex >= activity.chapterList.size()) {
                Hint.show(activity, "章节索引无效，列表可能刚更新");
                return;
            }
            try {
                if (chapterIndex != activity.currentChapterIndex) {
                    activity.loadChapterContent(chapterIndex);
                }
            } catch (Throwable t) {
                android.util.Log.e("ReadActivity", "目录切章崩溃", t);
                showPopupErrorDialog("切换章节失败：" + t.getMessage());
            }
        });
        fragments.add(chapterFragment);
        fragments.add(createBookmarkFragment());
        return new ReadActivity.PopupPagerAdapter(activity, fragments);
    }

    /**
     * 当前书的分卷结构（阅读器目录浮窗用，每次打开目录浮窗都会重新推导）。
     *
     * <p>本地书：直接返回 {@link #localVolumes}（导入时持久化的卷表，其 start/end 即 chapterList 下标）。
     * <p>服务器书：用 {@link #serverMajorChapters} 与「当前 {@link #chapterList} 的 sortKey」实时推导，
     * 因此缓存恢复 / {@link #mergeServerData} / 切章之后，卷区间始终对齐最新 chapterList 的下标，
     * 不会错位；数据缺失或卷数小于 2 时 {@link VolumeDeriver} 返回空，目录退回平铺。
     * <p>外站书：无分卷结构，返回空（保持平铺）。
     */
    private List<LocalBookParser.VolumeInfo> currentVolumes() {
        if (activity.isLocalBook) return activity.localVolumes;
        if (activity.isExternalBook) return new ArrayList<>();
        List<String> sortKeys = new ArrayList<>(activity.chapterList.size());
        for (ReadActivity.Chapter c : activity.chapterList) sortKeys.add(c.getSortKey());
        return VolumeDeriver.derive(sortKeys, activity.serverMajorChapters);
    }

    /** 目录弹窗相关报错时统一弹对话框（替代白屏/崩回详情页） */
    void showPopupErrorDialog(String reason) {
        String msg = (reason == null ? "" : reason)
                + "\n\n若问题持续，请先返回书籍详情页从「目录」入口打开章节列表。";
        new AlertDialog.Builder(activity)
                .setTitle("操作失败")
                .setMessage(msg)
                .setCancelable(true)
                .setNegativeButton("知道了", null)
                .setPositiveButton("返回详情", (d, w) -> activity.finish())
                .show();
    }

    private PopupBookmarkFragment createBookmarkFragment() {
        PopupBookmarkFragment bookmarkFragment = new PopupBookmarkFragment();
        // 关键修复：外站书 currentBook.getId() 返回 null（Long 对象），
        // 若直接 setBookId(currentBook.getId()) 会触发 Long->long 自动拆箱 NPE：
        // "Attempt to invoke virtual method 'long java.lang.Long.longValue()' on a null object reference"
        Long rawId = activity.currentBook.getId();
        long safeBookId = (rawId == null || rawId <= 0) ? 0L : rawId;
        bookmarkFragment.setBookId(safeBookId);
        bookmarkFragment.setIsLocalBook(activity.isLocalBook);
        if (activity.currentBook != null) {
            bookmarkFragment.setSourceType(activity.currentBook.getSourceType());
            bookmarkFragment.setSourceBookId(activity.currentBook.getSourceUrl());
        }
        bookmarkFragment.setOnBookmarkSelectedListener(bookmark -> {
            try {
                if (bookmark == null) return;
                Integer chIdxObj = bookmark.getChapterIndex();
                Integer scrollObj = bookmark.getScrollPosition();
                int chIdx = chIdxObj == null ? 0 : chIdxObj;
                int scrollPos = scrollObj == null ? 0 : scrollObj;
                if (chIdx < 0 || chIdx >= activity.chapterList.size()) {
                    Hint.show(activity, "书签章节索引已失效");
                    return;
                }
                if (chIdx != activity.currentChapterIndex) {
                    activity.loadChapterContent(chIdx);
                    activity.webView.postDelayed(() -> {
                        try { activity.webView.evaluateJavascript("jumpToPage(" + scrollPos + ")",
                                null); } catch (Throwable ignored) {}
                    }, 300);
                } else {
                    try { activity.webView.evaluateJavascript("jumpToPage(" + scrollPos + ")",
                            null); } catch (Throwable ignored) {}
                }
                dismissChapterPopup();
            } catch (Throwable t) {
                android.util.Log.e("ReadActivity", "书签选择回调崩溃", t);
                showPopupErrorDialog("书签跳转失败：" + t.getMessage());
            }
        });
        return bookmarkFragment;
    }

}
