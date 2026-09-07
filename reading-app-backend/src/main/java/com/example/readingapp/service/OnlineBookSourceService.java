package com.example.readingapp.service;

import com.example.readingapp.entity.Book;

import java.util.List;

public interface OnlineBookSourceService {

    List<Book> searchBooks(String keyword, String sourceType, int page, int size);

    /**
     * 发现页/榜单：从书源首页或排行榜获取书籍列表。
     * 默认返回空列表（公版书源等无榜单功能），JsonBookSourceServiceImpl 覆写实现。
     * 若 category 不为 null/空，则仅加载该分类下的书籍。
     */
    default List<Book> exploreBooks(String sourceType, int page, int size, String category) {
        return java.util.Collections.emptyList();
    }

    /** 旧版兼容：无 category 参数时走默认全部 */
    default List<Book> exploreBooks(String sourceType, int page, int size) {
        return exploreBooks(sourceType, page, size, null);
    }

    /**
     * 获取书源的发现页分类列表。
     * 返回 String[]{分类名称, 分类URL} 的列表；
     * 若书源不支持分类发现页，返回空列表。
     */
    default List<String[]> getExploreCategories(String sourceType) {
        return java.util.Collections.emptyList();
    }

    Book getBookDetail(String sourceBookId, String sourceType);

    Book importBook(String sourceBookId, String sourceType);

    String getSourceName(String sourceType);

    List<String[]> getChapterList(String sourceBookId, String sourceType);

    String getChapterContent(String sourceBookId, String chapterUrl, String sourceType);
}
