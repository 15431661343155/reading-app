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

    /**
     * 主分类名（男生 / 女生 / 后台自定义的主分类）。空串表示未指定主分类
     * （改造前导入的老书迁移后会落成空串，管理员在后台指认主分类即可）。
     */
    @Column(length = 50)
    private String category;

    /**
     * 子分类（多选），JSON 数组字符串，如 {@code ["玄幻","都市"]}。
     * 编解码唯一实现见 {@link com.example.readingapp.util.BookCategories}；
     * 新写入一律是 "[]" 而不是 null —— null 专留给「本列上线前的历史数据」，供启动迁移识别。
     */
    @Column(name = "sub_categories", length = 500)
    private String subCategories;

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