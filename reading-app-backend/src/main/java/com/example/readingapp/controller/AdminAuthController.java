package com.example.readingapp.controller;

import com.example.readingapp.dto.AdminLoginRequest;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.User;
import com.example.readingapp.repository.UserRepository;
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

import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台登录：只有 role=ADMIN 的账号才能登录。
 *
 * <ul>
 *   <li>GET  /admin/login          登录页面由独立前端（reading-app-admin）静态托管，后端不再渲染；前端已登录则跳首页</li>
 *   <li>POST /api/admin/auth/login 校验账号密码 + 管理员角色，签发 JWT 并写入 HttpOnly Cookie</li>
 *   <li>POST /api/admin/auth/logout 清除登录 Cookie</li>
 *   <li>GET  /api/admin/auth/me    返回当前登录管理员（未登录返回 401）</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class AdminAuthController {

    /** 与 {@link com.example.readingapp.config.JwtAuthenticationFilter#ADMIN_COOKIE} 保持一致 */
    private static final String ADMIN_COOKIE = "ADMIN_TOKEN";
    private static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @Value("${jwt.expiration:86400000}")
    private Long jwtExpiration;

    // 登录页由独立前端（reading-app-admin）静态托管，后端仅提供 /api/admin/auth/*

    /** 登录接口 */
    @PostMapping("/api/admin/auth/login")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @RequestBody(required = false) AdminLoginRequest request,
            HttpServletResponse response) {

        String username = request == null ? null : trim(request.getUsername());
        String password = request == null ? null : request.getPassword();

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("请输入账号和密码"));
        }

        // 统一用「账号或密码错误」表述，不区分账号不存在 / 密码错误 / 无管理员权限，避免账号枚举
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.ok(ApiResponse.error("账号或密码错误"));
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return ResponseEntity.ok(ApiResponse.error("该账号已被禁用"));
        }
        if (!ADMIN_ROLE.equalsIgnoreCase(user.getRole())) {
            return ResponseEntity.ok(ApiResponse.error("该账号没有管理员权限"));
        }

        String token = jwtUtils.generateToken(user.getUsername(), user.getId(), ADMIN_ROLE);

        ResponseCookie cookie = ResponseCookie.from(ADMIN_COOKIE, token)
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofMillis(jwtExpiration))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", ADMIN_ROLE);

        return ResponseEntity.ok(ApiResponse.success("登录成功", data));
    }

    /** 退出登录 */
    @PostMapping("/api/admin/auth/logout")
    @ResponseBody
    public ApiResponse<Void> logout(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(ADMIN_COOKIE, "")
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ApiResponse.success("已退出登录", null);
    }

    /** 当前登录管理员信息 */
    @GetMapping("/api/admin/auth/me")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未登录或登录已过期"));
        }

        User user = userRepository.findById(Long.valueOf(authentication.getName())).orElse(null);
        if (user == null || !ADMIN_ROLE.equalsIgnoreCase(user.getRole())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未登录或登录已过期"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", ADMIN_ROLE);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
