package com.example.readingapp.repository;

import com.example.readingapp.entity.ReadTimeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ReadTimeRecordRepository extends JpaRepository<ReadTimeRecord, Long> {

    // 统计用户总阅读时长（秒）
    @Query("SELECT COALESCE(SUM(r.duration), 0) FROM ReadTimeRecord r WHERE r.userId = :userId")
    Long getTotalDurationByUserId(@Param("userId") Long userId);

    // 统计某本书的阅读时长
    @Query("SELECT COALESCE(SUM(r.duration), 0) FROM ReadTimeRecord r WHERE r.userId = :userId AND r.bookId = :bookId")
    Long getDurationByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") Long bookId);

    /** 删除某用户的全部阅读时长记录（删除用户时级联清理）。派生删除方法必须带事务。 */
    @Transactional
    void deleteByUserId(Long userId);
}