package com.example.readingapp.service.impl;

import com.example.readingapp.entity.Bookmark;
import com.example.readingapp.repository.BookmarkRepository;
import com.example.readingapp.service.BookmarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BookmarkServiceImpl implements BookmarkService {

    private final BookmarkRepository bookmarkRepository;

    @Override
    @Transactional
    public Bookmark addBookmark(Bookmark bookmark) {
        // 检查是否已存在相同位置的书签
        if (bookmarkRepository.existsByUserIdAndBookIdAndChapterIndex(
                bookmark.getUserId(),
                bookmark.getBookId(),
                bookmark.getChapterIndex())) {
            throw new RuntimeException("该位置已有书签");
        }

        return bookmarkRepository.save(bookmark);
    }

    @Override
    @Transactional
    public void deleteBookmark(Long id) {
        bookmarkRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteBookmarksByBook(Long userId, Long bookId) {
        bookmarkRepository.deleteByUserIdAndBookId(userId, bookId);
    }

    @Override
    public List<Bookmark> getBookmarksByBook(Long userId, Long bookId) {
        return bookmarkRepository.findByUserIdAndBookIdOrderByCreatedAtDesc(userId, bookId);
    }

    @Override
    public Page<Bookmark> getUserBookmarks(Long userId, Pageable pageable) {
        return bookmarkRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    public boolean isBookmarkExists(Long userId, Long bookId, Integer chapterIndex) {
        return bookmarkRepository.existsByUserIdAndBookIdAndChapterIndex(userId, bookId, chapterIndex);
    }
}