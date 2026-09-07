package com.example.myapplication.bean;

/**
 * 意见反馈请求体
 */
public class FeedbackRequest {
    private long userId;
    private String content;    // 反馈内容
    private String contact;    // 联系方式（可选）
    private String type;       // 反馈类型：bug / suggestion / other

    public FeedbackRequest() {}

    public FeedbackRequest(long userId, String content, String contact, String type) {
        this.userId = userId;
        this.content = content;
        this.contact = contact;
        this.type = type;
    }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
