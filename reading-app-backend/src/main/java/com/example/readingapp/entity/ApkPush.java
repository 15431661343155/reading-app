package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "apk_push")
public class ApkPush {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "version")
    private String version;

    @Column(name = "md5")
    private String md5;

    /** 更新内容：上传 APK 时由管理员填写，随更新弹窗展示给用户；空则客户端显示「修复已知问题」 */
    @Column(name = "update_notes", length = 1000)
    private String updateNotes;

    @Column(name = "is_pushed")
    private Boolean isPushed = false;

    @Column(name = "push_time")
    private LocalDateTime pushTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}