package com.example.myapplication.activity;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ChapterDto;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器书章节预取与列表合并（2026-10-02 自 ReadActivity 拆出，纯搬移，行为不变）。
 *
 * <p>职责：
 * <ul>
 *   <li>{@link #mergeServerData}：服务器章节列表到达后重建 chapterList/chapterContents，
 *       当前章 ±半径的旧正文原位回填，其余缓存正文后台批量回填（代数守卫防跨代覆盖）；</li>
 *   <li>{@link #prefetchServerChaptersAround}：以当前章为中心由近及远预取前后
 *       {@link ReadActivity#CHAPTER_PREFETCH_RADIUS} 章到内存并落盘（SP），
 *       第二次打开本书窗口内点击即看、翻章零等待。</li>
 * </ul>
 *
 * <p>并发模型：预取走单线程串行队列（不与正文加载抢并发）；在途去重 + 60 秒失败冷却；
 * 所有回填都带「占位才写」守卫，绝不覆盖用户已触发的真实内容。
 */
class ReadChapterPrefetchController {

    private final ReadActivity activity;

    ReadChapterPrefetchController(ReadActivity activity) {
        this.activity = activity;
    }

    /** 预取去重：在途章节不重复请求。 */
    private final java.util.Set<String> serverPrefetchInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** 预取失败冷却：同一章 60 秒内不重试（离线/弱网时避免无效轮询）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> serverPrefetchFailAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 单线程串行预取：由近及远排队，不与正文加载抢并发。 */
    private final java.util.concurrent.ExecutorService serverPrefetchExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "server-chapter-prefetch");
                t.setDaemon(true);
                return t;
            });

    /**
     * 服务器书预取：以 center 为中心、由近及远把前后 CHAPTER_PREFETCH_RADIUS 章补齐到内存并落盘
     * （chapter_content_<bookId> SP）。第二次打开本书时 restoreFullChapterListFromCache 直接命中
     * 窗口内缓存正文，点击即看、翻章零等待。全程后台执行，不渲染、不覆盖真实内容。
     */
    void prefetchServerChaptersAround(int center) {
        if (activity.isExternalBook || activity.isLocalBook) return;
        final long bookId = (activity.currentBook == null) ? 0 : activity.currentBook.getId();
        if (bookId <= 0 || activity.chapterList.isEmpty()) return;
        final int size = Math.min(activity.chapterList.size(), activity.chapterContents.size());
        final long now = System.currentTimeMillis();
        for (int d = 1; d <= ReadActivity.CHAPTER_PREFETCH_RADIUS; d++) {
            // 同距离先「下一章」后「上一章」（向后读是主路径）
            tryPrefetchServerChapter(bookId, center + d, size, now);
            tryPrefetchServerChapter(bookId, center - d, size, now);
        }
    }

    private void tryPrefetchServerChapter(long bookId, int idx, int size, long now) {
        if (idx < 0 || idx >= size) return;
        long chapterId = activity.chapterList.get(idx).getId();
        if (chapterId <= 0) return;
        if (!ReadActivity.isChapterContentPending(activity.chapterContents.get(idx))) return;   // 已有正文
        final String key = bookId + ":" + idx;
        if (!serverPrefetchInFlight.add(key)) return;                     // 在途
        Long failAt = serverPrefetchFailAt.get(key);
        if (failAt != null && now - failAt < 60_000L) {                   // 失败冷却中
            serverPrefetchInFlight.remove(key);
            return;
        }
        serverPrefetchExecutor.execute(() -> {
            try {
                retrofit2.Response<ApiResponse<com.example.myapplication.bean.Chapter>> resp =
                        RetrofitClient.getApiService().getChapterContent(chapterId).execute();
                if (resp.isSuccessful() && resp.body() != null && resp.body().isSuccess()
                        && resp.body().getData() != null) {
                    String raw = resp.body().getData().getContent();
                    final String content = (raw == null || raw.trim().isEmpty() || raw.equals("\uFEFF"))
                            ? "【本章节内容暂缺】" : raw;
                    serverPrefetchFailAt.remove(key);
                    activity.runOnUiThread(() -> {
                        // 守卫：期间用户可能已翻到该章触发按需拉取 / merge 回填——绝不覆盖真实内容
                        if (idx < activity.chapterContents.size() && ReadActivity.isChapterContentPending(activity.chapterContents.get(idx))) {
                            activity.chapterContents.set(idx, content);
                            activity.cacheChapterContent(bookId, idx, content);
                        }
                    });
                } else {
                    serverPrefetchFailAt.put(key, System.currentTimeMillis());
                }
            } catch (Throwable ignored) {
                serverPrefetchFailAt.put(key, System.currentTimeMillis());
            } finally {
                serverPrefetchInFlight.remove(key);
            }
        });
    }

    /** mergeServerData 代数守卫：后台缓存回填只作用于自己那次 merge 产出的列表；
     *  期间若又发生一次 merge（章节列表整体重建），旧回填快照直接作废。 */
    private final java.util.concurrent.atomic.AtomicInteger mergeGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    void mergeServerData(List<ChapterDto> serverList) {
        final int gen = mergeGeneration.incrementAndGet();
        final long bid = activity.safeBookId();
        // 外站书不走 chapter_content_ SP 缓存（bookId=0 时 key 全都重名）；
        //    仅服务器/本地书 bookId>0 才走 SP 缓存
        final boolean contentCacheable = !activity.isExternalBook && bid > 0;

        android.util.Log.d("ReadActivity", "mergeServerData: BEFORE clear, currentChapterIndex=" + activity.currentChapterIndex);

        // 性能修复：重建前先把「当前章 ±1」的正文从旧 chapterContents 内存捕获下来
        //    （零 IO），重建后原位放回。此前这里逐章 getChapterContentCache 把全部缓存
        //    正文在主线程重读一遍（几千章 = 几千次查找 + 大字符串重建），进书后服务器
        //    列表一到就瞬卡一下。
        final int oldSize = activity.chapterContents.size();
        final int cur = activity.currentChapterIndex;
        // 性能修复：重建前先把「当前章 ±CHAPTER_PREFETCH_RADIUS」的正文从旧 chapterContents
        //    内存捕获下来（零 IO），重建后原位放回。此前这里逐章 getChapterContentCache 把全部缓存
        //    正文在主线程重读一遍（几千章 = 几千次查找 + 大字符串重建），进书后服务器
        //    列表一到就瞬卡一下。
        final String[] keep = new String[ReadActivity.CHAPTER_PREFETCH_RADIUS * 2 + 1];
        for (int k = -ReadActivity.CHAPTER_PREFETCH_RADIUS; k <= ReadActivity.CHAPTER_PREFETCH_RADIUS; k++) {
            int oi = cur + k;
            if (oi >= 0 && oi < oldSize) keep[k + ReadActivity.CHAPTER_PREFETCH_RADIUS] = activity.chapterContents.get(oi);
        }

        activity.chapterList.clear();
        activity.chapterContents.clear();
        for (int i = 0; i < serverList.size(); i++) {
            ChapterDto dto = serverList.get(i);
            ReadActivity.Chapter ch = new ReadActivity.Chapter();
            // 统一使用循环索引 i，不再使用 sortOrder，避免前后端索引不一致
            ch.setIndex(i);
            ch.setId(dto.getId());
            ch.setTitle(dto.getTitle());
            // 保留分卷键：目录浮窗据此按「最新 chapterList」实时推导分卷，保证下标一致
            ch.setSortKey(dto.getSortKey());
            activity.chapterList.add(ch);
            // 一律先占位：当前章 ±1 由上面捕获的旧内容立即回填（保证恢复渲染/顺滑翻页），
            // 其余章节的缓存正文由下方后台线程回填（离线也能翻到读过的章），
            // 未缓存的章翻到时按需拉取（isChapterContentPending 空串=true）
            activity.chapterContents.add("");

            // 调试日志：抽查前 5 章与非法 ID
            if (i < 5 || dto.getId() <= 0) {
                android.util.Log.d("ReadActivity", "mergeServerData: index=" + i + ", id="
                        + dto.getId() + ", title=" + dto.getTitle());
            }
        }

        // 当前章 ±CHAPTER_PREFETCH_RADIUS 原位回填（仅位置有效且旧内容是真实正文时才回填）
        final int newSize = activity.chapterContents.size();
        if (contentCacheable && newSize > 0) {
            for (int k = -ReadActivity.CHAPTER_PREFETCH_RADIUS; k <= ReadActivity.CHAPTER_PREFETCH_RADIUS; k++) {
                int ni = cur + k;
                String kept = keep[k + ReadActivity.CHAPTER_PREFETCH_RADIUS];
                if (ni >= 0 && ni < newSize && !ReadActivity.isChapterContentPending(kept)) {
                    activity.chapterContents.set(ni, kept);
                }
            }
        }

        // 其余章节的缓存正文改为后台批量回填（主线程零 SP 读）：
        //    1) 回填绝不覆盖真实内容——用户翻到某章触发按需拉取、网络正文先到时跳过该章；
        //    2) 代数守卫——期间若又发生一次 merge，本次快照整体作废；
        //    3) 一次性批量应用到 UI 线程，避免逐章 post。
        if (contentCacheable && newSize > 0) {
            final int snapshotGen = gen;
            final long fBid = bid;
            final List<int[]> backfillIdx = new ArrayList<>();
            final List<String> backfillContent = new ArrayList<>();
            new Thread(() -> {
                try {
                    SharedPreferences sp = activity.getSharedPreferences("chapter_content_" + fBid, Context.MODE_PRIVATE);
                    for (int i = 0; i < newSize; i++) {
                        if (Math.abs(i - cur) <= ReadActivity.CHAPTER_PREFETCH_RADIUS) continue;   // 已同步回填过
                        String c = sp.getString("content_" + i, null);
                        if (c == null || c.isEmpty()) continue;
                        backfillIdx.add(new int[]{i});
                        backfillContent.add(c);
                    }
                    activity.runOnUiThread(() -> {
                        if (mergeGeneration.get() != snapshotGen) return;
                        int limit = Math.min(newSize, activity.chapterContents.size());
                        for (int k = 0; k < backfillIdx.size(); k++) {
                            int idx = backfillIdx.get(k)[0];
                            if (idx >= limit) continue;
                            if (!ReadActivity.isChapterContentPending(activity.chapterContents.get(idx))) continue;
                            activity.chapterContents.set(idx, backfillContent.get(k));
                        }
                        // merge 就绪后触发一轮预取：窗口内缺失的章由后台补齐并落盘
                        prefetchServerChaptersAround(cur);
                    });
                } catch (Throwable ignored) {}
            }, "merge-content-backfill").start();
        }

        android.util.Log.d("ReadActivity", "mergeServerData: AFTER rebuild, currentChapterIndex="
                + activity.currentChapterIndex);
        
        // 统计非法 ID（<=0）的章节数量
        int invalidIdCount = 0;
        for (ReadActivity.Chapter ch : activity.chapterList) {
            if (ch.getId() <= 0) {
                invalidIdCount++;
            }
        }
        if (invalidIdCount > 0) {
            android.util.Log.w("ReadActivity", "Found " + invalidIdCount
                    + " chapters with invalid ID (<=0) out of " + activity.chapterList.size() + " total chapters");
        }
    }
}
