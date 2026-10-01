package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.ImportResult;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.BookSource;
import com.example.readingapp.legado.model.LegadoBookSource;
import com.example.readingapp.repository.BookSourceRepository;
import com.example.readingapp.service.BookSourceFactory;
import com.example.readingapp.service.OnlineBookSourceService;
import com.example.readingapp.util.SafeUrlGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/admin/book-source")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminBookSourceController {

    /** 匹配 Legado 书源中没有意义的纯数字 sourceType */
    private static final Pattern PURE_NUMERIC_TYPE = Pattern.compile("^\\d+(?:_\\d+)*$");

    private final BookSourceRepository bookSourceRepository;
    private final BookSourceFactory bookSourceFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/list")
    public ApiResponse<List<BookSource>> list() {
        try {
            return ApiResponse.success(bookSourceRepository.findAll());
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/add")
    public ApiResponse<BookSource> add(@RequestBody BookSource bookSource) {
        try {
            if (bookSource.getName() == null || bookSource.getName().isEmpty()) {
                return ApiResponse.error("书源名称不能为空");
            }
            if (bookSource.getSourceType() == null || bookSource.getSourceType().isEmpty()) {
                return ApiResponse.error("书源类型不能为空");
            }
            if (bookSourceRepository.findBySourceType(bookSource.getSourceType()) != null) {
                return ApiResponse.error("书源类型已存在: " + bookSource.getSourceType());
            }
            if (bookSource.getConfigJson() != null && !bookSource.getConfigJson().isEmpty()) {
                try {
                    objectMapper.readValue(bookSource.getConfigJson(), LegadoBookSource.class);
                } catch (Exception e) {
                    return ApiResponse.error("JSON 配置格式错误: " + e.getMessage());
                }
            }
            if (bookSource.getEnabled() == null) bookSource.setEnabled(true);
            if (bookSource.getBuiltIn() == null) bookSource.setBuiltIn(false);
            if (bookSource.getBookCount() == null) bookSource.setBookCount(0);
            BookSource saved = bookSourceRepository.save(bookSource);
            bookSourceFactory.refresh();
            return ApiResponse.success(saved);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/update")
    public ApiResponse<BookSource> update(@RequestBody BookSource bookSource) {
        try {
            BookSource existing = bookSourceRepository.findById(bookSource.getId()).orElse(null);
            if (existing == null) return ApiResponse.error("书源不存在");

            if (bookSource.getName() != null) existing.setName(bookSource.getName());
            if (bookSource.getDescription() != null) existing.setDescription(bookSource.getDescription());
            if (bookSource.getBaseUrl() != null) existing.setBaseUrl(bookSource.getBaseUrl());
            if (bookSource.getEnabled() != null) existing.setEnabled(bookSource.getEnabled());
            if (bookSource.getRemark() != null) existing.setRemark(bookSource.getRemark());
            if (bookSource.getConfigJson() != null) existing.setConfigJson(bookSource.getConfigJson());
            if (bookSource.getSourceGroup() != null) existing.setSourceGroup(bookSource.getSourceGroup());

            if (existing.getConfigJson() != null && !existing.getConfigJson().isEmpty()) {
                try {
                    objectMapper.readValue(existing.getConfigJson(), LegadoBookSource.class);
                } catch (Exception e) {
                    return ApiResponse.error("JSON 配置格式错误: " + e.getMessage());
                }
            }
            BookSource saved = bookSourceRepository.save(existing);
            bookSourceFactory.refresh();
            return ApiResponse.success(saved);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            BookSource existing = bookSourceRepository.findById(id).orElse(null);
            if (existing == null) return ApiResponse.error("书源不存在");
            if (existing.getBuiltIn() != null && existing.getBuiltIn()) return ApiResponse.error("内置书源不能删除");
            bookSourceRepository.deleteById(id);
            bookSourceFactory.refresh();
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<BookSource> toggle(@PathVariable Long id) {
        try {
            BookSource existing = bookSourceRepository.findById(id).orElse(null);
            if (existing == null) return ApiResponse.error("书源不存在");
            existing.setEnabled(existing.getEnabled() == null ? true : !existing.getEnabled());
            BookSource saved = bookSourceRepository.save(existing);
            bookSourceFactory.refresh();
            return ApiResponse.success(saved);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/batch/toggle")
    public ApiResponse<ImportResult> batchToggle(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Number> idsObj = (List<Number>) body.get("ids");
            Boolean enabled = (Boolean) body.get("enabled");
            if (idsObj == null || idsObj.isEmpty()) return ApiResponse.error("请选择要操作的书源");
            List<Long> ids = new ArrayList<>();
            for (Number n : idsObj) ids.add(n.longValue());

            ImportResult result = new ImportResult();
            result.setTotal(ids.size());
            for (BookSource bs : bookSourceRepository.findAllById(ids)) {
                if (bs.getBuiltIn() != null && bs.getBuiltIn()) {
                    result.setSkipped(result.getSkipped() + 1);
                    result.addMessage("【" + bs.getName() + "】内置书源跳过");
                    continue;
                }
                bs.setEnabled(enabled != null ? enabled : true);
                bookSourceRepository.save(bs);
                result.setSuccess(result.getSuccess() + 1);
            }
            result.setFailed(result.getTotal() - result.getSuccess() - result.getSkipped());
            bookSourceFactory.refresh();
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/batch/delete")
    public ApiResponse<ImportResult> batchDelete(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Number> idsObj = (List<Number>) body.get("ids");
            if (idsObj == null || idsObj.isEmpty()) return ApiResponse.error("请选择要删除的书源");
            List<Long> ids = new ArrayList<>();
            for (Number n : idsObj) ids.add(n.longValue());

            ImportResult result = new ImportResult();
            result.setTotal(ids.size());
            for (BookSource bs : bookSourceRepository.findAllById(ids)) {
                if (bs.getBuiltIn() != null && bs.getBuiltIn()) {
                    result.setSkipped(result.getSkipped() + 1);
                    result.addMessage("【" + bs.getName() + "】内置书源无法删除");
                    continue;
                }
                bookSourceRepository.delete(bs);
                result.setSuccess(result.getSuccess() + 1);
                result.addMessage("【" + bs.getName() + "】已删除");
            }
            result.setFailed(result.getTotal() - result.getSuccess() - result.getSkipped());
            bookSourceFactory.refresh();
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/import-url")
    public ApiResponse<ImportResult> importFromUrl(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.trim().isEmpty()) return ApiResponse.error("URL 不能为空");
        // SSRF 防护：URL 由外部直接传入（匿名可调），入口处校验并回显明确错误
        try {
            SafeUrlGuard.check(url.trim());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
        try {
            String json = fetchUrl(url.trim());
            if (json == null || json.trim().isEmpty()) return ApiResponse.error("下载内容为空");

            ImportResult result = new ImportResult();
            json = json.trim();

            List<Map<String, Object>> sourceList;
            if (json.startsWith("[")) {
                sourceList = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            } else {
                Map<String, Object> single = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                sourceList = List.of(single);
            }

            result.setTotal(sourceList.size());

            for (Map<String, Object> item : sourceList) {
                try {
                    String name = getString(item, "bookSourceName", "name", "bookName");
                    String sourceType = getString(item, "sourceType", "type", "bookSourceType");
                    String baseUrl = getString(item, "bookSourceUrl", "baseUrl", "url");
                    String description = getString(item, "bookSourceComment", "description", "desc", "intro");
                    String sourceGroup = getString(item, "bookSourceGroup", "sourceGroup", "group");
                    String remark = getString(item, "remark", "comment", "note");

                    // 纯数字 sourceType 重映射
                    if (sourceType != null && PURE_NUMERIC_TYPE.matcher(sourceType).matches()) {
                        String old = sourceType;
                        sourceType = null;
                        remark = (remark == null ? "" : remark + "\n") + "Legado 原 sourceType=\"" + old + "\" 已重映射";
                    }

                    // 直接保存完整的 Legado JSON 作为 configJson（不再转换格式）
                    String configJson;
                    try {
                        configJson = objectMapper.writeValueAsString(item);
                    } catch (Exception e) {
                        result.setFailed(result.getFailed() + 1);
                        result.addMessage("【" + (name != null ? name : sourceType) + "】JSON 序列化失败: " + e.getMessage());
                        continue;
                    }

                    // 用 LegadoBookSource 模型校验（检查 searchUrl 是否存在）
                    try {
                        LegadoBookSource legado = objectMapper.readValue(configJson, LegadoBookSource.class);
                        if (legado.getSearchUrl() == null || legado.getSearchUrl().isEmpty()) {
                            result.setFailed(result.getFailed() + 1);
                            result.addMessage("【" + (name != null ? name : sourceType) + "】搜索URL为空，请检查书源");
                            continue;
                        }
                    } catch (Exception e) {
                        result.setFailed(result.getFailed() + 1);
                        result.addMessage("【" + (name != null ? name : sourceType) + "】Legado 格式解析失败: " + e.getMessage());
                        continue;
                    }

                    // 生成唯一 sourceType
                    if (sourceType == null || sourceType.isEmpty()) {
                        if (name != null && !name.isEmpty()) {
                            sourceType = name.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
                            if (sourceType.length() > 16) sourceType = sourceType.substring(0, 16);
                            if (sourceType.isEmpty()) sourceType = "CUSTOM_" + Math.abs(name.hashCode()) % 100000;
                        } else {
                            sourceType = "CUSTOM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
                        }
                    }
                    String baseSourceType = sourceType;
                    int suffix = 1;
                    while (bookSourceRepository.findBySourceType(sourceType) != null) {
                        sourceType = baseSourceType + "_" + suffix++;
                        if (suffix > 100) {
                            sourceType = baseSourceType + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
                            break;
                        }
                    }

                    BookSource bs = new BookSource();
                    bs.setName(truncate(name != null ? name : sourceType, 200));
                    bs.setSourceType(truncate(sourceType, 50));
                    bs.setDescription(description);
                    bs.setBaseUrl(baseUrl);
                    bs.setSourceGroup(truncate(sourceGroup, 200));
                    bs.setRemark(remark);
                    bs.setConfigJson(configJson);
                    bs.setEnabled(true);
                    bs.setBuiltIn(false);
                    bs.setBookCount(0);

                    bookSourceRepository.save(bs);
                    result.setSuccess(result.getSuccess() + 1);
                    result.addMessage("【" + bs.getName() + "】导入成功");
                } catch (Exception e) {
                    result.setFailed(result.getFailed() + 1);
                    result.addMessage("导入失败: " + e.getMessage());
                }
            }

            bookSourceFactory.refresh();
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("网络导入书源失败", e);
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    @PostMapping("/test-search")
    public ApiResponse<Map<String, Object>> testSearch(@RequestBody BookSource bookSource) {
        try {
            if (bookSource.getConfigJson() == null || bookSource.getConfigJson().isEmpty()) {
                return ApiResponse.error("书源配置为空");
            }

            LegadoBookSource legado;
            try {
                legado = objectMapper.readValue(bookSource.getConfigJson(), LegadoBookSource.class);
            } catch (Exception e) {
                return ApiResponse.error("JSON 格式错误: " + e.getMessage());
            }

            String searchUrl = legado.getSearchUrl() != null ? legado.getSearchUrl() : "";
            String bookList = legado.getRuleSearch() != null ? legado.getRuleSearch().getBookList() : "";
            String nameRule = legado.getRuleSearch() != null ? legado.getRuleSearch().getName() : "";

            String keyword = "斗破苍穹";
            String sourceType = bookSource.getSourceType();
            if (sourceType == null || sourceType.isEmpty()) {
                sourceType = "TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            }

            // 优先从 Factory 获取，若不存在则创建瞬态适配器
            OnlineBookSourceService svc = bookSourceFactory.getService(sourceType);
            List<Book> books = null;
            int bookCount = 0;
            String error = null;
            try {
                if (svc == null) {
                    // 书源未注册（未保存或未刷新），创建瞬态适配器
                    svc = new com.example.readingapp.legado.LegadoSourceAdapter(
                            legado, sourceType, bookSource.getName() != null ? bookSource.getName() : sourceType);
                }
                books = svc.searchBooks(keyword, sourceType, 0, 10);
                bookCount = books != null ? books.size() : 0;
            } catch (Exception e) {
                error = e.getMessage();
            }

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("searchUrl", searchUrl);
            result.put("baseUrl", legado.getBookSourceUrl());
            result.put("listSelector", bookList);
            result.put("nameSelector", nameRule);
            result.put("bookCount", bookCount);
            result.put("error", error);
            if (books != null && !books.isEmpty()) {
                result.put("sampleBook", books.get(0).getTitle() + " / " + books.get(0).getAuthor());
            }
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("测试失败: " + e.getMessage());
        }
    }

    /** 截断字符串到指定最大长度，防止数据库字段溢出 */
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private String getString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null && !v.toString().trim().isEmpty()) return v.toString().trim();
        }
        return null;
    }

    private String fetchUrl(String urlString) {
        try {
            // 双保险：入口已校验，此处再拦一次（防其他调用方绕过）；异常吞掉返回 null 由外层兜底
            SafeUrlGuard.check(urlString);
            URL url = new URL(urlString);
            URLConnection conn = url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                return sb.toString();
            }
        } catch (Exception e) {
            log.error("下载书源失败: {}", e.getMessage());
            return null;
        }
    }
}
