package com.example.readingapp.service.impl;

import com.example.readingapp.entity.Book;
import com.example.readingapp.repository.*;
import com.example.readingapp.service.BookService;
import com.example.readingapp.util.BookCategories;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final BookshelfRepository bookshelfRepository;
    private final ReadingProgressRepository readingProgressRepository;
    private final BookmarkRepository bookmarkRepository;
    private final MajorChapterRepository majorChapterRepository;

    @Override
    public Book findById(Long id) {
        return bookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("书籍不存在"));
    }

    @Override
    public Page<Book> findAll(Pageable pageable) {
        return bookRepository.findAll(pageable);
    }

    @Override
    public Page<Book> findByCategory(String category, Pageable pageable) {
        return bookRepository.findByCategory(category, pageable);
    }

    @Override
    public Page<Book> searchBooks(String keyword, Pageable pageable) {
        return bookRepository.searchBooks(keyword, pageable);
    }

    @Override
    public List<Book> getHotBooks() {
        return bookRepository.findTop10ByOrderByViewCountDesc();
    }

    @Override
    public List<Book> getNewBooks() {
        return bookRepository.findTop10ByOrderByCreatedAtDesc();
    }

    @Override
    @Transactional
    public void incrementViewCount(Long bookId) {
        // 原子自增：并发下不丢计数，且 view_count 为 NULL 的老数据不会 NPE
        bookRepository.incrementViewCount(bookId);
    }

    @Override
    public Book save(Book book) {
        return bookRepository.save(book);
    }

    @Override
    public void delete(Long id) {
        bookRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        for (Long id : ids) {
            chapterRepository.deleteByBookId(id);
            bookshelfRepository.deleteByBookId(id);
            readingProgressRepository.deleteByBookId(id);
            bookmarkRepository.deleteByBookId(id);
            majorChapterRepository.deleteByBookId(id);
            bookRepository.deleteById(id);
        }
    }

    @Override
    @Transactional
    public void batchUpdateStatus(List<Long> ids, Integer status) {
        List<Book> books = bookRepository.findAllById(ids);
        for (Book book : books) {
            book.setStatus(status);
        }
        bookRepository.saveAll(books);
    }

    @Override
    @Transactional
    public void batchUpdateCategory(List<Long> ids, String category, String subCategories) {
        List<Book> books = bookRepository.findAllById(ids);
        boolean updateMain = category != null && !category.trim().isEmpty();
        String main = updateMain ? category.trim() : null;
        // 子分类统一走规范化：前端可能传 JSON 数组串 / 逗号分隔 / 空（清空）
        String subs = BookCategories.toStorage(subCategories);
        for (Book book : books) {
            if (updateMain) {
                book.setCategory(main);
            }
            book.setSubCategories(subs);
        }
        bookRepository.saveAll(books);
    }
}
