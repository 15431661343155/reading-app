package com.example.readingapp.service;

import com.example.readingapp.entity.User;

public interface BindService {

    User bindPhone(Long userId, String phone, String code, String type);

    User bindEmail(Long userId, String email, String code, String type);

    User unbindPhone(Long userId, String code);

    User unbindEmail(Long userId, String code);

    User changePasswordByPassword(Long userId, String oldPassword, String newPassword);

    User changePasswordByEmail(Long userId, String email, String code, String newPassword);
}