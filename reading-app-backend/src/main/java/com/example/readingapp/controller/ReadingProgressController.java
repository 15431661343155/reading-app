package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.ReadingProgress;
import com.example.readingapp.service.ReadingProgressService;
import com.example.readingapp.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 阅读进度（网络书）。
 *
 * 安全：userId 一律从 JWT（SecurityContext）派生，忽略请求传入的 userId。
 * `/recent/{userId}` 路径保持不变，但路径中的 id 不再被信任。
 */
@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ReadingProgressController {

    private final ReadingProgressService progressService;

    // 保存或更新阅读进度（userId 强制为当前登录用户）
    @PostMapping("/save")
    public ApiResponse<ReadingProgress> saveProgress(@RequestBody ReadingProgress progress) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            progress.setUserId(userId);
            ReadingProgress saved = progressService.saveProgress(progress);
            return ApiResponse.success("进度已保存", saved);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取阅读进度
    @GetMapping("/get")
    public ApiResponse<ReadingProgress> getProgress(@RequestParam Long bookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        ReadingProgress progress = progressService.getProgress(userId, bookId);
        return ApiResponse.success(progress);
    }

    // 获取最近阅读（忽略路径中的 userId）
    @GetMapping("/recent/{userId}")
    public ApiResponse<List<ReadingProgress>> getRecentReading(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "10") int limit) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) return ApiResponse.error(401, "未登录");
        List<ReadingProgress> recent = progressService.getRecentReading(currentUserId, limit);
        return ApiResponse.success(recent);
    }

    // 删除阅读进度
    @DeleteMapping("/delete")
    public ApiResponse<Void> deleteProgress(@RequestParam Long bookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            progressService.deleteProgress(userId, bookId);
            return ApiResponse.success("进度已删除", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
