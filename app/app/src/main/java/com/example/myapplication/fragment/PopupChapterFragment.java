package com.example.myapplication.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.myapplication.R;
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.adapter.TocTreeAdapter;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.TocOrder;
import com.example.myapplication.utils.Hint;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.util.ArrayList;
import java.util.List;

public class PopupChapterFragment extends Fragment {

    private List<ReadActivity.Chapter> chapterList;
    /** 分卷结构（无分卷的书为空列表 → 目录退化成平铺章节列表） */
    private List<LocalBookParser.VolumeInfo> volumeList;
    private int currentChapterIndex;
    private OnChapterSelectedListener listener;

    public interface OnChapterSelectedListener {
        void onChapterSelected(int chapterIndex);
    }

    public PopupChapterFragment(List<ReadActivity.Chapter> chapters, int currentIndex) {
        this(chapters, currentIndex, null);
    }

    public PopupChapterFragment(List<ReadActivity.Chapter> chapters, int currentIndex,
                               List<LocalBookParser.VolumeInfo> volumes) {
        // 构造时再做一次防御性拷贝，防止外部列表被 clear/replaceAll 导致 Fragment 渲染崩溃
        this.chapterList = (chapters == null ? new ArrayList<>() : new ArrayList<>(chapters));
        this.volumeList = (volumes == null ? new ArrayList<>() : new ArrayList<>(volumes));
        int size = this.chapterList.size();
        this.currentChapterIndex = (size == 0) ? 0 : Math.max(0, Math.min(currentIndex, size - 1));
    }

    public void setOnChapterSelectedListener(OnChapterSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            View view = inflater.inflate(R.layout.fragment_popup_chapter, container, false);

            if (chapterList == null || chapterList.isEmpty()) {
                try {
                    ((TextView) view.findViewById(R.id.tv_chapter_count)).setText("共 0 章");
                } catch (Throwable ignored) {}
                // 没有章节可定位/排序，悬浮按钮与排序按钮一并隐藏
                try {
                    View emptyLocate = view.findViewById(R.id.btn_toc_locate);
                    if (emptyLocate != null) emptyLocate.setVisibility(View.GONE);
                    View emptySort = view.findViewById(R.id.btn_toc_sort);
                    if (emptySort != null) emptySort.setVisibility(View.GONE);
                } catch (Throwable ignored) {}
                ReadActivity.themeViewTree(view);
                return view;
            }

            ((TextView) view.findViewById(R.id.tv_chapter_count)).setText("共 " + chapterList.size() + " 章");

            FastScrollRecyclerView rv = view.findViewById(R.id.rv_fastscroll);
            final LinearLayoutManager lm = new LinearLayoutManager(getContext());
            rv.setLayoutManager(lm);

            // 分卷折叠树（无分卷信息时自动退化为平铺列表，行为与改造前一致）
            // 排序偏好（正序/倒序）在 setAdapter 之前应用，避免打开时先正序渲染再翻转。
            final SharedPreferences sortSp = requireContext()
                    .getSharedPreferences("read_settings", Context.MODE_PRIVATE);
            TocTreeAdapter adapter = new TocTreeAdapter(chapterList, currentChapterIndex, volumeList);
            // 阅读器目录浮窗：列表项跟随阅读器背景派生配色
            adapter.setFollowReaderTheme(true);
            adapter.setDescending(sortSp.getBoolean("toc_descending", false));
            adapter.setOnChapterClickListener(c -> {
                if (listener == null) return;
                try {
                    int idx = c.getIndex();
                    if (idx < 0 || idx >= chapterList.size()) {
                        Hint.show(getContext(), "章节索引异常，请重试");
                        return;
                    }
                    listener.onChapterSelected(idx);
                } catch (Throwable t) {
                    android.util.Log.e("PopupChapter", "章节点击回调异常", t);
                    Hint.show(getContext(), "切换章节失败：" + t.getMessage());
                }
            });
            rv.setAdapter(adapter);
            // 打开抽屉即把当前章钉在首行。必须在 setAdapter 之后、首帧布局之前挂上：
            // 布局器尚未测量时挂的锚点会在真正布局后被沿用，比 post 到布局后再滚更稳。
            anchorCurrentChapter(lm, adapter, currentChapterIndex);

            // 正序/倒序切换：按钮标签显示「当前顺序」，点击后顺序与标签一起翻转，
            // 并把列表滚回新顺序的第一行（正序=第一章那端、倒序=最后一章那端）。
            // 状态记忆在 read_settings（与阅读器其它偏好同一个 SP），下次打开目录沿用上次的顺序。
            final TextView sortBtn = view.findViewById(R.id.btn_toc_sort);
            if (sortBtn != null) {
                sortBtn.setText(adapter.isDescending() ? "倒序" : "正序");
                sortBtn.setOnClickListener(v -> {
                    try {
                        boolean desc = !adapter.isDescending();
                        adapter.setDescending(desc);   // 内部会把新顺序的首卷展开，首行下方即第一章/最后一章
                        sortBtn.setText(desc ? "倒序" : "正序");
                        sortSp.edit().putBoolean("toc_descending", desc).apply();
                        // 焦点回到第一行（正序=第一章端、倒序=最后一章端），与详情页目录共用 TocOrder 的实现
                        TocOrder.scrollToFirstRow(rv);
                    } catch (Throwable t) {
                        android.util.Log.e("PopupChapter", "切换目录正序/倒序失败", t);
                    }
                });
            }

            // 「定位当前章节」悬浮按钮：滑动目录后一键回到当前章。
            // 当前章若被折叠在别的卷里，先展开该卷再定位（见 TocTreeAdapter.locateCurrentChapter）。
            // 用即时定位而非 smoothScroll：跨度可能有上千行，平滑滚动会肉眼可见地滚很久（用户体感像卡住）。
            View locateBtn = view.findViewById(R.id.btn_toc_locate);
            if (locateBtn != null) {
                locateBtn.setOnClickListener(v -> {
                    try {
                        int pos = adapter.locateCurrentChapter();
                        // 与打开抽屉时同一落点：当前章钉在首行（scrollToPosition 只保证可见，会落到可视区末尾）
                        lm.scrollToPositionWithOffset(
                                pos >= 0 ? pos : currentChapterIndex, 0);
                    } catch (Throwable t) {
                        android.util.Log.e("PopupChapter", "定位当前章节失败", t);
                    }
                });
            }

            final int scrollTarget = currentChapterIndex;
            if (scrollTarget >= 0 && scrollTarget < chapterList.size()) {
                rv.post(() -> {
                    try {
                        anchorCurrentChapter(lm, adapter, scrollTarget);
                    } catch (Throwable ignored) {}
                });
            }
            // 目录 Fragment 根视图（背景/章节数栏）跟随日/夜间；列表项由 ChapterAdapter 在 bind 时着色
            ReadActivity.themeViewTree(view);
            return view;
        } catch (Throwable t) {
            android.util.Log.e("PopupChapter", "onCreateView 崩溃", t);
            // Fragment 创建失败时返回空布局，避免宿主 ReadActivity 崩溃 → 白屏→返回详情
            View fallback = new View(container != null ? container.getContext() : inflater.getContext());
            fallback.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            try {
                Hint.show(inflater.getContext(), "目录加载失败：" + t.getMessage());
            } catch (Throwable ignored) {}
            return fallback;
        }
    }

    /**
     * 把当前章钉成列表首行。
     *
     * <p>不用 {@code rv.scrollToPosition}：它只保证「目标可见」，本浮窗里实测会把当前章落到可视区
     * 末尾。带 0 偏移的锚定才等价于「作为第一个可见项」。</p>
     */
    private static void anchorCurrentChapter(LinearLayoutManager lm, TocTreeAdapter adapter,
                                             int chapterIndex) {
        int pos = adapter.visiblePositionOfChapter(chapterIndex);
        lm.scrollToPositionWithOffset(pos >= 0 ? pos : chapterIndex, 0);
    }
}
