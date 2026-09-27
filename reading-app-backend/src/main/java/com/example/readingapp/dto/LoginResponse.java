package com.example.readingapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private Long id;
    private String userId;
    private String username;
    private String nickname;
    private String avatar;
    private String email;
    private String phone;
    /** 本次登录是否伴随新账号创建（邮箱验证码自动注册 / 邮箱注册）。默认 false。 */
    private Boolean newUser = false;
}
