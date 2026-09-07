package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "external_reading_record")
public class ExternalReadingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_type", length = 50)
    private String sourceType;

    @Column(name = "source_book_id", length = 200)
    private String sourceBookId;

    @Column(name = "chapter_index", columnDefinition = "INT DEFAULT 0")
    private int chapterIndex;

    @Column(name = "chapter_title", length = 500)
    private String chapterTitle;

    @Column(name = "book_title", length = 200)
    private String bookTitle;

    @Column(name = "book_author", length = 100)
    private String bookAuthor;

    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    @Column(name = "page", columnDefinition = "INT DEFAULT 0")
    private int page;

    @Column(name = "update_time")
    private Long updateTime;
}
