package com.example.readingapp.repository;

import com.example.readingapp.entity.Bookshelf;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface BookshelfRepository extends JpaRepository<Bookshelf, Long> {

    // 查询用户的书架列表（按最后阅读时间倒序）
    Page<Bookshelf> findByUserIdOrderByLastReadAtDesc(Long userId, Pageable pageable);

    // 检查书籍是否已在用户书架中
    boolean existsByUserIdAndBookId(Long userId, Long bookId);

    // 根据用户ID和书籍ID查找书架记录
    Optional<Bookshelf> findByUserIdAndBookId(Long userId, Long bookId);

    // 更新最后阅读时间
    @Transactional
    @Modifying
    @Query("UPDATE Bookshelf b SET b.lastReadAt = CURRENT_TIMESTAMP WHERE b.userId = :userId AND b.bookId = :bookId")
    void updateLastReadTime(@Param("userId") Long userId, @Param("bookId") Long bookId);

    // 从书架移除书籍
    @Transactional
    @Modifying
    @Query("DELETE FROM Bookshelf b WHERE b.userId = :userId AND b.bookId = :bookId")
    void deleteByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") Long bookId);
    void deleteByBookId(Long bookId);
    // 统计用户书架书籍数量
    long countByUserId(Long userId);

}