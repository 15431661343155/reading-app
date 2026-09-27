package com.example.myapplication.bean;

/**
 * 阅读打卡请求（对应后端 {@code POST /api/user/reading-stat/checkin}）。
 *
 * <p>{@code migratedStreak} 用于把旧版本存在本机的连续天数迁到账号上：
 * 服务端只在「从未打卡过」时采纳，因此重复上报也不会覆盖已有进度。
 */
public class ReadingCheckInRequest {

    private long userId;
    private int migratedStreak;

    public ReadingCheckInRequest(long userId, int migratedStreak) {
        this.userId = userId;
        this.migratedStreak = migratedStreak;
    }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public int getMigratedStreak() { return migratedStreak; }
    public void setMigratedStreak(int migratedStreak) { this.migratedStreak = migratedStreak; }
}
