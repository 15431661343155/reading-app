package com.example.readingapp.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // 跨域：必须由 Security 过滤器链处理（而非仅靠 @CrossOrigin）。
                // @CrossOrigin 只作用于 Spring MVC 层，而 Security 的过滤器链跑在 DispatcherServlet
                // 之前 —— 浏览器的 OPTIONS 预检请求不属于下方任一 permitAll 规则，会落到
                // anyRequest().authenticated() 返回 401，且响应不带 Access-Control-Allow-Origin，
                // 浏览器直接判定 CORS 失败。表现为：匿名 GET 能过，但只要请求带上 Authorization
                // （Web 端登录后每个请求都带），全部接口 "Failed to fetch"。
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ===== 管理后台登录入口（必须先于 /admin/** 的管理员规则） =====
                        .requestMatchers("/admin/login").permitAll()
                        .requestMatchers("/api/admin/auth/**").permitAll()
                        // 后台页面脚本等静态资源
                        .requestMatchers("/js/**", "/css/**", "/images/**").permitAll()
                        // ===== 公开接口：注册/登录 + 只读静态资源 =====
                        // /api/auth/** 全部为匿名认证入口（注册、密码登录、邮箱验证码登录/注册、发码），
                        // 故整段放行；注意与 /api/admin/auth/** 前缀不重叠，互不影响。
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/fonts/**",
                                "/fonts/**",
                                "/api/app/**",
                                "/covers/**",
                                // 用户头像为公开展示资源（列表/后台/Web 都要能匿名加载图片本身），
                                // 上传与绑定归属仍由 /api/user/avatar 的登录态 + JWT 控制。
                                "/avatars/**",
                                "/api/feedback/submit",
                                "/api/user/bind/send-email-code",
                                "/api/user/bind/send-sms-code",
                                "/download",
                                "/app",
                                "/apk"
                        ).permitAll()
                        // ===== 阅读量埋点：匿名也要计数 =====
                        // App / Web 打开书籍详情时打点，未登录用户同样计入「点击量」。
                        // 必须排在下方 /api/books/** 的 ADMIN 规则之前，否则普通用户打点会 403，
                        // 表现为「热门榜永远全是 0 阅读」。该接口只做 +1，不读不写其他数据。
                        .requestMatchers(HttpMethod.POST, "/api/books/*/view").permitAll()
                        // ===== 书籍 / 章节：仅 GET 匿名只读 =====
                        // App 端只读（书架、详情、搜索、目录、正文），因此只放行 GET；
                        // 新增/修改/删除/批量删书等写操作由下方 /api/books/** 的 ADMIN 规则保护，
                        // 避免匿名用户直接删除全站书籍。
                        .requestMatchers(HttpMethod.GET, "/api/books/**", "/api/chapters/**").permitAll()
                        .requestMatchers("/api/books/**", "/api/chapters/**").hasRole("ADMIN")
                        // ===== App 端与后台共用的管理接口：维持原有的完全开放 =====
                        // 这几个接口被安卓端「书城/在线书源」直接调用，且安卓端没有 401 兜底逻辑
                        // （token 过期后不会跳登录页），一旦收紧为 authenticated / hasRole 会导致
                        // App 静默失败。故此处保持原状，如需收紧须同步修改安卓端。
                        .requestMatchers(
                                "/api/admin/online-source/**",
                                "/api/admin/book-source/import-url"
                        ).permitAll()
                        // ===== 分卷只读接口：App 端匿名读取，刻意限定 GET =====
                        // App 端阅读器 / 书籍详情「目录」需按书读取分卷名（后台导入 EPUB 时写入），
                        // 且安卓端没有 401 兜底逻辑（token 过期不会跳登录页，会静默失败），故放行匿名读取。
                        // 这里刻意用 HttpMethod.GET 限定方法：防止将来在 /book/ 下新增写接口被匿名放行，
                        // 也避免非 GET 请求绕过鉴权直接落到 dispatcher（会退化成 405/500 而非 401）。
                        // 写接口 POST /api/major-chapters 与 PUT/DELETE /api/major-chapters/{id}
                        // 不在 /book/ 前缀下，仍由下方 anyRequest().authenticated() + ADMIN 保护。
                        .requestMatchers(HttpMethod.GET, "/api/major-chapters/book/**").permitAll()
                        // ===== 用户数据（App 端 legacy 接口）：必须登录 =====
                        // 路径中的 userId 不再被信任，控制器内会与 JWT 中的当前用户做归属校验，
                        // 杜绝匿名/他人读取或写入他人书架、进度、书签。
                        .requestMatchers(
                                "/api/user/progress/**",
                                "/api/user/bookshelf/**",
                                "/api/user/bookmark/**",
                                "/api/user/readtime/**",
                                // 个人资料自助读写与头像上传：目标用户一律由 JWT 决定，
                                // 请求体里的 userId 不被信任（见 UserProfileController）。
                                "/api/user/profile",
                                "/api/user/avatar"
                        ).authenticated()
                        // ===== 反馈：仅匿名提交开放，列表/详情/回复/关闭/删除走管理后台 =====
                        // App 端只用 POST /api/feedback/submit；其余动作原先随 /api/feedback/** 一起
                        // 匿名放行，导致任何人都能读取全部用户反馈（含联系方式）并删除。
                        .requestMatchers("/api/feedback/**").hasRole("ADMIN")
                        // ===== 用户管理接口：仅管理员（原为匿名开放，可越权删除/禁用任意用户） =====
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        // ===== 封面写操作（管理后台使用）：仅管理员 =====
                        .requestMatchers("/api/cover/**").hasRole("ADMIN")
                        // ===== 其余管理接口：仅管理员 =====
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // ===== 管理后台页面：仅管理员（未登录时由入口点跳转登录页） =====
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/user/bind/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 跨域规则（Web 端 / 调试工具等浏览器客户端跨域调用后端时必需）。
     *
     * <p>刻意<b>不开启</b> {@code allowCredentials}：App 与 Web 端都用
     * {@code Authorization: Bearer <JWT>} 鉴权，不依赖 Cookie；而管理后台的
     * {@code ADMIN_TOKEN} 是 HttpOnly Cookie —— 一旦开启凭证 + 通配 Origin，
     * 任意站点都能借用管理员的 Cookie 调后台接口。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** 未登录：页面请求跳登录页，接口请求返回统一 JSON */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (HttpServletRequest request, HttpServletResponse response,
                org.springframework.security.core.AuthenticationException authException) -> {
            if (isAdminPageRequest(request)) {
                response.sendRedirect(request.getContextPath() + "/admin/login");
                return;
            }
            writeJson(response, HttpStatus.UNAUTHORIZED.value(),
                    "{\"code\":500,\"success\":false,\"message\":\"用户未登录，请先登录\",\"data\":null}");
        };
    }

    /** 已登录但权限不足（非管理员访问后台）：页面请求跳登录页，接口请求返回 403 JSON */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (HttpServletRequest request, HttpServletResponse response,
                org.springframework.security.access.AccessDeniedException accessDeniedException) -> {
            if (isAdminPageRequest(request)) {
                response.sendRedirect(request.getContextPath() + "/admin/login");
                return;
            }
            writeJson(response, HttpStatus.FORBIDDEN.value(),
                    "{\"code\":403,\"success\":false,\"message\":\"无管理员权限\",\"data\":null}");
        };
    }

    /** 是否为管理后台的 HTML 页面请求（区别于 /api/ 接口请求） */
    private boolean isAdminPageRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/admin") && !uri.startsWith("/api/");
    }

    private void writeJson(HttpServletResponse response, int status, String json) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setStatus(status);
        response.getWriter().write(json);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
