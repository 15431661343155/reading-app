package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.entity.MajorChapter;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.repository.ChapterRepository;
import com.example.readingapp.repository.MajorChapterRepository;
import com.example.readingapp.service.OperationLogService;
import com.example.readingapp.util.EpubBookParser;
import com.example.readingapp.util.TxtBookParser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/import")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminImportController {

    private final JdbcTemplate jdbcTemplate;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final MajorChapterRepository majorChapterRepository;
    private final OperationLogService operationLogService;

    @Value("${file.upload.cover-path:uploads/covers/}")
    private String coverPath;

    @Value("${file.upload.books-path:uploads/books/}")
    private String booksPath;

    // 执行自定义 SQL
    @PostMapping("/sql")
    public ApiResponse<String> importSQL(@RequestBody Map<String, String> request) {
        try {
            String sql = request.get("sql");
            if (sql == null || sql.trim().isEmpty()) {
                return ApiResponse.error("SQL语句不能为空");
            }
            
            // 安全检查：禁止危险操作
            String upperSql = sql.toUpperCase();
            if (upperSql.contains("DROP") || upperSql.contains("TRUNCATE") || 
                upperSql.contains("DELETE FROM") || upperSql.contains("UPDATE ")) {
                return ApiResponse.error("禁止执行危险的SQL操作（DROP/TRUNCATE/DELETE/UPDATE）");
            }
            
            String[] statements = sql.split(";");
            int count = 0;
            for (String stmt : statements) {
                stmt = stmt.trim();
                if (!stmt.isEmpty()) {
                    jdbcTemplate.execute(stmt);
                    count++;
                }
            }
            return ApiResponse.success("成功执行 " + count + " 条SQL语句", null);
        } catch (Exception e) {
            return ApiResponse.error("执行失败: " + e.getMessage());
        }
    }

    // 快速生成测试数据
    @PostMapping("/generate")
    @Transactional
    public ApiResponse<String> generateData(@RequestBody Map<String, Object> request) {
        try {
            String title = (String) request.get("title");
            String author = (String) request.get("author");
            String category = (String) request.get("category");
            String intro = (String) request.get("intro");
            int chapterCount = (int) request.getOrDefault("chapterCount", 10);

            // 创建书籍
            Book book = new Book();
            book.setTitle(title);
            book.setAuthor(author);
            book.setCategory(category);
            book.setIntro(intro);
            book.setChapterCount(chapterCount);
            book.setStatus(chapterCount > 50 ? 1 : 0);
            book = bookRepository.save(book);

            // 生成章节
            String lorem = "这是一个关于修仙的故事。天元大陆，以武为尊，强者可翻云覆雨，弱者只能任人宰割。少年自小父母双亡，在家族中受尽欺凌。但他从未放弃变强的信念。一日，在后山采药时，意外发现一处神秘洞府...";

            for (int i = 1; i <= chapterCount; i++) {
                Chapter chapter = new Chapter();
                chapter.setBookId(book.getId());
                chapter.setTitle("第" + i + "章");
                chapter.setSortOrder(i);
                chapter.setWordCount(lorem.length() + 50);
                chapter.setContent(lorem + "\n\n（第" + i + "章内容）");
                chapterRepository.save(chapter);
            }

            // 更新书籍总字数（去空格计算）
            List<Chapter> generatedChapters = chapterRepository.findByBookIdOrderBySortOrderAsc(book.getId());
            int totalWordCount = 0;
            for (Chapter c : generatedChapters) {
                if (c.getContent() != null) {
                    totalWordCount += c.getContent().replaceAll("\\s+", "").length();
                }
            }
            book.setWordCount(totalWordCount);
            bookRepository.save(book);

            return ApiResponse.success("成功生成《" + title + "》共 " + chapterCount + " 章", null);
        } catch (Exception e) {
            return ApiResponse.error("生成失败: " + e.getMessage());
        }
    }

    // 上传 TXT 自动分章（自动提取编码、书名、作者、简介、分卷）
    @PostMapping("/txt")
    @Transactional
    public ApiResponse<String> uploadTxt(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "autoExtract", defaultValue = "true") boolean autoExtract,
            HttpServletRequest request) {
        try {
            byte[] data = file.getBytes();
            if (data.length == 0) {
                return ApiResponse.error("文件内容为空");
            }

            // 未勾选「自动提取」时，不使用文件名与正文推断，只认手动填写的值
            TxtBookParser.ParseResult parsed = TxtBookParser.parse(
                    data, autoExtract ? file.getOriginalFilename() : null, title, author);

            if (parsed.title == null || parsed.title.trim().isEmpty()) {
                return ApiResponse.error("无法从文件名或正文中提取书名，请手动输入");
            }
            if (parsed.chapterCount() == 0) {
                return ApiResponse.error("未识别到任何章节，请检查文件是否为标准 TXT 小说"
                        + "（章节标题需以「第X章」等形式独占一行）");
            }

            Book book = new Book();
            book.setTitle(parsed.title.trim());
            book.setAuthor(parsed.author == null || parsed.author.trim().isEmpty()
                    ? "佚名" : parsed.author.trim());
            book.setCategory(category != null ? category.trim() : "");
            book.setIntro(parsed.intro == null ? "" : parsed.intro.trim());
            book.setChapterCount(parsed.chapterCount());
            book.setStatus(0);
            book = bookRepository.save(book);

            // 写入章节（sortKey 由解析器保证唯一且有序）
            int totalWordCount = 0;
            int order = 0;
            for (TxtBookParser.RawChapter rc : parsed.chapters) {
                String content = rc.contentText();
                int words = content.replaceAll("\\s+", "").length();
                totalWordCount += words;

                Chapter chapter = new Chapter();
                chapter.setBookId(book.getId());
                chapter.setTitle(rc.title);
                chapter.setContent(content);
                chapter.setSortOrder(++order);
                chapter.setSortKey(rc.sortKey);
                chapter.setWordCount(words);
                chapterRepository.save(chapter);
            }
            book.setWordCount(totalWordCount);
            bookRepository.save(book);

            // 自动创建大章节（分卷）记录
            if (parsed.volumeTitles.size() > 1) {
                for (int level = 1; level <= parsed.volumeTitles.size(); level++) {
                    if (!majorChapterRepository.existsByBookIdAndSortKey(book.getId(),
                            String.valueOf(level))) {
                        MajorChapter mc = new MajorChapter();
                        mc.setBookId(book.getId());
                        mc.setTitle(parsed.volumeTitles.get(level - 1));
                        mc.setSortKey(String.valueOf(level));
                        mc.setSortOrder(level);
                        majorChapterRepository.save(mc);
                    }
                }
            }

            StringBuilder msg = new StringBuilder();
            msg.append("成功导入《").append(parsed.title).append("》共 ")
                    .append(parsed.chapterCount()).append(" 章");
            if (!"佚名".equals(book.getAuthor())) {
                msg.append("，作者：").append(book.getAuthor());
            }
            if (parsed.volumeTitles.size() > 1) {
                msg.append("，分 ").append(parsed.volumeTitles.size()).append(" 卷");
            }
            if (parsed.intro != null && !parsed.intro.isEmpty()) {
                msg.append("，已提取简介");
            }
            if (parsed.adLinesRemoved > 0) {
                msg.append("，清理广告行 ").append(parsed.adLinesRemoved).append(" 条");
            }
            if (parsed.mergedChapters > 0) {
                msg.append("，合并被广告切断的重复章节 ").append(parsed.mergedChapters).append(" 处");
            }
            msg.append("（编码 ").append(parsed.encoding).append("）");

            operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                    "导入TXT书籍：《" + parsed.title + "》（" + parsed.chapterCount() + "章）",
                    book.getId(), getClientIp(request));

            return ApiResponse.success(msg.toString(), null);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    // 获取客户端IP
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    // 上传EPUB文件自动解析（保留原文样式 + 按目录层级还原分卷）
    @PostMapping("/epub")
    @Transactional
    public ApiResponse<String> uploadEpub(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "keepStyle", defaultValue = "true") boolean keepStyle,
            HttpServletRequest request) {
        try {
            byte[] data = file.getBytes();
            EpubBookParser.ParseResult parsed = EpubBookParser.parse(
                    data, file.getOriginalFilename(), title, author);

            String bookTitle = parsed.title == null || parsed.title.trim().isEmpty()
                    ? "未命名书籍" : parsed.title.trim();
            String bookAuthor = parsed.author == null || parsed.author.trim().isEmpty()
                    ? "佚名" : parsed.author.trim();
            String bookIntro = parsed.intro == null ? "" : parsed.intro.trim();

            Book book = new Book();
            book.setTitle(bookTitle);
            book.setAuthor(bookAuthor);
            book.setCategory(category != null ? category.trim() : "");
            book.setIntro(bookIntro);
            book.setChapterCount(0);
            book.setStatus(0);
            String coverUrl = saveEpubCover(parsed);
            if (coverUrl != null) book.setCover(coverUrl);
            book = bookRepository.save(book);

            // 先把体积较大的图片落地成文件，并拿到 epubimg://id -> 真实 URL 的映射
            Map<Integer, String> imgUrl = new HashMap<>();
            if (keepStyle && !parsed.images.isEmpty()) {
                imgUrl = saveEpubImages(parsed, book.getId());
            }

            int chapterCount = 0;
            int totalWords = 0;
            for (EpubBookParser.RawChapter rc : parsed.chapters) {
                Chapter chapter = new Chapter();
                chapter.setBookId(book.getId());
                chapter.setTitle(rc.title);
                chapter.setContent(rc.text);
                // 保留样式的 HTML 单独存一列：为空时阅读器按纯文本渲染，不影响旧数据
                String html = keepStyle && rc.html != null && !rc.html.isEmpty()
                        ? EpubBookParser.replaceImageTokens(rc.html, imgUrl) : null;
                chapter.setContentHtml(html);
                chapter.setSortOrder(chapterCount + 1);
                chapter.setSortKey(rc.sortKey);
                int words = rc.text == null ? 0 : rc.text.replaceAll("\\s+", "").length();
                chapter.setWordCount(words);
                totalWords += words;
                chapterRepository.save(chapter);
                chapterCount++;
            }

            int volumeCount = parsed.volumeTitles.size();
            if (volumeCount > 1) {
                for (int i = 0; i < volumeCount; i++) {
                    MajorChapter mc = new MajorChapter();
                    mc.setBookId(book.getId());
                    mc.setTitle(parsed.volumeTitles.get(i));
                    mc.setSortKey(String.valueOf(i + 1));
                    mc.setSortOrder(i + 1);
                    majorChapterRepository.save(mc);
                }
            }

            book.setChapterCount(chapterCount);
            book.setWordCount(totalWords);
            bookRepository.save(book);

            StringBuilder msg = new StringBuilder();
            msg.append("成功导入《").append(bookTitle).append("》共 ").append(chapterCount).append(" 章");
            if (volumeCount > 1) msg.append("，").append(volumeCount).append(" 卷");
            if (!"佚名".equals(bookAuthor)) msg.append("，作者：").append(bookAuthor);
            if (!bookIntro.isEmpty()) msg.append("，已提取简介");
            if (coverUrl != null) msg.append("，已提取封面");
            if (keepStyle) msg.append("，已保留原文样式");
            if (parsed.skippedPages > 0) msg.append("，跳过非正文页 ").append(parsed.skippedPages).append(" 个");
            if (!parsed.warnings.isEmpty()) msg.append("（提示：").append(String.join("；", parsed.warnings)).append("）");

            operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                "导入EPUB书籍：《" + bookTitle + "》（" + chapterCount + "章"
                        + (volumeCount > 1 ? "，" + volumeCount + "卷" : "") + "）",
                book.getId(), getClientIp(request));

            return ApiResponse.success(msg.toString(), null);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error("EPUB导入失败: " + e.getMessage());
        }
    }

    /**
     * 把 EPUB 内嵌封面写入 uploads/covers，返回可访问 URL；失败返回 null。
     */
    private String saveEpubCover(EpubBookParser.ParseResult parsed) {
        if (parsed.coverData == null || parsed.coverData.length == 0) return null;
        try {
            String ext = "jpg";
            String mime = parsed.coverMime == null ? "" : parsed.coverMime;
            if (mime.contains("png")) ext = "png";
            else if (mime.contains("gif")) ext = "gif";
            else if (mime.contains("webp")) ext = "webp";
            File dir = new File(coverPath);
            if (!dir.exists()) dir.mkdirs();
            String fileName = UUID.randomUUID() + "." + ext;
            Files.write(Paths.get(coverPath, fileName), parsed.coverData);
            return "/covers/" + fileName;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 EPUB 内体积较大的图片资源落地到 uploads/books/{bookId}/，
     * 返回 epubimg://id -> 可访问 URL 的映射，供 replaceImageTokens 回填。
     */
    private Map<Integer, String> saveEpubImages(EpubBookParser.ParseResult parsed, Long bookId) {
        Map<Integer, String> urlById = new HashMap<>();
        if (parsed.images == null || parsed.images.isEmpty()) return urlById;
        File dir = new File(booksPath, String.valueOf(bookId));
        if (!dir.exists()) dir.mkdirs();
        for (EpubBookParser.ImageAsset a : parsed.images) {
            if (a == null || a.data == null || a.data.length == 0) continue;
            try {
                String fileName = a.fileName != null && !a.fileName.isEmpty() ? a.fileName : (a.id + ".bin");
                Files.write(Paths.get(dir.getPath(), fileName), a.data);
                urlById.put(a.id, "/books/" + bookId + "/" + fileName);
            } catch (Exception e) {
                // 单张图落地失败不致命：replaceImageTokens 会把该占位符替换为空，阅读器忽略即可
            }
        }
        return urlById;
    }

    // 批量导入章节
    @PostMapping("/chapters")
    @Transactional
    public ApiResponse<String> importChapters(@RequestBody Map<String, Object> request) {
        try {
            Long bookId = Long.valueOf(request.get("bookId").toString());
            List<Map<String, Object>> chapters = (List<Map<String, Object>>) request.get("chapters");

            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("书籍不存在"));

            int count = 0;
            for (Map<String, Object> ch : chapters) {
                Chapter chapter = new Chapter();
                chapter.setBookId(bookId);
                chapter.setTitle((String) ch.get("title"));
                chapter.setContent((String) ch.getOrDefault("content", ""));
                chapter.setSortOrder(count + 1);
                chapter.setSortKey("1-" + String.format("%04d", count + 1));
                chapter.setWordCount(((String) ch.getOrDefault("content", "")).length());
                chapterRepository.save(chapter);
                count++;
            }

            book.setChapterCount(book.getChapterCount() + count);
            // 重新计算书籍总字数（去空格）
            List<Chapter> allChapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
            int totalWordCount = 0;
            for (Chapter c : allChapters) {
                if (c.getContent() != null) {
                    totalWordCount += c.getContent().replaceAll("\\s+", "").length();
                }
            }
            book.setWordCount(totalWordCount);
            bookRepository.save(book);

            return ApiResponse.success("成功导入 " + count + " 个章节", null);
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

}