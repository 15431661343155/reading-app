package com.example.readingapp.repository;

import com.example.readingapp.entity.Bookmark;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    // 查询用户在某本书中的所有书签
    List<Bookmark> findByUserIdAndBookIdOrderByCreatedAtDesc(Long userId, Long bookId);

    // 分页查询用户的书签
    Page<Bookmark> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // 检查是否已存在相同位置的书签
    boolean existsByUserIdAndBookIdAndChapterIndex(Long userId, Long bookId, Integer chapterIndex);

    // 根据用户ID、书籍ID和章节索引查找书签
    Optional<Bookmark> findByUserIdAndBookIdAndChapterIndex(Long userId, Long bookId, Integer chapterIndex);

    // 删除用户在某本书中的所有书签
    @Modifying
    @Query("DELETE FROM Bookmark b WHERE b.userId = :userId AND b.bookId = :bookId")
    void deleteByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") Long bookId);
    void deleteByBookId(Long bookId);

    // 统计用户书签数量
    long countByUserId(Long userId);
}
