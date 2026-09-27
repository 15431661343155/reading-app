package com.example.readingapp.repository;

import com.example.readingapp.entity.Chapter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterRepository extends JpaRepository<Chapter, Long> {

    // 根据书籍ID查询所有章节，按排序字段升序
    List<Chapter> findByBookIdOrderBySortOrderAsc(Long bookId);

    // 分页版：后台列表场景。带 Lazy 正文时一次性查全部会拖几十 MB 进内存，
    // 但实测每页 500 行总耗时 <90ms，因此后端与前端都按 500 一页取。
    Page<Chapter> findByBookIdOrderBySortOrderAsc(Long bookId, Pageable pageable);

    // 按 id 集合 + 书籍 批量取（批量删除前必须先按 bookId 收口，防止跨书误删）
    List<Chapter> findByBookIdAndIdIn(Long bookId, Collection<Long> ids);

    // 根据书籍ID和排序字段查询章节
    Optional<Chapter> findByBookIdAndSortOrder(Long bookId, Integer sortOrder);

    // 按排序键查询（sortKey 形如 "1-0001"，卷内唯一）
    Optional<Chapter> findByBookIdAndSortKey(Long bookId, String sortKey);

    // 按标题查（补章场景的兜底匹配键）
    List<Chapter> findByBookIdAndTitle(Long bookId, String title);

    // 查询书籍的总章节数
    long countByBookId(Long bookId);

    // 查询某本书的最大排序序号
    @Query("SELECT MAX(c.sortOrder) FROM Chapter c WHERE c.bookId = :bookId")
    Integer findMaxSortOrderByBookId(@Param("bookId") Long bookId);

    // 查询某本书的最后一章（sortOrder 最大的章节）
    @Query("SELECT c FROM Chapter c WHERE c.bookId = :bookId ORDER BY c.sortOrder DESC")
    List<Chapter> findLastChapterByBookId(@Param("bookId") Long bookId);

    // 查询某一章节的前一章
    @Query("SELECT c FROM Chapter c WHERE c.bookId = :bookId AND c.sortOrder < :sortOrder ORDER BY c.sortOrder DESC LIMIT 1")
    Optional<Chapter> findPreviousChapter(@Param("bookId") Long bookId, @Param("sortOrder") Integer sortOrder);

    // 查询某一章节的后一章
    @Query("SELECT c FROM Chapter c WHERE c.bookId = :bookId AND c.sortOrder > :sortOrder ORDER BY c.sortOrder ASC LIMIT 1")
    Optional<Chapter> findNextChapter(@Param("bookId") Long bookId, @Param("sortOrder") Integer sortOrder);

    // 更新书籍的章节总数
    @Modifying
    @Query("UPDATE Book b SET b.chapterCount = :count WHERE b.id = :bookId")
    void updateBookChapterCount(@Param("bookId") Long bookId, @Param("count") Integer count);

    void deleteByBookId(Long bookId);
}