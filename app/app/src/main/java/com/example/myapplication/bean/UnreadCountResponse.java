package com.example.myapplication.bean;

/**
 * 未读消息数量响应
 * 后端接口: GET /api/user/message/{userId}/unread-count
 */
public class UnreadCountResponse {
    private long total;
    private long comment;
    private long like;
    private long follow;
    private long system;

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public long getComment() { return comment; }
    public void setComment(long comment) { this.comment = comment; }

    public long getLike() { return like; }
    public void setLike(long like) { this.like = like; }

    public long getFollow() { return follow; }
    public void setFollow(long follow) { this.follow = follow; }

    public long getSystem() { return system; }
    public void setSystem(long system) { this.system = system; }
}
