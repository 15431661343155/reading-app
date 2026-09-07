package com.example.readingapp.dto;

import lombok.Data;

@Data
public class ReadTimeRequest {
    private Long userId;
    private Long bookId;
    private Long duration;  // 本次阅读时长（秒）
}