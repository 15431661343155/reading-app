package com.example.readingapp.dto;

import lombok.Data;

/**
 * 管理后台登录请求体。
 */
@Data
public class AdminLoginRequest {
    private String username;
    private String password;
}
