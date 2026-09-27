package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.ExternalBookshelf;
import com.example.readingapp.repository.ExternalBookshelfRepository;
import com.example.readingapp.utils.SecurityUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 外站书书架（用户隔离）。
 *
 * 安全：userId 一律从 JWT（SecurityContext）派生，忽略请求里传来的 userId，
 * 杜绝「已登录用户写他人数据」。请求体/查询参数中若仍带 userId 会被忽略（向后兼容 App）。
 */
@RestController
@RequestMapping("/api/external-bookshelf")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ExternalBookshelfController {

    private final ExternalBookshelfRepository externalBookshelfRepository;

    // 加入书架（外站书，按用户 + 书源唯一）
    @PostMapping("/add")
    public ApiResponse<ExternalBookshelf> add(@RequestBody AddRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            ExternalBookshelf shelf = externalBookshelfRepository
                    .findByUserIdAndSourceTypeAndSourceBookId(userId, req.sourceType, req.sourceBookId)
                    .orElseGet(ExternalBookshelf::new);
            shelf.setUserId(userId);
            shelf.setSourceType(req.sourceType);
            shelf.setSourceBookId(req.sourceBookId);
            shelf.setBookTitle(req.bookTitle);
            shelf.setBookAuthor(req.bookAuthor);
            shelf.setCoverUrl(req.coverUrl);
            if (req.chapterCount != null) shelf.setChapterCount(req.chapterCount);
            shelf.setUpdateTime(System.currentTimeMillis());
            ExternalBookshelf saved = externalBookshelfRepository.save(shelf);
            return ApiResponse.success("已加入书架", saved);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 移出书架
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestBody RemoveRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            externalBookshelfRepository.deleteByUserIdAndSourceTypeAndSourceBookId(
                    userId, req.sourceType, req.sourceBookId);
            return ApiResponse.success("已移出书架", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取当前用户全部外站书架
    @GetMapping("/list")
    public ApiResponse<List<ExternalBookshelf>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            return ApiResponse.success(externalBookshelfRepository.findAllByUserId(userId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 检查是否在书架中
    @GetMapping("/check")
    public ApiResponse<Boolean> check(
            @RequestParam String sourceType,
            @RequestParam String sourceBookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        boolean in = externalBookshelfRepository
                .findByUserIdAndSourceTypeAndSourceBookId(userId, sourceType, sourceBookId)
                .isPresent();
        return ApiResponse.success(in);
    }

    @Data
    public static class AddRequest {
        private String sourceType;
        private String sourceBookId;
        private String bookTitle;
        private String bookAuthor;
        private String coverUrl;
        private Integer chapterCount;
    }

    @Data
    public static class RemoveRequest {
        private String sourceType;
        private String sourceBookId;
    }
}
