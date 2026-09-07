package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.BookSource;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.legado.LegadoBookSourceService;
import com.example.readingapp.legado.LegadoSourceAdapter;
import com.example.readingapp.legado.analyze.AnalyzeByJSonPath;
import com.example.readingapp.legado.analyze.AnalyzeRule;
import com.example.readingapp.legado.analyze.AnalyzeUrl;
import com.example.readingapp.legado.js.LegadoJsEngine;
import com.example.readingapp.legado.model.LegadoBookSource;
import com.example.readingapp.legado.model.BookInfoRule;
import com.example.readingapp.legado.model.ContentRule;
import com.example.readingapp.legado.model.SearchRule;
import com.example.readingapp.legado.model.TocRule;
import com.example.readingapp.service.BookSourceFactory;
import com.example.readingapp.service.OnlineBookSourceService;
import com.example.readingapp.repository.BookSourceRepository;
import com.example.readingapp.util.HttpFetcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/admin/online-source")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminOnlineSourceController {

    private final BookSourceFactory bookSourceFactory;
    private final BookSourceRepository bookSourceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/sources")
    public ApiResponse<List<BookSourceFactory.SourceInfo>> getSources() {
        try {
            List<BookSourceFactory.SourceInfo> sources = bookSourceFactory.getAvailableSources();
            return ApiResponse.success(sources);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/search")
    public ApiResponse<List<Book>> searchBooks(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "GUTENBERG") String sourceType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<Book> books;
            if ("all".equalsIgnoreCase(sourceType)) {
                books = searchAllSources(keyword, size);
            } else {
                OnlineBookSourceService service = bookSourceFactory.getService(sourceType);
                if (service == null) {
                    return ApiResponse.error("不支持的书源类型: " + sourceType);
                }
                books = service.searchBooks(keyword, sourceType, page, size);
                // 单书源路径：后端再做一次 keyword 二次过滤，防止第三方书源返回默认榜单
                books = filterByKeyword(books, keyword);
            }
            return ApiResponse.success(books);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 搜索结果关键词二次过滤：
     *  - 书名或作者包含 keyword（忽略大小写、忽略空白与全角半角标点差异）
     *  - 对纯数字短关键词（长度 ≤ 2），不参与 category 匹配（防止书源的编号 tag 如 0_1 被误匹配）
     */
    private static List<Book> filterByKeyword(List<Book> books, String keyword) {
        if (books == null || books.isEmpty()) return books;
        if (keyword == null || keyword.trim().isEmpty()) return books;
        String normKw = normalizeForMatch(keyword);
        if (normKw.isEmpty()) return books;
        // 纯数字短关键词：不匹配 category（避免 0_1、1_2 这种编号 tag 误命中）
        boolean shortNumeric = normKw.matches("\\d+") && normKw.length() <= 2;
        List<Book> filtered = new ArrayList<>();
        for (Book b : books) {
            String title = b.getTitle() == null ? "" : b.getTitle();
            String author = b.getAuthor() == null ? "" : b.getAuthor();
            String intro = b.getIntro() == null ? "" : b.getIntro();
            String category = b.getCategory() == null ? "" : b.getCategory();
            String nt = normalizeForMatch(title);
            String na = normalizeForMatch(author);
            String ni = normalizeForMatch(intro);
            boolean titleOrAuthorOrIntroMatch = nt.contains(normKw) || na.contains(normKw) || ni.contains(normKw);
            boolean categoryMatch = !shortNumeric && normalizeForMatch(category).contains(normKw);
            if (titleOrAuthorOrIntroMatch || categoryMatch) filtered.add(b);
        }
        return filtered;
    }

    /** 归一化：去空白 + 转小写 + 去常见标点符号，便于模糊匹配 */
    private static String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[\\p{Punct}\\p{P}\\p{S}]", "")
                .trim();
    }

    private List<Book> searchAllSources(String keyword, int maxSize) {
        List<BookSourceFactory.SourceInfo> sources = bookSourceFactory.getAvailableSources();
        List<CompletableFuture<List<Book>>> futures = new ArrayList<>();

        for (BookSourceFactory.SourceInfo info : sources) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    OnlineBookSourceService svc = bookSourceFactory.getService(info.getType());
                    if (svc != null) {
                        List<Book> books = svc.searchBooks(keyword, info.getType(), 0, Math.min(maxSize, 10));
                        for (Book b : books) {
                            if (b.getSourceType() == null) {
                                b.setSourceType(info.getType());
                            }
                        }
                        return books;
                    }
                } catch (Exception e) {
                    // 单个书源搜索失败不影响整体
                }
                return new ArrayList<Book>();
            }));
        }

        List<Book> allBooks = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 超时或异常，返回已有的结果
        }

        String normKw = normalizeForMatch(keyword);
        boolean shortNumericKw = normKw != null && normKw.matches("\\d+") && normKw.length() <= 2;
        for (CompletableFuture<List<Book>> future : futures) {
            try {
                List<Book> books = future.getNow(new ArrayList<>());
                for (Book b : books) {
                    String key = (b.getTitle() == null ? "" : b.getTitle()) + "|" + (b.getAuthor() == null ? "" : b.getAuthor());
                    if (seen.contains(key)) continue;

                    // 关键词二次过滤：title/author/intro 包含 keyword；category 仅对非短数字关键词才匹配
                    String nt = normalizeForMatch(b.getTitle());
                    String na = normalizeForMatch(b.getAuthor());
                    String ni = normalizeForMatch(b.getIntro());
                    boolean titleIntroAuthorMatch = nt.contains(normKw) || na.contains(normKw) || ni.contains(normKw);
                    boolean categoryMatch = !shortNumericKw && normalizeForMatch(b.getCategory()).contains(normKw);
                    if (!titleIntroAuthorMatch && !categoryMatch) continue;

                    seen.add(key);
                    allBooks.add(b);
                    if (allBooks.size() >= maxSize) {
                        return allBooks;
                    }
                }
            } catch (Exception ignored) {}
        }
        return allBooks;
    }

    @GetMapping("/explore")
    public ApiResponse<List<Book>> exploreBooks(
            @RequestParam(defaultValue = "all") String sourceType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String category) {
        try {
            if ("all".equalsIgnoreCase(sourceType)) {
                List<Book> result = exploreAllSources(page, size);
                return ApiResponse.success(result);
            }
            OnlineBookSourceService service = bookSourceFactory.getService(sourceType);
            if (service == null) {
                return ApiResponse.error("不支持的书源类型: " + sourceType);
            }
            List<Book> books = service.exploreBooks(sourceType, page, size, category);
            return ApiResponse.success(books);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取指定书源的发现页分类列表。
     * 返回 List of {分类名称, 分类URL}。
     */
    @GetMapping("/explore-categories")
    public ApiResponse<List<String[]>> getExploreCategories(
            @RequestParam String sourceType) {
        try {
            OnlineBookSourceService service = bookSourceFactory.getService(sourceType);
            if (service == null) {
                return ApiResponse.error("不支持的书源类型: " + sourceType);
            }
            List<String[]> categories = service.getExploreCategories(sourceType);
            return ApiResponse.success(categories);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    private List<Book> exploreAllSources(int page, int size) {
        List<BookSourceFactory.SourceInfo> sources = bookSourceFactory.getAvailableSources();
        List<CompletableFuture<List<Book>>> futures = new ArrayList<>();

        for (BookSourceFactory.SourceInfo info : sources) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    OnlineBookSourceService svc = bookSourceFactory.getService(info.getType());
                    if (svc != null) {
                        List<Book> books = svc.exploreBooks(info.getType(), page, Math.min(size, 20));
                        for (Book b : books) {
                            if (b.getSourceType() == null) {
                                b.setSourceType(info.getType());
                            }
                        }
                        return books;
                    }
                } catch (Exception e) {
                    // 单个书源失败不影响整体
                }
                return new ArrayList<Book>();
            }));
        }

        List<Book> allBooks = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 超时返回已有结果
        }

        for (CompletableFuture<List<Book>> future : futures) {
            try {
                List<Book> books = future.getNow(new ArrayList<>());
                for (Book b : books) {
                    String key = (b.getTitle() == null ? "" : b.getTitle()) + "|" + (b.getAuthor() == null ? "" : b.getAuthor());
                    if (!seen.contains(key)) {
                        seen.add(key);
                        allBooks.add(b);
                    }
                    if (allBooks.size() >= size) {
                        return allBooks;
                    }
                }
            } catch (Exception ignored) {}
        }
        return allBooks;
    }

    @GetMapping("/book")
    public ApiResponse<Book> getBookDetail(
            @RequestParam String sourceType,
            @RequestParam String sourceBookId) {
        try {
            String decodedId = URLDecoder.decode(sourceBookId, StandardCharsets.UTF_8);
            OnlineBookSourceService service = bookSourceFactory.getService(sourceType);
            if (service == null) {
                return ApiResponse.error("不支持的书源类型: " + sourceType);
            }
            Book book = service.getBookDetail(decodedId, sourceType);
            if (book == null) {
                return ApiResponse.error("未找到该书籍");
            }
            return ApiResponse.success(book);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/import")
    public ApiResponse<Book> importBook(
            @RequestParam String sourceType,
            @RequestParam String sourceBookId) {
        try {
            String decodedId = URLDecoder.decode(sourceBookId, StandardCharsets.UTF_8);
            OnlineBookSourceService service = bookSourceFactory.getService(sourceType);
            if (service == null) {
                return ApiResponse.error("不支持的书源类型: " + sourceType);
            }
            Book book = service.importBook(decodedId, sourceType);
            return ApiResponse.success(book);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/chapters")
    public ApiResponse<List<String[]>> getChapterList(
            @RequestParam String sourceType,
            @RequestParam String sourceBookId) {
        try {
            String decodedId = URLDecoder.decode(sourceBookId, StandardCharsets.UTF_8);
            OnlineBookSourceService service = bookSourceFactory.getService(sourceType);
            if (service == null) {
                return ApiResponse.error("不支持的书源类型: " + sourceType);
            }
            List<String[]> chapters = service.getChapterList(decodedId, sourceType);
            return ApiResponse.success(chapters);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/content")
    public ApiResponse<String> getChapterContent(
            @RequestParam String sourceType,
            @RequestParam String sourceBookId,
            @RequestParam String chapterUrl) {
        try {
            String decodedId = URLDecoder.decode(sourceBookId, StandardCharsets.UTF_8);
            String decodedUrl = URLDecoder.decode(chapterUrl, StandardCharsets.UTF_8);
            OnlineBookSourceService service = bookSourceFactory.getService(sourceType);
            if (service == null) {
                return ApiResponse.error("不支持的书源类型: " + sourceType);
            }
            String content = service.getChapterContent(decodedId, decodedUrl, sourceType);
            return ApiResponse.success(content);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 诊断端点：逐步追踪搜索流程，返回每个环节的中间结果，用于定位空数据根因。
     * 用法：GET /api/admin/online-source/diagnose-search?sourceType=CUSTOM_MAOMAO&keyword=斗破苍穹&page=1
     */
    @GetMapping("/diagnose-search")
    public ApiResponse<Map<String, Object>> diagnoseSearch(
            @RequestParam String sourceType,
            @RequestParam(defaultValue = "斗破苍穹") String keyword,
            @RequestParam(defaultValue = "1") int page) {
        Map<String, Object> diag = new LinkedHashMap<>();
        try {
            // Step 0: 从 DB 加载书源配置
            diag.put("step0_sourceType", sourceType);
            diag.put("step0_keyword", keyword);
            diag.put("step0_page", page);

            BookSource bsEntity = bookSourceRepository.findBySourceType(sourceType);
            if (bsEntity == null) {
                diag.put("error", "数据库中未找到 sourceType=" + sourceType);
                return ApiResponse.success(diag);
            }
            diag.put("step0_dbName", bsEntity.getName());
            diag.put("step0_dbBaseUrl", bsEntity.getBaseUrl());

            String configJson = bsEntity.getConfigJson();
            if (configJson == null || configJson.isEmpty()) {
                diag.put("error", "configJson 为空");
                return ApiResponse.success(diag);
            }

            LegadoBookSource legadoSource = objectMapper.readValue(configJson, LegadoBookSource.class);
            diag.put("step1_bookSourceUrl", legadoSource.getBookSourceUrl());
            diag.put("step1_searchUrl", legadoSource.getSearchUrl());
            diag.put("step1_header", legadoSource.getHeader());
            diag.put("step1_loginUrl", legadoSource.getLoginUrl());
            diag.put("step1_loginUi", legadoSource.getLoginUi());

            if (legadoSource.getRuleSearch() != null) {
                SearchRule rule = legadoSource.getRuleSearch();
                diag.put("step1_ruleSearch_bookList", rule.getBookList());
                diag.put("step1_ruleSearch_name", rule.getName());
                diag.put("step1_ruleSearch_author", rule.getAuthor());
                diag.put("step1_ruleSearch_coverUrl", rule.getCoverUrl());
                diag.put("step1_ruleSearch_intro", rule.getIntro());
                diag.put("step1_ruleSearch_kind", rule.getKind());
                diag.put("step1_ruleSearch_wordCount", rule.getWordCount());
                diag.put("step1_ruleSearch_bookUrl", rule.getBookUrl());
                diag.put("step1_ruleSearch_lastChapter", rule.getLastChapter());
                diag.put("step1_ruleSearch_checkKeyWord", rule.getCheckKeyWord());
            } else {
                diag.put("step1_ruleSearch", "null");
            }

            // 输出 ruleBookInfo / ruleToc / ruleContent 配置
            if (legadoSource.getRuleBookInfo() != null) {
                BookInfoRule bi = legadoSource.getRuleBookInfo();
                Map<String, Object> biMap = new LinkedHashMap<>();
                biMap.put("init", bi.getInit());
                biMap.put("name", bi.getName());
                biMap.put("author", bi.getAuthor());
                biMap.put("intro", bi.getIntro());
                biMap.put("kind", bi.getKind());
                biMap.put("coverUrl", bi.getCoverUrl());
                biMap.put("tocUrl", bi.getTocUrl());
                biMap.put("lastChapter", bi.getLastChapter());
                biMap.put("wordCount", bi.getWordCount());
                biMap.put("updateTime", bi.getUpdateTime());
                diag.put("step1_ruleBookInfo", biMap);
            } else {
                diag.put("step1_ruleBookInfo", "null");
            }
            if (legadoSource.getRuleToc() != null) {
                TocRule toc = legadoSource.getRuleToc();
                Map<String, Object> tocMap = new LinkedHashMap<>();
                tocMap.put("chapterList", toc.getChapterList());
                tocMap.put("chapterName", toc.getChapterName());
                tocMap.put("chapterUrl", toc.getChapterUrl());
                tocMap.put("isVolume", toc.getIsVolume());
                tocMap.put("isVip", toc.getIsVip());
                tocMap.put("isPay", toc.getIsPay());
                tocMap.put("updateTime", toc.getUpdateTime());
                tocMap.put("nextTocUrl", toc.getNextTocUrl());
                diag.put("step1_ruleToc", tocMap);
            } else {
                diag.put("step1_ruleToc", "null");
            }
            if (legadoSource.getRuleContent() != null) {
                ContentRule cr = legadoSource.getRuleContent();
                Map<String, Object> crMap = new LinkedHashMap<>();
                crMap.put("content", cr.getContent());
                crMap.put("nextContentUrl", cr.getNextContentUrl());
                crMap.put("replaceRegex", cr.getReplaceRegex());
                crMap.put("sourceRegex", cr.getSourceRegex());
                crMap.put("webJs", cr.getWebJs());
                diag.put("step1_ruleContent", crMap);
            } else {
                diag.put("step1_ruleContent", "null");
            }

            // Step 2: 创建 LegadoBookSourceService 并解析 header
            LegadoBookSourceService service = new LegadoBookSourceService(legadoSource);
            // 通过反射获取 headerMap（因为 parseHeaderJson 是 private）
            // 直接用 LegadoBookSourceService 的 parseHeaderJson 静态方法
            Map<String, String> headerMap = LegadoBookSourceService.parseHeaderJson(legadoSource.getHeader());
            diag.put("step2_parsedHeaders", headerMap);
            diag.put("step2_headerHasAuth", headerMap != null && headerMap.containsKey("Authorization"));

            // Step 3: 构造 AnalyzeUrl 并解析
            String baseUrl = LegadoBookSourceService.sanitizeBaseUrl(legadoSource.getBookSourceUrl());
            String searchUrl = legadoSource.getSearchUrl();
            LegadoJsEngine jsEngine = new LegadoJsEngine(legadoSource.getJsLib(), baseUrl);

            AnalyzeUrl analyzeUrl = new AnalyzeUrl(searchUrl, keyword, page, baseUrl, headerMap, !headerMap.isEmpty());
            analyzeUrl.setJsEngine(jsEngine);

            diag.put("step3_resolvedUrl", analyzeUrl.getUrl());
            diag.put("step3_method", analyzeUrl.getMethod());
            diag.put("step3_headers", analyzeUrl.headerMap);
            diag.put("step3_urlNoQuery", analyzeUrl.getUrlNoQuery());
            diag.put("step3_encodedQuery", analyzeUrl.getEncodedQuery());

            // Step 4: 执行 HTTP 请求
            String response;
            try {
                response = analyzeUrl.execute();
            } catch (Exception e) {
                diag.put("step4_error", e.getClass().getName() + ": " + e.getMessage());
                return ApiResponse.success(diag);
            }
            if (response == null || response.isEmpty()) {
                diag.put("step4_response", "null或空");
                diag.put("step4_responseLength", 0);
                return ApiResponse.success(diag);
            }
            diag.put("step4_responseLength", response.length());
            diag.put("step4_responsePreview", response.length() < 2000 ? response : response.substring(0, 2000) + "...");

            // Step 5: JSONPath 解析 bookList
            AnalyzeRule analyzer = new AnalyzeRule();
            analyzer.setContent(response, analyzeUrl.getUrl());
            analyzer.setJsEngine(jsEngine);
            jsEngine.setAnalyzeUrl(analyzeUrl);
            jsEngine.setAnalyzeRule(analyzer);

            String bookListRule = legadoSource.getRuleSearch() != null ? legadoSource.getRuleSearch().getBookList() : null;
            diag.put("step5_bookListRule", bookListRule);

            if (bookListRule != null && !bookListRule.isEmpty()) {
                List<Object> items = analyzer.getElements(bookListRule);
                diag.put("step5_itemsCount", items.size());
                if (!items.isEmpty()) {
                    // 显示前3个item的类型和内容
                    List<Map<String, Object>> itemPreviews = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, items.size()); i++) {
                        Object item = items.get(i);
                        Map<String, Object> preview = new LinkedHashMap<>();
                        preview.put("type", item.getClass().getSimpleName());
                        if (item instanceof Map) {
                            // 安全预览 Map 内容
                            Map<?, ?> map = (Map<?, ?>) item;
                            Map<String, Object> safeMap = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> entry : map.entrySet()) {
                                String key = entry.getKey() == null ? "null" : entry.getKey().toString();
                                Object val = entry.getValue();
                                if (val instanceof String || val instanceof Number || val instanceof Boolean) {
                                    safeMap.put(key, val);
                                } else if (val == null) {
                                    safeMap.put(key, "null");
                                } else {
                                    safeMap.put(key, val.getClass().getSimpleName() + ": " + val.toString().substring(0, Math.min(100, val.toString().length())));
                                }
                            }
                            preview.put("fields", safeMap);
                        } else {
                            preview.put("value", item.toString().substring(0, Math.min(200, item.toString().length())));
                        }
                        itemPreviews.add(preview);
                    }
                    diag.put("step5_itemPreviews", itemPreviews);

                    // Step 6: 字段提取测试（对第一个item）
                    if (legadoSource.getRuleSearch() != null) {
                        SearchRule rule = legadoSource.getRuleSearch();
                        Object firstItem = items.get(0);
                        Map<String, Object> fieldExtraction = new LinkedHashMap<>();
                        try { fieldExtraction.put("name", analyzer.getString(rule.getName(), firstItem, false)); } catch (Exception e) { fieldExtraction.put("name_error", e.getMessage()); }
                        try { fieldExtraction.put("author", analyzer.getString(rule.getAuthor(), firstItem, false)); } catch (Exception e) { fieldExtraction.put("author_error", e.getMessage()); }
                        try { fieldExtraction.put("coverUrl", analyzer.getString(rule.getCoverUrl(), firstItem, false)); } catch (Exception e) { fieldExtraction.put("coverUrl_error", e.getMessage()); }
                        try { fieldExtraction.put("intro", analyzer.getString(rule.getIntro(), firstItem, false)); } catch (Exception e) { fieldExtraction.put("intro_error", e.getMessage()); }
                        try { fieldExtraction.put("kind", analyzer.getString(rule.getKind(), firstItem, false)); } catch (Exception e) { fieldExtraction.put("kind_error", e.getMessage()); }
                        try { fieldExtraction.put("wordCount", analyzer.getString(rule.getWordCount(), firstItem, false)); } catch (Exception e) { fieldExtraction.put("wordCount_error", e.getMessage()); }
                        diag.put("step6_fieldExtraction", fieldExtraction);
                    }
                }
            }

            // Step 7: 逐步追踪 searchBooks 内部流程，定位0结果根因
            // 7a: checkLoginOrThrow 检查
            try {
                // 通过反射检查 loginHandler 状态
                java.lang.reflect.Field loginHandlerField = LegadoBookSourceService.class.getDeclaredField("loginHandler");
                loginHandlerField.setAccessible(true);
                Object loginHandler = loginHandlerField.get(service);
                if (loginHandler != null) {
                    java.lang.reflect.Method checkLoginNeeded = loginHandler.getClass().getDeclaredMethod("checkLoginNeeded");
                    checkLoginNeeded.setAccessible(true);
                    boolean loginNeeded = (boolean) checkLoginNeeded.invoke(loginHandler);
                    diag.put("step7a_loginNeeded", loginNeeded);
                } else {
                    diag.put("step7a_loginNeeded", "loginHandler is null");
                }
            } catch (Exception e) {
                diag.put("step7a_error", e.getMessage());
            }

            // 7b: checkKeywordAllowed 检查
            try {
                SearchRule rule7 = legadoSource.getRuleSearch();
                String checkKeyWord = rule7 != null ? rule7.getCheckKeyWord() : null;
                diag.put("step7b_checkKeyWord", checkKeyWord);
                if (checkKeyWord != null && !checkKeyWord.trim().isEmpty()) {
                    // 复现 checkKeywordAllowed 逻辑
                    boolean allowed = false;
                    for (String ck : checkKeyWord.split("[,，|;\\s]+")) {
                        String t = ck.trim();
                        if (!t.isEmpty() && keyword.contains(t)) {
                            allowed = true;
                            break;
                        }
                    }
                    diag.put("step7b_keywordAllowed", allowed);
                } else {
                    diag.put("step7b_keywordAllowed", true);
                }
            } catch (Exception e) {
                diag.put("step7b_error", e.getMessage());
            }

            // 7c: 用 service 内部的 createAnalyzeContext 复现
            try {
                java.lang.reflect.Method createCtx = LegadoBookSourceService.class.getDeclaredMethod("createAnalyzeContext", String.class, String.class, Integer.class);
                createCtx.setAccessible(true);
                AnalyzeRule analyzer7 = (AnalyzeRule) createCtx.invoke(service, legadoSource.getSearchUrl(), keyword, page);

                // 获取 currentAnalyzeUrl
                java.lang.reflect.Field urlField = LegadoBookSourceService.class.getDeclaredField("currentAnalyzeUrl");
                urlField.setAccessible(true);
                AnalyzeUrl aUrl7 = (AnalyzeUrl) urlField.get(service);
                diag.put("step7c_resolvedUrl", aUrl7 != null ? aUrl7.getUrl() : "null");
                diag.put("step7c_method", aUrl7 != null ? aUrl7.getMethod() : "null");
                diag.put("step7c_headers", aUrl7 != null ? aUrl7.headerMap : "null");
            } catch (Exception e) {
                diag.put("step7c_error", e.getClass().getName() + ": " + e.getMessage());
            }

            // 7d: 通过 service 内部的 currentAnalyzeUrl 执行 HTTP 请求
            try {
                java.lang.reflect.Field urlField2 = LegadoBookSourceService.class.getDeclaredField("currentAnalyzeUrl");
                urlField2.setAccessible(true);
                AnalyzeUrl aUrl7d = (AnalyzeUrl) urlField2.get(service);
                if (aUrl7d != null) {
                    String resp7d = aUrl7d.execute();
                    diag.put("step7d_responseLength", resp7d != null ? resp7d.length() : 0);
                    diag.put("step7d_responsePreview", resp7d != null && resp7d.length() > 0
                            ? (resp7d.length() < 500 ? resp7d : resp7d.substring(0, 500) + "...") : "null/empty");
                } else {
                    diag.put("step7d_error", "currentAnalyzeUrl is null");
                }
            } catch (Exception e) {
                diag.put("step7d_error", e.getClass().getName() + ": " + e.getMessage());
            }

            // 7e: 用 service 的 analyzer 执行 getElements
            try {
                // 重新创建 context（因为 execute 可能改变了状态）
                java.lang.reflect.Method createCtx2 = LegadoBookSourceService.class.getDeclaredMethod("createAnalyzeContext", String.class, String.class, Integer.class);
                createCtx2.setAccessible(true);
                AnalyzeRule analyzer7e = (AnalyzeRule) createCtx2.invoke(service, legadoSource.getSearchUrl(), keyword, page);

                // 执行 HTTP 请求
                java.lang.reflect.Field urlField3 = LegadoBookSourceService.class.getDeclaredField("currentAnalyzeUrl");
                urlField3.setAccessible(true);
                AnalyzeUrl aUrl7e = (AnalyzeUrl) urlField3.get(service);
                String resp7e = aUrl7e.execute();
                diag.put("step7e_responseLength", resp7e != null ? resp7e.length() : 0);

                // setContent
                analyzer7e.setContent(resp7e, aUrl7e.getUrl());

                // getElements
                String bookListRule7e = legadoSource.getRuleSearch() != null ? legadoSource.getRuleSearch().getBookList() : null;
                List<Object> items7e = analyzer7e.getElements(bookListRule7e);
                diag.put("step7e_itemsCount", items7e.size());

                // 7f: 字段提取测试
                if (!items7e.isEmpty() && legadoSource.getRuleSearch() != null) {
                    SearchRule rule7f = legadoSource.getRuleSearch();
                    List<Map<String, Object>> extraction7f = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, items7e.size()); i++) {
                        Object item = items7e.get(i);
                        Map<String, Object> fields = new LinkedHashMap<>();
                        try { fields.put("name", analyzer7e.getString(rule7f.getName(), item, false)); } catch (Exception e) { fields.put("name_error", e.getMessage()); }
                        try { fields.put("author", analyzer7e.getString(rule7f.getAuthor(), item, false)); } catch (Exception e) { fields.put("author_error", e.getMessage()); }
                        try { fields.put("bookUrl", analyzer7e.getString(rule7f.getBookUrl(), item, false)); } catch (Exception e) { fields.put("bookUrl_error", e.getMessage()); }
                        extraction7f.add(fields);
                    }
                    diag.put("step7f_fieldExtraction", extraction7f);
                }

                // 7g: 模拟 searchKeywordFilter
                if (!items7e.isEmpty() && legadoSource.getRuleSearch() != null) {
                    SearchRule rule7g = legadoSource.getRuleSearch();
                    List<Book> books7g = new ArrayList<>();
                    for (Object item : items7e) {
                        String name7g = analyzer7e.getString(rule7g.getName(), item, false);
                        if (name7g == null || name7g.trim().isEmpty()) continue;
                        Book b = new Book();
                        b.setTitle(name7g.trim());
                        b.setAuthor(analyzer7e.getString(rule7g.getAuthor(), item, false));
                        b.setIntro(analyzer7e.getString(rule7g.getIntro(), item, false));
                        books7g.add(b);
                    }
                    diag.put("step7g_beforeFilter", books7g.size());

                    // normalizeMatch 模拟
                    String nkw = keyword.toLowerCase().replaceAll("\\s+", "").replaceAll("[\\p{Punct}\\p{P}\\p{S}]", "").trim();
                    int matchCount = 0;
                    List<String> matchDetails = new ArrayList<>();
                    for (Book b : books7g) {
                        String nt = b.getTitle() == null ? "" : b.getTitle().toLowerCase().replaceAll("\\s+", "").replaceAll("[\\p{Punct}\\p{P}\\p{S}]", "").trim();
                        String na = b.getAuthor() == null ? "" : b.getAuthor().toLowerCase().replaceAll("\\s+", "").replaceAll("[\\p{Punct}\\p{P}\\p{S}]", "").trim();
                        boolean titleMatch = nt.contains(nkw);
                        boolean authorMatch = na.contains(nkw);
                        if (titleMatch || authorMatch) {
                            matchCount++;
                        }
                        if (matchDetails.size() < 5) {
                            matchDetails.add("title='" + b.getTitle() + "' nt='" + nt + "' nkw='" + nkw + "' titleMatch=" + titleMatch + " authorMatch=" + authorMatch);
                        }
                    }
                    diag.put("step7g_afterFilter", matchCount);
                    diag.put("step7g_matchDetails", matchDetails);
                }
            } catch (Exception e) {
                diag.put("step7e_error", e.getClass().getName() + ": " + e.getMessage());
                diag.put("step7e_stack", getStackTrace(e));
            }

            // Step 7h: 原始 service.searchBooks 调用
            try {
                List<Book> books = service.searchBooks(keyword, page);
                diag.put("step7h_searchBooksCount", books.size());
                if (!books.isEmpty()) {
                    List<Map<String, String>> bookPreviews = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, books.size()); i++) {
                        Book b = books.get(i);
                        Map<String, String> bp = new LinkedHashMap<>();
                        bp.put("title", b.getTitle());
                        bp.put("author", b.getAuthor());
                        bp.put("intro", b.getIntro() != null ? b.getIntro().substring(0, Math.min(50, b.getIntro().length())) : null);
                        bp.put("category", b.getCategory());
                        bookPreviews.add(bp);
                    }
                    diag.put("step7h_bookPreviews", bookPreviews);
                }
            } catch (Exception e) {
                diag.put("step7h_error", e.getClass().getName() + ": " + e.getMessage());
                diag.put("step7h_stack", getStackTrace(e));
            }

        } catch (Exception e) {
            diag.put("fatalError", e.getClass().getName() + ": " + e.getMessage());
            diag.put("fatalStack", getStackTrace(e));
        }
        return ApiResponse.success(diag);
    }

    /**
     * 诊断端点：追踪书籍详情 + 章节列表 + 章节内容流程。
     * 用法：GET /api/admin/online-source/diagnose-detail?sourceType=CUSTOM_26412&bookUrl=http://...
     * 如果不传 bookUrl，则先搜索获取第一本书的 bookUrl 再测试详情。
     */
    @GetMapping("/diagnose-detail")
    public ApiResponse<Map<String, Object>> diagnoseDetail(
            @RequestParam String sourceType,
            @RequestParam(required = false) String bookUrl,
            @RequestParam(defaultValue = "斗破苍穹") String keyword) {
        Map<String, Object> diag = new LinkedHashMap<>();
        try {
            // Step 0: 加载书源
            BookSource bsEntity = bookSourceRepository.findBySourceType(sourceType);
            if (bsEntity == null) {
                diag.put("error", "数据库中未找到 sourceType=" + sourceType);
                return ApiResponse.success(diag);
            }
            String configJson = bsEntity.getConfigJson();
            LegadoBookSource legadoSource = objectMapper.readValue(configJson, LegadoBookSource.class);
            LegadoBookSourceService service = new LegadoBookSourceService(legadoSource);

            // 输出规则配置
            diag.put("ruleBookInfo", legadoSource.getRuleBookInfo() != null ? objectMapper.writeValueAsString(legadoSource.getRuleBookInfo()) : "null");
            diag.put("ruleToc", legadoSource.getRuleToc() != null ? objectMapper.writeValueAsString(legadoSource.getRuleToc()) : "null");
            diag.put("ruleContent", legadoSource.getRuleContent() != null ? objectMapper.writeValueAsString(legadoSource.getRuleContent()) : "null");
            diag.put("ruleSearch_bookUrl", legadoSource.getRuleSearch() != null ? legadoSource.getRuleSearch().getBookUrl() : "null");

            // Step 1: 如果没有 bookUrl，先搜索获取
            if (bookUrl == null || bookUrl.trim().isEmpty()) {
                diag.put("step1_action", "搜索获取 bookUrl");
                List<Book> books = service.searchBooks(keyword, 1);
                diag.put("step1_searchCount", books.size());
                if (!books.isEmpty()) {
                    Book first = books.get(0);
                    diag.put("step1_firstTitle", first.getTitle());
                    diag.put("step1_firstSourceUrl", first.getSourceUrl());
                    diag.put("step1_firstCover", first.getCover());
                    diag.put("step1_firstIntro", first.getIntro() != null ? first.getIntro().substring(0, Math.min(100, first.getIntro().length())) : "null");
                    bookUrl = first.getSourceUrl();
                    diag.put("step1_resolvedBookUrl", bookUrl);
                } else {
                    diag.put("error", "搜索无结果，无法获取 bookUrl");
                    return ApiResponse.success(diag);
                }
            } else {
                diag.put("step1_bookUrl", bookUrl);
            }

            if (bookUrl == null || bookUrl.trim().isEmpty()) {
                diag.put("error", "bookUrl 为空，无法继续详情测试");
                return ApiResponse.success(diag);
            }

            // Step 2: 测试 getBookInfo
            diag.put("step2_action", "测试 getBookInfo");
            try {
                Book bookInfo = service.getBookInfo(bookUrl);
                if (bookInfo == null) {
                    diag.put("step2_result", "null");
                } else {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("title", bookInfo.getTitle());
                    info.put("author", bookInfo.getAuthor());
                    info.put("intro", bookInfo.getIntro() != null ? bookInfo.getIntro().substring(0, Math.min(200, bookInfo.getIntro().length())) : "null");
                    info.put("cover", bookInfo.getCover());
                    info.put("category", bookInfo.getCategory());
                    info.put("tocUrl", bookInfo.getTocUrl());
                    info.put("sourceUrl", bookInfo.getSourceUrl());
                    info.put("lastChapter", bookInfo.getLastChapter());
                    info.put("wordCount", bookInfo.getWordCount());
                    diag.put("step2_bookInfo", info);
                }
            } catch (Exception e) {
                diag.put("step2_error", e.getClass().getName() + ": " + e.getMessage());
                diag.put("step2_stack", getStackTrace(e));
            }

            // Step 3: 手动追踪 getBookInfo 内部流程
            diag.put("step3_action", "手动追踪 getBookInfo 内部流程");
            try {
                // 3a: 创建 AnalyzeUrl 并请求详情页
                String baseUrl = LegadoBookSourceService.sanitizeBaseUrl(legadoSource.getBookSourceUrl());
                Map<String, String> headerMap = LegadoBookSourceService.parseHeaderJson(legadoSource.getHeader());
                AnalyzeUrl detailUrl = new AnalyzeUrl(bookUrl, null, null, baseUrl, headerMap, !headerMap.isEmpty());
                LegadoJsEngine jsEngine3 = new LegadoJsEngine(legadoSource.getJsLib(), baseUrl);
                detailUrl.setJsEngine(jsEngine3);
                diag.put("step3a_resolvedUrl", detailUrl.getUrl());
                diag.put("step3a_method", detailUrl.getMethod());

                // 3b: 执行 HTTP 请求
                String detailResp = detailUrl.execute();
                diag.put("step3b_responseLength", detailResp != null ? detailResp.length() : 0);
                diag.put("step3b_responsePreview", detailResp != null && detailResp.length() > 0
                        ? (detailResp.length() < 1000 ? detailResp : detailResp.substring(0, 1000) + "...") : "null/empty");

                // 3c: setContent + getElements (如果有 ruleBookInfo)
                if (legadoSource.getRuleBookInfo() != null) {
                    AnalyzeRule analyzer3 = new AnalyzeRule();
                    analyzer3.setContent(detailResp, detailUrl.getUrl());
                    analyzer3.setJsEngine(jsEngine3);
                    jsEngine3.setAnalyzeUrl(detailUrl);
                    jsEngine3.setAnalyzeRule(analyzer3);

                    BookInfoRule biRule = legadoSource.getRuleBookInfo();
                    Map<String, Object> extract3 = new LinkedHashMap<>();
                    try { extract3.put("name", analyzer3.getString(biRule.getName(), null, false)); } catch (Exception e) { extract3.put("name_error", e.getMessage()); }
                    try { extract3.put("author", analyzer3.getString(biRule.getAuthor(), null, false)); } catch (Exception e) { extract3.put("author_error", e.getMessage()); }
                    try { extract3.put("intro", analyzer3.getString(biRule.getIntro(), null, false)); } catch (Exception e) { extract3.put("intro_error", e.getMessage()); }
                    try { extract3.put("tocUrl", analyzer3.getString(biRule.getTocUrl(), null, true)); } catch (Exception e) { extract3.put("tocUrl_error", e.getMessage()); }
                    try { extract3.put("coverUrl", analyzer3.getString(biRule.getCoverUrl(), null, false)); } catch (Exception e) { extract3.put("coverUrl_error", e.getMessage()); }
                    diag.put("step3c_fieldExtraction", extract3);
                } else {
                    diag.put("step3c_ruleBookInfo", "null - 无法提取详情字段");
                }
            } catch (Exception e) {
                diag.put("step3_error", e.getClass().getName() + ": " + e.getMessage());
                diag.put("step3_stack", getStackTrace(e));
            }

            // Step 4: 测试 getChapterList
            diag.put("step4_action", "测试 getChapterList");
            try {
                // 先获取 tocUrl（从 getBookInfo 或直接用 bookUrl）
                String tocUrl = bookUrl;
                try {
                    Book info4 = service.getBookInfo(bookUrl);
                    if (info4 != null && info4.getTocUrl() != null && !info4.getTocUrl().trim().isEmpty()) {
                        tocUrl = info4.getTocUrl();
                    }
                } catch (Exception ignored) {}
                diag.put("step4_tocUrl", tocUrl);

                List<Chapter> chapters = service.getChapterList(tocUrl);
                diag.put("step4_chapterCount", chapters.size());
                if (!chapters.isEmpty()) {
                    List<Map<String, Object>> chPreviews = new ArrayList<>();
                    for (int i = 0; i < Math.min(5, chapters.size()); i++) {
                        Chapter ch = chapters.get(i);
                        Map<String, Object> cp = new LinkedHashMap<>();
                        cp.put("title", ch.getTitle());
                        cp.put("url", ch.getUrl());
                        cp.put("urlLength", ch.getUrl() != null ? ch.getUrl().length() : 0);
                        cp.put("isVolume", ch.getIsVolume());
                        chPreviews.add(cp);
                    }
                    diag.put("step4_chapterPreviews", chPreviews);
                    // 统计空URL的章节数
                    long emptyUrlCount = chapters.stream().filter(ch -> ch.getUrl() == null || ch.getUrl().trim().isEmpty()).count();
                    diag.put("step4_emptyUrlCount", emptyUrlCount);
                }
            } catch (Exception e) {
                diag.put("step4_error", e.getClass().getName() + ": " + e.getMessage());
                diag.put("step4_stack", getStackTrace(e));
            }

            // Step 5: 手动追踪 getChapterList 内部流程
            diag.put("step5_action", "手动追踪 getChapterList 内部流程");
            try {
                if (legadoSource.getRuleToc() != null) {
                    TocRule tocRule = legadoSource.getRuleToc();
                    diag.put("step5_chapterListRule", tocRule.getChapterList());
                    diag.put("step5_chapterNameRule", tocRule.getChapterName());
                    diag.put("step5_chapterUrlRule", tocRule.getChapterUrl());

                    String baseUrl5 = LegadoBookSourceService.sanitizeBaseUrl(legadoSource.getBookSourceUrl());
                    Map<String, String> headerMap5 = LegadoBookSourceService.parseHeaderJson(legadoSource.getHeader());
                    String tocUrl5 = bookUrl;
                    // 尝试从详情获取 tocUrl
                    try {
                        Book info5 = service.getBookInfo(bookUrl);
                        if (info5 != null && info5.getTocUrl() != null && !info5.getTocUrl().trim().isEmpty()) {
                            tocUrl5 = info5.getTocUrl();
                        }
                    } catch (Exception ignored) {}

                    AnalyzeUrl tocAnalyzeUrl = new AnalyzeUrl(tocUrl5, null, null, baseUrl5, headerMap5, !headerMap5.isEmpty());
                    LegadoJsEngine jsEngine5 = new LegadoJsEngine(legadoSource.getJsLib(), baseUrl5);
                    tocAnalyzeUrl.setJsEngine(jsEngine5);
                    diag.put("step5a_resolvedTocUrl", tocAnalyzeUrl.getUrl());

                    String tocResp = tocAnalyzeUrl.execute();
                    diag.put("step5b_responseLength", tocResp != null ? tocResp.length() : 0);
                    diag.put("step5b_responsePreview", tocResp != null && tocResp.length() > 0
                            ? (tocResp.length() < 1000 ? tocResp : tocResp.substring(0, 1000) + "...") : "null/empty");

                    if (tocResp != null && !tocResp.isEmpty()) {
                        AnalyzeRule analyzer5 = new AnalyzeRule();
                        analyzer5.setContent(tocResp, tocAnalyzeUrl.getUrl());
                        analyzer5.setJsEngine(jsEngine5);
                        jsEngine5.setAnalyzeUrl(tocAnalyzeUrl);
                        jsEngine5.setAnalyzeRule(analyzer5);

                        if (tocRule.getChapterList() != null && !tocRule.getChapterList().isEmpty()) {
                            List<Object> chItems = analyzer5.getElements(tocRule.getChapterList());
                            diag.put("step5c_itemsCount", chItems.size());
                            if (!chItems.isEmpty()) {
                                List<Map<String, Object>> chExtract = new ArrayList<>();
                                for (int i = 0; i < Math.min(5, chItems.size()); i++) {
                                    Object item = chItems.get(i);
                                    Map<String, Object> ce = new LinkedHashMap<>();
                                    if (item instanceof Map) {
                                        Map<?, ?> m = (Map<?, ?>) item;
                                        Map<String, Object> safeMap = new LinkedHashMap<>();
                                        for (Map.Entry<?, ?> entry : m.entrySet()) {
                                            String k = entry.getKey() == null ? "null" : entry.getKey().toString();
                                            Object v = entry.getValue();
                                            if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                                                safeMap.put(k, v);
                                            } else if (v == null) {
                                                safeMap.put(k, "null");
                                            } else {
                                                safeMap.put(k, v.getClass().getSimpleName() + ":" + v.toString().substring(0, Math.min(80, v.toString().length())));
                                            }
                                        }
                                        ce.put("rawFields", safeMap);
                                    }
                                    try { ce.put("chapterName", analyzer5.getString(tocRule.getChapterName(), item, false)); } catch (Exception e) { ce.put("chapterName_error", e.getMessage()); }
                                    try { ce.put("chapterUrl", analyzer5.getString(tocRule.getChapterUrl(), item, false)); } catch (Exception e) { ce.put("chapterUrl_error", e.getMessage()); }
                                    chExtract.add(ce);
                                }
                                diag.put("step5d_chapterExtraction", chExtract);
                            }
                        } else {
                            diag.put("step5c_chapterListRule", "空或null");
                        }
                    }
                } else {
                    diag.put("step5_ruleToc", "null - 无法提取章节列表");
                }
            } catch (Exception e) {
                diag.put("step5_error", e.getClass().getName() + ": " + e.getMessage());
                diag.put("step5_stack", getStackTrace(e));
            }

        } catch (Exception e) {
            diag.put("fatalError", e.getClass().getName() + ": " + e.getMessage());
            diag.put("fatalStack", getStackTrace(e));
        }
        return ApiResponse.success(diag);
    }

    /**
     * API 探测端点：探测书源的搜索结果原始字段结构，并尝试探测详情/目录/正文 API。
     * 用法：GET /api/admin/online-source/probe-api?sourceType=CUSTOM_26412&keyword=斗破苍穹
     * 
     * 目的：当 ruleBookInfo/ruleToc/ruleContent 缺失时，通过此端点发现 API 结构，
     * 以便补全配置规则。
     */
    @GetMapping("/probe-api")
    public ApiResponse<Map<String, Object>> probeApi(
            @RequestParam String sourceType,
            @RequestParam(defaultValue = "斗破苍穹") String keyword) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 1. 加载书源
            BookSource bsEntity = bookSourceRepository.findBySourceType(sourceType);
            if (bsEntity == null) {
                result.put("error", "数据库中未找到 sourceType=" + sourceType);
                return ApiResponse.success(result);
            }
            String configJson = bsEntity.getConfigJson();
            if (configJson == null || configJson.isEmpty()) {
                result.put("error", "configJson 为空");
                return ApiResponse.success(result);
            }
            LegadoBookSource legadoSource = objectMapper.readValue(configJson, LegadoBookSource.class);
            String baseUrl = LegadoBookSourceService.sanitizeBaseUrl(legadoSource.getBookSourceUrl());
            Map<String, String> headerMap = LegadoBookSourceService.parseHeaderJson(legadoSource.getHeader());

            result.put("baseUrl", baseUrl);
            result.put("searchUrl", legadoSource.getSearchUrl());
            result.put("header", legadoSource.getHeader());
            result.put("hasRuleBookInfo", legadoSource.getRuleBookInfo() != null);
            result.put("hasRuleToc", legadoSource.getRuleToc() != null);
            result.put("hasRuleContent", legadoSource.getRuleContent() != null);
            result.put("hasBookUrl", legadoSource.getRuleSearch() != null && legadoSource.getRuleSearch().getBookUrl() != null);

            // 2. 执行搜索请求，获取原始响应
            String searchUrl = legadoSource.getSearchUrl();
            if (searchUrl == null || searchUrl.trim().isEmpty()) {
                result.put("error", "searchUrl 为空");
                return ApiResponse.success(result);
            }
            LegadoJsEngine jsEngine = new LegadoJsEngine(legadoSource.getJsLib(), baseUrl);
            AnalyzeUrl analyzeUrl = new AnalyzeUrl(searchUrl, keyword, 1, baseUrl, headerMap, !headerMap.isEmpty());
            analyzeUrl.setJsEngine(jsEngine);

            result.put("resolvedSearchUrl", analyzeUrl.getUrl());

            String searchResp = analyzeUrl.execute();
            if (searchResp == null || searchResp.isEmpty()) {
                result.put("error", "搜索响应为空");
                return ApiResponse.success(result);
            }
            result.put("searchResponseLength", searchResp.length());
            result.put("searchResponsePreview", searchResp.length() < 3000 ? searchResp : searchResp.substring(0, 3000) + "...");

            // 3. 解析搜索结果，显示第一个 item 的所有字段
            AnalyzeRule analyzer = new AnalyzeRule();
            analyzer.setContent(searchResp, analyzeUrl.getUrl());
            analyzer.setJsEngine(jsEngine);
            jsEngine.setAnalyzeUrl(analyzeUrl);
            jsEngine.setAnalyzeRule(analyzer);

            String bookListRule = legadoSource.getRuleSearch() != null ? legadoSource.getRuleSearch().getBookList() : null;
            if (bookListRule == null || bookListRule.isEmpty()) {
                result.put("error", "ruleSearch.bookList 为空");
                return ApiResponse.success(result);
            }

            List<Object> items = analyzer.getElements(bookListRule);
            result.put("searchItemsCount", items.size());

            if (!items.isEmpty()) {
                Object firstItem = items.get(0);
                // 显示第一个 item 的所有原始字段
                if (firstItem instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) firstItem;
                    Map<String, Object> rawFields = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String key = entry.getKey() == null ? "null" : entry.getKey().toString();
                        Object val = entry.getValue();
                        if (val instanceof String || val instanceof Number || val instanceof Boolean) {
                            rawFields.put(key, val);
                        } else if (val == null) {
                            rawFields.put(key, "null");
                        } else if (val instanceof List) {
                            // 列表类型：显示元素数量和第一个元素的概要
                            List<?> list = (List<?>) val;
                            rawFields.put(key, "List[" + list.size() + "]" + (list.isEmpty() ? "" : 
                                " first=" + (list.get(0) instanceof Map ? "Map" : list.get(0).toString().substring(0, Math.min(80, list.get(0).toString().length())))));
                        } else if (val instanceof Map) {
                            rawFields.put(key, "Map" + val.toString().substring(0, Math.min(100, val.toString().length())));
                        } else {
                            rawFields.put(key, val.getClass().getSimpleName() + ": " + val.toString().substring(0, Math.min(80, val.toString().length())));
                        }
                    }
                    result.put("firstItemRawFields", rawFields);

                    // 提取可能的 ID 字段
                    Object idVal = map.get("id");
                    Object novelIdVal = map.get("novelId");
                    Object bookIdVal = map.get("bookId");
                    Object nidVal = map.get("nid");
                    String detectedId = null;
                    String idFieldName = null;
                    if (idVal != null) { detectedId = idVal.toString(); idFieldName = "id"; }
                    else if (novelIdVal != null) { detectedId = novelIdVal.toString(); idFieldName = "novelId"; }
                    else if (bookIdVal != null) { detectedId = bookIdVal.toString(); idFieldName = "bookId"; }
                    else if (nidVal != null) { detectedId = nidVal.toString(); idFieldName = "nid"; }
                    result.put("detectedIdField", idFieldName);
                    result.put("detectedIdValue", detectedId);

                    // 4. 如果找到 ID，尝试探测详情 API
                    if (detectedId != null) {
                        result.put("probe_action", "尝试探测详情/目录/正文 API");
                        String cleanBaseUrl = baseUrl.replaceAll("/+$", ""); // 去除尾部斜杠
                        
                        // 常见的详情 API 模式
                        String[] detailPatterns = {
                            cleanBaseUrl + "/novel/" + detectedId,
                            cleanBaseUrl + "/novel/detail?id=" + detectedId,
                            cleanBaseUrl + "/book/" + detectedId,
                            cleanBaseUrl + "/book/detail?id=" + detectedId,
                            cleanBaseUrl + "/api/novel/" + detectedId,
                            cleanBaseUrl + "/api/novel/detail?id=" + detectedId,
                            cleanBaseUrl + "/api/book/" + detectedId,
                            cleanBaseUrl + "/api/book/detail?id=" + detectedId
                        };

                        Map<String, Object> detailProbeResults = new LinkedHashMap<>();
                        for (String url : detailPatterns) {
                            try {
                                AnalyzeUrl probeUrl = new AnalyzeUrl(url, null, null, baseUrl, headerMap, !headerMap.isEmpty());
                                String probeResp = probeUrl.execute();
                                if (probeResp != null && !probeResp.isEmpty()) {
                                    int len = probeResp.length();
                                    String preview = len < 500 ? probeResp : probeResp.substring(0, 500) + "...";
                                    // 检查是否是有效 JSON 响应（非 404 HTML）
                                    boolean looksLikeJson = probeResp.trim().startsWith("{") || probeResp.trim().startsWith("[");
                                    if (looksLikeJson) {
                                        detailProbeResults.put(url, "OK len=" + len + " preview=" + preview);
                                    } else {
                                        detailProbeResults.put(url, "NON-JSON len=" + len + " preview=" + preview.substring(0, Math.min(100, preview.length())));
                                    }
                                } else {
                                    detailProbeResults.put(url, "空响应");
                                }
                            } catch (Exception e) {
                                detailProbeResults.put(url, "ERROR: " + e.getMessage());
                            }
                        }
                        result.put("detailProbeResults", detailProbeResults);

                        // 5. 如果找到详情 API，尝试探测目录 API
                        String[] tocPatterns = {
                            cleanBaseUrl + "/novel/" + detectedId + "/chapters",
                            cleanBaseUrl + "/chapter/list?novelId=" + detectedId,
                            cleanBaseUrl + "/chapter/list?novel_id=" + detectedId,
                            cleanBaseUrl + "/novel/chapters?id=" + detectedId,
                            cleanBaseUrl + "/api/chapter/list?novelId=" + detectedId,
                            cleanBaseUrl + "/api/novel/" + detectedId + "/chapters",
                            cleanBaseUrl + "/toc?id=" + detectedId,
                            cleanBaseUrl + "/api/toc?id=" + detectedId
                        };

                        Map<String, Object> tocProbeResults = new LinkedHashMap<>();
                        for (String url : tocPatterns) {
                            try {
                                AnalyzeUrl probeUrl = new AnalyzeUrl(url, null, null, baseUrl, headerMap, !headerMap.isEmpty());
                                String probeResp = probeUrl.execute();
                                if (probeResp != null && !probeResp.isEmpty()) {
                                    int len = probeResp.length();
                                    String preview = len < 500 ? probeResp : probeResp.substring(0, 500) + "...";
                                    boolean looksLikeJson = probeResp.trim().startsWith("{") || probeResp.trim().startsWith("[");
                                    if (looksLikeJson) {
                                        tocProbeResults.put(url, "OK len=" + len + " preview=" + preview);
                                    } else {
                                        tocProbeResults.put(url, "NON-JSON len=" + len);
                                    }
                                } else {
                                    tocProbeResults.put(url, "空响应");
                                }
                            } catch (Exception e) {
                                tocProbeResults.put(url, "ERROR: " + e.getMessage());
                            }
                        }
                        result.put("tocProbeResults", tocProbeResults);
                    } else {
                        result.put("probe_action", "未找到 ID 字段，无法探测详情 API");
                    }
                } else {
                    result.put("firstItemType", firstItem.getClass().getName());
                    result.put("firstItemValue", firstItem.toString().substring(0, Math.min(500, firstItem.toString().length())));
                }
            }
        } catch (Exception e) {
            result.put("fatalError", e.getClass().getName() + ": " + e.getMessage());
            result.put("fatalStack", getStackTrace(e));
        }
        return ApiResponse.success(result);
    }

    private static String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement ste : e.getStackTrace()) {
            sb.append(ste.toString()).append("\n");
            if (sb.length() > 2000) break;
        }
        return sb.toString();
    }
}
