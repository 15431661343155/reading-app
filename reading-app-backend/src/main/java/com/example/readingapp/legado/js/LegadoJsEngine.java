package com.example.readingapp.legado.js;

import com.example.readingapp.legado.analyze.AnalyzeRule;
import com.example.readingapp.legado.analyze.AnalyzeUrl;
import com.example.readingapp.util.HttpFetcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.jsoup.Jsoup;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legado 书源 JS 执行引擎（基于 JDK Nashorn，JDK17 通过 nashorn-core 兼容库提供）。
 *
 * <p>每个自定义书源 new 一个（携带自己的 jsLib 与 baseUrl），所有访问串行化线程安全。
 * 引擎向 JS 注入 {@code java = this}（实现 {@link JsExtensions}），书源脚本调用
 * {@code java.ajax(...)} / {@code java.md5Encode(...)} 等方法时直接路由到本实例。
 *
 * <p>参考实现：{@code com.example.readingapp.util.LegadoJsEngine}（旧版，基于静态 BridgeHelper +
 * ThreadLocal 路由）。本类是其干净重写，直接绑定 {@code this}，去掉了 ThreadLocal 复杂度。
 */
@Slf4j
public class LegadoJsEngine implements JsExtensions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jsLib;
    private final String baseUrl;
    private final HttpFetcher httpFetcher;

    /** 可注入的规则/URL 分析器（用于 ajax / 变量读写等） */
    private AnalyzeRule analyzeRule;
    private AnalyzeUrl analyzeUrl;

    /** 书源级持久变量（对应 Legado source.getVariable/setVariable，七猫用它缓存 token） */
    private String persistentVar = "";

    /** 内部变量存储：仅当 analyzeRule 未注入时使用 */
    private final Map<String, String> variables = new LinkedHashMap<>();

    private ScriptEngine engine;

    /* ============================== 构造 ============================== */

    /**
     * @param jsLib   书源的 JavaScript 库代码（可为空）
     * @param baseUrl 书源 baseUrl（用于相对 URL 拼接与 ajax）
     */
    public LegadoJsEngine(String jsLib, String baseUrl) {
        this.jsLib = jsLib;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.httpFetcher = new HttpFetcher();
        initEngine();
    }

    private synchronized void initEngine() {
        ScriptEngineManager mgr = new ScriptEngineManager();
        ScriptEngine e = mgr.getEngineByName("nashorn");
        if (e == null) {
            e = mgr.getEngineByName("JavaScript");
        }
        if (e == null) {
            throw new IllegalStateException("Nashorn 脚本引擎不可用，请检查 nashorn-core 依赖");
        }
        engine = e;

        try {
            Bindings global = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            // java = this（JsExtensions）：JS 中 java.ajax(...) 直接路由到本实例方法
            global.put("java", this);

            engine.eval(
                // source 对象：Legado 规范的"书源级持久变量"读写，委托到 java.getSourceVariable/setSourceVariable
                "var source = {\n" +
                "  getVariable: function()   { return java.getSourceVariable(); },\n" +
                "  setVariable: function(v) { java.setSourceVariable(v == null ? '' : String(v)); }\n" +
                "};\n" +
                // 顶层 ajax 简写：部分书源直接写 ajax(url) 而非 java.ajax(url)
                "if (typeof ajax === 'undefined') { var ajax = function(u) { return java.ajax(u); }; }\n" +
                // Packages 别名：Legado 脚本常引用 Packages.android.util.Base64 / Packages.java.* / Packages.javax.crypto.*
                "if (typeof Packages === 'undefined') { var Packages = {}; }\n" +
                "if (!Packages.android)  { Packages.android = {}; }\n" +
                "if (!Packages.android.util) { Packages.android.util = {}; }\n" +
                "Packages.android.util.Base64 = Java.type('com.example.readingapp.legado.js.LegadoJsEngine$AndroidBase64');\n" +
                "if (typeof android === 'undefined') { var android = Packages.android; }\n" +
                "if (!Packages.java) { Packages.java = {}; }\n" +
                "Packages.java.util = Packages.java.util || {};\n" +
                "Packages.java.util.UUID   = Packages.java.util.UUID   || Java.type('java.util.UUID');\n" +
                "Packages.java.util.Arrays = Packages.java.util.Arrays || Java.type('java.util.Arrays');\n" +
                "Packages.java.lang = Packages.java.lang || {};\n" +
                "Packages.java.lang.String = Packages.java.lang.String || Java.type('java.lang.String');\n" +
                "Packages.javax = Packages.javax || {};\n" +
                "Packages.javax.crypto = Packages.javax.crypto || {};\n" +
                "Packages.javax.crypto.spec = Packages.javax.crypto.spec || {};\n" +
                "Packages.javax.crypto.Cipher                   = Packages.javax.crypto.Cipher                   || Java.type('javax.crypto.Cipher');\n" +
                "Packages.javax.crypto.spec.SecretKeySpec        = Packages.javax.crypto.spec.SecretKeySpec        || Java.type('javax.crypto.spec.SecretKeySpec');\n" +
                "Packages.javax.crypto.spec.IvParameterSpec      = Packages.javax.crypto.spec.IvParameterSpec      || Java.type('javax.crypto.spec.IvParameterSpec');\n" +
                // java.util.Base64 (区别于 android.util.Base64)：七猫 ruleContent.content 的 decode() 使用
                "Packages.java.util.Base64                       = Packages.java.util.Base64                       || Java.type('java.util.Base64');\n" +
                // Legado 书源常用 java.util.regex.Pattern
                "Packages.java.util.regex = Packages.java.util.regex || {};\n" +
                "Packages.java.util.regex.Pattern = Packages.java.util.regex.Pattern || Java.type('java.util.regex.Pattern');\n" +
                // 全局类别名：七猫等书源的 ruleContent.content 使用 JavaImporter + with() 块引用
                // Base64/Arrays/SecretKeySpec/IvParameterSpec/Cipher 等类名。
                // 由于 with() 块内定义的 function 被提升到外层作用域，调用时其实在外层解析这些名字，
                // 因此必须设为全局才能生效。nashorn-core 的 JavaImporter 不支持 importPackage，
                // 故用 no-op polyfill 避免抛错，再通过全局别名提供实际类引用。
                "if (typeof Base64 === 'undefined')        { var Base64        = Java.type('java.util.Base64'); }\n" +
                "if (typeof Arrays === 'undefined')        { var Arrays        = Java.type('java.util.Arrays'); }\n" +
                // SecretKeySpec / IvParameterSpec 是构造函数类，Legado JS 在 JavaImporter+with() 块中
                // 直接调用 SecretKeySpec(bytes, algo) 而不加 new。nashorn-core 的 JavaClass 对象不支持
                // 函数式调用，必须用 new。故用工厂函数包装，使其可省略 new 关键字。
                "if (typeof SecretKeySpec === 'undefined') {\n" +
                "  var _SecretKeySpecCls = Java.type('javax.crypto.spec.SecretKeySpec');\n" +
                "  var SecretKeySpec = function(bytes, algo) { return new _SecretKeySpecCls(bytes, algo); };\n" +
                "}\n" +
                "if (typeof IvParameterSpec === 'undefined') {\n" +
                "  var _IvParameterSpecCls = Java.type('javax.crypto.spec.IvParameterSpec');\n" +
                "  var IvParameterSpec = function(iv) { return new _IvParameterSpecCls(iv); };\n" +
                "}\n" +
                "if (typeof Cipher === 'undefined')        { var Cipher        = Java.type('javax.crypto.Cipher'); }\n" +
                // 覆盖全局 String()：Legado JS 中 String(chipher.doFinal(...)) 传入 Java byte[]，
                // nashorn-core 会返回 byte[].toString()（如 [B@1a2b3c），而非解码后的文本。
                // 覆盖为：byte[] → new java.lang.String(bytes)，其余类型走原生 String() 转换。
                // 注意：不能用 isArray()，Nashorn 中 Java Boolean 在 && 中被视为 truthy。
                // 用 getClass().getName() === '[B' 直接判断 byte[] 类型。
                "var _origString = String;\n" +
                "String = function(v) {\n" +
                "  if (v && v.getClass && v.getClass().getName() === '[B') {\n" +
                "    return new (Java.type('java.lang.String'))(v);\n" +
                "  }\n" +
                "  return _origString(v);\n" +
                "};\n" +
                // JavaImporter polyfill：nashorn-core 不支持 importPackage，用 no-op 避免抛错
                "if (typeof JavaImporter !== 'undefined' && !JavaImporter.prototype.importPackage) {\n" +
                "  JavaImporter.prototype.importPackage = function() {};\n" +
                "} else if (typeof JavaImporter === 'undefined') {\n" +
                "  var JavaImporter = function() { this.importPackage = function() {}; };\n" +
                "}"
            );

            // 预加载 jsLib（定义全局函数），之后每次 eval 通过 ENGINE_SCOPE 继承复用
            if (jsLib != null && !jsLib.trim().isEmpty()) {
                engine.eval(jsLib);
                log.info("LegadoJsEngine: jsLib 装载成功（{} 字符）", jsLib.length());
                applyQimaoPatch();
            }
        } catch (Exception ex) {
            throw new RuntimeException("LegadoJsEngine 初始化失败: " + ex.getMessage(), ex);
        }
    }

    /* ============================== 七猫专属兼容补丁 ============================== */

    /**
     * 七猫兼容性检查与适配。
     *
     * <p>七猫 jsLib 中 {@code qmParamEncode} 使用 {@code qmMapChars(b64, QM_B64, QM_PARAM_MAP)}
     * 做字符映射。{@code QM_B64} 是七猫自定义字母表顺序（{@code +/0-9A-Za-z}），
     * 而本引擎的 {@link AndroidBase64#encodeToString} 输出标准 Base64 顺序（{@code A-Za-z0-9+/}）。
     *
     * <p>七猫 APP 在真实 Android 上也是用标准 {@code android.util.Base64}（标准顺序输出）
     * 配合原版 {@code qmMapChars(b64, QM_B64, QM_PARAM_MAP)}，服务器期望的就是这个映射结果。
     * 因此本方法<b>不覆盖</b> {@code qmParamEncode}，保留 jsLib 原版逻辑即可正常工作。
     *
     * <p>历史教训：曾尝试把 {@code qmMapChars} 的 FROM 改为标准 Base64 顺序，
     * 反而导致 qm-params 映射错位 → 服务器返回 44010102 参数错误。
     */
    private void applyQimaoPatch() throws Exception {
        if (jsLib == null) return;
        boolean isQimao = jsLib.contains("QM_SECRET") || jsLib.contains("qmParamEncode");
        if (!isQimao) {
            return;
        }
        // 仅记录检测到七猫书源，不覆盖任何 jsLib 函数。
        log.info("LegadoJsEngine: 检测到七猫书源，保留 jsLib 原版 qmParamEncode（QM_B64 映射）");
    }

    /* ============================== eval ============================== */

    /**
     * 执行一段 JS，注入局部变量（result/baseUrl/book 等）。
     *
     * <p>jsLib 已在构造时预加载到 ENGINE_SCOPE，此处通过继承可见，无需重复执行。
     *
     * @param jsStr    JS 代码（已去掉 @js: 前缀）
     * @param result   上一段解析结果（可空）
     * @param bindings 额外局部变量（可空，可覆盖 source/book 等）
     * @return 执行结果（Double/Integer/String/Boolean 等基本类型原样返回；JS 对象/数组转 JSON 字符串）
     */
    @Override
    public Object eval(String jsStr, Object result, Map<String, Object> bindings) {
        if (jsStr == null || jsStr.isEmpty()) {
            return result;
        }
        // 预处理：将 ES6+ 语法转为 Nashorn 兼容的 ES5
        jsStr = preprocessForNashorn(jsStr);
        synchronized (this) {
            long startAt = System.nanoTime();
            try {
                // 直接在 ENGINE_SCOPE 执行：Nashorn 的 `java` 全局绑定（= this，
                // JsExtensions）只在原始 ENGINE_SCOPE 生效。SimpleBindings 创建的新
                // SimpleScriptContext 无法覆盖 Nashorn 内置的 `java` 包访问器，导致
                // 顶层脚本中 `java.md5Encode(...)` 被误解析为 Java 类查找
                // （ClassNotFoundException: java.md5Encode）。
                // 因此采用「保存 → 注入局部变量 → eval → 恢复」模式，在 synchronized
                // 保护下操作 ENGINE_SCOPE，确保 java/source/ajax/Packages 及 jsLib
                // 函数均可见，且 java.* 调用正确路由到 JsExtensions 实例。
                Bindings global = engine.getBindings(ScriptContext.ENGINE_SCOPE);
                // 保存将被覆盖的变量旧值（可能不存在，用 null 兜底）
                java.util.Set<String> keysToRestore = new java.util.LinkedHashSet<>();
                java.util.Map<String, Object> savedValues = new LinkedHashMap<>();
                for (String k : new String[]{"result", "baseUrl", "book"}) {
                    keysToRestore.add(k);
                    savedValues.put(k, global.containsKey(k) ? global.get(k) : null);
                }
                if (bindings != null) {
                    for (Map.Entry<String, Object> en : bindings.entrySet()) {
                        if (en.getKey() != null) {
                            keysToRestore.add(en.getKey());
                            savedValues.put(en.getKey(),
                                    global.containsKey(en.getKey()) ? global.get(en.getKey()) : null);
                        }
                    }
                }
                try {
                    global.put("result", result);
                    global.put("baseUrl", baseUrl);
                    global.put("book", null);
                    if (bindings != null) {
                        for (Map.Entry<String, Object> en : bindings.entrySet()) {
                            if (en.getKey() != null) {
                                global.put(en.getKey(), en.getValue());
                            }
                        }
                    }
                    Object ret = engine.eval(jsStr);
                    Object out = normalizeResult(ret);
                    if (log.isDebugEnabled()) {
                        long ms = (System.nanoTime() - startAt) / 1_000_000L;
                        String preview = out == null ? "null"
                                : (out.toString().length() < 200 ? out.toString() : out.toString().substring(0, 200) + "...");
                        log.debug("JS eval {}ms → {}", ms, preview);
                    }
                    return out;
                } finally {
                    // 恢复被覆盖的全局变量旧值
                    for (String k : keysToRestore) {
                        Object v = savedValues.get(k);
                        if (v == null) {
                            global.remove(k);
                        } else {
                            global.put(k, v);
                        }
                    }
                }
            } catch (Exception ex) {
                long ms = (System.nanoTime() - startAt) / 1_000_000L;
                log.warn("JS 求值失败 ({}ms): {} — script={}", ms, ex.getMessage(),
                        jsStr.length() > 200 ? jsStr.substring(0, 200) + "..." : jsStr);
                if (log.isDebugEnabled()) {
                    log.debug("JS 异常详情", ex);
                }
                return null;
            }
        }
    }

    /**
     * JS 预处理器：将 Legado 书源中常见的 ES6+ 语法转换为 Nashorn 兼容的 ES5 语法。
     * 主要处理：
     * 1. 模板字符串 `` `text${expr}more` `` → 字符串拼接 "text"+expr+"more"
     * 2. 简单模板字符串 `` `text` `` → "text"
     * 3. let/const → var
     *
     * 注意：箭头函数 Nashorn（nashorn-core 兼容库）不支持，需预转换。
     */
    static String preprocessForNashorn(String js) {
        if (js == null) return null;
        String result = js;

        // 1. 处理模板字符串：递归转换带 ${} 的模板字符串
        //    简单模板：`text` → "text"  （不含 ${}）
        //    复杂模板：`text${expr}more` → "text"+(expr)+"more"
        result = processTemplateLiterals(result);

        // 2. let/const → var（简单替换，跳过字符串内部的关键字）
        result = result.replaceAll("(?<![\"'`])\\bconst\\b(?![\"'`])", "var");
        result = result.replaceAll("(?<![\"'`])\\blet\\b(?![\"'`])", "var");

        // 3. 简单箭头函数转换：(params)=>expr → function(params){return expr}
        //    Nashorn 不支持 ES6 箭头函数语法，需预转换。
        //    仅处理单行表达式体；body 终止于 \n ; { } , 避免吞掉后续代码。
        result = processArrowFunctions(result);

        return result;
    }

    /**
     * 处理模板字符串（backtick strings）。
     * 将 `` `text` `` 转为 `"text"`，将 `` `text${expr}more` `` 转为字符串拼接。
     */
    private static String processTemplateLiterals(String js) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < js.length()) {
            char c = js.charAt(i);
            if (c == '`') {
                // 找到模板字符串起始
                int end = js.indexOf('`', i + 1);
                if (end == -1) {
                    // 没有结束 backtick，原样输出
                    sb.append(c);
                    i++;
                    continue;
                }
                String template = js.substring(i + 1, end);
                if (template.isEmpty()) {
                    sb.append("\"\"");
                } else if (!template.contains("${")) {
                    // 简单模板字符串，直接转双引号
                    sb.append("\"").append(escapeForJsString(template)).append("\"");
                } else {
                    // 含 ${} 的模板字符串，转为字符串拼接
                    sb.append(convertTemplateToStringConcat(template));
                }
                i = end + 1;
            } else if (c == '"' || c == '\'') {
                // 普通字符串，原样复制到结束引号
                sb.append(c);
                i++;
                while (i < js.length()) {
                    char sc = js.charAt(i);
                    sb.append(sc);
                    if (sc == '\\' && i + 1 < js.length()) {
                        sb.append(js.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    i++;
                    if (sc == c) break;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 将模板字符串内容（去除 backtick）转为 JS 字符串拼接表达式。
     * 例如："text${expr}more${expr2}" → "\"text\"+(expr)+\"more\"+(expr2)"
     */
    private static String convertTemplateToStringConcat(String template) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        boolean first = true;
        while (i < template.length()) {
            int startDollar = template.indexOf("${", i);
            if (startDollar == -1) {
                // 剩余都是纯文本
                String text = template.substring(i);
                if (!text.isEmpty()) {
                    if (!first) sb.append("+");
                    sb.append("\"").append(escapeForJsString(text)).append("\"");
                    first = false;
                }
                break;
            }
            // ${ 前的纯文本
            if (startDollar > i) {
                String text = template.substring(i, startDollar);
                if (!first) sb.append("+");
                sb.append("\"").append(escapeForJsString(text)).append("\"");
                first = false;
            }
            // 找到对应的 }
            int braceCount = 1;
            int j = startDollar + 2;
            while (j < template.length() && braceCount > 0) {
                char ch = template.charAt(j);
                if (ch == '{') braceCount++;
                else if (ch == '}') braceCount--;
                j++;
            }
            String expr = template.substring(startDollar + 2, j - 1);
            if (!first) sb.append("+");
            sb.append("(").append(expr).append(")");
            first = false;
            i = j;
        }
        return sb.toString();
    }

    /**
     * 转义字符串内容用于双引号包裹的 JS 字符串。
     */
    private static String escapeForJsString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 处理简单箭头函数：(params) => expression → function(params){return expression}
     * 注意：仅处理单行、无复杂函数体的箭头函数。
     * expression 终止于 ,  ;  \n  或 结束符，确保不吞掉后面的参数。
     */
    private static String processArrowFunctions(String js) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\(([^()]*?)\\)\\s*=>\\s*([^\\n;{},]+)");
        java.util.regex.Matcher m = p.matcher(js);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String params = m.group(1).trim();
            String body = m.group(2).trim();
            m.appendReplacement(sb, "function(" + params + "){return " + body + "}");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 仅传 JS、无 result/额外 bindings 的便捷重载。 */
    public Object eval(String jsStr) {
        return eval(jsStr, null, null);
    }

    /** 字符串结果便捷重载。 */
    public String evalString(String jsStr, Object result, Map<String, Object> bindings) {
        Object o = eval(jsStr, result, bindings);
        return o == null ? null : o.toString();
    }

    /* ============================== 结果归一化 ============================== */

    /**
     * Nashorn 原始返回 → Java Object。
     * <ul>
     *   <li>null / Boolean / Number / String / Character：原样返回</li>
     *   <li>JS 对象/数组（ScriptObjectMirror）：通过同引擎 JSON.stringify 转字符串</li>
     * </ul>
     */
    /**
     * 将 JS eval 结果转为 Java 可用的对象。
     * <p>
     * 核心逻辑：
     * 1. 基本类型（Boolean/Number/String/Character）直接返回
     * 2. Nashorn ScriptObjectMirror：
     *    - 先用 Nashorn typeof 判断真实类型
     *    - 如果是 JS string 类型（ScriptObjectMirror 包裹的 NativeString），提取其字符串值
     *    - 如果是 JS object 类型（NativeObject 等），用 Nashorn 的 JSON.stringify 序列化
     *    - 其他类型（number/boolean/undefined）转为字符串
     * <p>
     * 关键：Nashorn 将 JS string 包装成 ScriptObjectMirror，其 toString() 返回原始字符串
     * （不加引号）。若直接 JSON.stringify 会产生 "..." 包裹，导致 URL 等场景出错。
     * 因此统一走 Nashorn typeof 分流处理。
     */
    private Object normalizeResult(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean || v instanceof Number || v instanceof String || v instanceof Character) {
            return v;
        }
        // Nashorn ScriptObjectMirror：用 Nashorn typeof 判断真实 JS 类型
        try {
            Bindings b = new SimpleBindings();
            b.put("__x", v);
            Object typeRet = engine.eval("typeof __x", b);
            String type = typeRet != null ? typeRet.toString() : "object";
            switch (type) {
                case "string":
                    // JS string → 返回 Java String（ScriptObjectMirror.toString() 已返回原始值）
                    return v.toString();
                case "number":
                    return Double.parseDouble(v.toString());
                case "boolean":
                    return Boolean.parseBoolean(v.toString());
                case "object":
                    // JS object/array → JSON 字符串，供后续解析
                    Object jsonStr = engine.eval("JSON.stringify(__x)", b);
                    return jsonStr == null ? null : jsonStr.toString();
                default:
                    return v.toString();
            }
        } catch (Exception e) {
            // 兜底：用 Nashorn 的 JSON.stringify
            try {
                Bindings b = new SimpleBindings();
                b.put("__x", v);
                Object s = engine.eval("JSON.stringify(__x)", b);
                return s == null ? null : s.toString();
            } catch (Exception e2) {
                return String.valueOf(v);
            }
        }
    }

    /* ============================== 依赖注入 ============================== */

    @Override
    public void setAnalyzeRule(AnalyzeRule rule) {
        this.analyzeRule = rule;
    }

    @Override
    public void setAnalyzeUrl(AnalyzeUrl url) {
        this.analyzeUrl = url;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /* ============================== JsExtensions 实现 ============================== */

    /**
     * Legado 的 java.ajax 参数格式：
     * <pre>
     *   "https://a.b/c"                              → GET
     *   "https://a.b/c,{method:'POST',headers:{},body:'x'}"
     * </pre>
     * 用 {@link AnalyzeUrl} 解析 urlExpr 并 execute() 返回 HTTP 响应字符串。
     */
    @Override
    public String ajax(String urlExpr) {
        if (urlExpr == null || urlExpr.isEmpty()) {
            return null;
        }
        // 去除 Legado JS 中 `url` 语法的 backtick 包裹
        urlExpr = urlExpr.replace("`", "");
        try {
            Map<String, String> headers = analyzeUrl != null ? new LinkedHashMap<>(analyzeUrl.headerMap) : null;
            AnalyzeUrl au = new AnalyzeUrl(urlExpr, null, null, baseUrl, headers, false);
            String resp = au.execute();
            // 临时 INFO 级别日志：排查七猫 ruleContent.content 的 ajax 调用
            String preview = resp == null ? "<NULL>"
                    : (resp.length() < 500 ? resp : resp.substring(0, 500) + "...");
            log.info("java.ajax({}) | len={} | resp={}",
                    urlExpr.length() > 300 ? urlExpr.substring(0, 300) + "..." : urlExpr,
                    resp == null ? -1 : resp.length(), preview);
            return resp;
        } catch (Exception e) {
            log.warn("java.ajax 失败: {} - {}", urlExpr, e.getMessage());
            return null;
        }
    }

    /**
     * 批量 ajax：urlExpr 可为单个 URL、JSON 数组字符串、JS 数组（ScriptObjectMirror）或 Java 集合。
     * 返回所有响应组成的 List。
     */
    @Override
    public List<String> ajaxAll(Object urlExpr) {
        List<String> result = new ArrayList<>();
        if (urlExpr == null) {
            return result;
        }
        if (urlExpr instanceof Collection) {
            for (Object u : (Collection<?>) urlExpr) {
                String r = ajax(u == null ? null : u.toString());
                if (r != null) {
                    result.add(r);
                }
            }
            return result;
        }
        if (urlExpr instanceof Map) {
            // Nashorn 的 JS 数组 ScriptObjectMirror 实现了 Map 接口，values() 即各元素
            for (Object u : ((Map<?, ?>) urlExpr).values()) {
                String r = ajax(u == null ? null : u.toString());
                if (r != null) {
                    result.add(r);
                }
            }
            return result;
        }
        if (urlExpr.getClass().isArray()) {
            for (Object u : (Object[]) urlExpr) {
                String r = ajax(u == null ? null : u.toString());
                if (r != null) {
                    result.add(r);
                }
            }
            return result;
        }
        // 字符串：可能是 JSON 数组
        String s = urlExpr.toString().trim();
        if (s.startsWith("[")) {
            try {
                List<?> arr = MAPPER.readValue(s, List.class);
                for (Object u : arr) {
                    String r = ajax(u == null ? null : u.toString());
                    if (r != null) {
                        result.add(r);
                    }
                }
                return result;
            } catch (Exception ignore) {
                // 解析失败按单个 URL 处理
            }
        }
        String r = ajax(s);
        if (r != null) {
            result.add(r);
        }
        return result;
    }

    /* ---------- 变量系统 ---------- */

    @Override
    public String get(String key) {
        if (key == null) {
            return "";
        }
        // Always read from JsEngine's own variables (persistent across calls)
        // This is critical for sources like 七猫 that use java.put("headers", ...)
        // during search and java.get("headers") during detail/toc/content calls.
        String v = variables.get(key);
        if (v != null) return v;
        // Fall back to AnalyzeRule's local variables (current call only)
        if (analyzeRule != null) {
            return analyzeRule.get(key);
        }
        return "";
    }

    @Override
    public String put(String key, String value) {
        if (key == null) {
            return value;
        }
        String v = value == null ? "" : value;
        // Always store in JsEngine's own variables (persistent across calls)
        variables.put(key, v);
        // Also store in AnalyzeRule for current-call access (no delegation back to here)
        if (analyzeRule != null) {
            analyzeRule.put(key, v);
        }
        return v;
    }

    /** 书源级持久变量（source.getVariable 委托至此） */
    @Override
    public String getSourceVariable() {
        return persistentVar == null ? "" : persistentVar;
    }

    /** Direct variable storage (no delegation to AnalyzeRule, avoids circular calls) */
    @Override
    public void putVariable(String key, String value) {
        if (key != null) {
            variables.put(key, value == null ? "" : value);
        }
    }

    /** Direct variable read (no delegation to AnalyzeRule, avoids circular calls) */
    @Override
    public String getVariable(String key) {
        if (key == null) return "";
        String v = variables.get(key);
        return v != null ? v : "";
    }

    /** 书源级持久变量（source.setVariable 委托至此） */
    @Override
    public void setSourceVariable(String value) {
        this.persistentVar = value == null ? "" : value;
    }

    /** 清空书源级持久变量缓存（用于强制重新登录等场景） */
    public synchronized void clearPersistentVar() {
        int len = this.persistentVar == null ? 0 : this.persistentVar.length();
        this.persistentVar = "";
        log.info("LegadoJsEngine: clearPersistentVar 调用，清空前 len={}", len);
    }

    /* ---------- 哈希 ---------- */

    @Override
    public String md5Encode(String str) {
        if (str == null) {
            return "";
        }
        return DigestUtils.md5Hex(str.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String md5Encode16(String str) {
        String full = md5Encode(str);
        // 16 位 MD5 = 32 位 hex 去掉首尾各 8 位
        if (full.length() >= 24) {
            return full.substring(8, 24);
        }
        return full;
    }

    /* ---------- Base64 ---------- */

    @Override
    public String base64Encode(String str) {
        if (str == null) {
            return null;
        }
        // 对齐 Android Base64.DEFAULT：每 76 字符换行（MIME）
        return Base64.getMimeEncoder().encodeToString(str.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String base64Decode(String str) {
        if (str == null) {
            return null;
        }
        try {
            // MIME 解码器能兼容带换行/不带换行、带 padding/不带 padding 的输入
            return new String(Base64.getMimeDecoder().decode(str), StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                return new String(Base64.getDecoder().decode(str), StandardCharsets.UTF_8);
            } catch (Exception e2) {
                log.warn("base64Decode 失败: {}", e2.getMessage());
                return null;
            }
        }
    }

    @Override
    public String base64EncodeNoNewLine(String str) {
        if (str == null) {
            return null;
        }
        // 对齐 Android Base64.NO_WRAP：不换行、带 padding
        return Base64.getEncoder().encodeToString(str.getBytes(StandardCharsets.UTF_8));
    }

    /* ---------- AES ---------- */

    @Override
    public String aesEncode(String data, String key, String iv, String mode, String padding) {
        return doAes(data, key, iv, mode, padding, true);
    }

    @Override
    public String aesDecode(String data, String key, String iv, String mode, String padding) {
        return doAes(data, key, iv, mode, padding, false);
    }

    /**
     * Legado 原生方法名：aesBase64DecodeToString。
     * 书源规则调用格式：java.aesBase64DecodeToString(data, key, transformation, iv)
     * 其中 transformation 格式为 "AES/CBC/PKCS5Padding"，内部解析后委托给 aesDecode。
     *
     * 示例：java.aesBase64DecodeToString(result,"f041c49714d39908","AES/CBC/PKCS5Padding","0123456789abcdef")
     */
    @Override
    public String aesBase64DecodeToString(String data, String key, String transformation, String iv) {
        String mode = "CBC";
        String padding = "PKCS5Padding";
        if (transformation != null && transformation.contains("/")) {
            String[] parts = transformation.split("/");
            if (parts.length >= 2) {
                mode = parts[1];       // e.g. "CBC" from "AES/CBC/PKCS5Padding"
            }
            if (parts.length >= 3) {
                padding = parts[2];    // e.g. "PKCS5Padding"
            }
        }
        return aesDecode(data, key, iv, mode, padding);
    }

    private String doAes(String data, String key, String iv, String mode, String padding, boolean encrypt) {
        if (data == null || key == null) {
            return null;
        }
        String m = (mode == null || mode.isEmpty()) ? "CBC" : mode.toUpperCase();
        String p = (padding == null || padding.isEmpty()) ? "PKCS5Padding" : padding;
        String algo = "AES/" + m + "/" + p;
        try {
            Cipher cipher = Cipher.getInstance(algo);
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            if ("ECB".equals(m)) {
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keySpec);
            } else {
                byte[] ivBytes = (iv == null ? new byte[16] : iv.getBytes(StandardCharsets.UTF_8));
                IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keySpec, ivSpec);
            }
            if (encrypt) {
                byte[] out = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(out);
            } else {
                byte[] raw = Base64.getMimeDecoder().decode(data);
                byte[] out = cipher.doFinal(raw);
                return new String(out, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("AES {} 失败 ({}): {}", encrypt ? "encode" : "decode", algo, e.getMessage());
            return null;
        }
    }

    /* ---------- DES（简化实现） ---------- */

    @Override
    public String desEncode(String data, String key, String iv, String mode, String padding) {
        return doDes(data, key, iv, mode, padding, true);
    }

    @Override
    public String desDecode(String data, String key, String iv, String mode, String padding) {
        return doDes(data, key, iv, mode, padding, false);
    }

    private String doDes(String data, String key, String iv, String mode, String padding, boolean encrypt) {
        if (data == null || key == null) {
            return null;
        }
        String m = (mode == null || mode.isEmpty()) ? "CBC" : mode.toUpperCase();
        String p = (padding == null || padding.isEmpty()) ? "PKCS5Padding" : padding;
        String algo = "DES/" + m + "/" + p;
        try {
            Cipher cipher = Cipher.getInstance(algo);
            DESKeySpec keySpec = new DESKeySpec(key.getBytes(StandardCharsets.UTF_8));
            SecretKeyFactory skf = SecretKeyFactory.getInstance("DES");
            SecretKey secretKey = skf.generateSecret(keySpec);
            if ("ECB".equals(m)) {
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, secretKey);
            } else {
                byte[] ivBytes = (iv == null ? new byte[8] : iv.getBytes(StandardCharsets.UTF_8));
                IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, secretKey, ivSpec);
            }
            if (encrypt) {
                byte[] out = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(out);
            } else {
                byte[] raw = Base64.getMimeDecoder().decode(data);
                byte[] out = cipher.doFinal(raw);
                return new String(out, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("DES {} 失败 ({}): {}", encrypt ? "encode" : "decode", algo, e.getMessage());
            return null;
        }
    }

    /* ---------- HTTP ---------- */

    @Override
    public String httpGet(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        HttpFetcher.RequestConfig cfg = new HttpFetcher.RequestConfig(url);
        cfg.method = "GET";
        return httpFetcher.fetch(cfg);
    }

    @Override
    public String httpPost(String url, String body) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        HttpFetcher.RequestConfig cfg = new HttpFetcher.RequestConfig(url);
        cfg.method = "POST";
        cfg.body = body;
        return httpFetcher.fetch(cfg);
    }

    /* ---------- 杂项 ---------- */

    @Override
    public void log(String msg) {
        log.info("[JS] {}", msg);
    }

    @Override
    public String htmlFormat(String html) {
        if (html == null) {
            return "";
        }
        // 清理 HTML 标签，提取纯文本
        return Jsoup.parse(html).text();
    }

    @Override
    public String timeFormat(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return "";
        }
        try {
            long ts = Long.parseLong(timestamp.trim());
            // 10 位秒级 → 毫秒
            if (ts < 1_000_000_000_000L) {
                ts *= 1000L;
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts));
        } catch (NumberFormatException e) {
            return timestamp;
        }
    }

    @Override
    public String urlEncode(String str) {
        if (str == null) {
            return "";
        }
        try {
            return URLEncoder.encode(str, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return str;
        }
    }

    /* ============================== Android Base64 替身 ============================== */

    /**
     * android.util.Base64 替身（静态方法），供 Legado 脚本通过
     * {@code Packages.android.util.Base64.encodeToString(bytes, flags)} 调用。
     *
     * <p>flags 语义与 Android 官方一致：
     * <ul>
     *   <li>0 DEFAULT    带 MIME 换行</li>
     *   <li>1 NO_PADDING 末尾去 =</li>
     *   <li>2 NO_WRAP    不换行</li>
     *   <li>4 CRLF       MIME + \r\n 换行</li>
     *   <li>8 URL_SAFE  使用 -_ 替代 +/</li>
     * </ul>
     */
    public static final class AndroidBase64 {
        private AndroidBase64() {
        }

        public static String encodeToString(byte[] input, int flags) {
            if (input == null) {
                return null;
            }
            boolean noWrap = (flags & 2) != 0;
            boolean urlSafe = (flags & 8) != 0;
            boolean noPadding = (flags & 1) != 0;
            boolean crlf = (flags & 4) != 0;

            Base64.Encoder enc;
            if (urlSafe) {
                enc = Base64.getUrlEncoder();
            } else if (crlf) {
                enc = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII));
            } else if (noWrap || flags == 0) {
                // flags=0 (DEFAULT)：Android 会加 MIME 换行，但 Legado 几乎都传 NO_WRAP=2；
                // 为兼容两种调用，这里默认使用不换行的普通编码器。
                enc = Base64.getEncoder();
            } else {
                enc = Base64.getEncoder();
            }
            if (noPadding) {
                enc = enc.withoutPadding();
            }
            return enc.encodeToString(input);
        }

        public static byte[] decode(Object str, int flags) {
            if (str == null) {
                return new byte[0];
            }
            String s = str.toString();
            boolean urlSafe = (flags & 8) != 0;
            try {
                if (urlSafe) {
                    return Base64.getUrlDecoder().decode(s);
                }
                // MIME 解码器最宽松，能兼容带/不带换行、带/不带 padding 的输入
                return Base64.getMimeDecoder().decode(s);
            } catch (Exception ignore) {
                try {
                    return Base64.getDecoder().decode(s);
                } catch (Exception ignore2) {
                    return new byte[0];
                }
            }
        }
    }

}

/* ============================== JsExtensions 契约（顶层接口） ============================== */

/**
 * Legado 书源脚本可调用的 java 桥接方法集合（对应 Legado 的 JsExtensions 接口）。
 * JS 中通过 {@code java.xxx(...)} 调用，{@code java} 绑定为引擎实例本身。
 *
 * <p>声明为顶层（包级可见）接口而非 {@code LegadoJsEngine} 的嵌套接口，以避免
 * {@code class LegadoJsEngine implements LegadoJsEngine.JsExtensions} 触发的循环继承编译错误。
 */
interface JsExtensions {
    Object eval(String jsStr, Object result, Map<String, Object> bindings);

    String ajax(String urlExpr);

    List<String> ajaxAll(Object urlExpr);

    String get(String key);

    String put(String key, String value);

    /**
     * Direct variable storage without delegating to AnalyzeRule.
     * Used by AnalyzeRule.put to sync variables to JsEngine without circular calls.
     */
    void putVariable(String key, String value);

    /**
     * Direct variable read without delegating to AnalyzeRule.
     * Used by AnalyzeRule.get to check JsEngine variables as fallback.
     */
    String getVariable(String key);

    String getSourceVariable();

    void setSourceVariable(String value);

    void setAnalyzeRule(AnalyzeRule rule);

    void setAnalyzeUrl(AnalyzeUrl url);

    String md5Encode(String str);

    String md5Encode16(String str);

    String base64Encode(String str);

    String base64Decode(String str);

    String base64EncodeNoNewLine(String str);

    String aesEncode(String data, String key, String iv, String mode, String padding);

    String aesDecode(String data, String key, String iv, String mode, String padding);

    /**
     * Legado 原生方法名：aesBase64DecodeToString。
     * 参数顺序 (data, key, transformation, iv)，其中 transformation 格式为 "AES/CBC/PKCS5Padding"。
     * 内部解析 transformation 后委托给 aesDecode。
     */
    String aesBase64DecodeToString(String data, String key, String transformation, String iv);

    String desEncode(String data, String key, String iv, String mode, String padding);

    String desDecode(String data, String key, String iv, String mode, String padding);

    String httpGet(String url);

    String httpPost(String url, String body);

    void log(String msg);

    String htmlFormat(String html);

    String timeFormat(String timestamp);

    String urlEncode(String str);
}
