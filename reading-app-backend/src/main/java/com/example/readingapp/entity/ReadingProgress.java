package com.example.readingapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Data
@Entity
@Table(name = "reading_progress", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_book", columnNames = {"user_id", "book_id"})
}, indexes = {
        @Index(name = "idx_updated_at", columnList = "user_id, updated_at")
})
public class ReadingProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "chapter_id")
    private Long chapterId;

    @Column(name = "chapter_index", columnDefinition = "INT DEFAULT 0")
    private Integer chapterIndex = 0;

    @Column(name = "scroll_position", columnDefinition = "INT DEFAULT 0")
    private Integer scrollPosition = 0;

    @Column(name = "font_size", columnDefinition = "FLOAT DEFAULT 18")
    private Float fontSize = 18f;

    @Column(name = "night_mode", columnDefinition = "TINYINT DEFAULT 0")
    private Integer nightMode = 0; // 0关闭 1开启

    @Column(name = "bg_color", columnDefinition = "INT DEFAULT 0")
    private Integer bgColor = 0; // 0默认 1护眼1 2护眼2 3夜间

    @Column(name = "text_anchor", length = 200)
    private String textAnchor;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "book_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Book book;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "chapter_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Chapter chapter;

    public String getTextAnchor() { return textAnchor; }
    public void setTextAnchor(String textAnchor) { this.textAnchor = textAnchor; }

    /**
     * 供客户端比较「最后阅读时间」用的绝对时间戳（epoch 毫秒）。
     *
     * <p>{@link #updatedAt} 是「服务器本地时间、不带时区」，客户端若按自己设备时区解析，
     * 在设备时区 ≠ 服务器时区时比较会失真（模拟器常见 GMT，差 8 小时）。
     * 这里直接下发与设备时区无关的 epoch 毫秒；老客户端仍可继续用 updatedAt 字符串。
     */
    @Transient
    public Long getUpdatedAtEpoch() {
        return updatedAt == null ? null : updatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}