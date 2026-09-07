package com.example.readingapp.repository;

import com.example.readingapp.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookRepository extends JpaRepository<Book, Long> {

    // 根据分类查询书籍
    Page<Book> findByCategory(String category, Pageable pageable);

    // 根据状态查询（0连载 1完结）
    Page<Book> findByStatus(Integer status, Pageable pageable);

    // 根据书名模糊搜索
    Page<Book> findByTitleContaining(String keyword, Pageable pageable);

    // 根据作者模糊搜索
    Page<Book> findByAuthorContaining(String author, Pageable pageable);

    // 综合搜索（书名或作者），按关联性排序：标题完全匹配 > 标题前缀 > 标题包含 > 作者匹配
    @Query(value = "SELECT * FROM book b WHERE b.title LIKE CONCAT('%', :keyword, '%') OR b.author LIKE CONCAT('%', :keyword, '%') " +
           "ORDER BY CASE " +
           "  WHEN b.title = :keyword THEN 0 " +
           "  WHEN b.title LIKE CONCAT(:keyword, '%') THEN 1 " +
           "  WHEN b.title LIKE CONCAT('%', :keyword, '%') THEN 2 " +
           "  WHEN b.author LIKE CONCAT('%', :keyword, '%') THEN 3 " +
           "  ELSE 4 END, b.id DESC",
           countQuery = "SELECT count(*) FROM book b WHERE b.title LIKE CONCAT('%', :keyword, '%') OR b.author LIKE CONCAT('%', :keyword, '%')",
           nativeQuery = true)
    Page<Book> searchBooks(@Param("keyword") String keyword, Pageable pageable);

    // 热门书籍（按阅读量排序）
    List<Book> findTop10ByOrderByViewCountDesc();

    // 最新上架
    List<Book> findTop10ByOrderByCreatedAtDesc();

    // 公版书籍
    Page<Book> findByPublicDomainTrue(Pageable pageable);

    // 有章节内容的公版书籍
    @Query("SELECT b FROM Book b WHERE b.publicDomain = true AND b.chapterCount > 0")
    Page<Book> findPublicDomainWithChapters(Pageable pageable);

    // 按分类查有章节内容的公版书籍
    @Query("SELECT b FROM Book b WHERE b.publicDomain = true AND b.chapterCount > 0 AND b.category = :category")
    Page<Book> findPublicDomainWithChaptersAndCategory(@Param("category") String category, Pageable pageable);

    // 获取有章节的公版书分类列表
    @Query("SELECT DISTINCT b.category FROM Book b WHERE b.publicDomain = true AND b.chapterCount > 0 AND b.category IS NOT NULL AND b.category <> ''")
    List<String> findDistinctPublicDomainCategoriesWithChapters();

    // 非公版书籍
    Page<Book> findByPublicDomainFalse(Pageable pageable);

    // 按书源类型查询
    Page<Book> findBySourceType(String sourceType, Pageable pageable);

    // 按公版状态和分类查询
    Page<Book> findByPublicDomainTrueAndCategory(String category, Pageable pageable);

    Page<Book> findByPublicDomainFalseAndCategory(String category, Pageable pageable);

    // 按书名查找
    Book findByTitle(String title);

    // 按书源ID和书源类型查找
    Book findBySourceIdAndSourceType(Long sourceId, String sourceType);

    // 按来源URL查找
    Book findBySourceUrl(String sourceUrl);

    // 获取公版书所有分类
    @Query("SELECT DISTINCT b.category FROM Book b WHERE b.publicDomain = true AND b.category IS NOT NULL AND b.category <> ''")
    List<String> findDistinctPublicDomainCategories();

    // 获取非公版书所有分类
    @Query("SELECT DISTINCT b.category FROM Book b WHERE (b.publicDomain = false OR b.publicDomain IS NULL) AND b.category IS NOT NULL AND b.category <> ''")
    List<String> findDistinctFictionCategories();
}