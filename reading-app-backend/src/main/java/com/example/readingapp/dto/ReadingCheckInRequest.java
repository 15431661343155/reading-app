package com.example.readingapp.dto;

import lombok.Data;

/**
 * 阅读打卡请求。
 *
 * <p>{@code migratedStreak} 用于旧版客户端把本地 SharedPreferences 里攒的连续天数迁到账号上，
 * 只在服务端从未打卡过时生效；新版客户端不传（默认 0）。
 */
@Data
public class ReadingCheckInRequest {
    private Long userId;
    /** 迁移自客户端的本地连续天数，可选。 */
    private Integer migratedStreak;
}
