package com.example.myapplication.activity;

/**
 * 阅读器排版缓存：把「章节分页结果」按 layout signature 落盘，下次进入同章同排版直接复用。
 *
 * layout signature 由「书籍 + 章节 + 字号 + 视口宽高 + 页眉页脚开关 + 字体 + 内容哈希」组成，
 * 任一变动（改字号 / 换字体 / 旋转 / 改内容）都会自然错开缓存 → 自动失效并重排。
 * 缓存目录、单线程写入池与容量淘汰（LAYOUT_CACHE_MAX_FILES）均由本类托管，Activity 只经
 * 委托桩调用 read/write/compute 三个入口。
 *
 * 2026-10-02 自 ReadActivity 拆出（纯搬移，行为不变）。
 */
class ReadLayoutCache {

    private static final int LAYOUT_CACHE_MAX_FILES = 300;

    private final ReadActivity activity;
    private final java.io.File layoutCacheDir;
    private final java.util.concurrent.ExecutorService layoutExecutor;

    ReadLayoutCache(ReadActivity activity) {
        this.activity = activity;
        // 排版缓存目录 + 单线程写入池；启动时清理上次残留的 .tmp 半文件
        layoutCacheDir = new java.io.File(activity.getCacheDir(), "reader_layout");
        if (!layoutCacheDir.exists()) layoutCacheDir.mkdirs();
        if (layoutCacheDir.exists()) {
            java.io.File[] tmpFiles = layoutCacheDir.listFiles((d, n) -> n.endsWith(".tmp"));
            if (tmpFiles != null) for (java.io.File t : tmpFiles) t.delete();
        }
        layoutExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    }

    String computeLayoutKey(int chIndex, String content) {
        long bid = activity.safeBookId();
        String bookPart;
        if (bid > 0) {
            bookPart = "L" + bid;
        } else {
            String st = activity.currentBook != null && activity.currentBook.getSourceType() != null
                    ? activity.currentBook.getSourceType() : "";
            String su = activity.currentBook != null && activity.currentBook.getSourceUrl() != null
                    ? activity.currentBook.getSourceUrl() : "";
            bookPart = "E" + Integer.toHexString((st + "|" + su).hashCode());
        }
        int w = (activity.webView != null) ? activity.webView.getWidth() : 0;
        int h = (activity.webView != null) ? activity.webView.getHeight() : 0;
        int fh = (activity.currentFontFamily != null) ? activity.currentFontFamily.hashCode() : 0;
        int chc = (content != null) ? content.hashCode() : 0;
        // 间距参数参与签名：行距/段距/左右/上下任一变动都会自然错开缓存 → 自动失效并重排
        // （padTB=-1 表示默认公式，与显式值天然不同串，不会误命中）
        return bookPart + "_" + chIndex + "_" + ((int) activity.currentFontSize) + "_" + w + "_" + h
                + "_" + (activity.showHeaderFooter ? 1 : 0) + "_" + fh + "_" + chc
                + "_" + Math.round(activity.lineSpacingRatio * 10) + "_" + Math.round(activity.paraGapRatio * 100)
                + "_" + activity.padLR + "_" + activity.padTB;
    }

    String readLayoutCache(String key) {
        try {
            java.io.File f = new java.io.File(layoutCacheDir, sha256hex(key) + ".json");
            if (!f.exists() || f.length() > 1536 * 1024) return null; // 超大文件不读，避免主线程卡顿
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            android.util.Log.w("ReadLayoutCache", "readLayoutCache 失败: " + e.getMessage());
            return null;
        }
    }

    /** Activity 销毁时调用：优雅关闭写入池（已提交任务会跑完）。 */
    void shutdown() {
        if (layoutExecutor != null) {
            try { layoutExecutor.shutdown(); } catch (Throwable ignored) {}
        }
    }

    void writeLayoutCache(String key, String json) {
        if (layoutExecutor == null) return;
        layoutExecutor.execute(() -> {
            try {
                String name = sha256hex(key) + ".json";
                java.io.File tmp = new java.io.File(layoutCacheDir, name + ".tmp");
                java.io.File dst = new java.io.File(layoutCacheDir, name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                fos.write(json.getBytes("UTF-8"));
                fos.close();
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) { // 极少数 rename 失败：直接原地写兜底
                    java.io.FileOutputStream fos2 = new java.io.FileOutputStream(dst);
                    fos2.write(json.getBytes("UTF-8"));
                    fos2.close();
                    tmp.delete();
                }
                trimLayoutCache();
            } catch (Exception e) {
                android.util.Log.w("ReadLayoutCache", "writeLayoutCache 失败: " + e.getMessage());
            }
        });
    }

    private void trimLayoutCache() {
        try {
            java.io.File[] files = layoutCacheDir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null || files.length <= LAYOUT_CACHE_MAX_FILES) return;
            java.util.Arrays.sort(files, (a, b) -> java.lang.Long.compare(a.lastModified(), b.lastModified()));
            int remove = files.length - LAYOUT_CACHE_MAX_FILES;
            for (int i = 0; i < remove; i++) files[i].delete();
        } catch (Exception e) { /* ignore */ }
    }

    private static String sha256hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "k" + Integer.toHexString(s.hashCode());
        }
    }
}
