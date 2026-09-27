package com.example.myapplication.bean;

/**
 * 账号级阅读统计（对应后端 {@code GET /api/user/reading-stat/{userId}}）。
 *
 * <p>一次性拿到个人中心需要的全部数字：连续天数 + 累计时长 + 累计打卡天数 + 最长连续。
 */
public class UserReadingStat {

    private int streakDays;
    private int maxStreakDays;
    private int totalReadDays;
    private long totalReadSeconds;

    public int getStreakDays() { return streakDays; }
    public void setStreakDays(int streakDays) { this.streakDays = streakDays; }

    public int getMaxStreakDays() { return maxStreakDays; }
    public void setMaxStreakDays(int maxStreakDays) { this.maxStreakDays = maxStreakDays; }

    public int getTotalReadDays() { return totalReadDays; }
    public void setTotalReadDays(int totalReadDays) { this.totalReadDays = totalReadDays; }

    public long getTotalReadSeconds() { return totalReadSeconds; }
    public void setTotalReadSeconds(long totalReadSeconds) { this.totalReadSeconds = totalReadSeconds; }
}
