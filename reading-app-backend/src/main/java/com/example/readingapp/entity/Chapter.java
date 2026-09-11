package com.example.readingapp.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chapter", indexes = {
        @Index(name = "idx_book_id", columnList = "book_id"),
        @Index(name = "idx_sort_order", columnList = "book_id, sort_order")
})
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /**
     * 保留样式的正文（EPUB 导入时写入，自包含 HTML 片段）。
     * 为空表示只有纯文本，阅读器按纯文本渲染。
     */
    @Column(name = "content_html", columnDefinition = "LONGTEXT")
    private String contentHtml;

    @Column(name = "sort_order", nullable = false, columnDefinition = "INT DEFAULT 0")
    private Integer sortOrder = 0;

    @Column(name = "word_count", columnDefinition = "INT DEFAULT 0")
    private Integer wordCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", insertable = false, updatable = false)
    private Book book;

    @Column(name = "sort_key", length = 50)
    private String sortKey;

    public String getSortKey() { return sortKey; }
    public void setSortKey(String sortKey) { this.sortKey = sortKey; }

    /** 章节正文 URL（书源规则提取，供 getChapterContent 使用，不入库） */
    @Transient
    private String url;

    /** 是否为卷/分卷标题（书源规则提取，不入库） */
    @Transient
    private Boolean isVolume;

    /** 书源规则提取的更新时间文本（不入库；库内更新时间用 updatedAt） */
    @Transient
    private String updateTime;
}