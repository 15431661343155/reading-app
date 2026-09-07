package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.ExternalReadingRecord;
import com.example.readingapp.repository.ExternalReadingRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/external-reading")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ExternalReadingController {

    private final ExternalReadingRecordRepository externalReadingRecordRepository;

    // 保存或更新外站书籍阅读记录
    @PostMapping("/save")
    public ApiResponse<ExternalReadingRecord> saveRecord(@RequestBody SaveRequest req) {
        try {
            ExternalReadingRecord record = externalReadingRecordRepository
                    .findByUserIdAndSourceTypeAndSourceBookId(req.userId, req.sourceType, req.sourceBookId)
                    .orElseGet(ExternalReadingRecord::new);

            record.setUserId(req.userId);
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
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取外站书籍阅读记录
    @GetMapping("/get")
    public ApiResponse<Map<String, Object>> getRecord(
            @RequestParam Long userId,
            @RequestParam String sourceType,
            @RequestParam String sourceBookId) {
        return externalReadingRecordRepository
                .findByUserIdAndSourceTypeAndSourceBookId(userId, sourceType, sourceBookId)
                .map(this::toMap)
                .map(ApiResponse::success)
                .orElse(ApiResponse.success(null));
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
        private Long userId;
        private String sourceType;
        private String sourceBookId;
        private int chapterIndex;
        private String chapterTitle;
        private String bookTitle;
        private String bookAuthor;
        private String coverUrl;
        private int page;
    }
}
