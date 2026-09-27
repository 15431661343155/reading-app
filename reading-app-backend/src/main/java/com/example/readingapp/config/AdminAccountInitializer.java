package com.example.readingapp.config;

import com.example.readingapp.entity.User;
import com.example.readingapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * 管理后台账号初始化。
 *
 * <p>应用启动时保证存在一个 role=ADMIN 的账号，用于首次登录管理后台：
 * <ul>
 *   <li>配置的用户名不存在 → 新建管理员账号（密码取自 admin.password，BCrypt 落库）</li>
 *   <li>配置的用户名已存在但不是管理员 → 提升为 ADMIN（不改动其密码）</li>
 * </ul>
 * 关闭该行为：admin.auto-init=false。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String DEFAULT_PASSWORD = "admin123";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.auto-init:true}")
    private boolean autoInit;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (!autoInit) {
            log.info("管理后台账号自动初始化已关闭（admin.auto-init=false）");
            return;
        }

        String username = adminUsername == null ? "" : adminUsername.trim();
        if (username.isEmpty()) {
            log.warn("admin.username 未配置，跳过管理后台账号初始化");
            return;
        }

        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            User user = existing.get();
            if (ADMIN_ROLE.equalsIgnoreCase(user.getRole())) {
                log.info("管理后台账号已就绪：{}", username);
            } else {
                user.setRole(ADMIN_ROLE);
                userRepository.save(user);
                log.info("已将已有账号 {} 提升为管理员（role=ADMIN）", username);
            }
        } else {
            User admin = new User();
            admin.setUserId(generateUserId());
            admin.setUsername(username);
            admin.setPassword(passwordEncoder.encode(
                    adminPassword == null || adminPassword.isEmpty() ? DEFAULT_PASSWORD : adminPassword));
            admin.setNickname("管理员");
            admin.setGender(0);
            admin.setStatus(1);
            admin.setRole(ADMIN_ROLE);
            userRepository.save(admin);
            log.info("已创建管理后台账号：{}（初始密码取自配置项 admin.password）", username);
        }

        if (adminPassword == null || adminPassword.isEmpty() || DEFAULT_PASSWORD.equals(adminPassword)) {
            log.warn("管理后台正在使用默认密码，请尽快修改 admin.password 配置项");
        }
    }

    /** App 端历史逻辑要求 userId 为 6 位数字且唯一 */
    private String generateUserId() {
        String userId;
        int attempt = 0;
        do {
            userId = String.valueOf(100000 + RANDOM.nextInt(900000));
            attempt++;
            if (attempt > 100) {
                throw new IllegalStateException("生成管理员 userId 失败");
            }
        } while (userRepository.existsByUserId(userId));
        return userId;
    }
}
