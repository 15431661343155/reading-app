package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "external_bookmark", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ext_bm_user_src_ch",
                columnNames = {"user_id", "source_type", "source_book_id", "chapter_index"})
})
public class ExternalBookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_type", length = 50, nullable = false)
    private String sourceType;

    @Column(name = "source_book_id", length = 200, nullable = false)
    private String sourceBookId;

    @Column(name = "chapter_index", nullable = false)
    private Integer chapterIndex;

    @Column(name = "chapter_title", length = 200)
    private String chapterTitle;

    @Column(name = "scroll_position", columnDefinition = "INT DEFAULT 0")
    private Integer scrollPosition = 0;

    @Column(name = "preview_text", length = 500)
    private String previewText;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "update_time")
    private Long updateTime;
}
