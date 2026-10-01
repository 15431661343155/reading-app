package com.example.readingapp.service.impl;

import com.example.readingapp.exception.BusinessException;
import com.example.readingapp.entity.User;
import com.example.readingapp.repository.UserRepository;
import com.example.readingapp.service.BindService;
import com.example.readingapp.service.VerificationCodeService;
import com.example.readingapp.utils.PasswordPolicy;
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
            throw new BusinessException("验证码无效或已过期");
        }

        if (userRepository.existsByPhone(phone) && !Objects.equals(phone, user.getPhone())) {
            throw new BusinessException("该手机号已被其他账户绑定");
        }

        user.setPhone(phone);
        return userRepository.save(user);
    }

    @Override
    public User bindEmail(Long userId, String email, String code, String type) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!verificationCodeService.verifyEmailCode(email, code, type)) {
            throw new BusinessException("验证码无效或已过期");
        }

        if (userRepository.existsByEmail(email) && !Objects.equals(email, user.getEmail())) {
            throw new BusinessException("该邮箱已被其他账户绑定");
        }

        user.setEmail(email);
        return userRepository.save(user);
    }

    @Override
    public User unbindPhone(Long userId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (user.getPhone() == null || user.getPhone().isEmpty()) {
            throw new BusinessException("未绑定手机号");
        }

        if (!verificationCodeService.verifySmsCode(user.getPhone(), code, "unbind_phone")) {
            throw new BusinessException("验证码无效或已过期");
        }

        user.setPhone(null);
        return userRepository.save(user);
    }

    @Override
    public User unbindEmail(Long userId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            throw new BusinessException("未绑定邮箱");
        }

        if (!verificationCodeService.verifyEmailCode(user.getEmail(), code, "unbind_email")) {
            throw new BusinessException("验证码无效或已过期");
        }

        user.setEmail(null);
        return userRepository.save(user);
    }

    @Override
    public User changePasswordByPassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("当前密码错误");
        }

        // 新密码强度校验（在写库前拦截，避免不合规密码落库）
        String passwordError = PasswordPolicy.validate(newPassword);
        if (passwordError != null) {
            throw new BusinessException(passwordError);
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }

    @Override
    public User changePasswordByEmail(Long userId, String email, String code, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 顺序铁律：先校验新密码，再消费验证码。
        // 验证码是一次性的，若先消费却发现密码不合规，用户会白白废掉一次验证码。
        String passwordError = PasswordPolicy.validate(newPassword);
        if (passwordError != null) {
            throw new BusinessException(passwordError);
        }

        if (!verificationCodeService.verifyEmailCode(email, code, "change_password")) {
            throw new BusinessException("验证码无效或已过期");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }
}