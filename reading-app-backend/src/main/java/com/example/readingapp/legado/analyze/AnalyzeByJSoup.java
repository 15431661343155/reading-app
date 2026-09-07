package com.example.readingapp.legado.analyze;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTML/CSS 选择器解析适配器（基于 jsoup）。
 * 规则格式如 ".book-title@text" / ".book-title@href" / "div.book@attr:src"，
 * 纯属性取值 "@text" / "@html" / "@innerHtml" 直接作用于当前元素。
 */
@Slf4j
public class AnalyzeByJSoup {

    private final Element root;

    public AnalyzeByJSoup(Object content) {
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content instanceof Element) {
            this.root = (Element) content;
        } else {
            // String HTML：用 jsoup 解析为 Document（Document 继承自 Element）
            this.root = Jsoup.parse(content.toString());
        }
    }

    public String getString(String rule) {
        if (rule == null || rule.isEmpty()) return null;
        try {
            String r = rule.trim();
            // 去除 @CSS: 前缀（Legado AnalyzeRule.SourceRule 保留前缀，由本类处理）
            if (r.regionMatches(true, 0, "@CSS:", 0, 5)) {
                r = r.substring(5).trim();
            }
            // 纯属性取值，作用于当前元素
            if (r.equals("@text")) return root.text();
            if (r.equals("@html") || r.equals("@innerHtml")) return root.html();
            if (r.equals("@outerHtml")) return root.outerHtml();
            if (r.equals("@ownText")) return root.ownText();

            // selector@attr 形式
            String selector;
            String attr;
            int atIdx = r.lastIndexOf("@");
            if (atIdx > 0) {
                selector = r.substring(0, atIdx).trim();
                attr = r.substring(atIdx + 1).trim();
            } else {
                selector = r;
                attr = "text";
            }
            if (selector.isEmpty()) {
                return extractAttr(root, attr);
            }
            Element el = root.selectFirst(formatCss(selector));
            if (el == null) return null;
            return extractAttr(el, attr);
        } catch (Exception e) {
            log.debug("JSoup 解析失败 rule={} err={}", rule, e.getMessage());
            return null;
        }
    }

    /**
     * 获取一个字符串（不抛异常，空时返回 ""）。
     * 对应 Kotlin AnalyzeByJSoup.getString0。
     * Legado AnalyzeRule.getString 在 isUrl=true 且 mode=Default 时调用此方法。
     */
    public String getString0(String rule) {
        List<String> list = getStringList(rule);
        return list.isEmpty() ? "" : list.get(0);
    }

    public List<String> getStringList(String rule) {
        if (rule == null || rule.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        try {
            // 去除 @CSS: 前缀
            String ruleTrimmed = rule.trim();
            if (ruleTrimmed.regionMatches(true, 0, "@CSS:", 0, 5)) {
                ruleTrimmed = ruleTrimmed.substring(5).trim();
            }
            // 多个选择器以 && 分隔
            String[] parts = ruleTrimmed.split("&&");
            for (String part : parts) {
                String p = part.trim();
                if (p.isEmpty()) continue;

                String selector;
                String attr;
                if (p.startsWith("@")) {
                    // 纯属性作用于当前元素
                    selector = "";
                    attr = p.substring(1);
                } else {
                    int atIdx = p.lastIndexOf("@");
                    if (atIdx > 0) {
                        selector = p.substring(0, atIdx).trim();
                        attr = p.substring(atIdx + 1).trim();
                    } else {
                        selector = p;
                        attr = "text";
                    }
                }

                Elements elements = selector.isEmpty()
                        ? new Elements(root)
                        : root.select(formatCss(selector));
                for (Element el : elements) {
                    String v = extractAttr(el, attr);
                    if (v != null && !v.isEmpty()) {
                        result.add(v);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("JSoup getStringList 失败 rule={} err={}", rule, e.getMessage());
        }
        return result;
    }

    public List<Element> getElements(String rule) {
        if (rule == null || rule.isEmpty()) return Collections.emptyList();
        List<Element> result = new ArrayList<>();
        try {
            String r = rule.trim();
            // 去除 @CSS: 前缀
            if (r.regionMatches(true, 0, "@CSS:", 0, 5)) {
                r = r.substring(5).trim();
            }
            Elements elements = root.select(formatCss(r));
            for (Element el : elements) {
                result.add(el);
            }
        } catch (Exception e) {
            log.debug("JSoup getElements 失败 rule={} err={}", rule, e.getMessage());
        }
        return result;
    }

    public Element getObject(String rule) {
        if (rule == null || rule.isEmpty()) return null;
        try {
            String r = rule.trim();
            // 去除 @CSS: 前缀
            if (r.regionMatches(true, 0, "@CSS:", 0, 5)) {
                r = r.substring(5).trim();
            }
            return root.selectFirst(formatCss(r));
        } catch (Exception e) {
            log.debug("JSoup getObject 失败 rule={} err={}", rule, e.getMessage());
            return null;
        }
    }

    private String extractAttr(Element el, String attr) {
        if (attr == null || attr.isEmpty() || "text".equalsIgnoreCase(attr)) {
            return el.text();
        }
        if ("html".equalsIgnoreCase(attr) || "innerHtml".equalsIgnoreCase(attr)) {
            return el.html();
        }
        if ("outerHtml".equalsIgnoreCase(attr)) {
            return el.outerHtml();
        }
        if ("ownText".equalsIgnoreCase(attr)) {
            return el.ownText();
        }
        if (attr.startsWith("attr:")) {
            return el.attr(attr.substring(5));
        }
        // 直接当属性名，如 href / src / title
        return el.attr(attr);
    }

    /**
     * Legado CSS 扩展语法转换：把索引选择器 {@code tag.<n>} 转为 jsoup 支持的 {@code tag:eq(<n>)}。
     *
     * <p>Legado 书源常用 {@code td.0} / {@code td.1} 表示第 n 个 td 子元素，
     * 而标准 CSS 中 {@code .0} 是非法类选择器（HTML/CSS 类名不能以数字开头），
     * jsoup 会将其作为不存在的类名解析 → 永远匹配不到 → 解析失败（如夜伴书屋
     * {@code td.0 a@text##《|》} 解析出 0 本书）。
     *
     * <p>移植自 Legado {@code AnalyzeByJSoupKt.formatCss}：仅当 {@code .}
     * 后紧跟数字、且 {@code .} 之前是标识符字符（字母/数字/下划线/{@code *}/{@code )}）时
     * 才转换为 {@code :eq(<n>)}，避免误伤浮点数属性值（如 {@code [data-x="0.5"]}）。
     */
    private static String formatCss(String css) {
        if (css == null || css.isEmpty()) return css;
        char[] arr = css.toCharArray();
        StringBuilder sb = new StringBuilder(arr.length + 8);
        int ruleStart = 0;
        int i = 0;
        while (i < arr.length) {
            char c = arr[i];
            if (c == '.' && i + 1 < arr.length && Character.isDigit(arr[i + 1])) {
                // 前一字符必须是标识符尾部（字母/数字/下划线/* /)），否则视为类名前缀，跳过
                boolean prevOk = i > 0;
                if (prevOk) {
                    char p = arr[i - 1];
                    prevOk = Character.isLetterOrDigit(p) || p == '_' || p == '*' || p == ')';
                }
                if (prevOk) {
                    if (i > ruleStart) sb.append(css, ruleStart, i);
                    sb.append(":eq(");
                    int j = i + 1;
                    while (j < arr.length && Character.isDigit(arr[j])) {
                        sb.append(arr[j]);
                        j++;
                    }
                    sb.append(')');
                    i = j;
                    ruleStart = i;
                    continue;
                }
            }
            i++;
        }
        if (ruleStart < arr.length) {
            sb.append(css, ruleStart, arr.length);
        }
        return sb.toString();
    }
}
