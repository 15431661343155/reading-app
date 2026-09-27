package com.example.readingapp.service;

import com.example.readingapp.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface BookService {

    // 根据ID查询书籍
    Book findById(Long id);

    // 分页查询所有书籍
    Page<Book> findAll(Pageable pageable);

    // 根据分类查询书籍
    Page<Book> findByCategory(String category, Pageable pageable);

    // 搜索书籍（书名或作者）
    Page<Book> searchBooks(String keyword, Pageable pageable);

    // 热门书籍
    List<Book> getHotBooks();

    // 最新上架
    List<Book> getNewBooks();

    // 增加阅读量
    void incrementViewCount(Long bookId);

    // 保存书籍
    Book save(Book book);

    // 删除书籍
    void delete(Long id);

    // 批量删除
    void batchDelete(List<Long> ids);

    // 批量更新状态
    void batchUpdateStatus(List<Long> ids, Integer status);

    // 批量更新分类（category=主分类，可空表示保留原主分类；subCategories=子分类多选）
    void batchUpdateCategory(List<Long> ids, String category, String subCategories);
}