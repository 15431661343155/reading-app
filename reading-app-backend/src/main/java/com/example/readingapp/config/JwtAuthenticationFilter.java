package com.example.readingapp.config;

import com.example.readingapp.entity.User;
import com.example.readingapp.repository.UserRepository;
import com.example.readingapp.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 管理后台登录凭证 Cookie（见 AdminAuthController） */
    public static final String ADMIN_COOKIE = "ADMIN_TOKEN";

    /** 管理员角色标识 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 内部人员角色标识（可登录管理后台，权限受限，见 SecurityConfig） */
    public static final String ROLE_STAFF = "STAFF";

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

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

            // ===== 查库校验（安全加固 2026-10-01）=====
            // 旧实现只验 JWT 签名不查库，存在三个漏洞：
            //   ① 用户被管理员封禁后，已签发 token 仍可继续使用最长 7 天；
            //   ② ADMIN 被降级为 STAFF/USER 后，旧 token 仍持有 ADMIN 权限；
            //   ③ 密码被重置后旧 token 不失效（无法夺回被盗账号）。
            // 现改为每请求查库确认「账号存在 + 未封禁 + 角色以库为准」，
            // 配合 60 秒内存缓存控制 DB 开销（封禁/降权生效延迟 ≤ 60 秒）。
            UserSnapshot snap = userId == null ? null : userSnapshot(userId);

            if (snap != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // 角色一律以数据库当前值为准，不信任 token 中的角色声明
                String role = snap.role == null ? "" : snap.role;
                String authority;
                if (ROLE_ADMIN.equalsIgnoreCase(role)) {
                    authority = "ROLE_ADMIN";
                } else if (ROLE_STAFF.equalsIgnoreCase(role)) {
                    authority = "ROLE_STAFF";
                } else {
                    authority = "ROLE_USER";
                }

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
            // 账号已封禁/被删（snap == null）时不再续期。
            if (snap != null && request.getHeader("Authorization") != null) {
                Date expiration = jwtUtils.extractExpiration(jwt);
                long remainingMs = expiration.getTime() - System.currentTimeMillis();
                if (remainingMs > 0 && remainingMs < jwtUtils.getExpirationMs() / 2) {
                    response.setHeader("X-New-Token", jwtUtils.generateToken(
                            jwtUtils.extractUsername(jwt), userId, snap.role));
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
     */    private String resolveToken(HttpServletRequest request) {
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

    // ==================== 用户状态/角色查库缓存（60 秒） ====================

    /** 缓存有效期：封禁/降权/改密的生效延迟上限（可配置，默认 60 秒） */
    @Value("${security.user-snapshot-ttl-ms:60000}")
    private long snapshotTtlMs;
    /** 缓存容量上限，超过则整体清空（防内存无限增长；极端情况只是多查几次库） */
    private static final int SNAPSHOT_CACHE_MAX = 20_000;

    private final ConcurrentHashMap<Long, UserSnapshot> snapshotCache = new ConcurrentHashMap<>();

    /** 用户当前状态/角色快照（60 秒内复用，避免每请求都查库） */
    private static class UserSnapshot {
        final String role;
        final boolean banned;
        final long loadedAt;

        UserSnapshot(String role, boolean banned, long loadedAt) {
            this.role = role;
            this.banned = banned;
            this.loadedAt = loadedAt;
        }
    }

    /**
     * 取用户最新状态/角色（优先走缓存）。用户不存在或已封禁返回 null。
     * status 约定：1=正常，0=禁用（见 User 实体）。
     */
    private UserSnapshot userSnapshot(Long userId) {
        long now = System.currentTimeMillis();
        UserSnapshot snap = snapshotCache.get(userId);
        if (snap == null || now - snap.loadedAt >= snapshotTtlMs) {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                snapshotCache.remove(userId);
                return null;
            }
            boolean banned = user.getStatus() == null || user.getStatus() != 1;
            snap = new UserSnapshot(user.getRole(), banned, now);
            if (snapshotCache.size() >= SNAPSHOT_CACHE_MAX) {
                snapshotCache.clear();
            }
            snapshotCache.put(userId, snap);
        }
        return snap.banned ? null : snap;
    }
}