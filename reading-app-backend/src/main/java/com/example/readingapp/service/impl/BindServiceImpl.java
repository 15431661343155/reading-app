package com.example.readingapp.service.impl;

import com.example.readingapp.entity.User;
import com.example.readingapp.repository.UserRepository;
import com.example.readingapp.service.BindService;
import com.example.readingapp.service.VerificationCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class BindServiceImpl implements BindService {

    private final UserRepository userRepository;
    private final VerificationCodeService verificationCodeService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User bindPhone(Long userId, String phone, String code, String type) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!verificationCodeService.verifySmsCode(phone, code, type)) {
            throw new RuntimeException("验证码无效或已过期");
        }

        if (userRepository.existsByPhone(phone) && !Objects.equals(phone, user.getPhone())) {
            throw new RuntimeException("该手机号已被其他账户绑定");
        }

        user.setPhone(phone);
        return userRepository.save(user);
    }

    @Override
    public User bindEmail(Long userId, String email, String code, String type) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!verificationCodeService.verifyEmailCode(email, code, type)) {
            throw new RuntimeException("验证码无效或已过期");
        }

        if (userRepository.existsByEmail(email) && !Objects.equals(email, user.getEmail())) {
            throw new RuntimeException("该邮箱已被其他账户绑定");
        }

        user.setEmail(email);
        return userRepository.save(user);
    }

    @Override
    public User unbindPhone(Long userId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (user.getPhone() == null || user.getPhone().isEmpty()) {
            throw new RuntimeException("未绑定手机号");
        }

        if (!verificationCodeService.verifySmsCode(user.getPhone(), code, "unbind_phone")) {
            throw new RuntimeException("验证码无效或已过期");
        }

        user.setPhone(null);
        return userRepository.save(user);
    }

    @Override
    public User unbindEmail(Long userId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            throw new RuntimeException("未绑定邮箱");
        }

        if (!verificationCodeService.verifyEmailCode(user.getEmail(), code, "unbind_email")) {
            throw new RuntimeException("验证码无效或已过期");
        }

        user.setEmail(null);
        return userRepository.save(user);
    }

    @Override
    public User changePasswordByPassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("当前密码错误");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }

    @Override
    public User changePasswordByEmail(Long userId, String email, String code, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!verificationCodeService.verifyEmailCode(email, code, "change_password")) {
            throw new RuntimeException("验证码无效或已过期");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }
}