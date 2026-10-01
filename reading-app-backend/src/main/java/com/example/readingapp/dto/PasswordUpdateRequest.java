package com.example.readingapp.dto;

import lombok.Data;

/**
 * 管理后台重置用户密码请求体。
 */
@Data
public class PasswordUpdateRequest {
    /** 新密码（明文，服务端 BCrypt 落库；走与注册一致的强度策略） */
    private String newPassword;
}
