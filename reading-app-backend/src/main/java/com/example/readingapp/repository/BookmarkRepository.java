package com.example.readingapp.repository;

import com.example.readingapp.entity.Bookmark;
import org.springframework.data.domain.Page;
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
public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    // 查询用户在某本书中的所有书签
    List<Bookmark> findByUserIdAndBookIdOrderByCreatedAtDesc(Long userId, Long bookId);

    // 分页查询用户的书签
    Page<Bookmark> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // 检查是否已存在相同位置的书签
    boolean existsByUserIdAndBookIdAndChapterIndex(Long userId, Long bookId, Integer chapterIndex);

    // 根据用户ID、书籍ID和章节索引查找书签
    Optional<Bookmark> findByUserIdAndBookIdAndChapterIndex(Long userId, Long bookId, Integer chapterIndex);

    // 删除用户在某本书中的所有书签。
    // 事务注解不可省：本方法由 UserDataController.deleteProgress 在同一次请求里紧跟
    // progressRepo.deleteByUserIdAndBookId 之后调用，缺 @Transactional 会抛
    // TransactionRequiredException，被控制器的 catch 吞成「删除失败」——
    // 而进度其实已经删掉了，前端看到的是假失败。
    @Modifying
    @Transactional
    @Query("DELETE FROM Bookmark b WHERE b.userId = :userId AND b.bookId = :bookId")
    void deleteByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") Long bookId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Bookmark b WHERE b.bookId = :bookId")
    void deleteByBookId(@Param("bookId") Long bookId);

    /** 删除某用户的全部书签（删除用户时级联清理）。派生删除方法必须带事务。 */
    @Transactional
    void deleteByUserId(Long userId);

    // 统计用户书签数量
    long countByUserId(Long userId);
}
