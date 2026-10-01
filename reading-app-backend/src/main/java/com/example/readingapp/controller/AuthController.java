package com.example.readingapp.controller;

import com.example.readingapp.exception.BusinessException;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.CodeRequest;
import com.example.readingapp.dto.CodeVerifyRequest;
import com.example.readingapp.dto.EmailRegisterRequest;
import com.example.readingapp.dto.LoginRequest;
import com.example.readingapp.dto.LoginResponse;
import com.example.readingapp.dto.RegisterRequest;
import com.example.readingapp.entity.User;
import com.example.readingapp.service.RateLimitService;
import com.example.readingapp.service.UserService;
import com.example.readingapp.service.VerificationCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserService userService;
    private final VerificationCodeService verificationCodeService;
    private final RateLimitService rateLimitService;

    // 用户注册
    @PostMapping("/register")
    public ApiResponse<User> register(@RequestBody RegisterRequest request) {
        try {
            User user = userService.register(request);
            return ApiResponse.success("注册成功", user);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("注册失败，请稍后重试");
        }
    }

    // 用户登录（密码）。防爆破：同「IP|账号」15 分钟内失败 5 次锁定 15 分钟。
    // 仅凭证错误（消息为「账号或密码错误」）计数，避免把服务端异常误记为爆破。
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String account = request != null && request.getAccount() != null && !request.getAccount().trim().isEmpty()
                ? request.getAccount().trim() : (request != null ? request.getUsername() : null);
        String guardKey = clientIp(httpRequest) + "|" + (account == null ? "" : account);
        if (account != null && rateLimitService.isLoginBlocked(guardKey)) {
            return ApiResponse.error("失败次数过多，账号已临时锁定，请 15 分钟后再试");
        }
        try {
            LoginResponse response = userService.login(request);
            rateLimitService.recordLoginSuccess(guardKey);
            return ApiResponse.success("登录成功", response);
        } catch (BusinessException e) {
            String msg = e.getMessage();
            if ("账号或密码错误".equals(msg)) {
                rateLimitService.recordLoginFailure(guardKey);
            }
            return ApiResponse.error(msg);
        } catch (Exception e) {
            return ApiResponse.error("登录失败，请稍后重试");
        }
    }

    /** 客户端 IP：优先取 nginx 反代写入的 X-Real-IP（每次覆写，不可被客户端伪造），回退 remoteAddr */
    private String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) return realIp.trim();
        return request.getRemoteAddr();
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
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("验证码发送失败，请稍后重试");
        }
    }

    /** 邮箱验证码登录：未注册邮箱首次登录会自动创建账号。 */
    @PostMapping("/login-by-code")
    public ApiResponse<LoginResponse> loginByCode(@RequestBody CodeVerifyRequest request) {
        try {
            LoginResponse response = userService.loginByEmailCode(request.getEmail(), request.getCode());
            return ApiResponse.success("登录成功", response);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("登录失败，请稍后重试");
        }
    }

    /** 邮箱注册：邮箱 + 验证码 + 用户名 + 密码。 */
    @PostMapping("/register-by-email")
    public ApiResponse<LoginResponse> registerByEmail(@RequestBody EmailRegisterRequest request) {
        try {
            LoginResponse response = userService.registerByEmail(
                    request.getEmail(), request.getCode(), request.getUsername(), request.getPassword());
            return ApiResponse.success("注册成功", response);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("注册失败，请稍后重试");
        }
    }
}
