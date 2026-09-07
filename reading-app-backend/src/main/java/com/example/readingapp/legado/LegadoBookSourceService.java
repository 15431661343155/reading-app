package com.example.readingapp.legado;

import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.legado.analyze.AnalyzeRule;
import com.example.readingapp.legado.analyze.AnalyzeUrl;
import com.example.readingapp.legado.auth.LoginHandler;
import com.example.readingapp.legado.js.LegadoJsEngine;
import com.example.readingapp.legado.model.BookInfoRule;
import com.example.readingapp.legado.model.ContentRule;
import com.example.readingapp.legado.model.LegadoBookSource;
import com.example.readingapp.legado.model.SearchRule;
import com.example.readingapp.legado.model.TocRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 统一书源解析服务，替代旧的 {@code JsonBookSourceServiceImpl}。
 *
 * <p>使用 Legado 原生解析引擎（{@link AnalyzeRule} + {@link AnalyzeUrl} + {@link LegadoJsEngine}）
 * 完整实现书源的搜索、详情、目录、正文四大功能，输出项目已有的 {@link Book} / {@link Chapter} 实体。
 *
 * <p>每个 {@link LegadoBookSource} 对应一个本服务实例，串行使用（{@link LegadoJsEngine#eval}
 * 内部已加锁）。线程不安全，请勿跨线程并发复用同一实例。
 */
@Slf4j
public class LegadoBookSourceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LegadoBookSource source;
    private final String baseUrl;
    private final LegadoJsEngine jsEngine;
    private final Map<String, String> headerMap;
    private final LoginHandler loginHandler;

    /** 登录成功后缓存的请求头（Cookie/Token 等），doLogin 成功后填充 */
    private Map<String, String> cachedLoginHeaders;

    /** 最近一次 {@link #createAnalyzeContext} 创建的 AnalyzeUrl，供主流程调用 {@code execute()} */
    private AnalyzeUrl currentAnalyzeUrl;

    public LegadoBookSourceService(LegadoBookSource source) {
        this.source = source;
        // 清洗 baseUrl：去除 Legado 书源中常见的全角注释后缀（如 ＃妍希、#注释 等）
        this.baseUrl = sanitizeBaseUrl(source.getBookSourceUrl());
        // 初始化 JS 引擎
        this.jsEngine = new LegadoJsEngine(source.getJsLib(), baseUrl);
        // 解析 header JSON
        this.headerMap = parseHeaderJson(source.getHeader());
        this.loginHandler = new LoginHandler(source);
    }

    /**
     * 清洗 bookSourceUrl：去除全角注释、非法字符、backtick 包裹。
     * Legado 书源中常见格式：
     * - https://example.com＃作者注释 (全角哈希注释)
     * - https://example.com#注释 (ASCII 哈希注释)
     * - `https://example.com` (backtick 包裹)
     * 需要去除所有注释和包裹字符，只保留干净的 URL。
     */
    public static String sanitizeBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }
        String cleaned = url.trim();
        // 去除 backtick 包裹（Legado JS 中 `url` 语法的字符串标记）
        if (cleaned.startsWith("`") && cleaned.endsWith("`") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        } else if (cleaned.startsWith("`")) {
            cleaned = cleaned.substring(1);
        } else if (cleaned.endsWith("`")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        // 去除全角哈希 ＃ (U+FF03) 及其后所有内容
        int fullwidthHash = cleaned.indexOf('＃');
        if (fullwidthHash > 0) {
            cleaned = cleaned.substring(0, fullwidthHash);
        }
        // 去除 ASCII # 及其后所有内容（注释标记）
        int asciiHash = cleaned.indexOf('#');
        if (asciiHash > 0) {
            cleaned = cleaned.substring(0, asciiHash);
        }
        // 去除尾部空白
        cleaned = cleaned.trim();
        return cleaned;
    }

    /* ============================================================
     * 1. 搜索
     * ============================================================ */

    /**
     * 搜索书籍。
     *
     * @param keyword 搜索关键词
     * @param page    页码（从 1 开始）
     * @return 匹配的书籍列表（已做关键词二次过滤）
     */
    public List<Book> searchBooks(String keyword, int page) {
        checkLoginOrThrow();
        // 猫眼等API要求page从1开始，前端传入0-based，统一+1
        if (page < 1) { page = 1; }
        List<Book> result = new ArrayList<>();
        try {
            SearchRule rule = source.getRuleSearch();
            if (rule == null) {
                log.warn("[{}] 无 ruleSearch 配置，无法搜索", sourceName());
                return result;
            }
            if (keyword == null || keyword.trim().isEmpty()) {
                return result;
            }

            // 1) checkKeyWord 关键词匹配检查
            if (!checkKeywordAllowed(keyword, rule.getCheckKeyWord())) {
                log.info("[{}] 关键词 {} 不匹配 checkKeyWord({})，跳过搜索",
                        sourceName(), keyword, rule.getCheckKeyWord());
                return result;
            }

            // 2) 获取 searchUrl，{{key}}/{{page}} 等占位符由 AnalyzeUrl.replaceKeyPageJs() 统一替换
            //    不再手动 URL 编码关键词——AnalyzeUrl.analyzeUrl() 的 encodeParams() 会正确处理
            String searchUrl = source.getSearchUrl();
            if (searchUrl == null || searchUrl.trim().isEmpty()) {
                log.warn("[{}] searchUrl 为空", sourceName());
                return result;
            }

            // 3) 创建 LegadoJsEngine + AnalyzeUrl + AnalyzeRule，互相注入
            //    传入原始 searchUrl（含 {{key}}/{{page}} 占位符）和 keyword/page 参数
            AnalyzeRule analyzer = createAnalyzeContext(searchUrl, keyword, page);

            // 4) 执行 HTTP 请求获取响应
            log.info("[{}] 解析后搜索URL: {}", sourceName(), currentAnalyzeUrl.getUrl());
            if (log.isDebugEnabled()) {
                log.debug("[{}] 请求头: {}", sourceName(), currentAnalyzeUrl.headerMap);
            }
            String response = currentAnalyzeUrl.execute();
            if (response == null || response.isEmpty()) {
                log.warn("[{}] 搜索响应为空", sourceName());
                return result;
            }
            log.info("[{}] 搜索响应长度={} 预览={}", sourceName(), response.length(),
                    response.length() < 800 ? response : response.substring(0, 800) + "...");
            log.info("[{}] 请求头: {}", sourceName(), currentAnalyzeUrl.headerMap);

            // 5) AnalyzeRule.setContent(响应, baseUrl)
            String url = currentAnalyzeUrl.getUrl();
            analyzer.setContent(response, url);

            // 6) 用 ruleSearch.bookList 获取书籍列表
            if (rule.getBookList() == null || rule.getBookList().isEmpty()) {
                log.warn("[{}] ruleSearch.bookList 为空", sourceName());
                return result;
            }
            List<Object> items = analyzer.getElements(rule.getBookList());
            log.info("[{}] 搜索列表匹配到 {} 条结果", sourceName(), items.size());

            // 7) 对每个 item 提取字段并构建 Book
            for (Object item : items) {
                String name = analyzer.getString(rule.getName(), item, false);
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                Book book = new Book();
                book.setTitle(safe(name));
                book.setAuthor(safe(analyzer.getString(rule.getAuthor(), item, false)));
                book.setIntro(safe(analyzer.getString(rule.getIntro(), item, false)));
                book.setCategory(safe(analyzer.getString(rule.getKind(), item, false)));
                book.setCover(absoluteUrl(analyzer.getString(rule.getCoverUrl(), item, false)));
                book.setLastChapter(safe(analyzer.getString(rule.getLastChapter(), item, false)));
                book.setUpdateTime(safe(analyzer.getString(rule.getUpdateTime(), item, false)));
                book.setWordCount(parseWordCount(analyzer.getString(rule.getWordCount(), item, false)));
                String bookUrl = absoluteUrl(analyzer.getString(rule.getBookUrl(), item, false));
                book.setSourceUrl(bookUrl);
                book.setSourceType(baseUrl);
                result.add(book);
            }

            // 8) 关键词二次过滤
            result = searchKeywordFilter(result, keyword);
            log.info("[{}] 搜索解析出 {} 本有效书籍", sourceName(), result.size());
        } catch (Exception e) {
            log.error("[{}] 搜索书籍失败: {}", sourceName(), e.getMessage(), e);
        }
        return result;
    }

    /* ============================================================
     * 2. 详情
     * ============================================================ */

    /**
     * 获取书籍详情。
     *
     * @param bookUrl 书籍详情页 URL
     * @return 书籍信息（含 tocUrl，供 {@link #getChapterList} 使用）
     */
    public Book getBookInfo(String bookUrl) {
        checkLoginOrThrow();
        try {
            BookInfoRule rule = source.getRuleBookInfo();

            // 1) 创建 AnalyzeUrl(bookUrl, null, null, baseUrl, headerMap, true)
            AnalyzeRule analyzer = createAnalyzeContext(bookUrl, null, null);

            // 2) 执行 HTTP 请求
            String response = currentAnalyzeUrl.execute();
            if (response == null) {
                return null;
            }

            // 3) AnalyzeRule.setContent(响应, url)
            String url = currentAnalyzeUrl.getUrl();
            analyzer.setContent(response, url);

            // 4) 如果 ruleBookInfo.init 非空，执行初始化规则（导航到子对象）
            //    Legado 行为：init 规则的结果成为后续规则的新 content 作用域
            //    例如 init:"data.book" → 后续 name:"title" 从 data.book.title 提取
            if (rule != null && rule.getInit() != null && !rule.getInit().trim().isEmpty()) {
                List<Object> initItems = analyzer.getElements(rule.getInit());
                if (!initItems.isEmpty()) {
                    Object newContent = initItems.get(0);
                    // JSON 子对象（Map/List）需序列化回 JSON 字符串，确保 JSONPath 可用
                    if (newContent instanceof Map || newContent instanceof List) {
                        try {
                            newContent = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(newContent);
                        } catch (Exception ignored) {
                        }
                    }
                    analyzer.setContent(newContent, url);
                }
            }

            // 5) 提取 name/author/intro/coverUrl/tocUrl/wordCount 等
            Book book = new Book();
            if (rule != null) {
                book.setTitle(safe(analyzer.getString(rule.getName(), null, false)));
                book.setAuthor(safe(analyzer.getString(rule.getAuthor(), null, false)));
                book.setIntro(safe(analyzer.getString(rule.getIntro(), null, false)));
                book.setCategory(safe(analyzer.getString(rule.getKind(), null, false)));
                book.setCover(absoluteUrl(analyzer.getString(rule.getCoverUrl(), null, false)));
                book.setLastChapter(safe(analyzer.getString(rule.getLastChapter(), null, false)));
                book.setUpdateTime(safe(analyzer.getString(rule.getUpdateTime(), null, false)));
                book.setWordCount(parseWordCount(analyzer.getString(rule.getWordCount(), null, false)));
                // tocUrl：为空时兜底为 bookUrl（Legado 约定：目录与详情同页）
                String tocUrl = analyzer.getString(rule.getTocUrl(), null, true);
                if (tocUrl == null || tocUrl.trim().isEmpty()) {
                    tocUrl = bookUrl;
                }
                book.setTocUrl(tocUrl);
            }
            book.setSourceUrl(bookUrl);
            book.setSourceType(baseUrl);
            if (book.getTitle() == null || book.getTitle().isEmpty()) {
                book.setTitle("未知书名");
            }
            return book;
        } catch (Exception e) {
            log.error("[{}] 获取书籍详情失败: {}", sourceName(), e.getMessage(), e);
            return null;
        }
    }

    /* ============================================================
     * 3. 目录
     * ============================================================ */

    /**
     * 获取章节目录列表。
     *
     * @param tocUrl 目录页 URL（可由 {@link #getBookInfo} 的 tocUrl 取得）
     * @return 章节列表（index 递增，已合并 nextTocUrl 翻页）
     */
    public List<Chapter> getChapterList(String tocUrl) {
        checkLoginOrThrow();
        List<Chapter> result = new ArrayList<>();
        try {
            TocRule rule = source.getRuleToc();
            if (rule == null || rule.getChapterList() == null || rule.getChapterList().isEmpty()) {
                return result;
            }

            String currentUrl = tocUrl;
            int index = 0;
            int guard = 0; // 翻页保护，避免异常死循环
            while (guard < 100) {
                // 1) 创建 AnalyzeUrl(tocUrl, ...) 并执行
                AnalyzeRule analyzer = createAnalyzeContext(currentUrl, null, null);
                String response = currentAnalyzeUrl.execute();
                if (response == null) {
                    break;
                }
                String resolvedUrl = currentAnalyzeUrl.getUrl();

                // 2) AnalyzeRule.setContent(响应, url)
                analyzer.setContent(response, resolvedUrl);

                // 3) 用 ruleToc.chapterList 获取列表
                List<Object> items = analyzer.getElements(rule.getChapterList());

                // 4) 对每个 item 提取 chapterName/chapterUrl/isVolume 等
                for (Object item : items) {
                    Chapter ch = new Chapter();
                    ch.setTitle(safe(analyzer.getString(rule.getChapterName(), item, false)));
                    ch.setUrl(absoluteUrl(analyzer.getString(rule.getChapterUrl(), item, false)));
                    ch.setSortOrder(index); // index 递增
                    ch.setIsVolume(parseBool(analyzer.getString(rule.getIsVolume(), item, false)));
                    ch.setUpdateTime(safe(analyzer.getString(rule.getUpdateTime(), item, false)));
                    result.add(ch);
                    index++;
                }

                // 5) 如果有 nextTocUrl，循环获取下一页直到没有更多
                String nextUrl = null;
                if (rule.getNextTocUrl() != null && !rule.getNextTocUrl().trim().isEmpty()) {
                    nextUrl = analyzer.getString(rule.getNextTocUrl(), null, true);
                }
                if (nextUrl == null || nextUrl.trim().isEmpty()
                        || nextUrl.equals(resolvedUrl) || nextUrl.equals(currentUrl)) {
                    break;
                }
                currentUrl = nextUrl;
                guard++;
            }
            log.info("[{}] 目录解析出 {} 章", sourceName(), result.size());
        } catch (Exception e) {
            log.error("[{}] 获取章节列表失败: {}", sourceName(), e.getMessage(), e);
        }
        return result;
    }

    /* ============================================================
     * 4. 正文
     * ============================================================ */

    /**
     * 获取章节正文文本。
     *
     * @param chapterUrl 章节正文页 URL
     * @return 正文文本（已合并 nextContentUrl 翻页，已应用 replaceRegex）
     */
    public String getChapterContent(String chapterUrl) {
        checkLoginOrThrow();
        StringBuilder content = new StringBuilder();
        try {
            ContentRule rule = source.getRuleContent();
            if (rule == null || rule.getContent() == null || rule.getContent().isEmpty()) {
                return "";
            }

            String currentUrl = chapterUrl;
            int guard = 0; // 翻页保护
            // 判断 content 规则是否为 JS 规则（@js: 或 <js>）。
            // JS 规则通常在内部通过 java.ajax() 自行请求真实接口，
            // 此时 chapterUrl 只是承载 chapterId 的伪 URL（如 https://host/17059214170001），
            // 预取该 URL 会 404，但不应阻止 JS 执行。
            boolean contentIsJsRule = isJsRule(rule.getContent());
            while (guard < 50) {
                // 1) 创建 AnalyzeUrl(chapterUrl, ...) 并执行
                AnalyzeRule analyzer = createAnalyzeContext(currentUrl, null, null);
                String response = currentAnalyzeUrl.execute();
                String resolvedUrl = currentAnalyzeUrl.getUrl();
                if (response == null) {
                    // 对于 JS 内容规则，预取失败不中断：JS 会通过 java.ajax() 自行获取正文
                    if (!contentIsJsRule) {
                        break;
                    }
                    response = "";
                }

                // 2) AnalyzeRule.setContent(响应, url)
                // 注入 baseUrl=chapterUrl，使 JS 中 baseUrl.split("/").pop() 能取到 chapterId
                analyzer.setContent(response, resolvedUrl);

                // 3) 用 ruleContent.content 提取正文
                String text = analyzer.getString(rule.getContent(), null, false);
                if (text != null && !text.isEmpty()) {
                    content.append(text);
                }

                // 4) 如果有 nextContentUrl，循环获取下一页内容拼接
                String nextUrl = null;
                if (rule.getNextContentUrl() != null && !rule.getNextContentUrl().trim().isEmpty()) {
                    nextUrl = analyzer.getString(rule.getNextContentUrl(), null, true);
                }
                if (nextUrl == null || nextUrl.trim().isEmpty()
                        || nextUrl.equals(resolvedUrl) || nextUrl.equals(currentUrl)) {
                    break;
                }
                currentUrl = nextUrl;
                guard++;
            }

            // 5) 应用 replaceRegex
            String result = content.toString();
            if (rule.getReplaceRegex() != null && !rule.getReplaceRegex().trim().isEmpty()) {
                result = applyReplaceRegex(result, rule.getReplaceRegex());
            }
            return result;
        } catch (Exception e) {
            log.error("[{}] 获取章节内容失败: {}", sourceName(), e.getMessage(), e);
            return content.toString();
        }
    }

    /* ============================================================
     * 5. 辅助方法
     * ============================================================ */

    /**
     * 解析 JSON 格式请求头为 Map。空/失败返回空 Map。
     * 公开静态方法，供诊断端点等外部调用。
     */
    public static Map<String, String> parseHeaderJson(String headerJson) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headerJson == null || headerJson.trim().isEmpty()) {
            return result;
        }
        try {
            // Legado 书源 header 常用单引号（非标准 JSON），需预处理为双引号
            String normalized = normalizeJsonQuotes(headerJson);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<String, Object> m = MAPPER.readValue(normalized, Map.class);
            if (m != null) {
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (e.getValue() != null) {
                        result.put(e.getKey(), e.getValue().toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 header JSON 失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 将类 JSON 字符串中的单引号转为双引号，使其符合标准 JSON 规范。
     * Legado 书源的 header 字段常用单引号包裹键值（如 {'User-Agent': 'okhttp'}），
     * Jackson 默认不接受单引号，需预处理。
     */
    static String normalizeJsonQuotes(String s) {
        if (s == null || s.isEmpty()) return s;
        // 简单策略：将独立单引号替换为双引号
        // 处理 {'key': 'value'} → {"key": "value"}
        StringBuilder sb = new StringBuilder(s.length());
        boolean inDouble = false;  // 在双引号字符串内
        boolean inSingle = false;  // 在单引号字符串内
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                // 转义字符，原样保留
                sb.append(c);
                sb.append(s.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                sb.append(c);
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                sb.append('"');  // 单引号替换为双引号
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 创建带 JS 引擎的 {@link AnalyzeRule} + {@link AnalyzeUrl} 组合，并互相注入。
     *
     * <p>创建后可通过 {@link #currentAnalyzeUrl} 取得 {@link AnalyzeUrl} 调用 {@code execute()}。
     *
     * @param url  URL 规则串（可带 @js/&lt;js&gt;/{{}}/&lt;page&gt;/,{...}）
     * @param key  搜索关键字（可空）
     * @param page 页码（可空）
     * @return 已注入 JS 引擎的 AnalyzeRule
     */
    private AnalyzeRule createAnalyzeContext(String url, String key, Integer page) {
        Map<String, String> headers = resolveHeaders();
        this.currentAnalyzeUrl = new AnalyzeUrl(url, key, page, baseUrl, headers, !headers.isEmpty());
        AnalyzeRule analyzeRule = new AnalyzeRule();
        // 互相注入 JS 引擎
        currentAnalyzeUrl.setJsEngine(jsEngine);
        analyzeRule.setJsEngine(jsEngine);
        jsEngine.setAnalyzeUrl(currentAnalyzeUrl);
        jsEngine.setAnalyzeRule(analyzeRule);
        return analyzeRule;
    }

    /**
     * 相对 URL 转绝对（基于书源 baseUrl）。
     */
    private String absoluteUrl(String href) {
        if (href == null || href.trim().isEmpty()) {
            return "";
        }
        return AnalyzeUrl.getAbsoluteURL(baseUrl, href);
    }

    /**
     * 判断规则是否为 JS 规则（@js: 前缀或 &lt;js&gt; 标签包裹）。
     * JS 规则通常在内部通过 java.ajax() 自行请求真实接口，
     * 此时传入的 URL 仅作为 baseUrl 参数载体（如承载 chapterId），
     * 不应因预取该 URL 失败而中断正文提取。
     */
    private static boolean isJsRule(String rule) {
        if (rule == null || rule.trim().isEmpty()) {
            return false;
        }
        String trimmed = rule.trim();
        return trimmed.startsWith("@js:") || trimmed.startsWith("<js>")
                || trimmed.toLowerCase().startsWith("@js:")
                || trimmed.toLowerCase().startsWith("<js>");
    }

    /**
     * 关键词二次过滤：title/author/intro 任一包含 keyword（忽略大小写、空白、标点）即保留。
     * 用于防御第三方书源对短词/数字词返回默认榜单。
     */
    private List<Book> searchKeywordFilter(List<Book> books, String keyword) {
        if (books == null || books.isEmpty()) {
            return books;
        }
        if (keyword == null || keyword.trim().isEmpty()) {
            return books;
        }
        String nkw = normalizeMatch(keyword);
        if (nkw.isEmpty()) {
            return books;
        }
        List<Book> out = new ArrayList<>(books.size());
        for (Book b : books) {
            String nt = normalizeMatch(b.getTitle());
            String na = normalizeMatch(b.getAuthor());
            String ni = normalizeMatch(b.getIntro());
            if (nt.contains(nkw) || na.contains(nkw) || ni.contains(nkw)) {
                out.add(b);
            }
        }
        return out;
    }

    private static String normalizeMatch(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[\\p{Punct}\\p{P}\\p{S}]", "")
                .trim();
    }

    /* ============================================================
     * 6. 登录处理
     * ============================================================ */

    /**
     * 执行登录并缓存登录后的请求头。
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录凭证字符串；登录失败或无需登录返回 null
     */
    public String doLogin(String username, String password) {
        String result = loginHandler.doLogin(username, password);
        if (result != null && !result.isEmpty()) {
            cachedLoginHeaders = loginHandler.getLoginHeaders();
        }
        return result;
    }

    /**
     * 在执行请求前检查：若书源需要登录但尚未缓存登录头，抛出 RuntimeException 提示先登录。
     * 放在各公开方法入口、try 块之外，保证登录异常不被业务 catch 吞掉。
     */
    private void checkLoginOrThrow() {
        if (loginHandler.checkLoginNeeded()
                && (cachedLoginHeaders == null || cachedLoginHeaders.isEmpty())) {
            throw new RuntimeException("书源[" + sourceName()
                    + "]需要登录，请先调用 doLogin(username, password) 完成登录");
        }
    }

    /**
     * 合并书源 header 与（若已登录的）登录头，供 AnalyzeUrl 使用。
     * 登录检查已在 {@link #checkLoginOrThrow} 完成，此处不再抛异常。
     */
    private Map<String, String> resolveHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headerMap != null) {
            headers.putAll(headerMap);
        }
        if (loginHandler.checkLoginNeeded() && cachedLoginHeaders != null) {
            headers.putAll(cachedLoginHeaders);
        }
        return headers;
    }

    /* ============================================================
     * 工具方法
     * ============================================================ */

    /**
     * checkKeyWord 关键词匹配：任一 token 被包含即视为通过；为空表示无限制。
     */
    private boolean checkKeywordAllowed(String keyword, String checkKeyWord) {
        if (checkKeyWord == null || checkKeyWord.trim().isEmpty()) {
            return true;
        }
        for (String ck : checkKeyWord.split("[,，|;\\s]+")) {
            String t = ck.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (keyword.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) {
        if (s == null) return "";
        // 部分书源（如七猫）在 name/intro 等字段中嵌入 <font color='#ff4242'>关键词</font>
        // 之类的高亮标签用于 APP 端高亮显示，前端直接展示会暴露原始 HTML。
        // 用 Jsoup 转纯文本：去标签 + 反转义 HTML 实体（&amp; → &）。
        String t = s.trim();
        if (t.isEmpty() || t.indexOf('<') < 0) return t;
        try {
            String text = Jsoup.parse(t).text();
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            return t;
        }
    }

    private static Boolean parseBool(String s) {
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        String t = s.trim().toLowerCase();
        return "true".equals(t) || "1".equals(t) || "yes".equals(t);
    }

    /**
     * 解析字数文本为整数，兼容 "12.5万" / "125000" / "12.5w" 等写法。
     */
    private static Integer parseWordCount(String s) {
        if (s == null || s.trim().isEmpty()) {
            return 0;
        }
        String t = s.trim();
        boolean hasWan = t.contains("万") || t.toLowerCase().contains("w");
        String digits = t.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            double d = Double.parseDouble(digits);
            if (hasWan) {
                d *= 10000;
            }
            return (int) d;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 应用正文净化正则（replaceRegex）。
     *
     * <p>支持两种格式：
     * <ul>
     *   <li>JSON 数组 {@code [{"regex":"...","replacement":"..."}]}</li>
     *   <li>字符串 {@code regex##replacement}，多组用 {@code &&} 连接</li>
     * </ul>
     */
    private String applyReplaceRegex(String content, String replaceRegex) {
        if (replaceRegex == null || replaceRegex.trim().isEmpty()) {
            return content;
        }
        String result = content;
        String trimmed = replaceRegex.trim();

        // 1) JSON 数组格式
        if (trimmed.startsWith("[")) {
            try {
                List<?> arr = MAPPER.readValue(trimmed, List.class);
                for (Object o : arr) {
                    if (!(o instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> m = (Map<?, ?>) o;
                    String regex = m.get("regex") == null ? null : m.get("regex").toString();
                    String replacement = m.get("replacement") == null ? "" : m.get("replacement").toString();
                    if (regex != null && !regex.isEmpty()) {
                        try {
                            result = Pattern.compile(regex).matcher(result).replaceAll(replacement);
                        } catch (Exception e) {
                            log.warn("[{}] replaceRegex 正则错误: {}", sourceName(), regex);
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                log.warn("[{}] replaceRegex JSON 解析失败，按字符串规则处理: {}",
                        sourceName(), e.getMessage());
            }
        }

        // 2) "regex##replacement" 格式（&& 连接多组）
        for (String pair : trimmed.split("&&")) {
            String[] parts = pair.split(Pattern.quote("##"), -1);
            String regex = parts[0].trim();
            String replacement = parts.length > 1 ? parts[1] : "";
            if (regex.isEmpty()) {
                continue;
            }
            try {
                result = Pattern.compile(regex).matcher(result).replaceAll(replacement);
            } catch (Exception e) {
                log.warn("[{}] replaceRegex 正则错误: {}", sourceName(), regex);
            }
        }
        return result;
    }

    private String sourceName() {
        return source.getBookSourceName();
    }

    /* ============================================================
     * 5. 发现/榜单（explore）
     * ============================================================ */

    /**
     * 发现页书籍。
     *
     * <ol>
     *   <li>优先走 Legado 原生 {@link LegadoBookSource#exploreUrl} + {@code ruleExplore} 规则</li>
     *   <li>若无原生配置（或解析失败），回退为「热门搜索」：按候选关键词执行 {@link #searchBooks}，
     *     将结果拼接后按 size 截断，用于展示「分类/榜单」推荐书籍</li>
     * </ol>
     *
     * @param page 页码（从 1 开始）
     * @param size 每页数量
     */
    public List<Book> exploreBooks(int page, int size) {
        return exploreBooks(page, size, null);
    }

    /**
     * 发现页/榜单：支持按分类加载。
     * <p>
     * 解析策略：
     * 1. 有分类 + 有 ruleExplore → 用分类 URL + ruleExplore 解析（书源原生书城）
     * 2. 无分类 + 有 exploreUrl + 有 ruleExplore → 用 exploreUrl + ruleExplore 解析（书源原生书城）
     * 3. 有 exploreUrl + 无 ruleExplore → 用 exploreUrl + 搜索规则解析（从书城首页提取）
     * 4. 以上都失败 → 返回空列表（不再使用热词搜索兜底）
     *
     * @param page 页码（1-based）
     * @param size 每页数量
     * @param category 分类名称（对应 exploreUrl 中解析出的分类）；null/空=加载第一个分类
     */
    public List<Book> exploreBooks(int page, int size, String category) {
        checkLoginOrThrow();
        List<Book> result = new ArrayList<>();
        try {
            // —— 方案 A：有分类 + 有 ruleExplore.bookList → 原生规则解析 ——
            List<String[]> categories = getExploreCategories();
            LegadoBookSource.ExploreRule rule = source.getExploreRuleAsObject();
            boolean hasExploreRule = rule != null
                    && rule.getBookList() != null && !rule.getBookList().isEmpty();

            if (!categories.isEmpty() && hasExploreRule) {
                try {
                    // 找到指定分类对应的 URL；未匹配时取第一个分类
                    String targetUrl = categories.get(0)[1];
                    if (category != null && !category.trim().isEmpty()) {
                        for (String[] cat : categories) {
                            if (category.equals(cat[0])) { targetUrl = cat[1]; break; }
                        }
                    }
                    result.addAll(parseExploreWithRule(targetUrl, rule, page, size));
                } catch (Exception ex) {
                    log.warn("[{}] 原生 explore 解析失败: {}", sourceName(), ex.getMessage());
                }
            } else if (!categories.isEmpty()) {
                // —— 方案 B：有分类但无 ruleExplore.bookList → 用分类 URL + ruleSearch 解析 ——
                // 典型场景：猫眼看书等书源 exploreUrl 为 JSON 数组（含分类 title+url），
                // ruleExplore 为空对象 {}，但 ruleSearch 可用于解析各分类页面返回的数据
                try {
                    String targetUrl = categories.get(0)[1];
                    if (category != null && !category.trim().isEmpty()) {
                        for (String[] cat : categories) {
                            if (category.equals(cat[0])) { targetUrl = cat[1]; break; }
                        }
                    }
                    log.info("[{}] exploreUrl 有分类但无 ruleExplore，用 ruleSearch 解析分类 URL: {}",
                            sourceName(), targetUrl);
                    result.addAll(parseExploreWithSearchRule(targetUrl, page, size));
                } catch (Exception ex) {
                    log.warn("[{}] 分类 URL + searchRule 解析失败: {}", sourceName(), ex.getMessage());
                }
            } else {
                // —— 方案 C：exploreUrl 是单条 URL，尝试用 ruleExplore 或搜索规则解析 ——
                String exploreUrl = source.getExploreUrl();
                if (exploreUrl != null && !exploreUrl.trim().isEmpty()) {
                    if (hasExploreRule) {
                        // 有 ruleExplore，直接用原生规则解析
                        try {
                            result.addAll(parseExploreWithRule(exploreUrl, rule, page, size));
                        } catch (Exception ex) {
                            log.warn("[{}] exploreUrl + ruleExplore 解析失败: {}", sourceName(), ex.getMessage());
                        }
                    } else {
                        // 无 ruleExplore，尝试用搜索规则解析 exploreUrl 返回的数据
                        try {
                            result.addAll(parseExploreWithSearchRule(exploreUrl, page, size));
                        } catch (Exception ex) {
                            log.warn("[{}] exploreUrl + searchRule 解析失败: {}", sourceName(), ex.getMessage());
                        }
                    }
                }
            }

            // 返回结果（不再使用热词搜索兜底）
            if (!result.isEmpty()) {
                return truncate(result, size);
            }
            log.info("[{}] exploreBooks 无可用数据，返回空列表", sourceName());
            return result;
        } catch (Exception e) {
            log.error("[{}] exploreBooks 失败: {}", sourceName(), e.getMessage(), e);
            return result;
        }
    }

    /**
     * 解析 exploreUrl 字段，返回分类列表。
     * Legado exploreUrl 格式支持：
     * - JSON 数组：[{"title":"分类","url":"url"}, ...] 或 [{"name":"分类","url":"url"}, ...]
     * - 每行一条：分类名称::url
     * - 换行符 \n 或 && 分隔
     * 注意：url 为空的条目（分类标题/分隔符）会被过滤；url 中的 backtick 包裹会被去除。
     * @return List of String[]{categoryName, categoryUrl}
     */
    public List<String[]> getExploreCategories() {
        List<String[]> categories = new ArrayList<>();
        String exploreUrl = source.getExploreUrl();
        if (exploreUrl == null || exploreUrl.trim().isEmpty()) {
            return categories;
        }
        String trimmed = exploreUrl.trim();

        // 尝试 JSON 数组解析
        if (trimmed.startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                        // 兼容 title 和 name 两种字段名
                        String name = node.has("title") ? node.get("title").asText() :
                                (node.has("name") ? node.get("name").asText() : "");
                        String url  = node.has("url")  ? node.get("url").asText()  : "";
                        // 去除 backtick 包裹（Legado 用 `url` 标记原始 URL）
                        if (url.startsWith("`") && url.endsWith("`")) {
                            url = url.substring(1, url.length() - 1);
                        } else if (url.startsWith("`")) {
                            url = url.substring(1);
                        }
                        // 过滤空 URL（分类标题/分隔符，如"男频""女频""出版图书"）
                        if (!name.isEmpty() && !url.trim().isEmpty()) {
                            categories.add(new String[]{name, url.trim()});
                        }
                    }
                    if (!categories.isEmpty()) return categories;
                }
            } catch (Exception ignored) {}
        }

        // 文本格式解析：每行或 && 分隔，格式 "分类名称::url"
        String[] lines = trimmed.split("\\n|&&|\\\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // 支持 "名称::url" 和 "名称:url" 两种分隔符
            int sepIdx = line.indexOf("::");
            if (sepIdx < 0) sepIdx = line.indexOf(":");
            if (sepIdx > 0 && sepIdx < line.length() - 1) {
                // 用 "::" 优先，避免 url 中的 ":" 被截断
                String name = line.substring(0, sepIdx).trim();
                String url;
                if (line.indexOf("::") == sepIdx) {
                    url = line.substring(sepIdx + 2).trim();
                } else {
                    // 只有一个 ":"，但 url 中也有 "://"，需要找到第一个不属于 "://" 的位置
                    // 简单处理：如果 ":" 后紧跟 "//"，说明是 url scheme，继续找下一个
                    int nextColon = line.indexOf(':', sepIdx + 1);
                    // 如果格式是 "name::http://..."，上面已处理
                    // 如果格式是 "name:http://..."，sepIdx 就是第一个 ":"
                    // url 从 "http" 开始，所以 name=sepIdx 之前，url=sepIdx+1 之后
                    url = line.substring(sepIdx + 1).trim();
                }
                if (!name.isEmpty() && !url.isEmpty()) {
                    categories.add(new String[]{name, url});
                }
            }
        }
        return categories;
    }

    /** 使用原生 exploreUrl + ruleExplore 解析榜单 */
    private List<Book> parseExploreWithRule(String exploreUrl,
                                             LegadoBookSource.ExploreRule rule,
                                             int page, int size) throws Exception {
        // {{page}} 由 AnalyzeUrl.replaceKeyPageJs() 统一替换；{{size}} 非标准变量手动替换
        String url = exploreUrl.replace("{{size}}", String.valueOf(size));

        log.info("[{}] parseExploreWithRule url={}, page={}, size={}", sourceName(), url, page, size);

        AnalyzeRule analyzer = createAnalyzeContext(url, null, page);

        String bookListRule = rule.getBookList();
        boolean isJsBookList = bookListRule != null
                && (bookListRule.contains("<js>") || bookListRule.startsWith("@js:"));

        log.info("[{}] bookListRule isJs={}, ruleLength={}", sourceName(), isJsBookList,
                bookListRule != null ? bookListRule.length() : 0);

        if (isJsBookList) {
            // JS bookList：JS 会通过 java.ajax() 自行获取 API 数据，
            // 无需直接请求 exploreUrl（它可能只是参数载体，如 baidu.com 假地址）。
            // 设置非空 dummy content 和 baseUrl，让 getElements 不跳过 JS 执行。
            // baseUrl 会被 evalJS 注入 JS 引擎，JS 用 baseUrl.match(...) 提取参数。
            analyzer.setContent("{}", url);
            log.info("[{}] JS bookList: setContent with baseUrl={}", sourceName(), url);
        } else {
            // 非 JS bookList：直接请求 URL 并用响应作为 content
            log.info("[{}] Non-JS bookList: executing URL directly", sourceName());
            String response = currentAnalyzeUrl.execute();
            if (response == null || response.isEmpty()) {
                log.warn("[{}] explore URL response is empty", sourceName());
                return new ArrayList<>();
            }
            log.info("[{}] explore URL response length={}", sourceName(), response.length());
            String realUrl = currentAnalyzeUrl.getUrl();
            analyzer.setContent(response, realUrl);
        }

        log.info("[{}] Calling getElements...", sourceName());
        List<Object> items = analyzer.getElements(rule.getBookList());
        log.info("[{}] explore 规则命中 {} 条", sourceName(), items.size());

        List<Book> result = new ArrayList<>();
        for (Object item : items) {
            String name = analyzer.getString(rule.getName(), item, false);
            if (name == null || name.trim().isEmpty()) continue;
            Book book = new Book();
            book.setTitle(safe(name));
            book.setAuthor(safe(analyzer.getString(rule.getAuthor(), item, false)));
            book.setIntro(safe(analyzer.getString(rule.getIntro(), item, false)));
            book.setCategory(safe(analyzer.getString(rule.getKind(), item, false)));
            book.setCover(absoluteUrl(analyzer.getString(rule.getCoverUrl(), item, false)));
            book.setLastChapter(safe(analyzer.getString(rule.getLastChapter(), item, false)));
            book.setUpdateTime(safe(analyzer.getString(rule.getUpdateTime(), item, false)));
            book.setWordCount(parseWordCount(analyzer.getString(rule.getWordCount(), item, false)));
            String bookUrl = analyzer.getString(rule.getBookUrl(), item, false);
            // 去除 Legado JS 中 `url` 语法的 backtick 包裹
            if (bookUrl != null) {
                bookUrl = bookUrl.replace("`", "");
            }
            // bookUrl 可能是 @js: 规则生成的完整 URL（含 ,{headers}），不需要 absoluteUrl
            if (bookUrl != null && !bookUrl.isEmpty()) {
                if (bookUrl.startsWith("http") || bookUrl.startsWith("data:")) {
                    // 完整 URL，直接使用
                } else if (bookUrl.startsWith("/")) {
                    bookUrl = absoluteUrl(bookUrl);
                } else {
                    bookUrl = absoluteUrl(bookUrl);
                }
            }
            book.setSourceUrl(bookUrl);
            book.setTocUrl(bookUrl);
            result.add(book);
        }
        log.info("[{}] parseExploreWithRule returning {} books", sourceName(), result.size());
        return result;
    }

    /**
     * 使用搜索规则解析 exploreUrl 返回的数据。
     * 当书源没有配置 ruleExplore 时，复用 ruleSearch 来解析 explore 页面的书籍列表。
     */
    private List<Book> parseExploreWithSearchRule(String exploreUrl, int page, int size) throws Exception {
        SearchRule searchRule = source.getRuleSearch();
        if (searchRule == null || searchRule.getBookList() == null || searchRule.getBookList().isEmpty()) {
            log.warn("[{}] 无 ruleSearch 或 bookList 为空，无法用搜索规则解析 explore", sourceName());
            return new ArrayList<>();
        }

        // {{page}} 由 AnalyzeUrl.replaceKeyPageJs() 统一替换（支持 {{page-1}} 等 JS 表达式）
        // {{size}} 不是 Legado 标准变量，手动替换
        String url = exploreUrl.replace("{{size}}", String.valueOf(size));

        AnalyzeRule analyzer = createAnalyzeContext(url, null, page);
        log.info("[{}] explore+searchRule 解析后URL: {}", sourceName(), currentAnalyzeUrl.getUrl());
        String response = currentAnalyzeUrl.execute();
        if (response == null || response.isEmpty()) {
            log.warn("[{}] exploreUrl 响应为空", sourceName());
            return new ArrayList<>();
        }
        log.info("[{}] explore+searchRule 响应长度={} 预览={}", sourceName(), response.length(),
                response.length() < 500 ? response : response.substring(0, 500) + "...");
        String realUrl = currentAnalyzeUrl.getUrl();
        analyzer.setContent(response, realUrl);

        List<Object> items = analyzer.getElements(searchRule.getBookList());
        log.info("[{}] explore + searchRule 命中 {} 条", sourceName(), items.size());
        List<Book> result = new ArrayList<>();
        for (Object item : items) {
            String name = analyzer.getString(searchRule.getName(), item, false);
            if (name == null || name.trim().isEmpty()) continue;
            Book book = new Book();
            book.setTitle(safe(name));
            book.setAuthor(safe(analyzer.getString(searchRule.getAuthor(), item, false)));
            book.setIntro(safe(analyzer.getString(searchRule.getIntro(), item, false)));
            book.setCategory(safe(analyzer.getString(searchRule.getKind(), item, false)));
            book.setCover(absoluteUrl(analyzer.getString(searchRule.getCoverUrl(), item, false)));
            book.setLastChapter(safe(analyzer.getString(searchRule.getLastChapter(), item, false)));
            book.setUpdateTime(safe(analyzer.getString(searchRule.getUpdateTime(), item, false)));
            book.setWordCount(parseWordCount(analyzer.getString(searchRule.getWordCount(), item, false)));
            String bookUrl = absoluteUrl(analyzer.getString(searchRule.getBookUrl(), item, false));
            book.setSourceUrl(bookUrl);
            book.setTocUrl(bookUrl);
            result.add(book);
        }
        return result;
    }

    /** 根据不同书源返回一组「热门关键词」用作 explore 兜底榜单；不同题材侧重不同。 */
    private String[] getFallbackExploreKeywords() {
        String name = source.getBookSourceName() == null ? "" : source.getBookSourceName();
        String type = source.getBookSourceTypeStr();
        // 外站小说源（七猫、夜伴书屋等）使用小说热词
        if (name.contains("七猫") || name.contains("书屋") || type.startsWith("CUSTOM")
                || name.contains("小说")) {
            return new String[]{"斗破苍穹", "万相之王", "斗罗大陆", "凡人修仙传", "宿命之环", "诡秘之主"};
        }
        // 古籍/公版源（国学大师）
        if (name.contains("国学")) {
            return new String[]{"西游记", "红楼梦", "三国演义", "水浒传", "论语", "诗经", "史记"};
        }
        return new String[]{"小说", "历史", "文学"};
    }

    private static List<Book> truncate(List<Book> list, int size) {
        if (list.size() <= size || size <= 0) return list;
        return new ArrayList<>(list.subList(0, size));
    }
}
