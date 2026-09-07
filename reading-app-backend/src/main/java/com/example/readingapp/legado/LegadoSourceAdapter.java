package com.example.readingapp.legado;

import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.legado.model.LegadoBookSource;
import com.example.readingapp.service.OnlineBookSourceService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 适配器：将 LegadoBookSourceService 包装为 OnlineBookSourceService。
 * 使新的 Legado 原生解析引擎无缝接入旧的 Factory/Controller 调用链。
 */
@Slf4j
public class LegadoSourceAdapter implements OnlineBookSourceService {

    private final LegadoBookSource legadoSource;
    private final LegadoBookSourceService service;
    private final String sourceType;
    private final String sourceName;

    public LegadoSourceAdapter(LegadoBookSource legadoSource, String sourceType, String sourceName) {
        this.legadoSource = legadoSource;
        this.service = new LegadoBookSourceService(legadoSource);
        this.sourceType = sourceType;
        this.sourceName = sourceName;
    }

    @Override
    public List<Book> searchBooks(String keyword, String sourceType, int page, int size) {
        try {
            List<Book> books = service.searchBooks(keyword, page);
            if (books == null) return Collections.emptyList();
            // 统一设置正确的 sourceType（service 内部误设为 baseUrl，此处覆盖修正）
            for (Book b : books) {
                b.setSourceType(this.sourceType);
            }
            if (books.size() > size) return books.subList(0, size);
            return books;
        } catch (Exception e) {
            log.warn("[{}] 搜索失败: {}", sourceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Book> exploreBooks(String sourceType, int page, int size, String category) {
        try {
            List<Book> books = service.exploreBooks(page + 1, size, category);
            for (Book b : books) {
                b.setSourceType(this.sourceType);
            }
            return books;
        } catch (Exception e) {
            log.warn("[{}] exploreBooks(category={}) 失败: {}", sourceName, category, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Book> exploreBooks(String sourceType, int page, int size) {
        return exploreBooks(sourceType, page, size, null);
    }

    @Override
    public List<String[]> getExploreCategories(String sourceType) {
        try {
            return service.getExploreCategories();
        } catch (Exception e) {
            log.warn("[{}] getExploreCategories 失败: {}", sourceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public Book getBookDetail(String sourceBookId, String sourceType) {
        try {
            Book book = service.getBookInfo(sourceBookId);
            if (book != null) {
                book.setSourceType(this.sourceType);
            }
            return book;
        } catch (Exception e) {
            log.warn("[{}] 获取书籍详情失败: {}", sourceName, e.getMessage());
            return null;
        }
    }

    @Override
    public Book importBook(String sourceBookId, String sourceType) {
        return getBookDetail(sourceBookId, sourceType);
    }

    @Override
    public String getSourceName(String sourceType) {
        return sourceName;
    }

    @Override
    public List<String[]> getChapterList(String sourceBookId, String sourceType) {
        try {
            // Legado 设计：sourceBookId 是详情页 URL，需先调详情拿 tocUrl（章节列表 API URL），
            // 再用 tocUrl 调章节列表。某些书源（如七猫）的 tocUrl 由 ruleBookInfo.tocUrl
            // 通过 JS 生成（qmTocUrl.call(this, d.data.id)），与详情 URL 不同。
            String tocUrl = sourceBookId;
            try {
                Book info = service.getBookInfo(sourceBookId);
                if (info != null && info.getTocUrl() != null && !info.getTocUrl().trim().isEmpty()) {
                    tocUrl = info.getTocUrl();
                }
            } catch (Exception ignore) {
                // 详情获取失败时退化为直接用 sourceBookId 当目录 URL
            }
            List<Chapter> chapters = service.getChapterList(tocUrl);
            if (chapters == null) return Collections.emptyList();
            List<String[]> result = new ArrayList<>(chapters.size());
            for (Chapter ch : chapters) {
                result.add(new String[]{
                    ch.getTitle(),
                    ch.getUrl(),
                    String.valueOf(ch.getSortOrder() != null ? ch.getSortOrder() : 0)
                });
            }
            return result;
        } catch (Exception e) {
            log.warn("[{}] 获取章节列表失败: {}", sourceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String getChapterContent(String sourceBookId, String chapterUrl, String sourceType) {
        try {
            return service.getChapterContent(chapterUrl);
        } catch (Exception e) {
            log.warn("[{}] 获取章节内容失败: {}", sourceName, e.getMessage());
            return "";
        }
    }
}
