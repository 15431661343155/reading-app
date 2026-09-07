package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Bookmark;
import com.example.readingapp.service.BookmarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookmarks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BookmarkController {

    private final BookmarkService bookmarkService;

    // 添加书签
    @PostMapping("/add")
    public ApiResponse<Bookmark> addBookmark(@RequestBody Bookmark bookmark) {
        try {
            Bookmark saved = bookmarkService.addBookmark(bookmark);
            return ApiResponse.success("书签已添加", saved);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 删除书签
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteBookmark(@PathVariable Long id) {
        try {
            bookmarkService.deleteBookmark(id);
            return ApiResponse.success("书签已删除", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取用户在某本书的所有书签
    @GetMapping("/book")
    public ApiResponse<List<Bookmark>> getBookmarksByBook(
            @RequestParam Long userId,
            @RequestParam Long bookId) {
        List<Bookmark> bookmarks = bookmarkService.getBookmarksByBook(userId, bookId);
        return ApiResponse.success(bookmarks);
    }

    // 分页获取用户的所有书签
    @GetMapping("/user/{userId}")
    public ApiResponse<Page<Bookmark>> getUserBookmarks(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Bookmark> bookmarks = bookmarkService.getUserBookmarks(userId, pageable);
        return ApiResponse.success(bookmarks);
    }

    // 检查书签是否存在
    @GetMapping("/check")
    public ApiResponse<Boolean> isBookmarkExists(
            @RequestParam Long userId,
            @RequestParam Long bookId,
            @RequestParam Integer chapterIndex) {
        boolean exists = bookmarkService.isBookmarkExists(userId, bookId, chapterIndex);
        return ApiResponse.success(exists);
    }
}