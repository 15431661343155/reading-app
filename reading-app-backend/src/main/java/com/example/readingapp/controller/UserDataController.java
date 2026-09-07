package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.ReadTimeRequest;
import com.example.readingapp.entity.*;
import com.example.readingapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UserDataController {

    private final ReadingProgressRepository progressRepo;
    private final BookshelfRepository bookshelfRepo;
    private final BookmarkRepository bookmarkRepo;
    private final com.example.readingapp.service.BookmarkService bookmarkService;
    private final ChapterRepository chapterRepo;
    private final BookRepository bookRepo;
    private final ReadTimeRecordRepository readTimeRecordRepository;
    private final UserRepository userRepo;

    /**
     * 将字符串 userId 转换为数据库主键
     * 前端传递的是6位字符串 userId，需要转换为数据库主键 Long
     */
    private Long convertUserId(String userIdStr) {
        if (userIdStr == null || userIdStr.isEmpty()) {
            throw new RuntimeException("用户ID无效");
        }
        // 尝试直接解析为 Long（如果前端传递的是数据库主键）
        try {
            Long userId = Long.parseLong(userIdStr);
            // 验证用户是否存在
            if (userRepo.existsById(userId)) {
                return userId;
            }
        } catch (NumberFormatException e) {
            // 不是数字，可能是字符串 userId
        }
        // 查找用户
        User user = userRepo.findByUserId(userIdStr)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }

    /**
     * 将 Long userId 转换为数据库主键，兼容业务 userId（数字字符串）
     */
    private Long ensureValidUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new RuntimeException("用户ID无效");
        }
        // 如果已是有效的数据库主键，直接返回
        if (userRepo.existsById(userId)) {
            return userId;
        }
        // 否则认为传进来的是业务 userId（如 123456），通过业务 userId 查主键
        User user = userRepo.findByUserId(String.valueOf(userId))
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }

    // ========== 阅读进度 ==========

    @PostMapping("/progress/save")
    public ApiResponse<ReadingProgress> saveProgress(@RequestBody ReadingProgress progress) {
        try {
            Long dbUserId = ensureValidUserId(progress.getUserId());
            progress.setUserId(dbUserId);
            ReadingProgress exist = progressRepo.findByUserIdAndBookId(dbUserId, progress.getBookId()).orElse(null);
            if (exist != null) {
                exist.setChapterIndex(progress.getChapterIndex());
                exist.setScrollPosition(progress.getScrollPosition());
                exist.setFontSize(progress.getFontSize());
                exist.setNightMode(progress.getNightMode());
                exist.setBgColor(progress.getBgColor());
                exist.setTextAnchor(progress.getTextAnchor());
                exist.setUpdatedAt(LocalDateTime.now());
                return ApiResponse.success(progressRepo.save(exist));
            }
            progress.setUpdatedAt(LocalDateTime.now());
            return ApiResponse.success(progressRepo.save(progress));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() != null ? e.getMessage() : "保存失败");
        }
    }

    @GetMapping("/progress/{userId}/{bookId}")
    public ApiResponse<ReadingProgress> getProgress(@PathVariable String userId, @PathVariable Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            return ApiResponse.success(
                    progressRepo.findByUserIdAndBookId(dbUserId, bookId).orElse(null)
            );
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/progress/recent/{userId}")
    public ApiResponse<List<ReadingProgress>> getRecentReading(@PathVariable String userId) {
        try {
            Long dbUserId = convertUserId(userId);
            return ApiResponse.success(
                    progressRepo.findByUserIdOrderByUpdatedAtDesc(dbUserId, PageRequest.of(0, 10))
            );
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ========== 书架 ==========

    @PostMapping("/bookshelf/add")
    public ApiResponse<Bookshelf> addToBookshelf(@RequestParam String userId, @RequestParam Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            // 验证书籍是否存在
            Book book = bookRepo.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("书籍不存在"));
            if (bookshelfRepo.existsByUserIdAndBookId(dbUserId, bookId)) {
                return ApiResponse.error("已在书架中");
            }
            Bookshelf bs = new Bookshelf();
            bs.setUserId(dbUserId);
            bs.setBookId(bookId);
            bs.setAddedAt(LocalDateTime.now());
            return ApiResponse.success(bookshelfRepo.save(bs));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/bookshelf/{userId}")
    @Transactional
    public ApiResponse<List<Bookshelf>> getBookshelf(@PathVariable String userId) {
        try {
            Long dbUserId = convertUserId(userId);
            return ApiResponse.success(
                    bookshelfRepo.findByUserIdOrderByLastReadAtDesc(dbUserId, PageRequest.of(0, 50)).getContent()
            );
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/bookshelf/remove")
    public ApiResponse<Void> removeFromBookshelf(@RequestParam String userId, @RequestParam Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            bookshelfRepo.deleteByUserIdAndBookId(dbUserId, bookId);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ========== 书签 ==========

    @PostMapping("/bookmark/add")
    public ApiResponse<Bookmark> addBookmark(@RequestBody Bookmark bookmark) {
        try {
            if (bookmark.getId() != null) {
                // 更新已有书签
                Bookmark exist = bookmarkRepo.findById(bookmark.getId()).orElse(null);
                if (exist != null) {
                    if (bookmark.getNote() != null) exist.setNote(bookmark.getNote());
                    if (bookmark.getPreviewText() != null) exist.setPreviewText(bookmark.getPreviewText());
                    return ApiResponse.success(bookmarkRepo.save(exist));
                }
            }
            // 新增书签
            bookmark.setId(null);
            // 1) 确保 userId 是数据库主键（兼容业务 userId 数字字符串）
            Long dbUserId = ensureValidUserId(bookmark.getUserId());
            bookmark.setUserId(dbUserId);
            // 2) 确保 chapterId 有值：如果没传，通过 bookId + chapterIndex 查
            if ((bookmark.getChapterId() == null || bookmark.getChapterId() <= 0)
                    && bookmark.getBookId() != null && bookmark.getChapterIndex() != null) {
                chapterRepo.findByBookIdAndSortOrder(bookmark.getBookId(), bookmark.getChapterIndex())
                        .ifPresent(ch -> bookmark.setChapterId(ch.getId()));
            }
            Bookmark saved = bookmarkService.addBookmark(bookmark);
            return ApiResponse.success(saved);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() != null ? e.getMessage() : "添加失败");
        }
    }

    @GetMapping("/bookmark/{userId}/{bookId}")
    @Transactional
    public ApiResponse<List<Bookmark>> getBookmarks(@PathVariable String userId, @PathVariable Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            List<Bookmark> list = bookmarkRepo.findByUserIdAndBookIdOrderByCreatedAtDesc(dbUserId, bookId);
            return ApiResponse.success(list);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/bookmark/{id}")
    public ApiResponse<Void> deleteBookmark(@PathVariable Long id) {
        bookmarkRepo.deleteById(id);
        return ApiResponse.success(null);
    }

    /**
     * 获取用户总阅读时长（秒）
     */
    @GetMapping("/readtime/total/{userId}")
    public ApiResponse<Long> getTotalReadTime(@PathVariable String userId) {
        try {
            Long dbUserId = convertUserId(userId);
            Long total = readTimeRecordRepository.getTotalDurationByUserId(dbUserId);
            return ApiResponse.success(total != null ? total : 0L);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 保存阅读时长
     */
    @PostMapping("/readtime/save")
    public ApiResponse<Void> saveReadTime(@RequestBody ReadTimeRequest request) {
        try {
            Long dbUserId = ensureValidUserId(request.getUserId());
            ReadTimeRecord record = new ReadTimeRecord();
            record.setUserId(dbUserId);
            record.setBookId(request.getBookId());
            record.setDuration(request.getDuration());
            readTimeRecordRepository.save(record);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() != null ? e.getMessage() : "保存失败");
        }
    }

    /**
     * 删除阅读进度
     */
    @DeleteMapping("/progress/{userId}/{bookId}")
    public ApiResponse<Void> deleteProgress(
            @PathVariable String userId,
            @PathVariable Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            progressRepo.deleteByUserIdAndBookId(dbUserId, bookId);
            bookmarkRepo.deleteByUserIdAndBookId(dbUserId, bookId);
            return ApiResponse.<Void>success(null);
        } catch (Exception e) {
            return ApiResponse.error("删除失败");
        }
    }
}