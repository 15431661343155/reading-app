package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 存储占用统计与清理。
 *
 * <p>把 App 占用的空间明确分成两桶，界面上分开显示、分开处理：
 *
 * <ul>
 *   <li><b>可清缓存 {@link #getCacheBytes}</b> —— 全部可再生：删掉最多让下次打开慢一点，
 *       没有任何数据损失。包括解析 epub 的临时文件、章节/分页缓存、外站章节缓存、
 *       在线字体文件、更新包 APK、拍照临时图。</li>
 *   <li><b>本地书数据 {@link #getLocalBookBytes}</b> —— 用户自己导入的书（正文容器 + 封面），
 *       删掉需要重新导入才能恢复，因此<b>只统计、不清理</b>，仅用于让用户知道空间去哪了。</li>
 * </ul>
 *
 * <p>可清缓存进一步拆成 {@link Item} 六个分项，界面上逐项显示体积、逐项可勾选清理。
 *
 * <p><b>绝不触碰</b>：书架、阅读进度、阅读记录、书签、用户配置、头像（当前使用中的那个）。
 */
public final class CacheManager {

    private CacheManager() {
    }

    // ==================== 分项定义 ====================

    /**
     * 可清缓存的分项。
     *
     * <p>分项之间<b>不重叠</b>，六项之和恒等于 {@link #getCacheBytes}。
     * 新增分项时必须同步检查 {@link #getItemBytes} 与 {@link #clearItem}，
     * 否则会出现「统计得到但清不掉」的幽灵条目。
     */
    public enum Item {
        /** 章节列表、分卷表、章节正文（按书分文件的那些 SP）。 */
        CHAPTER_CACHE,
        /** 分页信息与在线阅读正文内容缓存。 */
        READING_CACHE,
        /** 已下载的在线字体。 */
        FONT,
        /** 更新包 APK 与图片选择临时图。 */
        UPDATE_TMP,
        /** 除当前使用中之外的头像历史文件。 */
        AVATAR,
        /** cacheDir 里未被上面归类的散碎临时文件（epub 解析中间产物等）。 */
        OTHER,
    }

    /** 各分项在「章节/阅读」两桶里对应的 SP 文件名（不含 .xml）。 */
    private static final String[] SP_CHAPTER_NAMES = {
            "external_chapter_cache",
            "external_chapter_content_cache",
            "chapter_cache",
    };
    private static final String[] SP_CHAPTER_PREFIXES = {
            "chapter_list_",
            "chapter_content_",
    };
    private static final String[] SP_READING_NAMES = {
            "page_cache",
    };

    /** 全部可再生 SP（用于 {@link #getCacheBytes} 求和，等于两桶并集）。 */
    private static boolean isCacheSp(String name) {
        if (name == null) return false;
        if (isChapterSpName(name)) return true;
        for (String n : SP_READING_NAMES) {
            if (n.equals(name)) return true;
        }
        return false;
    }

    private static boolean isChapterSpName(String name) {
        if (name == null) return false;
        for (String n : SP_CHAPTER_NAMES) {
            if (n.equals(name)) return true;
        }
        for (String p : SP_CHAPTER_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    // ==================== 分项统计 ====================

    /** 单个分项的体积（字节）。 */
    public static long getItemBytes(Context context, Item item) {
        if (context == null || item == null) return 0;
        switch (item) {
            case CHAPTER_CACHE:
                return sizeOfChapterSp(context);
            case READING_CACHE:
                return sizeOfReadingSp(context);
            case FONT:
                return sizeOf(new File(context.getFilesDir(), "fonts"));
            case UPDATE_TMP:
                long t = sizeOf(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS));
                t += sizeOf(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES));
                return t;
            case AVATAR:
                return getAvatarWasteBytes(context);
            case OTHER:
                return sizeOfOtherCache(context);
            default:
                return 0;
        }
    }

    /**
     * 可清缓存的总体积（字节）。
     *
     * <p>等于六个分项之和，保证抽屉里的分项数字加起来就是顶部那个大字。
     */
    public static long getCacheBytes(Context context) {
        if (context == null) return 0;
        long total = 0;
        for (Item item : Item.values()) {
            total += getItemBytes(context, item);
        }
        return total;
    }

    /**
     * 本地书数据体积（字节）—— 用户导入的正文容器 + 封面。
     * <p>只读不删，纯粹用于展示「空间去哪了」。
     */
    public static long getLocalBookBytes(Context context) {
        if (context == null) return 0;
        long total = 0;
        // 正文容器目录（懒解析书只有索引+键缓存，体积从几十 MB 降到几百 KB）
        total += sizeOf(new File(context.getFilesDir(), "local_book_html"));
        // EPUB 插图已改为 epubres:// 协议引用，零落盘，不再占用额外空间
        // 封面（cover_<bookId>.<ext> 直接躺在 filesDir 根下）
        File filesDir = context.getFilesDir();
        File[] children = filesDir == null ? null : filesDir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isFile() && f.getName().startsWith("cover_")) {
                    total += f.length();
                }
            }
        }
        return total;
    }

    /** 头像历史残留体积（字节）—— 除当前使用中的那个之外的全部历史文件。 */
    public static long getAvatarWasteBytes(Context context) {
        if (context == null) return 0;
        File[] stale = staleAvatarFiles(context);
        long total = 0;
        for (File f : stale) total += sizeOf(f);
        return total;
    }

    /** 章节与分卷缓存（SP）。 */
    private static long sizeOfChapterSp(Context context) {
        File dir = sharedPrefsDir(context);
        return sizeOfSpIn(dir, true);
    }

    /** 分页与在线阅读缓存（SP）。 */
    private static long sizeOfReadingSp(Context context) {
        File dir = sharedPrefsDir(context);
        return sizeOfSpIn(dir, false);
    }

    /**
     * cacheDir 里不属于「在线字体 / 更新包 / 头像」的剩余部分。
     *
     * <p>cacheDir 是系统给的私有缓存目录，装的是 epub 解析中间产物一类的散碎文件，
     * 无法再细分，也不需要细分——统一用「其他临时文件」呈现。
     */
    private static long sizeOfOtherCache(Context context) {
        File cacheDir = context.getCacheDir();
        if (cacheDir == null || !cacheDir.exists()) return 0;
        long total = 0;
        File[] children = cacheDir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            total += sizeOf(child);
        }
        return total;
    }

    // ==================== 分项清理 ====================

    /**
     * 清理单个分项，返回实际释放的字节数。
     *
     * @return 释放的字节数；分项不存在或删除失败返回 0
     */
    public static long clearItem(Context context, Item item) {
        if (context == null || item == null) return 0;
        switch (item) {
            case CHAPTER_CACHE:
                return clearSpIn(context, true);
            case READING_CACHE:
                return clearSpIn(context, false);
            case FONT:
                return clearDirOrFile(new File(context.getFilesDir(), "fonts"));
            case UPDATE_TMP:
                long freed = clearDirContents(
                        context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS));
                freed += clearDirContents(
                        context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES));
                return freed;
            case AVATAR:
                long a = 0;
                for (File f : staleAvatarFiles(context)) {
                    long before = sizeOf(f);
                    if (deleteRecursively(f)) a += before;
                }
                return a;
            case OTHER:
                return clearDirContents(context.getCacheDir());
            default:
                return 0;
        }
    }

    /**
     * 清理选中的分项。
     *
     * @param items 要清理的分项；null 或空表示不清理任何东西
     * @return 实际释放的字节数
     */
    public static long clearItems(Context context, List<Item> items) {
        if (context == null || items == null || items.isEmpty()) return 0;
        long freed = 0;
        for (Item item : items) {
            freed += clearItem(context, item);
        }
        return freed;
    }

    /**
     * 清理全部可清缓存（含头像历史残留）。
     *
     * @return 实际释放的字节数
     */
    public static long clearCache(Context context) {
        if (context == null) return 0;
        long freed = 0;
        for (Item item : Item.values()) {
            freed += clearItem(context, item);
        }
        return freed;
    }

    // ==================== 内部实现 ====================

    /** 统计 shared_prefs 下指定类别（章节 / 阅读）的体积。 */
    private static long sizeOfSpIn(File dir, boolean chapter) {
        if (dir == null) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long total = 0;
        for (File f : files) {
            String base = spBaseName(f);
            if (base == null) continue;
            if (chapter ? isChapterSpName(base) : isReadingSpName(base)) total += f.length();
        }
        return total;
    }

    private static boolean isReadingSpName(String name) {
        for (String n : SP_READING_NAMES) {
            if (n.equals(name)) return true;
        }
        return false;
    }

    /** 清掉 shared_prefs 下指定类别（章节 / 阅读）的文件。 */
    private static long clearSpIn(Context context, boolean chapter) {
        File dir = sharedPrefsDir(context);
        if (dir == null) return 0;
        long freed = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            String base = spBaseName(f);
            if (base == null) continue;
            if (!(chapter ? isChapterSpName(base) : isReadingSpName(base))) continue;
            long len = f.length();
            // 先清空内容再删：SP 在本进程内可能有内存副本，
            // 直接删文件会让后续 getSharedPreferences 读到旧的内存缓存。
            try {
                context.getSharedPreferences(base, Context.MODE_PRIVATE)
                        .edit().clear().apply();
            } catch (Exception ignored) {
                // 文件损坏等情况：忽略，继续删文件
            }
            if (f.delete()) freed += len;
        }
        return freed;
    }

    /** 取 shared_prefs 目录下一个文件对应的 SP 名（去掉 .xml）；非 xml 返回 null。 */
    private static String spBaseName(File f) {
        String n = f.getName();
        if (!n.endsWith(".xml")) return null;
        return n.substring(0, n.length() - 4);
    }

    private static File sharedPrefsDir(Context context) {
        // /data/data/<pkg>/shared_prefs
        File dataDir = context.getFilesDir() == null ? null : context.getFilesDir().getParentFile();
        return dataDir == null ? null : new File(dataDir, "shared_prefs");
    }

    /**
     * 找出头像目录里「不是当前正在使用」的历史文件。
     *
     * <p>每次裁剪头像都会新增一个 {@code avatar_cropped_<时间戳>.jpg}，旧文件从不删除。
     * 当前头像路径存在 {@code user_info} 的 {@code avatar} 键里，这里只保留它。
     */
    private static File[] staleAvatarFiles(Context context) {
        File dir = new File(context.getFilesDir(), "avatars");
        if (!dir.exists() || !dir.isDirectory()) return new File[0];

        String current = "";
        try {
            SharedPreferences sp = context.getSharedPreferences("user_info", Context.MODE_PRIVATE);
            String v = sp.getString("avatar", "");
            if (v != null) current = v;
        } catch (Exception ignored) {
        }

        File[] all = dir.listFiles();
        if (all == null) return new File[0];

        List<File> stale = new ArrayList<>();
        for (File f : all) {
            if (!f.isFile()) continue;
            // 当前头像：按绝对路径精确匹配，匹配上就保留
            if (!current.isEmpty() && current.equals(f.getAbsolutePath())) continue;
            stale.add(f);
        }
        return stale.toArray(new File[0]);
    }

    /** 清空目录内容但保留目录本身。 */
    private static long clearDirContents(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long freed = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            long before = sizeOf(child);
            if (deleteRecursively(child)) freed += before;
        }
        return freed;
    }

    /** 删除文件或目录本身（用于 fonts 这类整目录可丢的）。 */
    private static long clearDirOrFile(File target) {
        if (target == null || !target.exists()) return 0;
        long before = sizeOf(target);
        return deleteRecursively(target) ? before : 0;
    }

    /** 递归统计目录/文件体积。 */
    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        File[] children = file.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File child : children) {
            total += sizeOf(child);
        }
        return total;
    }

    /** 递归删除；返回是否全部删掉。 */
    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    // ==================== 展示 ====================

    /** 把字节数格式化成人类可读体积，如 "12.5 MB"、"380 KB"。 */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb);
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0);
    }
}
