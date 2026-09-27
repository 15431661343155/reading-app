package com.example.readingapp.dto;

import com.example.readingapp.entity.Bookmark;
import com.example.readingapp.entity.Bookshelf;
import com.example.readingapp.entity.ReadingProgress;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 管理后台「用户阅读数据」页面的响应体（仅管理员可读）。
 *
 * <p>为什么不直接复用 {@code /api/user/**}：那批接口由 {@code UserDataController.checkOwner()}
 * 强制「请求方 == 数据归属人」，管理员浏览器拿的是自己的身份，查别人一律 403，
 * 导致后台页面永远显示「暂无数据」。管理员是「运营视角」而非「数据归属人」，
 * 因此在这里单独开一条受 {@code hasRole("ADMIN")} 保护的只读通道，
 * 既能正常展示，又不松动用户侧接口的越权防线。
 */
@Data
@AllArgsConstructor
public class UserReadingResponse {

    /** 数据库主键 */
    private Long id;

    /** 业务用户号（6 位字符） */
    private String businessUserId;

    private String username;

    private String nickname;

    /** 书架列表（按最后阅读时间倒序，最多 50 条） */
    private List<Bookshelf> bookshelf;

    /** 最近阅读进度（按更新时间倒序，最多 20 条） */
    private List<ReadingProgress> recentProgress;

    /** 全部书签（按创建时间倒序，最多 50 条） */
    private List<Bookmark> bookmarks;

    /** bookId -> 书名。书签实体没有书籍关联，靠这张表在页面上显示真实书名。 */
    private Map<Long, String> bookTitles;
}
