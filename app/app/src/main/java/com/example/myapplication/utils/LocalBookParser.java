package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;  // 新增：用于调试日志

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


/**
 * 本地书解析器 - 支持 TXT 和 EPUB
 */
public class LocalBookParser {
    
    // 性能优化：预编译正则表达式，减少重复编译开销
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

    // ==================== 预编译正则：按章循环内绝不允许再 Pattern.compile ====================
    // 背景：Android 的 java.util.regex 由 ICU 实现，Pattern.compile 的成本约为桌面 JVM 的 50~100 倍。
    // 真机实测「选择器作用域修饰」那条长正则编译一次要 ~5.7ms；一本书 1800 章 × 每章数百个选择器
    // = 上百万次编译，导致「正在导入」卡到近一小时（导入耗时 ≈ 98 分钟）。
    // 下面所有正则在类加载时编译一次、全书复用。

    /** 选择器上附带的 class / id / 属性 / 伪类修饰（如 body.dark、:root:first-child） */
    private static final String SCOPE_MOD =
            "(?:\\.[\\w-]+|#[\\w-]+|\\[[^\\]]*\\]|:{1,2}[\\w-]+(?:\\([^)]*\\))?)*";
    /** 等价于原 "(?i)^(html|body|:root)(?![\\w-])" + mod */
    private static final Pattern RE_SCOPE_ROOT =
            Pattern.compile("(?i)^(html|body|:root)(?![\\w-])" + SCOPE_MOD);
    /** 等价于原 "(?i)^\\.epub-chapter\\s+body(?![\\w-])" + mod + "(?=[\\s>+~]|$)" */
    private static final Pattern RE_SCOPE_EPUB_BODY =
            Pattern.compile("(?i)^\\.epub-chapter\\s+body(?![\\w-])" + SCOPE_MOD + "(?=[\\s>+~]|$)");

    private static final Pattern RE_STYLE_BLOCK =
            Pattern.compile("<style[^>]*>([\\s\\S]*?)</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_STYLESHEET_LINK =
            Pattern.compile("<link[^>]+rel=\"stylesheet\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HREF_ATTR =
            Pattern.compile("href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_BODY_BLOCK =
            Pattern.compile("<body[^>]*>([\\s\\S]*?)</body>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HEAD_BLOCK = Pattern.compile("<head[\\s\\S]*?</head>");
    private static final Pattern RE_DOCTYPE = Pattern.compile("<!DOCTYPE[^>]*>");
    private static final Pattern RE_HTML_TAG = Pattern.compile("(?i)</?html[^>]*>");
    private static final Pattern RE_HEAD_TAG = Pattern.compile("(?i)</?head[^>]*>");

    private static final Pattern RE_IMG_TAG =
            Pattern.compile("<img\\b([\\s\\S]*?)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_SRC_ATTR =
            Pattern.compile("src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_SVG_IMAGE_TAG =
            Pattern.compile("<image\\b([\\s\\S]*?)/>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HREF_OR_XLINK =
            Pattern.compile("(xlink:href|href)=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern RE_HEADING =
            Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RE_TITLE_TAG =
            Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HAS_CHAPTER_NUM = Pattern.compile(
            "第[\\d一二三四五六七八九十百千万零〇两]+[章卷回部集篇回]|chapter\\s*\\d+",
            Pattern.CASE_INSENSITIVE);
    /**
     * EPUB 生成器（EasyPub 等）自动填进 &lt;title&gt; 的**占位标题**，形如
     * "chapter 3 - 0" / "chapter 12" / "section 2"。它没有任何信息量，
     * 却因为同时命中「含 chapter+数字」和「长度 ≥ 12」拿到高分，
     * 把真正的 h1-h6 / NCX 标题挤掉——实测《诸天尽头》1355 章里 92 章被它顶掉。
     */
    private static final Pattern RE_PLACEHOLDER_TITLE = Pattern.compile(
            "^(?:chapter|section|part)\\s*\\d+(?:\\s*[-_–—]\\s*\\d+)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RE_WS_PLUS = Pattern.compile("\\s+");
    private static final Pattern RE_TITLE_PREFIX_CHAPTER =
            Pattern.compile("^第[一二三四五六七八九十百千万0-9]+章\\s*");
    private static final Pattern RE_TITLE_PREFIX_VOLUME =
            Pattern.compile("^第[一二三四五六七八九十百千万0-9]+卷\\s*");
    private static final Pattern RE_TITLE_PREFIX_EN =
            Pattern.compile("(?i)^chapter\\s*[0-9]+[^\\u4e00-\\u9fa5]*\\s*");
    /** 正文首行「第X章/卷X/chapter N」前缀（后面再拼上标题正文，故作为常量拆分） */
    private static final String COMBINED_PREFIX_BODY =
            "^\\s*(?:第[一二三四五六七八九十百千万0-9]+[章卷回部集篇]|chapter\\s*[0-9]+)\\s*";

    /** 标题质量评估用的「通用占位词」（原实现每章都 new 一个 HashSet，这里改成类级常量） */
    private static final Set<String> GENERIC_TITLES = new HashSet<>(java.util.Arrays.asList(
            "chapter", "章节", "章", "part", "部分", "section", "节",
            "prologue", "epilogue", "preface", "前言", "序言", "楔子",
            "cover", "封面", "copyright", "版权"
    ));

    /**
     * 标题来源权重：NCX 目录（生成工具写入，最权威）&gt; HTML 标题标签 &gt; &lt;title&gt;。
     * 顺序与 {@code parseEpub} 里 ncxTitle / headingTitle / titleTagTitle 的取值顺序一致。
     * ★ 必须按来源加权：只按文本内容打分时，&lt;title&gt; 里的 "chapter 3 - 0"
     * 会靠长度和「chapter+数字」拿到比真实短标题更高的分，从而顶掉正确的标题。
     */
    private static final int[] TITLE_SOURCE_WEIGHT = {30, 20, 0};

    /**
     * 非正文章节标题关键词——【强标记】：出现在标题任意位置即视为非正文。
     * 这些都是"扉页性质"的词，正常章节名几乎不会包含。
     */
    private static final String[] SKIP_TITLE_KEYWORDS_ALWAYS = {
            "cover", "封面", "封底",
            "目录", "contents", "table of contents", "toc",
            "版权", "copyright", "制作说明", "出版信息",
            "acknowledgments", "about the author"
    };

    /**
     * 非正文章节标题关键词——【弱标记】：只有标题本身就基本是这个标记（很短）时才算非正文。
     *
     * <p>★ 不能无条件用 {@code contains}：这些词在**真实章节名**里很常见。例如
     * 《诸天尽头》第五百六十五章「自我介绍一下，我是九头蛇的现任首领」，
     * 旧实现按 contains("介绍") 直接把它当"简介"过滤掉 → 整章凭空消失
     * （真机实测：章节列表从"第五百六十四章"直接跳到"第五百六十六章"）。
     */
    private static final String[] SKIP_TITLE_KEYWORDS_SHORT = {
            "简介", "介绍", "说明", "引言",
            "前言", "序言", "楔子", "引子",
            "introduction", "preface", "foreword", "prologue", "epilogue",
            "版权页", "制作", "声明",
            "附录", "后记", "尾声", "跋",
            "afterword", "appendix", "致谢"
    };

    /** 弱标记只在标题长度不超过该值时才生效（标题≈标记本身，而不是"标记+章节名"） */
    private static final int SKIP_TITLE_SHORT_MAX_LEN = 12;

    /**
     * 「简介章」标题标记（**归一化后**比较：去空白、转小写）。
     *
     * <p>这类章节描述的是整本书而不是正文，导入后会被 {@link #shouldSkipChapter} 从章节列表剔除。
     * 在剔除之前要把它截获下来当书籍简介（见 parseEpub 中的捕获逻辑），否则简介就彻底丢了。
     *
     * <p>和 {@link #SKIP_TITLE_KEYWORDS_SHORT} 一样必须配合长度上限使用：
     * 例如《诸天尽头》第五百六十五章「自我介绍一下，我是九头蛇的现任首领」，
     * 标题里含"介绍"但它是一章真正文。
     */
    private static final String[] INTRO_TITLE_MARKERS = {
            "内容简介", "内容介绍", "内容提要", "内容梗概", "故事简介", "本书简介",
            "书籍简介", "作品简介", "图书简介", "简介",
            "description", "synopsis", "summary", "about this book"
    };

    /** 简介章标题的归一化长度上限（"内容简介"=4 命中；"…自我介绍一下…"=20 不命中） */
    private static final int INTRO_TITLE_MAX_LEN = 12;

    /** 简介正文的最小长度：少于该值视为无效简介（例如只有孤零零一个"内容简介"标题） */
    private static final int INTRO_MIN_TEXT_LEN = 20;

    /** 按文件名过滤非正文章节：13 个前缀 × 4 个后缀合成一条正则（原来是每章 26 次 matches，每次都要重编译） */
    private static final Pattern RE_SKIP_FILE_NAME = Pattern.compile(
            "^(cover|toc|copyright|instruction|pretxt|tiaoshu|advert|intro|preface|prologue|epilogue|afterword|postscript)"
                    + "\\.(x?html?|xhtml|xml|ncx)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RE_IMG_OPEN = Pattern.compile("<img");
    private static final Pattern RE_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern RE_A_OPEN = Pattern.compile("<a ");

    /** cleanHtmlText 用的 HTML 实体（顺序与原实现一致：nbsp → lt → gt → amp → quot → #39 → apos） */
    private static final Pattern RE_HTML_ENTITY_NBSP = Pattern.compile("&nbsp;");
    private static final Pattern RE_HTML_ENTITY_LT = Pattern.compile("&lt;");
    private static final Pattern RE_HTML_ENTITY_GT = Pattern.compile("&gt;");
    private static final Pattern RE_HTML_ENTITY_AMP = Pattern.compile("&amp;");
    private static final Pattern RE_HTML_ENTITY_QUOT = Pattern.compile("&quot;");
    private static final Pattern RE_HTML_ENTITY_APOS39 = Pattern.compile("&#39;");
    private static final Pattern RE_HTML_ENTITY_APOS = Pattern.compile("&apos;");

    /** 分卷标题判定（原来在 isVolumeLabel 里每次调用都 Pattern.compile，NCX 里每章都要调用） */
    private static final Pattern RE_VOLUME_LABEL = Pattern.compile(
            "^(第[\\d一二三四五六七八九十百千万零〇两]+[卷部篇]|卷[\\d一二三四五六七八九十百千万零〇两]+|part\\s*\\d+|volume\\s*\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_NAV_LABEL =
            Pattern.compile("<navLabel>\\s*<text[^>]*>([\\s\\S]*?)</text>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_NAV_CONTENT_SRC =
            Pattern.compile("<content[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    /** 合法图片扩展名（getFileExtension 里原来的 String.matches） */
    private static final Pattern RE_IMAGE_EXT =
            Pattern.compile("\\.(jpg|jpeg|png|gif|webp|bmp)");

    /** 正文清理链：正则 + 替换文本按顺序成对保存，正则只编译一次 */
    private static final Pattern[] CLEAN_PATTERNS;
    private static final String[] CLEAN_REPLACEMENTS;
    static {
        String[][] rules = {
                {"(?is)<script[^>]*>.*?</script>", ""},
                {"(?is)<style[^>]*>.*?</style>", ""},
                {"(?s)<!--.*?-->", ""},
                {"(?is)<meta[^>]>", ""},
                {"(?is)<link[^>]>", ""},
                {"(?i)<h[1-6][^>]*>.*?</h[1-6]>", "\n"},
                {"(?i)<br\\s*/?>", "\n"},
                {"(?i)</p>", "\n\n"},
                {"(?i)</div>", "\n"},
                {"(?i)</li>", "\n"},
                {"<[^>]+>", ""},
                {"&nbsp;", " "},
                {"&lt;", "<"},
                {"&gt;", ">"},
                {"&amp;", "&"},
                {"&quot;", "\""},
                {"&#39;", "'"},
                {"&apos;", "'"},
                {"&#x27;", "'"},
                {"&#x2018;", "'"},
                {"&#x2019;", "'"},
                {"&#x201C;", "\""},
                {"&#x201D;", "\""},
                {"\\n{3,}", "\n\n"},
                {"^[\\s\\n]+", ""},
                {"[\\s\\n]+$", ""},
                {"\\r\\n", "\n"},
                {"(?m)^Cover\\s*$", ""},
                {"(?m)^封面\\s*$", ""},
                {"(?m)^目录\\s*$", ""},
                {"\\n{3,}", "\n\n"},
        };
        CLEAN_PATTERNS = new Pattern[rules.length];
        CLEAN_REPLACEMENTS = new String[rules.length];
        for (int i = 0; i < rules.length; i++) {
            CLEAN_PATTERNS[i] = Pattern.compile(rules[i][0]);
            // 替换串里可能含 $ 或 \，统一按字面量处理，语义与 String.replaceAll 完全一致
            CLEAN_REPLACEMENTS[i] = Matcher.quoteReplacement(rules[i][1]);
        }
    }

    /** 执行正文清理链：等价于原先那一长串 String.replaceAll，但正则不再重复编译 */
    private static String cleanChapterContent(String html) {
        String c = html;
        for (int i = 0; i < CLEAN_PATTERNS.length; i++) {
            c = CLEAN_PATTERNS[i].matcher(c).replaceAll(CLEAN_REPLACEMENTS[i]);
        }
        return c.trim();
    }

    /**
     * 提取 {@code <body>} 内部片段；没有 body 标签时退化为「去掉 head / doctype / html 标签后的整体」。
     *
     * <p>调用方两处：① 阅读器「保留样式」HTML 的构建；② 抽取书籍简介。
     * 简介必须走 body，否则 head 里 {@code <title>} 的占位标题（如 {@code chapter 0 - 0}）
     * 会混进简介正文。
     */
    private static String bodyOf(String html) {
        if (html == null) return "";
        Matcher m = RE_BODY_BLOCK.matcher(html);
        if (m.find()) return m.group(1);
        return RE_HEAD_TAG.matcher(
                RE_HTML_TAG.matcher(
                        RE_DOCTYPE.matcher(
                                RE_HEAD_BLOCK.matcher(html).replaceAll("")
                        ).replaceAll("")
                ).replaceAll("")
        ).replaceAll("");
    }

    public static class Chapter {
        public String title;
        public String content;
        /** 保留样式的 HTML（供阅读器 HTML 渲染模式使用；为空则回退纯文本） */
        public String htmlContent = null;
        public int index;
        /** 所属分卷标题（无分卷时为空） */
        public String volumeTitle = "";
        /** 所属分卷序号，从 1 开始 */
        public int volumeIndex = 1;

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
        /** 书籍简介（EPUB 的 dc:description，或「内容简介」章的正文）；解析不到为空串 */
        public String intro = "";
        public List<Chapter> chapters = new ArrayList<>();
        /** 分卷标题列表（按出现顺序），无分卷时为空 */
        public List<String> volumes = new ArrayList<>();
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
                // 卷+章（如「第一卷 第一章」）
                "^(第[\\d零一二三四五六七八九十百千万]+[卷集册])\\s*(第[\\d零一二三四五六七八九十百千万]+[章节节]).*",
                // 章+节（如「第一章 第一节」）
                "^(第[\\d零一二三四五六七八九十百千万]+[章节节])\\s*(第[\\d零一二三四五六七八九十百千万]+[节]).*",
                "^(第[\\d零一二三四五六七八九十百千万]+[卷集册]).*",
                "^(第[\\d零一二三四五六七八九十百千万]+[章节节]).*",
                // 回（古典章回体），但排除「回合」这类非章节标题
                "^(第[\\d零一二三四五六七八九十百千万]+回(?!合)).*",
                // 序言/前言等前置章节，但仅当其后不是紧跟汉字（避免把「前言不搭后语…」这类正文误判为章节）
                "^(序言|前言|楔子|引子|尾声|后记|番外|附录)(?![\\u4e00-\\u9fa5]).*",
                // 纯数字编号章节（「1. 标题」），排除「1.0版本…」这类小数，且不再把「30、耐力…」这类列表误判
                "^(\\d+[\\.．\\s]+[^\\d]).*",
        };
        Pattern chapterPattern = Pattern.compile(String.join("|", patterns));
        // 纯数字编号行的「候选」判定；命中后还会用 containsSentencePunct/长度二次过滤，剔除大纲列表等正文行。
        Pattern digitChapterPattern = Pattern.compile("^(\\d+[\\.．、\\s]+)[^\\d]");

        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return info;
            byte[] rawData = readAllBytes(is);
            is.close();
            String charset = detectCharset(rawData);
            String text = new String(rawData, charset);
            // 只有当 UTF-8 解码后「替换字符（U+FFFD）占比很高」才判定原始字节流不是 UTF-8
            // （多半是 GBK），此时改用 GBK 重新解码。文本里偶尔自带几个 U+FFFD（编辑器把坏字符
            // 替换后留下的占位）不能据此整本改用 GBK 重解——否则正常 UTF-8 中文书会被解成乱码，
            // 导致「第X章」全部匹配失败、章节识别为 0，导入后只剩兜底的单章「第一章」。
            if (charset.equals("UTF-8") && !isUtf8DecodeLikelyValid(text)) {
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

            // 章节序号 → 该序号「上一次被保留的章节标题」与行号。
            // 强去重逻辑：同一序号（第N章/卷/回）再次出现时：
            //  - 水印/作者注垃圾词、或正文行误命中（「第N章」后无空格直接续写长句）→ 直接丢弃；
            //  - 否则若两处相隔很远、或中间存在「（第X卷完）」卷标记 → 判定为「新一卷重新编号」，
            //    保留并更新记录（多卷本会每卷重置编号，如第二卷的「第一章 外乡人」）；
            //  - 否则若标题相等/近似（「本章未完」复述、标点变体、被追加垃圾后缀）→ 丢弃；
            //  - 否则标题明显不同且非近似 → 视为独立真章节，保留。
            java.util.Map<String, String> lastTitleByKey = new java.util.HashMap<>();
            java.util.Map<String, Integer> lastKeptLineByKey = new java.util.HashMap<>();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.length() > 100) continue; // 标题通常不会太长

                Matcher matcher = chapterPattern.matcher(trimmed);
                if (matcher.find()) {
                    // 纯数字编号行若带有句末/句中标点（如大纲列表「1.观察…」「2.初次…」），多半是正文而非章节，跳过。
                    if (digitChapterPattern.matcher(trimmed).find()
                            && (containsSentencePunct(trimmed) || trimmed.length() > 40)) {
                        continue;
                    }

                    // 提取原始标题
                    String rawTitle = trimmed;
                    // 如果原始标题以 # 开头，则去除 # 和空格
                    String title = rawTitle.replaceFirst("^#+\\s*", "").trim();

                    // 强规则：同一章节序号（第N章/卷/回）的去重。
                    boolean isDuplicate = false;
                    String currentNumKey = chapterNumberKey(title);
                    if (currentNumKey != null) {
                        String lastKeptTitle = lastTitleByKey.get(currentNumKey);
                        Integer lastKeptLine = lastKeptLineByKey.get(currentNumKey);
                        if (lastKeptTitle != null && lastKeptLine != null) {
                            // 1) 水印/作者注垃圾词 → 丢弃
                            if (containsWatermarkJunk(title)) {
                                isDuplicate = true;
                            }
                            // 2) 正文行误命中（「第N章」后无空格直接续写长句）→ 丢弃
                            else if (isBodyLineFalsePositive(title)) {
                                isDuplicate = true;
                            } else {
                                // 3) 同序号再次出现：是否「新一卷重新编号」（卷标记在中间，或相隔很远）。
                                // 注意短路顺序：先判行距（O(1)），只有行距不大时才去扫描两行之间的小窗口找卷标记。
                                // 反过来先扫描的话，换卷场景两处相隔几万行，每个重复序号都要正则逐行扫几万行，
                                // 一本多卷本累计可达数千万次逐行正则，手机上会卡在「正在导入」数分钟。
                                boolean isVolumeRestart = (i - lastKeptLine) > VOLUME_GAP_THRESHOLD
                                        || hasVolumeMarkerBetween(lines, lastKeptLine, i);
                                if (isVolumeRestart) {
                                    // 真正的不同卷的同序号章节，保留并更新记录。
                                    lastTitleByKey.put(currentNumKey, title);
                                    lastKeptLineByKey.put(currentNumKey, i);
                                } else if (isChapterTitleNearDuplicate(title, lastKeptTitle)) {
                                    // 同卷、标题相等/近似（「本章未完」复述、标点变体、被追加垃圾后缀） → 丢弃
                                    isDuplicate = true;
                                } else {
                                    // 同卷、标题明显不同且非近似 → 视作独立真章节，保留并更新记录。
                                    lastTitleByKey.put(currentNumKey, title);
                                    lastKeptLineByKey.put(currentNumKey, i);
                                }
                            }
                        } else {
                            lastTitleByKey.put(currentNumKey, title);
                            lastKeptLineByKey.put(currentNumKey, i);
                        }
                    }
                    // 弱规则：只对「无章节序号」的标题（序言/前言等）做相似度去重，
                    // 避免把序号不同但标题近似的连续正文章节（如「…渡劫x2」「…渡劫x4」）错误地合并，导致缺章。
                    if (!isDuplicate && !chapterTitles.isEmpty()) {
                        String lastTitle = chapterTitles.get(chapterTitles.size() - 1);
                        if (currentNumKey == null && chapterNumberKey(lastTitle) == null) {
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

            // === TXT 分卷识别 ===
            // 两个来源：① 独立的「第X卷 …」卷标题章；② 章末的「（第X卷完）」结束标记。
            // 结果写回每章的 volumeIndex/volumeTitle，并记录 info.volumes（卷标题顺序）。
            // 无分卷信息的普通 TXT 保持原样（volumeIndex 默认 1、volumeTitle 空），目录仍是平铺列表。
            int[] chapterVolumeIndex = new int[chapterStarts.size()];
            List<String> txtVolumeTitles = new ArrayList<>();
            try {
                detectTxtVolumes(lines, chapterStarts, chapterTitles, chapterVolumeIndex, txtVolumeTitles);
            } catch (Throwable t) {
                android.util.Log.e("LocalBookParser", "TXT 分卷识别失败，按不分卷处理", t);
            }
            if (!txtVolumeTitles.isEmpty()) info.volumes = txtVolumeTitles;

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
                Chapter ch = new Chapter(i, chapterTitles.get(i), sb.toString());
                int vi = chapterVolumeIndex[i];
                if (vi > 0 && vi <= txtVolumeTitles.size()) {
                    ch.volumeIndex = vi;
                    ch.volumeTitle = txtVolumeTitles.get(vi - 1);
                }
                info.chapters.add(ch);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (info.chapters.isEmpty()) {
            info.chapters.add(new Chapter(0, "第一章", "无法解析文件内容"));
        }
        return info;
    }

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

    // ==================== TXT 分卷识别 ====================
    //
    // 站点 TXT 的分卷通常只有两种痕迹（很多书没有独立的「第X卷 卷名」行）：
    //   ① 独立的卷标题章：「第一卷 梦魇」这种整行；
    //   ② 章末的卷结束标记：「（第一卷完）」（多数网文只有这一种）。
    // 这里把两者统一成「每章归属哪一卷」，再由 buildVolumeInfos 汇总出卷区间，
    // 供导入持久化与阅读器目录的分卷折叠显示使用。

    /** 「第X卷/册」卷标题章；后接非汉字，避免命中「第三卷子」这类正文词 */
    private static final Pattern TXT_VOLUME_HEADING_PATTERN = Pattern.compile(
            "^第([\\d零一二三四五六七八九十百千万〇两]+)\\s*[卷册](?![\\u4e00-\\u9fa5])");

    /** 章末卷结束标记：（第X卷完）/（第X卷 终）/【第X卷完】/(第X卷完) 等 */
    private static final Pattern TXT_VOLUME_END_PATTERN = Pattern.compile(
            "[（(【]\\s*第?([\\d零一二三四五六七八九十百千万〇两]+)\\s*卷\\s*[完终结]?\\s*[）)】]");

    /** 卷结束标记只认「章末尾部」，避免正文里提到某卷被误判为分卷点 */
    private static final int TXT_VOLUME_END_TAIL_CHARS = 200;

    private static final char[] CN_DIGITS = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};

    /** 中文数字转 int（支持零〇两，及十/百/千/万；也接受纯阿拉伯数字）；无法解析返回 0 */
    private static int chineseNumberToInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        String t = s.trim();
        if (t.matches("[0-9]+")) {
            try { return Integer.parseInt(t); } catch (Exception e) { return 0; }
        }
        int total = 0, section = 0, cur = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int d = chineseDigit(c);
            if (d >= 0) { cur = d; continue; }
            switch (c) {
                case '十': section += (cur == 0 ? 1 : cur) * 10; cur = 0; break;
                case '百': section += (cur == 0 ? 1 : cur) * 100; cur = 0; break;
                case '千': section += (cur == 0 ? 1 : cur) * 1000; cur = 0; break;
                case '万': section = (section + cur) * 10000; total += section; section = 0; cur = 0; break;
                default: break;
            }
        }
        return total + section + cur;
    }

    private static int chineseDigit(char c) {
        switch (c) {
            case '零': case '〇': case '○': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    /** int 转中文数字（1~999 用中文，超出则退回阿拉伯数字）；用于「第X卷」自动编号 */
    private static String intToChineseNumber(int num) {
        if (num <= 0) return String.valueOf(num);
        if (num < 10) return String.valueOf(CN_DIGITS[num]);
        if (num < 20) return "十" + (num % 10 == 0 ? "" : String.valueOf(CN_DIGITS[num % 10]));
        if (num < 100) {
            return "" + CN_DIGITS[num / 10] + "十" + (num % 10 == 0 ? "" : String.valueOf(CN_DIGITS[num % 10]));
        }
        if (num < 1000) {
            String s = "" + CN_DIGITS[num / 100] + "百";
            int rem = num % 100;
            if (rem == 0) return s;
            if (rem < 10) return s + "零" + CN_DIGITS[rem];
            return s + intToChineseNumber(rem);
        }
        return String.valueOf(num);
    }

    /** 标题是否为卷标题章；是则返回其中的卷号数字文本（「第一卷」→「一」），否则 null */
    private static String txtVolumeHeadingText(String title) {
        if (title == null) return null;
        Matcher m = TXT_VOLUME_HEADING_PATTERN.matcher(title.trim());
        return m.find() ? m.group(1) : null;
    }

    /** 标题是否像卷标题（含 EPUB 目录里的「卷三」「Part 2」「Volume 1」形式） */
    private static boolean isVolumeHeadingTitle(String title) {
        if (title == null || title.isEmpty()) return false;
        String t = title.trim();
        return TXT_VOLUME_HEADING_PATTERN.matcher(t).find() || RE_VOLUME_LABEL.matcher(t).find();
    }

    /**
     * 本章末尾是否出现「（第X卷完）」标记；有则返回标记里的卷号数字文本，否则 null。
     * 只在章末 {@link #TXT_VOLUME_END_TAIL_CHARS} 个字符内找，避免正文引用被误判。
     */
    private static String txtVolumeEndText(String[] lines, int fromLine, int toLine) {
        if (lines == null || toLine <= fromLine) return null;
        StringBuilder tail = new StringBuilder();
        for (int i = toLine - 1; i >= fromLine && tail.length() < TXT_VOLUME_END_TAIL_CHARS; i--) {
            String s = lines[i].trim();
            if (s.isEmpty()) continue;
            tail.insert(0, s);
        }
        if (tail.length() == 0) return null;
        Matcher m = TXT_VOLUME_END_PATTERN.matcher(tail);
        String found = null;
        while (m.find()) found = m.group(1);   // 取最后一个标记
        return found;
    }

    /**
     * TXT 分卷识别：把每章归属到某一卷，输出 volumeIndexOfChapter（1 起，0 = 未归属）与卷标题顺序。
     * 无任何分卷痕迹时不做任何写入（目录保持平铺）。
     */
    private static void detectTxtVolumes(String[] lines, List<Integer> starts, List<String> titles,
                                         int[] volumeIndexOfChapter, List<String> volumeTitles) {
        int n = titles.size();
        if (n == 0) return;

        List<int[]> ranges = new ArrayList<>();      // {起始章, 结束章, 是否由卷标题章开启}
        List<String> rangeTitles = new ArrayList<>();
        int openStart = -1;          // 正在累积的卷起始章
        String openTitle = null;     // 该卷标题（来自卷标题章）
        boolean openFromHeading = false;
        int nextNumber = 0;          // 下一个「无卷名卷」的卷号（由结束标记推断）

        for (int j = 0; j < n; j++) {
            String headingText = txtVolumeHeadingText(titles.get(j));
            if (headingText != null) {
                // 卷标题章：闭合上一卷，开启新卷
                if (openStart >= 0 && j - 1 >= openStart) {
                    ranges.add(new int[]{openStart, j - 1, openFromHeading ? 1 : 0});
                    rangeTitles.add(openTitle);
                }
                openStart = j;
                openTitle = titles.get(j);
                openFromHeading = true;
                nextNumber = chineseNumberToInt(headingText) + 1;
                continue;
            }
            int toLine = (j + 1 < n) ? starts.get(j + 1) : lines.length;
            String endText = txtVolumeEndText(lines, starts.get(j) + 1, toLine);
            if (endText != null) {
                if (openStart >= 0) {
                    ranges.add(new int[]{openStart, j, openFromHeading ? 1 : 0});
                    rangeTitles.add(openTitle);
                } else {
                    // 尚未开卷：本标记之前的这些章节就属于「以本标记结尾」的这一卷
                    // （只给「（第X卷完）」没有卷标题行的 TXT 就是这种情况）
                    int st = ranges.isEmpty() ? 0 : ranges.get(ranges.size() - 1)[1] + 1;
                    if (st <= j) {
                        ranges.add(new int[]{st, j, 0});
                        rangeTitles.add("第" + endText + "卷");
                    }
                }
                nextNumber = chineseNumberToInt(endText) + 1;
                openStart = -1;
                openTitle = null;
                openFromHeading = false;
            }
        }
        // 收尾：末尾没有结束标记时，最后一段自成末卷
        if (openStart >= 0) {
            ranges.add(new int[]{openStart, n - 1, openFromHeading ? 1 : 0});
            rangeTitles.add(openTitle);
        } else if (!ranges.isEmpty()) {
            int st = ranges.get(ranges.size() - 1)[1] + 1;
            if (st <= n - 1) {
                ranges.add(new int[]{st, n - 1, 0});
                rangeTitles.add("第" + intToChineseNumber(nextNumber > 0 ? nextNumber : ranges.size() + 1) + "卷");
            }
        }

        if (ranges.isEmpty()) return;

        for (int v = 0; v < ranges.size(); v++) {
            int[] r = ranges.get(v);
            int from = Math.max(0, r[0]);
            int to = Math.min(n - 1, r[1]);
            if (from > to) continue;
            String title = rangeTitles.get(v);
            if (title == null || title.isEmpty()) title = "第" + intToChineseNumber(v + 1) + "卷";
            volumeTitles.add(title);
            for (int c = from; c <= to; c++) volumeIndexOfChapter[c] = volumeTitles.size();
        }
    }

    /** 一卷的区间信息：供导入持久化与阅读器目录分组共用 */
    public static class VolumeInfo {
        /** 卷号，从 1 开始 */
        public int index;
        /** 卷标题（如「第一卷」「第一卷 梦魇」） */
        public String title = "";
        /** 卷起始章（含） */
        public int start;
        /** 目录子项起始章（含）：卷标题章本身无正文时不作为子项，此时为 start+1 */
        public int childStart;
        /** 卷结束章（含） */
        public int end;
    }

    /**
     * 汇总分卷结构（TXT 由 detectTxtVolumes 写入，EPUB 由 NCX 目录写入 volumeIndex/volumeTitle）。
     * 单卷/无卷的书返回空列表（目录保持平铺，不做无意义的分组）。
     */
    public static List<VolumeInfo> buildVolumeInfos(BookInfo info) {
        List<VolumeInfo> result = new ArrayList<>();
        if (info == null || info.chapters == null || info.chapters.isEmpty()) return result;
        List<Chapter> chs = info.chapters;
        int maxIdx = 0;
        for (Chapter c : chs) if (c.volumeIndex > maxIdx) maxIdx = c.volumeIndex;
        if (maxIdx < 2) return result;   // 只有一卷等于没分卷

        for (int v = 1; v <= maxIdx; v++) {
            int start = -1, end = -1;
            String title = "";
            for (int i = 0; i < chs.size(); i++) {
                Chapter c = chs.get(i);
                if (c.volumeIndex != v) continue;
                if (start < 0) {
                    start = i;
                    title = (c.volumeTitle == null) ? "" : c.volumeTitle;
                }
                end = i;
            }
            if (start < 0 || end < start) continue;
            VolumeInfo vi = new VolumeInfo();
            vi.index = v;
            vi.start = start;
            vi.end = end;
            if (title.isEmpty() && info.volumes != null && v - 1 < info.volumes.size()) {
                String t = info.volumes.get(v - 1);
                title = (t == null) ? "" : t;
            }
            if (title.isEmpty()) title = "第" + intToChineseNumber(v) + "卷";
            vi.title = title;

            // 卷标题章（如「第一卷 梦魇」）本身通常没有正文，只是卷名页：
            // 这种情况不把它作为目录子项，避免展开后与卷行重复显示同一标题。
            Chapter first = chs.get(start);
            String content = first.content;
            boolean blank = (content == null) || content.trim().length() < 20;
            boolean headingChapter = isVolumeHeadingTitle(first.title);
            vi.childStart = (headingChapter && blank && start + 1 <= end) ? start + 1 : start;
            result.add(vi);
        }
        return result;
    }

    /**
     * 提取标题行开头的章节序号键（如「第一章」「第五百八十六章」「第二回」），
     * 用于 TXT 解析的强去重。非序号标题（序言/前言/普通正文行等）返回 null。
     * 注意：回章节排除「回合」这类非章节标题。
     */
    // 章节序号键正则（预编译）：每个候选标题行都会调用一次 chapterNumberKey，逐次编译开销不可接受。
    private static final Pattern CHAPTER_NUMBER_KEY_PATTERN = Pattern.compile(
            "^第[\\d零一二三四五六七八九十百千万]+[卷集册篇章回节]");

    private static String chapterNumberKey(String title) {
        if (title == null) return null;
        // 兼容阿拉伯数字与中文数字：第[0-9/中文数字]+[卷集册篇章回节]
        // 注意：不含「部/篇」，避免把正文里的「第三部分」「第一篇」误判成卷首序号键。
        java.util.regex.Matcher m = CHAPTER_NUMBER_KEY_PATTERN.matcher(title);
        return m.find() ? m.group() : null;
    }

    // 站点水印/作者注常见垃圾词：命中其一即视为同源重复章节行（如「…免费阅读」「…在审核」）。
    private static final String[] WATERMARK_KEYWORDS = {
            "免费阅读", "在审核", "最快更新", "为您提供", "书签", "浏览器", "进行查看", "题外话",
            "本章未完", "防盗", "笔趣", "小说网", "最新章节", "手机阅读", "求订阅", "求月票",
            "感言", "上架", "公告", "捉虫", "请假", "推书", "完本", "首发", "未完待续",
            "更新最快", "为您", "下次还能", "保存好"
    };

    private static boolean containsWatermarkJunk(String title) {
        if (title == null) return false;
        for (String k : WATERMARK_KEYWORDS) {
            if (title.contains(k)) return true;
        }
        return false;
    }

    // 是否含中文句末/句中标点（用于过滤「1.观察…」这类大纲列表行）。
    private static boolean containsSentencePunct(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if ("。；：，、！？…—".indexOf(s.charAt(i)) >= 0) return true;
        }
        return false;
    }

    // 两个标题是否「同源重复」：完全相等，或其一为另一者前缀（追加了「免费阅读」等垃圾后缀），且差异较短。
    private static boolean isChapterTitleNearDuplicate(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalizeTitle(a), nb = normalizeTitle(b);
        if (na.equals(nb)) return true;
        String longer = na.length() >= nb.length() ? na : nb;
        String shorter = na.length() >= nb.length() ? nb : na;
        return longer.startsWith(shorter) && (longer.length() - shorter.length()) <= 15;
    }

    // 同卷内两处「第N章」相隔多大行数，才足以判定为「新一卷重新编号」（而非同卷复述/水印）。
    private static final int VOLUME_GAP_THRESHOLD = 1500;

    // 标题是否为正文行误命中：如「第五百七十六章天下除我再无魔时机不合适…」——「章」后无空格直接
    // 续写 ≥8 个非空白字符的长句，显然是正文而不是章节标题。
    private static final Pattern BODY_LINE_FALSE_POSITIVE_PATTERN = Pattern.compile(
            "^第[\\d零一二三四五六七八九十百千万]+[卷集册章节节回][^\\s].{7,}");

    private static boolean isBodyLineFalsePositive(String title) {
        if (title == null) return false;
        return BODY_LINE_FALSE_POSITIVE_PATTERN.matcher(title).matches();
    }

    // 「第X卷」卷结束标记（预编译：导入热路径上使用；命名避开类顶部既有的
    // VOLUME_MARKER_PATTERN——那是「卷N」开头格式的另一用途）。
    private static final Pattern VOLUME_END_MARKER_PATTERN = Pattern.compile(
            "第?[零一二三四五六七八九十百千万0-9]+卷");

    // 两行之间（不含端点）是否存在「（第X卷完）」之类的卷结束标记，用于判定是否跨卷重新编号。
    // 调用方必须保证 toLine - fromLine 较小（先做过行距短路判断），否则不要调用本方法。
    private static boolean hasVolumeMarkerBetween(String[] lines, int fromLine, int toLine) {
        if (lines == null || toLine <= fromLine + 1) return false;
        for (int i = fromLine + 1; i < toLine; i++) {
            String s = lines[i].trim();
            if (s.length() <= 30 && VOLUME_END_MARKER_PATTERN.matcher(s).find()) return true;
        }
        return false;
    }

    // 规范化标题（去除 Markdown 符号、标点、空格，转小写），供去重比较使用。
    private static String normalizeTitle(String raw) {
        String cleaned = raw.replaceFirst("^#+\\s*", "").replaceFirst("^[*\\-]+\\s*", "").trim();
        return cleaned.replaceAll("\\p{Punct}", "").replaceAll("\\s+", "").toLowerCase();
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
            
            // 读取所有 zip 条目。
            // 普通 EPUB 走 ZipFile（对 STORE 模式的 mimetype 最稳）；
            // 多看等混淆 EPUB 常把 mimetype 在中央目录写两遍，Android 的 java.util.zip.ZipFile
            // 遇重复条目名会抛 "Duplicate entry name: mimetype" 导致整本解析失败。
            // readZipEntries 捕获该异常后回退到 ZipInputStream 流式读取（不校验重名，按先到先得去重）。
            Map<String, byte[]> zipEntries = readZipEntries(epubFile);
            
            // 如果是临时文件，删除它
            if ("content".equals(scheme) && epubFile != null && epubFile.exists()) {
                epubFile.delete();
            }
            
            android.util.Log.d("LocalBookParser", "Successfully loaded " + zipEntries.size()
                    + " ZIP entries using ZipFile");

            // 解析 container.xml 找到根文件
            String rootfilePath = null;
            byte[] containerData = zipEntries.get("META-INF/container.xml");
            
            // 调试：列出所有ZIP条目，方便排查
            Log.d("LocalBookParser", "EPUB ZIP entries count: " + zipEntries.size());
            
            // 新增：检查ZIP条目数量，如果太少说明文件损坏
            if (zipEntries.size() < 3) {
                Log.e("LocalBookParser", "EPUB file is too small or corrupted! Only "
                        + zipEntries.size() + " entries found.");
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
                
                // 方法1: 标准 full-path 属性匹配
                Matcher m = Pattern.compile("full-path=\"([^\"]+)\"").matcher(containerXml);
                if (m.find()) {
                    rootfilePath = m.group(1);
                    Log.d("LocalBookParser", "Method 1 found rootfile: " + rootfilePath);
                }
                
                // 方法2: 尝试带命名空间的匹配（如 xmlns:opf="..."）
                if (rootfilePath == null) {
                    Matcher nsM = Pattern.compile("(?:opf:)?full-path=\"([^\"]+)\"").matcher(containerXml);
                    if (nsM.find()) {
                        rootfilePath = nsM.group(1);
                        Log.d("LocalBookParser", "Method 2 (namespace) found rootfile: " + rootfilePath);
                    }
                }
                
                // 方法3: 尝试单引号
                if (rootfilePath == null) {
                    Matcher singleQuoteM = Pattern.compile("full-path='([^']+)\'").matcher(containerXml);
                    if (singleQuoteM.find()) {
                        rootfilePath = singleQuoteM.group(1);
                        Log.d("LocalBookParser", "Method 3 (single quote) found rootfile: " + rootfilePath);
                    }
                }
                
                // 方法4: 查找常见的OPF文件名模式
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
                
                // 方法5: 查找任何 .opf 文件
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
            Matcher titleM = Pattern.compile("<dc:title[^>]*>([^<]+)</dc:title>",
                    Pattern.CASE_INSENSITIVE).matcher(opfXml);
            if (titleM.find()) {
                info.title = cleanHtmlText(titleM.group(1)).trim();
            }
            
            // 尝试其他可能的书名标签
            if (info.title.equals("未命名")) {
                Matcher altTitleM = Pattern.compile("<title[^>]*>([^<]+)</title>",
                        Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (altTitleM.find()) {
                    info.title = cleanHtmlText(altTitleM.group(1)).trim();
                }
            }
            
            Matcher authorM = Pattern.compile("<dc:creator[^>]*>([^<]+)</dc:creator>",
                    Pattern.CASE_INSENSITIVE).matcher(opfXml);
            if (authorM.find()) {
                info.author = cleanHtmlText(authorM.group(1)).trim();
            }
            
            // 尝试其他可能的作者标签
            if (info.author.equals("未知作者")) {
                Matcher altAuthorM = Pattern.compile("<meta[^>]*name=['\"](?:author|creator)['\"][^>]*content=['\"]([^'\"]+)['\"]",
                        Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (altAuthorM.find()) {
                    info.author = cleanHtmlText(altAuthorM.group(1)).trim();
                }
            }

            // ========== 简介提取（来源1：OPF 元数据）==========
            // EPUB2 标准是 <dc:description>；部分生成器改用 <meta name="description" content="…">。
            // 这里只作为「首选来源」，很多网文 epub（如 EasyPub 生成的）根本没有该字段，
            // 此时留空，稍后在章节循环里从「内容简介」章截获（见来源2）。
            Matcher descM = Pattern.compile("<dc:description[^>]*>([\\s\\S]*?)</dc:description>",
                    Pattern.CASE_INSENSITIVE).matcher(opfXml);
            if (descM.find()) {
                info.intro = cleanChapterContent(descM.group(1)).trim();
            }
            if (info.intro.length() < INTRO_MIN_TEXT_LEN) {
                Matcher altDescM = Pattern.compile("<meta[^>]*name=['\"]description['\"][^>]*content=['\"]([^'\"]*)['\"]",
                        Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (altDescM.find()) {
                    info.intro = cleanChapterContent(altDescM.group(1)).trim();
                }
            }
            if (info.intro.length() < INTRO_MIN_TEXT_LEN) {
                info.intro = "";
            }

            // ========== 封面提取 ==========
            String coverHref = null;
            String coverMediaType = null;
            
            // 方法1: EPUB3标准 - 查找 properties="cover-image" 的 item
            Matcher coverItemMatcher = Pattern.compile("<item[^>]+id=\"([^\"]+)\"[^>]+href=\"([^\"]+)\"[^>]*properties=\"cover-image\"[^>]*>",
                    Pattern.CASE_INSENSITIVE).matcher(opfXml);
            if (coverItemMatcher.find()) {
                coverHref = coverItemMatcher.group(2);
                String itemTag = coverItemMatcher.group(0);
                Matcher mediaMatcher = Pattern.compile("media-type=\"([^\"]+)\"").matcher(itemTag);
                if (mediaMatcher.find()) coverMediaType = mediaMatcher.group(1);
            }
            
            // 方法2: 查找 id="cover" / "cover-image" 的 item
            // 同样只在指向**图片**时采纳：有的书里 id="cover" 挂的是封面页 xhtml，
            //    直接采用会重演「封面被 xhtml 抢占 → 真正的 jpeg 查不到」的问题。
            if (coverHref == null) {
                Matcher coverIdMatcher = Pattern.compile("<item[^>]+id=\"(cover|cover-image|Cover)\"[^>]+href=\"([^\"]+)\"[^>]*>",
                        Pattern.CASE_INSENSITIVE).matcher(opfXml);
                while (coverIdMatcher.find()) {
                    String href = coverIdMatcher.group(2);
                    String itemTag = coverIdMatcher.group(0);
                    Matcher mediaMatcher = Pattern.compile("media-type=\"([^\"]+)\"",
                            Pattern.CASE_INSENSITIVE).matcher(itemTag);
                    String mt = mediaMatcher.find() ? mediaMatcher.group(1) : null;
                    boolean isImg = isImageHref(href) || (mt != null && mt.toLowerCase().startsWith("image/"));
                    if (!isImg) continue;
                    coverHref = href;
                    coverMediaType = mt;
                    break;
                }
            }
            
            // 方法3: 从 guide 中查找 type="cover" 或 type="other.ms-coverimage"
            // guide 里的 cover 引用常常指向一个「封面页 xhtml」（如 cover.xhtml），而不是图片本身。
            //    若直接采用，会导致真正的图片（meta name="cover" 指向的 jpeg）永远查不到，
            //    且把 xhtml 页面的字节当成图片保存 → 封面空白/破图。
            //    因此这里只在引用确实是图片扩展名时才采纳。
            if (coverHref == null) {
                String guideHref = null;
                Matcher guideMatcher = Pattern.compile("<reference[^>]+type=\"(?:cover|other\\.ms-coverimage)\"[^>]+href=\"([^\"]+)\"",
                        Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (guideMatcher.find()) {
                    guideHref = guideMatcher.group(1);
                } else {
                    // 尝试另一种顺序
                    Matcher guideMatcher2 = Pattern.compile("<reference[^>]+href=\"([^\"]+)\"[^>]+type=\"(?:cover|other\\.ms-coverimage)\"",
                            Pattern.CASE_INSENSITIVE).matcher(opfXml);
                    if (guideMatcher2.find()) {
                        guideHref = guideMatcher2.group(1);
                    }
                }
                if (guideHref != null && isImageHref(guideHref)) {
                    coverHref = guideHref;
                }
            }
            
            // 方法4: 从 meta 标签查找封面ID
            if (coverHref == null) {
                Matcher metaCoverMatcher = Pattern.compile("<meta[^>]+name=\"cover\"[^>]+content=\"([^\"]+)\"",
                        Pattern.CASE_INSENSITIVE).matcher(opfXml);
                if (metaCoverMatcher.find()) {
                    String coverId = metaCoverMatcher.group(1);
                    // 根据ID查找对应的href
                    Matcher itemByIdMatcher = Pattern.compile("<item[^>]+id=\"" + Pattern.quote(coverId)
                            + "\"[^>]+href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(opfXml);
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

            // 方法6（兜底）: 在 manifest 里找「id 或 href 含 cover 且是图片」的 item。
            // 覆盖「meta name="cover" 缺失 / guide 指向 xhtml 封面页」的情况。
            if (coverHref == null) {
                Matcher anyImgM = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                String best = null;
                while (anyImgM.find()) {
                    String tag = anyImgM.group(0);
                    Matcher hrefM = Pattern.compile("href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(tag);
                    if (!hrefM.find()) continue;
                    String href = hrefM.group(1);
                    if (!isImageHref(href)) continue;
                    Matcher idM = Pattern.compile("id=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(tag);
                    String id = idM.find() ? idM.group(1) : "";
                    String lower = (id + " " + href).toLowerCase();
                    if (lower.contains("cover")) {
                        best = href;
                        Matcher mediaM = Pattern.compile("media-type=\"([^\"]+)\"",
                                Pattern.CASE_INSENSITIVE).matcher(tag);
                        if (mediaM.find()) coverMediaType = mediaM.group(1);
                        break;
                    }
                }
                coverHref = best;
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
                    
                    if (coverData != null && coverData.length > 0 && looksLikeImage(coverData)) {
                        // 确定扩展名
                        String ext = getFileExtension(coverHref, coverMediaType);
                        String coverFileName = "cover_" + bookId + ext;
                        File coverFile = new File(context.getFilesDir(), coverFileName);
                        
                        deleteOldCover(context, bookId);
                        
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

            // 解析 spine 顺序
            List<String> idrefList = new ArrayList<>();
            
            // 首先尝试从NCX文件获取目录结构（EPUB2）
            Map<String, String> ncxTitleMap = new HashMap<>();
            Map<String, String> chapterVolumeMap = new HashMap<>();
            List<String> volumeTitles = new ArrayList<>();
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
                    Matcher navPointM = Pattern.compile("<navPoint[^>]*>(.*?)</navPoint>",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(ncxXml);
                    while (navPointM.find()) {
                        String navPoint = navPointM.group(1);
                        Matcher textM = Pattern.compile("<text[^>]*>([^<]+)</text>",
                                Pattern.CASE_INSENSITIVE).matcher(navPoint);
                        Matcher srcM = Pattern.compile("<content[^>]*src=\"([^\"]+)\"",
                                Pattern.CASE_INSENSITIVE).matcher(navPoint);
                        if (textM.find() && srcM.find()) {
                            String title = cleanHtmlText(textM.group(1)).trim();
                            String src = srcM.group(1);
                            // 提取文件名部分作为key
                            String fileName = epubBaseName(src);
                            ncxTitleMap.put(fileName, title);
                        }
                    }

                    // 分卷检测：解析 NCX 嵌套结构，把每个章节映射到所属卷
                    parseNcxVolumes(ncxXml, chapterVolumeMap, volumeTitles);
                    info.volumes = volumeTitles;
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
                            Matcher navM = Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
                                    Pattern.CASE_INSENSITIVE).matcher(navHtml);
                            while (navM.find()) {
                                String href = navM.group(1);
                                String title = cleanHtmlText(navM.group(2)).trim();
                                String fileName = epubBaseName(href);
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
            Matcher spineM = Pattern.compile("<itemref[^>]*idref=\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE).matcher(opfXml);
            while (spineM.find()) {
                idrefList.add(spineM.group(1));
            }
            
            // 如果spine为空，尝试从manifest中获取所有项目作为备选
            if (idrefList.isEmpty()) {
                Matcher manifestM = Pattern.compile("<item[^>]+id=\"([^\"]+)\"[^>]+href=\"([^\"]+)\"[^>]*>",
                        Pattern.CASE_INSENSITIVE).matcher(opfXml);
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
            Matcher itemM = Pattern.compile("<item[^>]+id=\"([^\"]+)\"[^>]+href=\"([^\"]+)\"[^>]*>",
                    Pattern.CASE_INSENSITIVE).matcher(opfXml);
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
            // 全书共享的样式表 / 图片缓存：同一份资源只读取、作用域化、编码一次。
            //    否则一本书 800 章会把同一份 15KB 公共 CSS 重复内联 800 次，
            //    同一张 200KB 插图重复 base64 编码 800 次（产物膨胀到数百 MB，导入长时间卡死）
            Map<String, String> cssCache = new HashMap<>();
            Map<String, String> imgCache = new HashMap<>();
            for (String idref : idrefList) {
                String href = idToHref.get(idref);
                if (href == null) continue;
                
                // 处理相对路径
                String fullPath = basePath + href;
                // 规范化路径
                while (fullPath.contains("../")) {
                    fullPath = fullPath.replaceFirst("[^/]+/\\.\\./", "");
                }
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
                
                String fileName = epubBaseName(href);
                
                // ============================================
                // 新增第0步：先过滤前置文件（封面/版权/说明等）
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
                Matcher headingMatcher = RE_HEADING.matcher(html);
                if (headingMatcher.find()) {
                    headingTitle = cleanHtmlText(headingMatcher.group(2)).trim();
                }
                
                // ---- 来源3: HTML title 标签 ----
                String titleTagTitle = null;
                Matcher titleMatcher = RE_TITLE_TAG.matcher(html);
                if (titleMatcher.find()) {
                    titleTagTitle = titleMatcher.group(1).trim();
                }
                
                // ============================================
                // 选择最佳标题：优先选"包含章节号且非通用词"的那个
                // ============================================
                
                // 判断标题是否"高质量"（包含章节号前缀 或 非通用词且足够长）
                java.util.regex.Pattern hasChapterNum = RE_HAS_CHAPTER_NUM;
                
                java.util.function.Function<String, Integer> qualityScore = (t) -> {
                    if (t == null || t.isEmpty()) return Integer.MIN_VALUE;  // 该来源没有标题 → 直接淘汰
                    int score = 0;
                    String lower = t.toLowerCase().trim();
                    if (RE_PLACEHOLDER_TITLE.matcher(lower).matches()) score -= 100;  // 生成器占位标题（chapter 3 - 0）
                    if (hasChapterNum.matcher(t).find()) score += 10;  // 有章节号前缀 +10（最重要！）
                    if (GENERIC_TITLES.contains(lower)) score -= 20;     // 通用占位词 -20
                    if (t.length() >= 6) score += 2;                    // 有实质内容 +2
                    if (t.length() >= 12) score += 3;                   // 更长 +3
                    return score;
                };
                
                // 选分数最高的：文本质量分 + 来源权重（NCX > h1-h6 > <title>）
                int bestScore = Integer.MIN_VALUE;
                String bestTitle = null;
                String[] titleCandidates = {ncxTitle, headingTitle, titleTagTitle};
                for (int si = 0; si < titleCandidates.length; si++) {
                    int s = qualityScore.apply(titleCandidates[si]);
                    if (s == Integer.MIN_VALUE) continue;
                    s += TITLE_SOURCE_WEIGHT[si];
                    if (s > bestScore) {
                        bestScore = s;
                        bestTitle = titleCandidates[si];
                    }
                }
                
                chapterTitle = bestTitle;
                
                // 兜底：没有任何有效来源，或选出来的仍是生成器占位标题（"chapter 3 - 0" 之类）
                // 时，用「第N章」这个位置化标题，至少不会把占位词当成章节名显示给用户。
                if (chapterTitle == null || chapterTitle.isEmpty()
                        || RE_PLACEHOLDER_TITLE.matcher(chapterTitle.trim().toLowerCase()).matches()) {
                    chapterTitle = "第" + (chapterIndex + 1) + "章";
                }
                
                Log.d("LocalBookParser", "[EPUB] fileName=" + fileName + 
                    " ncx=\"" + ncxTitle + "\" heading=\"" + headingTitle + "\" titleTag=\"" + titleTagTitle + "\"" +
                    " => FINAL=\"" + chapterTitle + "\"");
                
                // 简介提取（来源2：EPUB 里的「内容简介」章）
                // 必须放在 shouldSkipChapter 之前：这类章节紧接着就会被当作非正文剔除，
                //    等跳过之后再来取就拿不到了（EasyPub 生成的书即 chapter0.html，NCX 标题"内容简介"）。
                if (info.intro.length() < INTRO_MIN_TEXT_LEN && isIntroChapterTitle(chapterTitle)) {
                    String introText = cleanChapterContent(bodyOf(html));
                    if (introText.length() >= INTRO_MIN_TEXT_LEN) {
                        info.intro = introText;
                        Log.d("LocalBookParser", "[EPUB] 截获简介章: \"" + chapterTitle + "\" len=" + introText.length());
                    }
                }

                // 过滤非正文章节（封面、目录、简介、版权信息等）——标题兜底过滤
                if (shouldSkipChapter(chapterTitle, html)) {
                    Log.d("LocalBookParser", "Skipping non-content chapter: " + chapterTitle);
                    continue;
                }

                // 改进的内容清理逻辑
                // （规则与顺序完全不变，只是把 31 条正则改成预编译后复用，
                //   避免每章 31 次 Pattern.compile —— 真机上这是每章数毫秒的开销）
                String content = cleanChapterContent(html);
                                
                // 移除内容开头的重复标题行/标题前缀
                // 策略：只在前 5 行检查。独立标题行 → 整行删除；标题+正文混在一起的行 → 只去掉开头的标题部分
                if (chapterTitle != null && !chapterTitle.isEmpty()) {
                    // 归一化标题（去掉空白，便于匹配）
                    String normalizedTitle = RE_WS_PLUS.matcher(chapterTitle.toLowerCase()).replaceAll("");
                    
                    // 构造组合模式：可选的"第X章/卷X/chapter X"前缀 + 章节标题内容
                    // 例如 chapterTitle="开局师父没了" → 能匹配 "第一章 开局师父没了"
                    //     chapterTitle="第一章 开局师父没了" → 能匹配 "第一章 开局师父没了" 本身
                    //     chapterTitle="第一章 开局师父没了" → 也能匹配纯"开局师父没了"
                    String tmp = RE_TITLE_PREFIX_CHAPTER.matcher(chapterTitle).replaceFirst("");
                    tmp = RE_TITLE_PREFIX_VOLUME.matcher(tmp).replaceFirst("");
                    String titleWithoutPrefix = RE_TITLE_PREFIX_EN.matcher(tmp).replaceFirst("").trim();
                    String normalizedTitleNoPrefix = RE_WS_PLUS.matcher(titleWithoutPrefix.toLowerCase()).replaceAll("");
                    
                    // 组合前缀正则每章只编译一次（原来放在逐行循环里，最多一章编译 5 次）
                    java.util.regex.Pattern combinedPrefixPattern = java.util.regex.Pattern.compile(
                            COMBINED_PREFIX_BODY + java.util.regex.Pattern.quote(titleWithoutPrefix),
                            java.util.regex.Pattern.CASE_INSENSITIVE);
                    
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
                        String normalizedLine = RE_WS_PLUS.matcher(trimmedLine.toLowerCase()).replaceAll("");
                        
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
                            java.util.regex.Matcher combinedPrefixM = combinedPrefixPattern.matcher(trimmedLine);
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
                                Log.d("LocalBookParser", "Stripping title prefix from line, kept: "
                                        + rest.substring(0, Math.min(rest.length(), 20)) + "...");
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
                    Chapter ch = new Chapter(chapterIndex, chapterTitle, content);
                    // 生成保留样式的 HTML（供 Android 阅读器 HTML 渲染模式使用；失败则回退纯文本）
                    try {
                        String chapterDir = dirOf(resolveInZip(href, basePath));
                        String htmlContent = buildEpubHtmlContent(html, chapterDir, zipEntries, cssCache, imgCache);
                        ch.htmlContent = htmlContent;
                    } catch (Exception e) {
                        android.util.Log.e("LocalBookParser", "htmlContent 生成失败: " + e.getMessage());
                    }
                    String vol = chapterVolumeMap.get(fileName);
                    if (vol != null && !vol.isEmpty()) {
                        ch.volumeTitle = vol;
                        ch.volumeIndex = volumeTitles.indexOf(vol) + 1;
                    }
                    info.chapters.add(ch);
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
        String c = RE_TAGS.matcher(html).replaceAll("");
        c = RE_HTML_ENTITY_NBSP.matcher(c).replaceAll(" ");
        c = RE_HTML_ENTITY_LT.matcher(c).replaceAll("<");
        c = RE_HTML_ENTITY_GT.matcher(c).replaceAll(">");
        c = RE_HTML_ENTITY_AMP.matcher(c).replaceAll("&");
        c = RE_HTML_ENTITY_QUOT.matcher(c).replaceAll("\"");
        c = RE_HTML_ENTITY_APOS39.matcher(c).replaceAll("'");
        c = RE_HTML_ENTITY_APOS.matcher(c).replaceAll("'");
        return c.trim();
    }

    // ==================== EPUB 章节 HTML（保留样式） ====================

    /**
     * 取文件名（去掉 #fragment 与目录前缀），用于 NCX src 与 manifest href 的匹配。
     * 例如 "ch1.xhtml#p1" / "OEBPS/ch1.xhtml" → "ch1.xhtml"
     */
    private static String epubBaseName(String path) {
        if (path == null) return "";
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** 取路径所在目录（含末尾 '/'）；传入 "a/b/c.xhtml" 返回 "a/b/" */
    private static String dirOf(String href) {
        if (href == null) return "";
        int slash = href.lastIndexOf('/');
        if (slash < 0) return "";
        return href.substring(0, slash + 1);
    }

    /**
     * 把相对引用（可能带 #fragment、../ 上级目录、前导 /）解析为 zip 内的完整 key。
     * chapterDir 是章节文件所在目录（已含末尾 '/'）。
     */
    private static String resolveInZip(String ref, String chapterDir) {
        if (ref == null) return "";
        int hash = ref.indexOf('#');
        if (hash >= 0) ref = ref.substring(0, hash);
        if (ref.isEmpty()) return "";

        String base = (chapterDir == null) ? "" : chapterDir;
        if (ref.startsWith("/")) {
            return ref.substring(1); // 绝对路径：去掉前导 /
        }
        // 处理 ../ 与 ./ 
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String seg : (base + ref).split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /**
     * 从 EPUB 章节 XHTML 提取"保留样式"的 HTML 片段：
     *  - 收集 <style> 与 <link rel="stylesheet"> 的内联/内嵌 CSS
     *  - 取 <body> 内部内容（无 body 则去 head 后整体）
     *  - 把 ≤512KB 的图片内联为 base64 data: URI（过大跳过，控制 SP 体积）
     *  - 用 <div class="epub-chapter"> 包裹
     * 任何异常都不抛出，返回 null 由调用方回退纯文本。
     */
    private static String buildEpubHtmlContent(String xhtml, String chapterDir, Map<String, byte[]> zipEntries,
                                               Map<String, String> cssCache, Map<String, String> imgCache) {
        if (xhtml == null || xhtml.isEmpty()) return null;
        StringBuilder css = new StringBuilder();

        // 1. <style> 标签内联 CSS
        //    按「样式内容」缓存**作用域化之后**的结果：同一段内联样式在多章重复出现时只处理一次
        Matcher styleM = RE_STYLE_BLOCK.matcher(xhtml);
        while (styleM.find()) {
            String block = styleM.group(1);
            String key = INLINE_KEY_PREFIX + block;
            String scopedInline = cssCache.get(key);
            if (scopedInline == null) {
                scopedInline = scopeEpubCss(block);
                cssCache.put(key, scopedInline);
            }
            css.append(scopedInline).append("\n");
        }

        // 2. <link rel="stylesheet" href="..."> 内嵌 CSS
        //    以「解析后的 zip 路径」为键，缓存**作用域化之后**的结果。
        //    原来这里只缓存了「解码后的原文」，导致 scopeEpubCss() 仍然每章都要在整份
        //    样式表（本书 83KB、254 个选择器）上重跑一遍——真机实测单次 1.5~2.6 秒，
        //    1800 章 ≈ 98 分钟，正是「导入一个小时还在导入」的根因。现在每份样式表全书只算一次。
        Matcher linkM = RE_STYLESHEET_LINK.matcher(xhtml);
        while (linkM.find()) {
            String tag = linkM.group(0);
            Matcher hrefM = RE_HREF_ATTR.matcher(tag);
            if (hrefM.find()) {
                String cssPath = resolveInZip(hrefM.group(1), chapterDir);
                String cacheKey = LINKED_KEY_PREFIX + cssPath;
                String cached = cssCache.get(cacheKey);
                if (cached != null) {
                    css.append(cached);
                    continue;
                }
                byte[] data = zipEntries.get(cssPath);
                if (data != null) {
                    String decoded = "";
                    try {
                        decoded = new String(data, detectCharset(data));
                    } catch (Exception ignore) { /* 编码失败不影响主流程 */ }
                    String scoped = scopeEpubCss(decoded);
                    cssCache.put(cacheKey, scoped);
                    css.append(scoped);
                } else {
                    // 也缓存未命中，避免同一缺失文件被反复查表
                    cssCache.put(cacheKey, "");
                }
            }
        }

        // 3. 提取 <body> 内容（无 body 则去 head 后整体）
        String body = bodyOf(xhtml);

        // 4. 内联图片（同样按 zip 路径做全书级缓存，避免同一张图被多章重复编码成 base64）
        body = processEpubImages(body, chapterDir, zipEntries, imgCache);

        // 5. CSS 作用域隔离：reader.html 是「单文档」，书里的 body / html / div / * / a
        //    等选择器会命中阅读器自身的 DOM（#htmlContent、#htmlColumns、#flipbookContainer…），
        //    必须把选择器限定到包裹正文的 .epub-chapter 之内。
        //    注意这里是「逐块」做的作用域化（上面的片段已经在缓存里作用域化过了），
        //    不能再对拼接结果整体再跑一次——那会让每章都重新解析整份 CSS。
        String scopedCss = css.toString();

        // 6. 组装（此处不包裹 epub-chapter：由 reader.html 的 loadHtmlContent 统一包裹，避免重复嵌套）
        StringBuilder sb = new StringBuilder();
        if (scopedCss.length() > 0) {
            sb.append("<style>").append(scopedCss).append("</style>");
        }
        sb.append(body);
        return sb.toString();
    }

    /** cssCache 的键前缀：区分「外链样式表(按 zip 路径)」与「内联 <style>(按内容)」 */
    private static final String LINKED_KEY_PREFIX = "L\u0000";
    private static final String INLINE_KEY_PREFIX = "S\u0000";

    /**
     * 给 EPUB 自带 CSS 加作用域（思路同 Zotero reader 的 CSSRewriter），避免污染 reader.html 自身。
     * 规则：html / body / :root 视作包裹正文的 .epub-chapter；其余选择器一律加后代前缀。
     * 说明：这是「选择器限定」级别的处理，不做完整 CSS 语法解析；
     * 无选择器的规则（@font-face / @keyframes / @page）与以 ';' 结尾的语句（@import / @charset）原样保留。
     */
    private static String scopeEpubCss(String css) {
        if (css == null || css.isEmpty()) return "";
        StringBuilder out = new StringBuilder(css.length() + 256);
        int i = 0;
        int n = css.length();
        while (i < n) {
            // 原样复制注释（其中可能含花括号，不能参与解析）
            if (css.charAt(i) == '/' && i + 1 < n && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                if (end < 0) { out.append(css, i, n); break; }
                out.append(css, i, end + 2);
                i = end + 2;
                continue;
            }
            int open = css.indexOf('{', i);
            if (open < 0) { out.append(css, i, n); break; }
            int close = matchBrace(css, open);
            if (close < 0) { out.append(css, i, n); break; }   // 花括号不配对：整体原样输出，不冒险改写
            String prelude = css.substring(i, open);
            String block = css.substring(open, close + 1);
            // prelude 里可能含着以 ';' 结束的独立语句（@import / @charset），先原样输出
            String head = prelude;
            int semi = head.lastIndexOf(';');
            if (semi >= 0) {
                out.append(head, 0, semi + 1);
                head = head.substring(semi + 1);
            }
            String trimmed = head.trim();
            if (trimmed.isEmpty()) {
                out.append(head).append(block);
            } else if (trimmed.charAt(0) == '@') {
                String lower = trimmed.toLowerCase();
                if (lower.startsWith("@media") || lower.startsWith("@supports") || lower.startsWith("@document")) {
                    // 条件规则：保留条件头，递归处理内部规则
                    int innerOpen = block.indexOf('{');
                    String inner = block.substring(innerOpen + 1, block.length() - 1);
                    out.append(head).append('{').append(scopeEpubCss(inner)).append('}');
                } else {
                    out.append(head).append(block);
                }
            } else {
                out.append(scopeSelectors(head)).append(block);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** 找到与 open 处 '{' 配对的 '}'（考虑嵌套） */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** 按逗号切分选择器组，逐个加作用域前缀 */
    private static String scopeSelectors(String selectorGroup) {
        StringBuilder sb = new StringBuilder(selectorGroup.length() + 32);
        int start = 0;
        for (int i = 0; i <= selectorGroup.length(); i++) {
            if (i < selectorGroup.length() && selectorGroup.charAt(i) != ',') continue;
            String sel = selectorGroup.substring(start, i).trim();
            start = i + 1;
            if (!sel.isEmpty()) sb.append(scopeOneSelector(sel));
            if (i < selectorGroup.length()) sb.append(',');
        }
        return sb.toString();
    }

    /** 单个选择器加作用域：html / body / :root 视作包裹正文的 .epub-chapter，其余加后代前缀 */
    private static String scopeOneSelector(String sel) {
        String s = sel.trim();
        if (s.isEmpty()) return s;
        // 这里原来用 String.replaceFirst(...)，每次调用都会把 "前缀 + mod" 这条长正则**重新编译一遍**。
        //    真机实测该正则编译一次 ~5.7ms，一本书 1800 章 × 每章数百个选择器 → 导入卡近一小时。
        //    改为预编译 Pattern + Matcher.replaceFirst（编译一次，全书复用）。
        Matcher m1 = RE_SCOPE_ROOT.matcher(s);
        if (m1.find()) s = m1.replaceFirst(".epub-chapter");
        // "html body p" 已被上一步换成 ".epub-chapter body p"，多余的 body 层再去掉
        Matcher m2 = RE_SCOPE_EPUB_BODY.matcher(s);
        if (m2.find()) s = m2.replaceFirst(".epub-chapter");
        if (s.startsWith(".epub-chapter")) return s;
        return ".epub-chapter " + s;
    }

    /**
     * 把 body 中的图片引用内联为 base64 data: URI（≤512KB 才内联，过大跳过以控制 SP 体积）。
     * 同时处理 <img src> 与 SVG <image xlink:href/href>。
     */
    private static String processEpubImages(String body, String chapterDir, Map<String, byte[]> zipEntries,
                                            Map<String, String> imgCache) {
        if (body == null) return "";
        // <img ... src="...">（正则预编译，见类顶部 RE_* 常量）
        Matcher imgM = RE_IMG_TAG.matcher(body);
        StringBuffer sb = new StringBuffer();
        while (imgM.find()) {
            String tag = imgM.group(0);
            String attrs = imgM.group(1);
            Matcher srcM = RE_SRC_ATTR.matcher(attrs);
            if (srcM.find()) {
                String src = srcM.group(1);
                String resolved = resolveInZip(src, chapterDir);
                String dataUri = cachedImageUri(resolved, imgCache, zipEntries);
                if (dataUri != null && !dataUri.isEmpty()) {
                    String newTag = tag.replace("src=\"" + src + "\"", "src=\"" + dataUri + "\"");
                    imgM.appendReplacement(sb, Matcher.quoteReplacement(newTag));
                    continue;
                }
            }
            imgM.appendReplacement(sb, Matcher.quoteReplacement(tag));
        }
        imgM.appendTail(sb);
        String out = sb.toString();

        // SVG <image ... xlink:href="..." /> 或 href="..."
        Matcher svgM = RE_SVG_IMAGE_TAG.matcher(out);
        StringBuffer sb2 = new StringBuffer();
        while (svgM.find()) {
            String tag = svgM.group(0);
            String attrs = svgM.group(1);
            Matcher hrefM = RE_HREF_OR_XLINK.matcher(attrs);
            if (hrefM.find()) {
                String ref = hrefM.group(2);
                String resolved = resolveInZip(ref, chapterDir);
                String dataUri = cachedImageUri(resolved, imgCache, zipEntries);
                if (dataUri != null && !dataUri.isEmpty()) {
                    String newAttr = hrefM.group(1) + "=\"" + dataUri + "\"";
                    String newTag = tag.replace(hrefM.group(0), newAttr);
                    svgM.appendReplacement(sb2, Matcher.quoteReplacement(newTag));
                    continue;
                }
            }
            svgM.appendReplacement(sb2, Matcher.quoteReplacement(tag));
        }
        svgM.appendTail(sb2);
        return sb2.toString();
    }

    /**
     * 取图片的 data: URI（带全书级缓存）：同一张图被多章引用时只编码一次。
     * 否则 200KB 的插图跨 800 章引用会被重复编码成 200MB+ 的内联 base64，
     * 解析阶段就会长时间卡在导入中。
     * 返回空串表示不内联该图（缺失，或超过 512KB 上限）。
     */
    private static String cachedImageUri(String resolved, Map<String, String> imgCache,
            Map<String, byte[]> zipEntries) {
        if (resolved == null) return "";
        if (imgCache.containsKey(resolved)) return imgCache.get(resolved);
        String uri = "";
        byte[] data = zipEntries.get(resolved);
        if (data != null && data.length <= 512 * 1024) {
            uri = embedImage(data, resolved);
        }
        imgCache.put(resolved, uri);
        return uri;
    }

    /** 把图片字节编码为 base64 data: URI */
    private static String embedImage(byte[] data, String fallbackName) {
        if (data == null) return "";
        String mime = guessMime(fallbackName, data);
        String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
        return "data:" + mime + ";base64," + b64;
    }

    /** 根据文件名后缀与字节头猜测 MIME（用于 data: URI） */
    private static String guessMime(String name, byte[] data) {
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".bmp")) return "image/bmp";
            if (lower.endsWith(".svg")) return "image/svg+xml";
        }
        if (data != null && data.length >= 4) {
            if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50) return "image/png";
            if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) return "image/jpeg";
            if (data[0] == (byte) 'G' && data[1] == (byte) 'I' && data[2] == (byte) 'F') return "image/gif";
            if (data[0] == (byte) 'R' && data[1] == (byte) 'I' && data[2] == (byte) 'F') return "image/webp";
        }
        return "image/png";
    }

    // ==================== 本地书 HTML 文件缓存（避免写入 SharedPreferences 撑爆 SP） ====================

    /** 返回某本书的 HTML 缓存目录：filesDir/local_book_html/<bookId> */
    static File getHtmlCacheDir(Context context, long bookId) {
        return new File(context.getFilesDir(), "local_book_html" + File.separator + bookId);
    }

    /**
     * 书籍内容容器文件名（位于 {@link #getHtmlCacheDir} 目录内）。
     * 全书章节的「保留样式 HTML」与「纯文本正文」都装进这**一个**文件。
     */
    private static final String CONTAINER_NAME = "chapters.bin";
    /** 容器魔数 'LBK1'，用于校验文件未被损坏/替换 */
    private static final int CONTAINER_MAGIC = 0x4C424B31;
    /**
     * 容器格式：magic(4) + blockCount(4) + (blockCount+1) 个 8 字节绝对偏移 + 各块数据。
     * 第 i 章的 HTML 是第 2i 块，正文是第 2i+1 块。
     */

    /**
     * 把整本书所有章节的 HTML 与正文**一次性**写入单个容器文件。
     *
     * <p>为什么不按章各写一个文件：实测同一份 37MB 内容，写成 3600 个文件需 ~75 秒，
     * 写成 1 个文件只要 ~30 毫秒——瓶颈是**文件个数**（每次 create/close 在 Android 上
     * 都有 dentry + fsync 开销），不是数据量。之前 1800 章的书籍因此卡在「正在导入」一分多钟。
     *
     * <p>写入采用「一次编码 + 缓冲输出 + 回填头部」：先把偏移量算好写在文件头，
     * 再顺序写数据，避免把全书字节同时驻留内存。
     */
    public static void writeBookChapters(Context context, long bookId, List<Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return;
        File dir = getHtmlCacheDir(context, bookId);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, CONTAINER_NAME);
        int n = chapters.size() * 2;
        long headerEnd = 8L + (long) (n + 1) * 8L;
        long[] offs = new long[n + 1];
        try {
            long pos = headerEnd;
            try (java.io.BufferedOutputStream out =
                         new java.io.BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
                // 先占位文件头（偏移量要等写完才知道，稍后回填）
                out.write(new byte[(int) headerEnd]);
                for (int i = 0; i < chapters.size(); i++) {
                    Chapter ch = chapters.get(i);
                    byte[] hb = utf8Bytes(ch.htmlContent);
                    offs[2 * i] = pos;
                    pos += hb.length;
                    out.write(hb);

                    byte[] tb = utf8Bytes(ch.content);
                    offs[2 * i + 1] = pos;
                    pos += tb.length;
                    out.write(tb);
                }
                offs[n] = pos;
            }
            // 回填文件头
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
                raf.seek(0);
                raf.writeInt(CONTAINER_MAGIC);
                raf.writeInt(n);
                for (long o : offs) raf.writeLong(o);
            }
        } catch (Exception e) {
            android.util.Log.e("LocalBookParser", "writeBookChapters 失败 bookId=" + bookId + ": " + e.getMessage());
            // 写了一半的容器留着重读会被判为损坏，直接删掉更干净（阅读器会回退到 SP 旧数据）
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static byte[] utf8Bytes(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** 读取容器中第 idx 块；文件缺失/损坏/越界返回 null */
    private static byte[] readContainerBlock(File f, int idx) {
        if (idx < 0 || !f.exists()) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            if (raf.readInt() != CONTAINER_MAGIC) return null;
            int n = raf.readInt();
            if (idx >= n) return null;
            // 头部 = magic(4) + blockCount(4)，其后紧跟偏移数组；offsets[k] 位于 8 + k*8
            raf.seek(8L + (long) idx * 8L);
            long start = raf.readLong();
            long end = raf.readLong();
            if (end <= start) return new byte[0];
            // 偏移合理性校验：块长不可能超过文件本身，防止损坏的偏移导致巨额分配
            long len = end - start;
            if (len > f.length()) return null;
            byte[] buf = new byte[(int) len];
            raf.seek(start);
            raf.readFully(buf);
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取单章 HTML；缺失返回空串（阅读器回退纯文本）。兼容旧版逐章 <index>.html 缓存 */
    public static String readChapterHtml(Context context, long bookId, int index) {
        File dir = getHtmlCacheDir(context, bookId);
        byte[] b = readContainerBlock(new File(dir, CONTAINER_NAME), index * 2);
        if (b != null) {
            if (b.length == 0) return "";
            try {
                return new String(b, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
        // 旧版缓存兜底（升级前导入的书）
        File legacy = new File(dir, index + ".html");
        if (!legacy.exists()) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(legacy), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 读取单章纯文本正文；缺失返回空串。兼容旧版逐章 <index>.txt 缓存 */
    public static String readChapterText(Context context, long bookId, int index) {
        File dir = getHtmlCacheDir(context, bookId);
        byte[] b = readContainerBlock(new File(dir, CONTAINER_NAME), index * 2 + 1);
        if (b != null) {
            if (b.length == 0) return "";
            try {
                return new String(b, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
        // 旧版缓存兜底（升级前导入的书）
        File legacy = new File(dir, index + ".txt");
        if (!legacy.exists()) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(legacy), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 删除某本本地书的 HTML 缓存目录（filesDir/local_book_html/&lt;bookId&gt;）。
     * 删除本地书时调用，避免重导入生成新 bookId 后旧目录残留成为孤儿文件。
     * 递归删除整棵目录；失败仅记录日志，不抛出。
     */
    public static void deleteHtmlCache(Context context, long bookId) {
        File dir = getHtmlCacheDir(context, bookId);
        if (!dir.exists()) return;
        try {
            deleteRecursively(dir);
        } catch (Exception e) {
            android.util.Log.e("LocalBookParser", "deleteHtmlCache 失败 bookId=" + bookId + ": " + e.getMessage());
        }
    }

    /** 递归删除文件或目录 */
    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 清扫孤儿 HTML 缓存目录：local_book_html/ 下所有目录名（bookId），
     * 凡是不在 local_books 中的一律删除。
     * 用于兜住"重导入同一本书（新 bookId）但未先删除旧书"导致的残留。
     * 建议在后台线程调用；失败仅记录日志，不抛出。
     */
    public static void cleanupOrphanHtmlCache(Context context) {
        File root = new File(context.getFilesDir(), "local_book_html");
        if (!root.exists() || !root.isDirectory()) return;

        SharedPreferences sp = context.getSharedPreferences("local_books", Context.MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        Set<String> validIds = new HashSet<>();
        for (int i = 0; i < count; i++) {
            long id = sp.getLong("book_id_" + i, 0);
            if (id > 0) validIds.add(String.valueOf(id));
        }

        File[] dirs = root.listFiles();
        if (dirs == null) return;
        int removed = 0;
        for (File d : dirs) {
            if (d == null || !d.isDirectory()) continue;
            if (!validIds.contains(d.getName())) {
                try {
                    deleteRecursively(d);
                    removed++;
                } catch (Exception e) {
                    android.util.Log.e("LocalBookParser", "清理孤儿 HTML 缓存失败: " + d.getName());
                }
            }
        }
        if (removed > 0) {
            android.util.Log.i("LocalBookParser", "已清理 " + removed + " 个孤儿 HTML 缓存目录");
        }
    }

    // ==================== EPUB 分卷检测（NCX 嵌套结构） ====================

    /** 标题是否像"卷/部/篇"：第一卷、卷三、Part 2、Volume 1 等 */
    private static boolean isVolumeLabel(String t) {
        if (t == null || t.isEmpty()) return false;
        return RE_VOLUME_LABEL.matcher(t.trim()).find();
    }

    /**
     * 解析 NCX 的 navPoint 嵌套结构，把章节文件名映射到所属分卷，并收集有序卷名。
     * 卷的判定：标题命中卷正则，或自身拥有子 navPoint（即目录层级中的上层节点）。
     */
    private static void parseNcxVolumes(String ncxXml, Map<String, String> fileToVol, List<String> vols) {
        if (ncxXml == null) return;
        parseNavLevel(ncxXml, "", fileToVol, vols);
    }

    private static void parseNavLevel(String xml, String curVol, Map<String, String> fileToVol, List<String> vols) {
        int idx = 0;
        while (true) {
            int open = xml.indexOf("<navPoint", idx);
            if (open < 0) break;
            int tagEnd = xml.indexOf('>', open);
            if (tagEnd < 0) break;
            int close = matchingNavClose(xml, tagEnd + 1);
            if (close < 0) break;
            String block = xml.substring(tagEnd + 1, close); // 该 navPoint 内部内容（到其自身的 </navPoint> 为止）
            int childStart = block.indexOf("<navPoint");
            String own = (childStart >= 0) ? block.substring(0, childStart) : block; // 仅自身（不含子节点）
            String label = navLabelText(own);
            String src = navContentSrc(own);
            boolean isVol = isVolumeLabel(label) || childStart >= 0;
            String vol = curVol;
            if (isVol && !label.isEmpty()) {
                vol = label;
                if (!vols.contains(vol)) vols.add(vol);
            }
            if (src != null && !src.isEmpty()) {
                String fn = epubBaseName(src);
                if (!fileToVol.containsKey(fn)) fileToVol.put(fn, vol);
            }
            // 递归处理子节点
            if (childStart >= 0) parseNavLevel(block, vol, fileToVol, vols);
            idx = close + "</navPoint>".length();
        }
    }

    /** 在 block 中找到与当前已打开 navPoint 匹配的右闭合标签位置 */
    private static int matchingNavClose(String xml, int from) {
        int depth = 1;
        int i = from;
        while (i < xml.length()) {
            if (xml.startsWith("<navPoint", i)) {
                // 必须是标签起始（后面是空格或 '>'），避免误匹配 <navPoints
                char c = (i + "<navPoint".length() < xml.length()) ? xml.charAt(i + "<navPoint".length()) : '>';
                if (c == ' ' || c == '>' || c == '\t' || c == '\n' || c == '\r') {
                    depth++;
                    i += "<navPoint".length();
                    continue;
                }
            } else if (xml.startsWith("</navPoint>", i)) {
                depth--;
                if (depth == 0) return i;
                i += "</navPoint>".length();
                continue;
            }
            i++;
        }
        return -1;
    }

    private static String navLabelText(String s) {
        Matcher m = RE_NAV_LABEL.matcher(s);
        if (m.find()) return cleanHtmlText(m.group(1)).trim();
        return "";
    }

    private static String navContentSrc(String s) {
        Matcher m = RE_NAV_CONTENT_SRC.matcher(s);
        if (m.find()) return m.group(1);
        return "";
    }
    
    private static String detectCharset(byte[] data) {
        if (data.length >= 2 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFF) return "UTF-16BE";
        if (data.length >= 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xFE) return "UTF-16LE";
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) return "UTF-8";
        return "UTF-8";
    }

    /**
     * 判断「按 UTF-8 解码后的文本」是否看起来是真实的 UTF-8。
     * 若替换字符（U+FFFD）占总字符比例过高，说明原始字节流其实不是 UTF-8（多半是 GBK 字节被当成
     * UTF-8 解码，每个中文都变 U+FFFD），应改用 GBK 重新解码；反之，正常 UTF-8 文件即便混有个别
     * 坏字符（文本自带少量 U+FFFD 占位），比例也极低，应当保留 UTF-8 解码结果。
     * 阈值 5%：真实 GBK 字节按 UTF-8 解，中文几乎全部成 U+FFFD，比例远高于此。
     */
    private static boolean isUtf8DecodeLikelyValid(String text) {
        if (text == null || text.isEmpty()) return true;
        int fffd = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') fffd++;
        }
        return (fffd * 100L / text.length()) < 5;
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
                if (RE_IMAGE_EXT.matcher(fileExt).matches()) {
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

    /** href 是否为图片（按扩展名判断），用于过滤指向 xhtml 封面页的引用 */
    private static boolean isImageHref(String href) {
        if (href == null) return false;
        String lower = href.toLowerCase();
        int hash = lower.indexOf('#');
        if (hash >= 0) lower = lower.substring(0, hash);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    /** 按字节头判断是否为图片（防止把 xhtml/svg 页面字节当封面保存） */
    private static boolean looksLikeImage(byte[] d) {
        if (d == null || d.length < 4) return false;
        if (d[0] == (byte) 0xFF && d[1] == (byte) 0xD8) return true;                       // JPEG
        if (d[0] == (byte) 0x89 && d[1] == (byte) 0x50) return true;                       // PNG
        if (d[0] == (byte) 'G' && d[1] == (byte) 'I' && d[2] == (byte) 'F') return true;   // GIF
        if (d[0] == (byte) 'R' && d[1] == (byte) 'I' && d[2] == (byte) 'F') return true;   // WEBP
        if (d[0] == (byte) 'B' && d[1] == (byte) 'M') return true;                          // BMP
        return false;
    }

    /**
     * 把 EPUB 的 zip 条目全部读入内存（name → bytes）。
     * 优先用 {@link java.util.zip.ZipFile}（对 STORE 模式的 mimetype 最稳）；
     * 若 zip 含重复条目名（多看等混淆 EPUB 常见：mimetype 被写两遍），Android 的 ZipFile 会抛
     * "Duplicate entry name: mimetype" 使整本解析失败，此时回退 {@link #readZipEntriesStreaming}。
     */
    private static Map<String, byte[]> readZipEntries(java.io.File epubFile) throws Exception {
        java.util.zip.ZipFile zf = null;
        try {
            Map<String, byte[]> map = new HashMap<>();
            zf = new java.util.zip.ZipFile(epubFile);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                // 防御：个别实现不抛异常而是返回重复项，这里按"先到先得"去重
                if (map.containsKey(e.getName())) continue;
                java.io.InputStream is = zf.getInputStream(e);
                map.put(e.getName(), readAllBytes(is));
                is.close();
            }
            return map;
        } catch (java.util.zip.ZipException ze) {
            // 重复条目名等 ZipFile 不支持的情况 → 回退流式读取
            Log.w("LocalBookParser", "ZipFile 读取失败（疑似重复条目名），回退 ZipInputStream: " + ze.getMessage());
            return readZipEntriesStreaming(epubFile);
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (java.io.IOException ignored) {}
            }
        }
    }

    /**
     * 流式读取 zip 全部条目（容错重名）：不校验条目名唯一性，遇到重复名按"先到先得"保留第一个。
     * 用于 Android ZipFile 因 "Duplicate entry name" 拒绝的混淆 EPUB。
     */
    private static Map<String, byte[]> readZipEntriesStreaming(java.io.File epubFile) throws Exception {
        Map<String, byte[]> map = new HashMap<>();
        java.io.FileInputStream fis = new java.io.FileInputStream(epubFile);
        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(fis);
        java.util.zip.ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            if (e.isDirectory()) { zis.closeEntry(); continue; }
            String name = e.getName();
            if (!map.containsKey(name)) {
                map.put(name, readAllBytes(zis));
            }
            zis.closeEntry();
        }
        zis.close();
        fis.close();
        return map;
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
     * 判断是否应该跳过该章节（封面、目录、简介等非正文章节）
     */
    private static boolean shouldSkipChapter(String title, String html) {
        if (title == null || title.isEmpty()) {
            return false; // 没有标题的章节不跳过
        }
        
        String lowerTitle = title.toLowerCase();
        
        // 1. 根据标题关键词判断（关键词已预先小写，避免每章循环里反复 toLowerCase）
        for (String keyword : SKIP_TITLE_KEYWORDS_ALWAYS) {
            if (lowerTitle.contains(keyword)) {
                return true;
            }
        }
        // 弱标记：只有标题很短（≈标记本身）时才认定非正文，避免误杀"…自我介绍一下…"这类真章节
        if (lowerTitle.length() <= SKIP_TITLE_SHORT_MAX_LEN) {
            for (String keyword : SKIP_TITLE_KEYWORDS_SHORT) {
                if (lowerTitle.contains(keyword)) {
                    return true;
                }
            }
        }
        
        // 2. 根据HTML内容特征判断
        if (html != null && !html.isEmpty()) {
            // 封面通常包含大量图片且文字很少
            int imgCount = RE_IMG_OPEN.split(html).length - 1;
            int textLength = RE_TAGS.matcher(html).replaceAll("").trim().length();
            
            // 如果图片很多但文字很少，可能是封面
            if (imgCount >= 3 && textLength < 500) {
                Log.d("LocalBookParser", "Detected cover by image count: " + imgCount + ", text length: " + textLength);
                return true;
            }
            
            // 目录通常包含大量链接
            int linkCount = RE_A_OPEN.split(html).length - 1;
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
     * 判断章节标题是否就是「内容简介」本身（用来把该章正文当书籍简介取走）。
     *
     * <p>判定方式与 {@link #shouldSkipChapter} 的弱标记一致：归一化（去空白 + 小写）后
     * 标题很短且命中简介标记。这样「内容简介」「简介」「作品简介」命中，
     * 而「第五百六十五章 自我介绍一下…」因长度超限不命中。
     */
    private static boolean isIntroChapterTitle(String title) {
        if (title == null) return false;
        String norm = RE_WS_PLUS.matcher(title).replaceAll("").toLowerCase();
        if (norm.isEmpty() || norm.length() > INTRO_TITLE_MAX_LEN) return false;
        for (String marker : INTRO_TITLE_MARKERS) {
            if (norm.contains(marker)) return true;
        }
        return false;
    }

    /**
     * 查询本地书的简介（按 bookId 从 {@code local_books} 回查）。
     *
     * <p>书籍详情页有多条进入路径（书架 → 阅读器 → 更多 → 书籍详情；阅读记录 → 阅读器 → 更多），
     * 传进来的 Book 不一定带 intro 字段，因此统一在这里回查一次。
     *
     * @return 简介文本；查不到返回空串（不返回 null，调用方无需判空）
     */
    public static String findLocalBookIntro(Context context, long bookId) {
        if (context == null || bookId <= 0) return "";
        try {
            SharedPreferences sp = context.getSharedPreferences("local_books", Context.MODE_PRIVATE);
            int count = sp.getInt("count", 0);
            for (int i = 0; i < count; i++) {
                if (sp.getLong("book_id_" + i, 0) == bookId) {
                    String intro = sp.getString("book_intro_" + i, "");
                    return intro == null ? "" : intro;
                }
            }
        } catch (Exception e) {
            Log.w("LocalBookParser", "findLocalBookIntro 失败: " + e.getMessage());
        }
        return "";
    }
    
    /**
     * 按文件名过滤前置/后置非正文章节
     * cover, toc, copyright, instruction, pretxt, tiaoshu 等
     */
    private static boolean shouldSkipByFileName(String fileName) {
        if (fileName == null) return false;
        // 原来是 13 个模式各跑 2 次 String.matches()（每次都要重新编译正则），
        // 即每章 26 次 Pattern.compile；现在合并成一条预编译正则，一次匹配解决。
        return RE_SKIP_FILE_NAME.matcher(fileName).matches();
    }
}