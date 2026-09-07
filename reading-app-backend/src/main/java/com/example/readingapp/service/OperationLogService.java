package com.example.readingapp.service;

import com.example.readingapp.entity.OperationLog;
import com.example.readingapp.repository.OperationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OperationLogService {
    
    private final OperationLogRepository operationLogRepository;
    
    // 操作类型常量
    public static final String TYPE_BOOK_ADD = "BOOK_ADD";
    public static final String TYPE_BOOK_UPDATE = "BOOK_UPDATE";
    public static final String TYPE_BOOK_DELETE = "BOOK_DELETE";
    public static final String TYPE_CHAPTER_ADD = "CHAPTER_ADD";
    public static final String TYPE_CHAPTER_UPDATE = "CHAPTER_UPDATE";
    public static final String TYPE_CHAPTER_DELETE = "CHAPTER_DELETE";
    public static final String TYPE_CHAPTER_RENAME = "CHAPTER_RENAME";
    public static final String TYPE_USER_DISABLE = "USER_DISABLE";
    public static final String TYPE_USER_ENABLE = "USER_ENABLE";
    public static final String TYPE_USER_DELETE = "USER_DELETE";
    public static final String TYPE_IMPORT = "IMPORT";
    
    // 目标类型常量
    public static final String TARGET_BOOK = "BOOK";
    public static final String TARGET_CHAPTER = "CHAPTER";
    public static final String TARGET_USER = "USER";
    
    @Async
    public void log(String operationType, String description, String targetType, Long targetId, String operatorIp) {
        try {
            OperationLog log = new OperationLog();
            log.setOperationType(operationType);
            log.setDescription(description);
            log.setTargetType(targetType);
            log.setTargetId(targetId);
            log.setOperatorIp(operatorIp);
            log.setCreatedAt(LocalDateTime.now());
            operationLogRepository.save(log);
        } catch (Exception e) {
            // 记录日志失败不影响主业务
            e.printStackTrace();
        }
    }
    
    // 便捷方法：记录书籍操作
    public void logBook(String operationType, String description, Long bookId, String operatorIp) {
        log(operationType, description, TARGET_BOOK, bookId, operatorIp);
    }
    
    // 便捷方法：记录章节操作
    public void logChapter(String operationType, String description, Long chapterId, String operatorIp) {
        log(operationType, description, TARGET_CHAPTER, chapterId, operatorIp);
    }
    
    // 便捷方法：记录用户操作
    public void logUser(String operationType, String description, Long userId, String operatorIp) {
        log(operationType, description, TARGET_USER, userId, operatorIp);
    }
    
    // 获取最近的操作日志
    public List<OperationLog> getRecentLogs(int limit) {
        return operationLogRepository.findByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }
}
