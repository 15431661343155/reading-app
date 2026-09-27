package com.example.myapplication.bean;

public class ReadTimeRequest {
    private long userId;
    private long bookId;
    /** 本次阅读时长，<b>单位：秒</b>（与后端 total_read_seconds 一致，展示端自行折算分钟/小时）。 */
    private long duration;

    public ReadTimeRequest(long userId, long bookId, long duration) {
        this.userId = userId;
        this.bookId = bookId;
        this.duration = duration;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getBookId() {
        return bookId;
    }

    public void setBookId(long bookId) {
        this.bookId = bookId;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    @Override
    public String toString() {
        return "ReadTimeRequest{" +
                "userId=" + userId +
                ", bookId=" + bookId +
                ", duration=" + duration +
                " seconds}";
    }
}