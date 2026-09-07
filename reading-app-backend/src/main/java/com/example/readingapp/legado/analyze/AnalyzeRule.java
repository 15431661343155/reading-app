package com.example.readingapp.legado.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legado AnalyzeRule.kt 的 Java 移植版——核心规则解析器。
 *
 * <p>按规则串逐步执行 XPath/Json/CSS/JS/Regex 解析，支持 @put 变量保存、
 * @get/{{}} 内嵌求值、$N 引用、## 正则替换等复合规则。
 */
@Slf4j
public class AnalyzeRule {

    /* ==================== 模式枚举 ==================== */
    public enum Mode {
        XPath, Json, Default, Js, Regex
    }

    /* ==================== 常量正则（对应 Kotlin companion object） ==================== */
    /** @put:{...} 抽取，与 Kotlin 一致（CASE_INSENSITIVE） */
    private static final Pattern PUT_PATTERN =
            Pattern.compile("@put:(\\{[^}]+?\\})", Pattern.CASE_INSENSITIVE);
    /** @get:{...} 与 {{...}} 抽取 */
    private static final Pattern EVAL_PATTERN =
            Pattern.compile("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", Pattern.CASE_INSENSITIVE);
    /** $1..$99 引用抽取 */
    private static final Pattern REGEX_PATTERN = Pattern.compile("\\$\\d{1,2}");
    /**
     * @js: xxx | &lt;js&gt;xxx&lt;/js&gt; 切分。
     * 采用 Legado 源码 AppPattern.JS_PATTERN 原始定义：
     * {@code <js>([\w\W]*?)</js>|@js:([\w\W]*)}
     * group(1) = &lt;js&gt; 内容，group(2) = @js: 内容。
     * 调用处 {@code group(2) ?: group(1)} 与 Kotlin 用法一致。
     */
    public static final Pattern JS_PATTERN = Pattern.compile(
            "<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)",
            Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* ==================== 当前上下文 ==================== */
    private Object content = null;
    private String baseUrl = null;
    private URL redirectUrl = null;
    private boolean isJSON = false;
    private boolean isRegex = false;

    private AnalyzeByXPath analyzeByXPath = null;
    private AnalyzeByJSoup analyzeByJSoup = null;
    private AnalyzeByJSonPath analyzeByJSonPath = null;

    /** getString 类规则缓存 */
    private final HashMap<String, List<SourceRule>> stringRuleCache = new HashMap<>();
    /** 单段规则缓存（用于 makeUpRule 中 {{}} 内嵌求值） */
    private final HashMap<String, List<SourceRule>> singleRuleCache = new HashMap<>();
    /** 编译过的 Pattern 缓存 */
    private final HashMap<String, Pattern> regexCache = new HashMap<>();

    /** 临时变量存储（对应 Kotlin put/get 的简化版） */
    private final HashMap<String, String> variables = new HashMap<>();

    /* ==================== setContent / setBaseUrl ==================== */

    public AnalyzeRule setContent(Object content, String baseUrl) {
        if (content == null) {
            throw new AssertionError("内容不可空（Content cannot be null）");
        }
        this.content = content;
        this.isJSON = !(content instanceof Node) && isJson(content.toString());
        setBaseUrl(baseUrl);
        this.analyzeByXPath = null;
        this.analyzeByJSoup = null;
        this.analyzeByJSonPath = null;
        return this;
    }

    public AnalyzeRule setContent(Object content) {
        return setContent(content, null);
    }

    public AnalyzeRule setBaseUrl(String baseUrl) {
        if (baseUrl != null) {
            this.baseUrl = baseUrl;
        }
        return this;
    }

    public URL setRedirectUrl(String url) {
        if (url == null || url.isEmpty()) return redirectUrl;
        if (isDataUrl(url)) {
            return redirectUrl;
        }
        try {
            redirectUrl = new URL(url);
        } catch (Exception e) {
            log.warn("URL({}) error\n{}", url, e.getLocalizedMessage());
        }
        return redirectUrl;
    }

    /* ==================== 解析器懒加载（参考 Kotlin getAnalyzeByXxx） ==================== */

    private AnalyzeByXPath getAnalyzeByXPath(Object o) {
        if (o != content) {
            return new AnalyzeByXPath(o);
        }
        if (analyzeByXPath == null) {
            analyzeByXPath = new AnalyzeByXPath(content);
        }
        return analyzeByXPath;
    }

    private AnalyzeByJSoup getAnalyzeByJSoup(Object o) {
        if (o != content) {
            return new AnalyzeByJSoup(o);
        }
        if (analyzeByJSoup == null) {
            analyzeByJSoup = new AnalyzeByJSoup(content);
        }
        return analyzeByJSoup;
    }

    private AnalyzeByJSonPath getAnalyzeByJSonPath(Object o) {
        if (o != content) {
            return new AnalyzeByJSonPath(o);
        }
        if (analyzeByJSonPath == null) {
            analyzeByJSonPath = new AnalyzeByJSonPath(content);
        }
        return analyzeByJSonPath;
    }

    /* ==================== getString ==================== */

    public String getString(String ruleStr, Object mContent, boolean isUrl) {
        if (ruleStr == null || ruleStr.isEmpty()) return "";
        List<SourceRule> ruleList = splitSourceRuleCacheString(ruleStr);
        return getString(ruleList, mContent, isUrl, true);
    }

    public String getString(String ruleStr) {
        return getString(ruleStr, null, false);
    }

    public String getString(String ruleStr, boolean unescape) {
        if (ruleStr == null || ruleStr.isEmpty()) return "";
        List<SourceRule> ruleList = splitSourceRuleCacheString(ruleStr);
        return getString(ruleList, null, false, unescape);
    }

    public String getString(List<SourceRule> ruleList, Object mContent, boolean isUrl, boolean unescape) {
        Object result = null;
        Object content = mContent != null ? mContent : this.content;
        if (content != null && !ruleList.isEmpty()) {
            result = content;
            for (SourceRule sourceRule : ruleList) {
                putRule(sourceRule.putMap);
                sourceRule.makeUpRule(result);
                if (result == null) continue;
                String rule = sourceRule.rule;
                // Kotlin: rule.isNotBlank() || sourceRule.replaceRegex.isEmpty()
                // isNotBlank 等价于 !trim().isEmpty()，空白串视为空
                if (rule != null && (!rule.trim().isEmpty() || sourceRule.replaceRegex.isEmpty())) {
                    switch (sourceRule.mode) {
                        case Js:
                            result = evalJS(rule, result);
                            break;
                        case Json:
                            result = getAnalyzeByJSonPath(result).getString(rule);
                            break;
                        case XPath:
                            result = getAnalyzeByXPath(result).getString(rule);
                            break;
                        case Default:
                            // isUrl 时用 getString0（返回首个匹配，空时返回 ""）
                            if (isUrl) {
                                result = getAnalyzeByJSoup(result).getString0(rule);
                            } else {
                                result = getAnalyzeByJSoup(result).getString(rule);
                            }
                            break;
                        default:
                            result = rule;
                            break;
                    }
                }
                if (result != null && !sourceRule.replaceRegex.isEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule);
                }
            }
        }
        if (result == null) result = "";
        String resultStr = result.toString();
        String str = (unescape && resultStr.indexOf('&') > -1)
                ? Parser.unescapeEntities(resultStr, false)
                : resultStr;
        if (isUrl) {
            if (str == null || str.trim().isEmpty()) {
                return baseUrl != null ? baseUrl : "";
            }
            // 去除 Legado JS 中 `url` 语法的 backtick 包裹
            str = str.replace("`", "");
            return getAbsoluteURL(redirectUrl, str);
        }
        return str;
    }

    /* ==================== getStringList ==================== */

    public List<String> getStringList(String rule, Object mContent, boolean isUrl) {
        if (rule == null || rule.isEmpty()) return null;
        List<SourceRule> ruleList = splitSourceRuleCacheString(rule);
        return getStringList(ruleList, mContent, isUrl);
    }

    public List<String> getStringList(String rule) {
        return getStringList(rule, null, false);
    }

    public List<String> getStringList(List<SourceRule> ruleList, Object mContent, boolean isUrl) {
        Object result = null;
        Object content = mContent != null ? mContent : this.content;
        if (content != null && !ruleList.isEmpty()) {
            result = content;
            for (SourceRule sourceRule : ruleList) {
                putRule(sourceRule.putMap);
                sourceRule.makeUpRule(result);
                if (result == null) continue;
                String rule = sourceRule.rule;
                if (rule != null && !rule.isEmpty()) {
                    switch (sourceRule.mode) {
                        case Js:
                            result = evalJS(rule, result);
                            break;
                        case Json:
                            result = getAnalyzeByJSonPath(result).getStringList(rule);
                            break;
                        case XPath:
                            result = getAnalyzeByXPath(result).getStringList(rule);
                            break;
                        case Default:
                            result = getAnalyzeByJSoup(result).getStringList(rule);
                            break;
                        default:
                            result = rule;
                            break;
                    }
                }
                if (result instanceof List && !sourceRule.replaceRegex.isEmpty()) {
                    List<?> list = (List<?>) result;
                    ArrayList<String> newList = new ArrayList<>(list.size());
                    for (Object item : list) {
                        newList.add(replaceRegex(item == null ? "" : item.toString(), sourceRule));
                    }
                    result = newList;
                } else if (result != null && !sourceRule.replaceRegex.isEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule);
                }
            }
        }
        if (result == null) return null;
        if (result instanceof String) {
            result = splitByNewline((String) result);
        }
        if (isUrl) {
            ArrayList<String> urlList = new ArrayList<>();
            if (result instanceof List) {
                for (Object o : (List<?>) result) {
                    String u = o == null ? "" : o.toString();
                    String abs = getAbsoluteURL(redirectUrl, u);
                    if (abs != null && !abs.isEmpty() && !urlList.contains(abs)) {
                        urlList.add(abs);
                    }
                }
            }
            return urlList;
        }
        if (result instanceof List) {
            ArrayList<String> ret = new ArrayList<>(((List<?>) result).size());
            for (Object o : (List<?>) result) {
                ret.add(o == null ? null : o.toString());
            }
            return ret;
        }
        return null;
    }

    /* ==================== getElement / getElements ==================== */

    public Object getElement(String ruleStr) {
        if (ruleStr == null || ruleStr.isEmpty()) return null;
        Object result = null;
        Object content = this.content;
        List<SourceRule> ruleList = splitSourceRule(ruleStr, true);
        if (content != null && !ruleList.isEmpty()) {
            result = content;
            for (SourceRule sourceRule : ruleList) {
                putRule(sourceRule.putMap);
                sourceRule.makeUpRule(result);
                if (result == null) continue;
                String rule = sourceRule.rule;
                switch (sourceRule.mode) {
                    case Regex:
                        result = AnalyzeByRegex.getElement(result.toString(), splitNotBlank(rule, "&&"));
                        break;
                    case Js:
                        result = evalJS(rule, result);
                        break;
                    case Json:
                        result = getAnalyzeByJSonPath(result).getObject(rule);
                        break;
                    case XPath:
                        result = getAnalyzeByXPath(result).getElements(rule);
                        break;
                    default:
                        result = getAnalyzeByJSoup(result).getElements(rule);
                        break;
                }
                if (!sourceRule.replaceRegex.isEmpty() && result != null) {
                    result = replaceRegex(result.toString(), sourceRule);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getElements(String ruleStr) {
        Object result = null;
        Object content = this.content;
        List<SourceRule> ruleList = splitSourceRule(ruleStr, true);
        if (content != null && !ruleList.isEmpty()) {
            result = content;
            for (SourceRule sourceRule : ruleList) {
                putRule(sourceRule.putMap);
                if (result == null) continue;
                String rule = sourceRule.rule;
                switch (sourceRule.mode) {
                    case Regex:
                        result = AnalyzeByRegex.getElements(result.toString(), splitNotBlank(rule, "&&"));
                        break;
                    case Js:
                        result = evalJS(rule, result);
                        break;
                    case Json:
                        result = getAnalyzeByJSonPath(result).getList(rule);
                        break;
                    case XPath:
                        result = getAnalyzeByXPath(result).getElements(rule);
                        break;
                    default:
                        result = getAnalyzeByJSoup(result).getElements(rule);
                        break;
                }
            }
        }
        if (result instanceof List) {
            return (List<Object>) result;
        }
        return new ArrayList<>();
    }

    /* ==================== 变量系统 put / get ==================== */

    public String put(String key, String value) {
        if (key == null) return value;
        if ("bookName".equals(key) || "title".equals(key)) {
            log.debug("≡变量 {} 在特定情况下会被覆盖，建议使用其他键名", key);
        }
        String v = value != null ? value : "";
        variables.put(key, v);
        // Also sync to JsEngine for cross-call persistence (e.g., @put:{bid:id} in ruleBookInfo
        // needs to be available via java.get('bid') in ruleContent.content which runs in a
        // different AnalyzeRule instance).
        if (jsEngine != null) {
            jsEngine.putVariable(key, v);
        }
        return value;
    }

    public String get(String key) {
        if (key == null) return "";
        String v = variables.get(key);
        if (v == null && jsEngine != null) {
            v = jsEngine.getVariable(key);
        }
        return v != null ? v : "";
    }

    /* ==================== JS 引擎（延迟注入） ==================== */

    private com.example.readingapp.legado.js.LegadoJsEngine jsEngine;

    public void setJsEngine(com.example.readingapp.legado.js.LegadoJsEngine jsEngine) {
        this.jsEngine = jsEngine;
    }

    /**
     * 执行 JS 表达式。
     * 如果已注入 jsEngine，委托给它执行；否则返回 null。
     */
    public Object evalJS(String jsStr, Object result) {
        if (jsEngine == null) return null;
        try {
            // 将 AnalyzeRule 的 baseUrl（当前页面 URL）注入 JS 上下文，覆盖引擎的静态 baseUrl。
            // 对齐 Legado 原版行为：JS 中 baseUrl = 当前页 URL（如 tocUrl / 详情页 URL），
            // 而非书源 baseUrl。七猫 ruleToc.chapterUrl 用 baseUrl.match(/id=(\d+)/) 提取书 ID 依赖此行为。
            Map<String, Object> bindings = new java.util.HashMap<>();
            if (this.baseUrl != null) {
                bindings.put("baseUrl", this.baseUrl);
            }
            return jsEngine.eval(jsStr, result, bindings);
        } catch (Exception e) {
            log.warn("AnalyzeRule evalJS 失败: {}", e.getMessage());
            return null;
        }
    }

    /* ==================== 内部辅助：putRule / splitPutRule / replaceRegex ==================== */

    /**
     * 保存 @put 变量：对每个 value 调用 getString 求值后保存。
     */
    private void putRule(Map<String, String> map) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            put(entry.getKey(), getString(entry.getValue()));
        }
    }

    /**
     * 分离 @put:{...} 规则，提取键值对到 putMap，返回移除 @put 后的规则串。
     */
    private String splitPutRule(String ruleStr, HashMap<String, String> putMap) {
        String vRuleStr = ruleStr;
        Matcher putMatcher = PUT_PATTERN.matcher(vRuleStr);
        while (putMatcher.find()) {
            String matched = putMatcher.group();
            vRuleStr = vRuleStr.replace(matched, "");
            String putJsonStr = putMatcher.group(1);
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Map<String, Object> putJson = MAPPER.readValue(putJsonStr, Map.class);
                if (putJson != null) {
                    for (Map.Entry<String, Object> e : putJson.entrySet()) {
                        Object v = e.getValue();
                        putMap.put(e.getKey(), v == null ? "" : v.toString());
                    }
                }
            } catch (Exception e) {
                // Jackson 无法解析时，回退到 Legado 原生简化格式 @put:{key:value,key2:value2}
                // （key/value 无引号，value 是规则串，会被 putRule -> getString 求值）。
                // 典型用例：七猫 ruleBookInfo.name = "title@put:{bid:id}"
                try {
                    String inner = putJsonStr.substring(1, putJsonStr.length() - 1).trim();
                    if (!inner.isEmpty()) {
                        for (String pair : inner.split(",")) {
                            int colon = pair.indexOf(':');
                            if (colon > 0) {
                                String k = pair.substring(0, colon).trim();
                                String v = pair.substring(colon + 1).trim();
                                if (!k.isEmpty()) {
                                    putMap.put(k, v);
                                }
                            }
                        }
                    }
                } catch (Exception e2) {
                    log.debug("解析 @put 简化格式失败: {} err={}", putJsonStr, e2.getMessage());
                }
            }
        }
        return vRuleStr;
    }

    /**
     * 正则替换（对应 Kotlin replaceRegex）。
     */
    private String replaceRegex(String result, SourceRule rule) {
        if (rule.replaceRegex.isEmpty()) return result;
        String replaceRegex = rule.replaceRegex;
        String replacement = rule.replacement;
        Pattern regex = compileRegexCache(replaceRegex);
        if (rule.replaceFirst) {
            /* ##match##replace### 获取第一个匹配到的结果并进行替换 */
            if (regex != null) {
                try {
                    Matcher matcher = regex.matcher(result);
                    if (matcher.find()) {
                        String group0 = matcher.group(0);
                        // 在匹配到的子串中替换首个匹配（等价于 Kotlin group0.replaceFirst(regex, replacement)）
                        Matcher groupMatcher = regex.matcher(group0);
                        return groupMatcher.replaceFirst(replacement);
                    } else {
                        return "";
                    }
                } catch (Exception e) {
                    return replacement;
                }
            }
            return replacement;
        } else {
            /* ##match##replace 替换 */
            if (regex != null) {
                try {
                    return regex.matcher(result).replaceAll(replacement);
                } catch (Exception e) {
                    // fall through to literal replace
                }
            }
            return result.replace(replaceRegex, replacement);
        }
    }

    private Pattern compileRegexCache(String regex) {
        Pattern cached = regexCache.get(regex);
        if (cached != null) {
            return cached;
        }
        try {
            Pattern p = Pattern.compile(regex);
            if (regexCache.size() < 16) {
                regexCache.put(regex, p);
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== 规则切分 ==================== */

    /**
     * getString 类规则缓存（对应 Kotlin splitSourceRuleCacheString）。
     */
    private List<SourceRule> splitSourceRuleCacheString(String ruleStr) {
        if (ruleStr == null || ruleStr.isEmpty()) return new ArrayList<>();
        List<SourceRule> cached = stringRuleCache.get(ruleStr);
        if (cached != null) {
            return cached;
        }
        List<SourceRule> list = splitSourceRule(ruleStr, false);
        stringRuleCache.put(ruleStr, list);
        return list;
    }

    /**
     * 分解规则生成规则列表（对应 Kotlin splitSourceRule）。
     *
     * @param ruleStr  规则串
     * @param allInOne 当 true 且 ruleStr 首字符为 ':' 时进入 Regex 模式
     * @return SourceRule 列表
     */
    public List<SourceRule> splitSourceRule(String ruleStr, boolean allInOne) {
        List<SourceRule> ruleList = new ArrayList<>();
        if (ruleStr == null || ruleStr.isEmpty()) return ruleList;
        Mode mMode = Mode.Default;
        int start = 0;
        // 仅首字符为 : 时为 AllInOne，其实 : 与伪类选择器冲突，建议改成 ? 更合理
        if (allInOne && ruleStr.startsWith(":")) {
            mMode = Mode.Regex;
            isRegex = true;
            start = 1;
        } else if (isRegex) {
            mMode = Mode.Regex;
        }
        String tmp;
        Matcher jsMatcher = JS_PATTERN.matcher(ruleStr);
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                tmp = ruleStr.substring(start, jsMatcher.start()).trim();
                if (!tmp.isEmpty()) {
                    ruleList.add(new SourceRule(tmp, mMode));
                }
            }
            // group(2) = @js: 内容，group(1) = <js> 内容
            String js = jsMatcher.group(2);
            if (js == null) js = jsMatcher.group(1);
            ruleList.add(new SourceRule(js == null ? "" : js, Mode.Js));
            start = jsMatcher.end();
        }
        if (ruleStr.length() > start) {
            tmp = ruleStr.substring(start).trim();
            if (!tmp.isEmpty()) {
                ruleList.add(new SourceRule(tmp, mMode));
            }
        }
        return ruleList;
    }

    public List<SourceRule> splitSourceRule(String ruleStr) {
        return splitSourceRule(ruleStr, false);
    }

    private List<SourceRule> getOrCreateSingleSourceRule(String rule) {
        List<SourceRule> cached = singleRuleCache.get(rule);
        if (cached != null) {
            return cached;
        }
        List<SourceRule> list = new ArrayList<>();
        list.add(new SourceRule(rule));
        if (singleRuleCache.size() < 16) {
            singleRuleCache.put(rule, list);
        }
        return list;
    }

    /* ==================== 工具方法 ==================== */

    /**
     * 将相对 URL 解析为绝对 URL（对应 Kotlin NetworkUtils.getAbsoluteURL）。
     */
    private String getAbsoluteURL(URL redirectUrl, String url) {
        if (url == null || url.trim().isEmpty()) return "";
        if (url.startsWith("//")) {
            String scheme = redirectUrl != null ? redirectUrl.getProtocol() : "https";
            return scheme + ":" + url;
        }
        try {
            URL base = redirectUrl;
            if (base == null && baseUrl != null && !baseUrl.isEmpty()) {
                base = new URL(baseUrl);
            }
            if (base != null) {
                return new URL(base, url).toString();
            }
            return new URL(url).toString();
        } catch (Exception e) {
            return url;
        }
    }

    private static boolean isDataUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("data:");
    }

    private static boolean isJson(String str) {
        if (str == null || str.isEmpty()) return false;
        String s = str.trim();
        if (s.isEmpty()) return false;
        char first = s.charAt(0);
        if (first != '{' && first != '[') return false;
        try {
            JsonNode node = MAPPER.readTree(s);
            return node != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 按分隔符切分并去除空白段（对应 Kotlin splitNotBlank）。
     */
    private static List<String> splitNotBlank(String str, String sep) {
        List<String> result = new ArrayList<>();
        if (str == null || str.isEmpty()) return result;
        String[] parts = str.split(Pattern.quote(sep));
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * 按换行切分字符串（对应 Kotlin result.split("\n")）。
     */
    private static List<String> splitByNewline(String s) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = s.indexOf('\n', start)) >= 0) {
            result.add(s.substring(start, idx));
            start = idx + 1;
        }
        result.add(s.substring(start));
        return result;
    }

    /* ==================== SourceRule 内部类（非 static） ==================== */

    /**
     * 规则类（对应 Kotlin inner class SourceRule）。
     * 非静态内部类，可访问外部 AnalyzeRule 实例的 isJSON、getString、evalJS、get 等成员。
     */
    public class SourceRule {

        private static final int GET_RULE_TYPE = -2;
        private static final int JS_RULE_TYPE = -1;
        private static final int DEFAULT_RULE_TYPE = 0;

        Mode mode;
        String rule;
        String replaceRegex = "";
        String replacement = "";
        boolean replaceFirst = false;
        final HashMap<String, String> putMap = new HashMap<>();
        private final ArrayList<String> ruleParam = new ArrayList<>();
        private final ArrayList<Integer> ruleType = new ArrayList<>();

        SourceRule(String ruleStr) {
            this(ruleStr, Mode.Default);
        }

        SourceRule(String ruleStr, Mode mode) {
            this.mode = mode;
            // 1) 前缀判定 mode 并截取 rule
            if (mode == Mode.Js || mode == Mode.Regex) {
                rule = ruleStr;
            } else if (ruleStr.regionMatches(true, 0, "@CSS:", 0, 5)) {
                this.mode = Mode.Default;
                rule = ruleStr;
            } else if (ruleStr.startsWith("@@")) {
                this.mode = Mode.Default;
                rule = ruleStr.substring(2);
            } else if (ruleStr.regionMatches(true, 0, "@XPath:", 0, 7)) {
                this.mode = Mode.XPath;
                rule = ruleStr.substring(7);
            } else if (ruleStr.regionMatches(true, 0, "@Json:", 0, 6)) {
                this.mode = Mode.Json;
                rule = ruleStr.substring(6);
            } else if (isJSON || ruleStr.startsWith("$.") || ruleStr.startsWith("$[")) {
                this.mode = Mode.Json;
                rule = ruleStr;
            } else if (ruleStr.startsWith("/")) {
                // XPath 特征很明显，无需配置单独的识别标头
                this.mode = Mode.XPath;
                rule = ruleStr;
            } else {
                rule = ruleStr;
            }
            // 2) 分离 @put
            rule = splitPutRule(rule, putMap);
            // 3) @get:{}, {{}} 拆分
            int start = 0;
            String tmp;
            Matcher evalMatcher = EVAL_PATTERN.matcher(rule);

            if (evalMatcher.find()) {
                tmp = rule.substring(start, evalMatcher.start());
                if (this.mode != Mode.Js && this.mode != Mode.Regex
                        && (evalMatcher.start() == 0 || !tmp.contains("##"))) {
                    this.mode = Mode.Regex;
                }
                do {
                    if (evalMatcher.start() > start) {
                        tmp = rule.substring(start, evalMatcher.start());
                        splitRegex(tmp);
                    }
                    tmp = evalMatcher.group();
                    if (tmp.regionMatches(true, 0, "@get:", 0, 5)) {
                        // @get:{key} → 提取 key
                        ruleType.add(GET_RULE_TYPE);
                        ruleParam.add(tmp.substring(6, tmp.length() - 1));
                    } else if (tmp.startsWith("{{")) {
                        // {{js}} → 提取 js
                        ruleType.add(JS_RULE_TYPE);
                        ruleParam.add(tmp.substring(2, tmp.length() - 2));
                    } else {
                        splitRegex(tmp);
                    }
                    start = evalMatcher.end();
                } while (evalMatcher.find());
            }
            if (rule.length() > start) {
                tmp = rule.substring(start);
                splitRegex(tmp);
            }
        }

        /**
         * 拆分 \$\d{1,2}（$N 引用）。
         * 注意：regexMatcher 在 ruleStrArray[0]（## 之前）上匹配，
         * 但 substring 在完整 ruleStr 上操作（保留 ## 后续处理）。
         */
        private void splitRegex(String ruleStr) {
            int start = 0;
            String tmp;
            String[] ruleStrArray = ruleStr.split(Pattern.quote("##"));
            Matcher regexMatcher = REGEX_PATTERN.matcher(ruleStrArray[0]);

            if (regexMatcher.find()) {
                if (mode != Mode.Js && mode != Mode.Regex) {
                    mode = Mode.Regex;
                }
                do {
                    if (regexMatcher.start() > start) {
                        tmp = ruleStr.substring(start, regexMatcher.start());
                        ruleType.add(DEFAULT_RULE_TYPE);
                        ruleParam.add(tmp);
                    }
                    tmp = regexMatcher.group();
                    ruleType.add(Integer.parseInt(tmp.substring(1)));
                    ruleParam.add(tmp);
                    start = regexMatcher.end();
                } while (regexMatcher.find());
            }
            if (ruleStr.length() > start) {
                tmp = ruleStr.substring(start);
                ruleType.add(DEFAULT_RULE_TYPE);
                ruleParam.add(tmp);
            }
        }

        /**
         * 替换 @get, {{ }}（从后往前拼装最终 rule 串）。
         */
        public void makeUpRule(Object result) {
            StringBuilder infoVal = new StringBuilder();
            if (!ruleParam.isEmpty()) {
                int index = ruleParam.size();
                while (index-- > 0) {
                    int regType = ruleType.get(index);
                    if (regType > DEFAULT_RULE_TYPE) {
                        // $N 引用：从 result 列表取第 N 项
                        if (result instanceof List) {
                            List<?> list = (List<?>) result;
                            if (list.size() > regType) {
                                Object item = list.get(regType);
                                if (item != null) {
                                    infoVal.insert(0, item.toString());
                                } else {
                                    infoVal.insert(0, ruleParam.get(index));
                                }
                            }
                            // size <= regType 时不插入（与 Kotlin 行为一致）
                        } else {
                            infoVal.insert(0, ruleParam.get(index));
                        }
                    } else if (regType == JS_RULE_TYPE) {
                        // {{js}}：判断是规则还是 JS
                        if (isRule(ruleParam.get(index))) {
                            List<SourceRule> ruleList = getOrCreateSingleSourceRule(ruleParam.get(index));
                            // 用当前 result 作为 mContent，使 {{$.id}} 这种内嵌规则在
                            // 当前 item 范围内解析，而非在整个响应里查找（修复七猫
                            // bookUrl=@js:qmBookDetailUrl.call(this,{{$.id}}) 中 $.id 为空导致
                            // JS 退化为 qmBookDetailUrl.call(this,) 的报错）。
                            String s = getString(ruleList, result, false, true);
                            infoVal.insert(0, s);
                        } else {
                            Object jsEval = evalJS(ruleParam.get(index), result);
                            if (jsEval == null) {
                                // do nothing
                            } else if (jsEval instanceof String) {
                                infoVal.insert(0, jsEval);
                            } else if (jsEval instanceof Double) {
                                double d = (Double) jsEval;
                                if (d % 1.0 == 0.0) {
                                    infoVal.insert(0, String.format(Locale.ROOT, "%.0f", d));
                                } else {
                                    infoVal.insert(0, jsEval.toString());
                                }
                            } else {
                                infoVal.insert(0, jsEval.toString());
                            }
                        }
                    } else if (regType == GET_RULE_TYPE) {
                        // @get:{key}
                        infoVal.insert(0, get(ruleParam.get(index)));
                    } else {
                        // 默认：直接插入规则文本
                        infoVal.insert(0, ruleParam.get(index));
                    }
                }
                rule = infoVal.toString();
            }
            // 分离正则表达式 ##
            String[] ruleStrS = rule.split(Pattern.quote("##"), -1);
            rule = ruleStrS[0].trim();
            if (ruleStrS.length > 1) {
                replaceRegex = ruleStrS[1];
            }
            if (ruleStrS.length > 2) {
                replacement = ruleStrS[2];
            }
            if (ruleStrS.length > 3) {
                replaceFirst = true;
            }
        }

        /**
         * 判断字符串是否为规则（而非 JS）。
         * js 首个字符不可能是 @，除非是装饰器，所以 @ 开头规定为规则。
         */
        private boolean isRule(String ruleStr) {
            return ruleStr.startsWith("@")
                    || ruleStr.startsWith("$.")
                    || ruleStr.startsWith("$[")
                    || ruleStr.startsWith("//");
        }

        public int getParamSize() {
            return ruleParam.size();
        }
    }

    /* ==================== AnalyzeByRegex 静态嵌套类 ==================== */

    /**
     * 正则解析器（对应 Kotlin object AnalyzeByRegex）。
     * 作为私有静态嵌套类内嵌于 AnalyzeRule，避免创建独立文件。
     */
    private static class AnalyzeByRegex {

        static List<Object> getElements(String src, List<String> regexArray) {
            List<Object> result = new ArrayList<>();
            for (String regex : regexArray) {
                try {
                    Pattern pattern = Pattern.compile(regex);
                    Matcher matcher = pattern.matcher(src);
                    if (matcher.find()) {
                        if (matcher.groupCount() > 0) {
                            for (int i = 1; i <= matcher.groupCount(); i++) {
                                result.add(matcher.group(i));
                            }
                        } else {
                            result.add(matcher.group());
                        }
                    }
                } catch (Exception e) {
                    // ignore invalid regex
                }
            }
            return result;
        }

        static Object getElement(String src, List<String> regexArray) {
            for (String regex : regexArray) {
                try {
                    Pattern pattern = Pattern.compile(regex);
                    Matcher matcher = pattern.matcher(src);
                    if (matcher.find()) {
                        return matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
            return null;
        }
    }
}
