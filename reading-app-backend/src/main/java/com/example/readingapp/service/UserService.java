package com.example.readingapp.service;

import com.example.readingapp.dto.LoginRequest;
import com.example.readingapp.dto.LoginResponse;
import com.example.readingapp.dto.RegisterRequest;
import com.example.readingapp.entity.User;

public interface UserService {

    // 用户注册
    User register(RegisterRequest request);

    // 用户登录
    LoginResponse login(LoginRequest request);

    // 根据用户名查找用户
    User findByUsername(String username);

    // 根据ID查找用户
    User findById(Long id);

    // 更新用户信息
    User updateUser(Long id, User user);

    // 修改密码
    void changePassword(Long userId, String oldPassword, String newPassword);

    // 检查用户名是否存在
    boolean existsByUsername(String username);

    // 检查邮箱是否存在
    boolean existsByEmail(String email);
}
