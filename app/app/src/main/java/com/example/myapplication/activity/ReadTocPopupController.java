package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.example.myapplication.R;
import com.example.myapplication.fragment.PopupBookmarkFragment;
import com.example.myapplication.fragment.PopupChapterFragment;
import com.example.myapplication.utils.Hint;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.utils.VolumeDeriver;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录/书签浮窗控制器：浮窗承载（TabLayout + ViewPager2 双页）、书籍信息头、
 * 目录切章与书签跳转回调、分卷推导、边缘滑动收起。
 * 阅读状态（chapterList / currentChapterIndex / currentBook 等）由宿主持有，本类只读访问。
 */
class ReadTocPopupController {

    private final ReadActivity activity;

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
                ivCover.setImageResource(R.drawable.default_book_cover);
            } else {
                Glide.with(activity)
                        .load(RetrofitClient.getFullImageUrl(cover))
                        .placeholder(R.drawable.default_book_cover)
                        .error(R.drawable.default_book_cover)
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
            View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_chapter_list, null);
            TabLayout tabLayout = popupView.findViewById(R.id.tab_layout_popup);
            ViewPager2 vpPopup = popupView.findViewById(R.id.view_pager_popup);

            // 顶部书籍信息头（封面 / 书名 / 作者），目录与书签共用
            bindPopupBookInfoHeader(popupView);

            vpPopup.setAdapter(createPopupPagerAdapter());
            new TabLayoutMediator(tabLayout, vpPopup, (tab,
                    position) -> tab.setText(position == 0 ? "目录" : "书签")).attach();

            // 目录浮窗外壳（TabLayout 等）跟随日/夜间配色；列表项由各 Fragment/Adapter 自行着色
            ReadThemeController.themeViewTree(popupView);

            int popupWidth = (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.75f);
            activity.chapterPopupWindow = new PopupWindow(popupView, popupWidth, WindowManager.LayoutParams.MATCH_PARENT, true);
            activity.chapterPopupWindow.setAnimationStyle(R.style.LeftSlideAnimation);
            activity.chapterPopupWindow.showAtLocation(activity.layoutBottomNav, Gravity.START, 0, 0);

            // ViewPager2 里的 Fragment 在 attach 之后才走 onCreateView，首帧着色覆盖不到；
            // 这里 post 一次整体重染，并在切到「书签」页时再染一次，保证两页都跟随背景色。
            popupView.post(() -> ReadThemeController.themeViewTree(popupView));
            vpPopup.registerOnPageChangeCallback(new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                @Override public void onPageSelected(int position) {
                    popupView.post(() -> ReadThemeController.themeViewTree(popupView));
                }
            });

            // 书签页（最后一页）继续左滑 → 平滑收起目录弹窗
            setupChapterPopupEdgeSwipe(vpPopup);

            activity.mainHandler.removeCallbacks(activity.hideNavRunnable);
            activity.chapterPopupWindow.setOnDismissListener(activity::resetAutoHideTimer);
            popupView.setOnTouchListener((v,
                    event) -> { v.performClick(); activity.chapterPopupWindow.dismiss(); return true; });
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "showChapterPopup 崩溃", t);
            showPopupErrorDialog("目录弹窗无法打开：" + t.getMessage());
        }
    }

    /**
     * 书签页（ViewPager2 最后一页，索引 1）继续左滑时，平滑收起目录弹窗。
     * 只在“当前页 == 最后一页”且为左滑（横向位移大于纵向）时触发，
     * 避免与「目录 → 书签」的正常分页滑动冲突。
     */
    private void setupChapterPopupEdgeSwipe(ViewPager2 vpPopup) {
        try {
            RecyclerView pagerRv = (RecyclerView) vpPopup.getChildAt(0);
            if (pagerRv == null) return;
            final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
            final float[] downX = {0f};
            final float[] downY = {0f};
            final int[] startPage = {-1};
            RecyclerView.SimpleOnItemTouchListener listener = new RecyclerView.SimpleOnItemTouchListener() {
                private void track(RecyclerView rv, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX[0] = e.getX();
                            downY[0] = e.getY();
                            startPage[0] = vpPopup.getCurrentItem();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            float dx = downX[0] - e.getX();          // 左滑 dx > 0
                            float dy = Math.abs(downY[0] - e.getY());
                            // 必须“按下时已经在书签页”才收起，避免目录页左滑分页到书签时误关闭
                            if (dx > touchSlop && dx > dy && startPage[0] == 1) {
                                dismissChapterPopup();
                            }
                            break;
                    }
                }

                @Override
                public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                    track(rv, e);
                    return false; // 不拦截，交给 ViewPager2 正常处理
                }

                @Override
                public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                    track(rv, e);
                }
            };
            pagerRv.addOnItemTouchListener(listener);
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "setupChapterPopupEdgeSwipe 失败", t);
        }
    }

    /** 收起目录弹窗（带左滑出动画，由 PopupWindow 动画样式决定） */
    private void dismissChapterPopup() {
        if (activity.chapterPopupWindow != null && activity.chapterPopupWindow.isShowing()) {
            activity.chapterPopupWindow.dismiss();
        }
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
            if (activity.chapterPopupWindow != null) activity.chapterPopupWindow.dismiss();
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
                if (activity.chapterPopupWindow != null) activity.chapterPopupWindow.dismiss();
            } catch (Throwable t) {
                android.util.Log.e("ReadActivity", "书签选择回调崩溃", t);
                showPopupErrorDialog("书签跳转失败：" + t.getMessage());
            }
        });
        return bookmarkFragment;
    }

}
