package com.example.readingapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "user")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = true, unique = true, length = 10)
    private String userId;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /**
     * 密码（BCrypt 哈希）。仅允许写入，序列化到 JSON 时一律剔除，
     * 防止注册/绑定/用户列表等接口响应体回显密码哈希。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(nullable = false, length = 255)
    private String password;

    @Column(length = 50)
    private String nickname;

    @Column(length = 500)
    private String avatar;

    @Column(length = 100, unique = true)
    private String email;

    @Column(length = 20, unique = true)
    private String phone;

    @Column(columnDefinition = "TINYINT DEFAULT 0")
    private Integer gender = 0; // 0未知 1男 2女

    @Column(columnDefinition = "TINYINT DEFAULT 1")
    private Integer status = 1; // 0禁用 1正常

    /**
     * 角色：USER=普通用户（App 端），ADMIN=管理员（仅管理后台可登录）。
     * 历史数据该列为 NULL，读取时统一按 USER 处理。
     */
    @Column(length = 20)
    private String role = "USER";

    @Column(name = "last_login_time")
    private LocalDateTime lastLoginTime;

    @Column(name = "last_login_ip", length = 50)
    private String lastLoginIp;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private List<Bookshelf> bookshelves;

    @Transient
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private List<ReadingProgress> readingProgresses;

    @Transient
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private List<Bookmark> bookmarks;
}
