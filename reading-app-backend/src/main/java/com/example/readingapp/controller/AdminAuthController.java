package com.example.readingapp.controller;

import com.example.readingapp.dto.AdminLoginRequest;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.User;
import com.example.readingapp.repository.UserRepository;
import com.example.readingapp.service.RateLimitService;
import com.example.readingapp.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台登录：role=ADMIN（管理员）或 role=STAFF（内部人员，权限受限）的账号才能登录。
 *
 * <ul>
 *   <li>GET  /admin/login          登录页面由独立前端（reading-app-admin）静态托管，后端不再渲染；前端已登录则跳首页</li>
 *   <li>POST /api/admin/auth/login 校验账号密码 + 后台角色（ADMIN/STAFF），签发 JWT 并写入 HttpOnly Cookie</li>
 *   <li>POST /api/admin/auth/logout 清除登录 Cookie</li>
 *   <li>GET  /api/admin/auth/me    返回当前登录的后台账号（含实际角色；未登录返回 401）</li>
 *   <li>POST /api/admin/verify-password 校验当前登录管理员的密码（进入「内部人员管理」等敏感操作前的二次确认）</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class AdminAuthController {

    /** 与 {@link com.example.readingapp.config.JwtAuthenticationFilter#ADMIN_COOKIE} 保持一致 */
    private static final String ADMIN_COOKIE = "ADMIN_TOKEN";
    private static final String ADMIN_ROLE = "ADMIN";
    /** 内部人员角色：与 JwtAuthenticationFilter.ROLE_STAFF 一致 */
    private static final String STAFF_ROLE = "STAFF";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RateLimitService rateLimitService;

    @Value("${jwt.expiration:86400000}")
    private Long jwtExpiration;

    // 登录页由独立前端（reading-app-admin）静态托管，后端仅提供 /api/admin/auth/*

    /** 登录接口 */
    @PostMapping("/api/admin/auth/login")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @RequestBody(required = false) AdminLoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String username = request == null ? null : trim(request.getUsername());
        String password = request == null ? null : request.getPassword();

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("请输入账号和密码"));
        }

        // 登录防爆破：同一「IP|账号」15 分钟内失败 5 次锁定 15 分钟（RateLimitService）
        String guardKey = clientIp(httpRequest) + "|" + username;
        if (rateLimitService.isLoginBlocked(guardKey)) {
            return ResponseEntity.ok(ApiResponse.error("失败次数过多，账号已临时锁定，请 15 分钟后再试"));
        }

        // 统一用「账号或密码错误」表述，不区分账号不存在 / 密码错误 / 无后台权限，避免账号枚举
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            rateLimitService.recordLoginFailure(guardKey);
            return ResponseEntity.ok(ApiResponse.error("账号或密码错误"));
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return ResponseEntity.ok(ApiResponse.error("该账号已被禁用"));
        }
        // 仅 ADMIN（管理员）与 STAFF（内部人员）可登录管理后台
        String role = user.getRole();
        boolean backendRole = ADMIN_ROLE.equalsIgnoreCase(role) || STAFF_ROLE.equalsIgnoreCase(role);
        if (!backendRole) {
            return ResponseEntity.ok(ApiResponse.error("该账号没有后台登录权限"));
        }

        // 按实际角色签发 token（JwtAuthenticationFilter 据此授予 ROLE_ADMIN / ROLE_STAFF）
        String effectiveRole = ADMIN_ROLE.equalsIgnoreCase(role) ? ADMIN_ROLE : STAFF_ROLE;
        String token = jwtUtils.generateToken(user.getUsername(), user.getId(), effectiveRole);
        rateLimitService.recordLoginSuccess(guardKey);

        ResponseCookie cookie = ResponseCookie.from(ADMIN_COOKIE, token)
                .path("/")
                .httpOnly(true)
                .sameSite("Strict")
                .maxAge(Duration.ofMillis(jwtExpiration))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", effectiveRole);

        return ResponseEntity.ok(ApiResponse.success("登录成功", data));
    }

    /** 退出登录 */
    @PostMapping("/api/admin/auth/logout")
    @ResponseBody
    public ApiResponse<Void> logout(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(ADMIN_COOKIE, "")
                .path("/")
                .httpOnly(true)
                .sameSite("Strict")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ApiResponse.success("已退出登录", null);
    }

    /** 当前登录的后台账号信息（ADMIN / STAFF），未登录返回 401 */
    @GetMapping("/api/admin/auth/me")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(Authentication authentication) {
        User user = currentBackendUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未登录或登录已过期"));
        }

        String effectiveRole = ADMIN_ROLE.equalsIgnoreCase(user.getRole()) ? ADMIN_ROLE : STAFF_ROLE;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", effectiveRole);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 校验当前登录管理员的密码（进入「内部人员管理」等敏感操作前的二次确认）。
     * 仅 ADMIN 可调用（/api/admin/** 非 auth 前缀已被 SecurityConfig 限定为 ADMIN）。
     */
    @PostMapping("/api/admin/verify-password")
    @ResponseBody
    public ApiResponse<Void> verifyPassword(
            Authentication authentication,
            @RequestBody(required = false) Map<String, String> body) {
        User user = currentBackendUser(authentication);
        if (user == null) {
            return ApiResponse.error(401, "未登录或登录已过期");
        }
        if (!ADMIN_ROLE.equalsIgnoreCase(user.getRole())) {
            return ApiResponse.error("仅管理员可执行该操作");
        }

        String password = body == null ? null : body.get("password");
        if (password == null || password.isEmpty()) {
            return ApiResponse.error("请输入密码");
        }

        // 防爆破：同账号 5 次失败锁 15 分钟（防 Cookie 被窃后暴力破解二级密码确认）
        String verifyKey = "verify-pwd:" + user.getUsername();
        if (rateLimitService.isLoginBlocked(verifyKey)) {
            return ApiResponse.error("失败次数过多，请 15 分钟后再试");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            rateLimitService.recordLoginFailure(verifyKey);
            return ApiResponse.error("密码错误");
        }
        rateLimitService.recordLoginSuccess(verifyKey);
        return ApiResponse.success("验证通过", null);
    }

    /** 取当前登录的后台账号（ADMIN 或 STAFF），未登录/角色不符返回 null */
    private User currentBackendUser(Authentication authentication) {
        boolean backendUser = authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_STAFF".equals(a.getAuthority()));
        if (!backendUser) {
            return null;
        }
        User user = userRepository.findById(Long.valueOf(authentication.getName())).orElse(null);
        if (user == null) {
            return null;
        }
        String role = user.getRole();
        if (!ADMIN_ROLE.equalsIgnoreCase(role) && !STAFF_ROLE.equalsIgnoreCase(role)) {
            return null;
        }
        return user;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** 客户端 IP：优先取 nginx 反代写入的 X-Real-IP（每次覆写，不可被客户端伪造），回退 remoteAddr */
    private String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) return realIp.trim();
        return request.getRemoteAddr();
    }
}
