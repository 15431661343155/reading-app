package com.example.readingapp.dto;

import lombok.Data;

/**
 * 管理后台「内部人员管理」新增账号请求体。
 *
 * <p>role 仅允许 ADMIN（管理员）或 STAFF（内部人员）；普通用户由 App 端注册产生，
 * 不通过本接口创建。
 */
@Data
public class InternalUserRequest {
    private String username;
    private String password;
    private String nickname;
    private String role;
}
