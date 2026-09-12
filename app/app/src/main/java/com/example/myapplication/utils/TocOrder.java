package com.example.myapplication.utils;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 目录「正序 / 倒序」的单一实现，供阅读器、外站书籍详情页、本站书籍详情页共用，
 * 避免三处各自实现相同的切换逻辑。
 *
 * <p>要点：
 * <ul>
 *   <li>只保存一个 {@code descending} 状态，并提供「展示位置 ↔ 真实章节下标」的双向映射；</li>
 *   <li>底层章节列表始终保留原始 / 服务器顺序，倒序只改「展示顺序」，点击仍回传真实 chapterIndex，
 *       因此不影响阅读记录与阅读器语义；</li>
 *   <li>切换顺序后通过 {@link #scrollToFirstRow(RecyclerView)} 把焦点回到第一行
 *       （正序=第一章端、倒序=最后一章端），与阅读器目录行为一致；</li>
 *   <li>按钮文案由 {@link #label(boolean)} 提供（「正序 / 倒序」），由各调用方渲染成文字按钮，
 *       与阅读器目录的 {@code btn_toc_sort} 样式保持一致。</li>
 * </ul>
 */
public final class TocOrder {

    private boolean descending;

    public TocOrder() {
        this(false);
    }

    public TocOrder(boolean descending) {
        this.descending = descending;
    }

    public boolean isDescending() {
        return descending;
    }

    /** 翻转顺序并返回新状态 */
    public boolean toggle() {
        descending = !descending;
        return descending;
    }

    public void setDescending(boolean d) {
        descending = d;
    }

    /**
     * 展示位置 → 真实章节下标。倒序时整体反转（chapters 本身保持原始顺序）。
     */
    public int toRealIndex(int displayPosition, int totalCount) {
        if (totalCount <= 0) return displayPosition;
        return descending ? (totalCount - 1 - displayPosition) : displayPosition;
    }

    /** 真实章节下标 → 展示位置 */
    public int toDisplayPosition(int realIndex, int totalCount) {
        if (totalCount <= 0) return realIndex;
        return descending ? (totalCount - 1 - realIndex) : realIndex;
    }

    /** 排序切换后把焦点回到第一行（正序=第一章端、倒序=最后一章端） */
    public static void scrollToFirstRow(RecyclerView rv) {
        if (rv == null) return;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(0, 0);
        } else {
            rv.scrollToPosition(0);
        }
    }

    /** 排序按钮文案 */
    public static String label(boolean descending) {
        return descending ? "倒序" : "正序";
    }
}
