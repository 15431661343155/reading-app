package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.ReadingProgress;
import com.example.readingapp.service.ReadingProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ReadingProgressController {

    private final ReadingProgressService progressService;

    // 保存或更新阅读进度
    @PostMapping("/save")
    public ApiResponse<ReadingProgress> saveProgress(@RequestBody ReadingProgress progress) {
        try {
            ReadingProgress saved = progressService.saveProgress(progress);
            return ApiResponse.success("进度已保存", saved);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取阅读进度
    @GetMapping("/get")
    public ApiResponse<ReadingProgress> getProgress(
            @RequestParam Long userId,
            @RequestParam Long bookId) {
        ReadingProgress progress = progressService.getProgress(userId, bookId);
        return ApiResponse.success(progress);
    }

    // 获取最近阅读
    @GetMapping("/recent/{userId}")
    public ApiResponse<List<ReadingProgress>> getRecentReading(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "10") int limit) {
        List<ReadingProgress> recent = progressService.getRecentReading(userId, limit);
        return ApiResponse.success(recent);
    }

    // 删除阅读进度
    @DeleteMapping("/delete")
    public ApiResponse<Void> deleteProgress(
            @RequestParam Long userId,
            @RequestParam Long bookId) {
        try {
            progressService.deleteProgress(userId, bookId);
            return ApiResponse.success("进度已删除", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}