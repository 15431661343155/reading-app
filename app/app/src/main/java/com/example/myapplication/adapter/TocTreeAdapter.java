package com.example.myapplication.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.TocOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读器「目录」浮窗的适配器：支持分卷折叠树。
 *
 * <p>有分卷信息时，卷渲染成一行可展开/折叠的分组（▸/▾ 箭头），卷内章节缩进显示为该卷的子项；
 * 无分卷信息（volumes 为空）时退化成与原来完全一致的平铺章节列表。</p>
 *
 * <p>配色默认使用「宿主页面自身配色」（与 {@link ChapterAdapter} 一致），仅当显式
 * {@link #setFollowReaderTheme(boolean)} 置 true（阅读器目录浮窗）时才跟随阅读器背景派生色，
 * 避免被阅读器调色板带到书城 / 书籍详情等外部页面。</p>
 */
public class TocTreeAdapter extends RecyclerView.Adapter<TocTreeAdapter.TocHolder> {

    private static final int TYPE_VOLUME = 0;
    private static final int TYPE_CHAPTER = 1;

    /** 卷行左侧内边距（dp） */
    private static final int PAD_VOLUME_DP = 16;
    /** 章行左侧内边距（dp）：比卷行再缩进一层，形成层级感 */
    private static final int PAD_CHAPTER_DP = 36;

    private final List<ReadActivity.Chapter> chapters;
    private final List<LocalBookParser.VolumeInfo> volumes;
    private final boolean[] expanded;
    private final List<Row> rows = new ArrayList<>();

    private int currentChapterIndex;
    private OnChapterClickListener listener;
    /**
     * 是否跟随阅读器背景的派生配色。仅阅读器「目录」浮窗置 true（默认 false）。
     * 本 Adapter 现在也被书籍详情页「目录」复用，该页面不属于阅读器，
     * 必须保持自身配色，不能被阅读器的调色板带跑（与 {@link ChapterAdapter} 同款开关）。
     */
    private boolean followReaderTheme = false;
    /** 目录顺序：false = 正序（第 1 章在前，默认），true = 倒序（最后一章在前）。
     *  共用 utils.TocOrder，避免与详情页目录各自实现一遍。 */
    private final TocOrder order = new TocOrder();

    public interface OnChapterClickListener {
        void onChapterClick(ReadActivity.Chapter chapter);
    }

    private static class Row {
        final int type;
        final int volumePos;    // TYPE_VOLUME：volumes 下标
        final int chapterPos;   // TYPE_CHAPTER：chapters 下标

        Row(int type, int volumePos, int chapterPos) {
            this.type = type;
            this.volumePos = volumePos;
            this.chapterPos = chapterPos;
        }

        static Row volume(int v) { return new Row(TYPE_VOLUME, v, -1); }
        static Row chapter(int c) { return new Row(TYPE_CHAPTER, -1, c); }
    }

    public TocTreeAdapter(List<ReadActivity.Chapter> chapterList, int currentChapterIndex,
                          List<LocalBookParser.VolumeInfo> volumeList) {
        this.chapters = (chapterList == null) ? new ArrayList<>() : new ArrayList<>(chapterList);
        this.volumes = (volumeList == null) ? new ArrayList<>() : new ArrayList<>(volumeList);
        this.currentChapterIndex = Math.max(0, currentChapterIndex);
        this.expanded = new boolean[this.volumes.size()];
        // 默认只展开「当前所在卷」，其余收起；打开目录即可看到自己读到哪一卷
        for (int v = 0; v < this.volumes.size(); v++) {
            if (containsChapter(this.volumes.get(v), this.currentChapterIndex)) {
                this.expanded[v] = true;
                break;
            }
        }
        rebuildRows();
    }

    private static boolean containsChapter(LocalBookParser.VolumeInfo vol, int chapterIndex) {
        return vol != null && chapterIndex >= vol.start && chapterIndex <= vol.end;
    }

    public void setOnChapterClickListener(OnChapterClickListener l) {
        this.listener = l;
    }

    /** 是否跟随阅读器背景派生配色（默认 false；仅阅读器目录浮窗置 true） */
    public void setFollowReaderTheme(boolean follow) {
        this.followReaderTheme = follow;
    }

    public boolean isDescending() {
        return order.isDescending();
    }

    /**
     * 切换正序/倒序。倒序时整体反转：末卷在最上、卷内最后一章在最前，
     * 但卷行仍位于其组内章节之上（保证折叠树结构不被打乱）。
     *
     * <p>切序后调用方会把列表滚回第一行，所以这里顺便把「新顺序的首卷」展开：
     * 正序展开第一卷（首行下方直接是第一章）、倒序展开末卷（首行下方直接是最后一章），
     * 否则分卷书的首行只是一个收起的卷行，看不到第一章 / 最后一章。</p>
     */
    public void setDescending(boolean desc) {
        if (order.isDescending() == desc) return;
        order.setDescending(desc);
        if (!volumes.isEmpty()) {
            int edge = desc ? volumes.size() - 1 : 0;
            if (edge >= 0 && edge < expanded.length) expanded[edge] = true;
        }
        rebuildRows();
        notifyDataSetChanged();
    }

    /** 重建可见行：卷行始终可见，其子章仅在该卷展开时加入 */
    private void rebuildRows() {
        rows.clear();
        final int chSize = chapters.size();
        if (volumes.isEmpty()) {
        if (order.isDescending()) {
            for (int i = chSize - 1; i >= 0; i--) rows.add(Row.chapter(i));
        } else {
            for (int i = 0; i < chSize; i++) rows.add(Row.chapter(i));
        }
            return;
        }
        final int firstStart = Math.max(0, volumes.get(0).start);
        final int lastEnd = volumes.get(volumes.size() - 1).end;
        if (!order.isDescending()) {
            // 正序：散章前缀 → 各卷（卷行在上、子章在下）→ 散章后缀
            if (firstStart > 0) addPlainChapters(0, firstStart - 1);
            for (int v = 0; v < volumes.size(); v++) addVolumeGroup(v);
            if (lastEnd + 1 <= chSize - 1) addPlainChapters(lastEnd + 1, chSize - 1);
        } else {
            // 倒序：整体反转——散章后缀 → 各卷（从末卷起，卷行仍在其组内章节之上、组内倒序）→ 散章前缀
            if (lastEnd + 1 <= chSize - 1) addPlainChapters(chSize - 1, lastEnd + 1);
            for (int v = volumes.size() - 1; v >= 0; v--) addVolumeGroup(v);
            if (firstStart > 0) addPlainChapters(firstStart - 1, 0);
        }
    }

    /** 追加一个卷分组：先卷行，再（若已展开）其子章；倒序时组内章节反向 */
    private void addVolumeGroup(int v) {
        if (v < 0 || v >= volumes.size()) return;
        LocalBookParser.VolumeInfo vol = volumes.get(v);
        rows.add(Row.volume(v));
        if (v >= expanded.length || !expanded[v]) return;
        int from = Math.max(0, vol.childStart);
        int to = Math.min(vol.end, chapters.size() - 1);
        if (order.isDescending()) {
            for (int i = to; i >= from; i--) rows.add(Row.chapter(i));
        } else {
            for (int i = from; i <= to; i++) rows.add(Row.chapter(i));
        }
    }

    /** 追加一段「不属于任何卷」的散章；from > to 表示反向追加，越界自动跳过 */
    private void addPlainChapters(int from, int to) {
        final int max = chapters.size() - 1;
        if (from <= to) {
            for (int i = Math.max(0, from); i <= Math.min(max, to); i++) rows.add(Row.chapter(i));
        } else {
            for (int i = Math.min(max, from); i >= Math.max(0, to); i--) rows.add(Row.chapter(i));
        }
    }

    /** 当前章在可见行里的位置；找不到（被折叠在未展开的卷里）返回 -1 */
    public int visiblePositionOfChapter(int chapterIndex) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.type == TYPE_CHAPTER && r.chapterPos == chapterIndex) return i;
        }
        return -1;
    }

    /**
     * 「定位当前章节」：若当前章被折叠在未展开的卷里，先展开该卷，再返回它在可见行中的位置。
     * 返回 -1 表示当前章不在任何卷的展示范围内（调用方自行兜底，例如直接 scrollToPosition）。
     */
    public int locateCurrentChapter() {
        for (int v = 0; v < volumes.size(); v++) {
            if (!containsChapter(volumes.get(v), currentChapterIndex)) continue;
            if (!expanded[v]) {
                expanded[v] = true;
                rebuildRows();
                notifyDataSetChanged();
            }
            break;
        }
        return visiblePositionOfChapter(currentChapterIndex);
    }

    private void toggleVolume(int volumePos) {
        if (volumePos < 0 || volumePos >= expanded.length) return;
        expanded[volumePos] = !expanded[volumePos];
        rebuildRows();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TocHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_toc_entry, parent, false);
        return new TocHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TocHolder holder, int position) {
        if (position < 0 || position >= rows.size()) return;
        Row row = rows.get(position);
        final float density = holder.itemView.getResources().getDisplayMetrics().density;

        if (row.type == TYPE_VOLUME) {
            LocalBookParser.VolumeInfo vol = volumes.get(row.volumePos);
            boolean isOpen = row.volumePos < expanded.length && expanded[row.volumePos];
            holder.tvArrow.setText(isOpen ? "▾" : "▸");
            holder.tvTitle.setText(vol.title == null || vol.title.isEmpty()
                    ? ("第" + (row.volumePos + 1) + "卷") : vol.title);
            holder.tvTitle.setTextSize(16f);
            // 卷内包含当前阅读章节时，卷标题也用强调色，收起状态下也能一眼定位
            boolean holdsCurrent = containsChapter(vol, currentChapterIndex);
            holder.tvTitle.setTextColor(holdsCurrent ? accentColor() : textPrimaryColor());
            holder.rowContent.setPaddingRelative(dp(PAD_VOLUME_DP, density), 0,
                    dp(PAD_VOLUME_DP, density), 0);
            holder.itemView.setOnClickListener(v -> toggleVolume(row.volumePos));
        } else {
            if (row.chapterPos < 0 || row.chapterPos >= chapters.size()) return;
            ReadActivity.Chapter chapter = chapters.get(row.chapterPos);
            holder.tvArrow.setText("");   // 章行不显示箭头，但保留占位以对齐卷标题
            holder.tvTitle.setText(chapter.getTitle());
            holder.tvTitle.setTextSize(15f);
            boolean isCurrent = chapter.getIndex() == currentChapterIndex;
            holder.tvTitle.setTextColor(isCurrent ? accentColor() : textPrimaryColor());
            // 只有分卷书才把章行缩进一层（表示从属关系）；无分卷的平铺列表保持常规边距
            int chapterPad = volumes.isEmpty() ? PAD_VOLUME_DP : PAD_CHAPTER_DP;
            holder.rowContent.setPaddingRelative(dp(chapterPad, density), 0,
                    dp(PAD_VOLUME_DP, density), 0);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onChapterClick(chapter);
            });
        }

        // 列表项（文字/分割线/背景）跟随阅读器背景派生配色；
        // 非阅读器宿主（书籍详情页）保持自身配色
        if (followReaderTheme) {
            ReadActivity.themeViewTree(holder.itemView);
        }
    }

    /** 当前章节强调色：阅读器目录跟随背景派生色，其它页面用详情页自身配色 */
    private int accentColor() {
        return followReaderTheme ? ReadActivity.getAccentColor() : Color.parseColor("#007AFF");
    }

    /** 普通章节文字色：阅读器目录跟随背景派生色，其它页面用详情页自身配色 */
    private int textPrimaryColor() {
        return followReaderTheme ? ReadActivity.getTextPrimaryColor() : Color.parseColor("#1D1D1F");
    }

    private static int dp(int value, float density) {
        return (int) (value * density + 0.5f);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class TocHolder extends RecyclerView.ViewHolder {
        final LinearLayout rowContent;
        final TextView tvArrow;
        final TextView tvTitle;

        TocHolder(@NonNull View itemView) {
            super(itemView);
            rowContent = itemView.findViewById(R.id.row_toc_content);
            tvArrow = itemView.findViewById(R.id.tv_toc_arrow);
            tvTitle = itemView.findViewById(R.id.tv_toc_title);
        }
    }
}
