package com.example.myapplication.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.myapplication.R;
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.adapter.ChapterAdapter;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.util.ArrayList;
import java.util.List;

public class PopupChapterFragment extends Fragment {

    private List<ReadActivity.Chapter> chapterList;
    private int currentChapterIndex;
    private OnChapterSelectedListener listener;

    public interface OnChapterSelectedListener {
        void onChapterSelected(int chapterIndex);
    }

    public PopupChapterFragment(List<ReadActivity.Chapter> chapters, int currentIndex) {
        // 构造时再做一次防御性拷贝，防止外部列表被 clear/replaceAll 导致 Fragment 渲染崩溃
        this.chapterList = (chapters == null ? new ArrayList<>() : new ArrayList<>(chapters));
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
                ReadActivity.themeViewTree(view);
                return view;
            }

            ((TextView) view.findViewById(R.id.tv_chapter_count)).setText("共 " + chapterList.size() + " 章");

            FastScrollRecyclerView rv = view.findViewById(R.id.rv_fastscroll);
            rv.setLayoutManager(new LinearLayoutManager(getContext()));

            ChapterAdapter adapter = new ChapterAdapter(chapterList, currentChapterIndex);
            adapter.setFollowReaderTheme(true);   // 仅阅读器目录页跟随背景派生配色
            adapter.setOnChapterClickListener(c -> {
                if (listener == null) return;
                try {
                    int idx = c.getIndex();
                    if (idx < 0 || idx >= chapterList.size()) {
                        Toast.makeText(getContext(), "章节索引异常，请重试", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    listener.onChapterSelected(idx);
                } catch (Throwable t) {
                    android.util.Log.e("PopupChapter", "章节点击回调异常", t);
                    Toast.makeText(getContext(), "切换章节失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
            rv.setAdapter(adapter);

            final int scrollTarget = currentChapterIndex;
            if (scrollTarget >= 0 && scrollTarget < chapterList.size()) {
                rv.post(() -> {
                    try {
                        rv.scrollToPosition(scrollTarget);
                    } catch (Throwable ignored) {}
                });
            }
            // ✅ 目录 Fragment 根视图（背景/章节数栏）跟随日/夜间；列表项由 ChapterAdapter 在 bind 时着色
            ReadActivity.themeViewTree(view);
            return view;
        } catch (Throwable t) {
            android.util.Log.e("PopupChapter", "onCreateView 崩溃", t);
            // Fragment 创建失败时返回空布局，避免宿主 ReadActivity 崩溃 → 白屏→返回详情
            View fallback = new View(container != null ? container.getContext() : inflater.getContext());
            fallback.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            try {
                Toast.makeText(inflater.getContext(), "目录加载失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
            return fallback;
        }
    }
}
