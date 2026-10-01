package com.example.readingapp.config;

import com.example.readingapp.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 安全加固过滤器（注册在 SecurityConfig 的 JWT 过滤器之前），职责两项：
 *
 * <h3>1. 匿名易滥用接口按 IP 限流（429）</h3>
 * 规则表见 {@link #RULES}：书源接口（防被当免费代理刷）、验证码校验（防穷举辅助）、
 * 注册（防批量垃圾账号）、反馈（防垃圾信息刷量）。App 端正常使用频率远低于阈值。
 *
 * <h3>2. 后台 Cookie 请求强制校验 X-Requested-With 头（CSRF 纵深防御）</h3>
 * 管理后台以 HttpOnly Cookie（ADMIN_TOKEN）鉴权，Spring CSRF 防护是关闭的。跨站攻击
 * （如表单自动提交 POST）能自动携带 Cookie，但<b>无法自定义请求头</b>（自定义头会触发
 * CORS 预检且我们不开 credentials）。因此：凡是「带 ADMIN_TOKEN Cookie 且未带
 * Authorization 头」的 /api/admin/** 请求，必须携带 X-Requested-With: XMLHttpRequest。
 * <ul>
 *   <li>后台页面：admin-auth.js 统一包装 fetch，自动附加该头。</li>
 *   <li>App 端：走 Authorization: Bearer，不带 Cookie，不受影响。</li>
 *   <li>匿名书源端点（online-source / import-url）：App 无 Cookie 无自定义头，
 *       显式豁免，仅受第 1 项限流约束。</li>
 * </ul>
 */
@RequiredArgsConstructor
public class SecurityHardeningFilter extends OncePerRequestFilter {

    private static final String ADMIN_COOKIE = JwtAuthenticationFilter.ADMIN_COOKIE;
    private static final String REQUIRED_HEADER = "X-Requested-With";
    private static final String REQUIRED_VALUE = "XMLHttpRequest";

    /** 限流规则：命中的路径前缀 → 按桶名 + IP 限频 */
    private record Rule(List<String> pathPrefixes, String bucket, int maxReq, long windowMs) {}

    private static final long MIN = 60_000L;

    /**
     * 限流规则表（均为匿名或低门槛接口，防滥用；App 正常使用远低于阈值）：
     * <ul>
     *   <li>source：书源接口 60 次/分（App 书城依赖，阈值放宽）</li>
     *   <li>code-verify：验证码校验 10 次/分（配合 VerificationCodeService 失败作废防穷举）</li>
     *   <li>register：注册 5 次/时（防批量垃圾账号）</li>
     *   <li>feedback：反馈提交 3 次/10 分（防垃圾信息刷量）</li>
     * </ul>
     */
    private static final List<Rule> RULES = List.of(
            new Rule(List.of("/api/admin/online-source/", "/api/admin/book-source/import-url"),
                    "source", 60, MIN),
            new Rule(List.of("/api/auth/login-by-code", "/api/auth/register-by-email"),
                    "code-verify", 10, MIN),
            new Rule(List.of("/api/auth/register", "/api/auth/register-by-email"),
                    "register", 5, 60 * MIN),
            new Rule(List.of("/api/feedback/submit"),
                    "feedback", 3, 10 * MIN)
    );

    private final RateLimitService rateLimitService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        // ---------- 1. 匿名易滥用接口限流 ----------
        if (uri.startsWith("/api/")) {
            String ip = clientIp(request);
            for (Rule rule : RULES) {
                for (String prefix : rule.pathPrefixes()) {
                    boolean hit = uri.startsWith(prefix) || uri.equals(prefix);
                    if (hit && !rateLimitService.allow(rule.bucket(), ip, rule.maxReq(), rule.windowMs())) {
                        writeJson(response, 429,
                                "{\"code\":429,\"success\":false,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
                        return;
                    }
                }
            }
        }

        // ---------- 2. Cookie 化的后台 API 请求：强制 X-Requested-With ----------
        // 条件：/api/admin/** + 请求带 ADMIN_TOKEN Cookie（浏览器同源自动携带）+
        //       未带 Authorization 头（App 走 Bearer，无 Cookie，天然不命中此分支）。
        // 豁免 /api/admin/auth/**（登录/登出/me）：登录页 login.html 不经过 admin-auth.js
        // 包装、不会带该头；且若浏览器残留旧 Cookie，豁免可避免「无法重新登录」的死锁。
        // 这三个接口只建立/清除/查询登录态本身，即使被 CSRF 也无破坏性写操作。
        if (uri.startsWith("/api/admin/") && !uri.startsWith("/api/admin/auth/")
                && !hasAuthorizationHeader(request) && hasAdminCookie(request)) {
            String value = request.getHeader(REQUIRED_HEADER);
            if (!REQUIRED_VALUE.equals(value)) {
                writeJson(response, 403,
                        "{\"code\":403,\"success\":false,\"message\":\"缺少必要的请求头校验\",\"data\":null}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean hasAuthorizationHeader(HttpServletRequest request) {
        return request.getHeader("Authorization") != null;
    }

    private boolean hasAdminCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie cookie : cookies) {
            if (ADMIN_COOKIE.equals(cookie.getName())) return true;
        }
        return false;
    }

    /** 客户端 IP：优先取 nginx 反代写入的 X-Real-IP（nginx 每次覆写，不可被客户端伪造），回退 remoteAddr */
    private String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) return realIp.trim();
        return request.getRemoteAddr();
    }

    private void writeJson(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json);
    }
}
