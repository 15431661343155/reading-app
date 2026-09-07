package com.example.readingapp.legado.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LegadoBookSource {

    private String bookSourceName;

    private String bookSourceGroup;

    /** baseUrl */
    private String bookSourceUrl;

    /** 0=文本, 1=有声书, 2=图片, 3=RSS */
    private int bookSourceType;

    private String bookSourceComment;

    private boolean enabled;

    private boolean enabledExplore;

    private boolean enabledCookieJar;

    /** JSON 格式的请求头 */
    private String header;

    private String loginUrl;

    /** JSON 格式的登录UI配置 */
    private String loginUi;

    private String loginCheckJs;

    private boolean personalLogin;

    private String searchUrl;

    private String exploreUrl;

    private long lastUpdateTime;

    private int weight;

    private SearchRule ruleSearch;

    /**
     * 发现页规则。
     * <p>Legado 书源中此字段既可以是单个对象，也可以是数组（多套发现页规则），
     * 故此处使用 {@link JsonNode} 容纳两种形态，避免反序列化失败。
     * 使用时可通过 {@link #getExploreRuleAsObject()} 获取首个 ExploreRule。
     */
    private JsonNode ruleExplore;

    private BookInfoRule ruleBookInfo;

    private TocRule ruleToc;

    private ContentRule ruleContent;

    /** JavaScript 库代码 */
    private String jsLib;

    private int customOrder;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExploreRule {

        private String bookList;
        private String name;
        private String author;
        private String intro;
        private String kind;
        private String lastChapter;
        private String updateTime;
        private String coverUrl;
        private String wordCount;
        private String bookUrl;
    }

    /**
     * ruleExplore 可能是单个对象或对象数组；统一取第一项返回，便于单页发现解析。
     */
    public ExploreRule getExploreRuleAsObject() {
        if (ruleExplore == null || ruleExplore.isNull() || ruleExplore.isMissingNode()) {
            return null;
        }
        JsonNode target = ruleExplore;
        if (ruleExplore.isArray()) {
            if (ruleExplore.isEmpty()) return null;
            target = ruleExplore.get(0);
        }
        try {
            return MAPPER_CONTAINER.MAPPER.treeToValue(target, ExploreRule.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 返回「字符串形态」的书源类型标识，便于做包含判断（兼容 int/string 配置）。
     */
    public String getBookSourceTypeStr() {
        // 若未来扩展有字符串类型字段则优先，目前仅基于 int + 名称做兜底
        return String.valueOf(bookSourceType);
    }

    /** 内部持有 ObjectMapper 实例，避免影响主类 lombok @Data 生成逻辑 */
    private static final class MAPPER_CONTAINER {
        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER
                = new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
