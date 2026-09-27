package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.CodeRequest;
import com.example.readingapp.dto.CodeVerifyRequest;
import com.example.readingapp.dto.EmailRegisterRequest;
import com.example.readingapp.dto.LoginRequest;
import com.example.readingapp.dto.LoginResponse;
import com.example.readingapp.dto.RegisterRequest;
import com.example.readingapp.entity.User;
import com.example.readingapp.service.UserService;
import com.example.readingapp.service.VerificationCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserService userService;
    private final VerificationCodeService verificationCodeService;

    // 用户注册
    @PostMapping("/register")
    public ApiResponse<User> register(@RequestBody RegisterRequest request) {
        try {
            User user = userService.register(request);
            return ApiResponse.success("注册成功", user);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() != null ? e.getMessage() : "注册失败");
        }
    }

    // 用户登录
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        try {
            LoginResponse response = userService.login(request);
            return ApiResponse.success("登录成功", response);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() != null ? e.getMessage() : "登录失败");
        }
    }

    /**
     * 发送邮箱验证码（登录 / 注册通用，匿名可调）。
     * 限流由 {@link VerificationCodeService#sendEmailCode} 内部统一处理，此处不再叠加。
     */
    @PostMapping("/email-code")
    public ApiResponse<Void> sendEmailCode(@RequestBody CodeRequest request) {
        try {
            String email = request.getEmail();
            if (email == null || email.trim().isEmpty()) {
                return ApiResponse.error("邮箱不能为空");
            }
            verificationCodeService.sendEmailCode(email.trim(), "auth_email");
            return ApiResponse.success("验证码发送成功", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() != null ? e.getMessage() : "验证码发送失败");
        }
    }

    /** 邮箱验证码登录：未注册邮箱首次登录会自动创建账号。 */
    @PostMapping("/login-by-code")
    public ApiResponse<LoginResponse> loginByCode(@RequestBody CodeVerifyRequest request) {
        try {
            LoginResponse response = userService.loginByEmailCode(request.getEmail(), request.getCode());
            return ApiResponse.success("登录成功", response);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() != null ? e.getMessage() : "登录失败");
        }
    }

    /** 邮箱注册：邮箱 + 验证码 + 用户名 + 密码。 */
    @PostMapping("/register-by-email")
    public ApiResponse<LoginResponse> registerByEmail(@RequestBody EmailRegisterRequest request) {
        try {
            LoginResponse response = userService.registerByEmail(
                    request.getEmail(), request.getCode(), request.getUsername(), request.getPassword());
            return ApiResponse.success("注册成功", response);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() != null ? e.getMessage() : "注册失败");
        }
    }
}
