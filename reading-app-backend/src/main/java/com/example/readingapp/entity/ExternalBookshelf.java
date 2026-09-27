package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "external_bookshelf", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ext_shelf_user_src",
                columnNames = {"user_id", "source_type", "source_book_id"})
})
public class ExternalBookshelf {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_type", length = 50, nullable = false)
    private String sourceType;

    @Column(name = "source_book_id", length = 200, nullable = false)
    private String sourceBookId;

    @Column(name = "book_title", length = 200)
    private String bookTitle;

    @Column(name = "book_author", length = 100)
    private String bookAuthor;

    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    @Column(name = "chapter_count", columnDefinition = "INT DEFAULT 0")
    private Integer chapterCount = 0;

    /** 最近一次更新时间（毫秒），用于同步时的增量/排序判断 */
    @Column(name = "update_time")
    private Long updateTime;
}
