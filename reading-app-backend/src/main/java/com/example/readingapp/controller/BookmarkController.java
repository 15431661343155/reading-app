package com.example.readingapp.controller;

import com.example.readingapp.exception.BusinessException;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Bookmark;
import com.example.readingapp.repository.BookmarkRepository;
import com.example.readingapp.service.BookmarkService;
import com.example.readingapp.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户书签。
 *
 * 安全：userId 一律从 JWT（SecurityContext）派生，忽略请求传入的 userId；
 * 删除时校验归属，禁止操作他人书签。`/user/{userId}` 路径保持不变但路径中的 id 不再被信任。
 */
@RestController
@RequestMapping("/api/bookmarks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BookmarkController {

    private final BookmarkService bookmarkService;
    private final BookmarkRepository bookmarkRepository;

    // 添加书签（userId 强制为当前登录用户）
    @PostMapping("/add")
    public ApiResponse<Bookmark> addBookmark(@RequestBody Bookmark bookmark) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            bookmark.setUserId(userId);
            Bookmark saved = bookmarkService.addBookmark(bookmark);
            return ApiResponse.success("书签已添加", saved);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    // 删除书签（校验归属，禁止删除他人书签）
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteBookmark(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        try {
            boolean owned = bookmarkRepository.findById(id)
                    .map(b -> userId.equals(b.getUserId()))
                    .orElse(false);
            if (!owned) return ApiResponse.error(403, "无权操作该书签");
            bookmarkService.deleteBookmark(id);
            return ApiResponse.success("书签已删除", null);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    // 获取当前用户在某本书的所有书签
    @GetMapping("/book")
    public ApiResponse<List<Bookmark>> getBookmarksByBook(@RequestParam Long bookId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        List<Bookmark> bookmarks = bookmarkService.getBookmarksByBook(userId, bookId);
        return ApiResponse.success(bookmarks);
    }

    // 分页获取当前用户的所有书签（忽略路径中的 userId）
    @GetMapping("/user/{userId}")
    public ApiResponse<Page<Bookmark>> getUserBookmarks(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) return ApiResponse.error(401, "未登录");
        Pageable pageable = PageRequest.of(page, size);
        Page<Bookmark> bookmarks = bookmarkService.getUserBookmarks(currentUserId, pageable);
        return ApiResponse.success(bookmarks);
    }

    // 检查书签是否存在
    @GetMapping("/check")
    public ApiResponse<Boolean> isBookmarkExists(
            @RequestParam Long bookId,
            @RequestParam Integer chapterIndex) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error(401, "未登录");
        boolean exists = bookmarkService.isBookmarkExists(userId, bookId, chapterIndex);
        return ApiResponse.success(exists);
    }
}
