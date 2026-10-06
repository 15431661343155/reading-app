package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
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

    /** 孤儿封面宽限期：导入先落封面文件、后写 local_books，窗口内的新封面不能当垃圾删 */
    private static final long COVER_ORPHAN_GRACE_MS = 10 * 60 * 1000L;
    
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
    /** 上一项的预编译版（只匹配前缀头，标题部分改用字面量比较，见「移除开头重复标题」处） */
    private static final Pattern RE_COMBINED_PREFIX_HEAD =
            Pattern.compile(COMBINED_PREFIX_BODY, Pattern.CASE_INSENSITIVE);

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

    /** 实体字面量（大小写敏感，与原规则 11-22 一致）及其替换字符 */
    private static final String[] ENT = {"&nbsp;", "&lt;", "&gt;", "&amp;", "&quot;", "&#39;",
            "&apos;", "&#x27;", "&#x2018;", "&#x2019;", "&#x201C;", "&#x201D;"};
    private static final String[] ENTR = {" ", "<", ">", "&", "\"", "'", "'", "'", "'", "'", "\"", "\""};

    private static boolean startsWith(String s, int i, String lit) {
        return s.regionMatches(false, i, lit, 0, lit.length());
    }

    private static boolean startsWithCI(String s, int i, String lowerLit) {
        return s.regionMatches(true, i, lowerLit, 0, lowerLit.length());
    }

    /** 从 i 起找字符 ch 的下标（不含 i 之前），找不到返回 -1 */
    private static int indexOfChar(String s, char ch, int i) {
        return s.indexOf(ch, i);
    }

    /**
     * 单次字符扫描完成原先规则 0-22（script/style/注释/meta/link/h/br//p//div//li/其余标签 + 12 条实体）。
     *
     * <p>逐条复刻顺序 replaceAll 的语义：块级标签（script/style/注释/h）连同内容一起移除；
     *    未闭合的块级开标签退化为「只删开标签、保留内容」（与原 {@code <[^>]+>} 兜底一致）；
     *    h 块的 {@code .*?} 不跨行（原规则无 (?s)），script/style/注释可跨行（原规则有 (?s)）。
     *    真机实测正则链每章约 10ms，本扫描为线性单遍，是导入耗时的大头优化；产物与原链逐字相同（差分门禁验证）。
     */
    private static String stripTagsAndEntities(String c) {
        return stripTagsAndEntities(c, 0);
    }

    /**
     * 去标签/实体，最多产出 {@code cap} 个字符（{@code cap<=0} 表示不限）。
     *
     * <p>给「正文探针」用：它只需要正文开头几百字符，不必为判定一章空不空而扫完整章几十 KB。
     * 产出是 {@link #stripTagsAndEntities(String)} 的**前缀**（同一套跳过规则，只是提前停），
     * 所以基于它算出的任何下界对完整结果同样成立。
     */
    private static String stripTagsAndEntities(String c, int cap) {
        int n = c.length();
        StringBuilder out = new StringBuilder(cap > 0 ? Math.min(cap, n) : n);
        int i = 0;
        while (i < n) {
            if (cap > 0 && out.length() >= cap) break;
            char ch = c.charAt(i);
            if (ch == '&') {
                boolean hit = false;
                for (int e = 0; e < ENT.length; e++) {
                    if (startsWith(c, i, ENT[e])) {
                        out.append(ENTR[e]);
                        i += ENT[e].length();
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    out.append(ch);
                    i++;
                }
                continue;
            }
            if (ch != '<') {
                out.append(ch);
                i++;
                continue;
            }
            // 以下处理 '<'
            // 注释（原规则 2，(?s) 大小写敏感）
            if (startsWith(c, i, "<!--")) {
                int k = c.indexOf("-->", i + 4);
                if (k >= 0) {
                    i = k + 3;
                    continue;
                }
                // 未闭合注释：退化为通用标签（删到首个 '>'）
                int j = indexOfChar(c, '>', i + 1);
                if (j >= 0) {
                    i = j + 1;
                } else {
                    out.append(ch);
                    i++;
                }
                continue;
            }
            boolean closing = (i + 1 < n && c.charAt(i + 1) == '/');
            int nameStart = i + 1 + (closing ? 1 : 0);
            int nameEnd = nameStart;
            while (nameEnd < n && (Character.isLetterOrDigit(c.charAt(nameEnd)))) nameEnd++;
            String name = c.substring(nameStart, nameEnd).toLowerCase();

            if (!closing) {
                if (name.equals("script") || name.equals("style")) {
                    int j = indexOfChar(c, '>', nameEnd);
                    if (j < 0) {
                        out.append(ch);
                        i++;
                        continue;
                    }
                    String close = "</" + name + ">";
                    int k = indexOfCI(c, close, j + 1);
                    if (k >= 0) {
                        i = k + close.length();
                    } else {
                        // 未闭合块：只删开标签，保留内容（原 <[^>]+> 兜底）
                        i = j + 1;
                    }
                    continue;
                }
                if (name.equals("meta") || name.equals("link")) {
                    int j = indexOfChar(c, '>', nameEnd);
                    if (j >= 0) {
                        i = j + 1;
                    } else {
                        out.append(ch);
                        i++;
                    }
                    continue;
                }
                if (name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1' && name.charAt(1) <= '6') {
                    int j = indexOfChar(c, '>', nameEnd);
                    if (j < 0) {
                        out.append(ch);
                        i++;
                        continue;
                    }
                    String close = "</" + name + ">";
                    int k = indexOfCI(c, close, j + 1);
                    // 原规则 (?i) 无 (?s)：.*? 不跨行 → 开闭标签之间不得有换行
                    int nl = c.indexOf('\n', j + 1);
                    if (k >= 0 && (nl < 0 || nl > k)) {
                        out.append('\n');
                        i = k + close.length();
                    } else {
                        // 跨行或未闭合 → 原规则不匹配 → 只删开标签，保留内容
                        i = j + 1;
                    }
                    continue;
                }
                if (name.equals("br")) {
                    int p = i + 3;
                    while (p < n && Character.isWhitespace(c.charAt(p))) p++;
                    if (p < n && c.charAt(p) == '/') p++;
                    if (p < n && c.charAt(p) == '>') {
                        out.append('\n');
                        i = p + 1;
                        continue;
                    }
                    // 非 <br/> 形态（如 <br class=x>）：原规则 6 不匹配 → 通用标签删除
                    int j = indexOfChar(c, '>', i + 1);
                    if (j >= 0 && j > i + 1) {
                        i = j + 1;
                    } else {
                        out.append(ch);
                        i++;
                    }
                    continue;
                }
                // 其余开标签：通用删除
                int j = indexOfChar(c, '>', i + 1);
                if (j >= 0 && j > i + 1) {
                    i = j + 1;
                } else {
                    out.append(ch);
                    i++;
                }
                continue;
            } else {
                // 闭合标签
                int afterName = nameEnd;
                if (name.equals("p") && afterName < n && c.charAt(afterName) == '>') {
                    out.append("\n\n");
                    i = afterName + 1;
                    continue;
                }
                if ((name.equals("div") || name.equals("li")) && afterName < n && c.charAt(afterName) == '>') {
                    out.append('\n');
                    i = afterName + 1;
                    continue;
                }
                // 其余闭合标签：通用删除
                int j = indexOfChar(c, '>', i + 1);
                if (j >= 0 && j > i + 1) {
                    i = j + 1;
                } else {
                    out.append(ch);
                    i++;
                }
                continue;
            }
        }
        return out.toString();
    }

    /** 大小写不敏感 indexOf */
    private static int indexOfCI(String s, String lowerLit, int from) {
        int max = s.length() - lowerLit.length();
        for (int i = Math.max(from, 0); i <= max; i++) {
            if (s.regionMatches(true, i, lowerLit, 0, lowerLit.length())) return i;
        }
        return -1;
    }

    /** 字面量出现次数（大小写敏感），等价于 {@code Pattern.compile(lit).split(s).length - 1} 但不建数组 */
    private static int countOccurrences(String s, String lit) {
        int count = 0;
        int i = 0;
        while ((i = s.indexOf(lit, i)) >= 0) {
            count++;
            i += lit.length();
        }
        return count;
    }

    /**
     * 「去掉所有 {@code <[^>]+>} 标签后 trim」的长度，线性扫描不新建字符串。
     * 与 {@code RE_TAGS.matcher(html).replaceAll("").trim().length()} 逐字等价：
     *    {@code <>} 与未闭合的 {@code <} 不被 {@code <[^>]+>} 匹配，按字面字符计入。
     *
     * <p>{@code cap > 0} 时最多扫到 cap 个计数字符即返回 cap：trim 只会让结果变小，
     *    而扫描途中 {@code end - start} 单调不减，故「前缀已达 cap」等价于「全长 ≥ cap」。
     */
    private static int tagStrippedTrimmedLength(String s, int cap) {
        int n = s.length();
        int counted = 0;
        int start = -1;   // 首个非空白计数字符处的 counted 值
        int end = -1;     // 末个非空白计数字符之后的 counted 值
        int i = 0;
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '<') {
                int j = s.indexOf('>', i + 1);
                if (j > i + 1) {
                    i = j + 1;
                    continue;
                }
                // "<>" 或无 '>'：按字面 '<' 计入
            }
            if (start < 0 && !Character.isWhitespace(ch)) start = counted;
            counted++;
            if (!Character.isWhitespace(ch)) end = counted;
            i++;
            if (cap > 0 && end - start >= cap) return cap;
        }
        return (start < 0) ? 0 : (end - start);
    }

    /**
     * 标记整行清理（原顺序第 27-29 条）：正文书里几乎不出现这些字面量，故保留正则 + 必要字面量守卫，
     * 绝大多数字节直接跳过。真正每章必跑的空白/换行规则已改为字符扫描，见 cleanChapterContent。
     */
    private static final Pattern[] MARKER_PATTERNS;
    private static final String[] MARKER_GUARDS;
    static {
        String[][] rules = {
                {"(?m)^Cover\\s*$", "Cover"},
                {"(?m)^封面\\s*$", "封面"},
                {"(?m)^目录\\s*$", "目录"},
        };
        MARKER_PATTERNS = new Pattern[rules.length];
        MARKER_GUARDS = new String[rules.length];
        for (int i = 0; i < rules.length; i++) {
            MARKER_PATTERNS[i] = Pattern.compile(rules[i][0]);
            MARKER_GUARDS[i] = rules[i][1];
        }
    }

    /** Java 正则 {@code \s} 的精确字符集：{@code [ \t\n\x0B\f\r]}（默认不含 Unicode 空白）。 */
    private static boolean isAsciiWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /** 等价 {@code \n{3,} → \n\n}：连续 3 个及以上 {@code \n} 压成 2 个；1-2 个原样。只数 '\n'，'\r' 会断开游程。 */
    private static String collapseNewlines(String s) {
        int i = 0, n = s.length();
        // 先定位首个需要改写的游程；无则原样返回，避免无谓新建 String
        while (i < n) {
            if (s.charAt(i) == '\n') {
                int j = i;
                while (j < n && s.charAt(j) == '\n') j++;
                if (j - i >= 3) break;
                i = j;
            } else {
                i++;
            }
        }
        if (i >= n) return s; // 无 3+ 游程
        StringBuilder sb = new StringBuilder(n);
        sb.append(s, 0, i);
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                int j = i;
                while (j < n && s.charAt(j) == '\n') j++;
                int run = j - i;
                sb.append("\n\n", 0, run >= 3 ? 2 : run);
                i = j;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 等价先 {@code ^[\s\n]+ → ""} 再 {@code [\s\n]+$ → ""}：两端各去一段 {@code \s} 游程。全空白则返回空串。 */
    private static String trimAsciiWs(String s) {
        int n = s.length(), a = 0, b = n;
        while (a < b && isAsciiWs(s.charAt(a))) a++;
        while (b > a && isAsciiWs(s.charAt(b - 1))) b--;
        if (a == 0 && b == n) return s; // 两端都无空白
        return (a >= b) ? "" : s.substring(a, b);
    }

    /** 等价 {@code \r\n → \n}：非重叠自左向右替换；孤立的 '\r' 保留。调用前已确认含 '\r'。 */
    private static String crlfToLf(String s) {
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') {
                sb.append('\n');
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 执行正文清理链：等价于原先那一长串 String.replaceAll，但正则不再重复编译 */
    /**
     * 移除正文开头的重复标题（与 EPUB 目录标题同源）。
     *
     * <p>策略：只在前 5 行检查。独立标题行整行删除；「标题 + 正文」混在一行的只去掉开头标题部分。
     *    导入与懒解析共用这一份实现，两条链路产出的正文才能逐字相同。
     */
    static String stripLeadingDuplicateTitle(String content, String chapterTitle) {
        // 策略：只在前 5 行检查。独立标题行 → 整行删除；标题+正文混在一起的行 → 只去掉开头的标题部分
        if (chapterTitle != null && !chapterTitle.isEmpty()) {
                    // 归一化标题（去掉空白，便于匹配）
                    String lowerChapterTitle = chapterTitle.toLowerCase();
                    String normalizedTitle = RE_WS_PLUS.matcher(lowerChapterTitle).replaceAll("");
                    
                    // 构造组合模式：可选的"第X章/卷X/chapter X"前缀 + 章节标题内容
                    // 例如 chapterTitle="开局师父没了" → 能匹配 "第一章 开局师父没了"
                    //     chapterTitle="第一章 开局师父没了" → 能匹配 "第一章 开局师父没了" 本身
                    //     chapterTitle="第一章 开局师父没了" → 也能匹配纯"开局师父没了"
                    String tmp = RE_TITLE_PREFIX_CHAPTER.matcher(chapterTitle).replaceFirst("");
                    tmp = RE_TITLE_PREFIX_VOLUME.matcher(tmp).replaceFirst("");
                    String titleWithoutPrefix = RE_TITLE_PREFIX_EN.matcher(tmp).replaceFirst("").trim();
                    // 标题小写形一次算好，供归一化与逐行比较复用（原来每行都调 toLowerCase()）
                    String lowerTitleWithoutPrefix = titleWithoutPrefix.toLowerCase();
                    String normalizedTitleNoPrefix = RE_WS_PLUS.matcher(lowerTitleWithoutPrefix).replaceAll("");

                    // 组合前缀不再逐章 Pattern.compile：Android ICU 单次编译约 5.7ms，
                    //    千章书光是编译就要数秒（真机「一直在导入」的主因之一）。
                    //    改由预编译的前缀头 + 标题字面量比较组合出完全相同的判定，见下面 cutIdx。
                    
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
                        String lowerTrimmedLine = trimmedLine.toLowerCase();
                        int cutIdx = -1;
                        // 尝试用完整标题匹配开头
                        if (lowerTrimmedLine.startsWith(lowerChapterTitle)) {
                            cutIdx = chapterTitle.length();
                        }
                        // 尝试用去掉"第X章"前缀后的标题匹配开头
                        if (cutIdx < 0 && !titleWithoutPrefix.isEmpty()
                                && lowerTrimmedLine.startsWith(lowerTitleWithoutPrefix)) {
                            cutIdx = titleWithoutPrefix.length();
                        }
                        // 尝试匹配 "第X章" + 标题内容 的组合（当 chapterTitle 不含章节号时）。
                        //    预编译前缀头 + 字面量比较，等价于原先「前缀 + Pattern.quote(标题)」的整串正则：
                        //    该正则以 ^ 锚定且标题已 trim（不会以空白开头），故前缀头的 \s* 无需回溯。
                        if (cutIdx < 0) {
                            java.util.regex.Matcher prefixM = RE_COMBINED_PREFIX_HEAD.matcher(trimmedLine);
                            if (prefixM.find()
                                    && lowerTrimmedLine.startsWith(lowerTitleWithoutPrefix, prefixM.end())) {
                                cutIdx = prefixM.end() + titleWithoutPrefix.length();
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
        return content;
    }
    /** 章节 XHTML 字节 → 文本：先按 UTF-8，坏字符过多再退回 GBK。导入与懒解析共用 */
    static String decodeXhtmlBytes(byte[] data) {
        try {
            return new String(data, "UTF-8");
        } catch (Exception e) {
            try {
                return new String(data, "GBK");
            } catch (Exception ex) {
                return new String(data); // 默认编码
            }
        }
    }

    static String cleanChapterContent(String html) {
        String c = html;
        // 标签与实体用单遍字符扫描完成（原 23 条正则）；纯文本（无 '<' 且无 '&'）直接跳过，TXT 几乎零开销
        if (c.indexOf('<') >= 0 || c.indexOf('&') >= 0) c = stripTagsAndEntities(c);
        // 原顺序第 23-30 条：每章必跑的空白/换行规则用字符扫描（真机热点），仅标记整行仍走正则且多被守卫跳过
        c = collapseNewlines(c);            // 23: \n{3,} → \n\n
        c = trimAsciiWs(c);                 // 24+25: 去首尾空白
        if (c.indexOf('\r') >= 0) c = crlfToLf(c); // 26: \r\n → \n
        for (int i = 0; i < MARKER_PATTERNS.length; i++) { // 27-29: Cover / 封面 / 目录 整行
            if (!c.contains(MARKER_GUARDS[i])) continue;   // 必要字面量缺席 → 必不匹配 → 跳过，产物不变
            c = MARKER_PATTERNS[i].matcher(c).replaceAll("");
        }
        c = collapseNewlines(c);            // 30: \n{3,} → \n\n
        return c.trim();
    }

    /**
     * 正文探针扫描的输出上限。定论只要开头几段正文，1024 已留四五倍余量；
     * 这个值直接决定探针代价——{@link #stripTagsAndEntities} 会实打实产出这么长一个串。
     */
    private static final int PROBE_HEAD_CHARS = 1024;
    /** 非空白字符多于此算「像样的一段」：三种标记行（Cover/封面/目录）最多才 5 个字符 */
    private static final int PROBE_LINE_MIN_CHARS = 6;
    /** {@link #stripLeadingDuplicateTitle} 最多只查看清理后正文的前 5 行，探针把最先这几段整段让过 */
    private static final int PROBE_TITLE_LINES = 5;
    /** 探针确信后的记账值：只用于「≥20 字符」这一档判断，无需精确长度 */
    static final int BODY_CHARS_CONFIRMED = 64;

    /**
     * 廉价判定「这一章清理后到底有没有正文」，避免为每章构建整篇正文再丢弃。
     *
     * <p>返回 ≥0 表示**确证**：{@code cleanChapterContent(html)} 的产物（乃至再过一遍
     * {@link #stripLeadingDuplicateTitle}）必然还有至少 24 个非空白字符，既非空也不算「空白页」；
     * 返回 -1 表示探针不足以定论，调用方必须回退完整清理链去判。
     *
     * <p>完整清理链在去标签之后能抹掉非空白字符的地方只有两处：整行等于 {@code Cover}/{@code 封面}/
     * {@code 目录} 的标记行（至多 5 个字符），以及 {@link #stripLeadingDuplicateTitle} 对**清理后前 5 行**
     * 的处置（≤30 字符的独立标记行、归一化后与标题相等的整行、或开头那段标题前缀）。
     * 其余规则（压 3+ 连续换行、两端去空白、CRLF 转 LF）只动空白。
     *
     * <p>于是把「非空白字符 ≥ {@value #PROBE_LINE_MIN_CHARS}」的行当作一段正文——标记行天然够不着这条线——
     * 并整段让过最先 {@value #PROBE_TITLE_LINES} 段：清理只会删行不会增行、也不会改序，被删的又只能是标记行
     * 与空行，故「清理后前 5 行」必然落在这 5 段之内；此后各段字符数累加到 24 即可定论。
     *
     * <p>旧规则要求「找到一段 ≥（标题长度+60）非空白字符的行」，而网文段落普遍只有六七十字，
     * 真机实测 544 章里约 81 章因此定不了论、回退跑完整清理链；改成累计式后第一段正文就能开始计数，
     * 扫描量随之降了一个数量级（真机 544 章：{@code probeMs} 1491 → 156，整本导入 3134ms → 1435ms）。
     */
    static int probeBodyChars(String html) {
        if (html == null || html.isEmpty()) return -1;
        String head = stripTagsAndEntities(html, PROBE_HEAD_CHARS);
        int acc = 0, run = 0, skipped = 0;
        // 末位多走一步当作换行，好把没有结尾换行的最后一行也结算掉；被 cap 截断时 run 只是下界，偏保守
        for (int i = 0; i <= head.length(); i++) {
            char ch = i < head.length() ? head.charAt(i) : '\n';
            if (ch != '\n') {
                if (!isAsciiWs(ch)) run++;
                continue;
            }
            if (run >= PROBE_LINE_MIN_CHARS) {
                if (skipped < PROBE_TITLE_LINES) skipped++;
                else acc += run;
            }
            run = 0;
            if (acc >= 24) return BODY_CHARS_CONFIRMED;
        }
        return -1;
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
        /**
         * 清理后正文的字符数（上限 {@link #BODY_CHARS_CONFIRMED}，够不着上限时是精确值）。
         * 正文本身**不在导入时构建**：懒解析读到时才从源文件重现，落盘的只有下面的定位串。
         * 这个计数只为分卷目录服务——卷标题页那种「只有个卷名」的章要识别出来，
         * 否则目录里卷行和它的子项会重复显示同一个标题（见 {@link #buildVolumeInfos}）。
         */
        public int bodyChars;
        public int index;
        /** 所属分卷标题（无分卷时为空） */
        public String volumeTitle = "";
        /** 所属分卷序号，从 1 开始 */
        public int volumeIndex = 1;
        /** EPUB：该章在 zip 里的实际条目名，供「按需回源解析」定位；TXT 与其它格式为空 */
        public String locator = "";
        /** EPUB：该章条目所在目录（含末尾 '/'），章节内相对引用（CSS/图片）按它解析 */
        public String locatorDir = "";
        /**
         * TXT 懒解析：本章正文在源文件中的字节起点（含）。非懒解析的书为 -1。
         * 与 {@link #txtEnd} 一起界定「回源定位读」的区间；解码后跑与导入时同一套行循环即可逐字节复现正文。
         */
        public long txtStart = -1L;
        /** TXT 懒解析：本章正文在源文件中的字节终点（不含） */
        public long txtEnd = -1L;

        public Chapter(int index, String title) {
            this.index = index;
            this.title = title;
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
        /** 「样式片段键 → 作用域化结果」全书缓存；懒解析靠它免掉每章重跑 scopeEpubCss */
        public Map<String, String> cssChunks = new HashMap<>();
        /**
         * TXT 懒解析开关：为真表示这本书的正文不落盘，
         * 而是把每章的源文件字节区间写进 {@code txtindex.bin}，阅读时回源重建。
         * 只有「字符集可按字节安全扫描换行 + 成功识别出章节」时才置真；
         * 否则不写任何回源索引，这本书仍进书架但打开时提示不可读。
         */
        public boolean txtLazy = false;
        /** TXT 懒解析：解码源文件字节区间所用的字符集（回源重建必须与导入时逐字一致） */
        public String txtCharset = "UTF-8";
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
            // 按文件真实大小一次性分配读入：避免 ByteArrayOutputStream 翻倍扩容 + toByteArray 再拷贝
            //    （12MB 的书光读取阶段瞬时就能占到 ~28MB）。拿不到大小就退回通用流式读。
            byte[] rawData = readAllBytesSized(context, uri, is);
            is.close();
            String charset = detectCharset(rawData);
            String text = new String(rawData, charset);
            // 记录「真正用于解码的字符集」：回源懒解析必须用它逐字节复现正文，否则会与导入时不一致
            String txtCharset = charset;
            // 只有当 UTF-8 解码后「替换字符（U+FFFD）占比很高」才判定原始字节流不是 UTF-8
            // （多半是 GBK），此时改用 GBK 重新解码。文本里偶尔自带几个 U+FFFD（编辑器把坏字符
            // 替换后留下的占位）不能据此整本改用 GBK 重解——否则正常 UTF-8 中文书会被解成乱码，
            // 导致「第X章」全部匹配失败、章节识别为 0，导入后只剩兜底的单章「第一章」。
            if (charset.equals("UTF-8") && !isUtf8DecodeLikelyValid(text)) {
                try { text = new String(rawData, "GBK"); txtCharset = "GBK"; } catch (Exception ignored) {}
            }
            // TXT 懒解析：字符集能按字节安全扫描换行（UTF-8 / GBK，0x0A 绝不会出现在多字节序列内部）时，
            //    先算出每一行的起始字节偏移，稍后据此给每章记一个源文件字节区间。阅读时回源定位读该区间、
            //    用同一字符集解码、跑与下面完全一致的行循环，即可逐字节复现正文，全书正文不落盘。
            //    UTF-16 等按字节扫描不安全的字符集（0x0A 可能是码元的一部分）不记索引，打开时提示不可读。
            int[] lineByteStart = isByteScanSafeCharset(txtCharset) ? computeLineByteStarts(rawData) : null;
            // 原始字节已解码完毕，尽早释放（12MB 级别），不要再与 text/lines/chapters 同时压在堆上
            rawData = null;
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
                info.chapters.add(new Chapter(0, "第一章"));
                return info;
            }
            // 整本 String 已切成 lines，除上面的兜底分支外不再需要；尽早释放，别与 lines 同时占堆
            text = null;

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
                Chapter ch = new Chapter(i, chapterTitles.get(i));
                ch.bodyChars = txtBodyChars(lines, start, end);
                // 正文已不在此拼装（由 TxtLazyStore 回源重建），这里只留字符数给分卷判空用
                for (int j = start; j < end; j++) lines[j] = null;
                // TXT 懒解析：记下本章正文在源文件中的字节区间 [txtStart, txtEnd)。
                //    区间对齐到「行首字节」——start 行是标题后一行，end 行是下一章标题行（或全书末尾哨兵），
                //    回源读这段字节、按 txtCharset 解码后跑上面同一套行循环即可逐字节复现 content。
                if (lineByteStart != null && start < lineByteStart.length && end < lineByteStart.length) {
                    ch.txtStart = lineByteStart[start];
                    ch.txtEnd = lineByteStart[end];
                }
                int vi = chapterVolumeIndex[i];
                if (vi > 0 && vi <= txtVolumeTitles.size()) {
                    ch.volumeIndex = vi;
                    ch.volumeTitle = txtVolumeTitles.get(vi - 1);
                }
                info.chapters.add(ch);
            }

            // 懒解析开关：字符集按字节安全 + 识别出章节 + 每章都拿到了合法字节区间，三者齐备才置真。
            //    只要有一章缺区间（越界/异常）就整本物化，否则那一章永远回源读不出来。
            if (lineByteStart != null && !info.chapters.isEmpty()) {
                boolean allRanged = true;
                for (Chapter ch : info.chapters) {
                    if (ch.txtStart < 0 || ch.txtEnd < ch.txtStart) { allRanged = false; break; }
                }
                if (allRanged) {
                    info.txtLazy = true;
                    info.txtCharset = txtCharset;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (info.chapters.isEmpty()) {
            info.chapters.add(new Chapter(0, "第一章"));
        }
        return info;
    }

    /**
     * 等价于旧物化路径的 {@code content.trim().length()}（上限 {@link #BODY_CHARS_CONFIRMED}），全程不新建字符串。
     *
     * <p>旧 content 是「每个 {@code trim()} 非空的行拼 {@code raw + "\n"}」，故 trim 只能吃掉
     *    首行的行首空白、末行的行尾空白与最后那个 {@code \n}；非空行必含 {@code > ' '} 的字符，
     *    两端裁剪都越不出自己所在的行，按行累加即可逐字符复现。
     */
    private static int txtBodyChars(String[] lines, int start, int end) {
        int total = 0, leadFirst = 0, trailLast = 0;
        for (int j = start; j < end; j++) {
            String raw = lines[j];
            int len = raw.length();
            int a = 0;
            while (a < len && raw.charAt(a) <= ' ') a++;
            if (a == len) continue;
            int b = len;
            while (raw.charAt(b - 1) <= ' ') b--;
            if (total == 0) leadFirst = a;
            trailLast = len - b;
            total += len + 1;
        }
        if (total == 0) return 0;
        return Math.min(total - leadFirst - trailLast - 1, BODY_CHARS_CONFIRMED);
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
            boolean blank = first.bodyChars < 20;
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
    // ==================== EPUB 源读取：整档 / 按需两种视图 ====================

    /**
     * 「epub 源」的统一读法：既是一张 {@code Map<条目名, 字节>}（解析代码按名字点查、列条目名），
     * 又能只列条目名而不解压任何内容。
     *
     * <p>两个实现：{@link FullZipSource} 整档解压进内存（后台建索引时逐章都要用到），
     * {@link LazyZipSource} 只解中央目录（导入前台按书架卡片所需点读那三四条）。
     * 实测一本 21.5MB 的书，卡片真正用到的只有约 350KB——其余全是没人看的图片与字体。
     */
    private static abstract class ZipSource extends java.util.AbstractMap<String, byte[]> {
        /** 全部条目名（不含目录项）；只列名字不解压任何条目内容 */
        abstract java.util.Set<String> names();
    }

    private static final class FullZipSource extends ZipSource {
        private final Map<String, byte[]> map;

        FullZipSource(Map<String, byte[]> map) {
            this.map = map;
        }

        @Override
        java.util.Set<String> names() {
            return map.keySet();
        }

        @Override
        public java.util.Set<String> keySet() {
            return map.keySet();
        }

        @Override
        public byte[] get(Object key) {
            return map.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return map.containsKey(key);
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public java.util.Set<Entry<String, byte[]>> entrySet() {
            return map.entrySet();
        }
    }

    /**
     * 按需视图：条目名来自 zip 中央目录，用到哪条才解压哪条。
     * 整表遍历等价于把全书读进内存，正是这里要避免的事，所以 {@code entrySet()} 直接拒绝。
     */
    private static final class LazyZipSource extends ZipSource {
        private final EpubLazyStore.ZipBytes zip;
        private java.util.Set<String> names;

        LazyZipSource(EpubLazyStore.ZipBytes zip) {
            this.zip = zip;
        }

        @Override
        java.util.Set<String> names() {
            if (names == null) names = zip.names();
            return names;
        }

        @Override
        public java.util.Set<String> keySet() {
            return names();
        }

        @Override
        public byte[] get(Object key) {
            return key instanceof String ? zip.get((String) key) : null;
        }

        @Override
        public boolean containsKey(Object key) {
            // 只比条目名：判断存在与否不该把整条内容解压出来
            return key instanceof String && names().contains((String) key);
        }

        @Override
        public int size() {
            return names().size();
        }

        @Override
        public java.util.Set<Entry<String, byte[]>> entrySet() {
            throw new UnsupportedOperationException("按需 zip 视图只支持点查与列条目名");
        }
    }

    /** OPF + 目录（NCX / EPUB3 nav）一次收齐的全书元数据；卡片（前台）与逐章索引（后台）共用 */
    private static final class EpubMeta {
        /** spine 顺序的 itemref id */
        final List<String> idrefs = new ArrayList<>();
        /** item id → href（已 URL 解码） */
        final Map<String, String> idToHref = new HashMap<>();
        /** 章条目文件名 → 目录标题 */
        final Map<String, String> tocTitles = new HashMap<>();
        /** 章条目文件名 → 所属卷标题 */
        final Map<String, String> fileToVolume = new HashMap<>();
        /** 卷标题（按出现顺序） */
        final List<String> volumeTitles = new ArrayList<>();
        /** OPF 所在目录（含末尾 '/'），spine 的相对 href 按它定位 */
        String basePath = "";
    }

    /**
     * 导入「前台」解析：只取出书架卡片要的东西——书名/作者/简介/封面，
     * 外加由目录页与 spine 直接得到的<b>粗版</b>章节标题。不读任何章节正文，也不写回源索引。
     *
     * <p>为什么能秒级：EPUB 的卡片信息全在 container.xml/OPF/NCX/封面那几条小条目里，按需点读即可。
     * 过去导入却先把整本（含图片字体）解压进内存，再逐章定标题、剔非正文、算样式键表——
     * 实测 15MB/544 章冷启动那一段要 1.8 秒。逐章的活交给 {@code LocalBookImport} 在后台跑，
     * 产物与过去逐字节一致。TXT 没有元数据，卡片字段全部来自文件名，前台一次文件都不读。
     *
     * <p>粗版目录与后台精修结果可能不同（前台不解压正文，判不出某条 spine 是不是空章），
     * 索引就绪时由 {@code LocalBookImport} 把目录与章数覆盖成精修版。
     */
    public static BookInfo parseQuick(Context context, Uri uri, String fileName, long bookId) {
        if (fileName != null && fileName.toLowerCase().endsWith(".epub")) {
            return quickEpub(context, uri, bookId);
        }
        BookInfo info = new BookInfo();
        info.title = fileName == null ? "未命名" : fileName.replace(".txt", "").replace(".epub", "");
        info.author = "未知作者";
        info.intro = "";
        return info;
    }

    private static BookInfo quickEpub(Context context, Uri uri, long bookId) {
        BookInfo info = new BookInfo();
        info.title = "未命名";
        info.author = "未知作者";
        EpubLazyStore.ZipBytes zip = null;
        try {
            zip = EpubLazyStore.openForImport(context, uri);
            if (zip == null) return info;
            EpubMeta meta = new EpubMeta();
            if (!readEpubMeta(context, new LazyZipSource(zip), bookId, info, meta)) return info;
            int idx = 0;
            for (String idref : meta.idrefs) {
                String href = meta.idToHref.get(idref);
                if (href == null) continue;
                if (epubEntryKey(href, zip.names(), meta.basePath) == null) continue;
                String baseName = epubBaseName(href);
                if (shouldSkipByFileName(baseName)) continue;
                String title = meta.tocTitles.get(baseName);
                if (title == null || title.isEmpty()
                        || RE_PLACEHOLDER_TITLE.matcher(title.trim().toLowerCase()).matches()) {
                    title = "第" + (idx + 1) + "章";
                }
                Chapter ch = new Chapter(idx, title);
                String vol = meta.fileToVolume.get(baseName);
                if (vol != null && !vol.isEmpty()) {
                    ch.volumeTitle = vol;
                    ch.volumeIndex = meta.volumeTitles.indexOf(vol) + 1;
                }
                info.chapters.add(ch);
                idx++;
            }
        } catch (Exception e) {
            android.util.Log.e("LocalBookParser", "EPUB 前台解析失败: " + e.getMessage(), e);
        } finally {
            if (zip != null) zip.close();
        }
        return info;
    }

    /**
     * OPF 里的 href → 实际 zip 条目名（三段回退：basePath 规范化路径 / 原始 href / URL 解码）。
     * 只比条目名存在与否，不取内容——前台扫全书 spine 时不能为此把每章都解压一遍。
     * 取不到返回 null，与整本解析里「这条 spine 没有对应条目就跳过」同一判据。
     */
    private static String epubEntryKey(String href, java.util.Set<String> names, String basePath) {
        String fullPath = basePath + href;
        while (fullPath.contains("../")) {
            fullPath = fullPath.replaceFirst("[^/]+/\\.\\./", "");
        }
        if (fullPath.startsWith("/")) {
            fullPath = fullPath.substring(1);
        }
        if (names.contains(fullPath)) return fullPath;
        if (names.contains(href)) return href;
        try {
            String decodedPath = java.net.URLDecoder.decode(fullPath, "UTF-8");
            if (names.contains(decodedPath)) return decodedPath;
        } catch (Exception e) {
            // 解码失败按「取不到」处理
        }
        return null;
    }

    /**
     * 整本解析用的源：content:// 先落到缓存临时文件（ZipFile 要真路径），读完即删；
     * 然后一次性把全部条目读进内存——后台逐章解析确实每条都要用。
     */
    private static ZipSource openEpubFull(Context context, Uri uri) throws Exception {
        java.io.File epubFile = null;
        String scheme = uri.getScheme();
        if ("content".equals(scheme)) {
            epubFile = new java.io.File(context.getCacheDir(),
                    "temp_epub_" + System.currentTimeMillis() + ".epub");
            java.io.InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            java.io.FileOutputStream fos = new java.io.FileOutputStream(epubFile);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.close();
            is.close();
        } else if ("file".equals(scheme)) {
            epubFile = new java.io.File(uri.getPath());
        }
        if (epubFile == null || !epubFile.exists()) return null;
        try {
            // 普通 EPUB 走 ZipFile（对 STORE 模式的 mimetype 最稳）；多看等混淆 EPUB 常把 mimetype
            //    在中央目录写两遍，ZipFile 遇重复条目名会抛 "Duplicate entry name" 使整本解析失败，
            //    readZipEntries 捕获后回退 ZipInputStream 流式读取（不校验重名，按先到先得去重）。
            Map<String, byte[]> entries = readZipEntries(epubFile);
            android.util.Log.d("LocalBookParser", "Successfully loaded " + entries.size()
                    + " ZIP entries using ZipFile");
            return new FullZipSource(entries);
        } finally {
            if ("content".equals(scheme)) {
                //noinspection ResultOfMethodCallIgnored
                epubFile.delete();
            }
        }
    }


    /**
     * 读全书元数据：OPF 的书名/作者/简介、封面落盘、NCX（或 EPUB3 nav）的目录标题与分卷、spine 顺序。
     *
     * <p>整本解析（{@link #parseEpub}）与导入前台（{@link #parseQuick}）跑的就是这同一段：
     * 前台用 {@link LazyZipSource} 只点读那几条小条目，后台用整档 Map 读同一批内容。
     * 卡片信息必须与精修结果同源，所以不允许写成两份代码。
     *
     * @return epub 不可用（条目太少 / 找不到 rootfile / 读不出 OPF）时为假，占位章由调用方补
     */
    private static boolean readEpubMeta(Context context, ZipSource zipEntries, long bookId,
                                        BookInfo info, EpubMeta meta) throws Exception {
        // 解析 container.xml 找到根文件
        String rootfilePath = null;
        byte[] containerData = zipEntries.get("META-INF/container.xml");

        // 调试：列出所有ZIP条目，方便排查
        Log.d("LocalBookParser", "EPUB ZIP entries count: " + zipEntries.size());

        // 新增：检查ZIP条目数量，如果太少说明文件损坏
        if (zipEntries.size() < 3) {
            Log.e("LocalBookParser", "EPUB file is too small or corrupted! Only "
                    + zipEntries.size() + " entries found.");
            return false;
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
                    for (String name : zipEntries.keySet()) {
                        if (name.endsWith("/" + opfName) || name.equals(opfName)) {
                            rootfilePath = name;
                            Log.d("LocalBookParser", "Method 4 found rootfile in subdirectory: " + rootfilePath);
                            break;
                        }
                    }
                    if (rootfilePath != null) break;
                }
            }

            // 方法5: 查找任何 .opf 文件
            if (rootfilePath == null) {
                for (String name : zipEntries.keySet()) {
                    if (name.toLowerCase().endsWith(".opf")) {
                        rootfilePath = name;
                        Log.d("LocalBookParser", "Method 5 found any .opf file: " + rootfilePath);
                        break;
                    }
                }
            }
        }

        if (rootfilePath == null) {
            Log.e("LocalBookParser", "All methods failed to find rootfile!");
            return false;
        }

        Log.d("LocalBookParser", "Successfully found rootfile: " + rootfilePath);

        // 获取根文件内容 (content.opf)
        byte[] opfData = zipEntries.get(rootfilePath);
        if (opfData == null) {
            return false;
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
        for (String name : zipEntries.keySet()) {
            if (name.endsWith(".ncx")) {
                ncxData = zipEntries.get(name);
                break;
            }
        }

        if (ncxData != null) {
            try {
                String ncxXml = new String(ncxData, "UTF-8");
                // 标题与分卷在同一次游标遍历里一起收出来。
                //    原来标题另用一条 "<navPoint[^>]*>(.*?)</navPoint>"（DOTALL）正则扫，
                //    非贪婪 + 全域匹配在 283KB 的 ncx 上每次 find() 都要重扫后续内容，
                //    而 parseNavLevel 已按 navPoint 配对精确切块，遍历一遍即等价（同为后到覆盖）。
                parseNcxVolumes(ncxXml, chapterVolumeMap, volumeTitles, ncxTitleMap);
                info.volumes = volumeTitles;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 尝试从 EPUB3 导航文档获取目录。
        //    只在 NCX 一个标题都没给到时才做：有 toc.ncx 的书这段是纯浪费，而旧写法为了找一页目录
        //    会把 zip 里每个 XHTML 都 UTF-8 解码一遍（实测一本书 10MB 正文白解两遍）。
        if (ncxTitleMap.isEmpty()) {
            String navPath = null;
            Matcher navItemM = Pattern.compile(
                    "<item[^>]+href=\"([^\"]+)\"[^>]*properties=\"[^\"]*[,\\s]nav(?:[,\\s]|\"|[>])",
                    Pattern.CASE_INSENSITIVE).matcher(opfXml);
            if (navItemM.find()) {
                navPath = resolveInZip(navItemM.group(1), dirOf(rootfilePath));
            }
            collectTocTitlesFromNav(zipEntries, navPath, ncxTitleMap);
            // 兜底：既无 NCX，OPF 又没有标准 properties="nav" 声明（手写 class="toc" 目录页的生成器）
            if (ncxTitleMap.isEmpty()) {
                collectTocTitlesFromNav(zipEntries, null, ncxTitleMap);
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

        meta.idrefs.addAll(idrefList);
        meta.idToHref.putAll(idToHref);
        meta.tocTitles.putAll(ncxTitleMap);
        meta.fileToVolume.putAll(chapterVolumeMap);
        meta.volumeTitles.addAll(volumeTitles);
        meta.basePath = basePath;
        return true;
    }


    /**
     * 整本解析：按 spine 逐章定标题、剔除非正文、探空章，并算好全书样式键表；
     * 产物落 {@code index.bin} / {@code css_chunks.bin}，正文本身不落盘。
     *
     * <p>导入不再等它（实测 15MB/544 章冷启动 1.8 秒），改由后台队列跑，见 {@link #parseQuick}。
     */
    private static BookInfo parseEpub(Context context, Uri uri, long bookId) {
        long t0 = System.currentTimeMillis();
        BookInfo info = new BookInfo();
        info.title = "未命名";
        info.author = "未知作者";

        try {
            ZipSource zipEntries = openEpubFull(context, uri);
            long tZip = System.currentTimeMillis();
            if (zipEntries == null) {
                android.util.Log.e("LocalBookParser", "Failed to create or find EPUB file!");
                info.chapters.add(new Chapter(0, "第一章"));
                return info;
            }

            EpubMeta meta = new EpubMeta();
            if (!readEpubMeta(context, zipEntries, bookId, info, meta)) {
                android.util.Log.e("LocalBookParser", "EPUB 元数据不可用，无法建回源索引: " + uri);
                info.chapters.add(new Chapter(0, "第一章"));
                return info;
            }
            List<String> idrefList = meta.idrefs;
            Map<String, String> ncxTitleMap = meta.tocTitles;
            Map<String, String> chapterVolumeMap = meta.fileToVolume;
            List<String> volumeTitles = meta.volumeTitles;
            Map<String, String> idToHref = meta.idToHref;
            String basePath = meta.basePath;

            int chapterIndex = 0;
            // 全书共享的样式表缓存：同一份样式只读取、作用域化一次。
            //    否则一本书 800 章会把同一份 15KB 公共 CSS 重复作用域化 800 次。
            //    这张表会落盘（css_chunks.bin），懒解析每章都拿它当起点，所以导入时必须预热好。
            Map<String, String> cssCache = new HashMap<>();
            long t2 = System.currentTimeMillis();
            long tRead = 0, tClean = 0, tHtml = 0;
            for (String idref : idrefList) {
                long tc = System.currentTimeMillis();
                String href = idToHref.get(idref);
                if (href == null) continue;
                
                // 命中的**实际** zip 条目名——懒解析要靠它回源读取，规范化路径的三种回退都可能改它
                String entryKey = epubEntryKey(href, zipEntries.keySet(), basePath);
                if (entryKey == null) continue;
                byte[] chapterData = zipEntries.get(entryKey);
                if (chapterData == null) continue;

                String html = decodeXhtmlBytes(chapterData);
                tRead += System.currentTimeMillis() - tc;

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

                // 空章闸门：只有「清理后确实没正文」的章才不进目录。
                //    整篇正文过去也在导入时逐章构建，但物化删掉之后它只剩「判空」一个用途，
                //    读时按定位串会原样重现（见 EpubLazyStore.chapter），所以先用探针确证「肯定有正文」；
                //    探针下不了结论的（短文、纯标记页）才回退完整清理链，判定与过去逐字一致。
                long tq = System.currentTimeMillis();
                int probed = probeBodyChars(html);
                int bodyChars;
                boolean keep;
                if (probed >= 0) {
                    bodyChars = probed;
                    keep = true;
                } else {
                    String content = stripLeadingDuplicateTitle(cleanChapterContent(html), chapterTitle);
                    bodyChars = Math.min(content.length(), BODY_CHARS_CONFIRMED);
                    keep = !content.isEmpty();
                }
                tClean += System.currentTimeMillis() - tq;

                if (keep) {
                    Chapter ch = new Chapter(chapterIndex, chapterTitle);
                    ch.bodyChars = bodyChars;
                    ch.locator = entryKey;
                    // 预热全书样式缓存（css_chunks.bin 的来源）。整章 HTML 不在这里拼——
                    //    它过去拼完就丢，读时同一套 buildEpubHtmlContent 会再拼一遍。
                    try {
                        String chapterDir = dirOf(resolveInZip(href, basePath));
                        ch.locatorDir = chapterDir;
                        long th = System.currentTimeMillis();
                        warmEpubCaches(html, chapterDir, zipEntries, cssCache);
                        tHtml += System.currentTimeMillis() - th;
                    } catch (Exception e) {
                        android.util.Log.e("LocalBookParser", "样式预热失败: " + e.getMessage());
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

            info.cssChunks = cssCache;
            android.util.Log.d("LocalBookParser", "EPUBPERF chapters=" + info.chapters.size()
                    + " zipAllMs=" + (tZip - t0) + " metaMs=" + (t2 - tZip)
                    + " loopMs=" + (System.currentTimeMillis() - t2)
                    + " readDecodeMs=" + tRead + " probeMs=" + tClean + " warmMs=" + tHtml);

        } catch (Exception e) {
            // 正文已不在导入时构建，失败原因只能留在日志里（这本书进书架后打开会提示不可读）
            android.util.Log.e("LocalBookParser", "EPUB 解析失败: " + e.getMessage(), e);
            if (info.chapters.isEmpty()) {
                info.chapters.add(new Chapter(0, "第一章"));
            }
        }

        if (info.chapters.isEmpty()) {
            info.chapters.add(new Chapter(0, "第一章"));
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
     * 从 EPUB 章节 XHTML 拼出「保留样式」的整章 HTML：
     *  - 收集 &lt;style&gt; 与 &lt;link rel="stylesheet"&gt; 的 CSS，作用域化后拼成一块内联 &lt;style&gt;
     *    （每份样式的结果按全书级键记入 {@code cssCache}，同一份样式全书只算一次）
     *  - 取 &lt;body&gt; 内部内容（无 body 则去 head 后整体）
     *  - 图片引用改写为 {@code epubres://<zip 条目>}，由阅读器拦截后从源 zip 现读
     *
     * <p>懒解析读一章就调一次本方法（{@code EpubLazyStore.chapter}），产物直接进 WebView。
     * 任何异常都不抛出，返回 null 由调用方回退纯文本。
     */
    static String buildEpubHtmlContent(String xhtml, String chapterDir, Map<String, byte[]> zipEntries,
                                       Map<String, String> cssCache) {
        if (xhtml == null || xhtml.isEmpty()) return null;

        // 1+2. 本章样式（内联 <style> 与外链样式表，均已作用域化并全书缓存）
        String scopedCss = collectEpubCss(xhtml, chapterDir, zipEntries, cssCache);

        // 3. 提取 <body> 内容（无 body 则去 head 后整体）
        String body = bodyOf(xhtml);

        // 4. 图片引用改写为 epubres:// 协议串（纯字符串拼接，见 imageUri）
        body = processEpubImages(body, chapterDir);

        // 5. 组装（此处不包裹 epub-chapter：由 reader.html 的 loadHtmlContent 统一包裹，避免重复嵌套）
        StringBuilder sb = new StringBuilder();
        if (scopedCss.length() > 0) {
            sb.append("<style>").append(scopedCss).append("</style>");
        }
        sb.append(body);
        return sb.toString();
    }

    /**
     * 导入侧的样式预热：只把本章用到的样式表作用域化并记进 {@code cssCache}，不碰正文。
     *
     * <p>这张表落盘成 css_chunks.bin，是懒解析免掉「每章重跑 scopeEpubCss」的前提，
     *    所以必须在导入时预热好；拼整章 HTML 则是读时的事（{@link #buildEpubHtmlContent}）。
     */
    static void warmEpubCaches(String xhtml, String chapterDir, Map<String, byte[]> zipEntries,
                               Map<String, String> cssCache) {
        collectEpubCss(xhtml, chapterDir, zipEntries, cssCache);
    }

    /**
     * 汇出本章整份样式文本，并把每份样式的「作用域化结果」按全书级键缓存。
     *
     * <p>CSS 作用域隔离：reader.html 是「单文档」，书里的 body / html / div / * / a
     *    等选择器会命中阅读器自身的 DOM（#htmlContent、#htmlColumns、#flipbookContainer…），
     *    必须把选择器限定到包裹正文的 .epub-chapter 之内。
     *    注意是「逐块」作用域化（每块的结果已在缓存里），不能对拼接整体再跑一次——
     *    那会让每章都重新解析整份 CSS。
     *
     * <p>内联样式排在前、外链样式表排在后，与浏览器层叠顺序一致；顺序过去由调用方
     *    的拼接代码决定，这里原样保留。
     */
    private static String collectEpubCss(String xhtml, String chapterDir, Map<String, byte[]> zipEntries,
                                         Map<String, String> cssCache) {
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
        return css.toString();
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
     * 把 body 中的图片引用替换为 {@code epubres://} 协议 URI。
     * 同时处理 &lt;img src&gt; 与 SVG &lt;image xlink:href/href&gt;。
     */
    private static String processEpubImages(String body, String chapterDir) {
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
                String imgRef = imageUri(resolved);
                if (imgRef != null && !imgRef.isEmpty()) {
                    String newTag = tag.replace("src=\"" + src + "\"", "src=\"" + imgRef + "\"");
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
                String imgRef = imageUri(resolved);
                if (imgRef != null && !imgRef.isEmpty()) {
                    String newAttr = hrefM.group(1) + "=\"" + imgRef + "\"";
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
     * 图片在章节 HTML 里应写的引用：不再解到磁盘，改用自定义协议
     * {@code epubres://<zip条目路径>}，由阅读器的 WebViewClient 拦截后从源 zip 实时读取。
     *
     * <p>为什么放弃 file:// 落盘：插图目录随书一起占空间（实测《我的模拟长生路》6.5MB/11张），
     *    且需要额外的清理逻辑；改为协议引用后，插图零额外存储，HTML 里只存条目名字符串。
     *    渲染时 WebView 发起请求 → shouldInterceptRequest 从 zip 读字节返回，
     *    单章成本 ≈ 一次点查 + deflate 解压（几十 KB 图约 0.5-2ms），用户无感。
     *
     * <p>纯字符串拼接，因此读时现场算即可，不需要任何「图片引用缓存」落盘。
     */
    private static String imageUri(String resolved) {
        return resolved == null ? "" : "epubres://" + resolved;
    }

    // ==================== 本地书 HTML 文件缓存（避免写入 SharedPreferences 撑爆 SP） ====================

    /** 返回某本书的 HTML 缓存目录：filesDir/local_book_html/<bookId> */
    static File getHtmlCacheDir(Context context, long bookId) {
        return new File(context.getFilesDir(), "local_book_html" + File.separator + bookId);
    }

    /** 每章回源定位串（EPUB=zip 条目名）的容器文件名；懒解析只靠它 + 源文件就能重现正文 */
    private static final String INDEX_CONTAINER_NAME = "index.bin";
    /** TXT 懒解析回源索引（字符集 + 每章正文字节区间）的文件名；有它则 TXT 正文回源重建、不落盘 */
    private static final String TXT_INDEX_CONTAINER_NAME = "txtindex.bin";
    /** 「样式片段键 → 作用域化结果」缓存容器（键/值成对） */
    private static final String CSS_CHUNKS_CONTAINER_NAME = "css_chunks.bin";
    /** 容器魔数 'LBK1'，用于校验文件未被损坏/替换 */
    private static final int CONTAINER_MAGIC = 0x4C424B31;
    /** 后台构建产物的临时后缀：改名去掉它才算「发布」 */
    private static final String PENDING_SUFFIX = ".pending";
    /**
     * 容器格式：magic(4) + blockCount(4) + (blockCount+1) 个 8 字节绝对偏移 + 各块数据。
     * index.bin 每章占两块（条目名 + 所在目录），css_chunks.bin 每对键值占两块。
     */

    /**
     * 把整本书的「回源索引 + 样式缓存」落盘；正文一律不物化，阅读时回源重建。
     *
     * <p>为什么不物化正文：一本 10MB 的书展开成 HTML+纯文本要 15~32MB（正文还存了两份），
     *    而业界的做法是记住源文件、只在本地留几 KB 索引。EPUB 每章都记 zip 条目名
     *    （{@code index.bin}），TXT 每章记源文件里的字节区间（{@code txtindex.bin}），
     *    样式作用域化结果缓存成键表（{@code css_chunks.bin}），现场重建一章只要零点几毫秒。
     *
     * <p>插图不进任何缓存：HTML 里只写 {@code epubres://<zip 条目>}，读时由阅读器拦截现取，
     *    引用串是纯拼接，因此没有「图片引用」可缓存（曾经落盘过 img_refs.bin，已随实测作废）。
     *
     * <p>代价：源文件被删就再也读不出正文——这是刻意取舍，打不开时由阅读器提示，不做整本兜底。
     *    有一章拿不到定位串的书同样不写索引：它仍会进书架，只是打开时提示「内容缺失」。
     *
     * <p>为什么不按章各写一个文件：实测同一份 37MB 内容，写成 3600 个文件需 ~75 秒，
     * 写成 1 个文件只要 ~30 毫秒——瓶颈是**文件个数**（每次 create/close 在 Android 上
     * 都有 dentry + fsync 开销），不是数据量。之前 1800 章的书籍因此卡在「正在导入」一分多钟。
     *
     * <p>样式单独拆一份缓存容器：一本书通常只有 1~4 种整章 CSS 组合，却要被上千章各内联一遍
     *    （实测《乱世书》35.9MB 章内 HTML 里有 31.8MB 是重复 CSS）。作用域化真机单章 1.5~2.6 秒，
     *    只有导入时算好存键表，读时才能免掉重算。
     */
    public static void writeBookChapters(Context context, long bookId, BookInfo info) {
        writeBookChapters(context, bookId, info, false);
    }

    /**
     * @param pending 后台导入用：产物写成 {@code *.bin.pending}，等调用方把目录（章节标题/章数）
     *                发布进 SharedPreferences 之后再由 {@link #publishPending} 改名到位。
     *                顺序是「目录先、索引后」，于是「索引文件存在 ⟹ 目录与它一致」这条阅读器
     *                赖以校验的不变量不会在中间态被打破。
     */
    public static void writeBookChapters(Context context, long bookId, BookInfo info, boolean pending) {
        if (info == null || info.chapters == null || info.chapters.isEmpty()) return;
        String sfx = pending ? PENDING_SUFFIX : "";
        List<Chapter> chapters = info.chapters;
        File dir = getHtmlCacheDir(context, bookId);
        boolean created = dir.mkdirs();
        if (!created && !dir.exists()) {
            throw new RuntimeException("无法创建书籍缓存目录: " + dir.getAbsolutePath());
        }
        // TXT 懒解析：只写「字符集 + 每章字节区间」这份几 KB 的索引，正文不落盘，
        //    阅读时回源定位读源文件重建。EPUB 专用的 index.bin 一并清掉，避免残留脏索引。
        if (info.txtLazy) {
            writeTxtIndex(new File(dir, TXT_INDEX_CONTAINER_NAME + sfx), info.txtCharset, chapters);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, INDEX_CONTAINER_NAME + sfx).delete();
            File keyFile = new File(dir, TXT_INDEX_CONTAINER_NAME + sfx);
            if (!keyFile.exists() || keyFile.length() < 12) {
                throw new RuntimeException("TXT 懒解析索引写入不完整，请重试");
            }
            android.util.Log.d("LocalBookParser", "writeBookChapters(TXT 懒解析) 完成: bookId=" + bookId
                    + ", chapters=" + chapters.size() + ", keyFile=" + keyFile.getName()
                    + ", size=" + keyFile.length() + (pending ? " [pending]" : ""));
            return;
        }
        List<String> locators = new ArrayList<>(chapters.size() * 2);
        boolean allLocated = true;
        for (Chapter ch : chapters) {
            String l = ch.locator == null ? "" : ch.locator;
            locators.add(l);
            locators.add(ch.locatorDir == null ? "" : ch.locatorDir);
            if (l.isEmpty()) allLocated = false;
        }
        // 回源索引（每章两个块：条目名 + 所在目录）：懒解析只靠它 + 源 epub 就能重现任意一章。
        //    有一章拿不到定位串就不写索引——这本书仍会进书架，但打开时提示「内容缺失」。
        writeContainerIf(new File(dir, INDEX_CONTAINER_NAME + sfx), allLocated ? locators : null);
        // 「样式片段键 → 作用域化结果」缓存：懒解析每章都要用，却绝不能每章重算（scopeEpubCss 真机 1.5~2.6 秒）
        writePairContainer(new File(dir, CSS_CHUNKS_CONTAINER_NAME + sfx), info.cssChunks);

        if (allLocated) {
            // 验证关键文件是否成功写入（能懒解析的 EPUB 必须有 index.bin）
            File keyFile = new File(dir, INDEX_CONTAINER_NAME + sfx);
            if (!keyFile.exists() || keyFile.length() < 12) { // 至少要有 magic(4) + count(4) + 一个偏移(8)
                android.util.Log.e("LocalBookParser", "关键文件写入失败或损坏: " + keyFile.getAbsolutePath()
                        + ", size=" + (keyFile.exists() ? keyFile.length() : -1));
                throw new RuntimeException("书籍缓存写入不完整，请重试");
            }
            android.util.Log.d("LocalBookParser", "writeBookChapters 完成: bookId=" + bookId
                    + ", chapters=" + chapters.size() + ", allLocated=true"
                    + ", keyFile=" + keyFile.getName() + ", size=" + keyFile.length()
                    + (pending ? " [pending]" : ""));
        } else {
            android.util.Log.w("LocalBookParser", "writeBookChapters 完成（无回源索引，本书将不可读）: bookId=" + bookId
                    + ", chapters=" + chapters.size());
        }
    }

    /** 这本书是否写了 EPUB 回源索引；无索引说明它没法懒解析，打开时应提示不可读 */
    public static boolean hasChapterIndex(Context context, long bookId) {
        return new File(getHtmlCacheDir(context, bookId), INDEX_CONTAINER_NAME).exists();
    }

    /** 这本书是否写了 TXT 懒解析索引；无索引说明它没法回源，打开时应提示不可读 */
    public static boolean hasTxtIndex(Context context, long bookId) {
        return new File(getHtmlCacheDir(context, bookId), TXT_INDEX_CONTAINER_NAME).exists();
    }

    /**
     * 后台构建的产物里是否已有「可用」回源索引（{@code *.pending} 形态）。
     * 调用方据此决定要不要把精修目录发布进 SharedPreferences——没索引就别改目录，
     * 否则会把前台的粗版目录毁成一个「第一章」占位条目。
     */
    public static boolean hasPendingIndex(Context context, long bookId) {
        return containerReady(new File(getHtmlCacheDir(context, bookId), INDEX_CONTAINER_NAME + PENDING_SUFFIX))
                || containerReady(new File(getHtmlCacheDir(context, bookId),
                        TXT_INDEX_CONTAINER_NAME + PENDING_SUFFIX));
    }

    /** 容器文件存在且不像截断的半成品（至少 magic(4) + count(4) + 一条偏移(8)） */
    private static boolean containerReady(File f) {
        return f.exists() && f.length() >= 12;
    }

    /**
     * 把后台构建的 {@code *.bin.pending} 改名成正式文件。
     *
     * <p>改名顺序刻意为「样式在前、回源索引最后」：阅读器只认 index.bin/txtindex.bin 的存在与否，
     * 它一出现就必须能配套读到样式缓存，否则懒解析每章现算 scopeEpubCss（真机 1.5~2.6 秒）。
     * TXT 构建则顺手删掉可能残留的 EPUB 索引，避免两种索引并存。
     *
     * @return 是否成功发布了回源索引；false 表示这次构建没产出可用索引，pending 已清理
     */
    public static boolean publishPending(Context context, long bookId) {
        File dir = getHtmlCacheDir(context, bookId);
        File epubIndex = new File(dir, INDEX_CONTAINER_NAME + PENDING_SUFFIX);
        File txtIndex = new File(dir, TXT_INDEX_CONTAINER_NAME + PENDING_SUFFIX);
        File css = new File(dir, CSS_CHUNKS_CONTAINER_NAME + PENDING_SUFFIX);
        boolean epub = containerReady(epubIndex);
        boolean txt = !epub && containerReady(txtIndex);
        if (!epub && !txt) {
            dropPending(dir);
            return false;
        }
        if (epub) {
            // css_chunks.bin 可能本来就为空（全书没有外链样式）：没有 pending 就把旧缓存清掉
            replace(new File(dir, CSS_CHUNKS_CONTAINER_NAME), css);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, TXT_INDEX_CONTAINER_NAME).delete();
            renameInto(epubIndex, new File(dir, INDEX_CONTAINER_NAME));
        } else {
            //noinspection ResultOfMethodCallIgnored
            css.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, CSS_CHUNKS_CONTAINER_NAME).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, INDEX_CONTAINER_NAME).delete();
            renameInto(txtIndex, new File(dir, TXT_INDEX_CONTAINER_NAME));
        }
        return true;
    }

    /** 清掉一本未完成构建留下的 {@code *.pending}（进程被杀、解析失败、书已删除都会走到） */
    public static void dropPending(Context context, long bookId) {
        dropPending(getHtmlCacheDir(context, bookId));
    }

    private static void dropPending(File dir) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, INDEX_CONTAINER_NAME + PENDING_SUFFIX).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, TXT_INDEX_CONTAINER_NAME + PENDING_SUFFIX).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, CSS_CHUNKS_CONTAINER_NAME + PENDING_SUFFIX).delete();
    }

    /** 目标改名到位；来源不存在（或被判半成品）时删除旧目标 */
    private static void replace(File target, File source) {
        if (containerReady(source)) {
            renameInto(source, target);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        source.delete();
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }

    private static void renameInto(File src, File dst) {
        if (dst.exists() && !dst.delete()) {
            android.util.Log.w("LocalBookParser", "旧索引删不掉，改名跳过: " + dst.getName());
            return;
        }
        if (!src.renameTo(dst)) {
            // 同目录改名在 Android 上基本不会失败；真失败了（如跨挂载点）退回复制
            try {
                copyFile(src, dst);
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            } catch (Exception e) {
                android.util.Log.e("LocalBookParser", "pending 改名失败: " + e.getMessage());
            }
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (java.io.InputStream in = new FileInputStream(from);
             java.io.OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 15];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** TXT 懒解析回源索引：解码字符集 + 每章正文在源文件中的字节区间 {@code [starts[i], ends[i])}。 */
    static final class TxtIndex {
        final String charset;
        final long[] starts;
        final long[] ends;

        TxtIndex(String charset, long[] starts, long[] ends) {
            this.charset = charset;
            this.starts = starts;
            this.ends = ends;
        }

        int count() {
            return starts.length;
        }
    }

    /**
     * 写 TXT 懒解析索引：magic(4) + count(4) + charset(UTF) + 每章 {start(8), end(8)}。
     * 几千章也才十几 KB，故整份顺序写、读端一次性载入内存即可，无需分块定位。
     */
    private static void writeTxtIndex(File f, String charset, List<Chapter> chapters) {
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
            out.writeInt(CONTAINER_MAGIC);
            out.writeInt(chapters.size());
            out.writeUTF(charset == null || charset.isEmpty() ? "UTF-8" : charset);
            for (Chapter ch : chapters) {
                out.writeLong(ch.txtStart);
                out.writeLong(ch.txtEnd);
            }
        } catch (Exception e) {
            android.util.Log.e("LocalBookParser", "txtindex 写出失败: " + e.getMessage());
            // 写了一半的索引留着重读会被判损坏，直接删掉更干净（阅读器据此提示本书不可读）
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 读 TXT 懒解析索引；文件缺失/损坏/为空返回 null（调用方据此提示本书不可读）。 */
    static TxtIndex readTxtIndex(Context context, long bookId) {
        File f = new File(getHtmlCacheDir(context, bookId), TXT_INDEX_CONTAINER_NAME);
        if (!f.exists()) return null;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != CONTAINER_MAGIC) return null;
            int n = in.readInt();
            if (n <= 0) return null;
            String charset = in.readUTF();
            long[] starts = new long[n];
            long[] ends = new long[n];
            for (int i = 0; i < n; i++) {
                starts[i] = in.readLong();
                ends[i] = in.readLong();
            }
            return new TxtIndex(charset, starts, ends);
        } catch (Exception e) {
            return null;
        }
    }

    /** 有内容才写；传 null 表示该书这项为空，删除旧文件避免留下脏容器 */
    private static void writeContainerIf(File f, List<String> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        writeContainer(f, blocks);
    }

    /** 写「键/值成对」容器（块 2i=键，2i+1=值）；map 为空则删除文件 */
    private static void writePairContainer(File f, Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        List<String> blocks = new ArrayList<>(map.size() * 2);
        for (Map.Entry<String, String> e : map.entrySet()) {
            blocks.add(e.getKey());
            blocks.add(e.getValue());
        }
        writeContainer(f, blocks);
    }

    /** 读「键/值成对」容器为可变 Map；文件缺失/损坏返回空 Map（调用方按「缓存未命中」现算） */
    private static Map<String, String> readPairContainer(File f) {
        Map<String, String> out = new HashMap<>();
        if (f == null || !f.exists()) return out;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            if (raf.readInt() != CONTAINER_MAGIC) return out;
            int n = raf.readInt();
            for (int i = 0; i + 1 < n; i += 2) {
                String key = readContainerString(f, i);
                String value = readContainerString(f, i + 1);
                if (key != null && value != null) out.put(key, value);
            }
        } catch (Exception e) {
            return new HashMap<>();
        }
        return out;
    }

    /** 写出一个块容器：magic + 块数 + (块数+1) 个绝对偏移 + 依次排列的 UTF-8 块体；失败则删除半成品 */
    private static void writeContainer(File f, List<String> blocks) {
        int n = blocks.size();
        long headerEnd = 8L + (long) (n + 1) * 8L;
        long[] offs = new long[n + 1];
        try {
            long pos = headerEnd;
            try (java.io.BufferedOutputStream out =
                         new java.io.BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
                // 先占位文件头（偏移量要等写完才知道，稍后回填）
                out.write(new byte[(int) headerEnd]);
                for (int i = 0; i < n; i++) {
                    byte[] b = utf8Bytes(blocks.get(i));
                    offs[i] = pos;
                    pos += b.length;
                    out.write(b);
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
            android.util.Log.e("LocalBookParser", "容器写出失败 " + f.getName() + ": " + e.getMessage());
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

    /**
     * 读某本书的「每章回源定位」索引，返回每章 {zip 条目名, 所在目录}；
     * 无索引（旧版物化的书、TXT）返回 null。
     * 懒解析链路用它 + 源 epub 定位单章原文，条目顺序与章节顺序一致。
     */
    static List<String[]> readChapterIndex(Context context, long bookId) {
        File f = new File(getHtmlCacheDir(context, bookId), INDEX_CONTAINER_NAME);
        if (!f.exists()) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            if (raf.readInt() != CONTAINER_MAGIC) return null;
            int n = raf.readInt();
            if (n <= 0) return null;
            List<String[]> out = new ArrayList<>(n / 2);
            for (int i = 0; i + 1 < n; i += 2) {
                String entry = readContainerString(f, i);
                String dir = readContainerString(f, i + 1);
                if (entry == null || entry.isEmpty()) return null;
                out.add(new String[]{entry, dir == null ? "" : dir});
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 懒解析用的「样式片段键 → 作用域化结果」缓存 */
    static Map<String, String> readCssChunkCache(Context context, long bookId) {
        return readPairContainer(new File(getHtmlCacheDir(context, bookId), CSS_CHUNKS_CONTAINER_NAME));
    }

    /** 读容器某一块并解码为 UTF-8；块缺失/损坏返回 null（区别于「块存在但为空」的空串） */
    private static String readContainerString(File container, int idx) {
        byte[] b = readContainerBlock(container, idx);
        if (b == null) return null;
        if (b.length == 0) return "";
        try {
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 删除某本本地书的 HTML 缓存目录（index.bin / txtindex.bin / css_chunks.bin），递归整棵删。
     * 删除本地书时调用，避免重导入生成新 bookId 后旧目录残留成为孤儿文件；失败仅记录日志，不抛出。
     * EPUB 插图已改为 epubres:// 协议引用，零落盘，无需额外清理。
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

    /**
     * 删掉这本书落在 filesDir 根下的封面（{@code cover_<bookId>.<ext>}）。
     * 删书时随正文索引一起释放，否则封面会永久留在磁盘上（重导入是新 bookId，不会再覆盖它）。
     */
    public static void deleteCoverFiles(Context context, long bookId) {
        deleteOldCover(context, bookId);
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
     * 清扫孤儿缓存：local_book_html/ 与 local_book_img/ 下的子目录、以及 filesDir 根下的
     * {@code cover_<bookId>.*}，凡 bookId 不在 local_books 中的一律删除。
     * 用于兜住"重导入同一本书（新 bookId）但未先删除旧书"导致的残留，
     * 以及删书时未勾选「清除书籍缓存」留下的那部分。
     * 建议在后台线程调用；失败仅记录日志，不抛出。
     */
    public static void cleanupOrphanHtmlCache(Context context) {
        File filesDir = context.getFilesDir();
        Set<String> validIds = shelfBookIds(context);
        cleanupOrphanDirsUnder(new File(filesDir, "local_book_html"), validIds);
        cleanupOrphanDirsUnder(new File(filesDir, "local_book_img"), validIds);
        cleanupOrphanCovers(filesDir, validIds);
    }

    /** 书架（local_books）在册的 bookId 集合，孤儿判定以它为准 */
    private static Set<String> shelfBookIds(Context context) {
        SharedPreferences sp = context.getSharedPreferences("local_books", Context.MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            long id = sp.getLong("book_id_" + i, 0);
            if (id > 0) ids.add(String.valueOf(id));
        }
        return ids;
    }

    /**
     * 清扫 filesDir 根下的孤儿封面（{@code cover_<bookId>.<ext>}）。
     * 删书时不勾「清除书籍缓存」的封面就靠这里回收，否则「保留」会变成永久残留。
     *
     * <p>宽限期是必需的：导入先把封面落到 filesDir、后写 local_books，
     * 正在导入的那本会被判成孤儿。书架页重建恰好可能落在这个窗口里。
     */
    private static void cleanupOrphanCovers(File filesDir, Set<String> validIds) {
        File[] files = filesDir.listFiles((d, name) -> name.startsWith("cover_"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - COVER_ORPHAN_GRACE_MS;
        int removed = 0;
        for (File f : files) {
            String id = f.getName().substring("cover_".length()).replaceFirst("\\..*$", "");
            if (validIds.contains(id) || f.lastModified() > cutoff) continue;
            if (f.delete()) removed++;
        }
        if (removed > 0) {
            android.util.Log.i("LocalBookParser", "已清理 " + removed + " 个孤儿封面");
        }
    }

    /** 删除 root 下所有目录名（bookId）不在 local_books 中的子目录 */
    private static void cleanupOrphanDirsUnder(File root, Set<String> validIds) {
        if (!root.exists() || !root.isDirectory()) return;

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
                    android.util.Log.e("LocalBookParser", "清理孤儿缓存目录失败: " + d.getName());
                }
            }
        }
        if (removed > 0) {
            android.util.Log.i("LocalBookParser", "已清理 " + removed + " 个孤儿缓存目录（" + root.getName() + "）");
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
    private static void parseNcxVolumes(String ncxXml, Map<String, String> fileToVol, List<String> vols,
                                        Map<String, String> titleOut) {
        if (ncxXml == null) return;
        parseNavLevel(ncxXml, "", fileToVol, vols, titleOut);
    }

    private static void parseNavLevel(String xml, String curVol, Map<String, String> fileToVol,
                                      List<String> vols, Map<String, String> titleOut) {
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
                // 标题与分卷同遍收出，同为「后到覆盖」，与原 NCX 标题正则等价
                if (titleOut != null && !label.isEmpty()) titleOut.put(fn, label);
            }
            // 递归处理子节点
            if (childStart >= 0) parseNavLevel(block, vol, fileToVol, vols, titleOut);
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

    /**
     * 从 EPUB3 导航文档（nav）收集「文件名 → 目录标题」。
     *
     * @param navPath 已由 OPF 的 properties="nav" 定位到的条目；为 null 时退化为扫描全部 XHTML 找目录页
     */
    private static void collectTocTitlesFromNav(ZipSource zipEntries, String navPath,
                                                Map<String, String> titleOut) {
        for (String entryName : zipEntries.keySet()) {
            if (navPath != null) {
                if (!entryName.equals(navPath)) continue;
            } else if (!entryName.endsWith(".xhtml") && !entryName.endsWith(".html")) {
                continue;
            }
            byte[] data = zipEntries.get(entryName);
            // 先在字节层面探测目录标记，再解码：旧写法为了找一页目录会把 zip 里所有 XHTML 解码一遍
            if (!(containsAscii(data, "epub:type=\"toc\"") || containsAscii(data, "class=\"toc\""))) continue;
            try {
                String navHtml = new String(data, "UTF-8");
                Matcher navM = Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
                        Pattern.CASE_INSENSITIVE).matcher(navHtml);
                while (navM.find()) {
                    String fileName = epubBaseName(navM.group(1));
                    String title = cleanHtmlText(navM.group(2)).trim();
                    if (!titleOut.containsKey(fileName)) titleOut.put(fileName, title);
                }
            } catch (Exception e) {
                // 忽略单个导航文档解析失败
            }
        }
    }

    /** 字节流中是否出现给定 ASCII 片段（待探测的标记都是纯 ASCII，无需先把整文件解码） */
    private static boolean containsAscii(byte[] data, String needle) {
        if (data == null || data.length == 0) return false;
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + n.length <= data.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (data[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
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

    /**
     * 已知文件大小时按精确容量一次读满，避开 {@link java.io.ByteArrayOutputStream} 的翻倍扩容与
     * {@code toByteArray()} 再拷贝（12MB 的书这两步能把读取阶段瞬时占用推到 ~28MB）。
     * 拿不到大小（provider 不支持 openFileDescriptor / 非常规源）就退回 {@link #readAllBytes}。
     */
    private static byte[] readAllBytesSized(Context context, Uri uri, InputStream is) throws Exception {
        long size = -1;
        try (android.os.ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) size = pfd.getStatSize();
        } catch (Exception ignore) { /* 拿不到大小就走通用流式读 */ }
        if (size <= 0 || size > Integer.MAX_VALUE - 8) return readAllBytes(is);
        return readExactly(is, (int) size);
    }

    /** 读满 {@code size} 字节；若源比预期短则截到实际长度（大小只是提示，不能盲信）。 */
    private static byte[] readExactly(InputStream is, int size) throws Exception {
        byte[] buf = new byte[size];
        int off = 0;
        while (off < size) {
            int n = is.read(buf, off, size - off);
            if (n < 0) break;
            off += n;
        }
        return (off == size) ? buf : java.util.Arrays.copyOf(buf, off);
    }

    /**
     * 该字符集能否靠「扫描 0x0A 字节」安全地切行——即 0x0A 绝不会作为多字节序列的一部分出现。
     * UTF-8（续字节 0x80~0xBF）与 GBK/GB18030（第二字节 0x40~0xFE）都满足；UTF-16 不满足
     * （0x0A 会是码元的高/低字节），故 UTF-16 书不走懒解析，维持整本物化。
     */
    private static boolean isByteScanSafeCharset(String cs) {
        if (cs == null) return false;
        String u = cs.toUpperCase(java.util.Locale.ROOT);
        return u.equals("UTF-8") || u.equals("UTF8")
                || u.equals("GBK") || u.equals("GB2312") || u.equals("GB18030");
    }

    /**
     * 扫描原始字节，算出每一行的起始字节偏移，末位追加一个「数据长度」哨兵。
     * 返回数组长度 = (0x0A 出现次数 + 1) + 1；第 k 项是第 k 行的起始字节，最后一项是 {@code data.length}。
     * 与 {@code new String(data, cs).split("\n")} 的行号一一对应（尾部空行被 split 丢弃时，
     * 其起始偏移仍在数组里，取用时行号不会越界）。仅可对 {@link #isByteScanSafeCharset} 为真的字节调用。
     */
    private static int[] computeLineByteStarts(byte[] data) {
        int n = data.length;
        int rows = 1;
        for (int i = 0; i < n; i++) if (data[i] == 0x0A) rows++;
        int[] starts = new int[rows + 1];
        starts[0] = 0;
        int k = 1;
        for (int i = 0; i < n; i++) {
            if (data[i] == 0x0A) starts[k++] = i + 1;
        }
        starts[rows] = n;
        return starts;
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
     * 「封面/目录」两条判据共用的文字长度天花板（较大那个）。
     * 去标签扫描以此封顶即可判定两条都不成立，省掉每章两次全文计数扫描。
     */
    private static final int SKIP_TEXT_MAX_CHARS = 2000;

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
            // 计数与「去标签后 trim 长度」改用线性扫描：原先 RE_IMG_OPEN.split / RE_A_OPEN.split 会对整章
            //    建数组、RE_TAGS.replaceAll 会再整章新建字符串，每章三次全文正则操作是导入耗时大头之一。
            //    字面量大小写敏感，与 RE_IMG_OPEN("<img") / RE_A_OPEN("<a ") / RE_TAGS("<[^>]+>") 完全一致。
            int imgCount = countOccurrences(html, "<img");
            int textLength = tagStrippedTrimmedLength(html, SKIP_TEXT_MAX_CHARS);

            // 如果图片很多但文字很少，可能是封面
            if (imgCount >= 3 && textLength < 500) {
                Log.d("LocalBookParser", "Detected cover by image count: " + imgCount + ", text length: " + textLength);
                return true;
            }

            // 目录通常包含大量链接
            int linkCount = countOccurrences(html, "<a ");
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