package com.example.readingapp.util;

import com.example.readingapp.util.TxtBookParser.ParseResult;
import com.example.readingapp.util.TxtBookParser.RawChapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TXT 解析回归测试。
 *
 * <p>覆盖：文件名元信息提取、编码嗅探、目录页跳过、正文内"第X章"不误切、
 * 盗版广告行剔除、被广告切断的重复章节合并、分卷识别。
 */
class TxtBookParserTest {

    private static ParseResult parse(String text, String fileName) {
        return TxtBookParser.parseText(text, fileName, null, null);
    }

    // ============ 文件名 → 书名/作者 ============

    @Test
    @DisplayName("文件名：书名(作者).txt")
    void fileNameWithAuthorInParentheses() {
        String[] r = TxtBookParser.parseFileName("修仙就是这样子的(凤嘲凰).txt");
        assertEquals("修仙就是这样子的", r[0]);
        assertEquals("凤嘲凰", r[1]);
    }

    @Test
    @DisplayName("文件名：《书名》(作者).txt")
    void fileNameWithBookMarks() {
        String[] r = TxtBookParser.parseFileName("《宿命之环》(爱潜水的乌贼).txt");
        assertEquals("宿命之环", r[0]);
        assertEquals("爱潜水的乌贼", r[1]);
    }

    @Test
    @DisplayName("文件名：空格 + 括号 + 结尾空格")
    void fileNameWithSpaces() {
        String[] r = TxtBookParser.parseFileName("苟在初圣魔门当人材 (鹤守月满池).txt");
        assertEquals("苟在初圣魔门当人材", r[0]);
        assertEquals("鹤守月满池", r[1]);

        String[] r2 = TxtBookParser.parseFileName("老祖,时代变了 (阿玖未) .txt");
        assertEquals("老祖,时代变了", r2[0]);
        assertEquals("阿玖未", r2[1]);
    }

    @Test
    @DisplayName("文件名：剔除站点广告与垃圾词")
    void fileNameSiteJunk() {
        String[] r = TxtBookParser.parseFileName("某小说[笔趣阁www.xxx.com].txt");
        assertEquals("某小说", r[0]);
        assertEquals("", r[1]);

        // 括号里是"全集下载"这类垃圾词时，不能当成作者
        String[] r2 = TxtBookParser.parseFileName("某小说(全集下载).txt");
        assertEquals("某小说", r2[0]);
        assertEquals("", r2[1]);
    }

    @Test
    @DisplayName("文件名：无作者时书名为主干")
    void fileNameNoAuthor() {
        String[] r = TxtBookParser.parseFileName("完美世界.txt");
        assertEquals("完美世界", r[0]);
        assertEquals("", r[1]);
    }

    // ============ 中文数字 ============

    @Test
    @DisplayName("中文数字转换")
    void chineseNumber() {
        assertEquals(1, TxtBookParser.chineseToInt("一"));
        assertEquals(10, TxtBookParser.chineseToInt("十"));
        assertEquals(11, TxtBookParser.chineseToInt("十一"));
        assertEquals(20, TxtBookParser.chineseToInt("二十"));
        assertEquals(583, TxtBookParser.chineseToInt("五百八十三"));
        assertEquals(1012, TxtBookParser.chineseToInt("一千零一十二"));
        assertEquals(1303, TxtBookParser.chineseToInt("一千三百零三"));
        assertEquals(2, TxtBookParser.chineseToInt("002"));
        // 非数字串必须返回 -1（"第一道化形雷劫卷起"不应被当成卷）
        assertEquals(-1, TxtBookParser.chineseToInt("道化形雷劫"));
        assertEquals(-1, TxtBookParser.chineseToInt("回合"));
    }

    // ============ 核心 bug：分隔线开头 + 元信息只在文件名里 ============

    @Test
    @DisplayName("正文以分隔线/卷名开头时，书名作者回退到文件名（不把分隔线当书名）")
    void titleFallsBackToFileNameNotSeparator() {
        String text = "\n"
                + "------------\n"
                + "\n"
                + "正文卷\n"
                + "\n"
                + "\n"
                + "------------\n"
                + "\n"
                + "第一章 开局师父没了\n"
                + "\n"
                + "武周。\n"
                + "\n"
                + "宁州，东阳郡，琅瑜县北。\n"
                + "\n"
                + "第二章 然后师姐就来了\n"
                + "\n"
                + "正文内容若干，这里只是用来保证这一章有足够长度被判定为正文而不是目录。\n";

        ParseResult r = parse(text, "修仙就是这样子的(凤嘲凰).txt");
        assertEquals("修仙就是这样子的", r.title, "分隔线不能被当成书名");
        assertEquals("凤嘲凰", r.author);
        assertEquals("文件名", r.titleSource);
        assertEquals(2, r.chapterCount());
        assertEquals("第一章 开局师父没了", r.chapters.get(0).title);
        assertEquals("第二章 然后师姐就来了", r.chapters.get(1).title);
        assertFalse(r.chapters.get(0).contentText().isEmpty());
    }

    @Test
    @DisplayName("手动填写的书名优先于文件名")
    void manualTitleWins() {
        String text = "第一章 开局\n\n" + "内容".repeat(60) + "\n";
        ParseResult r = TxtBookParser.parseText(text, "某书名(某作者).txt", "手动书名", "手动作者");
        assertEquals("手动书名", r.title);
        assertEquals("手动作者", r.author);
        assertEquals("手动输入", r.titleSource);
    }

    @Test
    @DisplayName("正文里的《书名》/作者标注优先于文件名")
    void contentLabelWins() {
        String text = "《正式书名》\n"
                + "作者：凤嘲凰\n"
                + "简介：这是一段足够长的简介文本，用于验证简介提取是否正常工作，长度要超过最小阈值。\n"
                + "\n"
                + "第一章 开局\n"
                + "\n"
                + "内容".repeat(60) + "\n"
                + "第二章 后续\n"
                + "\n"
                + "内容".repeat(60) + "\n";
        ParseResult r = parse(text, "某文件名.txt");
        assertEquals("正式书名", r.title);
        assertEquals("凤嘲凰", r.author);
        assertEquals("正文标注", r.titleSource);
        assertTrue(r.intro.contains("简介提取"));
    }

    // ============ 分章正确性 ============

    @Test
    @DisplayName("正文中出现的『第X章』不切分章节")
    void inlineChapterWordNotSplit() {
        String body = "内容".repeat(40);
        String text = "第一章 开局\n\n" + body + "\n"
                + "他回头看了一眼第二章提到的那件事，又想起第五百八十三章的细节，都没有放在心上。\n"
                + "\n"
                + "第二章 继续\n\n" + body + "\n";
        ParseResult r = parse(text, "测试.txt");
        assertEquals(2, r.chapterCount(), "正文句子里的『第X章』不能被当成标题行");
        assertTrue(r.chapters.get(0).contentText().contains("第五百八十三章"));
    }

    @Test
    @DisplayName("『第X回合』『第X节点』这类量词不是章节")
    void measureWordNotChapter() {
        String body = "内容".repeat(40);
        String text = "第一章 开局\n\n" + body + "\n第二回合开始。\n第一次天地大变的节点，是他唯一的生机，不争难道等死吗？这句话很长所以不会被误判。\n";
        ParseResult r = parse(text, "测试.txt");
        assertEquals(1, r.chapterCount());
    }

    @Test
    @DisplayName("目录页（标题后无正文）不会被当成章节")
    void tableOfContentsSkipped() {
        StringBuilder sb = new StringBuilder();
        sb.append("目录\n");
        for (int i = 1; i <= 5; i++) {
            sb.append("第").append(i).append("章 章节标题\n");
        }
        sb.append("\n");
        for (int i = 1; i <= 3; i++) {
            sb.append("第").append(i).append("章 章节标题\n\n").append("内容".repeat(40)).append("\n");
        }
        ParseResult r = parse(sb.toString(), "测试.txt");
        assertEquals(3, r.chapterCount(), "目录行没有正文，应被跳过");
        assertEquals("第1章 章节标题", r.chapters.get(0).title);
    }

    @Test
    @DisplayName("盗版广告行被剔除，被广告切断的同一章会被合并")
    void adLinesRemovedAndDuplicateMerged() {
        String body = "内容".repeat(40);
        String text = "第一章 开局师父没了\n\n" + body + "\n"
                + "为您提供大神凤嘲凰的《测试》最快更新，！\n"
                + "第一章 开局师父没了\n\n" + body + "\n"
                + "『』 ，最快更新最新章节！\n"
                + "第一章 开局师父没了\n\n" + body + "\n"
                + "第二章 后续\n\n" + body + "\n";
        ParseResult r = parse(text, "测试.txt");
        assertEquals(2, r.chapterCount(), "被广告切断的同一章应合并为一章");
        assertEquals(2, r.mergedChapters);
        assertTrue(r.adLinesRemoved >= 2, "广告行应被剔除");
        assertFalse(r.chapters.get(0).contentText().contains("为您提供"));
        assertTrue(r.chapters.get(0).contentText().length() > body.length() * 2);
    }

    @Test
    @DisplayName("章节标题里的『免费阅读』等广告后缀被清理")
    void adSuffixStrippedFromTitle() {
        String body = "内容".repeat(40);
        String text = "第六百一十三章 氐土貉免费阅读.\n\n" + body + "\n"
                + "第六百一十四章 后续\n\n" + body + "\n";
        ParseResult r = parse(text, "测试.txt");
        assertEquals(2, r.chapterCount());
        assertEquals("第六百一十三章 氐土貉", r.chapters.get(0).title);
        assertEquals(613, r.chapters.get(0).number);
    }

    @Test
    @DisplayName("作者单章不会触发误分卷")
    void authorNoteDoesNotBreakVolume() {
        String body = "内容".repeat(40);
        String text = "第二百四十五章 叶孤月！死\n\n" + body + "\n"
                + "第万章 订啦！求月票！\n\n" + body + "\n"
                + "第二百四十六章 二品真功之秘\n\n" + body + "\n"
                + "第二百四十七章 继续\n\n" + body + "\n";
        ParseResult r = parse(text, "测试.txt");
        assertEquals(1, r.volumeTitles.size(), "作者单章不应造成分卷");
        assertEquals(1, r.chapters.get(r.chapters.size() - 1).volumeLevel);
    }

    // ============ 分卷 ============

    @Test
    @DisplayName("章节号重新开始 → 自动分卷")
    void restartNumberingSplitsVolume() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            sb.append("第").append(i).append("章 章节\n\n").append("内容".repeat(40)).append("\n");
        }
        for (int i = 1; i <= 3; i++) {
            sb.append("第").append(i).append("章 新卷章节\n\n").append("内容".repeat(40)).append("\n");
        }
        ParseResult r = parse(sb.toString(), "测试.txt");
        assertEquals(2, r.volumeTitles.size());
        assertEquals(6, r.chapterCount());
        assertEquals("2-0001", r.chapters.get(3).sortKey);
        assertEquals(2, r.chapters.get(3).volumeLevel);
    }

    @Test
    @DisplayName("『第一部分第002回』前缀格式：识别章节并按前缀分卷")
    void prefixedChapterAndVolumes() {
        StringBuilder sb = new StringBuilder();
        sb.append("第一回 开篇\n\n").append("内容".repeat(40)).append("\n");
        for (int i = 2; i <= 3; i++) {
            sb.append("第一部分第").append(String.format("%03d", i)).append("回　标题").append(i)
                    .append("\n\n").append("内容".repeat(40)).append("\n");
        }
        for (int i = 4; i <= 5; i++) {
            sb.append("第二部分第").append(String.format("%03d", i)).append("回　标题").append(i)
                    .append("\n\n").append("内容".repeat(40)).append("\n");
        }
        ParseResult r = parse(sb.toString(), "西游记.txt");
        assertEquals(2, r.volumeTitles.size());
        assertEquals("第一部分", r.volumeTitles.get(0));
        assertEquals("第二部分", r.volumeTitles.get(1));
        assertEquals(5, r.chapterCount());
        assertEquals(2, r.chapters.get(1).number, "002 应解析为 2");
    }

    // ============ sortKey / 排序 ============

    @Test
    @DisplayName("sortKey 与章节一一对应且不重复（旧版存在错位 bug）")
    void sortKeyAlignment() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sb.append("第").append(i).append("章 章节\n\n").append("内容".repeat(40)).append("\n");
        }
        ParseResult r = parse(sb.toString(), "测试.txt");
        List<RawChapter> cs = r.chapters;
        assertEquals(5, cs.size());
        for (int i = 0; i < cs.size(); i++) {
            assertEquals(String.format("1-%04d", i + 1), cs.get(i).sortKey,
                    "第 " + (i + 1) + " 个章节的 sortKey 错位");
        }
    }

    // ============ 编码 ============

    @Test
    @DisplayName("GB18030 编码文件能被正确识别并解码")
    void gbkEncodingDetected() {
        String text = "第一章 开局\n\n" + "内容".repeat(40) + "\n第二章 后续\n\n" + "内容".repeat(40) + "\n";
        byte[] gbk = text.getBytes(Charset.forName("GB18030"));
        assertEquals("GB18030", TxtBookParser.detectEncoding(gbk));

        ParseResult r = TxtBookParser.parse(gbk, "西游记.txt", null, null);
        assertEquals("西游记", r.title);
        assertEquals(2, r.chapterCount());
        assertEquals("第一章 开局", r.chapters.get(0).title);
    }

    @Test
    @DisplayName("UTF-8 带 BOM 文件首行不留 BOM 字符")
    void utf8BomStripped() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = ("《带BOM的书》\n作者：某人\n\n第一章 开局\n\n" + "内容".repeat(40) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);

        ParseResult r = TxtBookParser.parse(all, "任意.txt", null, null);
        assertEquals("带BOM的书", r.title);
    }

    // ============ 边界 ============

    @Test
    @DisplayName("没有任何章节时返回空章节列表而不是报错")
    void noChapterFound() {
        ParseResult r = parse("这是一段没有任何章节标记的纯文本。\n没有任何章节标题。\n", "某书.txt");
        assertEquals(0, r.chapterCount());
        assertEquals("某书", r.title);
        assertEquals("佚名", r.author);
    }

    @Test
    @DisplayName("空文件与空文件名不抛异常")
    void emptyInput() {
        ParseResult r = TxtBookParser.parseText("", null, null, null);
        assertEquals(0, r.chapterCount());
        assertEquals("佚名", r.author);
    }
}
