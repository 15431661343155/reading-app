package com.example.readingapp.dto;

import lombok.Data;

@Data
public class LoginRequest {
    /**
     * 登录账号：邮箱或用户名（新客户端使用该字段）。
     * 含 "@" 视为邮箱走邮箱查找，否则按用户名查找。
     */
    private String account;
    /**
     * 兼容旧客户端的历史字段，语义与 {@link #account} 等价。
     * 当 {@link #account} 为空时回退使用该字段。
     */
    private String username;
    private String password;
}
