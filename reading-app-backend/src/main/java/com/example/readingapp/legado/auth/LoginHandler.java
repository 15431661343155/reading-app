package com.example.readingapp.legado.auth;

import com.example.readingapp.legado.analyze.AnalyzeUrl;
import com.example.readingapp.legado.js.LegadoJsEngine;
import com.example.readingapp.legado.model.LegadoBookSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 处理需要登录的 Legado 书源。
 *
 * <p>流程：解析 loginUi 表单 → 用 {{username}}/{{password}} 占位填充 loginUrl →
 * 经 {@link AnalyzeUrl} 解析并发起 HTTP 请求 → 如有 loginCheckJs，用 {@link LegadoJsEngine}
 * 在响应上执行检查脚本，返回登录后的 Cookie/Token → {@link #getLoginHeaders()} 暴露为请求头。
 *
 * <p>依赖：{@link LegadoBookSource}（含 loginUrl/loginUi/loginCheckJs/header/jsLib/bookSourceUrl）、
 * {@link AnalyzeUrl}、{@link com.example.readingapp.util.HttpFetcher}（经 AnalyzeUrl 间接调用）。
 */
@Slf4j
public class LoginHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 匹配 Cookie 形如 key=val; key2=val2 */
    private static final Pattern COOKIE_PATTERN =
            Pattern.compile("^[^=;\\s]+=[^;]*(;\\s*[^=;\\s]+=[^;]*)*$");

    private final LegadoBookSource source;

    /** 登录后的凭证（Cookie 字符串 / Bearer Token 等），由 loginCheckJs 或响应体得出 */
    private String loginResult;

    /** loginUrl 解析后实际发起请求使用的 AnalyzeUrl（可暴露 headerMap 等） */
    private AnalyzeUrl lastAnalyzeUrl;

    public LoginHandler(LegadoBookSource source) {
        this.source = source;
    }

    /* ============================== 登录判定 ============================== */

    /**
     * 判断书源是否需要登录。
     *
     * <p>判断逻辑：
     * <ul>
     *   <li>loginUi 非空 → 需要登录（有登录表单）</li>
     *   <li>loginUrl 非空 且 loginUi 为空 → 检查 header 中是否已有认证信息
     *       （如 Authorization/Cookie），若有则视为认证已通过 header 提供，无需交互式登录</li>
     *   <li>其它情况 → 无需登录</li>
     * </ul>
     *
     * <p>典型场景：猫眼看书等书源 loginUrl="/auth/me" 仅是验证端点，
     * 认证 token 已通过 header 字段直接携带，不需要执行 doLogin() 流程。
     */
    public boolean checkLoginNeeded() {
        if (source == null) {
            return false;
        }
        // loginUi 非空 → 需要交互式登录
        if (notBlank(source.getLoginUi())) {
            return true;
        }
        // loginUrl 非空但无 loginUi → 检查 header 中是否已有认证信息
        if (notBlank(source.getLoginUrl())) {
            return !headerHasAuth(source.getHeader());
        }
        return false;
    }

    /**
     * 检查 header JSON 中是否包含认证信息（Authorization / Cookie）。
     * 若包含，则认为认证已通过 header 提供，无需再执行登录流程。
     */
    private boolean headerHasAuth(String headerJson) {
        if (!notBlank(headerJson)) {
            return false;
        }
        try {
            String normalized = normalizeJsonQuotes(headerJson);
            Map<String, Object> m = MAPPER.readValue(normalized, Map.class);
            if (m == null) return false;
            for (String key : m.keySet()) {
                String lk = key.toLowerCase();
                if ("authorization".equals(lk) || "cookie".equals(lk)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("LoginHandler: 解析 header 检查认证失败: {}", e.getMessage());
        }
        return false;
    }

    /* ============================== 执行登录 ============================== */

    /**
     * 执行登录，返回登录后的 Cookie 或 Token。
     *
     * @param username 用户名（填充 {{username}} 占位）
     * @param password 密码（填充 {{password}} 占位）
     * @return 登录凭证字符串；登录失败或无需登录返回 null
     */
    public String doLogin(String username, String password) {
        if (!checkLoginNeeded()) {
            log.info("LoginHandler: 书源无需登录");
            return null;
        }
        String loginUrl = source.getLoginUrl();
        if (!notBlank(loginUrl)) {
            log.warn("LoginHandler: loginUi 存在但 loginUrl 为空，无法发起登录");
            return null;
        }

        // 1) 构造字段映射：username/password + loginUi 中声明的其它字段（默认空）
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", username == null ? "" : username);
        fields.put("password", password == null ? "" : password);
        for (LoginField f : parseLoginUi(source.getLoginUi())) {
            fields.putIfAbsent(f.getName(), "");
        }
        String resolvedUrl = replaceFields(loginUrl, fields);

        // 2) 经 AnalyzeUrl 解析（支持 ,{method:POST, body:...} 等）并执行
        Map<String, String> headerMap = parseHeaderJson(source.getHeader());
        String resp;
        try {
            lastAnalyzeUrl = new AnalyzeUrl(resolvedUrl, null, null,
                    source.getBookSourceUrl(), headerMap, headerMap != null);
            resp = lastAnalyzeUrl.execute();
        } catch (Exception e) {
            log.warn("LoginHandler: 登录请求失败: {}", e.getMessage(), e);
            return null;
        }

        if (resp == null) {
            log.warn("LoginHandler: 登录响应为空");
            return null;
        }

        // 3) loginCheckJs 检查登录是否成功，并提取凭证
        String checkJs = source.getLoginCheckJs();
        if (notBlank(checkJs)) {
            try {
                LegadoJsEngine engine = new LegadoJsEngine(source.getJsLib(), source.getBookSourceUrl());
                if (lastAnalyzeUrl != null) {
                    engine.setAnalyzeUrl(lastAnalyzeUrl);
                }
                Object r = engine.eval(checkJs, resp, null);
                loginResult = r == null ? null : r.toString();
            } catch (Exception e) {
                log.warn("LoginHandler: loginCheckJs 执行失败: {}", e.getMessage(), e);
                loginResult = null;
            }
        } else {
            // 无检查脚本：把响应体作为凭证（适用于直接在响应里返回 token/Cookie 的场景）
            loginResult = resp;
        }

        if (loginResult == null || loginResult.isEmpty()) {
            log.warn("LoginHandler: 登录未取到凭证 (loginResult 为空)");
        } else {
            log.info("LoginHandler: 登录成功，凭证长度={}", loginResult.length());
        }
        return loginResult;
    }

    /* ============================== 登录后的请求头 ============================== */

    /**
     * 返回登录后的请求头（合并书源 header 与登录凭证）。
     *
     * <p>凭证放置启发式：
     * <ul>
     *   <li>形如 Cookie 串（key=val; key2=val2）→ 放入 Cookie 头</li>
     *   <li>形如 Bearer/已带方案前缀 → 直接放入 Authorization 头</li>
     *   <li>其它 → 作为 Bearer Token 放入 Authorization 头</li>
     * </ul>
     */
    public Map<String, String> getLoginHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> base = parseHeaderJson(source.getHeader());
        if (base != null) {
            headers.putAll(base);
        }
        if (loginResult != null && !loginResult.isEmpty()) {
            String r = loginResult.trim();
            if (COOKIE_PATTERN.matcher(r).matches()) {
                headers.put("Cookie", r);
            } else if (r.toLowerCase().startsWith("bearer ") || r.toLowerCase().startsWith("basic ")) {
                headers.put("Authorization", r);
            } else {
                headers.put("Authorization", "Bearer " + r);
            }
        }
        return headers;
    }

    /* ============================== loginUi 解析 ============================== */

    /**
     * 解析登录 UI 配置（Legado 的 loginUi JSON 数组）。
     *
     * <p>格式示例：
     * <pre>
     * [
     *   {"name":"username","type":"text","hint":"用户名"},
     *   {"name":"password","type":"password","hint":"密码"}
     * ]
     * </pre>
     *
     * @param loginUiJson loginUi 原始 JSON（可空）
     * @return 字段列表（解析失败返回空列表）
     */
    public List<LoginField> parseLoginUi(String loginUiJson) {
        List<LoginField> result = new ArrayList<>();
        if (!notBlank(loginUiJson)) {
            return result;
        }
        try {
            List<?> list = MAPPER.readValue(loginUiJson, List.class);
            if (list == null) {
                return result;
            }
            for (Object o : list) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) o;
                String name = m.get("name") == null ? "" : m.get("name").toString();
                String type = m.get("type") == null ? "text" : m.get("type").toString();
                String hint = m.get("hint") == null ? "" : m.get("hint").toString();
                if (name.isEmpty()) {
                    continue;
                }
                result.add(new LoginField(name, type, hint));
            }
        } catch (Exception e) {
            log.warn("LoginHandler: 解析 loginUi 失败: {}", e.getMessage());
        }
        return result;
    }

    /* ============================== 内部工具 ============================== */

    /** 替换 {{key}} 占位为对应字段值 */
    private String replaceFields(String url, Map<String, String> fields) {
        if (url == null) {
            return null;
        }
        String result = url;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}",
                    e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }

    /** 解析 header JSON 为 Map；空/失败返回 null */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaderJson(String headerJson) {
        if (!notBlank(headerJson)) {
            return null;
        }
        try {
            // Legado 书源 header 常用单引号，需预处理为双引号
            String normalized = normalizeJsonQuotes(headerJson);
            Map<String, Object> m = MAPPER.readValue(normalized, Map.class);
            if (m == null) {
                return null;
            }
            Map<String, String> h = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() != null) {
                    h.put(e.getKey(), e.getValue().toString());
                }
            }
            return h;
        } catch (Exception e) {
            log.warn("LoginHandler: 解析 header 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将类 JSON 字符串中的单引号转为双引号，使其符合标准 JSON 规范。
     * Legado 书源的 header 字段常用单引号包裹键值。
     */
    private static String normalizeJsonQuotes(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
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
                sb.append('"');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /* ============================== 登录字段 ============================== */

    /**
     * 登录 UI 字段定义。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginField {
        /** 字段名（对应 {{name}} 占位） */
        private String name;
        /** 字段类型：text / password / 等 */
        private String type;
        /** 输入框提示文字 */
        private String hint;
    }
}
