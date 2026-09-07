package com.example.readingapp.repository;

import com.example.readingapp.entity.BookSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookSourceRepository extends JpaRepository<BookSource, Long> {

    List<BookSource> findByEnabledTrue();

    List<BookSource> findByBuiltInTrue();

    BookSource findBySourceType(String sourceType);

    void deleteBySourceType(String sourceType);

    List<BookSource> findAllByConfigJsonIsNotNullAndEnabledTrue();
}
