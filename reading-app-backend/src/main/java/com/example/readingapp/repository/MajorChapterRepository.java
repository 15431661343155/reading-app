package com.example.readingapp.repository;

import com.example.readingapp.entity.MajorChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MajorChapterRepository extends JpaRepository<MajorChapter, Long> {
    List<MajorChapter> findByBookIdOrderBySortOrderAsc(Long bookId);
    Optional<MajorChapter> findByBookIdAndSortKey(Long bookId, String sortKey);
    void deleteByBookId(Long bookId);
    boolean existsByBookIdAndSortKey(Long bookId, String sortKey);
}
