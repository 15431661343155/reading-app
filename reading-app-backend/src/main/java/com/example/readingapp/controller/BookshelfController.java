package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Bookshelf;
import com.example.readingapp.service.BookshelfService;
import com.example.readingapp.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

/**
 * 用户书架。
 *
 * 安全：userId 一律从 JWT（SecurityContext）派生，忽略请求传入的 userId，杜绝写/读他人书架。
 * 为兼容 App 既有 URL，`/user/{userId}` 与 `/count/{userId}` 路径保持不变，但路径中的 id 不再被信任。
 */
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BookshelfController {

    private final BookshelfService bookshelfService;

    // 加入书架
    @PostMapping("/add")
    public ApiResponse<Bookshelf> addToBookshelf(@RequestParam Long bookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            Bookshelf bookshelf = bookshelfService.addToBookshelf(userId, bookId);
            return ApiResponse.success("已加入书架", bookshelf);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 从书架移除
    @DeleteMapping("/remove")
    public ApiResponse<Void> removeFromBookshelf(@RequestParam Long bookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            bookshelfService.removeFromBookshelf(userId, bookId);
            return ApiResponse.success("已移出书架", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取用户书架（忽略路径中的 userId，一律查当前登录用户）
    @GetMapping("/user/{userId}")
    public ApiResponse<Page<Bookshelf>> getUserBookshelf(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) return ApiResponse.error(401, "未登录");
        Pageable pageable = PageRequest.of(page, size);
        Page<Bookshelf> bookshelf = bookshelfService.getUserBookshelf(currentUserId, pageable);
        return ApiResponse.success(bookshelf);
    }

    // 检查是否在书架中
    @GetMapping("/check")
    public ApiResponse<Boolean> isInBookshelf(@RequestParam Long bookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        boolean inShelf = bookshelfService.isInBookshelf(userId, bookId);
        return ApiResponse.success(inShelf);
    }

    // 获取书架数量（忽略路径中的 userId）
    @GetMapping("/count/{userId}")
    public ApiResponse<Long> getBookshelfCount(@PathVariable Long userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) return ApiResponse.error(401, "未登录");
        long count = bookshelfService.getBookshelfCount(currentUserId);
        return ApiResponse.success(count);
    }
}
