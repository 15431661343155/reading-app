package com.example.readingapp.repository;

import com.example.readingapp.entity.ExternalBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExternalBookmarkRepository extends JpaRepository<ExternalBookmark, Long> {

    Optional<ExternalBookmark> findByUserIdAndSourceTypeAndSourceBookIdAndChapterIndex(
            Long userId, String sourceType, String sourceBookId, Integer chapterIndex);

    List<ExternalBookmark> findAllByUserId(Long userId);

    List<ExternalBookmark> findByUserIdAndSourceTypeAndSourceBookId(
            Long userId, String sourceType, String sourceBookId);

    /** 派生删除方法必须带事务，否则报 "No EntityManager with actual transaction available"。 */
    @Transactional
    void deleteByUserIdAndSourceTypeAndSourceBookIdAndChapterIndex(
            Long userId, String sourceType, String sourceBookId, Integer chapterIndex);

    /** 删除某用户的全部外站书签（删除用户时级联清理）。 */
    @Transactional
    void deleteByUserId(Long userId);
}
