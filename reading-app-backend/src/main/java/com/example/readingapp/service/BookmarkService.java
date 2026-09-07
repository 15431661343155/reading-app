package com.example.readingapp.service;

import com.example.readingapp.entity.Bookmark;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface BookmarkService {

    // 添加书签
    Bookmark addBookmark(Bookmark bookmark);

    // 删除书签
    void deleteBookmark(Long id);

    // 删除用户在指定书籍的所有书签
    void deleteBookmarksByBook(Long userId, Long bookId);

    // 获取用户在某本书的所有书签
    List<Bookmark> getBookmarksByBook(Long userId, Long bookId);

    // 分页获取用户的所有书签
    Page<Bookmark> getUserBookmarks(Long userId, Pageable pageable);

    // 检查书签是否已存在
    boolean isBookmarkExists(Long userId, Long bookId, Integer chapterIndex);
}