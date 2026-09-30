package com.example.readingapp.config;

import com.example.readingapp.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 管理后台登录凭证 Cookie（见 AdminAuthController） */
    public static final String ADMIN_COOKIE = "ADMIN_TOKEN";

    /** 管理员角色标识 */
    public static final String ROLE_ADMIN = "ADMIN";

    private final JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        final String jwt = resolveToken(request);

        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Long userId = jwtUtils.extractUserId(jwt);

            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // 带 ADMIN 角色声明的 token 授予 ROLE_ADMIN，其余一律 ROLE_USER
                String role = jwtUtils.extractRole(jwt);
                String authority = ROLE_ADMIN.equalsIgnoreCase(role) ? "ROLE_ADMIN" : "ROLE_USER";

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userId.toString(),
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority(authority))
                );
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }

            // ===== 滑动续期 =====
            // App/Web 走 Authorization 头的 token，只要本次请求校验通过且剩余有效期不足一半，
            // 就在响应头下发一枚全新有效期的 token（X-New-Token），客户端收到后无感替换。
            // 效果：活跃用户永远不会再「莫名过期」；连续 7 天完全不用 App 才会真正过期。
            // 只对 Authorization 头来源生效：管理后台的 ADMIN_TOKEN Cookie 不在此续期。
            if (userId != null && request.getHeader("Authorization") != null) {
                Date expiration = jwtUtils.extractExpiration(jwt);
                long remainingMs = expiration.getTime() - System.currentTimeMillis();
                if (remainingMs > 0 && remainingMs < jwtUtils.getExpirationMs() / 2) {
                    response.setHeader("X-New-Token", jwtUtils.generateToken(
                            jwtUtils.extractUsername(jwt), userId, jwtUtils.extractRole(jwt)));
                }
            }
        } catch (Exception e) {
            logger.error("JWT Authentication failed: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 依次从 Authorization 头、管理后台 Cookie 中解析 token。
     * App 端走 Authorization，管理后台浏览器页面走 Cookie（同源请求自动携带）。
     */
    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (ADMIN_COOKIE.equals(cookie.getName())) {
                    String token = cookie.getValue();
                    if (token != null && !token.isEmpty()) {
                        return token;
                    }
                }
            }
        }
        return null;
    }
}