package com.example.myapplication.bean;

import com.google.gson.annotations.SerializedName;

/**
 * 消息中心实体类
 */
public class Message {
    private long id;
    private String title;
    private String content;
    private String type;       // system / activity / interaction / reply / comment / like / follow
    private boolean read;

    // 后端字段名为 createdAt，前端统一用 createTime 访问
    @SerializedName(value = "createTime", alternate = {"createdAt", "create_time"})
    private long createTime;

    public Message() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
}
