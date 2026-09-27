package com.example.readingapp.repository;

import com.example.readingapp.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

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

    /**
     * 阅读量 +1（原子自增）。
     * 不要改回「查出来 +1 再 save」：view_count 允许为 null（老数据），那种写法会 NPE，
     * 而且并发下会丢计数。
     */
    @Modifying
    @Query("UPDATE Book b SET b.viewCount = COALESCE(b.viewCount, 0) + 1 WHERE b.id = :id")
    int incrementViewCount(@Param("id") Long id);

    /** 子分类列为 NULL 的书籍 —— 即「子分类列上线前」的老数据，启动迁移用。 */
    List<Book> findBySubCategoriesIsNull();

    /**
     * 按书名批量查（重复导入预检用）。
     * 同名书可能有多本，故返回 List 而不是单本；不存在的书名不会出现在结果里。
     */
    List<Book> findByTitleIn(Collection<String> titles);

    // ---------- 分类维护（管理后台「分类管理」页） ----------

    /** 主分类下的书籍（不分页版本，分类删除/改名要遍历全部） */
    List<Book> findByCategory(String category);

    /**
     * 子分类命中的书籍。参数传 {@code "\"玄幻\""}（带引号）：
     * 存储是 JSON 数组串，带引号才能精确匹配，不会把「玄幻言情」算成「玄幻」。
     */
    List<Book> findBySubCategoriesContaining(String nameWithQuotes);

    /** 分类使用量统计（与上面两个方法同一匹配口径） */
    long countByCategory(String category);

    long countBySubCategoriesContaining(String nameWithQuotes);

    /** 还没指认主分类的书籍数（分类管理页用来提示「还有 N 本待归类」） */
    @Query("SELECT COUNT(b) FROM Book b WHERE b.category IS NULL OR b.category = ''")
    long countUncategorized();

    /** 书库里实际出现过的子分类原始值（JSON 数组串），给后台分类选择框做候选用。 */
    @Query("SELECT b.subCategories FROM Book b WHERE b.subCategories IS NOT NULL AND b.subCategories <> '[]'")
    List<String> findAllSubCategoriesRaw();

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

    // 书名是否已存在（导入查重用，同名书可能有多本故不能用 findByTitle）
    boolean existsByTitle(String title);

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