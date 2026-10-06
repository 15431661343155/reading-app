package com.example.myapplication.utils;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TXT 懒解析仓储：不物化整本正文，只靠「每章源文件字节区间 + 字符集」（{@code txtindex.bin}）与源 txt
 * 现场重建任意一章的纯文本。
 *
 * <p>为什么能逐字节复现：导入时 {@code parseTxt} 已按 0x0A 扫描出每行的起始字节偏移，并把每章正文的
 * 字节区间 {@code [start, end)} 记进索引。回源时定位读这段字节、用**同一字符集**解码，得到的正是
 * 导入时 {@code text.split("\n")} 里 {@code [章节标题后一行, 下一章标题行)} 那几行；再跑与导入完全一致的
 * 行循环（跳过 trim 为空的行、其余原样 {@code raw + "\n"} 拼接）即可得到与物化正文逐字相同的字符串。
 * UTF-8 / GBK 里 0x0A 绝不会出现在多字节序列内部，故按字节切行天然字符对齐——这也是导入端只对这两种
 * 字符集启用懒解析的原因（UTF-16 等不安全，整本物化）。
 *
 * <p>TXT 没有「保留样式的 HTML」，{@link #html} 恒返回 null，调用方直接用 {@link #text} 的纯文本渲染。
 *
 * <p>已知取舍：源文件被删/被移动后本书就读不出来了（索引只存字节区间，不存正文）。这是懒解析的既定权衡。
 *
 * <p>实例与一本已打开的书绑定（内部持有源文件句柄），用完必须 {@link #close()}。
 */
public final class TxtLazyStore implements LazyStore {

    /** 源文件的按需字节区间读取；两个实现：真实文件路径、SAF Uri 的文件描述符 */
    private interface ByteRange {
        /** 读 {@code [start, end)} 的字节；读不满（截断/不可 seek/越界）返回 null */
        byte[] read(long start, long end);

        void close();
    }

    private static final int CACHE_LIMIT = 3;

    private final ByteRange src;
    private final LocalBookParser.TxtIndex index;
    private final String charset;
    /** 最近若干章重建出的正文；accessOrder 淘汰，翻页来回翻不重复解 */
    private final LinkedHashMap<Integer, String> cache;
    /** 读源与重建串成一段，缓存命中也在锁内，避免两条线程（UI + WebView 桥）同时读句柄 */
    private final Object lock = new Object();
    private volatile boolean closed;

    private TxtLazyStore(ByteRange src, LocalBookParser.TxtIndex index, String charset) {
        this.src = src;
        this.index = index;
        this.charset = charset;
        this.cache = new LinkedHashMap<Integer, String>(4, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                return size() > CACHE_LIMIT;
            }
        };
    }

    /**
     * 为某本本地 TXT 书打开懒解析仓储。
     *
     * @param sourceUri 导入时记录的源文件 Uri（{@code book_source_uri_}）
     * @return 该书没有 txt 索引、源 Uri 读不到时返回 null（调用方据此提示本书不可读）
     */
    public static TxtLazyStore open(Context context, long bookId, Uri sourceUri) {
        if (context == null || sourceUri == null) return null;
        LocalBookParser.TxtIndex index = LocalBookParser.readTxtIndex(context, bookId);
        if (index == null || index.count() == 0) return null;
        ByteRange src = openSource(context, sourceUri);
        if (src == null) return null;
        String cs = index.charset == null || index.charset.isEmpty() ? "UTF-8" : index.charset;
        return new TxtLazyStore(src, index, cs);
    }

    /** file:// 走 RandomAccessFile；content:// 在 SAF 授权的 fd 上做定位读 */
    private static ByteRange openSource(Context context, Uri uri) {
        android.os.ParcelFileDescriptor pfd = null;
        try {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) return null;
                File f = new File(path);
                return f.canRead() ? new FileByteRange(f) : null;
            }
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;
            return new ChannelByteRange(pfd);
        } catch (Exception e) {
            android.util.Log.w("TxtLazyStore", "源文件打不开 " + uri + ": " + e.getMessage());
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignore) { /* 打不开就别留句柄 */ }
            }
            return null;
        }
    }

    private static final class FileByteRange implements ByteRange {
        private final RandomAccessFile raf;

        FileByteRange(File f) throws java.io.IOException {
            raf = new RandomAccessFile(f, "r");
        }

        @Override
        public byte[] read(long start, long end) {
            if (start < 0 || end < start) return null;
            long len = end - start;
            if (len > Integer.MAX_VALUE) return null;
            try {
                if (len == 0) return new byte[0];
                raf.seek(start);
                byte[] buf = new byte[(int) len];
                raf.readFully(buf);
                return buf;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void close() {
            try {
                raf.close();
            } catch (Exception ignore) { /* 关闭失败不影响正确性 */ }
        }
    }

    /**
     * SAF {@code content://} 的定位读实现：在**已授权的 fd** 上按偏移直接读。
     *
     * <p>与 {@link EpubLazyStore} 同理，不走 {@code /proc/self/fd}（那会让内核重新解析真实路径并重查权限，
     * SAF 只授权了 fd 而非底层路径，Android 14 上必 EACCES）。读一律用
     * {@code FileChannel.read(dst, position)} 定位读变体：不读也不改通道游标，天然可并发。
     */
    private static final class ChannelByteRange implements ByteRange {
        private final FileChannel ch;
        private final android.os.ParcelFileDescriptor pfd;

        ChannelByteRange(android.os.ParcelFileDescriptor pfd) {
            this.pfd = pfd;
            // 不单独持有/关闭这个流：关闭 pfd 会连带关掉同一 fd，重复关闭反而可能波及后来复用的 fd 号
            this.ch = new java.io.FileInputStream(pfd.getFileDescriptor()).getChannel();
        }

        @Override
        public byte[] read(long start, long end) {
            if (start < 0 || end < start) return null;
            long len = end - start;
            if (len > Integer.MAX_VALUE) return null;
            if (len == 0) return new byte[0];
            try {
                ByteBuffer bb = ByteBuffer.allocate((int) len);
                int off = 0;
                while (off < len) {
                    int n = ch.read(bb, start + off);
                    if (n <= 0) break;
                    off += n;
                }
                return off == len ? bb.array() : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void close() {
            try {
                pfd.close();
            } catch (Exception ignore) { /* 关闭失败不影响正确性 */ }
        }
    }

    @Override
    public int chapterCount() {
        return index.count();
    }

    /** TXT 无保留样式的 HTML，恒返回 null；调用方直接用 {@link #text} 的纯文本渲染 */
    @Override
    public String html(int chapterIndex) {
        return null;
    }

    @Override
    public String text(int chapterIndex, String chapterTitle) {
        if (chapterIndex < 0 || chapterIndex >= index.count()) return null;
        synchronized (lock) {
            if (closed) return null;
            String hit = cache.get(chapterIndex);
            if (hit != null) return hit;
            long start = index.starts[chapterIndex];
            long end = index.ends[chapterIndex];
            if (start < 0 || end < start) return null;
            byte[] data = src.read(start, end);
            if (data == null) return null;
            String content = rebuildContent(data);
            cache.put(chapterIndex, content);
            return content;
        }
    }

    /**
     * 用与导入 {@code parseTxt} 完全一致的行循环重建正文：按 charset 解码字节区间后 {@code split("\n")}，
     * 跳过 trim 为空的行，其余原样 {@code raw + "\n"} 拼接。字节区间已排除标题行，故无需再剥标题。
     */
    private String rebuildContent(byte[] data) {
        String decoded;
        try {
            decoded = new String(data, charset);
        } catch (Exception e) {
            try {
                decoded = new String(data, "UTF-8");
            } catch (Exception e2) {
                return "";
            }
        }
        String[] lines = decoded.split("\n");
        StringBuilder sb = new StringBuilder(data.length + 16);
        for (String raw : lines) {
            if (raw.trim().isEmpty()) continue;
            sb.append(raw).append("\n");
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        synchronized (lock) {
            cache.clear();
            src.close();
        }
    }
}
