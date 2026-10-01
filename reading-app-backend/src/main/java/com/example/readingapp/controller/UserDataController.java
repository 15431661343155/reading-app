package com.example.readingapp.controller;

import com.example.readingapp.exception.BusinessException;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.ReadTimeRequest;
import com.example.readingapp.dto.ReadingCheckInRequest;
import com.example.readingapp.dto.UserReadingStatResponse;
import com.example.readingapp.entity.*;
import com.example.readingapp.repository.*;
import com.example.readingapp.service.UserReadingStatService;
import com.example.readingapp.utils.SecurityUtils;
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
    private final UserReadingStatService readingStatService;
    private final UserRepository userRepo;

    /**
     * 将字符串 userId 转换为数据库主键
     * 前端传递的是6位字符串 userId，需要转换为数据库主键 Long
     */
    private Long convertUserId(String userIdStr) {
        if (userIdStr == null || userIdStr.isEmpty()) {
            throw new BusinessException("用户ID无效");
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
            throw new BusinessException("用户ID无效");
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

    /**
     * 归属校验：操作目标数据（dbUserId）必须属于当前登录用户。
     *
     * <p>通过校验返回 null；否则返回应直接回给调用方的错误响应。
     * 这是修复「匿名/他人可通过路径或请求体中的 userId 读写他人书架、进度、书签」越权缺陷的关键。
     */
    private static <T> ApiResponse<T> checkOwner(Long dbUserId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) {
            return ApiResponse.error(401, "未登录，请先登录");
        }
        if (!currentUserId.equals(dbUserId)) {
            return ApiResponse.error(403, "无权访问他人数据");
        }
        return null;
    }

    // ========== 阅读进度 ==========

    @PostMapping("/progress/save")
    public ApiResponse<ReadingProgress> saveProgress(@RequestBody ReadingProgress progress) {
        try {
            Long dbUserId = ensureValidUserId(progress.getUserId());
            ApiResponse<ReadingProgress> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            progress.setUserId(dbUserId);
            ReadingProgress exist = progressRepo.findByUserIdAndBookId(dbUserId, progress.getBookId()).orElse(null);
            if (exist != null) {
                exist.setChapterIndex(progress.getChapterIndex());
                exist.setScrollPosition(progress.getScrollPosition());
                // 章节 id 只在本次请求带了值时才更新：客户端不一定都上传 chapterId
                // （老版本 App 就不传），无条件覆盖会把已有的章节冲成 null，
                // 后台「用户阅读数据」页就看不到「读到哪一章」了。
                if (progress.getChapterId() != null) {
                    exist.setChapterId(progress.getChapterId());
                }
                // 阅读偏好（fontSize / nightMode / bgColor / textAnchor）刻意不写：
                // 它们由各客户端各自本地保存（App 用 read_settings、web 用 localStorage），
                // 而进度记录一本书只有一条（uk_user_book），两端共写必然互相覆盖。
                // 因此共享记录只承载位置信息，偏好不再随进度落库。
                exist.setUpdatedAt(LocalDateTime.now());
                return ApiResponse.success(progressRepo.save(exist));
            }
            // 新记录同样不承载偏好，一律写实体默认值：老版本客户端仍会随进度上传这些字段，
            // 这里显式归位，避免它们的值留在库里被误当成「当前阅读设置」。
            progress.setFontSize(18f);
            progress.setNightMode(0);
            progress.setBgColor(0);
            progress.setTextAnchor(null);
            progress.setUpdatedAt(LocalDateTime.now());
            return ApiResponse.success(progressRepo.save(progress));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
            } catch (Exception e) {
            return ApiResponse.error("保存失败");
        }
    }

    @GetMapping("/progress/{userId}/{bookId}")
    public ApiResponse<ReadingProgress> getProgress(@PathVariable String userId, @PathVariable Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            ApiResponse<ReadingProgress> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            return ApiResponse.success(
                    progressRepo.findByUserIdAndBookId(dbUserId, bookId).orElse(null)
            );
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @GetMapping("/progress/recent/{userId}")
    public ApiResponse<List<ReadingProgress>> getRecentReading(@PathVariable String userId) {
        try {
            Long dbUserId = convertUserId(userId);
            ApiResponse<List<ReadingProgress>> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            return ApiResponse.success(
                    progressRepo.findByUserIdOrderByUpdatedAtDesc(dbUserId, PageRequest.of(0, 10))
            );
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    // ========== 书架 ==========

    @PostMapping("/bookshelf/add")
    public ApiResponse<Bookshelf> addToBookshelf(@RequestParam String userId, @RequestParam Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            ApiResponse<Bookshelf> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
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
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @GetMapping("/bookshelf/{userId}")
    @Transactional
    public ApiResponse<List<Bookshelf>> getBookshelf(@PathVariable String userId) {
        try {
            Long dbUserId = convertUserId(userId);
            ApiResponse<List<Bookshelf>> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            return ApiResponse.success(
                    bookshelfRepo.findByUserIdOrderByLastReadAtDesc(dbUserId, PageRequest.of(0, 50)).getContent()
            );
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @DeleteMapping("/bookshelf/remove")
    public ApiResponse<Void> removeFromBookshelf(@RequestParam String userId, @RequestParam Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            ApiResponse<Void> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            bookshelfRepo.deleteByUserIdAndBookId(dbUserId, bookId);
            return ApiResponse.success(null);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
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
                    ApiResponse<Bookmark> denied = checkOwner(exist.getUserId());
                    if (denied != null) {
                        return denied;
                    }
                    if (bookmark.getNote() != null) exist.setNote(bookmark.getNote());
                    if (bookmark.getPreviewText() != null) exist.setPreviewText(bookmark.getPreviewText());
                    return ApiResponse.success(bookmarkRepo.save(exist));
                }
            }
            // 新增书签
            bookmark.setId(null);
            // 1) 确保 userId 是数据库主键（兼容业务 userId 数字字符串）
            Long dbUserId = ensureValidUserId(bookmark.getUserId());
            ApiResponse<Bookmark> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            bookmark.setUserId(dbUserId);
            // 2) 确保 chapterId 有值：如果没传，通过 bookId + chapterIndex 查
            if ((bookmark.getChapterId() == null || bookmark.getChapterId() <= 0)
                    && bookmark.getBookId() != null && bookmark.getChapterIndex() != null) {
                chapterRepo.findByBookIdAndSortOrder(bookmark.getBookId(), bookmark.getChapterIndex())
                        .ifPresent(ch -> bookmark.setChapterId(ch.getId()));
            }
            Bookmark saved = bookmarkService.addBookmark(bookmark);
            return ApiResponse.success(saved);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
            } catch (Exception e) {
            return ApiResponse.error("添加失败");
        }
    }

    @GetMapping("/bookmark/{userId}/{bookId}")
    @Transactional
    public ApiResponse<List<Bookmark>> getBookmarks(@PathVariable String userId, @PathVariable Long bookId) {
        try {
            Long dbUserId = convertUserId(userId);
            ApiResponse<List<Bookmark>> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            List<Bookmark> list = bookmarkRepo.findByUserIdAndBookIdOrderByCreatedAtDesc(dbUserId, bookId);
            return ApiResponse.success(list);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @DeleteMapping("/bookmark/{id}")
    public ApiResponse<Void> deleteBookmark(@PathVariable Long id) {
        Bookmark exist = bookmarkRepo.findById(id).orElse(null);
        if (exist == null) {
            // 幂等：书签已不存在时直接返回成功，避免重复删除报错
            return ApiResponse.success(null);
        }
        ApiResponse<Void> denied = checkOwner(exist.getUserId());
        if (denied != null) {
            return denied;
        }
        bookmarkRepo.deleteById(id);
        return ApiResponse.success(null);
    }

    /**
     * 获取用户总阅读时长（秒）。
     *
     * <p>读 {@code user_reading_stat} 的累计值（一次主键查询），不再对流水表做全表 SUM。
     * 统计记录缺失时按服务层规则补建，因此老账号也不会返回 0 之外的异常。
     */
    @GetMapping("/readtime/total/{userId}")
    public ApiResponse<Long> getTotalReadTime(@PathVariable String userId) {
        try {
            Long dbUserId = convertUserId(userId);
            ApiResponse<Long> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            Long total = readingStatService.getStat(dbUserId).getTotalReadSeconds();
            return ApiResponse.success(total != null ? total : 0L);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    /**
     * 保存阅读时长。
     *
     * <p>双写：{@code read_time_record} 留明细（按书统计/审计用），
     * {@code user_reading_stat} 累加总时长（个人中心展示用）。
     * 流水写失败不影响累计值，反之亦然——两者互不阻塞阅读体验。
     */
    @PostMapping("/readtime/save")
    public ApiResponse<Void> saveReadTime(@RequestBody ReadTimeRequest request) {
        try {
            Long dbUserId = ensureValidUserId(request.getUserId());
            ApiResponse<Void> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            // 1) 流水明细
            ReadTimeRecord record = new ReadTimeRecord();
            record.setUserId(dbUserId);
            record.setBookId(request.getBookId());
            record.setDuration(request.getDuration());
            readTimeRecordRepository.save(record);
            // 2) 累计值
            if (request.getDuration() != null && request.getDuration() > 0) {
                readingStatService.addReadSeconds(dbUserId, request.getDuration());
            }
            return ApiResponse.success(null);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
            } catch (Exception e) {
            return ApiResponse.error("保存失败");
        }
    }

    // ========== 阅读统计（连续天数 / 累计时长） ==========

    /**
     * 读取账号级阅读统计，供个人中心一次性取全（连续天数 + 累计时长 + 累计打卡天数）。
     */
    @GetMapping("/reading-stat/{userId}")
    public ApiResponse<UserReadingStatResponse> getReadingStat(@PathVariable String userId) {
        try {
            Long dbUserId = convertUserId(userId);
            ApiResponse<UserReadingStatResponse> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            UserReadingStat stat = readingStatService.getStat(dbUserId);
            return ApiResponse.success(toStatResponse(stat));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    /**
     * 阅读打卡：记「今天读过」，推进连续天数。
     *
     * <p>幂等——同一天重复调用不会把天数刷上去。客户端每次打开阅读器调一次即可，
     * 不必自己判断今天是否已经打过卡。
     *
     * <p>{@code migratedStreak} 只有旧版客户端会带：服务端从未打卡过时采纳其本地天数，
     * 保证老用户攒的连续记录不白费。
     */
    @PostMapping("/reading-stat/checkin")
    public ApiResponse<UserReadingStatResponse> checkInReading(@RequestBody ReadingCheckInRequest request) {
        try {
            Long dbUserId = ensureValidUserId(request.getUserId());
            ApiResponse<UserReadingStatResponse> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            int migrated = request.getMigratedStreak() == null ? 0 : request.getMigratedStreak();
            UserReadingStat stat = readingStatService.checkIn(dbUserId, migrated);
            return ApiResponse.success(toStatResponse(stat));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
            } catch (Exception e) {
            return ApiResponse.error("打卡失败");
        }
    }

    /** 实体 → 响应 DTO，统一空值处理。 */
    private UserReadingStatResponse toStatResponse(UserReadingStat stat) {
        return new UserReadingStatResponse(
                stat.getStreakDays() == null ? 0 : stat.getStreakDays(),
                stat.getMaxStreakDays() == null ? 0 : stat.getMaxStreakDays(),
                stat.getTotalReadDays() == null ? 0 : stat.getTotalReadDays(),
                stat.getTotalReadSeconds() == null ? 0L : stat.getTotalReadSeconds());
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
            ApiResponse<Void> denied = checkOwner(dbUserId);
            if (denied != null) {
                return denied;
            }
            progressRepo.deleteByUserIdAndBookId(dbUserId, bookId);
            bookmarkRepo.deleteByUserIdAndBookId(dbUserId, bookId);
            return ApiResponse.<Void>success(null);
        } catch (Exception e) {
            return ApiResponse.error("删除失败");
        }
    }
}