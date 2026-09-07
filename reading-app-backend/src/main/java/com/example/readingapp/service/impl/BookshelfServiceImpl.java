package com.example.readingapp.service.impl;

import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Bookshelf;
import com.example.readingapp.exception.BookAlreadyInShelfException;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.repository.BookshelfRepository;
import com.example.readingapp.service.BookshelfService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookshelfServiceImpl implements BookshelfService {

    private final BookshelfRepository bookshelfRepository;
    private final BookRepository bookRepository;

    @Override
    @Transactional
    public Bookshelf addToBookshelf(Long userId, Long bookId) {
        // 检查书籍是否存在
        bookRepository.findById(bookId)
                .orElseThrow(() -> new RuntimeException("书籍不存在"));

        // 检查是否已在书架中
        if (bookshelfRepository.existsByUserIdAndBookId(userId, bookId)) {
            throw new BookAlreadyInShelfException("书籍已在书架中");
        }

        // 添加到书架
        Bookshelf bookshelf = new Bookshelf();
        bookshelf.setUserId(userId);
        bookshelf.setBookId(bookId);

        return bookshelfRepository.save(bookshelf);
    }

    @Override
    @Transactional
    public void removeFromBookshelf(Long userId, Long bookId) {
        bookshelfRepository.deleteByUserIdAndBookId(userId, bookId);
    }

    @Override
    public Page<Bookshelf> getUserBookshelf(Long userId, Pageable pageable) {
        Page<Bookshelf> page = bookshelfRepository.findByUserIdOrderByLastReadAtDesc(userId, pageable);
        page.forEach(bs -> {
            if (bs.getBook() != null) {
                bs.getBook().setIsInShelf(true);
            }
        });
        return page;
    }

    @Override
    public boolean isInBookshelf(Long userId, Long bookId) {
        return bookshelfRepository.existsByUserIdAndBookId(userId, bookId);
    }

    @Override
    @Transactional
    public void updateLastReadTime(Long userId, Long bookId) {
        bookshelfRepository.updateLastReadTime(userId, bookId);
    }

    @Override
    public long getBookshelfCount(Long userId) {
        return bookshelfRepository.countByUserId(userId);
    }
}
