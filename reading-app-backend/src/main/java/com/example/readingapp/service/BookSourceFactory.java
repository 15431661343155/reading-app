package com.example.readingapp.service;

import com.example.readingapp.entity.BookSource;
import com.example.readingapp.legado.LegadoSourceAdapter;
import com.example.readingapp.legado.model.LegadoBookSource;
import com.example.readingapp.repository.BookSourceRepository;
import com.example.readingapp.service.impl.GuoxuedashiBookSourceServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookSourceFactory {

    private final GuoxuedashiBookSourceServiceImpl guoxuedashiService;
    private final BookSourceRepository bookSourceRepository;

    private Map<String, OnlineBookSourceService> serviceMap = new HashMap<>();
    private List<SourceInfo> sourceList = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public synchronized void init() {
        serviceMap.clear();
        sourceList.clear();

        // 内置公版书源
        serviceMap.put("GUOXUEDASHI", guoxuedashiService);

        sourceList.add(new SourceInfo("GUOXUEDASHI", "国学大师", "国内访问最快，收录2万部古籍"));

        // 加载自定义 Legado 书源（直接使用原生 Legado 引擎解析，不再转换格式）
        try {
            List<BookSource> customSources = bookSourceRepository.findAllByConfigJsonIsNotNullAndEnabledTrue();
            for (BookSource bs : customSources) {
                try {
                    if (bs.getConfigJson() == null || bs.getConfigJson().isEmpty()) continue;

                    // 直接解析为 LegadoBookSource 原生模型
                    LegadoBookSource legadoSource = objectMapper.readValue(
                        bs.getConfigJson(), LegadoBookSource.class);

                    // 补充 baseUrl（数据库存储的优先）并清洗
                    String baseUrl = legadoSource.getBookSourceUrl();
                    if (baseUrl == null || baseUrl.isEmpty()) {
                        baseUrl = bs.getBaseUrl();
                    }
                    // 清洗 baseUrl：去除全角注释等非法字符
                    baseUrl = com.example.readingapp.legado.LegadoBookSourceService.sanitizeBaseUrl(baseUrl);
                    legadoSource.setBookSourceUrl(baseUrl);

                    // 用适配器包装为 OnlineBookSourceService
                    LegadoSourceAdapter adapter = new LegadoSourceAdapter(
                        legadoSource, bs.getSourceType(), bs.getName());

                    serviceMap.put(bs.getSourceType(), adapter);
                    sourceList.add(new SourceInfo(bs.getSourceType(), bs.getName(), bs.getDescription()));

                    log.info("加载 Legado 书源 [{}] baseUrl={}, searchUrl={}, bookList={}",
                        bs.getName(),
                        baseUrl,
                        legadoSource.getSearchUrl() != null ? legadoSource.getSearchUrl() : "(空)",
                        legadoSource.getRuleSearch() != null ? legadoSource.getRuleSearch().getBookList() : "(空)");
                } catch (Exception e) {
                    log.warn("加载自定义书源失败: {} - {}", bs.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("加载自定义书源失败: {}", e.getMessage());
        }
    }

    public synchronized void refresh() {
        init();
    }

    public OnlineBookSourceService getService(String sourceType) {
        if (serviceMap.isEmpty()) {
            init();
        }
        return serviceMap.get(sourceType);
    }

    public List<SourceInfo> getAvailableSources() {
        if (sourceList.isEmpty()) {
            init();
        }
        return sourceList;
    }

    public static class SourceInfo {
        private String type;
        private String name;
        private String description;

        public SourceInfo(String type, String name, String description) {
            this.type = type;
            this.name = name;
            this.description = description;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
