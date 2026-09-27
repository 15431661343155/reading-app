package com.example.readingapp.repository;

import com.example.readingapp.entity.ReadingProgress;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReadingProgressRepository extends JpaRepository<ReadingProgress, Long> {

    // 根据用户ID和书籍ID查询阅读进度
    Optional<ReadingProgress> findByUserIdAndBookId(Long userId, Long bookId);

    List<ReadingProgress> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);
    // 更新阅读进度
    @Modifying
    @Query("UPDATE ReadingProgress r SET " +
            "r.chapterId = :#{#progress.chapterId}, " +
            "r.chapterIndex = :#{#progress.chapterIndex}, " +
            "r.scrollPosition = :#{#progress.scrollPosition}, " +
            "r.fontSize = :#{#progress.fontSize}, " +
            "r.nightMode = :#{#progress.nightMode}, " +
            "r.bgColor = :#{#progress.bgColor}, " +
            "r.textAnchor = :#{#progress.textAnchor}, " +
            "r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.userId = :#{#progress.userId} AND r.bookId = :#{#progress.bookId}")
    void updateProgress(@Param("progress") ReadingProgress progress);

    // 查询用户最近阅读的书籍
    @Query("SELECT r FROM ReadingProgress r WHERE r.userId = :userId ORDER BY r.updatedAt DESC")
    java.util.List<ReadingProgress> findRecentReading(@Param("userId") Long userId, Pageable pageable);

    void deleteByBookId(Long bookId);

    // ========== 删除阅读进度 ==========
    @Transactional
    @Modifying
    @Query("DELETE FROM ReadingProgress r WHERE r.userId = :userId AND r.bookId = :bookId")
    void deleteByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") Long bookId);

    /** 删除某用户的全部阅读进度（删除用户时级联清理）。 */
    @Transactional
    @Modifying
    @Query("DELETE FROM ReadingProgress r WHERE r.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}