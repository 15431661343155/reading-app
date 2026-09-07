package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "major_chapter")
public class MajorChapter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "sort_key", length = 50)
    private String sortKey;

    @Column(name = "sort_order")
    private Integer sortOrder;
}
