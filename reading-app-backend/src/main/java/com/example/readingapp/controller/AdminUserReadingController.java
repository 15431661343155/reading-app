package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.UserReadingResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Bookmark;
import com.example.readingapp.entity.Bookshelf;
import com.example.readingapp.entity.ReadingProgress;
import com.example.readingapp.entity.User;
import com.example.readingapp.repository.BookmarkRepository;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.repository.BookshelfRepository;
import com.example.readingapp.repository.ReadingProgressRepository;
import com.example.readingapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理后台「用户阅读数据」只读接口。
 *
 * <p>路径挂在 {@code /api/admin/**} 下，由 SecurityConfig 的
 * {@code .requestMatchers("/api/admin/**").hasRole("ADMIN")} 统一保护，
 * 匿名与普通用户都无法访问。
 *
 * <p>与 {@link UserDataController} 的区别：后者服务于 App/Web 的「本人数据」，
 * 必须走 {@code checkOwner()} 归属校验；本控制器服务于管理员的「跨用户运营查看」，
 * 因此不做归属校验，但只提供<b>读</b>能力（不提供任何写入）。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminUserReadingController {

    private final UserRepository userRepository;
    private final BookshelfRepository bookshelfRepo;
    private final ReadingProgressRepository progressRepo;
    private final BookmarkRepository bookmarkRepo;
    private final BookRepository bookRepo;

    /**
     * 取某个用户的书架 / 最近阅读进度 / 书签。
     *
     * <p>{@code id} 兼容三种写法，方便后台页面从不同入口跳转：
     * 数据库主键（{@code /admin/users` 列表传的就是它}）、业务用户号（6 位数字字符串）、用户名。
     */
    @GetMapping("/{id}/reading")
    public ApiResponse<UserReadingResponse> getReadingData(@PathVariable String id) {
        User user = resolveUser(id);
        if (user == null) {
            return ApiResponse.error("用户不存在");
        }
        Long dbUserId = user.getId();

        List<Bookshelf> bookshelf =
                bookshelfRepo.findByUserIdOrderByLastReadAtDesc(dbUserId, PageRequest.of(0, 50)).getContent();
        List<ReadingProgress> recentProgress =
                progressRepo.findByUserIdOrderByUpdatedAtDesc(dbUserId, PageRequest.of(0, 20));
        List<Bookmark> bookmarks =
                bookmarkRepo.findByUserIdOrderByCreatedAtDesc(dbUserId, PageRequest.of(0, 50)).getContent();

        return ApiResponse.success(new UserReadingResponse(
                dbUserId,
                user.getUserId(),
                user.getUsername(),
                user.getNickname(),
                bookshelf,
                recentProgress,
                bookmarks,
                collectBookTitles(bookshelf, recentProgress, bookmarks)
        ));
    }

    /** 依次按 数据库主键 → 业务用户号 → 用户名 解析用户 */
    private User resolveUser(String rawId) {
        if (rawId == null || rawId.trim().isEmpty()) {
            return null;
        }
        String key = rawId.trim();
        try {
            User byPrimaryKey = userRepository.findById(Long.parseLong(key)).orElse(null);
            if (byPrimaryKey != null) {
                return byPrimaryKey;
            }
        } catch (NumberFormatException ignored) {
            // 不是数字，继续按业务用户号 / 用户名解析
        }
        User byBusinessId = userRepository.findByUserId(key).orElse(null);
        if (byBusinessId != null) {
            return byBusinessId;
        }
        return userRepository.findByUsername(key).orElse(null);
    }

    /**
     * 汇总本次响应涉及的 bookId -> 书名。
     *
     * <p>书签（{@link Bookmark}）实体上没有书籍关联，页面只能拿到 bookId；
     * 在这里一次性补齐书名，避免前端为每条记录单独发请求，也避免页面出现「书籍 728」这种裸 ID。
     */
    private Map<Long, String> collectBookTitles(List<Bookshelf> bookshelf,
                                                List<ReadingProgress> recentProgress,
                                                List<Bookmark> bookmarks) {
        Set<Long> bookIds = new LinkedHashSet<>();
        for (Bookshelf b : bookshelf) {
            if (b.getBookId() != null) bookIds.add(b.getBookId());
        }
        for (ReadingProgress p : recentProgress) {
            if (p.getBookId() != null) bookIds.add(p.getBookId());
        }
        for (Bookmark bm : bookmarks) {
            if (bm.getBookId() != null) bookIds.add(bm.getBookId());
        }

        Map<Long, String> titles = new LinkedHashMap<>();
        if (bookIds.isEmpty()) {
            return titles;
        }
        for (Book book : bookRepo.findAllById(new ArrayList<>(bookIds))) {
            titles.put(book.getId(), book.getTitle());
        }
        return titles;
    }
}
