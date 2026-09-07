package com.example.readingapp.legado.analyze;

import lombok.extern.slf4j.Slf4j;
import org.jaxen.XPath;
import org.jaxen.dom.DOMXPath;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * XPath 解析适配器（基于 Jaxen + W3C DOM）。
 * 构造函数接受 String XML/HTML 或 org.w3c.dom.Document。
 * 解析 HTML 为 DOM 时用 jsoup 的 Jsoup.parse 再通过 W3CDom 转为 W3C Document。
 */
@Slf4j
public class AnalyzeByXPath {

    private final Document document;

    public AnalyzeByXPath(Object content) {
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content instanceof Document) {
            this.document = (Document) content;
        } else {
            // 用 jsoup 解析 HTML，再转 W3C Document（jsoup 提供 W3C 兼容接口）
            org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(content.toString());
            this.document = new W3CDom().fromJsoup(jsoupDoc);
        }
    }

    public String getString(String rule) {
        if (rule == null || rule.isEmpty()) return null;
        try {
            XPath xpath = new DOMXPath(stripPrefix(rule.trim()));
            String result = xpath.stringValueOf(document);
            return result == null ? null : result.trim();
        } catch (Exception e) {
            log.debug("XPath getString 失败 rule={} err={}", rule, e.getMessage());
            return null;
        }
    }

    public List<String> getStringList(String rule) {
        if (rule == null || rule.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        try {
            XPath xpath = new DOMXPath(stripPrefix(rule.trim()));
            List<?> nodes = xpath.selectNodes(document);
            for (Object o : nodes) {
                if (o != null) {
                    String t = nodeText(o);
                    if (t != null && !t.isEmpty()) {
                        result.add(t);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("XPath getStringList 失败 rule={} err={}", rule, e.getMessage());
        }
        return result;
    }

    public List<Object> getElements(String rule) {
        if (rule == null || rule.isEmpty()) return Collections.emptyList();
        List<Object> result = new ArrayList<>();
        try {
            XPath xpath = new DOMXPath(stripPrefix(rule.trim()));
            List<?> nodes = xpath.selectNodes(document);
            for (Object o : nodes) {
                if (o != null) {
                    result.add(o);
                }
            }
        } catch (Exception e) {
            log.debug("XPath getElements 失败 rule={} err={}", rule, e.getMessage());
        }
        return result;
    }

    private String nodeText(Object o) {
        if (o instanceof Node) {
            String t = ((Node) o).getTextContent();
            return t == null ? null : t.trim();
        }
        return o.toString();
    }

    private String stripPrefix(String rule) {
        if (rule.startsWith("xpath:")) {
            return rule.substring(6).trim();
        }
        if (rule.startsWith("@XPath:")) {
            return rule.substring(7).trim();
        }
        return rule;
    }
}
