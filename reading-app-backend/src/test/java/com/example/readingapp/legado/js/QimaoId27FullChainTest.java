package com.example.readingapp.legado.js;

import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.legado.LegadoBookSourceService;
import com.example.readingapp.legado.model.LegadoBookSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * 验证 id=27 七猫书源的完整阅读链：
 * 1. 从数据库加载 configJson → LegadoBookSource
 * 2. getExploreCategories() → 分类列表
 * 3. exploreBooks(page, size, category) → 书城数据
 * 4. getBookInfo(bookUrl) → 书籍详情 + tocUrl
 * 5. getChapterList(tocUrl) → 章节列表
 * 每一步都需要真实的 HTTP 请求到七猫 API。
 */
class QimaoId27FullChainTest {

    private LegadoBookSource loadSourceFromDb(int id) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/reading-app?useSSL=false&serverTimezone=Asia/Shanghai",
                "reading-app", "123456")) {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT config_json FROM book_source WHERE id=" + id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            String json = rs.getString(1);
            return new ObjectMapper().readValue(json, LegadoBookSource.class);
        }
    }

    @Test
    void testFullChain() throws Exception {
        // 0. 从数据库加载 id=27 的书源配置
        LegadoBookSource source = loadSourceFromDb(27);
        if (source == null) {
            System.out.println("未找到 id=27 的书源，测试终止");
            return;
        }
        System.out.println("书源名称: " + source.getBookSourceName());
        System.out.println("bookSourceUrl: " + source.getBookSourceUrl());
        System.out.println("exploreUrl 非空? " + (source.getExploreUrl() != null && !source.getExploreUrl().isEmpty()));
        System.out.println("jsLib 非空? " + (source.getJsLib() != null && !source.getJsLib().isEmpty()));

        LegadoBookSourceService service = new LegadoBookSourceService(source);

        // 1. 获取分类
        List<String[]> categories = service.getExploreCategories();
        System.out.println("\n===== 1. 分类列表 =====");
        System.out.println("分类数量: " + categories.size());
        for (String[] cat : categories) {
            System.out.println("  " + cat[0] + " -> " + (cat[1].length() > 80 ? cat[1].substring(0, 80) + "..." : cat[1]));
        }

        if (categories.isEmpty()) {
            System.out.println("无分类，测试终止");
            return;
        }

        // 2. 获取第一个分类的书城数据
        String firstCategory = categories.get(0)[1];
        System.out.println("\n===== 2. 书城数据（分类: " + categories.get(0)[0] + "）=====");
        List<Book> books = service.exploreBooks(1, 5, firstCategory);
        System.out.println("书籍数量: " + books.size());
        for (Book b : books) {
            System.out.println("  《" + b.getTitle() + "》 " + b.getAuthor()
                    + " | sourceUrl=" + (b.getSourceUrl() != null && b.getSourceUrl().length() > 80
                            ? b.getSourceUrl().substring(0, 80) + "..." : b.getSourceUrl()));
        }

        if (books.isEmpty()) {
            System.out.println("无书籍，测试终止");
            return;
        }

        // 3. 获取第一本书的详情（含 tocUrl）
        String bookUrl = books.get(0).getSourceUrl();
        System.out.println("\n===== 3. 书籍详情 =====");
        System.out.println("bookUrl: " + (bookUrl.length() > 100 ? bookUrl.substring(0, 100) + "..." : bookUrl));
        Book info = service.getBookInfo(bookUrl);
        if (info == null) {
            System.out.println("获取详情失败，测试终止");
            return;
        }
        System.out.println("书名: " + info.getTitle());
        System.out.println("作者: " + info.getAuthor());
        System.out.println("tocUrl: " + (info.getTocUrl() != null && info.getTocUrl().length() > 100
                ? info.getTocUrl().substring(0, 100) + "..." : info.getTocUrl()));

        if (info.getTocUrl() == null || info.getTocUrl().isEmpty()) {
            System.out.println("tocUrl 为空，测试终止");
            return;
        }

        // 4. 获取章节列表
        System.out.println("\n===== 4. 章节列表 =====");
        List<Chapter> chapters = service.getChapterList(info.getTocUrl());
        System.out.println("章节数量: " + chapters.size());
        for (int i = 0; i < Math.min(5, chapters.size()); i++) {
            Chapter ch = chapters.get(i);
            System.out.println("  [" + i + "] " + ch.getTitle() + " | url="
                    + (ch.getUrl() != null && ch.getUrl().length() > 80
                            ? ch.getUrl().substring(0, 80) + "..." : ch.getUrl()));
        }
        if (chapters.size() > 5) {
            System.out.println("  ... 共 " + chapters.size() + " 章");
        }
        System.out.println("\n===== 测试完成 =====");
    }
}
