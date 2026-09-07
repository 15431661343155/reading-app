package com.example.readingapp.dto;

import lombok.Data;

@Data
public class FeedbackRequest {
    private Long userId;
    private String content;
    private String contact;
    private String type; // 故障报告/功能建议/其他
}