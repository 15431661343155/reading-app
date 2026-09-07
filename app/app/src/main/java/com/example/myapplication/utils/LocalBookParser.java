package com.example.myapplication.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;  // ✅ 新增：用于调试日志

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.File;
import java.io.FileOutputStream;


/**
 * 本地书解析器 - 支持 TXT 和 EPUB
 */
public class LocalBookParser {
    
    // ✅ 性能优化：预编译正则表达式，减少重复编译开销
    private static final Pattern CHAPTER_MARKER_PATTERN = Pattern.compile(
        "(?i)^第[\\d零一二三四五六七八九十百千万]+章"
    );
    private static final Pattern VOLUME_MARKER_PATTERN = Pattern.compile(
        "(?i)^卷[\\d零一二三四五六七八九十百千万]+"
    );
    private static final Pattern ENGLISH_CHAPTER_PATTERN = Pattern.compile(
        "(?i)^chapter\\s+[\\d]+\\s*[-–]?\\s*[\\d]*"
    );
    private static final Pattern COVER_PATTERN = Pattern.compile(
        "(?i)^cover$|^封面$|^目录$"
    );

    public static class Chapter {
        public String title;
        public String content;
        public int index;

        public Chapter(int index, String title, String content) {
            this.index = index;
            this.title = title;
            this.content = content;
        }
    }

    public static class BookInfo {
        public String title;
        public String author;
        public String cover;
        public String coverPath;
        public List<Chapter> chapters = new ArrayList<>();
    }

    /**
     * 解析书籍（自动判断格式）
     */
    public static BookInfo parse(Context context, Uri uri, String fileName,long bookId) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".epub")) {
            return parseEpub(context, uri, bookId);
        } else {
            return parseTxt(context, uri, fileName);
        }
    }

    // ==================== TXT 解析 ====================

    private static BookInfo parseTxt(Context context, Uri uri, String fileName) {
        BookInfo info = new BookInfo();
        info.title = fileName.replace(".txt", "").replace(".epub", "");
        info.author = "未知作者";

        String[] patterns = {
                "^(第[\\d零一二三四五六七八九十百千万]+[卷集册部篇])\\s*(第[\\d零一二三四五六七八九十百千万]+[章节回]).*",
                "^(第[\\d零一二三四五六七八九十百千万]+[章节回])\\s*(第[\\d零一二三四五六七八九十百千万]+[节]).*",
                "^(第[\\d零一二三四五六七八九十百千万]+[卷集册部篇]).*",
                "^(第[\\d零一二三四五六七八九十百千万]+[章节回]).*",
                "^(序言|前言|楔子|引子|尾声|后记|番外|附录).*",
                "^(\\d+[\\.、\\s]+).*",
        };
        Pattern chapterPattern = Pattern.compile(String.join("|", patterns));

        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return info;
            byte[] rawData = readAllBytes(is);
            is.close();
            String charset = detectCharset(rawData);
            String text = new String(rawData, charset);
            if (text.contains("\uFFFD")) {
                try { text = new String(rawData, "GBK"); } catch (Exception ignored) {}
            }
            String[] lines = text.split("\n");
            List<Integer> chapterStarts = new ArrayList<>();
            List<String> chapterTitles = new ArrayList<>();

            // 辅助函数：规范化标题（去除Markdown符号、标点、空格，转小写）
            java.util.function.Function<String, String> normalize = (raw) -> {
                // 移除行首的 #、*、- 等Markdown标题符号，以及可能的空格
                String cleaned = raw.replaceFirst("^#+\\s*", "").replaceFirst("^[*\\-]+\\s*", "").trim();
                // 去除标点符号和空格，转为小写
                return cleaned.replaceAll("\\p{Punct}", "").replaceAll("\\s+", "").toLowerCase();
            };

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.length() > 100) continue; // 标题通常不会太长

                Matcher matcher = chapterPattern.matcher(trimmed);
                if (matcher.find()) {
                    // 提取原始标题
                    String rawTitle = trimmed;
                    // 如果原始标题以 # 开头，则去除 # 和空格
                    String title = rawTitle.replaceFirst("^#+\\s*", "").trim();

                    // 检查是否与上一个章节标题重复（规范化后比较）
                    boolean isDuplicate = false;
                    if (!chapterTitles.isEmpty()) {
                        String lastTitle = chapterTitles.get(chapterTitles.size() - 1);
                        String lastNorm = normalize.apply(lastTitle);
                        String currentNorm = normalize.apply(title);
                        if (lastNorm.equals(currentNorm)) {
                            isDuplicate = true;
                        } else {
                            // 如果编辑距离很小，也视为重复
                            int distance = levenshteinDistance(lastNorm, currentNorm);
                            if (distance <= 2) {
                                isDuplicate = true;
                            }
                        }
                    }
                    if (!isDuplicate) {
                        chapterStarts.add(i);
                        chapterTitles.add(title);
                    }
                }
            }

            if (chapterStarts.isEmpty()) {
                info.chapters.add(new Chapter(0, "第一章", text));
                return info;
            }

            for (int i = 0; i < chapterStarts.size(); i++) {
                int start = chapterStarts.get(i) + 1;
                int end = (i + 1 < chapterStarts.size()) ? chapterStarts.get(i + 1) : lines.length;
                StringBuilder sb = new StringBuilder();
                for (int j = start; j < end; j++) {
                    // 跳过可能重复的标题行（如果当前行与章节标题相同，且不是新的章节起始）
                    String line = lines[j].trim();
                    if (line.isEmpty()) continue;
                    // 如果当前行匹配章节模式但不是下一个章节起始，仍保留内容（但不要重复添加标题）
                    // 简单做法：保留所有行，不过滤
                    sb.append(lines[j]).append("\n");
                }
                info.chapters.add(new Chapter(i, chapterTitles.get(i), sb.toString()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (info.chapters.isEmpty()) {
            info.chapters.add(new Chapter(0, "第一章", "无法解析文件内容"));
        }
        return info;
    }

    // 添加 Levenshtein 距离辅助方法
    private static int levenshteinDistance(String s, String t) {
        if (s == null || t == null) return 0;
        int m = s.length();
        int n = t.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) dp[i][0] = i;
        for (int j = 0; j <= n; j++) dp[0][j] = j;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = (s.charAt(i - 1) == t.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1), dp[i-1][j-1] + cost);
            }
        }
        return dp[m][n];
    }

    // EPUB 解析
    private static BookInfo parseEpub(Context context, Uri uri, long bookId) {
        BookInfo info = new BookInfo();
        info.title = "未命名";
        info.author = "未知作者";

        try {
            // 使用 ZipFile 而不是 ZipInputStream，避免 mimetype STORE 模式导致的读取问题
            // 首先将 Uri 转换为 File 路径
            java.io.File epubFile = null;
            String scheme = uri.getScheme();
            
            if ("content".equals(scheme)) {
                // Content URI，需要复制到临时文件
                android.util.Log.d("LocalBookParser", "Content URI detected, copying to temp file...");
                epubFile = new java.io.File(context.getCacheDir(), "temp_epub_" + System.currentTimeMillis() + ".epub");
                java.io.InputStream is = context.getContentResolver().openInputStream(uri);
                if (is == null) return info;
                
                java.io.FileOutputStream fos = new java.io.FileOutputStream(epubFile);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                is.close();
                android.util.Log.d("LocalBookParser", "Temp file created: " + epubFile.getAbsolutePath());
            } else if ("file".equals(scheme)) {
                // File URI，直接使用
                epubFile = new java.io.File(uri.getPath());
            }
            
            if (epubFile == null || !epubFile.exists()) {
                android.util.Log.e("LocalBookParser", "Failed to create or find EPUB file!");
                info.chapters.add(new Chapter(0, "第一章", "无法访问EPUB文件"));
                return info;
            }
            
            // ✅ 使用 ZipFile 读取所有条目
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(epubFile);
            Map<String, byte[]> zipEntries = new HashMap<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    java.io.InputStream entryStream = zipFile.getInputStream(entry);
                    byte[] data = readAllBytes(entryStream);
                    entryStream.close();
                    zipEntries.put(entry.getName(), data);
                }
            }
            zipFile.close();
            
            // 如果是临时文件，删除它
            if ("content".equals(scheme) && epubFile != null && epubFile.exists()) {
                epubFile.delete();
            }
            
            android.util.Log.d("LocalBookParser", "Successfully loaded " + zipEntries.size() + " ZIP entries using ZipFile");

            // 解析 container.xml 找到根文件
            String rootfilePath = null;
            byte[] containerData = zipEntries.get("META-INF/container.xml");
            
            // ✅ 调试：列出所有ZIP条目，方便排查
            Log.d("LocalBookParser", "EPUB ZIP entries count: " + zipEntries.size());
            
            // ✅ 新增：检查ZIP条目数量，如果太少说明文件损坏
            if (zipEntries.size() < 3) {
                Log.e("LocalBookParser", "EPUB file is too small or corrupted! Only " + zipEntries.size() + " entries found.");
                info.chapters.add(new Chapter(0, "第一章", 
                    "EPUB文件损坏或不完整（仅找到" + zipEntries.size() + "个文件）。\n" +
                    "请重新下载或选择其他EPUB文件。"));
                return info;
            }
            
            if (containerData == null) {
                Log.e("LocalBookParser", "META-INF/container.xml NOT FOUND!");
                // 打印前10个条目帮助调试
                int count = 0;
                for (String key : zipEntries.keySet()) {
                    if (count++ < 10) {
                        Log.d("LocalBookParser", "  Entry: " + key);
                    }
                }
            } else {
                Log.d("LocalBookParser", "Found META-INF/container.xml, size: " + containerData.length);
                String containerXml = new String(containerData, "UTF-8");
                Log.d("LocalBookParser", "Container XML content:\n" + containerXml);
                
                // ✅ 方法1: 标准 full-path 属性匹配
                Matcher m = Pattern.compile("full-path=\"([^\"]+)\"").matcher(containerXml);
                if (m.find()) {
                    rootfilePath = m.group(1);
                    Log.d("LocalBookParser", "Method 1 found rootfile: " + rootfilePath);
                }
                
                // ✅ 方法2: 尝试带命名空间的匹配（如 xmlns:opf="..."）
                if (rootfilePath == null) {
                    Matcher nsM = Pattern.compile("(?:opf:)?full-path=\"([^\"]+)\"").matcher(containerXml);
                    if (nsM.find()) {
                        rootfilePath = nsM.group(1);
                        Log.d("LocalBookParser", "Method 2 (namespace) found rootfile: " + rootfilePath);
                    }
                }
                
                // ✅ 方法3: 尝试单引号
                if (rootfilePath == null) {
                    Matcher singleQuoteM = Pattern.compile("full-path='([^']+)\'").matcher(containerXml);
                    if (singleQuoteM.find()) {
                        rootfilePath = singleQuoteM.group(1);
                        Log.d("LocalBookParser", "Method 3 (single quote) found rootfile: " + rootfilePath);
                    }
                }
                
                // ✅ 方法4: 查找常见的OPF文件名模式
                if (rootfilePath == null) {
                    String[] commonOpfNames = {"content.opf", "package.opf", "book.opf", "index.opf"};
                    for (String opfName : commonOpfNames) {
                        if (zipEntries.containsKey(opfName)) {
                            rootfilePath = opfName;
                            Log.d("LocalBookParser", "Method 4 found rootfile in root: " + rootfilePath);
                            break;
                        }
                        // 尝试在子目录中查找
                        for (Map.Entry<String, byte[]> zipEntry2 : zipEntries.entrySet()) {
                            if (zipEntry2.getKey().endsWith("/" + opfName) || zipEntry2.getKey().equals(opfName)) {
                                rootfilePath = zipEntry2.getKey();
                                Log.d("LocalBookParser", "Method 4 found rootfile in subdirectory: " + rootfilePath);
                                break;
                            }
                        }
                        if (rootfilePath != null) break;
                    }
                }
                
                // ✅ 方法5: 查找任何 .opf 文件
                if (rootfilePath == null) {
                    for (Map.Entry<String, byte[]> zipEntry : zipEntries.entrySet()) {
                        if (zipEntry.getKey().toLowerCase().endsWith(".opf")) {
                            rootfilePath = zipEntry.getKey();
                            Log.d("LocalBookParser", "Method 5 found any .opf file: " + rootfilePath);
                            break;
                        }
                    }
                }
            }
            
            if (rootfilePath == null) {
                Log.e("LocalBookParser", "All methods failed to find rootfile!");
                info.chapters.add(new Chapter(0, "第一章", "无法解析EPUB: 找不到根文件(OPF)。请检查EPUB文件是否完整。"));
                return info;
            }
            
            Log.d("LocalBookParser", "Successfully found rootfile: " + rootfilePath);

            // 获取根文件内容 (content.opf)
            byte[] opfData = zipEntries.get(rootfilePath);
            if (opfData == null) {
                info.chapters.add(new Chapter(0, "第一章", "无法解析EPUB: 找不到OPF文件"));
                return info;
            }
            
            // 尝试多种编码解析OPF文件
            String opfXml = null;
            try {
                opfXml = new String(opfData, "UTF-8");
            } catch (Exception e) {
                try {
                    opfXml = new String(opfData, "GBK");
                } catch (Exception ex) {
                    opfXml = new String(opfData); // 默认编码
                }
            }

            // 提取书名、作者 - 更全面的匹配策略
            Matcher titleM = Pattern.compile("<dc:title[^>]*>([^<]+)</dc:title>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
            if (titleM.find()) {
                info.title = cleanHtmlText(titleM.group(1)).trim();
            }
            
            // 尝试其他可能的书名标签
            if (info.title.equals("未命名")) {
                Matcher altTitleM = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (altTitleM.find()) {
                    info.title = cleanHtmlText(altTitleM.group(1)).trim();
                }
            }
            
            Matcher authorM = Pattern.compile("<dc:creator[^>]*>([^<]+)</dc:creator>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
            if (authorM.find()) {
                info.author = cleanHtmlText(authorM.group(1)).trim();
            }
            
            // 尝试其他可能的作者标签
            if (info.author.equals("未知作者")) {
                Matcher altAuthorM = Pattern.compile("<meta[^>]*name=['\"](?:author|creator)['\"][^>]*content=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (altAuthorM.find()) {
                    info.author = cleanHtmlText(altAuthorM.group(1)).trim();
                }
            }

            // ========== 封面提取 ==========
            String coverHref = null;
            String coverMediaType = null;
            
            // 方法1: EPUB3标准 - 查找 properties="cover-image" 的 item
            Matcher coverItemMatcher = Pattern.compile("<item[^>]+id=\"([^\"]+)\"[^>]+href=\"([^\"]+)\"[^>]*properties=\"cover-image\"[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
            if (coverItemMatcher.find()) {
                coverHref = coverItemMatcher.group(2);
                String itemTag = coverItemMatcher.group(0);
                Matcher mediaMatcher = Pattern.compile("media-type=\"([^\"]+)\"").matcher(itemTag);
                if (mediaMatcher.find()) coverMediaType = mediaMatcher.group(1);
            }
            
            // 方法2: 查找 id="cover" 或 id="cover-image" 的 item
            if (coverHref == null) {
                Matcher coverIdMatcher = Pattern.compile("<item[^>]+id=\"(cover|cover-image|Cover)\"[^>]+href=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (coverIdMatcher.find()) {
                    coverHref = coverIdMatcher.group(2);
                    String itemTag = coverIdMatcher.group(0);
                    Matcher mediaMatcher = Pattern.compile("media-type=\"([^\"]+)\"").matcher(itemTag);
                    if (mediaMatcher.find()) coverMediaType = mediaMatcher.group(1);
                }
            }
            
            // 方法3: 从 guide 中查找 type="cover" 或 type="other.ms-coverimage"
            if (coverHref == null) {
                Matcher guideMatcher = Pattern.compile("<reference[^>]+type=\"(?:cover|other\\.ms-coverimage)\"[^>]+href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (guideMatcher.find()) {
                    coverHref = guideMatcher.group(1);
                } else {
                    // 尝试另一种顺序
                    Matcher guideMatcher2 = Pattern.compile("<reference[^>]+href=\"([^\"]+)\"[^>]+type=\"(?:cover|other\\.ms-coverimage)\"", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                    if (guideMatcher2.find()) {
                        coverHref = guideMatcher2.group(1);
                    }
                }
            }
            
            // 方法4: 从 meta 标签查找封面ID
            if (coverHref == null) {
                Matcher metaCoverMatcher = Pattern.compile("<meta[^>]+name=\"cover\"[^>]+content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (metaCoverMatcher.find()) {
                    String coverId = metaCoverMatcher.group(1);
                    // 根据ID查找对应的href
                    Matcher itemByIdMatcher = Pattern.compile("<item[^>]+id=\"" + Pattern.quote(coverId) + "\"[^>]+href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                    if (itemByIdMatcher.find()) {
                        coverHref = itemByIdMatcher.group(1);
                        // 尝试获取media-type
                        String itemTag = itemByIdMatcher.group(0);
                        Matcher mediaMatcher = Pattern.compile("media-type=\"([^\"]+)\"").matcher(itemTag);
                        if (mediaMatcher.find()) coverMediaType = mediaMatcher.group(1);
                    }
                }
            }
            
            // 方法5: 查找常见的封面文件名模式
            if (coverHref == null) {
                String[] commonCoverNames = {"cover.jpg", "cover.jpeg", "cover.png", "cover.gif",
                                              "Cover.jpg", "Cover.jpeg", "Cover.png",
                                              "images/cover.jpg", "images/cover.png",
                                              "Images/cover.jpg", "Images/cover.png"};
                for (String coverName : commonCoverNames) {
                    if (zipEntries.containsKey(coverName)) {
                        coverHref = coverName;
                        // 根据扩展名推断media-type
                        if (coverName.toLowerCase().endsWith(".jpg") || coverName.toLowerCase().endsWith(".jpeg")) {
                            coverMediaType = "image/jpeg";
                        } else if (coverName.toLowerCase().endsWith(".png")) {
                            coverMediaType = "image/png";
                        } else if (coverName.toLowerCase().endsWith(".gif")) {
                            coverMediaType = "image/gif";
                        }
                        break;
                    }
                }
            }

            // 保存封面图片
            if (coverHref != null) {
                try {
                    // 处理相对路径
                    String basePath = "";
                    int lastSlash = rootfilePath.lastIndexOf('/');
                    if (lastSlash != -1) {
                        basePath = rootfilePath.substring(0, lastSlash + 1);
                    }
                    
                    String fullCoverPath = basePath + coverHref;
                    // 规范化路径
                    while (fullCoverPath.contains("../")) {
                        fullCoverPath = fullCoverPath.replaceFirst("[^/]+/\\.\\./", "");
                    }
                    // 移除开头的斜杠
                    if (fullCoverPath.startsWith("/")) {
                        fullCoverPath = fullCoverPath.substring(1);
                    }
                    
                    // URL解码
                    try {
                        fullCoverPath = java.net.URLDecoder.decode(fullCoverPath, "UTF-8");
                    } catch (Exception e) {
                        // 忽略解码错误
                    }
                    
                    byte[] coverData = zipEntries.get(fullCoverPath);
                    if (coverData == null) {
                        // 尝试原始路径
                        coverData = zipEntries.get(coverHref);
                    }
                    if (coverData == null) {
                        // 尝试不带basePath的路径
                        String altPath = coverHref;
                        while (altPath.contains("../")) {
                            altPath = altPath.replaceFirst("[^/]+/\\.\\./", "");
                        }
                        if (altPath.startsWith("/")) {
                            altPath = altPath.substring(1);
                        }
                        coverData = zipEntries.get(altPath);
                    }
                    
                    if (coverData != null && coverData.length > 0) {
                        // 确定扩展名
                        String ext = getFileExtension(coverHref, coverMediaType);
                        String coverFileName = "cover_" + bookId + ext;
                        File coverFile = new File(context.getFilesDir(), coverFileName);
                        
                        // 删除旧封面（如果存在）
                        deleteOldCover(context, bookId);
                        
                        // 保存新封面
                        FileOutputStream fos = new FileOutputStream(coverFile);
                        fos.write(coverData);
                        fos.close();
                        
                        info.coverPath = coverFile.getAbsolutePath();
                        info.cover = coverFile.getAbsolutePath();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // ========== 封面提取结束 ==========

            // 解析 spine 顺序 - 更健壮的解析方式
            List<String> idrefList = new ArrayList<>();
            
            // 首先尝试从NCX文件获取目录结构（EPUB2）
            Map<String, String> ncxTitleMap = new HashMap<>();
            byte[] ncxData = null;
            for (Map.Entry<String, byte[]> zipEntry : zipEntries.entrySet()) {
                if (zipEntry.getKey().endsWith(".ncx")) {
                    ncxData = zipEntry.getValue();
                    break;
                }
            }
            
            if (ncxData != null) {
                try {
                    String ncxXml = new String(ncxData, "UTF-8");
                    // 提取navPoint中的标题和源引用
                    Matcher navPointM = Pattern.compile("<navPoint[^>]*>(.*?)</navPoint>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(ncxXml);
                    while (navPointM.find()) {
                        String navPoint = navPointM.group(1);
                        Matcher textM = Pattern.compile("<text[^>]*>([^<]+)</text>", Pattern.CASE_INSENSITIVE).matcher(navPoint);
                        Matcher srcM = Pattern.compile("<content[^>]*src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(navPoint);
                        if (textM.find() && srcM.find()) {
                            String title = cleanHtmlText(textM.group(1)).trim();
                            String src = srcM.group(1);
                            // 提取文件名部分作为key
                            String fileName = src.substring(src.lastIndexOf('/') + 1);
                            ncxTitleMap.put(fileName, title);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
            // 尝试从EPUB3导航文档获取目录
            for (Map.Entry<String, byte[]> zipEntry : zipEntries.entrySet()) {
                String entryName = zipEntry.getKey();
                if (entryName.endsWith(".xhtml") || entryName.endsWith(".html")) {
                    // 检查是否是导航文档
                    byte[] navData = zipEntry.getValue();
                    try {
                        String navHtml = new String(navData, "UTF-8");
                        if (navHtml.contains("epub:type=\"toc\"") || navHtml.contains("class=\"toc\"")) {
                            // 简单的TOC解析
                            Matcher navM = Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE).matcher(navHtml);
                            while (navM.find()) {
                                String href = navM.group(1);
                                String title = cleanHtmlText(navM.group(2)).trim();
                                String fileName = href.substring(href.lastIndexOf('/') + 1);
                                if (!ncxTitleMap.containsKey(fileName)) {
                                    ncxTitleMap.put(fileName, title);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // 忽略解析错误
                    }
                }
            }
            
            // 方法1: 标准spine解析
            Matcher spineM = Pattern.compile("<itemref[^>]*idref=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(opfXml);
            while (spineM.find()) {
                idrefList.add(spineM.group(1));
            }
            
            // 如果spine为空，尝试从manifest中获取所有项目作为备选
            if (idrefList.isEmpty()) {
                Matcher manifestM = Pattern.compile("<item[^>]+id=\"([^\"]+)\"[^>]+href=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                while (manifestM.find()) {
                    String id = manifestM.group(1);
                    String href = manifestM.group(2);
                    // 只添加HTML/XHTML文件
                    if (href.toLowerCase().matches(".*\\.(x?html?|xml)$")) {
                        idrefList.add(id);
                    }
                }
            }

            Map<String, String> idToHref = new HashMap<>();
            Matcher itemM = Pattern.compile("<item[^>]+id=\"([^\"]+)\"[^>]+href=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
            while (itemM.find()) {
                String id = itemM.group(1);
                String href = itemM.group(2);
                // 解码URL编码的href
                try {
                    href = java.net.URLDecoder.decode(href, "UTF-8");
                } catch (Exception e) {
                    // 如果解码失败，使用原始href
                }
                idToHref.put(id, href);
            }

            String basePath = "";
            int lastSlash = rootfilePath.lastIndexOf('/');
            if (lastSlash != -1) {
                basePath = rootfilePath.substring(0, lastSlash + 1);
            }

            int chapterIndex = 0;
            for (String idref : idrefList) {
                String href = idToHref.get(idref);
                if (href == null) continue;
                
                // 处理相对路径
                String fullPath = basePath + href;
                // 规范化路径
                while (fullPath.contains("../")) {
                    fullPath = fullPath.replaceFirst("[^/]+/\\.\\./", "");
                }
                // 移除开头的斜杠
                if (fullPath.startsWith("/")) {
                    fullPath = fullPath.substring(1);
                }
                
                byte[] chapterData = zipEntries.get(fullPath);
                if (chapterData == null) {
                    // 尝试不带basePath的路径
                    chapterData = zipEntries.get(href);
                }
                if (chapterData == null) {
                    // 尝试URL解码后的路径
                    try {
                        String decodedPath = java.net.URLDecoder.decode(fullPath, "UTF-8");
                        chapterData = zipEntries.get(decodedPath);
                    } catch (Exception e) {
                        // 忽略解码错误
                    }
                }
                if (chapterData == null) continue;

                String html = null;
                try {
                    html = new String(chapterData, "UTF-8");
                } catch (Exception e) {
                    try {
                        html = new String(chapterData, "GBK");
                    } catch (Exception ex) {
                        html = new String(chapterData); // 默认编码
                    }
                }
                
                String fileName = href.substring(href.lastIndexOf('/') + 1);
                
                // ============================================
                // ✅ 新增第0步：先过滤前置文件（封面/版权/说明等）
                // 从文件名就能排除的非正文章节，跳过标题提取节省时间
                // ============================================
                if (shouldSkipByFileName(fileName)) {
                    Log.d("LocalBookParser", "[EPUB] SKIP by fileName: " + fileName);
                    continue;
                }
                
                String chapterTitle = null;
                
                // ---- 来源1: NCX 目录（最可靠，是 EPUB 生成工具直接写入的）----
                String ncxTitle = ncxTitleMap.get(fileName);
                
                // ---- 来源2: HTML h1-h6 标签 ----
                String headingTitle = null;
                Matcher headingMatcher = Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
                if (headingMatcher.find()) {
                    headingTitle = cleanHtmlText(headingMatcher.group(2)).trim();
                }
                
                // ---- 来源3: HTML title 标签 ----
                String titleTagTitle = null;
                Matcher titleMatcher = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
                if (titleMatcher.find()) {
                    titleTagTitle = titleMatcher.group(1).trim();
                }
                
                // ============================================
                // ✅ 选择最佳标题：优先选"包含章节号且非通用词"的那个
                // ============================================
                
                // 定义"低质量"标题：EPUB 生成工具自动填充的通用占位词
                java.util.Set<String> genericTitles = new java.util.HashSet<>(java.util.Arrays.asList(
                    "chapter", "章节", "章", "part", "部分", "section", "节",
                    "prologue", "epilogue", "preface", "前言", "序言", "楔子",
                    "cover", "封面", "copyright", "版权"
                ));
                
                // 判断标题是否"高质量"（包含章节号前缀 或 非通用词且足够长）
                java.util.regex.Pattern hasChapterNum = java.util.regex.Pattern.compile("第[\\d一二三四五六七八九十百千万零〇两]+[章卷回部集篇回]|chapter\\s*\\d+", java.util.regex.Pattern.CASE_INSENSITIVE);
                
                java.util.function.Function<String, Integer> qualityScore = (t) -> {
                    if (t == null || t.isEmpty()) return -1;
                    int score = 0;
                    String lower = t.toLowerCase().trim();
                    if (hasChapterNum.matcher(t).find()) score += 10;  // 有章节号前缀 +10（最重要！）
                    if (genericTitles.contains(lower)) score -= 20;     // 通用占位词 -20
                    if (t.length() >= 6) score += 2;                    // 有实质内容 +2
                    if (t.length() >= 12) score += 3;                   // 更长 +3
                    return score;
                };
                
                // 选分数最高的
                int bestScore = -1;
                String bestTitle = null;
                // 依次尝试每个来源
                for (String candidate : new String[]{ncxTitle, headingTitle, titleTagTitle}) {
                    int s = qualityScore.apply(candidate);
                    if (s > bestScore) {
                        bestScore = s;
                        bestTitle = candidate;
                    }
                }
                
                chapterTitle = (bestTitle != null && bestScore >= 0) ? bestTitle : null;
                
                // 如果 NCX 标题短（只有"开局师父没了"）而 heading 标题完整（"第一章 开局师父没了"），
                // 用 heading 覆盖 NCX（qualityScore 会自动处理）
                
                // 兜底：默认标题
                if (chapterTitle == null || chapterTitle.isEmpty()) {
                    chapterTitle = "第" + (chapterIndex + 1) + "章";
                }
                
                Log.d("LocalBookParser", "[EPUB] fileName=" + fileName + 
                    " ncx=\"" + ncxTitle + "\" heading=\"" + headingTitle + "\" titleTag=\"" + titleTagTitle + "\"" +
                    " => FINAL=\"" + chapterTitle + "\"");
                
                // ✅ 过滤非正文章节（封面、目录、简介、版权信息等）——标题兜底过滤
                if (shouldSkipChapter(chapterTitle, html)) {
                    Log.d("LocalBookParser", "Skipping non-content chapter: " + chapterTitle);
                    continue;
                }

                // 改进的内容清理逻辑
                String content = html
                        // 移除脚本和样式
                        .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                        .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                        // 移除注释
                        .replaceAll("(?s)<!--.*?-->", "")
                        // 移除EPUB特定的元数据标签
                        .replaceAll("(?is)<meta[^>]>", "")
                        .replaceAll("(?is)<link[^>]>", "")
                        // ✅ 新增：移除标题标签及其内容（避免与页眉重复）
                        .replaceAll("(?i)<h[1-6][^>]*>.*?</h[1-6]>", "\n")
                        // 处理换行和段落
                        .replaceAll("(?i)<br\\s*/?>", "\n")
                        .replaceAll("(?i)</p>", "\n\n")
                        .replaceAll("(?i)</div>", "\n")
                        .replaceAll("(?i)</li>", "\n")
                        // 移除所有HTML标签
                        .replaceAll("<[^>]+>", "")
                        // 处理HTML实体
                        .replaceAll("&nbsp;", " ")
                        .replaceAll("&lt;", "<")
                        .replaceAll("&gt;", ">")
                        .replaceAll("&amp;", "&")
                        .replaceAll("&quot;", "\"")
                        .replaceAll("&#39;", "'")
                        .replaceAll("&apos;", "'")
                        .replaceAll("&#x27;", "'")
                        .replaceAll("&#x2018;", "'")
                        .replaceAll("&#x2019;", "'")
                        .replaceAll("&#x201C;", "\"")
                        .replaceAll("&#x201D;", "\"")
                        // 清理多余空白
                        .replaceAll("\\n{3,}", "\n\n")
                        .replaceAll("^[\\s\\n]+", "")  // 去除开头空白
                        .replaceAll("[\\s\\n]+$", "")  // 去除结尾空白
                        .replaceAll("\\r\\n", "\n")    // 统一换行符
                        // 移除通用独立标记行（固定清理，不涉及 chapterTitle）
                        .replaceAll("(?m)^Cover\\s*$", "")
                        .replaceAll("(?m)^封面\\s*$", "")
                        .replaceAll("(?m)^目录\\s*$", "")
                        // 清理因删除产生的空行
                        .replaceAll("\\n{3,}", "\n\n")
                        .trim();
                                
                // ✅ 移除内容开头的重复标题行/标题前缀
                // 策略：只在前 5 行检查。独立标题行 → 整行删除；标题+正文混在一起的行 → 只去掉开头的标题部分
                if (chapterTitle != null && !chapterTitle.isEmpty()) {
                    // 归一化标题（去掉空白，便于匹配）
                    String normalizedTitle = chapterTitle.toLowerCase().replaceAll("\\s+", "");
                    
                    // 构造组合模式：可选的"第X章/卷X/chapter X"前缀 + 章节标题内容
                    // 例如 chapterTitle="开局师父没了" → 能匹配 "第一章 开局师父没了"
                    //     chapterTitle="第一章 开局师父没了" → 能匹配 "第一章 开局师父没了" 本身
                    //     chapterTitle="第一章 开局师父没了" → 也能匹配纯"开局师父没了"
                    String titleWithoutPrefix = chapterTitle.replaceFirst("^第[一二三四五六七八九十百千万0-9]+章\\s*", "")
                                                            .replaceFirst("^第[一二三四五六七八九十百千万0-9]+卷\\s*", "")
                                                            .replaceFirst("(?i)^chapter\\s*[0-9]+[^\\u4e00-\\u9fa5]*\\s*", "")
                                                            .trim();
                    String normalizedTitleNoPrefix = titleWithoutPrefix.toLowerCase().replaceAll("\\s+", "");
                    
                    String[] lines = content.split("\n");
                    java.util.List<String> filteredLines = new java.util.ArrayList<>();
                    int maxTitleCheckLines = 5;
                    int checkedLines = 0;
                    boolean foundFirstContent = false;
                                    
                    for (String line : lines) {
                        if (foundFirstContent || checkedLines >= maxTitleCheckLines) {
                            filteredLines.add(line);
                            continue;
                        }
                        
                        String trimmedLine = line.trim();
                        
                        // 空行保留（不算有效内容，但也不跳过 checkedLines）
                        if (trimmedLine.isEmpty()) {
                            filteredLines.add(line);
                            checkedLines++;
                            continue;
                        }
                        checkedLines++;
                        
                        // === 判断这行是否是标题相关 ===
                        String normalizedLine = trimmedLine.toLowerCase().replaceAll("\\s+", "");
                        
                        // A. 独立"第X章"/"卷X"/"chapter X"标记行 → 整行删除
                        if ((CHAPTER_MARKER_PATTERN.matcher(trimmedLine).matches()
                                || VOLUME_MARKER_PATTERN.matcher(trimmedLine).matches()
                                || ENGLISH_CHAPTER_PATTERN.matcher(trimmedLine).matches()
                                || COVER_PATTERN.matcher(trimmedLine).matches())
                                && trimmedLine.length() <= 30) {   // 确保是独立短行
                            Log.d("LocalBookParser", "Removing standalone chapter marker line: " + trimmedLine);
                            continue;  // 删除整行
                        }
                        
                        // B. 独立一行等于 chapterTitle（含归一化后完全相等）→ 整行删除
                        if (normalizedLine.equals(normalizedTitle)
                                || normalizedLine.equals(normalizedTitleNoPrefix)) {
                            Log.d("LocalBookParser", "Removing exact title line: " + trimmedLine);
                            continue;  // 删除整行
                        }
                        
                        // C. 行**开头**是 chapterTitle 或 chapterTitleNoPrefix → 去掉开头的标题部分，保留正文
                        //    例："第一章 开局师父没了 武周。..." → 去掉"第一章 开局师父没了" → 剩余 "武周。..."
                        int cutIdx = -1;
                        // 尝试用完整标题匹配开头
                        if (trimmedLine.toLowerCase().startsWith(chapterTitle.toLowerCase())) {
                            cutIdx = chapterTitle.length();
                        }
                        // 尝试用去掉"第X章"前缀后的标题匹配开头
                        if (cutIdx < 0 && !titleWithoutPrefix.isEmpty()
                                && trimmedLine.toLowerCase().startsWith(titleWithoutPrefix.toLowerCase())) {
                            cutIdx = titleWithoutPrefix.length();
                        }
                        // 尝试匹配 "第X章" + 标题内容 的组合（当 chapterTitle 不含章节号时）
                        if (cutIdx < 0) {
                            java.util.regex.Matcher combinedPrefixM = java.util.regex.Pattern.compile(
                                    "^\\s*(?:第[一二三四五六七八九十百千万0-9]+[章卷回部集篇]|chapter\\s*[0-9]+)\\s*" + java.util.regex.Pattern.quote(titleWithoutPrefix),
                                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(trimmedLine);
                            if (combinedPrefixM.find()) {
                                cutIdx = combinedPrefixM.end();
                            }
                        }
                        
                        if (cutIdx >= 0) {
                            // 去掉标题部分，跳过标题后的空格
                            String rest = trimmedLine.substring(cutIdx).trim();
                            // 如果剩余为空 → 整行删除；如果还有正文内容 → 保留正文部分
                            if (rest.isEmpty()) {
                                Log.d("LocalBookParser", "Removing title-only line: " + trimmedLine);
                                continue;
                            } else {
                                Log.d("LocalBookParser", "Stripping title prefix from line, kept: " + rest.substring(0, Math.min(rest.length(), 20)) + "...");
                                filteredLines.add(rest);
                                foundFirstContent = true;  // 遇到正文，停止检查
                                continue;
                            }
                        }
                        
                        // D. 遇到真正的正文行 → 停止检查标题
                        foundFirstContent = true;
                        filteredLines.add(line);
                    }
                                    
                    // 重新组合内容
                    content = String.join("\n", filteredLines).trim();
                }

                if (!content.isEmpty()) {
                    info.chapters.add(new Chapter(chapterIndex, chapterTitle, content));
                    chapterIndex++;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            // 添加更详细的错误信息到章节中，方便调试
            if (info.chapters.isEmpty()) {
                info.chapters.add(new Chapter(0, "第一章", "EPUB解析失败: " + e.getMessage()));
            }
        }

        if (info.chapters.isEmpty()) {
            info.chapters.add(new Chapter(0, "第一章", "EPUB解析失败或文件无内容"));
        }
        return info;
    }

    // ==================== 辅助方法 ====================
    
    /**
     * 清理HTML文本，移除内部标签
     */
    private static String cleanHtmlText(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                  .replaceAll("&nbsp;", " ")
                  .replaceAll("&lt;", "<")
                  .replaceAll("&gt;", ">")
                  .replaceAll("&amp;", "&")
                  .replaceAll("&quot;", "\"")
                  .replaceAll("&#39;", "'")
                  .replaceAll("&apos;", "'")
                  .trim();
    }
    
    private static String detectCharset(byte[] data) {
        if (data.length >= 2 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFF) return "UTF-16BE";
        if (data.length >= 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xFE) return "UTF-16LE";
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) return "UTF-8";
        return "UTF-8";
    }

    private static String getFileExtension(String href, String mediaType) {
        // 优先从 MIME 类型推断扩展名
        if (mediaType != null && !mediaType.isEmpty()) {
            String lowerType = mediaType.toLowerCase();
            if (lowerType.contains("jpeg") || lowerType.contains("jpg")) return ".jpg";
            if (lowerType.contains("png")) return ".png";
            if (lowerType.contains("gif")) return ".gif";
            if (lowerType.contains("webp")) return ".webp";
            if (lowerType.contains("bmp")) return ".bmp";
        }
        
        // 从文件名中提取扩展名
        if (href != null && href.contains(".")) {
            int dotIndex = href.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < href.length() - 1) {
                String fileExt = href.substring(dotIndex).toLowerCase();
                // 验证是否为合法的图片扩展名
                if (fileExt.matches("\\.(jpg|jpeg|png|gif|webp|bmp)")) {
                    return fileExt;
                }
            }
        }
        
        // 默认使用 .jpg（最常见）
        return ".jpg";
    }

    private static void deleteOldCover(Context context, long bookId) {
        File dir = context.getFilesDir();
        File[] files = dir.listFiles((d, name) -> name.startsWith("cover_" + bookId));
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int n;
        while ((n = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, n);
        }
        return buffer.toByteArray();
    }
    
    /**
     * ✅ 新增：判断是否应该跳过该章节（封面、目录、简介等非正文章节）
     */
    private static boolean shouldSkipChapter(String title, String html) {
        if (title == null || title.isEmpty()) {
            return false; // 没有标题的章节不跳过
        }
        
        String lowerTitle = title.toLowerCase();
        
        // 1. 根据标题关键词判断
        String[] skipKeywords = {
            // 封面相关
            "cover", "封面", "封底",
            // 目录相关  
            "目录", "contents", "table of contents", "toc",
            // 简介/前言相关
            "简介", "介绍", "前言", "序言", "楔子", "引子", "说明",
            "introduction", "preface", "foreword", "prologue",
            // 版权/制作信息
            "版权", "copyright", "制作", "制作说明", "出版信息", "声明",
            // 附录/后记
            "附录", "后记", "尾声", "跋", "epilogue", "afterword", "appendix",
            // 其他非正文
            "致谢", "acknowledgments", "关于作者", "about the author"
        };
        
        for (String keyword : skipKeywords) {
            if (lowerTitle.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        
        // 2. 根据HTML内容特征判断
        if (html != null && !html.isEmpty()) {
            String lowerHtml = html.toLowerCase();
            
            // 封面通常包含大量图片且文字很少
            int imgCount = html.split("<img").length - 1;
            int textLength = html.replaceAll("<[^>]+>", "").trim().length();
            
            // 如果图片很多但文字很少，可能是封面
            if (imgCount >= 3 && textLength < 500) {
                Log.d("LocalBookParser", "Detected cover by image count: " + imgCount + ", text length: " + textLength);
                return true;
            }
            
            // 目录通常包含大量链接
            int linkCount = html.split("<a ").length - 1;
            if (linkCount >= 10 && textLength < 2000) {
                Log.d("LocalBookParser", "Detected TOC by link count: " + linkCount);
                return true;
            }
        }
        
        // 3. 检查是否是第一个章节且标题包含"cover"或"封面"
        // （EPUB通常将封面作为第一个itemref）
        
        return false;
    }
    
    /**
     * ✅ 新增：按文件名过滤前置/后置非正文章节
     * cover, toc, copyright, instruction, pretxt, tiaoshu 等
     */
    private static boolean shouldSkipByFileName(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        
        // EPUB 中常见的非正文章节文件名模式
        String[] skipPatterns = {
            "^cover",       // cover.xhtml, cover.html
            "^toc",         // toc.ncx, toc.html
            "^copyright",   // copyright.html
            "^instruction", // instruction.html（制作说明）
            "^pretxt",      // pretxt.html（前言/简介）
            "^tiaoshu",     // tiaoshu.xhtml（挑书/制作方logo页）
            "^advert",      // 广告页
            "^intro",       // intro.html
            "^preface",     // preface.html
            "^prologue",    // prologue.html
            "^epilogue",    // epilogue.html
            "^afterword",   // afterword.html
            "^postscript"   // postscript.html
        };
        
        for (String pattern : skipPatterns) {
            if (lower.matches(pattern + "\\.(x?html?|xhtml|xml)$") || lower.matches(pattern + "\\.ncx$")) {
                return true;
            }
        }
        
        return false;
    }
}