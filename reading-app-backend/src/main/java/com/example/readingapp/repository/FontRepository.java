package com.example.readingapp.repository;

import com.example.readingapp.entity.Font;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FontRepository extends JpaRepository<Font, Long> {

    List<Font> findAllByOrderBySortOrderAsc();
}
