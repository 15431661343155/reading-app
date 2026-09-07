package com.example.readingapp.repository;

import com.example.readingapp.entity.ExternalReadingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ExternalReadingRecordRepository extends JpaRepository<ExternalReadingRecord, Long> {

    Optional<ExternalReadingRecord> findByUserIdAndSourceTypeAndSourceBookId(Long userId, String sourceType, String sourceBookId);
}
