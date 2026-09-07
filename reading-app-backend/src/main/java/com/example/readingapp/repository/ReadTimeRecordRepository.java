package com.example.readingapp.repository;

import com.example.readingapp.entity.ReadTimeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReadTimeRecordRepository extends JpaRepository<ReadTimeRecord, Long> {

    // 统计用户总阅读时长（秒）
    @Query("SELECT COALESCE(SUM(r.duration), 0) FROM ReadTimeRecord r WHERE r.userId = :userId")
    Long getTotalDurationByUserId(@Param("userId") Long userId);

    // 统计某本书的阅读时长
    @Query("SELECT COALESCE(SUM(r.duration), 0) FROM ReadTimeRecord r WHERE r.userId = :userId AND r.bookId = :bookId")
    Long getDurationByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") Long bookId);
}