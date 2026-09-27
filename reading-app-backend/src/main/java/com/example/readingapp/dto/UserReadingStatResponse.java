package com.example.readingapp.dto;

import lombok.Data;

/**
 * 个人中心统计卡数据：{@code {streakDays, totalReadSeconds, totalReadDays, maxStreakDays}}。
 *
 * <p>一次性返回，避免客户端为一个卡片打多个接口。
 */
@Data
public class UserReadingStatResponse {

    /** 当前连续阅读天数。 */
    private Integer streakDays;

    /** 历史最长连续阅读天数。 */
    private Integer maxStreakDays;

    /** 累计打卡天数（含断签前的）。 */
    private Integer totalReadDays;

    /** 累计阅读时长（秒）。 */
    private Long totalReadSeconds;

    public UserReadingStatResponse() {
    }

    public UserReadingStatResponse(Integer streakDays, Integer maxStreakDays,
                                   Integer totalReadDays, Long totalReadSeconds) {
        this.streakDays = streakDays;
        this.maxStreakDays = maxStreakDays;
        this.totalReadDays = totalReadDays;
        this.totalReadSeconds = totalReadSeconds;
    }
}
