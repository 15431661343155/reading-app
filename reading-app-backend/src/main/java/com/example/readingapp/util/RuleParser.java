package com.example.readingapp.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class RuleParser {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern PUT_PATTERN = Pattern.compile("@put:(\\S+?) (.+)");
    private static final Pattern GET_PATTERN = Pattern.compile("@get:(\\S+)");
    private static final Pattern PUT_TMP_PATTERN = Pattern.compile("@putTmp:(\\S+?) (.+)");
    private static final Pattern GET_TMP_PATTERN = Pattern.compile("@getTmp:(\\S+)");

    public enum SelectorType {
        CSS, JSONPATH, TEXT, JS, XPATH
    }

    public static SelectorType detectType(String rule) {
        if (rule == null || rule.isEmpty()) return SelectorType.TEXT;
        String r = rule.trim();
        if (r.startsWith("@js:")) return SelectorType.JS;
        if (r.startsWith("$.") || r.startsWith("$[")) return SelectorType.JSONPATH;
        if (r.startsWith("xpath:")) return SelectorType.XPATH;
        if (r.startsWith("json:")) return SelectorType.JSONPATH;
        if (r.startsWith("css:")) return SelectorType.CSS;
        return SelectorType.CSS;
    }

    public static List<Object> parseList(String html, String rule, String baseUrl, VariableStore vars) {
        List<Object> result = new ArrayList<>();
        if (rule == null || rule.isEmpty()) return result;
        if (html == null || html.isEmpty()) return result;

        String processedRule = vars != null ? vars.replaceVars(rule) : rule;

        SelectorType type = detectType(processedRule);
        String actualRule = stripPrefix(processedRule);

        try {
            if (type == SelectorType.JSONPATH || isJsonContent(html)) {
                return parseJsonList(html, actualRule);
            } else {
                return parseCssList(html, actualRule, baseUrl);
            }
        } catch (Exception e) {
            log.debug("列表解析失败: {} - {}", rule, e.getMessage());
            return result;
        }
    }

    public static String parseFirst(String html, String rule, String baseUrl, VariableStore vars) {
        if (rule == null || rule.isEmpty()) return "";
        if (html == null) return "";

        String processedRule = vars != null ? vars.replaceVars(rule) : rule;

        if (processedRule.startsWith("@put:")) {
            Matcher m = PUT_PATTERN.matcher(processedRule);
            if (m.find()) {
                String key = m.group(1);
                String valRule = m.group(2);
                String value = parseFirst(html, valRule, baseUrl, vars);
                if (vars != null) vars.put(key, value);
                return value;
            }
        }

        if (processedRule.startsWith("@putTmp:")) {
            Matcher m = PUT_TMP_PATTERN.matcher(processedRule);
            if (m.find()) {
                String key = m.group(1);
                String valRule = m.group(2);
                String value = parseFirst(html, valRule, baseUrl, vars);
                if (vars != null) vars.putTmp(key, value);
                return value;
            }
        }

        if (processedRule.startsWith("@getTmp:")) {
            Matcher m = GET_TMP_PATTERN.matcher(processedRule);
            if (m.find() && vars != null) {
                return vars.getTmp(m.group(1));
            }
        }

        if (processedRule.startsWith("@get:")) {
            Matcher m = GET_PATTERN.matcher(processedRule);
            if (m.find() && vars != null) {
                return vars.get(m.group(1));
            }
        }

        if (processedRule.contains("||")) {
            String[] parts = processedRule.split("\\|\\|");
            for (String part : parts) {
                String r = part.trim();
                if (r.isEmpty()) continue;
                String v = parseSingle(html, r, baseUrl, vars);
                if (v != null && !v.isEmpty()) return v.trim();
            }
            return "";
        }

        if (processedRule.contains("##")) {
            StringBuilder sb = new StringBuilder();
            String[] parts = processedRule.split("##");
            for (String part : parts) {
                String r = part.trim();
                if (r.isEmpty()) continue;
                if (r.startsWith("\"") && r.endsWith("\"")) {
                    sb.append(r.substring(1, r.length() - 1));
                } else {
                    sb.append(parseSingle(html, r, baseUrl, vars));
                }
            }
            return sb.toString().trim();
        }

        return parseSingle(html, processedRule, baseUrl, vars);
    }

    private static String parseSingle(String html, String rule, String baseUrl, VariableStore vars) {
        if (rule == null || rule.isEmpty()) return "";
        rule = rule.trim();

        SelectorType type = detectType(rule);
        String actualRule = stripPrefix(rule);

        try {
            if (type == SelectorType.JSONPATH || isJsonContent(html)) {
                return parseJsonField(html, actualRule);
            } else {
                return parseCssField(html, actualRule, baseUrl);
            }
        } catch (Exception e) {
            log.debug("字段解析失败: {} - {}", rule, e.getMessage());
            return "";
        }
    }

    private static List<Object> parseCssList(String html, String rule, String baseUrl) {
        List<Object> result = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html, baseUrl != null ? baseUrl : "");
            Elements items = doc.select(rule);
            for (Element item : items) {
                result.add(item);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String parseCssField(String html, String rule, String baseUrl) {
        try {
            Document doc = Jsoup.parse(html, baseUrl != null ? baseUrl : "");

            int atIdx = rule.lastIndexOf("@");
            String selector;
            String attr = "text";
            if (atIdx > 0) {
                selector = rule.substring(0, atIdx).trim();
                attr = rule.substring(atIdx + 1).trim();
            } else {
                selector = rule;
            }

            Element el = selector.isEmpty() ? doc.body() : doc.selectFirst(selector);
            if (el == null) return "";

            if ("text".equalsIgnoreCase(attr)) {
                return el.text().trim();
            }
            if ("html".equalsIgnoreCase(attr) || "outerHtml".equalsIgnoreCase(attr)) {
                return el.html();
            }
            if ("ownText".equalsIgnoreCase(attr)) {
                return el.ownText().trim();
            }
            if ("href".equalsIgnoreCase(attr) || "src".equalsIgnoreCase(attr)) {
                return el.attr(attr);
            }
            return el.attr(attr);
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Object> parseJsonList(String json, String rule) {
        List<Object> result = new ArrayList<>();
        try {
            String path = rule.startsWith("$") ? rule : "$." + rule;
            Object r = JsonPath.read(json, path);
            if (r instanceof List) {
                return (List<Object>) r;
            }
            if (r != null) {
                result.add(r);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String parseJsonField(String json, String rule) {
        try {
            String path = rule.startsWith("$") ? rule : "$." + rule;
            Object r = JsonPath.read(json, path);
            if (r == null) return "";
            if (r instanceof List) {
                List<?> list = (List<?>) r;
                if (list.isEmpty()) return "";
                return list.get(0) != null ? list.get(0).toString().trim() : "";
            }
            return r.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static String extractFromItem(Object item, String rule, VariableStore vars) {
        if (item == null || rule == null || rule.isEmpty()) return "";
        String processedRule = vars != null ? vars.replaceVars(rule) : rule;

        if (processedRule.startsWith("@get:")) {
            Matcher m = GET_PATTERN.matcher(processedRule);
            if (m.find() && vars != null) return vars.get(m.group(1));
        }
        if (processedRule.startsWith("@getTmp:")) {
            Matcher m = GET_TMP_PATTERN.matcher(processedRule);
            if (m.find() && vars != null) return vars.getTmp(m.group(1));
        }
        if (processedRule.contains("||")) {
            String[] parts = processedRule.split("\\|\\|");
            for (String part : parts) {
                String v = extractFromItemSingle(item, part.trim(), vars);
                if (v != null && !v.isEmpty()) return v.trim();
            }
            return "";
        }
        if (processedRule.contains("##")) {
            StringBuilder sb = new StringBuilder();
            String[] parts = processedRule.split("##");
            for (String part : parts) {
                String r = part.trim();
                if (r.isEmpty()) continue;
                if (r.startsWith("\"") && r.endsWith("\"")) {
                    sb.append(r.substring(1, r.length() - 1));
                } else {
                    sb.append(extractFromItemSingle(item, r, vars));
                }
            }
            return sb.toString().trim();
        }
        return extractFromItemSingle(item, processedRule, vars);
    }

    private static String extractFromItemSingle(Object item, String rule, VariableStore vars) {
        if (item == null || rule == null) return "";
        String r = stripPrefix(rule.trim());
        if (r.isEmpty()) return "";

        if (item instanceof Element) {
            Element el = (Element) item;
            int atIdx = r.lastIndexOf("@");
            String selector;
            String attr = "text";
            if (atIdx > 0) {
                selector = r.substring(0, atIdx).trim();
                attr = r.substring(atIdx + 1).trim();
            } else {
                selector = r;
            }
            Element target = selector.isEmpty() ? el : el.selectFirst(selector);
            if (target == null) return "";
            if ("text".equalsIgnoreCase(attr)) return target.text().trim();
            if ("html".equalsIgnoreCase(attr)) return target.html();
            if ("ownText".equalsIgnoreCase(attr)) return target.ownText().trim();
            return target.attr(attr);
        }

        if (item instanceof Map) {
            return extractFromMap((Map<String, Object>) item, r);
        }
        return "";
    }

    private static String extractFromMap(Map<String, Object> map, String path) {
        try {
            String[] keys = path.split("\\.");
            Object current = map;
            for (String key : keys) {
                if (current == null) return "";
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(key);
                } else {
                    return "";
                }
            }
            return current != null ? current.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static String applyReplace(String content, String[] replaceRules) {
        if (content == null || content.isEmpty()) return "";
        if (replaceRules == null || replaceRules.length == 0) return content;
        String result = content;
        for (String rule : replaceRules) {
            if (rule == null || rule.isEmpty()) continue;
            String[] parts = rule.split("\\$\\$", 2);
            if (parts.length == 2) {
                try {
                    result = result.replaceAll(parts[0], parts[1]);
                } catch (Exception e) {
                    result = result.replace(parts[0], parts[1]);
                }
            } else {
                result = result.replace(rule, "");
            }
        }
        return result;
    }

    private static String stripPrefix(String rule) {
        if (rule == null) return null;
        String r = rule.trim();
        if (r.startsWith("css:")) return r.substring(4).trim();
        if (r.startsWith("json:")) return r.substring(5).trim();
        if (r.startsWith("xpath:")) return r.substring(6).trim();
        return r;
    }

    private static boolean isJsonContent(String content) {
        if (content == null) return false;
        String t = content.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }
}
