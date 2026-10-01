package com.example.readingapp.controller;

import com.example.readingapp.exception.BusinessException;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.ExternalReadingRecord;
import com.example.readingapp.repository.ExternalReadingRecordRepository;
import com.example.readingapp.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 外站书阅读记录（用户隔离）。
 *
 * 安全：userId 一律从 JWT（SecurityContext）派生，忽略请求里传来的 userId。
 */
@RestController
@RequestMapping("/api/external-reading")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ExternalReadingController {

    private final ExternalReadingRecordRepository externalReadingRecordRepository;

    // 保存或更新外站书籍阅读记录
    @PostMapping("/save")
    public ApiResponse<ExternalReadingRecord> saveRecord(@RequestBody SaveRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            ExternalReadingRecord record = externalReadingRecordRepository
                    .findByUserIdAndSourceTypeAndSourceBookId(userId, req.sourceType, req.sourceBookId)
                    .orElseGet(ExternalReadingRecord::new);

            record.setUserId(userId);
            record.setSourceType(req.sourceType);
            record.setSourceBookId(req.sourceBookId);
            record.setChapterIndex(req.chapterIndex);
            record.setChapterTitle(req.chapterTitle);
            record.setBookTitle(req.bookTitle);
            record.setBookAuthor(req.bookAuthor);
            record.setCoverUrl(req.coverUrl);
            record.setPage(req.page);
            record.setUpdateTime(System.currentTimeMillis());

            ExternalReadingRecord saved = externalReadingRecordRepository.save(record);
            return ApiResponse.success(saved);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    // 获取外站书籍阅读记录
    @GetMapping("/get")
    public ApiResponse<Map<String, Object>> getRecord(
            @RequestParam String sourceType,
            @RequestParam String sourceBookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        return externalReadingRecordRepository
                .findByUserIdAndSourceTypeAndSourceBookId(userId, sourceType, sourceBookId)
                .map(this::toMap)
                .map(ApiResponse::success)
                .orElse(ApiResponse.success(null));
    }

    // 删除外站书籍阅读记录（用户在阅读记录里删除时调用，避免下次登录被重新拉回）
    @PostMapping("/delete")
    public ApiResponse<Void> deleteRecord(@RequestBody DeleteRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            externalReadingRecordRepository.deleteByUserIdAndSourceTypeAndSourceBookId(
                    userId, req.sourceType, req.sourceBookId);
            return ApiResponse.success("记录已删除", null);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    // 获取当前用户全部外站阅读记录（登录时全量拉取用）
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            List<Map<String, Object>> result = externalReadingRecordRepository
                    .findAllByUserId(userId)
                    .stream()
                    .map(this::toMap)
                    .collect(Collectors.toList());
            return ApiResponse.success(result);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    private Map<String, Object> toMap(ExternalReadingRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.getId());
        map.put("userId", record.getUserId());
        map.put("sourceType", record.getSourceType());
        map.put("sourceBookId", record.getSourceBookId());
        map.put("chapterIndex", record.getChapterIndex());
        map.put("chapterTitle", record.getChapterTitle());
        map.put("bookTitle", record.getBookTitle());
        map.put("bookAuthor", record.getBookAuthor());
        map.put("coverUrl", record.getCoverUrl());
        map.put("page", record.getPage());
        map.put("updateTime", record.getUpdateTime());
        return map;
    }

    @lombok.Data
    public static class SaveRequest {
        private String sourceType;
        private String sourceBookId;
        private int chapterIndex;
        private String chapterTitle;
        private String bookTitle;
        private String bookAuthor;
        private String coverUrl;
        private int page;
    }

    @lombok.Data
    public static class DeleteRequest {
        private String sourceType;
        private String sourceBookId;
    }
}
