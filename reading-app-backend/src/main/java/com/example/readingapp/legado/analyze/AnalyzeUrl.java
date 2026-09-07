package com.example.readingapp.legado.analyze;

import com.example.readingapp.util.HttpFetcher;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legado AnalyzeUrl.kt 的 Java 移植版 —— 搜索URL规则解析器。
 *
 * <p>解析书源中带 URL 选项的规则字符串（{@code @js:xxx} / {@code <js>xxx</js>} 脚本、
 * {@code {{...}}} 内嵌规则、{@code <page1,page2,...>} 页码占位符、{@code ,{...}} JSON 选项等），
 * 最终拼装成可执行的 HTTP 请求。
 *
 * <p>JS 引擎暂以占位方式实现（{@link #evalJS(String, Object)} 直接返回原 result），
 * 后续接入 LegadoJsEngine 后再注入实际执行逻辑。
 */
@Slf4j
public class AnalyzeUrl {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 匹配 @js:xxx 与 &lt;js&gt;xxx&lt;/js&gt;；group(1)=@js 内容，group(2)=&lt;js&gt; 内容 */
    private static final Pattern JS_PATTERN = Pattern.compile(
            "@js:([\\s\\S]*?)(?=[\\r\\n]+ *-|@js:|$)|<js>([\\s\\S]*?)</js>",
            Pattern.CASE_INSENSITIVE);

    /** URL 与 JSON 选项的分隔符：逗号后紧跟 { */
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\s*,\\s*(?=\\{)");

    /** 页码占位符 &lt;page1,page2,...&gt; */
    private static final Pattern PAGE_PATTERN = Pattern.compile("<(.*?)>");

    // ============================== 字段 ==============================

    private final String mUrl;
    private final String key;
    private final Integer page;
    private String baseUrl;
    private final boolean hasLoginHeader;

    /** 原始规则 URL（带 @js/&lt;js&gt;/{{}} 等占位符的原始串） */
    private String ruleUrl = "";
    /** 最终用于 HTTP 请求的 URL */
    private String url = "";
    private String type;
    private String body;
    private String urlNoQuery = "";
    private String encodedForm;
    private String encodedQuery;
    private String charset;
    private String method = "GET";
    private String proxy;
    private int retry = 0;
    private boolean useWebView = false;
    private String webJs;
    private Long serverID;
    private String domain;

    /** 请求头（LinkedHashMap 保持插入顺序，外部可直接读写） */
    public final LinkedHashMap<String, String> headerMap = new LinkedHashMap<>();

    /** 变量存储 */
    private final Map<String, String> variables = new LinkedHashMap<>();

    // ============================== 构造 ==============================

    /**
     * @param mUrl          搜索/详情/正文 URL 规则串（可带 @js/&lt;js&gt;/{{}}/&lt;page&gt;/,{...})
     * @param key           当前搜索关键字（可空）
     * @param page          当前页码（1-based，可空）
     * @param baseUrl       书源 baseUrl（可空，可附带 ,{...} 选项）
     * @param headerMapF   外部传入的请求头（可空）
     * @param hasLoginHeader 是否含登录头（占位，本实现未使用）
     */
    public AnalyzeUrl(String mUrl, String key, Integer page, String baseUrl,
                      Map<String, String> headerMapF, boolean hasLoginHeader) {
        this.mUrl = mUrl == null ? "" : mUrl;
        this.key = key;
        this.page = page;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.hasLoginHeader = hasLoginHeader;

        // baseUrl 中可能带 ,{...} JSON 选项，剥离掉（与 kotlin init 块一致）
        Matcher urlMatcher = PARAM_PATTERN.matcher(this.baseUrl);
        if (urlMatcher.find()) {
            this.baseUrl = this.baseUrl.substring(0, urlMatcher.start());
        }

        // 合并外部传入的 headerMapF；proxy 单独提取
        if (headerMapF != null) {
            headerMap.putAll(headerMapF);
            if (headerMap.containsKey("proxy")) {
                proxy = headerMap.get("proxy");
                headerMap.remove("proxy");
            }
        }

        // 注意：此处不立即 initUrl()。因为 @js:/<js> 段需要 jsEngine 已注入才能执行，
        // 而 jsEngine 通常在构造之后才通过 setJsEngine 注入。
        // initUrl() 的执行延迟到 init()，由 setJsEngine 或 execute() 兜底触发。
        // domain 在 initUrl() 末尾根据最终 url 设置，此处不提前赋值（构造时 url 尚未解析）
    }

    // ============================== 处理 URL 主流程 ==============================

    /** 是否已完成 URL 解析（避免重复执行带副作用的 JS） */
    private boolean initialized = false;

    /**
     * 触发 URL 解析（执行 JS → 替换 key/page → 解析 URL 选项）。
     * 幂等：多次调用只会真正执行一次。
     */
    public void init() {
        if (initialized) {
            return;
        }
        initUrl();
        initialized = true;
    }

    /** 处理 url：执行 JS → 替换 key/page → 解析 URL 选项 */
    public void initUrl() {
        ruleUrl = mUrl;
        analyzeJs();
        replaceKeyPageJs();
        analyzeUrl();
        domain = extractDomain(this.url);
    }

    /**
     * 执行 @js:xxx / &lt;js&gt;xxx&lt;/js&gt;
     *
     * <p>逐段执行，前段执行结果通过 {@code @result} 占位符注入下一段。
     */
    private void analyzeJs() {
        int start = 0;
        Matcher jsMatcher = JS_PATTERN.matcher(ruleUrl);
        String result = ruleUrl;
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                String seg = ruleUrl.substring(start, jsMatcher.start()).trim();
                if (!seg.isEmpty()) {
                    result = seg.replace("@result", result);
                }
            }
            String jsCode = jsMatcher.group(2);
            if (jsCode == null) {
                jsCode = jsMatcher.group(1);
            }
            if (jsCode == null) {
                jsCode = "";
            }
            Object r = evalJS(jsCode, result);
            result = (r == null) ? "" : r.toString();
            start = jsMatcher.end();
        }
        if (ruleUrl.length() > start) {
            String seg = ruleUrl.substring(start).trim();
            if (!seg.isEmpty()) {
                result = seg.replace("@result", result);
            }
        }
        ruleUrl = result;
    }

    /**
     * 替换内嵌 {{js}} 与页码占位 &lt;page&gt;
     *
     * <p>先替换内嵌规则再替换页数规则，避免内嵌规则中存在大于小于号时被切错。
     */
    private void replaceKeyPageJs() {
        // 1) 替换 {{...}} 内嵌规则（evalJS 暂返回原 result）
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            RuleAnalyzer analyze = new RuleAnalyzer(ruleUrl);
            String replaced = analyze.innerRule("{{", "}}", it -> {
                Object jsEval = evalJS(it, null);
                if (jsEval == null) {
                    return "";
                }
                if (jsEval instanceof Double) {
                    Double d = (Double) jsEval;
                    if (d % 1.0 == 0.0) {
                        return String.format("%.0f", d);
                    }
                }
                return jsEval.toString();
            });
            if (replaced != null && !replaced.isEmpty()) {
                ruleUrl = replaced;
            }
        }
        // 2) 替换页码占位 <page1,page2,...>
        if (page != null) {
            Matcher matcher = PAGE_PATTERN.matcher(ruleUrl);
            StringBuilder sb = new StringBuilder();
            boolean found = false;
            while (matcher.find()) {
                found = true;
                String[] pages = matcher.group(1).split(",");
                String replacement;
                if (page >= 1 && page < pages.length) {
                    replacement = pages[page - 1].trim();
                } else if (pages.length > 0) {
                    replacement = pages[pages.length - 1].trim();
                } else {
                    replacement = "";
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            if (found) {
                matcher.appendTail(sb);
                ruleUrl = sb.toString();
            }
        }
    }

    /**
     * 解析 URL：剥离 ,{...} JSON 选项，提取 method/headers/body/charset/type/retry 等
     */
    @SuppressWarnings("unchecked")
    private void analyzeUrl() {
        Matcher urlMatcher = PARAM_PATTERN.matcher(ruleUrl);
        boolean hasOptions = urlMatcher.find();
        String urlNoOption = hasOptions ? ruleUrl.substring(0, urlMatcher.start()) : ruleUrl;

        url = getAbsoluteURL(baseUrl, urlNoOption);
        String newBase = getBaseUrl(url);
        if (newBase != null && !newBase.isEmpty()) {
            baseUrl = newBase;
        }

        if (hasOptions) {
            String urlOptionStr = ruleUrl.substring(urlMatcher.end());
            UrlOption option = UrlOption.fromJson(urlOptionStr);
            if (option != null) {
                if (option.method != null && option.method.equalsIgnoreCase("POST")) {
                    method = "POST";
                }
                if (option.headerMap != null) {
                    for (Map.Entry<?, ?> entry : option.headerMap.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            headerMap.put(entry.getKey().toString(), entry.getValue().toString());
                        }
                    }
                }
                if (option.body != null) {
                    body = option.body;
                }
                type = option.type;
                charset = option.charset;
                retry = option.retry;
                useWebView = option.useWebView;
                webJs = option.webJs;
                if (option.js != null) {
                    Object r = evalJS(option.js, url);
                    if (r != null) {
                        url = r.toString();
                    }
                }
                serverID = option.serverID;
            }
        }

        urlNoQuery = url;
        if ("GET".equals(method)) {
            int pos = url.indexOf('?');
            if (pos != -1) {
                analyzeQuery(url.substring(pos + 1));
                urlNoQuery = url.substring(0, pos);
            }
        } else if ("POST".equals(method) && body != null) {
            String trimmed = body.trim();
            boolean isJson = trimmed.startsWith("{") || trimmed.startsWith("[");
            boolean isXml = trimmed.startsWith("<?xml") || trimmed.startsWith("<");
            String contentType = headerMap.get("Content-Type");
            if (!isJson && !isXml && (contentType == null || contentType.isEmpty())) {
                analyzeFields(body);
            }
        }
    }

    private void analyzeFields(String fieldsTxt) {
        encodedForm = encodeParams(fieldsTxt, charset, false);
    }

    private void analyzeQuery(String query) {
        encodedQuery = encodeParams(query, charset, true);
    }

    /**
     * 参数编码（移植自 Legado AnalyzeUrl.encodeParams）
     *
     * @param params  原始参数字符串
     * @param charset 字符集；空表示 UTF-8（同时启用已编码检测），"escape" 表示用 escape() 编码
     * @param isQuery 兼容旧调用签名，新实现统一按 {@code &} 切分 + 按 {@code =} 拆 k/v 逐段编码
     */
    private String encodeParams(String params, String charset, boolean isQuery) {
        boolean checkEncoded = charset == null || charset.isEmpty();
        Charset cs;
        if (charset == null || charset.isEmpty()) {
            cs = StandardCharsets.UTF_8;
        } else if ("escape".equals(charset)) {
            cs = null;
        } else {
            try {
                cs = Charset.forName(charset);
            } catch (Exception e) {
                cs = StandardCharsets.UTF_8;
            }
        }
        // 已编码（含合法 %XX 且无未编码特殊字符）直接返回，避免二次编码
        if (isQuery && cs != null && encodedQuery(params)) {
            return params;
        }
        // 按 & 切分 → 按 = 拆 k/v → 分别 URLEncoder.encode
        // 注意：必须按段切分编码，否则 URLEncoder.encode(整体) 会把 =、& 也编码掉
        // 例如 "id=210863&sign=xxx" 整体编码会变成 "id%3D210863%26sign%3Dxxx"
        // 服务端按 query 解析参数失败 → 七猫详情返回 401 Unauthorized
        int len = params.length();
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos <= len) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            int ampOffset = params.indexOf("&", pos);
            if (ampOffset == -1) {
                ampOffset = len;
            }
            int eqOffset = params.indexOf("=", pos);
            String keyStr;
            String value = null;
            if (eqOffset == -1 || eqOffset > ampOffset) {
                keyStr = params.substring(pos, ampOffset);
            } else {
                keyStr = params.substring(pos, eqOffset);
                value = params.substring(eqOffset + 1, ampOffset);
            }
            appendEncoded(sb, keyStr, checkEncoded, cs);
            if (value != null) {
                sb.append("=");
                appendEncoded(sb, value, checkEncoded, cs);
            }
            pos = ampOffset + 1;
        }
        return sb.toString();
    }

    private void appendEncoded(StringBuilder sb, String value, boolean checkEncoded, Charset cs) {
        if (checkEncoded && encodedForm(value)) {
            sb.append(value);
        } else if (cs == null) {
            sb.append(escape(value));
        } else {
            try {
                sb.append(URLEncoder.encode(value, cs));
            } catch (Exception e) {
                sb.append(value);
            }
        }
    }

    /**
     * 执行 JS（暂留空，返回原 result）
     *
     /* ============================== JS 引擎（延迟注入） ============================== */

    private com.example.readingapp.legado.js.LegadoJsEngine jsEngine;

    public void setJsEngine(com.example.readingapp.legado.js.LegadoJsEngine jsEngine) {
        this.jsEngine = jsEngine;
        // jsEngine 注入完成后再触发 init()，使 @js:/<js> 段能由 jsEngine 执行。
        // 幂等：若已初始化，init() 内部会跳过。
        init();
    }

    /**
     * 执行 JS 表达式。
     * 如果已注入 jsEngine，委托给它执行；否则返回原 result（占位行为）。
     *
     * <p>注入 Legado 规范的全局变量：{@code key}/{@code keyword}/{@code searchKey}（搜索关键字）、
     * {@code page}（页码）、{@code baseUrl}，供脚本中 {@code qmSearchUrl.call(this,key,page)} 等调用使用。
     */
    public Object evalJS(String jsStr, Object result) {
        if (jsEngine == null) return result;
        try {
            Map<String, Object> bindings = new LinkedHashMap<>();
            if (key != null) {
                bindings.put("key", key);
                // Legado 兼容别名：keyword / searchKey
                bindings.put("keyword", key);
                bindings.put("searchKey", key);
            }
            if (page != null) {
                bindings.put("page", page);
            }
            bindings.put("baseUrl", baseUrl);
            return jsEngine.eval(jsStr, result, bindings);
        } catch (Exception e) {
            log.warn("AnalyzeUrl evalJS 失败: {}", e.getMessage());
            return result;
        }
    }

    // ============================== 变量系统 ==============================

    public String put(String key, String value) {
        if (key == null) {
            return value == null ? "" : value;
        }
        if ("bookName".equals(key) || "title".equals(key)) {
            log.warn("≡变量 {} 在特定情况下会被覆盖，建议使用其他键名", key);
        }
        variables.put(key, value == null ? "" : value);
        return value == null ? "" : value;
    }

    public String get(String key) {
        if (key == null) {
            return "";
        }
        String v = variables.get(key);
        return v == null ? "" : v;
    }

    // ============================== HTTP 执行 ==============================

    /**
     * 执行 HTTP 请求，返回响应字符串。
     *
     * <p>合并 {@link #headerMap}，构造 {@link HttpFetcher.RequestConfig} 调用 {@link HttpFetcher#fetch}。
     * <ul>
     *   <li>GET：把 {@link #encodedQuery} 拼回 {@code urlNoQuery + "?" + query}</li>
     *   <li>POST + encodedForm：以 application/x-www-form-urlencoded 提交</li>
     *   <li>POST + body：以原 body 提交（未设置 Content-Type 时 HttpFetcher 默认 application/json）</li>
     * </ul>
     */
    public String execute() {
        // 兜底：若调用者未显式 init()（如 LegadoJsEngine.ajax / LoginHandler 直接构造后 execute），
        // 在此处触发一次，保证 url/urlNoQuery/encodedQuery 等字段已就绪。
        init();
        setCookie();
        HttpFetcher.RequestConfig cfg = new HttpFetcher.RequestConfig(urlNoQuery);
        cfg.method = method;
        cfg.headers = new LinkedHashMap<>(headerMap);
        cfg.charset = charset;

        if ("POST".equalsIgnoreCase(method)) {
            if (encodedForm != null && !encodedForm.isEmpty()) {
                cfg.body = encodedForm;
                cfg.headers.put("Content-Type", "application/x-www-form-urlencoded");
            } else if (body != null && !body.isEmpty()) {
                cfg.body = body;
                // Content-Type 未设置时 HttpFetcher 默认 application/json
            }
        } else {
            // GET：把 encodedQuery 拼回 URL
            if (encodedQuery != null && !encodedQuery.isEmpty()) {
                cfg.url = urlNoQuery + "?" + encodedQuery;
            }
        }

        HttpFetcher fetcher = new HttpFetcher();
        return fetcher.fetch(cfg);
    }

    /**
     * 设置 Cookie：合并 CookieStore（暂未实现）与 headerMap["Cookie"]。
     *
     * <p>优先级：urlOption 临时 cookie &gt; 数据库 cookie。
     * 项目暂无 CookieStore，保留方法骨架供后续注入。
     */
    private void setCookie() {
        // 实现后逻辑：
        //   String stored = CookieStore.getCookie(domain);
        //   if (stored != null && !stored.isEmpty()) {
        //       String existing = headerMap.get("Cookie");
        //       headerMap.put("Cookie", mergeCookies(stored, existing));
        //   }
    }

    /**
     * 合并两个 Cookie 字符串：b 中的同名项覆盖 a 中的值。
     */
    private static String mergeCookies(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : a.split(";")) {
            applyCookiePair(map, pair);
        }
        for (String pair : b.split(";")) {
            applyCookiePair(map, pair);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey());
            if (!e.getValue().isEmpty()) {
                sb.append("=").append(e.getValue());
            }
        }
        return sb.toString();
    }

    private static void applyCookiePair(Map<String, String> map, String pair) {
        pair = pair.trim();
        if (pair.isEmpty()) {
            return;
        }
        int eq = pair.indexOf('=');
        if (eq >= 0) {
            map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        } else {
            map.put(pair, "");
        }
    }

    // ============================== URL 工具 ==============================

    /**
     * 相对 URL 转绝对 URL（复用 Legado NetworkUtils.getAbsoluteURL 逻辑）
     * <ul>
     *   <li>http/data/blob/file 开头原样返回</li>
     *   <li>// 开头加 https:</li>
     *   <li>/ 开头拼到 baseUrl 的 host 段（去掉 path）</li>
     *   <li>其他拼到 baseUrl 最后一个 / 之后</li>
     * </ul>
     */
    public static String getAbsoluteURL(String baseUrl, String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        if (u.isEmpty()) {
            return "";
        }
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (u.startsWith("http") || u.startsWith("data:") || u.startsWith("blob:")
                || u.startsWith("file:")) {
            return u;
        }
        if (u.startsWith("//")) {
            return "https:" + u;
        }
        if (u.startsWith("/")) {
            // 在 baseUrl 中找第三个斜杠（即 host 之后的第一个 /），跳过 "https://" 前缀 8 字符
            int idx = baseUrl.indexOf("/", 9);
            if (idx == -1) {
                return baseUrl + u;
            }
            return baseUrl.substring(0, idx) + u;
        }
        int idx = baseUrl.lastIndexOf("/");
        if (idx > 9) {
            return baseUrl.substring(0, idx + 1) + u;
        }
        if (baseUrl.endsWith("/")) {
            return baseUrl + u;
        }
        return baseUrl + "/" + u;
    }

    /**
     * 提取 base URL（去掉路径部分）：http://host:port
     */
    public static String getBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        int idx = url.indexOf("/", 9);
        return idx == -1 ? url : url.substring(0, idx);
    }

    /**
     * 从 URL 中提取主域名（用于 cookie 域匹配）
     */
    private static String extractDomain(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            String u = url;
            if (u.startsWith("https://")) {
                u = u.substring(8);
            } else if (u.startsWith("http://")) {
                u = u.substring(7);
            }
            int slash = u.indexOf('/');
            if (slash > 0) {
                u = u.substring(0, slash);
            }
            int colon = u.indexOf(':');
            if (colon > 0) {
                u = u.substring(0, colon);
            }
            String[] parts = u.split("\\.");
            if (parts.length >= 2) {
                return parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
            return u;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 检测 query 是否已编码（包含合法 %XX 且不含未编码的空格等不安全字符）
     */
    private static boolean encodedQuery(String params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        boolean hasPercent = false;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '%') {
                if (i + 2 < params.length()
                        && isHex(params.charAt(i + 1))
                        && isHex(params.charAt(i + 2))) {
                    hasPercent = true;
                } else {
                    return false;
                }
            } else if (c == ' ' || c == '"' || c == '{' || c == '}' || c == '|'
                    || c == '\\' || c == '^' || c == '[' || c == ']' || c == '`') {
                return false;
            }
        }
        return hasPercent;
    }

    /**
     * 检测表单字段值是否已编码
     */
    private static boolean encodedForm(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        boolean hasPercent = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 < value.length()
                        && isHex(value.charAt(i + 1))
                        && isHex(value.charAt(i + 2))) {
                    hasPercent = true;
                } else {
                    return false;
                }
            } else if (c == ' ') {
                return false;
            }
        }
        return hasPercent;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * JavaScript escape() 简化实现：用于 charset="escape" 模式
     * <p>ASCII 字母数字与 {@code @*_-+./} 不编码；其他字符 &lt; 256 用 %XX，&gt;= 256 用 %uXXXX。
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '@' || c == '*' || c == '_' || c == '-' || c == '+' || c == '.'
                    || c == '/') {
                sb.append(c);
            } else if (c < 256) {
                sb.append('%');
                sb.append(toHex(c / 16));
                sb.append(toHex(c % 16));
            } else {
                sb.append("%u");
                sb.append(toHex(c >> 12));
                sb.append(toHex((c >> 8) & 0xF));
                sb.append(toHex((c >> 4) & 0xF));
                sb.append(toHex(c & 0xF));
            }
        }
        return sb.toString();
    }

    private static char toHex(int n) {
        return (char) (n < 10 ? '0' + n : 'A' + n - 10);
    }

    // ============================== Getter ==============================

    public String getRuleUrl() {
        return ruleUrl;
    }

    public String getUrl() {
        return url;
    }

    public String getType() {
        return type;
    }

    public String getMethod() {
        return method;
    }

    public String getCharset() {
        return charset;
    }

    public String getBody() {
        return body;
    }

    public String getUrlNoQuery() {
        return urlNoQuery;
    }

    public String getEncodedForm() {
        return encodedForm;
    }

    public String getEncodedQuery() {
        return encodedQuery;
    }

    public int getRetry() {
        return retry;
    }

    public boolean isUseWebView() {
        return useWebView;
    }

    public String getWebJs() {
        return webJs;
    }

    public Long getServerID() {
        return serverID;
    }

    public String getDomain() {
        return domain;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getKey() {
        return key;
    }

    public Integer getPage() {
        return page;
    }

    public boolean isPost() {
        return "POST".equals(method);
    }

    // ============================== UrlOption ==============================

    /**
     * URL 选项对象，从 URL 后的 {@code ,{...}} JSON 中解析。
     *
     * <p>容错处理：空串归 null，解析失败 {@link #fromJson} 返回 null。
     */
    public static class UrlOption {
        public String method;
        public String charset;
        public Map<String, ?> headerMap;
        public String body;
        public String type;
        public int retry;
        public boolean useWebView;
        public String webJs;
        public String js;
        public Long serverID;

        /**
         * 从 JSON 字符串解析为 UrlOption，容错处理（解析失败返回 null）
         */
        @SuppressWarnings("unchecked")
        public static UrlOption fromJson(String json) {
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            try {
                Map<String, Object> map = MAPPER.readValue(json, Map.class);
                if (map == null) {
                    return null;
                }
                UrlOption opt = new UrlOption();
                opt.method = strOrBlank(map.get("method"));
                opt.charset = strOrBlank(map.get("charset"));

                Object headers = map.get("headers");
                if (headers instanceof Map) {
                    opt.headerMap = (Map<String, ?>) headers;
                } else if (headers instanceof String) {
                    try {
                        opt.headerMap = MAPPER.readValue((String) headers, Map.class);
                    } catch (Exception ignore) {
                        opt.headerMap = null;
                    }
                }

                Object body = map.get("body");
                if (body == null) {
                    opt.body = null;
                } else if (body instanceof String) {
                    String s = (String) body;
                    opt.body = s.trim().isEmpty() ? null : s;
                } else {
                    // JSON 对象/数组 → 重新序列化为字符串
                    try {
                        opt.body = MAPPER.writeValueAsString(body);
                    } catch (Exception ignore) {
                        opt.body = body.toString();
                    }
                }

                opt.type = strOrBlank(map.get("type"));
                opt.retry = intOrZero(map.get("retry"));
                opt.useWebView = parseBool(map.get("webView"));
                opt.webJs = strOrBlank(map.get("webJs"));
                opt.js = strOrBlank(map.get("js"));

                Object sid = map.get("serverID");
                if (sid == null) {
                    sid = map.get("serverId");
                }
                opt.serverID = longOrNull(sid);
                return opt;
            } catch (Exception e) {
                log.warn("UrlOption 解析失败: {}", e.getMessage());
                return null;
            }
        }

        private static String strOrBlank(Object o) {
            if (o == null) {
                return null;
            }
            String s = o.toString();
            return s.trim().isEmpty() ? null : s;
        }

        private static int intOrZero(Object o) {
            if (o == null) {
                return 0;
            }
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (Exception e) {
                return 0;
            }
        }

        private static boolean parseBool(Object o) {
            if (o == null) {
                return false;
            }
            if (o instanceof Boolean) {
                return (Boolean) o;
            }
            String s = o.toString();
            // 与 kotlin 一致：null/""/false/"false" 为 false，其余为 true
            return !s.isEmpty() && !"false".equalsIgnoreCase(s);
        }

        private static Long longOrNull(Object o) {
            if (o == null) {
                return null;
            }
            String s = o.toString().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(s);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
