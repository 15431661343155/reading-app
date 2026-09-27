package com.example.readingapp.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * EPUB 解析器（纯 Java，无 Spring 依赖，可脱离容器单独 javac 测试）。
 *
 * <p>设计目标（参照多看阅读对 EPUB 的处理方式）：
 * <ol>
 *   <li><b>目录优先</b>：优先用 NCX / EPUB3 nav 的层级结构还原"卷 → 章"，
 *       而不是靠正则猜文件名或正文里的"第X章"。</li>
 *   <li><b>正文保样式</b>：保留 XHTML 结构（p / h1-h6 / em / strong / img ...），
 *       把外链 CSS 内联进来，图片转成 data URI，输出自包含的 HTML 片段；
 *       同时另外产出一份纯文本，用于字数统计、搜索和不支持 HTML 时的降级渲染。</li>
 *   <li><b>安全</b>：去掉 script / iframe / 事件属性 / javascript: 协议，
 *       并剥离会与阅读器主题冲突的 color、background 与绝对字号。</li>
 * </ol>
 */
public final class EpubBookParser {

    private EpubBookParser() {}

    /** 包裹正文的类名，同时用于把 CSS 里的 body/html 选择器改写过来 */
    public static final String WRAPPER_CLASS = "epub-chapter";

    /** 单张图片内联上限 */
    private static final long MAX_IMAGE_ONE = 1024L * 1024L;
    /** 单章内联图片总字节上限 */
    private static final long MAX_IMAGE_TOTAL = 4L * 1024L * 1024L;
    /** 小于此尺寸的图片直接内联为 data URI；更大则登记为外部资源由调用方落地成文件 */
    private static final int INLINE_IMAGE_BYTES = 16 * 1024;
    /** 单章 CSS 上限 */
    private static final int MAX_CSS = 200_000;
    /** 正文字数低于此值且命中非正文特征时丢弃 */
    private static final int MIN_BODY_CHARS = 20;

    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";

    private static final String NUM_CLS = "0-9〇零一二三四五六七八九十百千万亿两廿";
    /** 卷/部/篇 标题，如"第一卷 我讨厌杀手"、"第二部"、"Part 3" */
    private static final Pattern RE_VOLUME_LABEL = Pattern.compile(
            "^\\s*(?:第\\s*[" + NUM_CLS + "]+\\s*[卷部篇]|卷\\s*[" + NUM_CLS + "]+"
                    + "|(?i:part|volume|book)\\s*[\\d" + NUM_CLS + "]+)\\b.*$");

    /** 明显不属于正文的页（封面 / 目录 / 版权） */
    private static final Pattern[] RE_SPECIAL_TITLE = {
            Pattern.compile("^\\s*(封面|封面页|封面设计|扉页|书名页|彩页|插图页|目录|总目录|目次|table of contents|contents|cover|title\\s*page)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(版权|版权页|版权信息|制作说明|制作信息|出版说明|关于本书|声明|免责声明|ebook\\s*制作)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(封底|封套|扉页|书名页|back\\s*cover|front\\s*cover)\\s*$", Pattern.CASE_INSENSITIVE),
    };
    /** 内容简介类页面：提取为书籍简介，不生成章节（子串匹配，兼容"书籍简介""实体简介"等） */
    private static final Pattern RE_INTRO_TITLE = Pattern.compile(
            "简介|内容提要|故事简介|abstract", Pattern.CASE_INSENSITIVE);

    // =====================================================================
    // 数据结构
    // =====================================================================

    /** 解析出的一个章节 */
    public static final class RawChapter {
        /** 章节标题 */
        public String title = "";
        /** 保留样式的正文 HTML 片段（自包含，外链 CSS/图片已内联） */
        public String html = "";
        /** 纯文本正文（段落以 \n 分隔） */
        public String text = "";
        /** 所属分卷标题，无分卷时为空 */
        public String volumeTitle = "";
        /** 所属分卷序号，从 1 开始 */
        public int volumeIndex = 1;
        /** 排序键 "卷号-序号"，与 TXT 解析保持一致 */
        public String sortKey = "1-0001";
        /** 在 EPUB 内的文件路径（调试用） */
        public String href = "";
    }

    /** 解析结果 */
    public static final class ParseResult {
        public String title = "";
        public String author = "";
        public String intro = "";
        public String language = "";
        public String publisher = "";
        /** 封面原始字节（可能为 null） */
        public byte[] coverData;
        public String coverMime;
        /** 封面在 EPUB 内的路径 */
        public String coverHref;
        public List<RawChapter> chapters = new ArrayList<>();
        /** 分卷标题列表（按出现顺序） */
        public List<String> volumeTitles = new ArrayList<>();
        /** 被跳过的非正文页（封面/目录/版权）数量 */
        public int skippedPages;
        /** 内联成 data URI 的图片数量 */
        public int inlinedImages;
        /** 外部图片资源（体积较大的图，由调用方落地成文件后通过 replaceImageTokens 改写 src） */
        public List<ImageAsset> images = new ArrayList<>();
        /** EPUB 版本，如 "2.0" / "3.0" */
        public String version = "";
        public List<String> warnings = new ArrayList<>();
        /** 内部用：zip 内路径 → images 下标，避免重复登记 */
        final Map<String, Integer> imageIndex = new HashMap<>();
    }

    /** 提取出的图片资源（体积较大、未内联，需由调用方写入磁盘/对象存储） */
    public static final class ImageAsset {
        /** 在 ParseResult.images 中的下标，对应 HTML 里 epubimg://id 的占位符 */
        public int id;
        /** EPUB 内的归一化路径 */
        public String zipPath;
        /** 图片原始字节 */
        public byte[] data;
        /** MIME，如 image/png */
        public String mime;
        /** 落地到磁盘时的安全文件名（含 id 前缀，保证唯一） */
        public String fileName;
    }

    // =====================================================================
    // 入口
    // =====================================================================

    /**
     * 解析 EPUB。
     *
     * @param data        EPUB 文件字节
     * @param fileName    原始文件名（用于兜底书名 / 作者）
     * @param hintTitle   手动填写的书名，非空则优先
     * @param hintAuthor  手动填写的作者，非空则优先
     */
    public static ParseResult parse(byte[] data, String fileName, String hintTitle, String hintAuthor) {
        ParseResult r = new ParseResult();
        if (data == null || data.length == 0) {
            r.warnings.add("文件内容为空");
            return r;
        }
        Zip zip = Zip.read(data, r);
        String opfPath = locateOpf(zip, r);
        if (opfPath == null) {
            r.warnings.add("找不到 OPF 根文件");
            applyFileNameFallback(r, fileName, hintTitle, hintAuthor);
            if (r.title.isEmpty()) r.title = "未命名书籍";
            if (r.author.isEmpty()) r.author = "佚名";
            return r;
        }

        String opfDir = dirOf(opfPath);
        String opfXml = zip.text(opfPath);
        Document opfDoc = parseXml(opfXml);

        Manifest manifest = new Manifest();
        List<String> spine = new ArrayList<>();
        String ncxHref = null;
        if (opfDoc != null) {
            r.version = attr(opfDoc.getDocumentElement(), "version");
            readMetadata(opfDoc, r);
            readManifest(opfDoc, opfDir, manifest);
            spine = readSpine(opfDoc, manifest, r);
            ncxHref = manifest.byId.get(attr(firstElement(opfDoc, "spine"), "toc"));
            if (ncxHref == null) ncxHref = manifest.firstByMedia("application/x-dtbncx+xml");
        } else {
            r.warnings.add("OPF 解析失败，回退到正则提取");
            regexFallback(opfXml, opfDir, manifest, spine, r);
        }
        if (ncxHref == null) ncxHref = manifest.firstByPath(".ncx");

        r.coverHref = findCover(opfDoc, manifest);
        if (r.coverHref != null) {
            r.coverData = zip.bytes(r.coverHref);
            r.coverMime = mimeOf(r.coverHref);
        }

        // ---- 目录 ----
        List<TocNode> toc = new ArrayList<>();
        String navHref = manifest.firstByProperty("nav");
        boolean useNav = navHref != null && (r.version.startsWith("3") || ncxHref == null);
        if (useNav) {
            toc = parseNavXhtml(zip.text(navHref), dirOf(navHref), r);
            if (toc.isEmpty() && ncxHref != null) toc = parseNcx(zip.text(ncxHref), dirOf(ncxHref), r);
        } else if (ncxHref != null) {
            toc = parseNcx(zip.text(ncxHref), dirOf(ncxHref), r);
            if (toc.isEmpty() && navHref != null) toc = parseNavXhtml(zip.text(navHref), dirOf(navHref), r);
        }
        if (toc.isEmpty()) r.warnings.add("未解析到目录，改用 spine 顺序 + 正文标题");
        Map<String, TocNode> tocByPath = new HashMap<>();
        indexToc(toc, tocByPath);

        // ---- 组装章节 ----
        buildChapters(zip, spine, opfDir, toc, tocByPath, r);

        // ---- 元信息兜底 ----
        // 元数据里的占位作者（Unknown/未知）要当成"没有"，让它有机会被文件名捞回来
        if (isJunkMeta(r.author)) r.author = "";
        applyFileNameFallback(r, fileName, hintTitle, hintAuthor);
        if (r.author.isEmpty()) r.author = "佚名";
        if (r.intro.length() > 2000) r.intro = r.intro.substring(0, 2000) + "...";
        return r;
    }

    // =====================================================================
    // ZIP
    // =====================================================================

    private static final class Zip {
        final Map<String, byte[]> raw = new LinkedHashMap<>();
        final Map<String, String> textCache = new HashMap<>();

        static Zip read(byte[] data, ParseResult r) {
            // 先按流读取（内存友好）。部分 EPUB 的目录项用了「数据描述符 + STORED」，
            // ZipInputStream 会抛 "only DEFLATED entries can have EXT descriptor"，
            // 这时必须退回到基于中央目录的 ZipFile。
            Zip z = new Zip();
            try {
                readStream(data, z);
            } catch (Exception ex) {
                z.raw.clear();
                r.warnings.add("ZIP 流式读取失败，改用 ZipFile: " + ex.getMessage());
            }
            if (z.raw.isEmpty()) {
                try {
                    readZipFile(data, z);
                } catch (Exception ex) {
                    r.warnings.add("ZIP 读取失败: " + ex.getMessage());
                }
            }
            return z;
        }

        private static void readStream(byte[] data, Zip z) throws IOException {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
                ZipEntry e;
                byte[] buf = new byte[8192];
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (isFont(name)) continue;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    z.put(normalize(name.replace('\\', '/')), bos.toByteArray());
                }
            }
        }

        private static void readZipFile(byte[] data, Zip z) throws IOException {
            File tmp = File.createTempFile("epub_parse_", ".zip");
            try {
                Files.write(tmp.toPath(), data);
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmp)) {
                    Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry e = en.nextElement();
                        if (e.isDirectory()) continue;
                        String name = e.getName();
                        if (isFont(name)) continue;
                        try (java.io.InputStream in = zf.getInputStream(e)) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                            z.put(normalize(name.replace('\\', '/')), bos.toByteArray());
                        }
                    }
                }
            } finally {
                tmp.delete();
            }
        }

        void put(String path, byte[] b) {
            String k = path.toLowerCase(Locale.ROOT);
            raw.putIfAbsent(k, b);
            String decoded = k.contains("%") ? decodeUrl(k) : k;
            if (!decoded.equals(k)) raw.putIfAbsent(decoded, b);
        }

        byte[] bytes(String path) {
            if (path == null) return null;
            byte[] b = raw.get(path.toLowerCase(Locale.ROOT));
            if (b == null) b = raw.get(normalize(path).toLowerCase(Locale.ROOT));
            if (b == null) b = raw.get(decodeUrl(path.toLowerCase(Locale.ROOT)));
            return b;
        }

        String text(String path) {
            String k = path.toLowerCase(Locale.ROOT);
            String cached = textCache.get(k);
            if (cached != null) return cached;
            byte[] b = bytes(path);
            if (b == null) return "";
            String s = decode(b);
            textCache.put(k, s);
            return s;
        }

        boolean has(String path) { return bytes(path) != null; }
    }

    private static boolean isFont(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".woff")
                || n.endsWith(".woff2") || n.endsWith(".eot") || n.endsWith(".ttc");
    }

    // =====================================================================
    // OPF
    // =====================================================================

    private static String locateOpf(Zip zip, ParseResult r) {
        String container = zip.text("META-INF/container.xml");
        String opf = null;
        Matcher m = Pattern.compile("full-path\\s*=\\s*([\"'])([^\"']+)\\1").matcher(container);
        if (m.find()) opf = m.group(2);
        if (opf == null || !zip.has(opf)) {
            opf = null;
            for (String k : zip.raw.keySet()) {
                if (k.endsWith(".opf")) { opf = k; break; }
            }
        }
        if (opf != null && !zip.has(opf)) opf = null;
        return opf;
    }

    private static final class Manifest {
        final Map<String, String> byId = new LinkedHashMap<>();     // id -> 解析后的绝对路径
        final Map<String, String> media = new HashMap<>();          // 路径 -> media-type
        final Map<String, String> props = new HashMap<>();          // 路径 -> properties
        final List<String> paths = new ArrayList<>();

        void add(String id, String path, String mediaType, String properties) {
            byId.put(id, path);
            media.put(path, mediaType);
            props.put(path, properties == null ? "" : properties);
            paths.add(path);
        }

        String firstByMedia(String mt) {
            for (String p : paths) if (mt.equalsIgnoreCase(media.get(p))) return p;
            return null;
        }

        String firstByProperty(String prop) {
            for (String p : paths) {
                String v = props.get(p);
                if (v != null && v.toLowerCase(Locale.ROOT).contains(prop.toLowerCase(Locale.ROOT))) return p;
            }
            for (String p : paths) {
                if (p.toLowerCase(Locale.ROOT).contains("nav") && p.endsWith(".xhtml")) return p;
            }
            return null;
        }

        String firstByPath(String suffix) {
            for (String p : paths) if (p.toLowerCase(Locale.ROOT).endsWith(suffix)) return p;
            return null;
        }
    }

    private static void readManifest(Document doc, String opfDir, Manifest mf) {
        NodeList items = doc.getElementsByTagName("*");
        for (int i = 0; i < items.getLength(); i++) {
            Node n = items.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (!local(el).equals("item")) continue;
            String id = attr(el, "id");
            String href = attr(el, "href");
            if (id == null || href == null) continue;
            mf.add(id, resolve(opfDir, href), attr(el, "media-type"), attr(el, "properties"));
        }
    }

    private static List<String> readSpine(Document doc, Manifest mf, ParseResult r) {
        List<String> spine = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("*");
        for (int i = 0; i < items.getLength(); i++) {
            Node n = items.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (!local(el).equals("itemref")) continue;
            String idref = attr(el, "idref");
            String path = idref == null ? null : mf.byId.get(idref);
            if (path == null) continue;
            String linear = attr(el, "linear");
            // linear="no" 是非线性内容（如页码映射表），但部分生成器误标，仅在明确为 no 时跳过封面
            if ("no".equalsIgnoreCase(linear) && isCoverLike(path)) {
                r.skippedPages++;
                continue;
            }
            spine.add(path);
        }
        return spine;
    }

    private static void readMetadata(Document doc, ParseResult r) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            String ln = local(el);
            String text = cleanInline(el.getTextContent());
            if (text.isEmpty()) continue;
            switch (ln) {
                case "title":
                    if (r.title.isEmpty() && isDc(el) && !isJunkMeta(text)) r.title = text;
                    break;
                case "creator":
                    if (isDc(el)) {
                        String role = attr(el, "role");
                        if (role == null) role = attrNs(el, "opf", "role");
                        if (!isJunkMeta(text)) {
                            if (r.author.isEmpty()) r.author = text;
                            else if ("aut".equalsIgnoreCase(role)) r.author = text;
                        }
                    }
                    break;
                case "description":
                    if (r.intro.isEmpty() && isDc(el) && !isJunkMeta(text)) r.intro = text;
                    break;
                case "language":
                    if (r.language.isEmpty() && isDc(el)) r.language = text;
                    break;
                case "publisher":
                    if (r.publisher.isEmpty() && isDc(el) && !isJunkMeta(text)) r.publisher = text;
                    break;
                default:
                    break;
            }
        }
    }

    private static boolean isDc(Element el) {
        String ns = el.getNamespaceURI();
        return ns == null || DC_NS.equals(ns);
    }

    /** OPF 里常见的占位元数据（作者填"作者"、简介填"暂无"等），应视为缺失，回退到文件名推断 */
    private static boolean isJunkMeta(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        if (t.length() > 12) return false; // 较长文本不太可能是占位符
        // 注意要带上 unknown/undefined：实测合集里有 epub 的 dc:creator 直接写 "Unknown"，
        // 漏掉它就会让「Unknown」当作者入库（占位值必须与 looksLikeAuthor 同步口径）
        return t.matches("(?i)作者|佚名|未知|不详|无名氏|暂无|无|none|n/?a|null|unknown|undefined|空"
                + "|\\[\\s*\\]|\\(?\\)?|[-_—=~．.]+");
    }

    /** OPF 整体解析失败时的正则兜底 */
    private static void regexFallback(String opfXml, String opfDir, Manifest mf, List<String> spine, ParseResult r) {
        Matcher m = Pattern.compile("<dc:title[^>]*>([^<]*)</dc:title>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
        if (m.find()) {
            String v = cleanInline(m.group(1));
            if (!isJunkMeta(v)) r.title = v;
        }
        m = Pattern.compile("<dc:creator[^>]*>([^<]*)</dc:creator>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
        if (m.find()) {
            // 这里同样要过 isJunkMeta：否则正则兜底路径会把 Unknown/未知 直接写进作者，
            // 而主路径（parseMetadataXml）是过滤的，两条路径口径必须一致
            String v = cleanInline(m.group(1));
            if (!isJunkMeta(v)) r.author = v;
        }
        m = Pattern.compile("<dc:description[^>]*>([\\s\\S]*?)</dc:description>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
        if (m.find()) {
            String v = cleanInline(stripTags(m.group(1)));
            if (!isJunkMeta(v)) r.intro = v;
        }
        m = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
        while (m.find()) {
            String tag = m.group();
            String id = pickAttr(tag, "id");
            String href = pickAttr(tag, "href");
            if (id == null || href == null) continue;
            mf.add(id, resolve(opfDir, href), pickAttr(tag, "media-type"), pickAttr(tag, "properties"));
        }
        m = Pattern.compile("<itemref\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
        while (m.find()) {
            String idref = pickAttr(m.group(), "idref");
            if (idref == null) continue;
            String p = mf.byId.get(idref);
            if (p != null) spine.add(p);
        }
    }

    private static String pickAttr(String tag, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*([\"'])([^\"']*)\\1").matcher(tag);
        return m.find() ? m.group(2) : null;
    }

    private static String findCover(Document doc, Manifest mf) {
        if (doc != null) {
            NodeList all = doc.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Node n = all.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;
                if ("meta".equals(local(el)) && "cover".equalsIgnoreCase(attr(el, "name"))) {
                    String p = mf.byId.get(attr(el, "content"));
                    if (p != null) return p;
                }
            }
        }
        String p = mf.firstByProperty("cover-image");
        if (p != null) return p;
        for (String path : mf.paths) {
            if (isCoverLike(path) && isImage(path)) return path;
        }
        return null;
    }

    private static boolean isCoverLike(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        if (n.contains("/")) n = n.substring(n.lastIndexOf('/') + 1);
        return n.startsWith("cover") || n.contains("封面") || n.startsWith("titlepage") || n.startsWith("title-page");
    }

    // =====================================================================
    // 目录（NCX / EPUB3 nav）
    // =====================================================================

    private static final class TocNode {
        String label = "";
        String path = "";     // 归一化后的文档路径
        int depth;
        /** 该节点是分卷标题页 */
        boolean volumePage;
        /** 该节点所属分卷标题（卷页自身为 ""） */
        String volumeTitle = "";
        final List<TocNode> children = new ArrayList<>();
    }

    private static List<TocNode> parseNcx(String xml, String baseDir, ParseResult r) {
        if (xml == null || xml.isEmpty()) return new ArrayList<>();
        Document doc = parseXml(xml);
        List<TocNode> roots = new ArrayList<>();
        if (doc != null) {
            Element navMap = null;
            NodeList all = doc.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Node n = all.item(i);
                if (n instanceof Element && "navMap".equals(local((Element) n))) { navMap = (Element) n; break; }
            }
            if (navMap != null) {
                for (Element e : childElements(navMap, "navPoint")) {
                    TocNode node = buildNcxNode(e, baseDir, 0);
                    if (node != null) roots.add(node);
                }
            }
        }
        if (roots.isEmpty()) roots = parseNcxByRegex(xml, baseDir);
        return roots;
    }

    private static TocNode buildNcxNode(Element navPoint, String baseDir, int depth) {
        TocNode node = new TocNode();
        node.depth = depth;
        for (Element e : childElements(navPoint, "navLabel")) {
            Element t = firstChild(e, "text");
            if (t != null) {
                node.label = cleanInline(t.getTextContent());
                if (!node.label.isEmpty()) break;
            }
        }
        for (Element e : childElements(navPoint, "content")) {
            String src = attr(e, "src");
            if (src != null && !src.isEmpty()) { node.path = resolve(baseDir, src); break; }
        }
        for (Element e : childElements(navPoint, "navPoint")) {
            TocNode c = buildNcxNode(e, baseDir, depth + 1);
            if (c != null) node.children.add(c);
        }
        return node;
    }

    /** NCX 不是合法 XML 时的兜底：用标签配对重建层级 */
    private static List<TocNode> parseNcxByRegex(String xml, String baseDir) {
        List<TocNode> roots = new ArrayList<>();
        java.util.ArrayDeque<TocNode> stack = new java.util.ArrayDeque<>();
        Pattern p = Pattern.compile("<navPoint\\b[^>]*>|</navPoint>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(xml);
        int depth = 0;
        while (m.find()) {
            String tok = m.group();
            if (tok.startsWith("</")) {
                depth--;
                if (!stack.isEmpty()) stack.pop();
                continue;
            }
            String seg = xml.substring(m.end(), Math.min(xml.length(), m.end() + 400));
            TocNode node = new TocNode();
            node.depth = depth;
            Matcher lm = Pattern.compile("<navLabel>\\s*<text[^>]*>([\\s\\S]*?)</text>", Pattern.CASE_INSENSITIVE).matcher(seg);
            if (lm.find()) node.label = cleanInline(lm.group(1));
            Matcher cm = Pattern.compile("<content[^>]+src\\s*=\\s*([\"'])([^\"']+)\\1", Pattern.CASE_INSENSITIVE).matcher(seg);
            if (cm.find()) node.path = resolve(baseDir, cm.group(2));
            if (!stack.isEmpty()) stack.peek().children.add(node);
            else roots.add(node);
            stack.push(node);
            depth++;
        }
        // 标签不配对（深度没归零）时层级不可信，全部拉平
        if (depth != 0) {
            List<TocNode> flat = new ArrayList<>();
            flatten(roots, flat);
            for (TocNode n : flat) n.children.clear();
            return flat;
        }
        return roots;
    }

    private static List<TocNode> parseNavXhtml(String html, String baseDir, ParseResult r) {
        List<TocNode> roots = new ArrayList<>();
        if (html == null || html.isEmpty()) return roots;
        Document doc = parseXml(html);
        if (doc != null) {
            Element nav = null;
            NodeList all = doc.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Node n = all.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;
                if (!"nav".equals(local(el))) continue;
                String type = attrNs(el, "epub", "type");
                if (type != null && type.toLowerCase(Locale.ROOT).contains("toc")) { nav = el; break; }
                if (nav == null) nav = el;
            }
            if (nav != null) {
                Element ol = firstChild(nav, "ol");
                if (ol != null) buildNavChildren(ol, roots, baseDir, 0);
            }
        }
        if (roots.isEmpty()) roots = parseNavByRegex(html, baseDir);
        return roots;
    }

    private static void buildNavChildren(Element ol, List<TocNode> out, String baseDir, int depth) {
        for (Element li : childElements(ol, "li")) {
            TocNode node = new TocNode();
            node.depth = depth;
            Element a = firstChild(li, "a");
            if (a == null) a = findDescendant(li, "a");
            if (a != null) {
                node.label = cleanInline(a.getTextContent());
                String href = attr(a, "href");
                if (href != null) node.path = resolve(baseDir, href);
            }
            if (node.label.isEmpty()) {
                Element span = findDescendant(li, "span");
                if (span != null) node.label = cleanInline(span.getTextContent());
            }
            Element sub = firstChild(li, "ol");
            if (sub != null) buildNavChildren(sub, node.children, baseDir, depth + 1);
            if (!node.label.isEmpty() || !node.children.isEmpty()) out.add(node);
        }
    }

    private static List<TocNode> parseNavByRegex(String html, String baseDir) {
        List<TocNode> out = new ArrayList<>();
        Matcher m = Pattern.compile("<a\\b[^>]*href\\s*=\\s*([\"'])([^\"']+)\\1[^>]*>([\\s\\S]*?)</a>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            TocNode n = new TocNode();
            n.path = resolve(baseDir, m.group(2));
            n.label = cleanInline(stripTags(m.group(3)));
            if (!n.label.isEmpty()) out.add(n);
        }
        return out;
    }

    private static void flatten(List<TocNode> nodes, List<TocNode> out) {
        for (TocNode n : nodes) { out.add(n); flatten(n.children, out); }
    }

    private static void indexToc(List<TocNode> nodes, Map<String, TocNode> byPath) {
        for (TocNode n : nodes) {
            if (!n.path.isEmpty()) {
                String k = n.path.toLowerCase(Locale.ROOT);
                TocNode prev = byPath.get(k);
                // 卷节点常与首章共享 src，优先保留真正的章节节点，避免整章被当成卷页跳过
                if (prev == null || (prev.volumePage && !n.volumePage)) byPath.put(k, n);
            }
            indexToc(n.children, byPath);
        }
    }

    // =====================================================================
    // 章节组装
    // =====================================================================

    private static void buildChapters(Zip zip, List<String> spine, String opfDir,
                                      List<TocNode> toc, Map<String, TocNode> tocByPath, ParseResult r) {
        // 按目录层级标出卷页，并记录每个文档路径所属的卷
        Map<String, String> volumeOfPath = new HashMap<>();
        for (TocNode root : toc) assignVolumes(root, "", 0, volumeOfPath, r);

        // 正文文档顺序：以 spine 为准，补上只在目录里出现的文档
        List<String> docs = new ArrayList<>();
        for (String p : spine) {
            if (isTextDoc(p) && !containsIgnoreCase(docs, p)) docs.add(p);
        }
        List<TocNode> flat = new ArrayList<>();
        flatten(toc, flat);
        for (TocNode n : flat) {
            if (n.path.isEmpty() || !isTextDoc(n.path)) continue;
            if (!containsIgnoreCase(docs, n.path)) docs.add(n.path);
        }

        String lastVolume = "";
        int curVolumeIndex = 1;
        int minor = 0;

        for (String path : docs) {
            String key = path.toLowerCase(Locale.ROOT);
            TocNode node = tocByPath.get(key);
            String rawHtml = zip.text(path);
            if (rawHtml.isEmpty()) continue;

            // 卷页：只登记分卷，不生成章节
            if (node != null && node.volumePage) {
                lastVolume = node.volumeTitle;
                curVolumeIndex = volumeIndexOf(r, lastVolume, curVolumeIndex);
                minor = 0;
                continue;
            }

            String bodyHtml = extractBody(rawHtml);
            String plain = htmlToText(bodyHtml);
            String title = node != null ? node.label : "";
            if (title.isEmpty()) title = extractHeading(bodyHtml, rawHtml);
            if (title.isEmpty()) title = titleFromPath(path);

            // 非正文页
            if (isSpecialTitle(title) || RE_INTRO_TITLE.matcher(title).find()) {
                if (RE_INTRO_TITLE.matcher(title).find() && r.intro.isEmpty() && plain.length() > MIN_BODY_CHARS) {
                    r.intro = plain.replace('\n', ' ');
                }
                r.skippedPages++;
                continue;
            }
            if (plain.length() < MIN_BODY_CHARS && isNavLike(bodyHtml)) {
                r.skippedPages++;
                continue;
            }
            if (r.coverHref != null && key.equals(r.coverHref.toLowerCase(Locale.ROOT))) {
                r.skippedPages++;
                continue;
            }

            // 去掉与标题重复的正文开头大标题（阅读器会单独显示标题）
            bodyHtml = stripLeadingHeading(bodyHtml, title);

            String cssText = collectCss(zip, rawHtml, path, opfDir);
            String safeBody = sanitizeBody(zip, bodyHtml, path, r);
            String html = composeHtml(cssText, safeBody);
            String text = htmlToText(safeBody);

            String vol = volumeOfPath.get(key);
            if (vol == null || vol.isEmpty()) vol = lastVolume;
            int volIdx = volumeIndexOf(r, vol, curVolumeIndex);
            if (volIdx != curVolumeIndex) {
                curVolumeIndex = volIdx;
                minor = 0;
            }

            RawChapter c = new RawChapter();
            c.title = title;
            c.html = html;
            c.text = text;
            c.href = path;
            c.volumeTitle = vol == null ? "" : vol;
            c.volumeIndex = curVolumeIndex;
            c.sortKey = curVolumeIndex + "-" + String.format("%04d", minor + 1);
            minor++;
            r.chapters.add(c);
        }
        if (r.chapters.isEmpty()) r.warnings.add("没有解析出任何章节");
    }

    /**
     * 递归给目录树标注分卷。
     * 判定为"卷"的条件：标题命中卷/部/篇 正则，或（顶层节点且拥有子目录）。
     */
    private static void assignVolumes(TocNode n, String currentVolume, int depth,
                                      Map<String, String> volumeOfPath, ParseResult r) {
        boolean labelIsVolume = !n.label.isEmpty() && RE_VOLUME_LABEL.matcher(n.label).matches();
        boolean isVolume = labelIsVolume || (!n.children.isEmpty() && depth == 0);
        if (isVolume) {
            n.volumePage = true;
            n.volumeTitle = n.label;
            r.volumeTitles.add(n.label);
            if (!n.path.isEmpty()) volumeOfPath.put(n.path.toLowerCase(Locale.ROOT), n.label);
            for (TocNode c : n.children) assignVolumes(c, n.label, depth + 1, volumeOfPath, r);
            return;
        }
        n.volumeTitle = currentVolume;
        if (!n.path.isEmpty()) volumeOfPath.put(n.path.toLowerCase(Locale.ROOT), currentVolume);
        for (TocNode c : n.children) assignVolumes(c, currentVolume, depth + 1, volumeOfPath, r);
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String v : list) if (v.equalsIgnoreCase(s)) return true;
        return false;
    }

    private static int volumeIndexOf(ParseResult r, String volumeTitle, int fallback) {
        if (volumeTitle == null || volumeTitle.isEmpty()) return fallback;
        for (int i = 0; i < r.volumeTitles.size(); i++) {
            if (r.volumeTitles.get(i).equals(volumeTitle)) return i + 1;
        }
        return fallback;
    }

    private static int minorInVolume(List<RawChapter> chapters, int volumeIndex) {
        int n = 0;
        for (RawChapter c : chapters) if (c.volumeIndex == volumeIndex) n++;
        return n;
    }

    private static boolean isSpecialTitle(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        for (Pattern p : RE_SPECIAL_TITLE) if (p.matcher(t).matches()) return true;
        return false;
    }

    private static boolean isNavLike(String bodyHtml) {
        int links = 0;
        Matcher m = Pattern.compile("(?i)<a\\b").matcher(bodyHtml);
        while (m.find()) links++;
        return links >= 3;
    }

    // =====================================================================
    // 正文：HTML 保留 + 清洗
    // =====================================================================

    private static String extractBody(String html) {
        if (html == null) return "";
        int bs = indexOfCi(html, "<body");
        if (bs >= 0) {
            int gt = html.indexOf('>', bs);
            bs = gt >= 0 ? gt + 1 : 0;
        } else bs = 0;
        int be = indexOfCi(html, "</body>");
        if (be < bs) be = html.length();
        return html.substring(bs, be);
    }

    /** 汇总本章引用的所有样式表（外链 + 内嵌 style） */
    private static String collectCss(Zip zip, String rawHtml, String docPath, String opfDir) {
        StringBuilder sb = new StringBuilder();
        List<String> hrefs = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<link\\b[^>]*>").matcher(rawHtml);
        while (m.find()) {
            String tag = m.group();
            if (!Pattern.compile("(?i)rel\\s*=\\s*([\"']?)\\s*stylesheet").matcher(tag).find()) continue;
            String href = pickAttr(tag, "href");
            if (href != null) hrefs.add(href);
        }
        for (String h : hrefs) {
            String css = zip.text(resolve(dirOf(docPath), h));
            if (css.isEmpty()) css = zip.text(resolve(opfDir, h));
            if (!css.isEmpty()) sb.append(sanitizeCss(css)).append('\n');
        }
        Matcher sm = Pattern.compile("(?is)<style\\b[^>]*>([\\s\\S]*?)</style>").matcher(rawHtml);
        while (sm.find()) sb.append(sanitizeCss(sm.group(1))).append('\n');
        String out = sb.toString();
        if (out.length() > MAX_CSS) out = out.substring(0, MAX_CSS);
        return out;
    }

    /**
     * CSS 清洗：去掉注释 / @font-face / @import，把 body、html 选择器改写到包裹容器，
     * 并剥离会与阅读器主题打架的颜色、背景与绝对字号（保留缩进、行距、对齐、字重等排版样式）。
     */
    static String sanitizeCss(String css) {
        if (css == null || css.isEmpty()) return "";
        String s = css.replaceAll("(?s)/\\*.*?\\*/", "");
        s = dropAtRule(s, "@font-face");
        s = dropAtRule(s, "@import");
        s = s.replaceAll("(?i)@charset[^;]*;", "");
        s = s.replaceAll("(?i)url\\(\\s*(res:///|file:///)[^)]*\\)", "none");
        s = s.replaceAll("(?i)(?<!-)\\bcolor\\s*:[^;}]*;?", "");
        s = s.replaceAll("(?i)\\bbackground(-color|-image|-attachment|-position|-repeat|-size)?\\s*:[^;}]*;?", "");
        s = s.replaceAll("(?i)\\btext-shadow\\s*:[^;}]*;?", "");
        // 绝对字号交给阅读器控制；相对单位（em/%/small/large）保留以维持层级
        s = s.replaceAll("(?i)\\bfont-size\\s*:\\s*[\\d.]+(px|pt|pc|in|cm|mm)\\s*;?", "");
        s = s.replaceAll("(?i)\\bposition\\s*:\\s*(fixed|absolute)\\s*;?", "position:static;");
        s = rewriteRootSelector(s);
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String dropAtRule(String css, String at) {
        StringBuilder out = new StringBuilder();
        Pattern p = Pattern.compile("(?i)" + Pattern.quote(at) + "\\s*\\{");
        int i = 0;
        while (i < css.length()) {
            Matcher m = p.matcher(css);
            if (!m.find(i)) { out.append(css, i, css.length()); break; }
            out.append(css, i, m.start());
            int j = m.end() - 1;
            int depth = 0;
            int k = j;
            for (; k < css.length(); k++) {
                char ch = css.charAt(k);
                if (ch == '{') depth++;
                else if (ch == '}') { depth--; if (depth == 0) { k++; break; } }
            }
            i = k;
        }
        return out.toString();
    }

    /** body{...} / html{...} → .epub-chapter{...} */
    private static String rewriteRootSelector(String css) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < css.length()) {
            int brace = css.indexOf('{', i);
            if (brace < 0) { out.append(css, i, css.length()); break; }
            int close = css.indexOf('}', brace);
            if (close < 0) close = css.length() - 1;
            String selector = css.substring(i, brace);
            String body = css.substring(brace, close + 1);
            selector = selector.replaceAll("(?i)(^|\\s)(body|html)(?=\\s|$|[,:])", "$1." + WRAPPER_CLASS);
            out.append(selector).append(body);
            i = close + 1;
        }
        return out.toString();
    }

    /** 正文片段清洗：去危险标签与属性，图片内联成 data URI */
    private static String sanitizeBody(Zip zip, String body, String docPath, ParseResult r) {
        String s = body;
        s = s.replaceAll("(?is)<script\\b[^>]*>[\\s\\S]*?</script>", "");
        s = s.replaceAll("(?is)<noscript\\b[^>]*>[\\s\\S]*?</noscript>", "");
        s = s.replaceAll("(?is)<iframe\\b[^>]*>[\\s\\S]*?</iframe>", "");
        s = s.replaceAll("(?is)<object\\b[^>]*>[\\s\\S]*?</object>", "");
        s = s.replaceAll("(?is)<form\\b[^>]*>[\\s\\S]*?</form>", "");
        s = s.replaceAll("(?is)<(link|meta|base)\\b[^>]*>", "");
        s = s.replaceAll("(?is)<embed\\b[^>]*>", "");
        s = s.replaceAll("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        s = s.replaceAll("(?i)\\ssrcset\\s*=\\s*(\"[^\"]*\"|'[^']*')", "");
        s = s.replaceAll("(?i)(href|src)\\s*=\\s*([\"']?)\\s*javascript:[^>\"']*", "$1=\"#\"");

        // 图片：小图内联为 data URI，大图登记为外部资源（epubimg://id 占位符，由调用方落地）
        Matcher m = Pattern.compile("(?i)(<img\\b[^>]*?\\bsrc\\s*=\\s*)([\"'])([^\"']*)\\2").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String src = m.group(3);
            String data = inlineImage(zip, docPath, src, r);
            if (data == null) {
                // 资源缺失（或过大且非图片）：整块 img 丢掉，避免显示裂图
                m.appendReplacement(sb, "");
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + data + m.group(2)));
        }
        m.appendTail(sb);
        s = sb.toString();

        // 没有 src 的 img（如占位图）一并去掉
        s = s.replaceAll("(?is)<img\\b(?![^>]*\\bsrc\\s*=)[^>]*>", "");
        // 内部链接：保留文字，去掉会跳到无效位置的 href
        s = s.replaceAll("(?i)\\shref\\s*=\\s*([\"'])([^\"']*)\\1", "");
        return s;
    }

    /**
     * 处理一个 img 的 src：
     * <ul>
     *   <li>已是 data URI / 已是占位符 → 原样返回；</li>
     *   <li>小图（≤ INLINE_IMAGE_BYTES）→ 内联为 data URI；</li>
     *   <li>大图 → 登记到 r.images，返回 {@code epubimg://id} 占位符，由调用方落地成文件后改写。</li>
     * </ul>
     */
    private static String inlineImage(Zip zip, String docPath, String src, ParseResult r) {
        if (src == null || src.isEmpty() || src.startsWith("data:") || src.startsWith("epubimg://")) return src;
        String path = resolve(dirOf(docPath), src);
        if (path == null) path = normalize(src);
        byte[] b = zip.bytes(path);
        if (b == null || b.length == 0) return null;
        if (!looksLikeImage(b) && b.length > 64 * 1024) return null;

        if (b.length <= INLINE_IMAGE_BYTES) {
            r.inlinedImages++;
            return "data:" + mimeOf(path) + ";base64," + Base64.getEncoder().encodeToString(b);
        }

        Integer id = r.imageIndex.get(path);
        if (id == null) {
            id = r.images.size();
            ImageAsset a = new ImageAsset();
            a.id = id;
            a.zipPath = path;
            a.data = b;
            a.mime = mimeOf(path);
            a.fileName = safeImageName(path, id);
            r.images.add(a);
            r.imageIndex.put(path, id);
        }
        return "epubimg://" + id;
    }

    /** 把落地后的真实 URL 回填进 HTML（替换 epubimg://id 占位符） */
    public static String replaceImageTokens(String html, Map<Integer, String> urlById) {
        if (html == null || html.indexOf("epubimg://") < 0) return html;
        Matcher m = Pattern.compile("epubimg://(\\d+)").matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            String url = urlById.get(id);
            m.appendReplacement(sb, Matcher.quoteReplacement(url == null ? "" : url));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String safeImageName(String zipPath, int id) {
        String base = zipPath;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("[^\\w.\\-]", "_");
        if (base.isEmpty()) base = "img";
        return id + "_" + base;
    }

    private static boolean looksLikeImage(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return true;
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        if (b.length > 6 && (b[0] & 0xFF) == 0x47 && b[1] == 'I' && b[2] == 'F') return true;
        if (b.length > 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        return false;
    }

    private static String composeHtml(String css, String body) {
        StringBuilder sb = new StringBuilder();
        if (css != null && !css.trim().isEmpty()) {
            sb.append("<style>\n").append(css.trim()).append("\n</style>\n");
        }
        sb.append("<div class=\"").append(WRAPPER_CLASS).append("\">\n").append(body.trim()).append("\n</div>");
        return sb.toString();
    }

    private static String stripLeadingHeading(String body, String title) {
        if (title == null || title.isEmpty()) return body;
        String key = title.replaceAll("\\s+", "");
        Matcher m = Pattern.compile("(?is)^\\s*(?:<p\\b[^>]*>\\s*)*<h([1-6])\\b[^>]*>([\\s\\S]*?)</h\\1>").matcher(body);
        if (m.find()) {
            String heading = htmlToText(m.group(2)).replaceAll("\\s+", "");
            if (!heading.isEmpty() && (key.contains(heading) || heading.contains(key))) {
                return body.substring(m.end());
            }
        }
        return body;
    }

    // =====================================================================
    // 文本工具
    // =====================================================================

    /** HTML → 纯文本（段落以 \n 分隔） */
    public static String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = html;
        s = s.replaceAll("(?is)<(script|style|noscript)\\b[^>]*>[\\s\\S]*?</\\1>", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article|dd|dt|pre|table)\\s*>", "\n");
        s = s.replaceAll("(?i)<(p|div|h[1-6]|li|tr|blockquote|section|article|dd|dt|pre|table)\\b[^>]*>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = decodeEntities(s);
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.replace('\u00a0', ' ').replace('\u3000', ' ').trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);|&([a-zA-Z]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep;
            if (m.group(2) != null) {
                try {
                    int code = "x".equals(m.group(1)) ? Integer.parseInt(m.group(2), 16) : Integer.parseInt(m.group(2));
                    rep = code > 0 && code < 0x110000 ? new String(Character.toChars(code)) : "";
                } catch (Exception e) {
                    rep = "";
                }
            } else {
                rep = NAMED_ENTITIES.get(m.group(3));
                rep = rep == null ? "" : rep;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final Map<String, String> NAMED_ENTITIES = new HashMap<>();

    static {
        NAMED_ENTITIES.put("amp", "&");
        NAMED_ENTITIES.put("lt", "<");
        NAMED_ENTITIES.put("gt", ">");
        NAMED_ENTITIES.put("quot", "\"");
        NAMED_ENTITIES.put("apos", "'");
        NAMED_ENTITIES.put("nbsp", " ");
        NAMED_ENTITIES.put("mdash", "\u2014");
        NAMED_ENTITIES.put("ndash", "\u2013");
        NAMED_ENTITIES.put("hellip", "\u2026");
        NAMED_ENTITIES.put("ldquo", "\u201c");
        NAMED_ENTITIES.put("rdquo", "\u201d");
        NAMED_ENTITIES.put("lsquo", "\u2018");
        NAMED_ENTITIES.put("rsquo", "\u2019");
        NAMED_ENTITIES.put("middot", "\u00b7");
        NAMED_ENTITIES.put("bull", "\u2022");
    }

    static String cleanInline(String s) {
        if (s == null) return "";
        String t = decodeEntities(s);
        t = t.replace('\u00a0', ' ');
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("(?s)<[^>]+>", " ");
    }

    private static String extractHeading(String bodyHtml, String rawHtml) {
        Matcher m = Pattern.compile("(?is)<h([1-6])\\b[^>]*>([\\s\\S]*?)</h\\1>").matcher(bodyHtml);
        if (m.find()) {
            String t = cleanInline(htmlToText(m.group(2)).replace('\n', ' '));
            if (!t.isEmpty()) return t;
        }
        m = Pattern.compile("(?is)<title[^>]*>([\\s\\S]*?)</title>").matcher(rawHtml);
        if (m.find()) {
            String t = cleanInline(m.group(1));
            if (!t.isEmpty() && !Pattern.matches("(?i)chapter\\s*\\d+\\s*-\\s*\\d+", t)
                    && !Pattern.matches("(?i)chapter\\s*\\d+", t)) return t;
        }
        return "";
    }

    private static String titleFromPath(String path) {
        String n = path;
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        i = n.lastIndexOf('.');
        if (i > 0) n = n.substring(0, i);
        return n.isEmpty() ? "章节" : n;
    }

    // =====================================================================
    // 路径 / 编码 / XML 工具
    // =====================================================================

    static String dirOf(String path) {
        int i = path == null ? -1 : path.lastIndexOf('/');
        return i >= 0 ? path.substring(0, i + 1) : "";
    }

    static String resolve(String baseDir, String href) {
        if (href == null) return "";
        String h = href.replace('\\', '/').trim();
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        if (h.contains("%")) h = decodeUrl(h);
        if (h.startsWith("/")) return normalize(h.substring(1));
        return normalize(baseDir + h);
    }

    static String normalize(String p) {
        if (p == null) return "";
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else parts.add(s);
        }
        return String.join("/", parts);
    }

    static String decodeUrl(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String mimeOf(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }

    private static boolean isImage(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp");
    }

    private static boolean isTextDoc(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        if (n.contains("?")) n = n.substring(0, n.indexOf('?'));
        return n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm");
    }

    static String decode(byte[] b) {
        if (b == null || b.length == 0) return "";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        String head = new String(b, 0, Math.min(b.length, 4096), StandardCharsets.ISO_8859_1);
        String enc = null;
        Matcher m = Pattern.compile("(?i)encoding\\s*=\\s*[\"']([^\"']+)[\"']").matcher(head);
        if (m.find()) enc = m.group(1);
        if (enc == null) {
            m = Pattern.compile("(?i)charset\\s*=\\s*([^\"'>\\s;]+)").matcher(head);
            if (m.find()) enc = m.group(1);
        }
        if (enc != null) {
            try {
                return new String(b, enc.trim());
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (isUtf8(b)) return new String(b, StandardCharsets.UTF_8);
        try {
            return new String(b, "GB18030");
        } catch (Exception e) {
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static boolean isUtf8(byte[] b) {
        int i = 0;
        int n = b.length;
        while (i < n) {
            int c = b[i] & 0xFF;
            int len;
            if (c < 0x80) { i++; continue; }
            else if ((c & 0xE0) == 0xC0) len = 2;
            else if ((c & 0xF0) == 0xE0) len = 3;
            else if ((c & 0xF8) == 0xF0) len = 4;
            else return false;
            if (i + len > n) return false;
            for (int k = 1; k < len; k++) {
                if ((b[i + k] & 0xC0) != 0x80) return false;
            }
            if (len == 3 && c == 0xE0 && (b[i + 1] & 0xE0) == 0x80) return false;
            if (len == 3 && c == 0xED && (b[i + 1] & 0xA0) == 0xA0) return false;
            i += len;
        }
        return true;
    }

    static Document parseXml(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setValidating(false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            trySet(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder b = f.newDocumentBuilder();
            String s = xml.charAt(0) == '\uFEFF' ? xml.substring(1) : xml;
            // NCX/nav 可能引用外部 DTD，这里直接不联网取
            return b.parse(new InputSource(new StringReader(s)));
        } catch (Exception e) {
            return null;
        }
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // 部分实现不支持该特性
        }
    }

    private static String local(Element el) {
        String n = el.getLocalName();
        return n != null ? n : el.getNodeName();
    }

    private static String attr(Element el, String name) {
        if (el == null) return null;
        String v = el.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static String attrNs(Element el, String prefix, String name) {
        if (el == null) return null;
        String ns = el.lookupNamespaceURI(prefix);
        String v = ns == null ? null : el.getAttributeNS(ns, name);
        if (v != null && !v.isEmpty()) return v;
        v = el.getAttribute(prefix + ":" + name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Element firstElement(Document doc, String localName) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element && localName.equals(local((Element) n))) return (Element) n;
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && localName.equals(local((Element) n))) out.add((Element) n);
        }
        return out;
    }

    private static Element firstChild(Element parent, String localName) {
        List<Element> l = childElements(parent, localName);
        return l.isEmpty() ? null : l.get(0);
    }

    private static Element findDescendant(Element parent, String localName) {
        NodeList all = parent.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element && localName.equals(local((Element) n))) return (Element) n;
        }
        return null;
    }

    private static int indexOfCi(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    // =====================================================================
    // 文件名兜底（与 TXT 导入保持一致）
    // =====================================================================

    private static void applyFileNameFallback(ParseResult r, String fileName, String hintTitle, String hintAuthor) {
        if (hintTitle != null && !hintTitle.trim().isEmpty()) {
            r.title = hintTitle.trim();
        }
        if (hintAuthor != null && !hintAuthor.trim().isEmpty()
                && !isJunkMeta(hintAuthor) && looksLikeAuthor(hintAuthor)) {
            r.author = hintAuthor.trim();
        }
        if (!r.title.isEmpty() && !r.author.isEmpty()) return;
        if (fileName == null || fileName.isEmpty()) return;
        String base = fileName;
        int i = base.lastIndexOf('/');
        if (i >= 0) base = base.substring(i + 1);
        i = base.lastIndexOf('\\');
        if (i >= 0) base = base.substring(i + 1);
        base = base.replaceAll("(?i)\\.epub$", "").trim();
        if (base.isEmpty()) return;

        String t = base, a = "";
        // 《书名》作者：某某 / 《书名》著 某某 —— 合集类文件名的主流写法，
        // 「作者：」前缀必须剥掉，否则「作者：醛石」含全角冒号会被 looksLikeAuthor 拒掉
        Matcher mkw = Pattern.compile("^\\s*[《【]?\\s*([^》】]{1,60}?)\\s*[》】]?\\s*"
                + "(?:作\\s*者|著\\s*者|作者名|写手|笔名|著)\\s*[:：\\s]\\s*(.{1,20})\\s*$").matcher(base);
        if (mkw.find()) {
            t = mkw.group(1).trim();
            String cand = mkw.group(2).trim().replaceFirst("\\s*(?:著|作品|创作)$", "").trim();
            if (looksLikeAuthor(cand)) a = cand;
        } else {
            Matcher m = Pattern.compile("^\\s*[《【]([^》】]+)[》】]\\s*[（(\\[]\\s*([^）)\\]\\s]+)\\s*[）)\\]]?\\s*$").matcher(base);
            if (m.find()) {
                t = m.group(1).trim();
                a = m.group(2).trim();
            } else {
                m = Pattern.compile("^\\s*(.+?)\\s*[（(\\[]\\s*([^（(\\[）)\\]]{1,20})\\s*[）)\\]]\\s*$").matcher(base);
                if (m.find() && looksLikeAuthor(m.group(2))) {
                    t = m.group(1).trim();
                    a = m.group(2).trim();
                } else {
                    // 书名-作者 / 书名 - 作者：合集类文件名的主流写法。
                    // 注意连字符两侧**可能没有空白**（实测某 2352 本合集里 2341 本都是
                    // 「三国之乱臣贼子-秀才会武术.epub」这种紧贴写法），因此不能用
                    // 「\s+-\s+」要求空白，否则作者全丢。
                    // 右侧先用 looksLikeAuthor 兜底，避免把「斗破苍穹-天蚕土豆-全集」
                    // 这种多段名切错。
                    m = Pattern.compile("^\\s*(.+?)\\s*-\\s*(.+?)\\s*$").matcher(base);
                    if (m.find() && looksLikeAuthor(m.group(2))) {
                        t = m.group(1).trim();
                        a = cleanAuthorTail(m.group(2).trim());
                    }
                }
            }
        }
        t = t.replaceAll("[《》]", "").trim();
        if (r.title.isEmpty()) r.title = t;
        if (r.author.isEmpty()) r.author = a;
    }

    /**
     * 剥掉作者名尾部的下载序号后缀，如 {@code 剑西来(1)} → {@code 剑西来}。
     *
     * <p>合集类压缩包解压后常见「同名文件自动加 (1)(2)」的产物，实测 2352 本里 185 本如此。
     * 只在括号内是纯数字/单个字母时才剥，避免误伤「火星引力(网名)」这类真括号名。
     */
    private static String cleanAuthorTail(String s) {
        if (s == null) return "";
        String v = s.trim();
        v = v.replaceAll("[（(]\\s*\\d{1,2}\\s*[）)]\\s*$", "").trim();
        return v;
    }

    private static boolean looksLikeAuthor(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.isEmpty() || v.length() > 16) return false;
        if (v.matches("(?i).*(全集|全本|完本|精校|校对|txt|epub|www\\.|\\.com|笔趣|小说网).*")) return false;
        // 元数据里的占位作者：dc:creator 为 Unknown/未知 时等于没有，不能让「Unknown」
        // 这种值通过文件名兜底又被捞回来（抽样 120 本里 dc:creator 非空的 27 本中约 1/3 是占位值）
        if (v.matches("(?i)^\\s*(unknown|n/?a|none|null|undefined|未知|佚名|不详|作者不详|无|—|-)\\s*$")) return false;
        return !v.matches("^\\d+$");
    }
}
