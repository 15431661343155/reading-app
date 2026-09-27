package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.ExternalBookmark;
import com.example.readingapp.repository.ExternalBookmarkRepository;
import com.example.readingapp.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 外站书书签（用户隔离）。
 *
 * 安全：userId 一律从 JWT（SecurityContext）派生，忽略请求里传来的 userId。
 */
@RestController
@RequestMapping("/api/external-bookmarks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ExternalBookmarkController {

    private final ExternalBookmarkRepository externalBookmarkRepository;

    // 添加/更新书签（外站书，按 用户+书源+章节 唯一，幂等）
    @PostMapping("/add")
    public ApiResponse<ExternalBookmark> add(@RequestBody AddRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            ExternalBookmark bm = externalBookmarkRepository
                    .findByUserIdAndSourceTypeAndSourceBookIdAndChapterIndex(
                            userId, req.sourceType, req.sourceBookId, req.chapterIndex)
                    .orElseGet(ExternalBookmark::new);
            long now = System.currentTimeMillis();
            bm.setUserId(userId);
            bm.setSourceType(req.sourceType);
            bm.setSourceBookId(req.sourceBookId);
            bm.setChapterIndex(req.chapterIndex);
            bm.setChapterTitle(req.chapterTitle);
            bm.setScrollPosition(req.scrollPosition != null ? req.scrollPosition : 0);
            bm.setPreviewText(req.previewText);
            bm.setNote(req.note);
            if (bm.getCreatedAt() == null) bm.setCreatedAt(now);
            bm.setUpdateTime(now);
            return ApiResponse.success("书签已添加", externalBookmarkRepository.save(bm));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 删除书签（按 用户+书源+章节 定位，无需服务端 id 回传）
    @PostMapping("/delete")
    public ApiResponse<Void> delete(@RequestBody DeleteRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            externalBookmarkRepository.deleteByUserIdAndSourceTypeAndSourceBookIdAndChapterIndex(
                    userId, req.sourceType, req.sourceBookId, req.chapterIndex);
            return ApiResponse.success("书签已删除", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取当前用户全部外站书签
    @GetMapping("/list")
    public ApiResponse<List<ExternalBookmark>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            return ApiResponse.success(externalBookmarkRepository.findAllByUserId(userId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取某本书的外站书签
    @GetMapping("/book")
    public ApiResponse<List<ExternalBookmark>> book(
            @RequestParam String sourceType,
            @RequestParam String sourceBookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            return ApiResponse.success(
                    externalBookmarkRepository.findByUserIdAndSourceTypeAndSourceBookId(
                            userId, sourceType, sourceBookId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @lombok.Data
    public static class AddRequest {
        private String sourceType;
        private String sourceBookId;
        private Integer chapterIndex;
        private String chapterTitle;
        private Integer scrollPosition;
        private String previewText;
        private String note;
    }

    @lombok.Data
    public static class DeleteRequest {
        private String sourceType;
        private String sourceBookId;
        private Integer chapterIndex;
    }
}
