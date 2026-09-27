package com.example.readingapp.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT 小说解析器（纯 Java，无 Spring 依赖，便于单独测试）。
 *
 * <p>覆盖能力：
 * <ul>
 *   <li>编码嗅探：UTF-8/UTF-16 BOM、UTF-8 严格校验、GB18030 兜底（中文 txt 常见）</li>
 *   <li>元数据源优先级：手动输入 &gt; 文件正文标注 &gt; 文件名 &gt; 文件名主干</li>
 *   <li>文件名解析：《书名》(作者).txt / 书名(作者).txt / 书名-作者.txt，自动剔除站点广告词</li>
 *   <li>目录页识别：跳过没有正文的目录行，避免把目录当章节</li>
 *   <li>章节切分：按"行首"匹配，杜绝正文中"第X章/第X回合"误切</li>
 *   <li>盗版广告：剔除广告行、合并被广告切断的重复章节、识别作者单章</li>
 *   <li>分卷：显式"第X卷"标记 + 章节号重开检测</li>
 * </ul>
 */
public final class TxtBookParser {

    private TxtBookParser() {}

    // ==================== 数据模型 ====================

    /** 解析出的一个章节 */
    public static final class RawChapter {
        public String title;
        public final StringBuilder content = new StringBuilder();
        /** 章节编号，无法识别为 -1 */
        public int number = -1;
        /** 卷序号，从 1 开始 */
        public int volumeLevel = 1;
        /** 排序键，形如 "1-0001" */
        public String sortKey;
        /** 是否为作者单章/公告（不参与编号与分卷判断） */
        public boolean note;

        public String contentText() {
            return content.toString().trim();
        }
    }

    /** 解析结果 */
    public static final class ParseResult {
        public String title = "";
        public String author = "";
        public String intro = "";
        public String encoding = "";
        public String titleSource = "";
        public String authorSource = "";
        public final List<RawChapter> chapters = new ArrayList<>();
        /** 卷名列表，下标 = 卷序号 - 1 */
        public final List<String> volumeTitles = new ArrayList<>();
        public int adLinesRemoved;
        public int mergedChapters;
        public int totalLines;
        /** 诊断日志（分卷/合并等决策，便于排查） */
        public final List<String> logs = new ArrayList<>();

        public int chapterCount() {
            return chapters.size();
        }
    }

    // ==================== 常量与正则 ====================

    /** 中文数字字符集（故意不含"道/化"等常用字，避免"第一道化形雷劫卷起"被误判成卷） */
    private static final String NUM = "\\d〇零一二三四五六七八九十百千万亿两廿";
    private static final String NUM_CLS = "[" + NUM + "]";
    /** 标题分隔符（含全角空格 U+3000，中文 txt 常见） */
    private static final String SEP = "[\\s\\u3000\\u00b7\\u2027:：、.．\\-—–_*]*";

    /** 卷：第X卷/第X部 [卷名] */
    private static final Pattern RE_VOLUME = Pattern.compile(
            "^\\s*第\\s*(" + NUM_CLS + "{1,12})\\s*([卷部])(" + SEP + ")([^\\n]{0,30})$");

    /** 章：第X[章回节篇集] [标题] */
    private static final Pattern RE_CHAPTER = Pattern.compile(
            "^\\s*第\\s*(" + NUM_CLS + "{1,12})\\s*([章节回節篇集])(" + SEP + ")([^\\n]{0,60})$");

    /** 带分卷前缀的章：第一部分第002回 标题 / 卷三第12章 标题 */
    private static final Pattern RE_PREFIXED_CHAPTER = Pattern.compile(
            "^\\s*(第\\s*" + NUM_CLS + "{1,12}\\s*[部卷篇集](?:分|份)?|卷\\s*" + NUM_CLS + "{1,12}|"
                    + "[上中下]卷|正文卷|作品相关|番外卷|正文)\\s*"
                    + "第\\s*(" + NUM_CLS + "{1,12})\\s*([章节回節篇集])(" + SEP + ")([^\\n]{0,60})$");

    /** 特殊章节：序章/楔子/尾声/番外… */
    private static final Pattern RE_SPECIAL = Pattern.compile(
            "^\\s*(序章|序言|自序|楔子|引子|前言|尾声|后记|後記|终章|終章|结语|結語|番外|外传|外傳|特别篇|特別篇|后序|後序|序)("
                    + SEP + ")([^\\n]{0,40})$");

    /** 英文章节：Chapter 12 / Part III */
    private static final Pattern RE_EN = Pattern.compile(
            "^\\s*(?i)(chapter|chap|part|section)\\s*\\.?\\s*([0-9]{1,5}|[ivxlcdmIVXLCDM]{1,7})\\b[^\\n]{0,60}$");

    /** 强广告行（整行丢弃） */
    private static final Pattern RE_AD_STRONG = Pattern.compile(
            "为您提供|为您提|最快更新|最新章节|免费阅读|全文阅读|笔趣|www\\.|https?://|手机阅读|"
                    + "顶点小说|八一中文|起点中文网|纵横中文网|小说网|本书首发|本章免费|『』|"
                    + "(?:txt|TXT|epub|EPUB)[^\\n]{0,8}下载|下载[^\\n]{0,8}(?:txt|TXT|epub|EPUB)|请收藏");

    /** 弱广告/运营行（仅短行丢弃，避免误删正文） */
    private static final Pattern RE_AD_WEAK = Pattern.compile(
            "月票|推荐票|求推荐|求订阅|加入书架|分享到|本章说|投推荐|求收藏");

    /** 作者单章 / 公告 */
    private static final Pattern RE_NOTE = Pattern.compile(
            "在审核|请假|停更|断更|感言|上架|公告|通知|书友群|更新(?:时间|通知)|今天(?:一|二|三|四)?更|"
                    + "求票|月票|推荐票|催更|加更|爆更|打赏|订阅|感谢(?:盟主|白银|书友|大家)|万订|均订");

    /** 内容里的元数据标注行 */
    private static final Pattern[] RE_TITLE_LABEL = {
            Pattern.compile("^\\s*(?:书名|作品名|小说名|书名：|标题|名称|书\\s*名)\\s*[:：\\s]\\s*(.{1,60})$"),
            Pattern.compile("^\\s*(?:《)([^》]{1,60})(?:》)\\s*$"),
    };
    private static final Pattern[] RE_AUTHOR_LABEL = {
            Pattern.compile("^\\s*(?:作\\s*者|著\\s*者|作者名|写手|笔名)\\s*[:：\\s]\\s*(.{1,30})$"),
            Pattern.compile("^\\s*(.{1,20}?)\\s*(?:著|作品|创作)\\s*$"),
            Pattern.compile("^\\s*[【\\[(（]\\s*(?:作者|著)\\s*[:：]?\\s*(.{1,30}?)\\s*[】\\])）]\\s*$"),
    };

    /** 明显不是书名的噪音词 */
    private static final Pattern RE_JUNK_TITLE = Pattern.compile(
            "(?:全集|完本|完结|全文|校对|精校|无删减?|纯净版|整理|转自|来自|下载|更新|最新|"
                    + "目录|章节|小说网|笔趣|首发|推荐|收藏|阅读|书签|txt|TXT|epub|EPUB|"
                    + "第\\s*" + NUM_CLS + "{1,12}\\s*[章节回卷部篇集])");

    /** 纯符号行 */
    private static final Pattern RE_PURE_SYMBOL = Pattern.compile("^[-=_*·—–\\s.。，,、|\\\\/~＃#]+$");

    /** 装饰框字符（合集 TXT 的「〓作者〓　　醛石　　〓」信息块用它围边） */
    private static final Pattern RE_FRAME_CHAR = Pattern.compile("[〓▬◆●■□★☆※═─━┃]");

    /** 「作者：」「著者 」「笔名：」等前缀 */
    private static final String AUTHOR_PREFIX = "(?:作\\s*者|著\\s*者|作者名|写手|笔名|著)";

    /** 文件名里的站点垃圾 */
    private static final Pattern[] RE_FILENAME_JUNK = {
            Pattern.compile("(?i)\\[[^\\]\\[]{0,40}(?:小说|文学|书屋|阁|网|下载|整理|校对|首发)[^\\]\\[]{0,40}\\]"),
            Pattern.compile("(?i)[（(\\[【][^）)\\]】]{0,40}(?:小说网|文学网|书屋|笔趣阁|全文阅读|免费下载|"
                    + "txt下载|最新章节|完本|全集|校对|精校|无删减|纯净版|整理|首发)[^）)\\]】]{0,40}[）)\\]】]"),
            Pattern.compile("(?i)(?:www\\.)?[a-z0-9-]{1,30}\\.(?:com|cn|net|org|cc|xyz|co|me)(?:\\.[a-z]{2,3})?"),
            Pattern.compile("(?i)(笔趣阁|八一中文|顶点小说|起点中文网|纵横中文网|番茄小说|小说下载|"
                    + "全文阅读|免费下载|txt下载|最新章节目录|最新章节|全集下载|精校版|无删减版)"),
    };

    // ==================== 对外入口 ====================

    /**
     * 解析 TXT 全文。
     *
     * @param rawBytes  原始字节（用于编码嗅探）
     * @param fileName  原始文件名（用于提取书名/作者，可为空）
     * @param hintTitle 手动填写的书名（优先）
     * @param hintAuthor 手动填写的作者（优先）
     */
    public static ParseResult parse(byte[] rawBytes, String fileName, String hintTitle, String hintAuthor) {
        String encoding = detectEncoding(rawBytes);
        String text = new String(rawBytes, charsetOf(encoding));
        if (encoding.startsWith("UTF-16")) {
            text = new String(rawBytes, charsetOf(encoding));
        } else {
            int off = bomLength(rawBytes);
            if (off > 0) {
                text = new String(rawBytes, off, rawBytes.length - off, charsetOf(encoding));
            }
        }
        ParseResult r = parseText(text, fileName, hintTitle, hintAuthor);
        r.encoding = encoding;
        return r;
    }

    /** 解析已解码的文本 */
    public static ParseResult parseText(String rawText, String fileName, String hintTitle, String hintAuthor) {
        ParseResult r = new ParseResult();
        String text = normalize(rawText);
        String[] lines = text.split("\n", -1);
        r.totalLines = lines.length;

        // ---- 1. 定位所有章节标题行 ----
        List<Match> titleLines = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.isEmpty()) continue;
            Match m = matchTitleLine(s);
            if (m != null) {
                m.lineIndex = i;
                m.note = isNoteLine(s);
                titleLines.add(m);
            }
        }

        // ---- 2. 跳过目录页，定位正文起点 ----
        int bodyStart = locateBodyStart(lines, titleLines);

        // ---- 3. 元数据 ----
        String[] fn = parseFileName(fileName);
        String fnTitle = fn[0];
        String fnAuthor = fn[1];
        String fnStem = fn[2];

        Map<String, String> cm = extractMetaFromPreface(lines, bodyStart);
        String cTitle = cm.get("title");
        String cAuthor = cm.get("author");

        if (notBlank(hintTitle)) {
            r.title = hintTitle.trim();
            r.titleSource = "手动输入";
        } else if (notBlank(cTitle)) {
            r.title = cTitle;
            r.titleSource = "正文标注";
        } else if (notBlank(fnTitle)) {
            r.title = fnTitle;
            r.titleSource = "文件名";
        } else if (notBlank(fnStem)) {
            r.title = fnStem;
            r.titleSource = "文件名主干";
        }

        if (notBlank(hintAuthor)) {
            r.author = hintAuthor.trim();
            r.authorSource = "手动输入";
        } else if (notBlank(cAuthor)) {
            r.author = cAuthor;
            r.authorSource = "正文标注";
        } else if (notBlank(fnAuthor)) {
            r.author = fnAuthor;
            r.authorSource = "文件名";
        } else {
            r.author = "佚名";
            r.authorSource = "默认值";
        }

        r.title = cleanBookTitle(r.title);
        r.author = cleanAuthor(r.author);
        r.intro = extractIntro(lines, bodyStart, r.title, r.author);

        // ---- 4. 切分章节 ----
        splitChapters(lines, titleLines, bodyStart, r);
        return r;
    }

    // ==================== 编码嗅探 ====================

    public static String detectEncoding(byte[] data) {
        if (data == null || data.length == 0) return "UTF-8";
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) return "UTF-16LE";
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) return "UTF-16BE";
        if (looksLikeUtf8(data)) return "UTF-8";
        return "GB18030";
    }

    /** BOM 长度（字节） */
    public static int bomLength(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) return 3;
        if (data.length >= 2 && ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE)) return 2;
        if (data.length >= 2 && ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF)) return 2;
        return 0;
    }

    private static Charset charsetOf(String name) {
        switch (name) {
            case "UTF-16LE": return StandardCharsets.UTF_16LE;
            case "UTF-16BE": return StandardCharsets.UTF_16BE;
            case "GB18030":
                try {
                    return Charset.forName("GB18030");
                } catch (Exception e) {
                    return Charset.forName("GBK");
                }
            default: return StandardCharsets.UTF_8;
        }
    }

    /** 严格 UTF-8 校验（采样前 512KB，回退到 ASCII 边界避免截断多字节序列） */
    private static boolean looksLikeUtf8(byte[] data) {
        int end = Math.min(data.length, 512 * 1024);
        while (end > 0 && (data[end - 1] & 0x80) != 0) end--;
        int i = 0;
        int multiByte = 0;
        while (i < end) {
            int b = data[i] & 0xFF;
            int need;
            if (b < 0x80) {
                i++;
                continue;
            } else if (b >= 0xC2 && b <= 0xDF) {
                need = 1;
            } else if (b >= 0xE0 && b <= 0xEF) {
                need = 2;
            } else if (b >= 0xF0 && b <= 0xF4) {
                need = 3;
            } else {
                return false; // 0x80–0xC1 / 0xF5–0xFF 非法首字节
            }
            if (i + need >= end) return false;
            for (int k = 1; k <= need; k++) {
                if ((data[i + k] & 0xC0) != 0x80) return false;
            }
            // 超长编码 / 代理区 / 超范围
            if (b == 0xE0 && (data[i + 1] & 0xE0) == 0x80) return false;
            if (b == 0xED && (data[i + 1] & 0xE0) == 0xA0) return false;
            if (b == 0xF0 && (data[i + 1] & 0xF0) == 0x80) return false;
            if (b == 0xF4 && (data[i + 1] & 0xF0) > 0x80) return false;
            multiByte++;
            i += need + 1;
        }
        // 有非 ASCII 字节但解码不出任何多字节序列 → 多半是 GBK
        boolean hasHigh = false;
        for (int k = 0; k < end; k++) {
            if ((data[k] & 0x80) != 0) {
                hasHigh = true;
                break;
            }
        }
        return !(hasHigh && multiByte == 0);
    }

    // ==================== 文本规范化 ====================

    private static String normalize(String text) {
        if (text == null) return "";
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        t = t.replace("\uFEFF", "");
        return t;
    }

    // ==================== 标题行识别 ====================

    private static final int TYPE_CHAPTER = 1;
    private static final int TYPE_VOLUME = 2;
    private static final int TYPE_SPECIAL = 3;
    private static final int TYPE_EN = 4;

    private static final class Match {
        int type;
        int number = -1;
        /** 分卷前缀，如"第一部分"，无则为空 */
        String prefix = "";
        int lineIndex;
        /** 作者单章/公告：不参与编号与分卷判断 */
        boolean note;
    }

    /** 判断某一行是否为章节/卷标题行 */
    private static Match matchTitleLine(String s) {
        if (s.isEmpty() || s.length() > 80) return null;

        Matcher mv = RE_VOLUME.matcher(s);
        if (mv.matches() && plausibleVolumeLine(s, mv.group(3), mv.group(4))) {
            Match m = new Match();
            m.type = TYPE_VOLUME;
            m.number = chineseToInt(mv.group(1));
            return m;
        }

        Matcher mc = RE_CHAPTER.matcher(s);
        if (mc.matches()) {
            String unit = mc.group(2);
            String sep = mc.group(3);
            String rest = mc.group(4);
            if (!plausibleChapterLine(s, sep, rest)) return null;
            if (("回".equals(unit) || "節".equals(unit) || "节".equals(unit)) && sep.isEmpty() && !rest.isEmpty()) {
                return null;
            }
            Match m = new Match();
            m.type = TYPE_CHAPTER;
            m.number = safeNumber(chineseToInt(mc.group(1)));
            return m;
        }

        Matcher mp = RE_PREFIXED_CHAPTER.matcher(s);
        if (mp.matches()) {
            String unit = mp.group(3);
            String sep = mp.group(4);
            if (("回".equals(unit) || "節".equals(unit) || "节".equals(unit)) && sep.isEmpty()) {
                return null;
            }
            Match m = new Match();
            m.type = TYPE_CHAPTER;
            m.number = safeNumber(chineseToInt(mp.group(2)));
            m.prefix = mp.group(1) == null ? "" : mp.group(1).trim();
            return m;
        }

        // 走到这里说明不是标准章节行：带"免费阅读/最快更新"等广告字样的一律不算标题
        // （如"为您提供大神XX的《YY》最快更新，！"）
        if (isAdLine(s)) return null;

        Matcher ms = RE_SPECIAL.matcher(s);
        if (ms.matches()) {
            Match m = new Match();
            m.type = TYPE_SPECIAL;
            return m;
        }

        Matcher me = RE_EN.matcher(s);
        if (me.matches()) {
            Match m = new Match();
            m.type = TYPE_EN;
            try {
                m.number = Integer.parseInt(me.group(2));
            } catch (Exception ignore) {
                m.number = -1;
            }
            return m;
        }
        return null;
    }

    /**
     * 排除"第X回合开始。""第一回约大师兄…"这类正文句子。
     * 单位后紧跟汉字时，要求行足够短且不含句读标点。
     */
    private static boolean plausibleChapterLine(String line, String sep, String rest) {
        if (rest.isEmpty()) return true;
        char c0 = rest.charAt(0);
        boolean cjk = (c0 >= '\u4e00' && c0 <= '\u9fff');
        if (!sep.isEmpty() || !cjk) return true;
        return line.length() <= 20 && !containsSentencePunct(rest);
    }

    private static boolean containsSentencePunct(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '，' || c == '！' || c == '？' || c == '；' || c == '：' || c == ',' || c == '.') {
                return true;
            }
        }
        return false;
    }

    /** 章节号合理性上限：超过则视为误解析（如"第万章"→10000） */
    private static final int MAX_CHAPTER_NUMBER = 20000;

    private static int safeNumber(int n) {
        return (n > 0 && n <= MAX_CHAPTER_NUMBER) ? n : -1;
    }

    /**
     * 排除"第四部分第100回""第三部分是另一段…"这类正文句子被当成卷。
     * 要求：单位后有分隔符、或整行只有卷名且够短。
     */
    private static boolean plausibleVolumeLine(String line, String sep, String rest) {
        if (rest.startsWith("分")) return false;   // 第X部分…
        if (rest.isEmpty()) return true;
        if (!sep.isEmpty()) return line.length() <= 40;
        char c0 = rest.charAt(0);
        boolean cjk = (c0 >= '\u4e00' && c0 <= '\u9fff');
        if (!cjk) return line.length() <= 40;
        // 单位后直接跟汉字：只有很短且无句读才认
        return line.length() <= 16 && !containsSentencePunct(rest);
    }

    private static boolean isAdLine(String s) {
        if (RE_AD_STRONG.matcher(s).find()) return true;
        return s.length() <= 30 && RE_AD_WEAK.matcher(s).find();
    }

    private static boolean isNoteLine(String s) {
        return s.length() <= 40 && RE_NOTE.matcher(s).find();
    }

    // ==================== 正文起点（跳过目录） ====================

    private static int locateBodyStart(String[] lines, List<Match> titleLines) {
        if (titleLines.isEmpty()) return -1;
        for (int k = 0; k < titleLines.size(); k++) {
            int idx = titleLines.get(k).lineIndex;
            int next = (k + 1 < titleLines.size()) ? titleLines.get(k + 1).lineIndex : lines.length;
            if (measureBody(lines, idx + 1, next) >= 50) return idx;
        }
        return titleLines.get(0).lineIndex;
    }

    private static int measureBody(String[] lines, int from, int to) {
        int len = 0;
        for (int i = from; i < to && i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.isEmpty() || isAdLine(s)) continue;
            len += s.length();
            if (len >= 200) return len;
        }
        return len;
    }

    // ==================== 元数据：文件名 ====================

    /**
     * @return {书名, 作者, 清理后的文件名主干}
     */
    public static String[] parseFileName(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        n = n.replaceAll("(?i)\\.(txt|epub|umd|azw3|mobi|doc|docx|pdf)$", "");
        for (Pattern p : RE_FILENAME_JUNK) {
            n = p.matcher(n).replaceAll("");
        }
        n = n.replaceAll("[\\s\\-—_\\.]+$", "").replaceAll("^[\\s\\-—_\\.]+", "").trim();
        if (n.isEmpty()) return new String[]{"", "", ""};

        String title = null;
        String author = null;

        // 《书名》 + 可选作者
        Matcher m1 = Pattern.compile("^\\s*《([^》]{1,60})》\\s*(.*)$").matcher(n);
        if (m1.matches()) {
            title = m1.group(1).trim();
            String tail = m1.group(2).trim();
            if (!tail.isEmpty()) {
                author = authorFromTail(tail);
            }
        } else {
            // 书名(作者) / 书名【作者】
            Matcher m2 = Pattern.compile("^(.+?)\\s*[\\(（\\[【]\\s*([^\\s\\(\\（\\[【）)】\\]]{1,20})\\s*[\\)）\\]】]\\s*$").matcher(n);
            if (m2.matches()) {
                title = m2.group(1).trim();
                if (!isJunkToken(m2.group(2))) author = m2.group(2).trim();
            } else {
                // 书名 - 作者（后半段必须很短且像人名）
                Matcher m3 = Pattern.compile("^(.+?)\\s*[-－—_]\\s*([^\\s\\-－—_]{1,12})\\s*$").matcher(n);
                if (m3.matches() && !isJunkToken(m3.group(2)) && looksLikeName(m3.group(2))) {
                    title = m3.group(1).trim();
                    author = m3.group(2).trim();
                } else {
                    title = n;
                }
            }
        }

        if (title != null) {
            title = title.replaceAll("[《》]", "").trim();
            // 书名尾部若还有括号包裹的作者
            Matcher mt = Pattern.compile("^(.+?)\\s*[\\(（\\[【]\\s*([^\\s\\(\\（\\[【）)】\\]]{1,20})\\s*[\\)）\\]】]\\s*$").matcher(title);
            if (mt.matches() && (author == null || author.isEmpty()) && !isJunkToken(mt.group(2))) {
                title = mt.group(1).trim();
                author = mt.group(2).trim();
            }
            if (isJunkToken(title)) title = n.replaceAll("[《》]", "").trim();
        }
        String stem = n.replaceAll("[《》]", "").replaceAll("\\s*[\\(（\\[【][^\\(\\（\\[【）)】\\]]*[\\)）\\]】]\\s*", "").trim();
        return new String[]{
                title == null ? "" : title,
                author == null ? "" : author,
                stem.isEmpty() ? (title == null ? "" : title) : stem
        };
    }

    private static String authorFromTail(String tail) {
        String t = tail.trim();
        Matcher m = Pattern.compile("^[\\(（\\[【]\\s*([^\\(\\（\\[【）)】\\]]{1,20})\\s*[\\)）\\]】]$").matcher(t);
        if (m.matches() && !isJunkToken(m.group(1))) return m.group(1).trim();
        // 「作者：醛石」「著者 醛石」「笔名：醛石」——合集文件名的主流写法，先剥前缀再判定，
        // 否则「作者：醛石」会因含全角冒号被 looksLikeName 的标点规则拒掉
        t = stripAuthorPrefix(t).replaceAll("^[:：\\s]+", "").trim();
        if (!t.isEmpty() && t.length() <= 20 && !isJunkToken(t) && looksLikeName(t)) return t;
        return "";
    }

    /**
     * 剥掉「作者：」「著者 」「笔名：」这类前缀（分隔符必需，避免把「作者简介」剥成「简介」），
     * 顺带清掉尾部的「著 / 作品 / 创作」。
     */
    private static String stripAuthorPrefix(String s) {
        if (s == null) return "";
        return s.replaceFirst("^\\s*" + AUTHOR_PREFIX + "\\s*[:：\\s]\\s*", "")
                .replaceFirst("\\s*(?:著|作品|创作)$", "")
                .trim();
    }

    private static boolean isJunkToken(String s) {
        if (s == null || s.trim().isEmpty()) return true;
        String t = s.trim();
        if (t.length() > 20) return true;
        if (RE_JUNK_TITLE.matcher(t).find()) return true;
        if (RE_PURE_SYMBOL.matcher(t).matches()) return true;
        long digits = t.chars().filter(Character::isDigit).count();
        return digits > t.length() / 2;
    }

    /** 粗略判断是否像作者名：以中英文开头，不含标点噪音 */
    private static boolean looksLikeName(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 20) return false;
        char c = t.charAt(0);
        if (!(Character.isLetter(c) || (c >= '\u4e00' && c <= '\u9fff'))) return false;
        return !t.matches(".*[。，！？、；：\"'“”‘’].*");
    }

    // ==================== 元数据：正文前言区 ====================

    private static Map<String, String> extractMetaFromPreface(String[] lines, int bodyStart) {
        Map<String, String> meta = new HashMap<>();
        int limit = bodyStart > 0 ? Math.min(bodyStart, lines.length) : Math.min(lines.length, 80);
        int scan = Math.min(limit, 120);
        for (int i = 0; i < scan; i++) {
            String s = lines[i].trim();
            if (s.isEmpty() || RE_PURE_SYMBOL.matcher(s).matches()) continue;
            if (isAdLine(s)) continue;
            if (matchTitleLine(s) != null) continue;
            // 装饰框信息行（「〓作者〓　　醛石　　〓」）归一成「作者 醛石」后再匹配标签
            String label = stripDecorFrame(s);
            if (label.isEmpty()) continue;

            if (!meta.containsKey("title")) {
                for (Pattern p : RE_TITLE_LABEL) {
                    Matcher m = p.matcher(label);
                    if (m.matches()) {
                        String cand = m.group(1).trim();
                        if (!isJunkToken(cand) && !RE_JUNK_TITLE.matcher(cand).find()) {
                            meta.put("title", cand);
                            break;
                        }
                    }
                }
            }
            if (!meta.containsKey("author")) {
                for (Pattern p : RE_AUTHOR_LABEL) {
                    Matcher m = p.matcher(label);
                    if (m.matches()) {
                        String cand = m.group(1).trim();
                        cand = cand.replaceAll("^[:：\\s]+", "").trim();
                        if (!cand.isEmpty() && cand.length() <= 20 && !isJunkToken(cand) && looksLikeName(cand)) {
                            meta.put("author", cand);
                            break;
                        }
                    }
                }
            }
            if (meta.containsKey("title") && meta.containsKey("author")) break;
        }
        return meta;
    }

    /**
     * 把「〓作者〓　　醛石　　〓」这类装饰框行归一成「作者 醛石」。
     * 只处理短行且必须含装饰符，普通正文行原样返回 —— 不动正文判定。
     */
    private static String stripDecorFrame(String s) {
        if (s == null || s.length() > 40 || !RE_FRAME_CHAR.matcher(s).find()) return s;
        String t = RE_FRAME_CHAR.matcher(s).replaceAll(" ").replace('\u3000', ' ');
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static String extractIntro(String[] lines, int bodyStart, String title, String author) {
        if (bodyStart <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bodyStart && i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.isEmpty()) continue;
            if (RE_PURE_SYMBOL.matcher(s).matches()) continue;
            if (isAdLine(s)) continue;
            if (matchTitleLine(s) != null) continue;
            if (RE_JUNK_TITLE.matcher(s).find()) continue;
            if (notBlank(title) && s.contains(title)) continue;
            if (notBlank(author) && author.length() > 1 && s.contains(author)) continue;
            if (s.length() < 6) continue; // 太短多半是卷名/杂项
            if (sb.length() > 0) sb.append("\n");
            sb.append(s);
            if (sb.length() > 800) break;
        }
        String intro = sb.toString().trim();
        if (intro.length() > 500) intro = intro.substring(0, 500) + "...";
        return intro;
    }

    // ==================== 章节切分 ====================

    private static void splitChapters(String[] lines, List<Match> titleLines, int bodyStart, ParseResult r) {
        if (bodyStart < 0 || titleLines.isEmpty()) return;

        // 预判断分卷点：章节号明显回退，且后续两章依次 +1/+2 递增，才认定是新卷
        Map<Integer, Boolean> volumeBreak = new HashMap<>();
        for (int k = 0; k < titleLines.size(); k++) {
            Match cur = titleLines.get(k);
            if (cur.note || cur.number <= 0 || cur.type != TYPE_CHAPTER) continue;
            int prev = -1;
            for (int j = k - 1; j >= 0; j--) {
                Match pj = titleLines.get(j);
                if (!pj.note && pj.number > 0 && pj.type == TYPE_CHAPTER) {
                    prev = pj.number;
                    break;
                }
            }
            // 新卷几乎总是从第 1 章（或序章附近）重新开始；
            // 若"回退"后的编号仍很大，多半是误解析（如作者单章"第万章"），不认定分卷
            if (prev <= 0 || cur.number >= prev || cur.number > 5) continue;
            int n1 = -1, n2 = -1;
            for (int j = k + 1, c = 0; j < titleLines.size() && c < 2; j++) {
                Match nx = titleLines.get(j);
                if (!nx.note && nx.number > 0 && nx.type == TYPE_CHAPTER) {
                    if (c == 0) n1 = nx.number; else n2 = nx.number;
                    c++;
                }
            }
            boolean restart = (n1 == cur.number + 1) && (n2 == -1 || n2 == cur.number + 2);
            if (restart) {
                volumeBreak.put(cur.lineIndex, true);
                r.logs.add("分卷(编号重开): 第" + cur.number + "章 @行" + (cur.lineIndex + 1)
                        + " 前一有效编号=" + prev);
            }
        }

        Map<Integer, Match> byLine = new HashMap<>();
        for (Match a : titleLines) byLine.put(a.lineIndex, a);

        int major = 1;
        int minor = 0;
        String curPrefix = null;
        RawChapter current = null;
        String lastTitleKey = null;

        for (int i = bodyStart; i < lines.length; i++) {
            String s = lines[i].trim();

            // 先判断是否为章节/卷标题：盗版 txt 常在真标题后追加"免费阅读"等广告后缀，
            // 若先按广告行丢弃会整章丢失
            Match hit = byLine.get(i);
            if (hit == null && isAdLine(s)) {
                r.adLinesRemoved++;
                continue;
            }
            if (hit != null) {
                String cleaned = cleanChapterTitle(s);

                if (hit.type == TYPE_VOLUME) {
                    if (minor > 0 || current != null) {
                        major++;
                        minor = 0;
                    }
                    while (r.volumeTitles.size() < major) r.volumeTitles.add("第" + major + "卷");
                    if (!cleaned.isEmpty()) r.volumeTitles.set(major - 1, cleaned);
                    current = null;
                    lastTitleKey = null;
                    continue;
                }

                // 分卷前缀变化（第一部分 → 第二部分）
                if (hit.prefix != null && !hit.prefix.isEmpty()) {
                    if (curPrefix == null) {
                        curPrefix = hit.prefix;
                        while (r.volumeTitles.size() < major) r.volumeTitles.add("第" + major + "卷");
                        r.volumeTitles.set(major - 1, curPrefix);
                    } else if (!curPrefix.equals(hit.prefix)) {
                        curPrefix = hit.prefix;
                        major++;
                        minor = 0;
                        while (r.volumeTitles.size() < major) r.volumeTitles.add("第" + major + "卷");
                        r.volumeTitles.set(major - 1, curPrefix);
                        r.logs.add("分卷(前缀变化): " + curPrefix + " @行" + (i + 1));
                    }
                }

                boolean note = hit.note;
                int num = note ? -1 : hit.number;

                if (!note && Boolean.TRUE.equals(volumeBreak.get(i))) {
                    major++;
                    minor = 0;
                    while (r.volumeTitles.size() < major) r.volumeTitles.add("第" + major + "卷");
                    if (curPrefix != null && curPrefix.equals(hit.prefix)) {
                        // 前缀未变，沿用前缀名
                        r.volumeTitles.set(major - 1, curPrefix);
                    }
                }

                // 被广告切断的同一章重复出现 → 合并回上一章
                String key = normalizeKey(cleaned);
                if (current != null && lastTitleKey != null && lastTitleKey.equals(key)) {
                    r.mergedChapters++;
                    continue;
                }

                minor++;
                RawChapter c = new RawChapter();
                c.title = cleaned;
                c.number = num;
                c.note = note;
                c.volumeLevel = major;
                c.sortKey = major + "-" + String.format("%04d", minor);
                r.chapters.add(c);
                current = c;
                lastTitleKey = key;
                continue;
            }

            if (current == null) continue; // 正文起点之前的残留内容丢弃
            if (s.isEmpty() && current.content.length() == 0) continue;
            current.content.append(s).append("\n");
        }

        // 兜底：没有任何卷标题时保持 1 卷
        while (r.volumeTitles.size() < 1) r.volumeTitles.add("第1卷");
    }

    private static String normalizeKey(String t) {
        if (t == null) return "";
        return t.replaceAll("\\s+", "").replaceAll("[，,。.：:；;！!？?、]", "").toLowerCase();
    }

    // ==================== 清洗 ====================

    /** 清理章节标题：去盗版站广告后缀、多余标点 */
    public static String cleanChapterTitle(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        t = t.replaceAll("(?i)\\s*(?:免费阅读|全文阅读|请记住|请收藏|求推荐|求月票|手机阅读|最新章节|最快更新).*$", "");
        t = t.replaceAll("[\\u002a\\u2731\\u2605]{2,}\\s*$", "");
        t = t.replaceAll("[\\s，,。.：:；;、!！？?'\"“”‘’]+$", "");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80);
        return t;
    }

    /** 清理书名：去括号后缀、去书名号、限长 */
    public static String cleanBookTitle(String t) {
        if (t == null) return "";
        String s = t.trim().replaceAll("[《》〈〉]", "").trim();
        s = s.replaceAll("[\\s]+", " ").trim();
        s = s.replaceAll("[\\-—_\\.\\s]+$", "").trim();
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    /**
     * 调用方传入的作者提示（前端按文件名猜的、或手填的）是否可用。
     *
     * <p>前端「按文件名猜作者」的口径比后端宽松，形如 {@code 我的属性修行人生(1).txt} 的
     * 下载序号会被猜成作者「1」；而 hintAuthor 优先级最高，直接采信会把正文标注 /
     * EPUB 元数据里解析出的正确作者覆盖掉。故这里加一道守卫：不像人名的提示值视为未填，
     * 让解析器回落到自动解析。纯手填的合法作者（如「天蚕土豆」）不受影响。
     */
    public static boolean isPlausibleAuthor(String a) {
        if (a == null) return false;
        String t = a.trim();
        if (t.isEmpty()) return false;
        if (isJunkToken(t)) return false;   // 已含长度上限、数字占比过半、垃圾词、纯符号判定
        return looksLikeName(t);            // 中英文开头且不含标点噪音
    }

    /** 清理作者名 */
    public static String cleanAuthor(String a) {
        if (a == null) return "";
        String s = a.trim();
        s = s.replaceAll("^[:：\\s]+", "").trim();
        s = s.replaceAll("[\\(（\\[【]?\\s*(?:著|作品|创作)\\s*[\\)）\\]】]?\\s*$", "").trim();
        s = s.replaceAll("[\\s]+", " ").trim();
        if (s.length() > 30) s = s.substring(0, 30);
        return s.isEmpty() ? "佚名" : s;
    }

    // ==================== 中文数字 ====================

    /** 支持"一千零一十二""五百八十三""23"等 */
    public static int chineseToInt(String s) {
        if (s == null || s.isEmpty()) return -1;
        String t = s.trim();
        if (t.matches("\\d+")) {
            try {
                return Integer.parseInt(t);
            } catch (Exception e) {
                return -1;
            }
        }
        int result = 0;
        int temp = 0;
        int lastUnit = 0;
        boolean any = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int d = -1;
            switch (c) {
                case '零': case '〇': d = 0; break;
                case '一': d = 1; break;
                case '二': case '两': d = 2; break;
                case '三': d = 3; break;
                case '四': d = 4; break;
                case '五': d = 5; break;
                case '六': d = 6; break;
                case '七': d = 7; break;
                case '八': d = 8; break;
                case '九': d = 9; break;
                default: break;
            }
            if (d >= 0) {
                temp = d;
                any = true;
                continue;
            }
            int unit;
            switch (c) {
                case '十': unit = 10; break;
                case '百': unit = 100; break;
                case '千': unit = 1000; break;
                case '万': unit = 10000; break;
                case '亿': unit = 100000000; break;
                default: return -1; // 非数字字符
            }
            any = true;
            if (unit > lastUnit) {
                if (temp == 0) temp = 1;
                result = (result + temp) * unit;
                temp = 0;
                lastUnit = unit;
            } else {
                if (temp == 0) temp = 1;
                result += temp * unit;
                temp = 0;
            }
        }
        if (!any) return -1;
        return result + temp;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
