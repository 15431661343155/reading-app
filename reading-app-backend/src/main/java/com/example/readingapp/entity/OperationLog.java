package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "operation_log")
public class OperationLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // 操作类型：BOOK_ADD, BOOK_UPDATE, BOOK_DELETE, CHAPTER_ADD, CHAPTER_UPDATE, CHAPTER_DELETE, USER_DISABLE, USER_ENABLE, IMPORT, etc.
    @Column(name = "operation_type", length = 50)
    private String operationType;
    
    // 操作描述，如"删除书籍：xxx"
    @Column(name = "description", length = 500)
    private String description;
    
    // 关联目标类型：BOOK, CHAPTER, USER, etc.
    @Column(name = "target_type", length = 20)
    private String targetType;
    
    // 关联目标ID
    @Column(name = "target_id")
    private Long targetId;
    
    // 操作时间
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    // 操作者IP
    @Column(name = "operator_ip", length = 50)
    private String operatorIp;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
