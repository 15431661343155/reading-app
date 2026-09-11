package com.example.readingapp.util;

import com.example.readingapp.util.EpubBookParser.ImageAsset;
import com.example.readingapp.util.EpubBookParser.ParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EPUB 解析回归测试（纯内存构造最小 EPUB，不依赖外部文件）。
 *
 * <p>覆盖：OPF 元信息读取、NCX 嵌套目录→分卷、章节切分与 sortKey、
 * 正文保样式（内联 CSS + img）、小图内联 data URI / 大图登记为外部资源占位符、
 * 简介页跳过并提取简介、目录页跳过、占位元数据回退文件名。
 */
class EpubBookParserTest {

    private static final String OPF = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"bookid\">\n" +
            "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
            "    <dc:title>测试书</dc:title>\n" +
            "    <dc:creator>测试作者</dc:creator>\n" +
            "    <dc:description>这是简介内容，足够长用来测试提取逻辑是否正常生效。</dc:description>\n" +
            "    <dc:language>zh-CN</dc:language>\n" +
            "  </metadata>\n" +
            "  <manifest>\n" +
            "    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n" +
            "    <item id=\"intro\" href=\"intro.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
            "    <item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
            "    <item id=\"c2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
            "    <item id=\"c3\" href=\"ch3.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
            "    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n" +
            "    <item id=\"img1\" href=\"img1.png\" media-type=\"image/png\"/>\n" +
            "    <item id=\"img2\" href=\"img2.png\" media-type=\"image/png\"/>\n" +
            "  </manifest>\n" +
            "  <spine toc=\"ncx\">\n" +
            "    <itemref idref=\"intro\"/>\n" +
            "    <itemref idref=\"c1\"/>\n" +
            "    <itemref idref=\"c2\"/>\n" +
            "    <itemref idref=\"c3\"/>\n" +
            "  </spine>\n" +
            "</package>";

    private static final String NCX = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n" +
            "  <head><meta name=\"dtb:uid\" content=\"x\"/></head>\n" +
            "  <docTitle><text>测试书</text></docTitle>\n" +
            "  <navMap>\n" +
            "    <navPoint id=\"v1\">\n" +
            "      <navLabel><text>第一卷 起始</text></navLabel>\n" +
            "      <navPoint id=\"n1\"><navLabel><text>第一章 开端</text></navLabel><content src=\"ch1.xhtml\"/></navPoint>\n" +
            "      <navPoint id=\"n2\"><navLabel><text>第二章 发展</text></navLabel><content src=\"ch2.xhtml\"/></navPoint>\n" +
            "    </navPoint>\n" +
            "    <navPoint id=\"v2\">\n" +
            "      <navLabel><text>第二卷 高潮</text></navLabel>\n" +
            "      <navPoint id=\"n3\"><navLabel><text>第三章 结局</text></navLabel><content src=\"ch3.xhtml\"/></navPoint>\n" +
            "    </navPoint>\n" +
            "  </navMap>\n" +
            "</ncx>";

    private static final String STYLE = ".epub-chapter p{text-indent:2em;line-height:1.6;}";

    private static String chapter(String title, String body, String imgs) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                "<head><title>" + title + "</title><link href=\"style.css\" rel=\"stylesheet\"/></head>\n" +
                "<body><h2>" + title + "</h2><p>" + body + "</p>" + imgs + "</body></html>";
    }

    private static byte[] pngMagic(int size) {
        byte[] b = new byte[size];
        b[0] = (byte) 0x89; b[1] = 'P'; b[2] = 'N'; b[3] = 'G';
        return b;
    }

    private static byte[] buildEpub() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(("<?xml version=\"1.0\"?><container version=\"1.0\" " +
                    "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
                    "media-type=\"application/oebps-package+xml\"/></rootfiles></container>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(OPF.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write(NCX.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/style.css"));
            zos.write(STYLE.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/intro.xhtml"));
            zos.write(chapter("书籍简介", "简介正文内容", "").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
            zos.write(chapter("第一章 开端", "开头第一句。", "<img src=\"img1.png\" alt=\"小图\"/>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/ch2.xhtml"));
            zos.write(chapter("第二章 发展", "中间第二句。", "<img src=\"img2.png\" alt=\"大图\"/>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/ch3.xhtml"));
            zos.write(chapter("第三章 结局", "结尾第三句。", "").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/img1.png"));
            zos.write(pngMagic(120)); // 小图 -> 内联 data URI
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/img2.png"));
            zos.write(pngMagic(20000)); // 大图 -> 外部资源占位符
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static ParseResult parse() throws Exception {
        return EpubBookParser.parse(buildEpub(), "测试书 (测试作者).epub", null, null);
    }

    @Test
    @DisplayName("OPF 元信息：书名 / 作者 / 简介")
    void metadata() throws Exception {
        ParseResult r = parse();
        assertEquals("测试书", r.title);
        assertEquals("测试作者", r.author);
        assertTrue(r.intro.contains("简介内容"), "简介应从书籍简介页提取: " + r.intro);
    }

    @Test
    @DisplayName("NCX 嵌套目录 → 分卷")
    void volumes() throws Exception {
        ParseResult r = parse();
        assertEquals(2, r.volumeTitles.size());
        assertEquals("第一卷 起始", r.volumeTitles.get(0));
        assertEquals("第二卷 高潮", r.volumeTitles.get(1));
    }

    @Test
    @DisplayName("章节切分与 sortKey（卷内连续编号，跨卷归零）")
    void chaptersAndSortKey() throws Exception {
        ParseResult r = parse();
        // intro 页应被跳过，剩 3 章
        assertEquals(3, r.chapters.size());
        assertEquals("第一章 开端", r.chapters.get(0).title);
        assertEquals("1-0001", r.chapters.get(0).sortKey);
        assertEquals(1, r.chapters.get(0).volumeIndex);
        assertEquals("第二章 发展", r.chapters.get(1).title);
        assertEquals("1-0002", r.chapters.get(1).sortKey);
        assertEquals("第三章 结局", r.chapters.get(2).title);
        assertEquals("2-0001", r.chapters.get(2).sortKey); // 第二卷重新编号
        assertEquals(2, r.chapters.get(2).volumeIndex);
    }

    @Test
    @DisplayName("正文保样式：内联 CSS + 段落结构保留")
    void htmlPreserved() throws Exception {
        ParseResult r = parse();
        String html = r.chapters.get(0).html;
        assertTrue(html.contains("<style>"), "应内联 style.css");
        assertTrue(html.contains("<p>开头第一句。</p>") || html.contains("<p>开头第一句。"), "段落结构应保留: " + html);
        // 纯文本兜底也应存在
        assertFalse(r.chapters.get(0).text.isEmpty());
    }

    @Test
    @DisplayName("小图内联为 data URI，大图登记为外部资源占位符")
    void images() throws Exception {
        ParseResult r = parse();
        String ch1 = r.chapters.get(0).html;
        String ch2 = r.chapters.get(1).html;
        assertTrue(ch1.contains("data:image/png;base64,"), "小图应内联为 data URI");
        assertTrue(ch2.contains("epubimg://"), "大图应登记为 epubimg:// 占位符: " + ch2);
        assertEquals(1, r.images.size(), "应只登记 1 张外部大图");
        ImageAsset a = r.images.get(0);
        assertEquals("OEBPS/img2.png", a.zipPath);
        assertTrue(a.fileName.endsWith(".png"));
    }

    @Test
    @DisplayName("replaceImageTokens 把占位符回填为真实 URL")
    void replaceTokens() {
        Map<Integer, String> url = new HashMap<>();
        url.put(0, "/books/1/0_img2.png");
        String out = EpubBookParser.replaceImageTokens("看图 <img src=\"epubimg://0\"/> 结束", url);
        assertTrue(out.contains("/books/1/0_img2.png"));
        assertFalse(out.contains("epubimg://"));
        // 无占位符时原样返回
        assertEquals("纯文本", EpubBookParser.replaceImageTokens("纯文本", url));
    }

    @Test
    @DisplayName("简介页被跳过（不生成章节）")
    void introSkipped() throws Exception {
        ParseResult r = parse();
        for (var c : r.chapters) assertNotEquals("书籍简介", c.title);
        assertTrue(r.skippedPages >= 1);
    }

    @Test
    @DisplayName("OPF 占位元数据回退文件名")
    void junkMetaFallback() throws Exception {
        // 构造 OPF 里作者填"作者"的占位，应被忽略并回退文件名
        String opf = OPF.replace("<dc:creator>测试作者</dc:creator>", "<dc:creator>作者</dc:creator>");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(("<?xml version=\"1.0\"?><container version=\"1.0\" " +
                    "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
                    "media-type=\"application/oebps-package+xml\"/></rootfiles></container>").getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opf.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write(NCX.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (String c : new String[]{"ch1", "ch2", "ch3"}) {
                zos.putNextEntry(new ZipEntry("OEBPS/" + c + ".xhtml"));
                zos.write(chapter(c, "正文", "").getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        ParseResult r = EpubBookParser.parse(bos.toByteArray(), "测试书 (彭湃).epub", null, null);
        // "作者" 是占位符 → 回退文件名得到 彭湃
        assertEquals("测试书", r.title);
        assertEquals("彭湃", r.author);
    }
}
