package com.example.myapplication.utils;

import com.example.myapplication.bean.MajorChapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「章节 sortKey + 分卷列表 → 分卷结构(VolumeInfo)」的唯一实现，
 * 供阅读器目录浮窗与书籍详情页目录共用，避免两处各写一份推导逻辑。
 *
 * <p>服务器书（含后台导入的 EPUB）的章节由后端写入 {@code ChapterDto.sortKey}，
 * 形如 {@code "1-0001"}：前缀 {@code "1"} 是卷号，对应 major_chapter 表的
 * {@code MajorChapter.sortKey}；后缀是该卷内的章号。后端导入时「卷标题页」不产生 Chapter
 * （见 EpubBookParser），因此这里按「同一卷的连续章节」直接构成一个卷区间，
 * 不存在「卷名占位章」需要从目录子项里另行剔除。</p>
 *
 * <p><b>安全降级</b>：只要出现「任一章节 sortKey 为空 / 解析不出卷号」、或分卷列表为空、
 * 或最终卷数 &lt; 2（单卷等于没分卷），一律返回空列表，调用方保持平铺展示 ——
 * 宁可退回平铺，也绝不产生错位分组。返回的 {@code start/end} 始终是入参章节列表的下标，
 * 因此只要在「最终生效的章节列表」上调用，卷区间就与下标严格一致。</p>
 */
public final class VolumeDeriver {

    private VolumeDeriver() {
        // 工具类，禁止实例化
    }

    /**
     * 推导分卷结构。
     *
     * @param chapterSortKeys 与章节列表<b>同序等长</b>的 sortKey 列表（下标 i 即该章在章节列表中的下标）
     * @param majorChapters   后端返回的分卷列表（title / sortKey / sortOrder）
     * @return 分卷结构（卷数 ≥ 2 才返回）；无法安全推导时返回空列表
     */
    public static List<LocalBookParser.VolumeInfo> derive(List<String> chapterSortKeys,
                                                          List<MajorChapter> majorChapters) {
        List<LocalBookParser.VolumeInfo> result = new ArrayList<>();
        if (chapterSortKeys == null || chapterSortKeys.isEmpty()
                || majorChapters == null || majorChapters.isEmpty()) {
            return result;
        }

        // 卷键（trim 后）→ 卷信息；重复键取首个，保证映射唯一
        Map<String, MajorChapter> byKey = new HashMap<>();
        for (MajorChapter mc : majorChapters) {
            if (mc == null) continue;
            String key = trimToEmpty(mc.getSortKey());
            if (key.isEmpty() || byKey.containsKey(key)) continue;
            byKey.put(key, mc);
        }
        if (byKey.isEmpty()) return result;

        // 游标：当前正在累积的卷（cur==null 表示该章不属于任何卷）
        String curKey = null;
        LocalBookParser.VolumeInfo cur = null;

        for (int i = 0; i < chapterSortKeys.size(); i++) {
            String key = volumeKeyOf(chapterSortKeys.get(i));
            // 任一章节解析不出卷号：数据不完整，整体放弃分卷（退回平铺），避免半截分组错位
            if (key == null) return new ArrayList<>();
            MajorChapter mc = byKey.get(key);
            if (mc == null) {
                // 该章不属于任何已登记卷（例如正文前置的版权/序言页）：中断当前卷的连续性
                curKey = null;
                cur = null;
                continue;
            }
            if (cur != null && key.equals(curKey)) {
                cur.end = i;
            } else {
                cur = new LocalBookParser.VolumeInfo();
                // 卷标题页不产生 Chapter，所以目录子项与卷起点一致
                cur.start = i;
                cur.childStart = i;
                cur.end = i;
                String title = trimToEmpty(mc.getTitle());
                cur.title = title.isEmpty() ? ("第" + (result.size() + 1) + "卷") : title;
                result.add(cur);
                curKey = key;
            }
        }

        if (result.size() < 2) return new ArrayList<>();   // 单卷等于没分卷
        for (int v = 0; v < result.size(); v++) result.get(v).index = v + 1;   // 卷序号，1 起
        return result;
    }

    /** 取 sortKey 中 '-' 之前的卷前缀并 trim；为空 / 无 '-' / '-' 在行首时返回 null（视作解析失败） */
    private static String volumeKeyOf(String sortKey) {
        if (sortKey == null) return null;
        String s = sortKey.trim();
        int dash = s.indexOf('-');
        if (dash <= 0) return null;
        String key = s.substring(0, dash).trim();
        return key.isEmpty() ? null : key;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
