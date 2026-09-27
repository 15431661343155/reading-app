package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.entity.MajorChapter;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.repository.ChapterRepository;
import com.example.readingapp.repository.MajorChapterRepository;
import com.example.readingapp.service.BookService;
import com.example.readingapp.service.OperationLogService;
import com.example.readingapp.util.BookCategories;
import com.example.readingapp.util.EpubBookParser;
import com.example.readingapp.util.TxtBookParser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final BookService bookService;

    /** 同名书籍的处理策略（见 duplicateStrategy） */
    private static final String DUP_SKIP = "skip";
    private static final String DUP_REPLACE = "replace";
    private static final String DUP_RENAME = "rename";
    /** 不做重复判断，直接导入（可能产生同名副本）——「跳过同名书籍」未勾选时的老行为 */
    private static final String DUP_IMPORT = "import";

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
            // 子分类列必须显式写 "[]"：留 NULL 会被启动时的一次性迁移当成「老数据」重新归类
            book.setSubCategories(BookCategories.toStorage((String) request.get("subCategories")));
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

    /* ==================== 重复导入：预检 + 三种处理策略 ====================
       批量导入时同一本书常常反复导入（换源、补章节、重下）。老做法只有「跳过同名」一个选项，
       用户既看不到哪些是重复的，也无法决定留哪本。现在：
         1) 选完文件先调 /check-duplicates，按书名批量查库 → 前端列出「重复导入清单」
         2) 清单里逐本选 skip（留库里那本）/ replace（用新文件替换）/ rename（两本都留，书名后加备注）
         3) 导入时把策略下发到 /txt|/epub 的 onDuplicate、备注下发到 titleSuffix
       ==================================================================== */

    /** 重复导入预检：批量查这些书名在库里是否已有，返回已有书籍的概况供清单展示 */
    @PostMapping("/check-duplicates")
    public ApiResponse<Map<String, Object>> checkDuplicates(@RequestBody Map<String, Object> request) {
        Object raw = request.get("titles");
        if (!(raw instanceof List)) {
            return ApiResponse.error("titles 必须是书名数组");
        }
        List<String> titles = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (o == null) {
                continue;
            }
            String t = String.valueOf(o).trim();
            if (!t.isEmpty() && !titles.contains(t)) {
                titles.add(t);
            }
        }

        Map<String, List<Map<String, Object>>> existing = new LinkedHashMap<>();
        // IN 子句参数过多会超出数据库限制，分批查
        for (int i = 0; i < titles.size(); i += 200) {
            List<String> chunk = titles.subList(i, Math.min(i + 200, titles.size()));
            for (Book b : bookRepository.findByTitleIn(chunk)) {
                existing.computeIfAbsent(b.getTitle(), k -> new ArrayList<>()).add(bookBrief(b));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checked", titles.size());
        data.put("existing", existing);
        return ApiResponse.success(data);
    }

    private Map<String, Object> bookBrief(Book b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("title", b.getTitle());
        m.put("author", b.getAuthor());
        m.put("chapterCount", b.getChapterCount());
        m.put("wordCount", b.getWordCount());
        m.put("category", b.getCategory());
        m.put("subCategories", BookCategories.parse(b.getSubCategories()));
        m.put("cover", b.getCover());
        m.put("viewCount", b.getViewCount());
        m.put("createdAt", b.getCreatedAt() == null ? null : b.getCreatedAt().toString());
        return m;
    }

    /** 未显式指定策略时，回落到「跳过同名书籍」勾选框（老前端只传 skipExisting） */
    private String duplicateStrategy(String onDuplicate, boolean skipExisting) {
        String s = onDuplicate == null ? "" : onDuplicate.trim().toLowerCase();
        if (DUP_SKIP.equals(s) || DUP_REPLACE.equals(s) || DUP_RENAME.equals(s) || DUP_IMPORT.equals(s)) {
            return s;
        }
        return skipExisting ? DUP_SKIP : DUP_IMPORT;
    }

    /** 备注清洗：会被拼进书名，去掉引号/反斜杠/换行并限长 */
    private String cleanSuffix(String titleSuffix) {
        if (titleSuffix == null) {
            return "";
        }
        String v = titleSuffix.replace("\"", "").replace("\\", "")
                .replaceAll("[\\r\\n\\t]", " ").trim();
        return v.length() > 30 ? v.substring(0, 30).trim() : v;
    }

    /**
     * 「两本都留」时的新书名：{@code 书名（备注）}，备注为空用「重复导入」。
     * 若加完备注仍然重名（例如同一本已加过备注又导一次），自动补序号，绝不静默造出同名书。
     */
    private String titleWithRemark(String title, String suffix) {
        String remark = suffix.isEmpty() ? "重复导入" : suffix;
        String candidate = title + "（" + remark + "）";
        int n = 2;
        while (bookRepository.existsByTitle(candidate) && n <= 99) {
            candidate = title + "（" + remark + " " + n++ + "）";
        }
        return candidate;
    }

    /** 库中同名书籍：取最早导入的一本（同名可能有多本，故不能用 findByTitle） */
    private Book firstExisting(String title) {
        Book oldest = null;
        for (Book b : bookRepository.findByTitleIn(Collections.singletonList(title))) {
            if (oldest == null || (b.getId() != null && oldest.getId() != null && b.getId() < oldest.getId())) {
                oldest = b;
            }
        }
        return oldest;
    }

    // 上传 TXT 自动分章（自动提取编码、书名、作者、简介、分卷）
    @PostMapping("/txt")
    @Transactional
    public ApiResponse<Map<String, Object>> uploadTxt(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "subCategories", required = false) String subCategories,
            @RequestParam(value = "autoExtract", defaultValue = "true") boolean autoExtract,
            @RequestParam(value = "skipExisting", defaultValue = "false") boolean skipExisting,
            @RequestParam(value = "onDuplicate", required = false) String onDuplicate,
            @RequestParam(value = "titleSuffix", required = false) String titleSuffix,
            HttpServletRequest request) {
        try {
            byte[] data = file.getBytes();
            if (data.length == 0) {
                return ApiResponse.error("文件内容为空");
            }

            // 前端按文件名猜的作者可能是下载序号（如「我的属性修行人生(1).txt」的 1），
            // 不像人名的提示值直接忽略，否则会以「手动输入」优先级覆盖正文标注里的正确作者
            String authorHint = TxtBookParser.isPlausibleAuthor(author) ? author.trim() : null;

            // 未勾选「自动提取」时，不使用文件名与正文推断，只认手动填写的值
            TxtBookParser.ParseResult parsed = TxtBookParser.parse(
                    data, autoExtract ? file.getOriginalFilename() : null, title, authorHint);

            if (parsed.title == null || parsed.title.trim().isEmpty()) {
                return ApiResponse.error("无法从文件名或正文中提取书名，请手动输入");
            }
            if (parsed.chapterCount() == 0) {
                return ApiResponse.error("未识别到任何章节，请检查文件是否为标准 TXT 小说"
                        + "（章节标题需以「第X章」等形式独占一行）");
            }

            // 重复导入：按「重复导入清单」下发的策略处理同名书（skip / replace / rename / import）
            String strategy = duplicateStrategy(onDuplicate, skipExisting);
            String finalTitle = parsed.title.trim();
            Book existing = firstExisting(finalTitle);
            Long replacedId = null;
            if (existing != null) {
                if (DUP_SKIP.equals(strategy)) {
                    return ApiResponse.success("已跳过《" + finalTitle + "》：书库中已存在同名书籍",
                            importResult(finalTitle, null, parsed.chapterCount(), true, DUP_SKIP, null));
                }
                if (DUP_REPLACE.equals(strategy)) {
                    // 用新文件替换旧书：旧书连同章节 / 大章节 / 读者书架与阅读进度一起删除
                    bookService.batchDelete(Collections.singletonList(existing.getId()));
                    replacedId = existing.getId();
                } else if (DUP_RENAME.equals(strategy)) {
                    finalTitle = titleWithRemark(finalTitle, cleanSuffix(titleSuffix));
                }
            }

            Book book = new Book();
            book.setTitle(finalTitle);
            book.setAuthor(parsed.author == null || parsed.author.trim().isEmpty()
                    ? "佚名" : parsed.author.trim());
            book.setCategory(category != null ? category.trim() : "");
            // 子分类多选：统一规范化入库（未传 → "[]"）
            book.setSubCategories(BookCategories.toStorage(subCategories));
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
            msg.append("成功导入《").append(finalTitle).append("》共 ")
                    .append(parsed.chapterCount()).append(" 章");
            if (replacedId != null) {
                msg.append("（已替换同名旧书 #").append(replacedId).append("）");
            }
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
                    "导入TXT书籍：《" + finalTitle + "》（" + parsed.chapterCount() + "章）",
                    book.getId(), getClientIp(request));

            return ApiResponse.success(msg.toString(),
                    importResult(finalTitle, book.getId(), parsed.chapterCount(), false, strategy, replacedId));
        } catch (Exception e) {
            e.printStackTrace();
            markRollbackOnly();
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    /**
     * 导入结果的结构化返回体：批量导入靠它统计成功/跳过并回填结果列表。
     *
     * @param onDuplicate    实际生效的同名处理策略（skip / replace / rename / import）
     * @param replacedBookId 替换旧书时被删掉的旧书 id，其余情况为 null
     */
    private Map<String, Object> importResult(String title, Long bookId, int chapterCount, boolean skipped,
                                             String onDuplicate, Long replacedBookId) {
        Map<String, Object> result = new HashMap<>();
        result.put("title", title);
        result.put("bookId", bookId);
        result.put("chapterCount", chapterCount);
        result.put("skipped", skipped);
        result.put("onDuplicate", onDuplicate);
        if (replacedBookId != null) {
            result.put("replacedBookId", replacedBookId);
        }
        return result;
    }

    /**
     * 把当前事务标记为回滚。
     * 导入逻辑用 try/catch 捕获异常后返回错误消息，异常被吞掉会导致事务提交，
     * 从而在库中留下「有书没章节」的半截数据；批量导入场景下必须避免。
     */
    private void markRollbackOnly() {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
        } catch (Exception ignored) {
            // 无事务上下文时不处理
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
    public ApiResponse<Map<String, Object>> uploadEpub(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "subCategories", required = false) String subCategories,
            @RequestParam(value = "keepStyle", defaultValue = "true") boolean keepStyle,
            @RequestParam(value = "skipExisting", defaultValue = "false") boolean skipExisting,
            @RequestParam(value = "onDuplicate", required = false) String onDuplicate,
            @RequestParam(value = "titleSuffix", required = false) String titleSuffix,
            HttpServletRequest request) {
        try {
            byte[] data = file.getBytes();
            // 同 TXT：不像人名的作者提示（下载序号等）一律忽略，让 EPUB 元数据的作者生效
            String epubAuthorHint = TxtBookParser.isPlausibleAuthor(author) ? author.trim() : null;
            EpubBookParser.ParseResult parsed = EpubBookParser.parse(
                    data, file.getOriginalFilename(), title, epubAuthorHint);

            String bookTitle = parsed.title == null || parsed.title.trim().isEmpty()
                    ? "未命名书籍" : parsed.title.trim();
            String bookAuthor = parsed.author == null || parsed.author.trim().isEmpty()
                    ? "佚名" : parsed.author.trim();
            String bookIntro = parsed.intro == null ? "" : parsed.intro.trim();

            // 重复导入：与 TXT 同一套策略（skip / replace / rename / import）
            String strategy = duplicateStrategy(onDuplicate, skipExisting);
            Long replacedId = null;
            if (!"未命名书籍".equals(bookTitle)) {
                Book existing = firstExisting(bookTitle);
                if (existing != null) {
                    if (DUP_SKIP.equals(strategy)) {
                        return ApiResponse.success("已跳过《" + bookTitle + "》：书库中已存在同名书籍",
                                importResult(bookTitle, null, parsed.chapters.size(), true, DUP_SKIP, null));
                    }
                    if (DUP_REPLACE.equals(strategy)) {
                        bookService.batchDelete(Collections.singletonList(existing.getId()));
                        replacedId = existing.getId();
                    } else if (DUP_RENAME.equals(strategy)) {
                        bookTitle = titleWithRemark(bookTitle, cleanSuffix(titleSuffix));
                    }
                }
            }

            Book book = new Book();
            book.setTitle(bookTitle);
            book.setAuthor(bookAuthor);
            book.setCategory(category != null ? category.trim() : "");
            // 子分类多选：统一规范化入库（未传 → "[]"）
            book.setSubCategories(BookCategories.toStorage(subCategories));
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
            if (replacedId != null) msg.append("（已替换同名旧书 #").append(replacedId).append("）");
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

            return ApiResponse.success(msg.toString(),
                    importResult(bookTitle, book.getId(), chapterCount, false, strategy, replacedId));
        } catch (Exception e) {
            e.printStackTrace();
            markRollbackOnly();
            return ApiResponse.error("EPUB导入失败: " + e.getMessage());
        }
    }

    /**
     * EPUB 批量「解析预检」：只解析、不落库，用来在真导入前核对每本书的书名/作者。
     *
     * <p>动机：合集类目录里文件名千奇百怪，epub 元数据（dc:creator）覆盖率又低
     * （实测某 2352 本合集仅 22.5% 有作者），一旦批量导入几百本，事后逐本改作者
     * 成本极高。此接口把 {@link EpubBookParser#parse} 的结果摊成表，让用户先看一眼
     * 「哪些书名是文件名兜出来的、哪些作者会变佚名、哪些和库中已有书重名」。
     *
     * <p>与 {@link #uploadEpub} 的解析口径完全一致（同一个 parse 调用、同样的
     * duplicateStrategy），差别只是不写库 —— 预检看到的书名/作者就是导入后会入库的值。
     */
    @PostMapping("/epub-parse-batch")
    public ApiResponse<Map<String, Object>> parseEpubBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "onDuplicate", required = false) String onDuplicate,
            @RequestParam(value = "skipExisting", defaultValue = "false") boolean skipExisting) {
        try {
            if (files == null || files.isEmpty()) {
                return ApiResponse.error("没有收到任何文件");
            }
            String strategy = duplicateStrategy(onDuplicate, skipExisting);

            List<Map<String, Object>> rows = new ArrayList<>(files.size());
            int okCount = 0, failed = 0, anonymous = 0, dupInLib = 0, titleFromFile = 0;
            for (MultipartFile f : files) {
                String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fileName", name);
                try {
                    if (f.isEmpty()) {
                        failed++;
                        row.put("ok", false);
                        row.put("error", "空文件");
                        rows.add(row);
                        continue;
                    }
                    EpubBookParser.ParseResult parsed = EpubBookParser.parse(
                            f.getBytes(), name, null, null);
                    String bookTitle = parsed.title == null || parsed.title.trim().isEmpty()
                            ? "未命名书籍" : parsed.title.trim();
                    String bookAuthor = parsed.author == null || parsed.author.trim().isEmpty()
                            ? "佚名" : parsed.author.trim();
                    // 书名/作者是否来自文件名兜底：元数据为空即为兜底 —— 供前端标黄提醒核对
                    boolean titleFromName = parsed.title == null || parsed.title.trim().isEmpty();
                    boolean authorFromName = "佚名".equals(bookAuthor);
                    Book dup = "未命名书籍".equals(bookTitle) ? null : firstExisting(bookTitle);
                    if (authorFromName) anonymous++;
                    if (titleFromName) titleFromFile++;
                    if (dup != null) dupInLib++;

                    row.put("ok", true);
                    row.put("title", bookTitle);
                    row.put("author", bookAuthor);
                    row.put("chapterCount", parsed.chapters == null ? 0 : parsed.chapters.size());
                    row.put("volumeCount", parsed.volumeTitles == null ? 0 : parsed.volumeTitles.size());
                    row.put("hasCover", parsed.coverData != null && parsed.coverData.length > 0);
                    row.put("titleFromFileName", titleFromName);
                    row.put("authorFromFileName", authorFromName);
                    row.put("existingBookId", dup == null ? null : dup.getId());
                    row.put("duplicateAction", dup == null ? "" : strategy);
                    if (parsed.warnings != null && !parsed.warnings.isEmpty()) {
                        row.put("warnings", new ArrayList<>(parsed.warnings));
                    }
                    okCount++;
                } catch (Exception e) {
                    failed++;
                    row.put("ok", false);
                    row.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                rows.add(row);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", files.size());
            data.put("okCount", okCount);
            data.put("failedCount", failed);
            // 作者落成「佚名」的本书：最需要人工过目的一类
            data.put("anonymousAuthorCount", anonymous);
            // 书名靠文件名兜出来的本数（元数据没 dc:title）
            data.put("titleFromFileNameCount", titleFromFile);
            // 与库中已有书名重复的本数（按传入策略处理）
            data.put("duplicateInLibraryCount", dupInLib);
            data.put("strategy", strategy);
            data.put("rows", rows);

            StringBuilder msg = new StringBuilder("已解析 ").append(files.size()).append(" 个 EPUB");
            msg.append("，成功 ").append(okCount);
            if (failed > 0) msg.append("，失败 ").append(failed);
            if (anonymous > 0) msg.append("；其中 ").append(anonymous).append(" 本未能识别作者（将入库为「佚名」）");
            if (dupInLib > 0) msg.append("；").append(dupInLib).append(" 本与库中已有书名重复");
            return ApiResponse.success(msg.toString(), data);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error("预检失败: " + e.getMessage());
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
    public ApiResponse<Map<String, Object>> importChapters(@RequestBody Map<String, Object> request,
                                                           HttpServletRequest httpRequest) {
        try {
            Long bookId = Long.valueOf(String.valueOf(request.get("bookId")));
            List<Map<String, Object>> incoming = (List<Map<String, Object>>) request.get("chapters");
            if (incoming == null || incoming.isEmpty()) {
                return ApiResponse.error("没有可导入的章节");
            }
            // 缺省「同名覆盖更新」：补章场景（导漏掉的章）最常用，也符合旧前端「按序号补章」的语义
            String onDuplicate = duplicateStrategy((String) request.get("onDuplicate"), false);
            boolean fillGaps = !Boolean.FALSE.equals(request.get("fillGaps"));

            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("书籍不存在"));

            // 现有章节：sortKey 与「规范化标题」两套索引，命中即视为同一章
            List<Chapter> existing = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
            Map<String, Chapter> bySortKey = new LinkedHashMap<>();
            Map<String, Chapter> byTitle = new LinkedHashMap<>();
            for (Chapter c : existing) {
                if (c.getSortKey() != null && !c.getSortKey().isEmpty()) {
                    bySortKey.putIfAbsent(c.getSortKey(), c);
                }
                String tk = chapterTitleKey(c.getTitle());
                if (!tk.isEmpty()) {
                    byTitle.putIfAbsent(tk, c);
                }
            }

            int updated = 0, added = 0, skipped = 0;
            int series = deriveSeries(bookId, existing);
            int nextMinor = maxMinor(existing, series);
            // 新增章节的落位序号：从现有最大值起算，绝不与已有章节撞号
            int nextOrder = 0;
            for (Chapter c : existing) {
                if (c.getSortOrder() != null && c.getSortOrder() > nextOrder) {
                    nextOrder = c.getSortOrder();
                }
            }

            for (Map<String, Object> ch : incoming) {
                String title = ch.get("title") == null ? "" : String.valueOf(ch.get("title")).trim();
                if (title.isEmpty()) {
                    title = "未命名章节";
                }
                String content = ch.get("content") == null ? "" : String.valueOf(ch.get("content"));
                String key = chapterTitleKey(title);

                // 标题统一清洗，避免同一章因尾部广告词/标点不同而重复入库
                String cleaned = TxtBookParser.cleanChapterTitle(title);
                if (!cleaned.isEmpty()) {
                    title = cleaned;
                }

                Chapter hit = null;
                String scKey = ch.get("sortKey") == null ? "" : String.valueOf(ch.get("sortKey")).trim();
                if (!scKey.isEmpty()) {
                    hit = bySortKey.get(scKey);
                }
                if (hit == null && !key.isEmpty()) {
                    hit = byTitle.get(key);
                }

                if (hit != null) {
                    if (DUP_SKIP.equals(onDuplicate)) {
                        skipped++;
                        continue;
                    }
                    // 覆盖更新（默认）：正文连同标题一起刷新，序号/排序键保持原样不破坏分卷结构
                    hit.setTitle(title);
                    hit.setContent(content);
                    hit.setWordCount(countWords(content));
                    chapterRepository.save(hit);
                    updated++;
                    continue;
                }

                // 新增：sortKey 优先沿用调用方给的（保留原书分卷结构），否则接续本书已有编号
                String newSortKey = scKey.isEmpty()
                        ? series + "-" + String.format("%04d", ++nextMinor)
                        : scKey;
                Chapter chapter = new Chapter();
                chapter.setBookId(bookId);
                chapter.setTitle(title);
                chapter.setContent(content);
                chapter.setSortOrder(++nextOrder);
                chapter.setSortKey(newSortKey);
                chapter.setWordCount(countWords(content));
                chapterRepository.save(chapter);
                bySortKey.put(newSortKey, chapter);
                if (!key.isEmpty()) {
                    byTitle.put(key, chapter);
                }
                added++;
            }

            // 章节数 / 总字数按库里实际数据重算，避免「旧值 + 增量」越滚越偏
            List<Chapter> all = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
            book.setChapterCount(all.size());
            int totalWordCount = 0;
            for (Chapter c : all) {
                totalWordCount += countWords(c.getContent());
            }
            book.setWordCount(totalWordCount);
            bookRepository.save(book);

            // 缺章补位：有新增章节时按内部编号重排一次，让 sortOrder/sortKey 连续
            boolean renumbered = false;
            if (fillGaps && added > 0) {
                renumbered = renumberContiguous(bookId);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("updated", updated);
            data.put("added", added);
            data.put("skipped", skipped);
            data.put("total", all.size());
            data.put("renumbered", renumbered);
            data.put("onDuplicate", onDuplicate);

            StringBuilder msg = new StringBuilder("导入完成：新增 ").append(added).append(" 章");
            if (updated > 0) {
                msg.append("，覆盖更新 ").append(updated).append(" 章");
            }
            if (skipped > 0) {
                msg.append("，跳过已存在 ").append(skipped).append(" 章");
            }
            if (fillGaps && added == 0 && !renumbered) {
                msg.append("（无缺章可补）");
            }
            msg.append("，本书现有 ").append(all.size()).append(" 章");

            operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                    "章节导入：" + msg, bookId, getClientIp(httpRequest));
            return ApiResponse.success(msg.toString(), data);
        } catch (Exception e) {
            e.printStackTrace();
            markRollbackOnly();
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    /** 正文字数口径与其余导入入口保持一致：去空白后计长度 */
    private int countWords(String content) {
        return content == null ? 0 : content.replaceAll("\\s+", "").length();
    }

    /** 标题匹配键：去空白与标点后小写，「第一章 楔子」与「第一章 楔子！」视为同一章 */
    private String chapterTitleKey(String title) {
        if (title == null) {
            return "";
        }
        return title.replaceAll("\\s+", "")
                .replaceAll("[，,。.：:；;！!？?、·\\-—_－【】\\[\\]（）()《》\"'“”‘’]", "")
                .toLowerCase();
    }

    /**
     * 本书章节目录所用的卷号：取现有章节 sortKey 中出现最多的 "X-" 前缀。
     * 空库 → 1；导入过的书几乎都是 "1-"，取众数能避开个别脏数据。
     */
    private int deriveSeries(Long bookId, List<Chapter> existing) {
        Map<String, Integer> freq = new HashMap<>();
        for (Chapter c : existing) {
            String sk = c.getSortKey();
            if (sk == null || !sk.contains("-")) {
                continue;
            }
            String head = sk.substring(0, sk.indexOf('-')).trim();
            if (head.matches("\\d{1,4}")) {
                freq.merge(head, 1, Integer::sum);
            }
        }
        int best = 1, bestCount = 0;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                best = Integer.parseInt(e.getKey());
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /** 该卷号下已用掉的最大章内序号（sortKey 的第二段数字），新章节从这里往后接 */
    private int maxMinor(List<Chapter> existing, int series) {
        int max = 0;
        String prefix = series + "-";
        for (Chapter c : existing) {
            String sk = c.getSortKey();
            if (sk == null || !sk.startsWith(prefix)) {
                continue;
            }
            String tail = sk.substring(prefix.length());
            if (tail.matches("\\d{1,6}")) {
                max = Math.max(max, Integer.parseInt(tail));
            }
        }
        return max;
    }

    /**
     * 按 (卷号, 章内序号) 重排本卷的 sortOrder 与 sortKey，使 1..N 连续，补上缺章留下的空位。
     *
     * <p>约束：书内所有章节的 sortKey 必须能解析出「卷号-序号」才动手；有任何一章不合法就整体放弃
     * （返回 false，保持原状），避免把分卷结构或外部导入的自定义键改坏。
     * 只重写 sortOrder / sortKey，不动标题与正文，也不影响读者书架（书架只记章节 id）。
     */
    private boolean renumberContiguous(Long bookId) {
        List<Chapter> all = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        if (all.isEmpty()) {
            return false;
        }

        // 按卷分组，卷号/序号必须全部解析成功
        Map<Integer, List<Chapter>> bySeries = new TreeMap<>();
        for (Chapter c : all) {
            String sk = c.getSortKey();
            if (sk == null || sk.isEmpty()) {
                return false;
            }
            int dash = sk.indexOf('-');
            if (dash <= 0 || dash == sk.length() - 1) {
                return false;
            }
            String head = sk.substring(0, dash).trim();
            String tail = sk.substring(dash + 1).trim();
            if (!head.matches("\\d{1,4}") || !tail.matches("\\d{1,6}")) {
                return false;
            }
            bySeries.computeIfAbsent(Integer.parseInt(head), k -> new ArrayList<>()).add(c);
        }

        List<Chapter> touched = new ArrayList<>();
        int order = 0;
        boolean changed = false;
        for (Map.Entry<Integer, List<Chapter>> e : bySeries.entrySet()) {
            int series = e.getKey();
            List<Chapter> list = e.getValue();
            // 同卷内按原 sortKey 的第二段排，保证顺序稳定
            list.sort(Comparator.comparingInt(c -> {
                String sk = c.getSortKey();
                return Integer.parseInt(sk.substring(sk.indexOf('-') + 1).trim());
            }));
            int minor = 0;
            for (Chapter c : list) {
                minor++;
                order++;
                String newKey = series + "-" + String.format("%04d", minor);
                if (!newKey.equals(c.getSortKey()) || !Integer.valueOf(order).equals(c.getSortOrder())) {
                    changed = true;
                }
                c.setSortOrder(order);
                c.setSortKey(newKey);
                touched.add(c);
            }
        }
        if (!changed) {
            return false;
        }
        chapterRepository.saveAll(touched);
        return true;
    }

    /**
     * 把「一个文件夹里的多个 TXT 分片」合并解析成一份章节清单（不落库，只回给前端预览/确认）。
     *
     * <p>典型场景：整本书被下载器按章节区间切成若干个文件
     * （{@code 书名_正文(第1章-20章).txt} … {@code 书名_正文(第541章-560章).txt}），
     * 想一次性把它们全部导入到同一本书里。前端用 {@code webkitdirectory} 选目录后
     * 把每个分片拼成 multipart，这里逐片解析再按「卷号 + 章内序号」合并去重。
     *
     * <p>只读接口，不修改任何数据；真正的写库由 {@link #importChaptersFromFiles} 完成，
     * 好处是用户能在预览里先确认「这批文件一共多少章」再决定导入。
     */
    @PostMapping("/parse-txt-batch")
    public ApiResponse<Map<String, Object>> parseTxtBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "bookId", required = false) Long bookId) {
        return parseTxtBatchInternal(files, bookId, false);
    }

    /**
     * 从「一个文件夹里的多个 TXT 分片」直接导入章节到指定书籍（解析 + 落库一步到位）。
     *
     * <p>与 {@link #parseTxtBatch} 的解析口径完全一致，差别只在最后多走一次
     * {@link #importChapters} 的合并逻辑：同名覆盖 / 跳过、编号接续、缺章补位。
     * 拆成两个接口是为了让前端能先「解析预览」让用户确认章数，再决定是否落库。
     */
    @PostMapping("/chapters-from-files")
    @Transactional
    public ApiResponse<Map<String, Object>> importChaptersFromFiles(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("bookId") Long bookId,
            @RequestParam(value = "onDuplicate", required = false) String onDuplicate,
            @RequestParam(value = "fillGaps", defaultValue = "true") boolean fillGaps,
            HttpServletRequest request) {
        try {
            // 复用解析接口拿到合并后的章节清单（含正文），解析失败会直接返回错误
            ApiResponse<Map<String, Object>> parsed = parseTxtBatchInternal(files, bookId, true);
            if (!parsed.isSuccess()) {
                return ApiResponse.error(parsed.getMessage());
            }
            List<Map<String, Object>> chapters =
                    (List<Map<String, Object>>) parsed.getData().get("chapters");
            if (chapters == null || chapters.isEmpty()) {
                return ApiResponse.error("没有从这些 TXT 里识别到任何章节");
            }

            // 转交给统一的导入逻辑，保证与粘贴导入走同一条合并/补位路径
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("bookId", bookId);
            req.put("chapters", chapters);
            req.put("onDuplicate", onDuplicate);
            req.put("fillGaps", fillGaps);
            ApiResponse<Map<String, Object>> result = importChapters(req, request);

            if (result.isSuccess() && result.getData() != null) {
                Map<String, Object> pdata = parsed.getData();
                result.getData().put("parsedChapterCount", chapters.size());
                result.getData().put("fileCount", files.size());
                // I1：把解析阶段逐文件明细透传前端，便于导入后看清哪些源文件没进库
                result.getData().put("failedFiles", pdata.get("failedFiles"));
                result.getData().put("fileInfos", pdata.get("files"));
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            markRollbackOnly();
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    /** 解析入口的共用壳：{@code fullChapters} = true 时额外回全量章节（含正文），供落库使用 */
    private ApiResponse<Map<String, Object>> parseTxtBatchInternal(
            List<MultipartFile> files, Long bookId, boolean fullChapters) {
        try {
            if (files == null || files.isEmpty()) {
                return ApiResponse.error("没有收到任何文件");
            }

            // 分片按「文件名里的起始章号」升序处理，保证 sortKey 的卷内序号递增
            List<MultipartFile> sorted = new ArrayList<>(files);
            sorted.sort(Comparator
                    .comparingInt((MultipartFile f) -> startChapterOf(f.getOriginalFilename()))
                    .thenComparing(f -> String.valueOf(f.getOriginalFilename())));

            // 合并结果：key = 章节的「真实章号」（从标题里解析出的 N），保证跨分片不重不漏。
            // 不能用 sortKey 当 key —— 每个分片各自从 1 开始编 minor，38 个分片会得到 38 组
            // 1-0001..1-0020 互相覆盖，实测把 760 章塌成 20 章。
            Map<Integer, TxtBookParser.RawChapter> merged = new TreeMap<>();
            // 无编号章节（序章/番外/作品相关等）单独排队，最后按出现顺序追加
            List<TxtBookParser.RawChapter> unnumbered = new ArrayList<>();
            List<String> volumeTitles = new ArrayList<>();
            List<String> fileInfos = new ArrayList<>();
            int duplicateChapters = 0, failed = 0;
            Set<String> encodings = new LinkedHashSet<>();

            for (MultipartFile f : sorted) {
                String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
                if (f.isEmpty()) {
                    failed++;
                    fileInfos.add(name + "｜空文件，已忽略");
                    continue;
                }
                try {
                    // 不传 fileName / hintTitle：分片文件名（书名_正文(第X章-Y章).txt）不是书名，
                    // 交给我们自己的 stripChapterRange 裁掉区间后缀后再当文件名用
                    TxtBookParser.ParseResult pr = TxtBookParser.parse(
                            f.getBytes(), stripChapterRange(name), null, null);
                    int before = merged.size() + unnumbered.size();
                    for (TxtBookParser.RawChapter rc : pr.chapters) {
                        if (rc.number > 0) {
                            TxtBookParser.RawChapter prev = merged.put(rc.number, rc);
                            if (prev != null) {
                                // 同一章号在多个分片里重复出现：取正文更长的那份（更完整）
                                if (prev.contentText().length() >= rc.contentText().length()) {
                                    merged.put(rc.number, prev);
                                }
                                duplicateChapters++;
                            }
                        } else if (rc.note) {
                            // 作者单章/公告：按标题去重后追加，不占章号
                            boolean dupNote = false;
                            for (TxtBookParser.RawChapter u : unnumbered) {
                                if (u.title != null && u.title.equals(rc.title)) {
                                    dupNote = true;
                                    break;
                                }
                            }
                            if (dupNote) {
                                duplicateChapters++;
                            } else {
                                unnumbered.add(rc);
                            }
                        } else {
                            unnumbered.add(rc);
                        }
                    }
                    for (int i = 0; i < pr.volumeTitles.size(); i++) {
                        while (volumeTitles.size() <= i) {
                            volumeTitles.add("第" + (volumeTitles.size() + 1) + "卷");
                        }
                        String vt = pr.volumeTitles.get(i);
                        if (vt != null && !vt.isEmpty()) {
                            // 分片各自从第1卷算起，后到的片若给出了更具体的卷名就补上
                            String cur = volumeTitles.get(i);
                            if (cur.startsWith("第") && cur.endsWith("卷")) {
                                volumeTitles.set(i, vt);
                            }
                        }
                    }
                    if (!pr.encoding.isEmpty()) {
                        encodings.add(pr.encoding);
                    }
                    int got = merged.size() + unnumbered.size() - before;
                    fileInfos.add(name + "｜" + got + " 章"
                            + (pr.mergedChapters > 0 ? "（合并重复 " + pr.mergedChapters + " 处）" : "")
                            + "｜" + pr.encoding);
                } catch (Exception e) {
                    failed++;
                    fileInfos.add(name + "｜解析失败：" + e.getMessage());
                }
            }

            if (merged.isEmpty() && unnumbered.isEmpty()) {
                return ApiResponse.error("没有从这些 TXT 里识别到任何章节，请确认文件是否为标准 TXT 小说"
                        + "（章节标题需以「第X章」等形式独占一行）");
            }

            // 有序的最终章节清单：无编号章节（序章/作品相关/站点 title 行等）排最前，
            // 其后是按章号升序的编号章节。
            // 去重：无编号条目若与某编号章节标题相同，视为同一章（分片头部的 <title> 行常导致），丢掉它。
            Set<String> numberedTitleKeys = new HashSet<>();
            for (TxtBookParser.RawChapter rc : merged.values()) {
                numberedTitleKeys.add(chapterTitleKey(rc.title));
            }
            List<TxtBookParser.RawChapter> head = new ArrayList<>();
            Set<String> headKeys = new HashSet<>();
            int droppedNodes = 0;
            for (TxtBookParser.RawChapter rc : unnumbered) {
                String k = chapterTitleKey(rc.title);
                if (!k.isEmpty() && (numberedTitleKeys.contains(k) || !headKeys.add(k))) {
                    droppedNodes++;
                    continue;
                }
                head.add(rc);
            }

            List<TxtBookParser.RawChapter> ordered = new ArrayList<>(head.size() + merged.size());
            ordered.addAll(head);
            ordered.addAll(merged.values());

            // 重新编号 sortKey，让跨分片合并后的顺序在库里也是连续的
            int minor = 0;
            for (TxtBookParser.RawChapter rc : ordered) {
                rc.sortKey = "1-" + String.format("%04d", ++minor);
            }

            // 只回前 300 章做预览，避免上千章时响应体过大
            int limit = Math.min(ordered.size(), 300);
            List<Map<String, Object>> preview = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                preview.add(chapterRow(ordered.get(i), false));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("chapterCount", ordered.size());
            data.put("numberedCount", merged.size());
            data.put("unnumberedCount", head.size());
            data.put("droppedDuplicateCount", droppedNodes);
            data.put("fileCount", files.size());
            data.put("failedFiles", failed);
            data.put("duplicateChapters", duplicateChapters);
            data.put("encodings", new ArrayList<>(encodings));
            data.put("volumeTitles", volumeTitles);
            data.put("volumeCount", volumeTitles.size());
            data.put("preview", preview);
            data.put("truncated", ordered.size() > limit);
            data.put("files", fileInfos);
            if (!merged.isEmpty()) {
                data.put("firstChapterNumber", firstKeyOf(merged));
                data.put("lastChapterNumber", lastKeyOf(merged));
            }
            if (bookId != null) {
                data.put("existingChapterCount", chapterRepository.countByBookId(bookId));
            }
            if (fullChapters) {
                List<Map<String, Object>> all = new ArrayList<>(ordered.size());
                for (TxtBookParser.RawChapter rc : ordered) {
                    all.add(chapterRow(rc, true));
                }
                data.put("chapters", all);
            }

            StringBuilder msg = new StringBuilder("已解析 ").append(files.size()).append(" 个文件，共 ")
                    .append(ordered.size()).append(" 章");
            if (duplicateChapters > 0) {
                msg.append("（跨分片重复合并 ").append(duplicateChapters).append(" 章）");
            }
            if (failed > 0) {
                msg.append("，").append(failed).append(" 个文件解析失败");
            }
            return ApiResponse.success(msg.toString(), data);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error("解析失败: " + e.getMessage());
        }
    }

    /** 有序章节表的最小章号；空表返回 0 */
    private int firstKeyOf(Map<Integer, TxtBookParser.RawChapter> merged) {
        int min = Integer.MAX_VALUE;
        for (Integer k : merged.keySet()) {
            min = Math.min(min, k);
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** 有序章节表的最大章号；空表返回 0 */
    private int lastKeyOf(Map<Integer, TxtBookParser.RawChapter> merged) {
        int max = 0;
        for (Integer k : merged.keySet()) {
            max = Math.max(max, k);
        }
        return max;
    }

    /** 解析结果 → 统一的行结构（{@code withContent} 决定是否带正文，预览不带以免响应过大） */
    private Map<String, Object> chapterRow(TxtBookParser.RawChapter rc, boolean withContent) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", rc.title);
        row.put("sortKey", rc.sortKey);
        row.put("number", rc.number);
        row.put("wordCount", rc.contentText().replaceAll("\\s+", "").length());
        if (withContent) {
            row.put("content", rc.contentText());
        }
        return row;
    }

    /** 读取分片文件名里的「起始章号」，用于排序；取不到返回 0（排最前） */
    private int startChapterOf(String fileName) {
        if (fileName == null) {
            return 0;
        }
        Matcher m = Pattern.compile("第\\s*([0-9〇零一二三四五六七八九十百千万亿两廿]{1,12})\\s*[章节回]")
                .matcher(fileName);
        int best = 0;
        while (m.find()) {
            int n = TxtBookParser.chineseToInt(m.group(1));
            if (n > 0 && (best == 0 || n < best)) {
                best = n;
            }
        }
        if (best > 0) {
            return best;
        }
        // 退而求其次：文件名里最后一段连续数字（书名_005.txt）
        Matcher md = Pattern.compile("(?<![0-9])([0-9]{1,6})(?![0-9])").matcher(fileName);
        int last = 0;
        while (md.find()) {
            last = Integer.parseInt(md.group(1));
        }
        return last;
    }

    /**
     * 去掉分片文件名里的「正文(第X章-Y章)」「(1-20)」等区间后缀，剩下的才是书名候选。
     * 例：{@code 成人美漫：开局社保黑丝旺达_正文(第541章-560章).txt}
     * → {@code 成人美漫：开局社保黑丝旺达}
     */
    private String stripChapterRange(String fileName) {
        if (fileName == null) {
            return null;
        }
        String s = fileName;
        // 括号内带章节区间的整段（全角/半角括号都吃）
        s = s.replaceAll("[（(【\\[]\\s*(?:正文\\s*)?第\\s*[0-9〇零一二三四五六七八九十百千万亿两廿]{1,12}\\s*[章节回]"
                + "\\s*[-—~～至]\\s*(?:第\\s*[0-9〇零一二三四五六七八九十百千万亿两廿]{1,12}\\s*[章节回])?"
                + "\\s*[）)】\\]]", "");
        // 括号内只有纯数字区间：(1-20) / （541-560）
        s = s.replaceAll("[（(【\\[]\\s*[0-9]{1,6}\\s*[-—~～至]\\s*[0-9]{1,6}\\s*[）)】\\]]", "");
        // 落单的「_正文」「-正文」尾巴
        s = s.replaceAll("[_\\-—\\s]*正文\\s*$", "");
        s = s.replaceAll("[\\s_\\-—]+$", "").trim();
        return s.isEmpty() ? fileName : s;
    }

}