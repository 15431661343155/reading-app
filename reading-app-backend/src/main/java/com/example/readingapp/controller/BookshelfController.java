package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Bookshelf;
import com.example.readingapp.service.BookshelfService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BookshelfController {

    private final BookshelfService bookshelfService;

    // 加入书架
    @PostMapping("/add")
    public ApiResponse<Bookshelf> addToBookshelf(
            @RequestParam Long userId,
            @RequestParam Long bookId) {
        try {
            Bookshelf bookshelf = bookshelfService.addToBookshelf(userId, bookId);
            return ApiResponse.success("已加入书架", bookshelf);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 从书架移除
    @DeleteMapping("/remove")
    public ApiResponse<Void> removeFromBookshelf(
            @RequestParam Long userId,
            @RequestParam Long bookId) {
        try {
            bookshelfService.removeFromBookshelf(userId, bookId);
            return ApiResponse.success("已移出书架", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 获取用户书架
    @GetMapping("/user/{userId}")
    public ApiResponse<Page<Bookshelf>> getUserBookshelf(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Bookshelf> bookshelf = bookshelfService.getUserBookshelf(userId, pageable);
        return ApiResponse.success(bookshelf);
    }

    // 检查是否在书架中
    @GetMapping("/check")
    public ApiResponse<Boolean> isInBookshelf(
            @RequestParam Long userId,
            @RequestParam Long bookId) {
        boolean inShelf = bookshelfService.isInBookshelf(userId, bookId);
        return ApiResponse.success(inShelf);
    }

    // 获取书架数量
    @GetMapping("/count/{userId}")
    public ApiResponse<Long> getBookshelfCount(@PathVariable Long userId) {
        long count = bookshelfService.getBookshelfCount(userId);
        return ApiResponse.success(count);
    }
}