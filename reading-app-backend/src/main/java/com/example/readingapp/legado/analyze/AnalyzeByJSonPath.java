package com.example.readingapp.legado.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSONPath 解析适配器（基于 com.jayway.jsonpath）。
 * 构造函数接受 String JSON 或已解析对象（Map/List），
 * 规则支持标准 JSONPath（$.xxx）或简写（xxx 自动补齐 $.）。
 */
@Slf4j
public class AnalyzeByJSonPath {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Object content;

    public AnalyzeByJSonPath(Object content) {
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        // 若 content 为 String，先用 Jackson 解析为 Map/List，
        // 再交给 JsonPath.read（JsonPath.read(Object,...) 不会自动解析 String）
        Object parsed;
        if (content instanceof String) {
            try {
                parsed = MAPPER.readValue((String) content, Object.class);
            } catch (Exception e) {
                // 解析失败时保留原始字符串，后续 JsonPath 会自行处理
                parsed = content;
            }
        } else {
            parsed = content;
        }
        this.content = parsed;
    }

    public String getString(String rule) {
        if (rule == null || rule.isEmpty()) return null;
        try {
            Object r = JsonPath.read(content, normalize(rule));
            if (r == null) return null;
            if (r instanceof List) {
                List<?> list = (List<?>) r;
                if (list.isEmpty()) return null;
                Object first = list.get(0);
                return first == null ? null : first.toString();
            }
            return r.toString();
        } catch (Exception e) {
            log.debug("JsonPath getString 失败 rule={} err={}", rule, e.getMessage());
            return null;
        }
    }

    public List<String> getStringList(String rule) {
        if (rule == null || rule.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        try {
            Object r = JsonPath.read(content, normalize(rule));
            if (r instanceof List) {
                for (Object o : (List<?>) r) {
                    if (o != null) {
                        result.add(o.toString());
                    }
                }
            } else if (r != null) {
                result.add(r.toString());
            }
        } catch (Exception e) {
            log.debug("JsonPath getStringList 失败 rule={} err={}", rule, e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getList(String rule) {
        if (rule == null || rule.isEmpty()) return Collections.emptyList();
        try {
            Object r = JsonPath.read(content, normalize(rule));
            if (r instanceof List) {
                return (List<Object>) r;
            }
            List<Object> result = new ArrayList<>();
            if (r != null) {
                result.add(r);
            }
            return result;
        } catch (Exception e) {
            log.debug("JsonPath getList 失败 rule={} err={}", rule, e.getMessage());
            return Collections.emptyList();
        }
    }

    public Object getObject(String rule) {
        if (rule == null || rule.isEmpty()) return null;
        try {
            return JsonPath.read(content, normalize(rule));
        } catch (Exception e) {
            log.debug("JsonPath getObject 失败 rule={} err={}", rule, e.getMessage());
            return null;
        }
    }

    private String normalize(String rule) {
        String r = rule.trim();
        if (r.startsWith("json:")) {
            r = r.substring(5).trim();
        }
        if (!r.startsWith("$")) {
            r = "$." + r;
        }
        return r;
    }
}
