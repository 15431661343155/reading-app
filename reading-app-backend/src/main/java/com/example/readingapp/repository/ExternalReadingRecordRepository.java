package com.example.readingapp.repository;

import com.example.readingapp.entity.ExternalReadingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface ExternalReadingRecordRepository extends JpaRepository<ExternalReadingRecord, Long> {

    Optional<ExternalReadingRecord> findByUserIdAndSourceTypeAndSourceBookId(Long userId, String sourceType, String sourceBookId);

    java.util.List<ExternalReadingRecord> findAllByUserId(Long userId);

    /** 派生删除方法必须带事务，否则报 "No EntityManager with actual transaction available"。 */
    @Transactional
    void deleteByUserIdAndSourceTypeAndSourceBookId(Long userId, String sourceType, String sourceBookId);

    /** 删除某用户的全部外站阅读记录（删除用户时级联清理）。 */
    @Transactional
    void deleteByUserId(Long userId);
}
