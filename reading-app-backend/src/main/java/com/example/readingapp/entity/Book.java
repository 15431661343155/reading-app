package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "book")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 100)
    private String author;

    @Column(length = 500)
    private String cover;

    @Column(columnDefinition = "TEXT")
    private String intro;

    @Column(length = 50)
    private String category;

    @Column(length = 500)
    private String tags; // JSON数组字符串

    @Column(name = "word_count", columnDefinition = "INT DEFAULT 0")
    private Integer wordCount = 0;

    @Column(name = "chapter_count", columnDefinition = "INT DEFAULT 0")
    private Integer chapterCount = 0;

    @Column(columnDefinition = "TINYINT DEFAULT 0")
    private Integer status = 0; // 0连载 1完结

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "source_type", length = 20)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "is_public_domain", columnDefinition = "TINYINT DEFAULT 0")
    private Boolean publicDomain = false;

    @Column(name = "view_count", columnDefinition = "BIGINT DEFAULT 0")
    private Long viewCount = 0L;

    @Column(name = "like_count", columnDefinition = "BIGINT DEFAULT 0")
    private Long likeCount = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    private Boolean isInShelf;

    /** 最近章节名（书源规则提取，不入库） */
    @Transient
    private String lastChapter;

    /** 书源规则提取的更新时间文本（不入库；库内更新时间用 updatedAt） */
    @Transient
    private String updateTime;

    /** 目录页 URL（ruleBookInfo.tocUrl 提取，供 getChapterList 使用，不入库） */
    @Transient
    private String tocUrl;
}