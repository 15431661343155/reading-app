package com.example.readingapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 邮箱注册请求：邮箱 + 验证码 + 用户名 + 密码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailRegisterRequest {
    private String email;
    private String code;
    private String username;
    private String password;
}
