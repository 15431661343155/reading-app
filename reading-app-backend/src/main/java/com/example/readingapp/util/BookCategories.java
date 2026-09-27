package com.example.readingapp.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 书籍「子分类（多选）」的存储编解码 —— 唯一实现，全项目共用。
 *
 * <p>落地形态：{@code book.sub_categories} 列存 JSON 数组字符串（与既有 {@code tags} 列同一约定），
 * 例如 {@code ["玄幻","都市"]}。所有读/写子分类的代码都必须走本类，
 * 避免出现 {@code ["玄幻"]} / {@code 玄幻,都市} / 单值混存的脏数据。
 *
 * <p>为什么不用逗号分隔：分类名本身可能含标点；JSON 数组便于精确匹配（LIKE '%"玄幻"%'），
 * 也与前端直接 JSON.stringify / JSON.parse 对接。
 */
public final class BookCategories {

    /** 单本书最多允许的子分类数量 */
    public static final int MAX_SUBS = 10;
    /** 单个分类名长度上限（与 book_category.name 列宽一致） */
    public static final int MAX_NAME_LEN = 50;
    /** 空集合的规范写法：写入时用 "[]" 而不是 null，null 专留给「改造前的历史数据」 */
    public static final String EMPTY = "[]";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BookCategories() {
    }

    /**
     * 解析存储值。容错处理：JSON 数组 / 逗号或分号分隔 / 单个裸值 / null / 空串。
     * 非法输入一律退化为「尽量切分」，绝不抛异常（分类解析失败不该影响书籍读取）。
     */
    public static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return out;
        }
        if (s.startsWith("[")) {
            try {
                List<?> list = MAPPER.readValue(s, new TypeReference<List<?>>() {
                });
                for (Object o : list) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
                return out;
            } catch (Exception ignored) {
                // 非法 JSON：退化为去掉首尾方括号后按分隔符切
                s = s.substring(1, s.length() - 1);
            }
        }
        for (String part : s.split("[,，;；]")) {
            out.add(part);
        }
        return out;
    }

    /**
     * 规范化：去空白、去掉会破坏 JSON 与 LIKE 匹配的引号/反斜杠、去重（保持顺序）、限长限量。
     * 返回的列表可直接入库。
     */
    public static List<String> normalize(List<String> names) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (names != null) {
            for (String n : names) {
                if (n == null) {
                    continue;
                }
                String v = n.replace("\"", "").replace("\\", "").trim();
                if (v.isEmpty()) {
                    continue;
                }
                if (v.length() > MAX_NAME_LEN) {
                    v = v.substring(0, MAX_NAME_LEN);
                }
                set.add(v);
                if (set.size() >= MAX_SUBS) {
                    break;
                }
            }
        }
        return new ArrayList<>(set);
    }

    /** 列表 → 存储字符串（JSON 数组） */
    public static String toJson(List<String> names) {
        List<String> normalized = normalize(names);
        if (normalized.isEmpty()) {
            return EMPTY;
        }
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            // 兜底：极端情况下手工拼，, 与 ] 已在 normalize 中过滤掉引号，这里再保证一下
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < normalized.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(normalized.get(i)).append('"');
            }
            return sb.append(']').toString();
        }
    }

    /** 任意来源的存储值/前端传值 → 规范化后的存储字符串（写库前统一走这里） */
    public static String toStorage(String raw) {
        return toJson(parse(raw));
    }

    /** 已入库的存储值里是否包含某个子分类（精确匹配，不做包含式匹配） */
    public static boolean contains(String raw, String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        return parse(raw).contains(name.trim());
    }

    /** 供界面展示的文本形式：玄幻 / 都市 */
    public static String joinForDisplay(String raw) {
        return String.join(" / ", parse(raw));
    }
}
