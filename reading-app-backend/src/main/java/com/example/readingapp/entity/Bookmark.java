package com.example.readingapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@JsonIgnoreProperties(ignoreUnknown = true)
@Table(name = "bookmark", indexes = {
        @Index(name = "idx_user_book", columnList = "user_id, book_id"),
        @Index(name = "idx_created_at", columnList = "user_id, created_at")
})
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "chapter_id")
    private Long chapterId;

    @Column(name = "chapter_index", nullable = false)
    private Integer chapterIndex;

    @Column(name = "chapter_title", nullable = false, length = 200)
    private String chapterTitle;

    @Column(name = "scroll_position", columnDefinition = "INT DEFAULT 0")
    private Integer scrollPosition = 0;

    @Column(name = "preview_text", length = 200)
    private String previewText;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "note", length = 500)
    private String note;
}