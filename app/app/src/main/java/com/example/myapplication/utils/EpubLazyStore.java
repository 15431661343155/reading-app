package com.example.myapplication.utils;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * EPUB 懒解析仓储：不物化整本书，只靠「每章定位索引 + 两份键缓存 + 源 epub」现场重建任意一章。
 *
 * <p>为什么能成立：导入时已经把全书的样式作用域化结果（{@code css_chunks.bin}）与插图落盘引用
 *    （{@code img_refs.bin}）算好并缓存，章节拼装剩下的工作就是「读一个 zip 条目 + 正则切 body」，
 *    单章毫秒级。反过来，如果每章现跑 {@code scopeEpubCss}，真机单次 1.5~2.6 秒，翻页会直接卡死。
 *
 * <p>产出的 HTML/正文与导入时逐章内联样式的结果逐字一致，
 *    因为两条链路调用的是同一组函数：{@link LocalBookParser#decodeXhtmlBytes}、
 *    {@link LocalBookParser#buildEpubHtmlContent}、{@link LocalBookParser#cleanChapterContent}、
 *    {@link LocalBookParser#stripLeadingDuplicateTitle}。
 *
 * <p>实例与一本已打开的书绑定（内部持有源文件的 zip 句柄），用完必须 {@link #close()}。
 */
public final class EpubLazyStore implements LazyStore {

    /** 源 zip 的按需字节读取；两个实现：真实文件路径、SAF Uri 的文件描述符 */
    public interface ZipBytes {
        /** 条目不存在返回 null */
        byte[] get(String entryPath);

        void close();
    }

    private static final int CACHE_LIMIT = 3;

    private final ZipBytes zip;
    private final List<String[]> index;
    private final Map<String, String> cssChunks;
    private final Map<String, String> imgRefs;
    private final File imgDir;
    /** 最近若干章的 {HTML, 正文}；accessOrder 淘汰，翻页来回翻不重复解 */
    private final LinkedHashMap<Integer, String[]> cache;
    /**
     * ZipFile 的并发读不保证安全，而阅读器有两条线程会同时取章（UI + WebView 桥），
     * 所以「读源条目 → 产出 HTML/正文」整段串起来，缓存命中也在锁内。
     */
    private final Object lock = new Object();
    private volatile boolean closed;

    private EpubLazyStore(ZipBytes zip, List<String[]> index, Map<String, String> cssChunks,
                          Map<String, String> imgRefs, File imgDir) {
        this.zip = zip;
        this.index = index;
        this.cssChunks = cssChunks;
        this.imgRefs = imgRefs;
        this.imgDir = imgDir;
        this.cache = new LinkedHashMap<Integer, String[]>(4, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, String[]> eldest) {
                return size() > CACHE_LIMIT;
            }
        };
    }

    /**
     * 为某本本地书打开懒解析仓储。
     *
     * @param sourceUri 导入时记录的源文件 Uri（{@code book_source_uri_}）
     * @return 该书没有回源索引、源 Uri 读不到、或索引与章节数不符时返回 null（调用方据此提示本书不可读）
     */
    public static EpubLazyStore open(Context context, long bookId, Uri sourceUri) {
        if (context == null || sourceUri == null) return null;
        List<String[]> index = LocalBookParser.readChapterIndex(context, bookId);
        if (index == null || index.isEmpty()) return null;
        ZipBytes zip = openZip(context, sourceUri);
        if (zip == null) return null;
        // EPUB 插图已改为 epubres:// 协议引用，零落盘；此处传 null
        return new EpubLazyStore(zip, index,
                LocalBookParser.readCssChunkCache(context, bookId),
                LocalBookParser.readImageRefCache(context, bookId),
                null);
    }

    /** file:// 走真实路径；content:// 在 SAF 授权的 fd 上做定位读（网盘类不可 seek 的 provider 会失败） */
    private static ZipBytes openZip(Context context, Uri uri) {
        android.os.ParcelFileDescriptor pfd = null;
        try {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) return null;
                File f = new File(path);
                return f.canRead() ? new FileZipBytes(f) : null;
            }
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;
            return new ChannelZipBytes(pfd);
        } catch (Exception e) {
            android.util.Log.w("EpubLazyStore", "源文件打不开 " + uri + ": " + e.getMessage());
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignore) { /* 打不开就别留句柄 */ }
            }
            return null;
        }
    }

    private static final class FileZipBytes implements ZipBytes {
        private ZipFile zip;

        FileZipBytes(File f) throws java.io.IOException {
            zip = new ZipFile(f);
        }

        @Override
        public byte[] get(String entryPath) {
            java.util.zip.ZipEntry e = zip.getEntry(entryPath);
            if (e == null) return null;
            return readAll(e);
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (Exception ignore) { /* 关闭失败不影响正确性 */ }
        }

        private byte[] readAll(java.util.zip.ZipEntry e) {
            try (InputStream in = zip.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, e.getSize()));
                byte[] buf = new byte[1 << 13];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * SAF {@code content://} 的随机读实现：在**已授权的 fd** 上按偏移直接读，自己解析中央目录。
     *
     * <p>为什么不用 {@code new ZipFile(new File("/proc/self/fd/" + fd))}：那是个符号链接，
     * ZipFile 会对它 open()，内核重新解析到真实路径并**重新检查权限**。SAF 只授权了 fd，
     * 并没有给底层路径的文件系统权限，于是必然 EACCES（实测 Android 14 上 100% 失败）。
     *
     * <p>读一律用 {@code FileChannel.read(dst, position)} 定位读变体：它不读也不改通道游标，
     * 因此天然可并发，不依赖外层锁来保护偏移状态。
     */
    private static final class ChannelZipBytes implements ZipBytes {
        private static final int SIG_EOCD = 0x06054b50;
        private static final int SIG_EOCD64_LOC = 0x07064b50;
        private static final int SIG_EOCD64 = 0x06064b50;
        private static final int SIG_CENTRAL = 0x02014b50;
        private static final int SIG_LOCAL = 0x04034b50;
        private static final int EOCD_MIN = 22;
        private static final int EOCD_MAX_COMMENT = 0xFFFF;

        /** 条目名 → {本地头偏移, 压缩方式, 压缩后大小, 原始大小} */
        private final Map<String, long[]> entries;
        private final java.nio.channels.FileChannel ch;
        private final android.os.ParcelFileDescriptor pfd;

        ChannelZipBytes(android.os.ParcelFileDescriptor pfd) throws java.io.IOException {
            this.pfd = pfd;
            // 不持有这个流的引用、也不单独关它：关闭通道会连带关掉 fd，与下面 pfd.close() 重复关闭。
            this.ch = new java.io.FileInputStream(pfd.getFileDescriptor()).getChannel();
            this.entries = readCentralDirectory();
            if (entries.isEmpty()) throw new java.io.IOException("zip 中央目录为空，可能不是合法 epub");
        }

        private Map<String, long[]> readCentralDirectory() throws java.io.IOException {
            long size = ch.size();
            if (size < EOCD_MIN) throw new java.io.IOException("文件太小，不是 zip: " + size);
            int scan = (int) Math.min(size, EOCD_MIN + EOCD_MAX_COMMENT);
            byte[] tail = readAt(size - scan, scan);
            if (tail == null) throw new java.io.IOException("读不到文件尾，源可能不可随机访问");
            int rel = -1;
            for (int i = scan - EOCD_MIN; i >= 0; i--) {
                if (le32(tail, i) == SIG_EOCD) { rel = i; break; }
            }
            if (rel < 0) throw new java.io.IOException("找不到 zip 结束记录（EOCD）");
            long eocdAbs = size - scan + rel;
            int total = le16(tail, rel + 10);
            long cdSize = le32(tail, rel + 12);
            long cdOffset = le32(tail, rel + 16);

            // ZIP64：条目数或偏移触顶时，真值在 EOCD64 记录里（由紧邻 EOCD 前的定位器指向）
            if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                byte[] loc = readAt(eocdAbs - 20, 20);
                if (loc == null || le32(loc, 0) != SIG_EOCD64_LOC) {
                    throw new java.io.IOException("需要 ZIP64 但找不到定位器");
                }
                byte[] z64 = readAt(le64(loc, 8), 56);
                if (z64 == null || le32(z64, 0) != SIG_EOCD64) {
                    throw new java.io.IOException("ZIP64 结束记录损坏");
                }
                total = (int) le64(z64, 32);
                cdSize = le64(z64, 40);
                cdOffset = le64(z64, 48);
            }
            if (cdSize <= 0 || cdSize > (1 << 24) || cdOffset < 0 || cdOffset + cdSize > size) {
                throw new java.io.IOException("中央目录尺寸越界: off=" + cdOffset + ", size=" + cdSize);
            }

            byte[] cd = readAt(cdOffset, (int) cdSize);
            if (cd == null) throw new java.io.IOException("读不到中央目录");
            Map<String, long[]> out = new HashMap<>(Math.max(16, total * 2));
            int p = 0;
            for (int i = 0; i < total && p + 46 <= cd.length; i++) {
                if (le32(cd, p) != SIG_CENTRAL) break;
                int method = le16(cd, p + 10);
                long compSize = le32(cd, p + 20);
                long rawSize = le32(cd, p + 24);
                int nameLen = le16(cd, p + 28);
                int extraLen = le16(cd, p + 30);
                int commentLen = le16(cd, p + 32);
                long localOff = le32(cd, p + 42);
                int extraStart = p + 46 + nameLen;
                String name = new String(cd, p + 46, nameLen, "UTF-8");
                if (compSize == 0xFFFFFFFFL || rawSize == 0xFFFFFFFFL || localOff == 0xFFFFFFFFL) {
                    long[] z = zip64Extra(cd, extraStart, extraLen, rawSize, compSize, localOff);
                    rawSize = z[0];
                    compSize = z[1];
                    localOff = z[2];
                }
                out.put(name, new long[]{localOff, method, compSize, rawSize});
                p += 46 + nameLen + extraLen + commentLen;
            }
            return out;
        }

        /**
         * 解析 ZIP64 扩展字段（tag 0x0001）：字段按「原始大小、压缩大小、本地头偏移」的顺序，
         * 且**只出现固定区里触顶（0xFFFFFFFF…）的那几项**，所以必须按同一判定顺序取值。
         */
        private static long[] zip64Extra(byte[] cd, int start, int len,
                                         long rawSize, long compSize, long localOff) {
            long[] out = {rawSize, compSize, localOff};
            int p = start, end = start + len;
            while (p + 4 <= end) {
                int tag = le16(cd, p);
                int sz = le16(cd, p + 2);
                if (tag == 0x0001) {
                    int q = p + 4;
                    if (rawSize == 0xFFFFFFFFL && q + 8 <= end) { out[0] = le64(cd, q); q += 8; }
                    if (compSize == 0xFFFFFFFFL && q + 8 <= end) { out[1] = le64(cd, q); q += 8; }
                    if (localOff == 0xFFFFFFFFL && q + 8 <= end) { out[2] = le64(cd, q); }
                    return out;
                }
                p += 4 + sz;
            }
            return out;
        }

        @Override
        public byte[] get(String entryPath) {
            if (entryPath == null) return null;
            long[] e = entries.get(entryPath);
            if (e == null) return null;
            try {
                byte[] lh = readAt(e[0], 30);
                if (lh == null || le32(lh, 0) != SIG_LOCAL) return null;
                // 数据起点必须用**本地头**的名字/扩展长度：它常与中央目录的不同（对齐填充）
                long dataStart = e[0] + 30 + le16(lh, 26) + le16(lh, 28);
                int compSize = (int) e[2];
                if (compSize < 0 || compSize > (1 << 28)) return null;
                byte[] raw = compSize == 0 ? new byte[0] : readAt(dataStart, compSize);
                if (raw == null) return null;
                int method = (int) e[1];
                if (method == 0) return raw;                 // STORED
                if (method == 8) return inflateRaw(raw, (int) e[3]); // DEFLATED
                return null;                                 // 其它压缩方式：epub 不该出现
            } catch (Exception ex) {
                return null;
            }
        }

        /** 裸 deflate 解压（zip 不带 zlib 头，故 nowrap=true） */
        private static byte[] inflateRaw(byte[] raw, int expected) {
            java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
            try {
                inf.setInput(raw);
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, expected));
                byte[] buf = new byte[1 << 13];
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    // 输入已一次性给全，返回 0 只可能是「已完」或「数据截断」，两者都该停
                    if (n == 0) break;
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } catch (Exception ex) {
                return null;
            } finally {
                inf.end();
            }
        }

        /** 定位读 len 字节；读不满（截断 / 不可 seek）返回 null */
        private byte[] readAt(long pos, int len) throws java.io.IOException {
            if (pos < 0 || len < 0) return null;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(len);
            int off = 0;
            while (off < len) {
                int n = ch.read(bb, pos + off);
                if (n <= 0) break;
                off += n;
            }
            return off == len ? bb.array() : null;
        }

        @Override
        public void close() {
            // 只关 pfd：通道与包装它的 FileInputStream 都建在同一个 fd 上，
            // 再关一次会重复关闭，某些实现上会波及后来复用的 fd 号。
            try {
                pfd.close();
            } catch (Exception ignore) { /* 关闭失败不影响正确性 */ }
        }

        private static int le16(byte[] b, int i) {
            return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
        }

        private static long le32(byte[] b, int i) {
            return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8)
                    | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24);
        }

        private static long le64(byte[] b, int i) {
            return le32(b, i) | (le32(b, i + 4) << 32);
        }
    }

    /** 章数（= 索引长度）；调用方用它校验索引与 SP 里的章节数是否还对得上 */
    public int chapterCount() {
        return index.size();
    }

    /** 该章「保留样式的 HTML」；源条目缺失返回 null */
    public String html(int chapterIndex) {
        String[] pair = chapter(chapterIndex, null, false);
        return pair == null ? null : pair[0];
    }

    /**
     * 该章纯文本正文。
     *
     * @param chapterTitle 目录里该章的标题，用于剥掉正文开头重复的标题行（与导入时同一函数）
     */
    public String text(int chapterIndex, String chapterTitle) {
        String[] pair = chapter(chapterIndex, chapterTitle, true);
        return pair == null ? null : pair[1];
    }

    public void close() {
        if (closed) return;
        closed = true;
        synchronized (lock) {
            cache.clear();
            zip.close();
        }
    }

    /**
     * 取整章的 {HTML, 正文}，缺哪半就现补哪半。
     *
     * @param wantText 是否需要纯文本正文（标题为空时按「无重复标题」处理，仍返回空串以外的正文）
     */
    private String[] chapter(int chapterIndex, String chapterTitle, boolean wantText) {
        String[] loc = locate(chapterIndex);
        if (loc == null) return null;
        synchronized (lock) {
            if (closed) return null;
            String[] hit = cache.get(chapterIndex);
            // 要正文而正文还没算过 → 必须回源，不能因为「命中缓存」就返回半成品
            if (hit != null && (!wantText || hit[1] != null)) return hit;
            String xhtml = rawXhtml(loc);
            if (xhtml == null) return null;
            if (hit == null) {
                String html = LocalBookParser.buildEpubHtmlContent(xhtml, loc[1],
                        new ZipBytesMap(zip), new HashMap<>(cssChunks), new HashMap<>(imgRefs),
                        imgDir, null, null);
                hit = new String[]{html, null};
                cache.put(chapterIndex, hit);
            }
            if (wantText && hit[1] == null) {
                hit[1] = LocalBookParser.stripLeadingDuplicateTitle(
                        LocalBookParser.cleanChapterContent(xhtml), chapterTitle);
            }
            return hit;
        }
    }

    private String rawXhtml(String[] loc) {
        byte[] data = zip.get(loc[0]);
        return data == null ? null : LocalBookParser.decodeXhtmlBytes(data);
    }

    private String[] locate(int chapterIndex) {
        if (chapterIndex < 0 || chapterIndex >= index.size()) return null;
        return index.get(chapterIndex);
    }

    /**
     * 只读视图：把 zip 按需包装成 {@code Map<条目名, 字节>}，
     * 这样章节拼装逻辑（{@code buildEpubHtmlContent} 及其图片/CSS 子过程）一行都不用改。
     */
    private static final class ZipBytesMap extends java.util.AbstractMap<String, byte[]> {
        private final ZipBytes zip;

        ZipBytesMap(ZipBytes zip) {
            this.zip = zip;
        }

        @Override
        public byte[] get(Object key) {
            return key instanceof String ? zip.get((String) key) : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public java.util.Set<Entry<String, byte[]>> entrySet() {
            // 懒解析链路只按条目名点查；整表遍历会退化成把全书读进内存
            throw new UnsupportedOperationException("lazy zip map is point-lookup only");
        }
    }

    /** 供调试/校验：把索引原样导出为「条目名 + 所在目录」列表 */
    public List<String> dumpIndex() {
        List<String> out = new ArrayList<>(index.size());
        for (String[] loc : index) out.add(loc[0]);
        return out;
    }

    /**
     * 按 zip 条目名读原始字节；用于 WebView 拦截 {@code epubres://} 协议时实时取图。
     * 条目不存在返回 null。
     */
    public byte[] getZipEntry(String entryPath) {
        if (closed || entryPath == null) return null;
        synchronized (lock) {
            return zip.get(entryPath);
        }
    }
}
