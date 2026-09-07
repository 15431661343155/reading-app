package com.example.readingapp.service;

import com.example.readingapp.entity.Bookshelf;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BookshelfService {

    // 加入书架
    Bookshelf addToBookshelf(Long userId, Long bookId);

    // 从书架移除
    void removeFromBookshelf(Long userId, Long bookId);

    // 获取用户书架列表
    Page<Bookshelf> getUserBookshelf(Long userId, Pageable pageable);

    // 检查书籍是否在书架中
    boolean isInBookshelf(Long userId, Long bookId);

    // 更新最后阅读时间
    void updateLastReadTime(Long userId, Long bookId);

    // 获取书架书籍数量
    long getBookshelfCount(Long userId);
}
