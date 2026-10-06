package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * 本地书后台构建队列：导入页只等「书架卡片那几项」，剩下的整本解析交给这里，
 * 跑完再把精修目录发布进 SharedPreferences、把回源索引改名到位。
 *
 * <p>拆成两段的原因（真机实测）：15MB/544 章的 epub 卡片信息只有 34ms（OPF+NCX+封面），
 * 逐章定标题/剔非正文/预热样式键表却要 219~1819ms，而整本解压又要 140~398ms——
 * 用户按「导入」时并不该等后面这两段。
 *
 * <p>发布顺序是<b>目录先、索引后</b>：阅读器拿「index.bin 是否存在」当「这本书能读」的依据，
 * 并假定它与 SharedPreferences 里的章数一致（{@code ReadActivity.lazyStore} 会校验）。
 * 所以索引必须最后一步才露面（见 {@link LocalBookParser#publishPending}）。
 * 进程在两件事之间被杀，结果是「目录已精修、索引还没写」——那种情况由阅读器等索引/自愈重建兜住。
 *
 * <p>任务按 <b>bookId</b> 而不是槽位寻址：删书会把槽位压缩重排，落盘文件名却始终是 bookId，
 * 只有它在这个队列的生命周期里稳定。
 */
public final class LocalBookImport {

    private static final String TAG = "LocalBookImport";

    /** 单线程：一次只允许一本书在解压整本，导入 5 本书不该把 5 份全书内容同时压进内存 */
    private static final ExecutorService EXEC =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "local-book-build");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    /** 正在构建（或已排队）的书；同书重复入队会被它挡住 */
    private static final Set<Long> inFlight = new HashSet<>();

    private LocalBookImport() {
    }

    /**
     * 排队构建一本书的整本索引。调用方（导入页/阅读器自愈）不需要等，也不假设它一定成功：
     * 失败的书就是「没索引」，打开时由阅读器提示不可读。
     *
     * @param fileName 导入时的原始文件名，用来判格式；阅读器自愈时传 {@code null}，
     *                 由 {@link #resolveFileName} 按文件头猜（书架记录里没有存文件名的键）。
     * @return 本次是否新入队；false 表示这本书已经在构建中
     */
    public static boolean enqueue(Context context, long bookId, Uri sourceUri, String fileName) {
        if (context == null || sourceUri == null || bookId <= 0) return false;
        final Context app = context.getApplicationContext();
        synchronized (inFlight) {
            if (!inFlight.add(bookId)) return false;
        }
        try {
            EXEC.execute(() -> {
                try {
                    build(app, bookId, sourceUri, resolveFileName(app, sourceUri, fileName));
                } catch (Throwable t) {
                    Log.e(TAG, "后台构建失败 bookId=" + bookId + ": " + t.getMessage(), t);
                    safeDrop(app, bookId);
                } finally {
                    synchronized (inFlight) {
                        inFlight.remove(bookId);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            // 线程池拒绝（机型上偶发）：不入队就别让调用方一直等
            synchronized (inFlight) {
                inFlight.remove(bookId);
            }
            Log.e(TAG, "后台构建入队失败: " + t.getMessage());
            return false;
        }
    }

    /** 这本书是否还在构建/排队中（阅读器据此决定继续等待还是自己发起一次重建） */
    public static boolean isBuilding(long bookId) {
        synchronized (inFlight) {
            return inFlight.contains(bookId);
        }
    }

    /**
     * 按书架记录自愈重建：从 {@code local_books} 取这本书记下的源文件 Uri 入队。
     * 进程在构建中途被杀、或旧版本导入时没写索引的书，都靠它补上。
     *
     * @return false 表示这本书已不在书架，或当初没记下可回源的 Uri（那种书读不出正文）
     */
    public static boolean enqueueByBookId(Context context, long bookId) {
        if (context == null || bookId <= 0) return false;
        Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("local_books", Context.MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0L) != bookId) continue;
            String src = sp.getString("book_source_uri_" + i, "");
            if (src == null || src.isEmpty()) return false;
            Uri uri;
            try {
                uri = Uri.parse(src);
            } catch (Throwable t) {
                return false;
            }
            return enqueue(app, bookId, uri, null);
        }
        return false;
    }

    /**
     * 判格式用的文件名：导入页有原名就用；阅读器自愈只有 bookId 与源 Uri，而 SAF 的 Uri 末段
     * 常常是不透明的 id，所以读文件头两个字节——zip 是 {@code PK}，其余按 TXT 处理。
     */
    private static String resolveFileName(Context app, Uri uri, String fileName) {
        if (fileName != null && !fileName.isEmpty()) return fileName;
        try (java.io.InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                byte[] head = new byte[2];
                int n = 0;
                while (n < 2) {
                    int r = in.read(head, n, 2 - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n == 2 && head[0] == 'P' && head[1] == 'K') return "book.epub";
            }
        } catch (Throwable t) {
            Log.w(TAG, "源文件头读不到，按 TXT 尝试: " + t.getMessage());
        }
        return "book.txt";
    }

    private static void build(Context app, long bookId, Uri sourceUri, String fileName) {
        long t0 = System.currentTimeMillis();
        LocalBookParser.BookInfo info = LocalBookParser.parse(app, sourceUri, fileName, bookId);
        long tParse = System.currentTimeMillis();
        if (info == null || info.chapters == null || info.chapters.isEmpty()) {
            safeDrop(app, bookId);
            Log.w(TAG, "整本解析没出章节，跳过发布 bookId=" + bookId);
            return;
        }
        // 产物先写成 *.bin.pending：此刻阅读器看到的仍是「无索引」，不会读到半成品
        try {
            LocalBookParser.writeBookChapters(app, bookId, info, true);
        } catch (Throwable t) {
            Log.e(TAG, "索引落盘失败 bookId=" + bookId + ": " + t.getMessage());
            safeDrop(app, bookId);
            return;
        }
        if (!LocalBookParser.hasPendingIndex(app, bookId)) {
            // 有一章拿不到定位串 → 没写索引。这种书保持前台的粗版目录（不进书架的章数不改），
            // 打开时由阅读器提示不可读；绝不能把目录覆盖成一个「第一章」占位条目。
            safeDrop(app, bookId);
            Log.w(TAG, "本书无可用回源索引，保留前台目录 bookId=" + bookId);
            return;
        }
        long tWrite = System.currentTimeMillis();
        if (!publishCatalog(app, bookId, info)) {
            // 书已经不在了（导入后立刻被删）：连缓存目录一起收回，别留一个空目录给孤儿清扫去认
            try {
                LocalBookParser.deleteHtmlCache(app, bookId);
            } catch (Throwable t) {
                Log.w(TAG, "回收已删书的缓存失败: " + t.getMessage());
            }
            return;
        }
        // 最后一步：索引改名到位（见类注释的顺序约定）
        boolean published = LocalBookParser.publishPending(app, bookId);
        long tEnd = System.currentTimeMillis();
        Log.d(TAG, "IMPORTBUILD bookId=" + bookId
                + " chapters=" + info.chapters.size()
                + " parseMs=" + (tParse - t0) + " writeMs=" + (tWrite - tParse)
                + " publishMs=" + (tEnd - tWrite) + " totalMs=" + (tEnd - t0)
                + " ok=" + published);
    }

    /**
     * 把精修目录写进 {@code local_books}：章数、每章标题、分卷表，以及只在正文里才找得到的简介。
     *
     * @return false 表示这本书已经不在书架上（导入后立刻被删），发布应中止
     */
    private static boolean publishCatalog(Context app, long bookId, LocalBookParser.BookInfo info) {
        SharedPreferences sp = app.getSharedPreferences("local_books", Context.MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        int slot = -1;
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0) == bookId) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            Log.w(TAG, "书架上已没有 bookId=" + bookId + "，放弃发布目录");
            return false;
        }

        int oldChapterCount = sp.getInt("chapter_count_" + slot, 0);
        List<LocalBookParser.Chapter> chapters = info.chapters;
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt("chapter_count_" + slot, chapters.size());
        for (int i = 0; i < chapters.size(); i++) {
            editor.putString("chapter_title_" + slot + "_" + i, chapters.get(i).title);
        }
        // 前台粗版可能比精修版长（多几页封面/版权/简介），多出来的键没人按索引读，但留着会误导排查
        for (int i = chapters.size(); i < oldChapterCount; i++) {
            editor.remove("chapter_title_" + slot + "_" + i);
        }
        // 分卷表按精修后的章序重建：粗版里的封面/版权页会占掉卷起始章，必须一起改
        List<LocalBookParser.VolumeInfo> volumes = LocalBookParser.buildVolumeInfos(info);
        int oldVolumeCount = sp.getInt("book_volume_count_" + slot, 0);
        editor.putInt("book_volume_count_" + slot, volumes.size());
        for (int v = 0; v < volumes.size(); v++) {
            LocalBookParser.VolumeInfo vi = volumes.get(v);
            editor.putString("book_volume_title_" + slot + "_" + v, vi.title != null ? vi.title : "");
            editor.putInt("book_volume_start_" + slot + "_" + v, vi.start);
            editor.putInt("book_volume_child_" + slot + "_" + v, vi.childStart);
            editor.putInt("book_volume_end_" + slot + "_" + v, vi.end);
        }
        for (int v = volumes.size(); v < oldVolumeCount; v++) {
            editor.remove("book_volume_title_" + slot + "_" + v);
            editor.remove("book_volume_start_" + slot + "_" + v);
            editor.remove("book_volume_child_" + slot + "_" + v);
            editor.remove("book_volume_end_" + slot + "_" + v);
        }
        // 简介：EPUB 的简介常是正文里的一章，只有整本解析才看得见；前台取到、这里取不到时不清空
        String intro = info.intro;
        if (intro != null && !intro.isEmpty()) {
            editor.putString("book_intro_" + slot, intro);
        }
        editor.apply();
        return true;
    }

    private static void safeDrop(Context app, long bookId) {
        try {
            LocalBookParser.dropPending(app, bookId);
        } catch (Throwable t) {
            Log.w(TAG, "清理 pending 失败: " + t.getMessage());
        }
    }
}
