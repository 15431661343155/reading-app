package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "book_source")
public class BookSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "base_url", columnDefinition = "TEXT")
    private String baseUrl;

    @Column(name = "is_enabled", columnDefinition = "TINYINT DEFAULT 1")
    private Boolean enabled = true;

    @Column(name = "is_built_in", columnDefinition = "TINYINT DEFAULT 0")
    private Boolean builtIn = false;

    @Column(columnDefinition = "INT DEFAULT 0")
    private Integer bookCount = 0;

    @Column(columnDefinition = "TEXT")
    private String remark;

    @Column(name = "config_json", columnDefinition = "LONGTEXT")
    private String configJson;

    @Column(name = "source_group", length = 200)
    private String sourceGroup;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
