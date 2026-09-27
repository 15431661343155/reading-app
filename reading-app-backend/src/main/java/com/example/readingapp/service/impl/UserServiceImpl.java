package com.example.readingapp.service.impl;

import com.example.readingapp.dto.LoginRequest;
import com.example.readingapp.dto.LoginResponse;
import com.example.readingapp.dto.ProfileUpdateRequest;
import com.example.readingapp.dto.RegisterRequest;
import com.example.readingapp.entity.User;
import com.example.readingapp.repository.BookmarkRepository;
import com.example.readingapp.repository.BookshelfRepository;
import com.example.readingapp.repository.ExternalBookmarkRepository;
import com.example.readingapp.repository.ExternalBookshelfRepository;
import com.example.readingapp.repository.ExternalReadingRecordRepository;
import com.example.readingapp.repository.FeedbackRepository;
import com.example.readingapp.repository.MessageRepository;
import com.example.readingapp.repository.ReadTimeRecordRepository;
import com.example.readingapp.repository.ReadingProgressRepository;
import com.example.readingapp.repository.UserRepository;
import com.example.readingapp.service.UserService;
import com.example.readingapp.service.UserReadingStatService;
import com.example.readingapp.service.VerificationCodeService;
import com.example.readingapp.utils.JwtUtils;
import com.example.readingapp.utils.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final VerificationCodeService verificationCodeService;

    // 用户关联数据仓储：删除用户时需要一并清理，避免外键约束导致删除失败或残留孤儿数据
    private final BookshelfRepository bookshelfRepository;
    private final BookmarkRepository bookmarkRepository;
    private final ReadingProgressRepository readingProgressRepository;
    private final ReadTimeRecordRepository readTimeRecordRepository;
    private final ExternalBookshelfRepository externalBookshelfRepository;
    private final ExternalBookmarkRepository externalBookmarkRepository;
    private final ExternalReadingRecordRepository externalReadingRecordRepository;
    private final MessageRepository messageRepository;
    private final FeedbackRepository feedbackRepository;
    /** 注册时建阅读统计记录（起始连续天数 = 1），删除用户时一并清理。 */
    private final UserReadingStatService userReadingStatService;

    private static final SecureRandom secureRandom = new SecureRandom();

    private String generateUserId() {
        String userId;
        int attempt = 0;
        do {
            int num = 100000 + secureRandom.nextInt(900000);
            userId = String.valueOf(num);
            attempt++;
            if (attempt > 100) {
                throw new RuntimeException("生成 userId 失败，请稍后重试");
            }
        } while (userRepository.existsByUserId(userId));
        return userId;
    }

    /** 用户名长度范围 */
    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 50;
    /** 新注册账号的用户名规则：3~20 个字符，以字母开头，仅允许字母/数字/下划线。 */
    private static final int REGISTER_USERNAME_MAX = 20;
    private static final Pattern REGISTER_USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2," + (REGISTER_USERNAME_MAX - 1) + "}$");

    /** 邮箱验证码用途标识，必须与 AuthController 发送验证码时的用途一致。 */
    private static final String AUTH_EMAIL_CODE_TYPE = "auth_email";

    /** 邮箱格式校验（宽松但足以拦截明显非法输入）。 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    @Override
    public User register(RegisterRequest request) {
        // 服务端参数校验（不能只依赖客户端；此前空用户名/空密码可直接注册成功）
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();

        if (username.isEmpty()) {
            throw new RuntimeException("用户名不能为空");
        }
        if (password.isEmpty()) {
            throw new RuntimeException("密码不能为空");
        }
        if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            throw new RuntimeException("用户名长度需为 " + USERNAME_MIN + "~" + USERNAME_MAX + " 个字符");
        }
        String passwordError = PasswordPolicy.validate(password);
        if (passwordError != null) {
            throw new RuntimeException(passwordError);
        }

        // 检查用户名是否已存在
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }

        // 检查邮箱是否已存在
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("邮箱已被注册");
        }

        // 生成 6 位随机 userId
        String generatedUserId = generateUserId();

        // 创建用户
        User user = new User();
        user.setUserId(generatedUserId);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(request.getNickname() != null && !request.getNickname().isBlank()
                ? request.getNickname() : username);
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setGender(0);
        user.setStatus(1);

        User saved = userRepository.save(user);
        // 建阅读统计记录：首次注册登录的当天即算连续阅读第 1 天
        userReadingStatService.initialize(saved.getId(), 1);
        return saved;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        // account 优先，为空回退兼容旧字段 username
        String account = request.getAccount();
        if (account == null || account.trim().isEmpty()) {
            account = request.getUsername();
        }
        if (account == null || account.trim().isEmpty()) {
            throw new RuntimeException("请输入账号");
        }
        account = account.trim();

        // 含 "@" 走邮箱登录，否则按用户名登录（老账号用原用户名+原密码仍可登录）
        User user = account.contains("@")
                ? userRepository.findByEmail(account).orElseThrow(() -> new RuntimeException("账号或密码错误"))
                : userRepository.findByUsername(account).orElseThrow(() -> new RuntimeException("账号或密码错误"));

        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("账号或密码错误");
        }

        // 检查用户状态
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new RuntimeException("账号已被禁用");
        }

        return buildLoginResponse(user);
    }

    @Override
    @Transactional
    public LoginResponse loginByEmailCode(String email, String code) {
        String normalizedEmail = email == null ? "" : email.trim();
        if (normalizedEmail.isEmpty()) {
            throw new RuntimeException("请输入邮箱");
        }
        if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new RuntimeException("邮箱格式不正确");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new RuntimeException("请输入验证码");
        }

        // 验证码一次性校验（成功后即消费）
        if (!verificationCodeService.verifyEmailCode(normalizedEmail, code.trim(), AUTH_EMAIL_CODE_TYPE)) {
            throw new RuntimeException("验证码无效或已过期");
        }

        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        boolean createdNewUser = false;
        if (user == null) {
            // 未注册邮箱首次验证码登录：自动创建账号
            user = createAutoRegisteredUser(normalizedEmail);
            createdNewUser = true;
        }

        LoginResponse response = buildLoginResponse(user);
        response.setNewUser(createdNewUser);
        return response;
    }

    @Override
    @Transactional
    public LoginResponse registerByEmail(String email, String code, String username, String password) {
        String normalizedEmail = email == null ? "" : email.trim();
        String trimmedUsername = username == null ? "" : username.trim();
        String rawPassword = password == null ? "" : password;

        // 1. 先做参数与唯一性校验（顺序很重要：避免用户名冲突白白消耗一次验证码）
        if (normalizedEmail.isEmpty()) {
            throw new RuntimeException("请输入邮箱");
        }
        if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new RuntimeException("邮箱格式不正确");
        }
        if (trimmedUsername.isEmpty()) {
            throw new RuntimeException("请输入用户名");
        }
        if (!REGISTER_USERNAME_PATTERN.matcher(trimmedUsername).matches()) {
            throw new RuntimeException("用户名需为 " + USERNAME_MIN + "~" + REGISTER_USERNAME_MAX
                    + " 个字符，仅含字母/数字/下划线，且以字母开头");
        }
        String passwordError = PasswordPolicy.validate(rawPassword);
        if (passwordError != null) {
            throw new RuntimeException(passwordError);
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new RuntimeException("邮箱已被注册");
        }
        if (userRepository.existsByUsername(trimmedUsername)) {
            throw new RuntimeException("用户名已存在");
        }

        // 2. 参数全部通过后再校验并消费验证码
        if (code == null || code.trim().isEmpty()) {
            throw new RuntimeException("请输入验证码");
        }
        if (!verificationCodeService.verifyEmailCode(normalizedEmail, code.trim(), AUTH_EMAIL_CODE_TYPE)) {
            throw new RuntimeException("验证码无效或已过期");
        }

        // 3. 创建账号
        User user = new User();
        user.setUserId(generateUserId());
        user.setUsername(trimmedUsername);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(trimmedUsername);
        user.setEmail(normalizedEmail);
        user.setGender(0);
        user.setStatus(1);
        user = userRepository.save(user);

        // 建阅读统计记录：首次注册登录的当天即算连续阅读第 1 天
        userReadingStatService.initialize(user.getId(), 1);

        LoginResponse response = buildLoginResponse(user);
        response.setNewUser(true);
        return response;
    }

    @Override
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }

    @Override
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }

    @Override
    public User updateUser(Long id, User user) {
        User existingUser = findById(id);
        // 只覆盖前端实际提交的非空字段，避免请求体缺字段时把已有值清空。
        // username / role / status / password / userId 不在本接口处理，保持原值。
        if (user.getNickname() != null) {
            existingUser.setNickname(user.getNickname().trim());
        }
        if (user.getAvatar() != null) {
            existingUser.setAvatar(user.getAvatar());
        }
        if (user.getEmail() != null) {
            String email = user.getEmail().trim();
            if (!email.equals(existingUser.getEmail())) {
                if (email.isEmpty()) {
                    throw new RuntimeException("邮箱不能为空");
                }
                if (userRepository.existsByEmail(email)) {
                    throw new RuntimeException("邮箱已被其他用户使用");
                }
            }
            existingUser.setEmail(email);
        }
        if (user.getPhone() != null) {
            String phone = user.getPhone().trim();
            if (phone.isEmpty()) {
                existingUser.setPhone(null);
            } else {
                // 手机号与邮箱一样有唯一约束：仅在确实变更时才校验是否被他人占用，
                // 避免把自己原有的手机号当成冲突。
                if (!phone.equals(existingUser.getPhone()) && userRepository.existsByPhone(phone)) {
                    throw new RuntimeException("手机号已被其他用户使用");
                }
                existingUser.setPhone(phone);
            }
        }
        if (user.getGender() != null) {
            existingUser.setGender(user.getGender());
        }
        return userRepository.save(existingUser);
    }

    /** 昵称长度上限，与 User.nickname 列长度保持一致 */
    private static final int NICKNAME_MAX = 50;

    @Override
    @Transactional
    public User updateProfile(Long userId, ProfileUpdateRequest request) {
        User user = findById(userId);
        if (request == null) {
            throw new RuntimeException("请求内容为空");
        }

        if (request.getNickname() != null) {
            String nickname = request.getNickname().trim();
            if (nickname.length() > NICKNAME_MAX) {
                throw new RuntimeException("昵称最长 " + NICKNAME_MAX + " 个字符");
            }
            // 允许留空：展示时前端会回退到 username
            user.setNickname(nickname);
        }

        if (request.getGender() != null) {
            int gender = request.getGender();
            if (gender < 0 || gender > 2) {
                throw new RuntimeException("性别取值不合法");
            }
            user.setGender(gender);
        }

        if (request.getAvatar() != null) {
            String avatar = request.getAvatar().trim();
            if (avatar.isEmpty()) {
                // 空串 = 恢复默认头像
                user.setAvatar(null);
            } else if (isServerReachableAvatar(avatar)) {
                user.setAvatar(avatar);
            } else {
                // 关键防线：App 端头像选择后拿到的是手机本地路径（/data/user/0/... 或 file://...），
                // 直接落库等于存了一条谁也打不开的地址，必须由上传接口先转成 /avatars/** 再提交。
                throw new RuntimeException("头像地址不合法，请先上传头像");
            }
        }

        return userRepository.save(user);
    }

    /** 头像地址是否为本服务可对外提供的地址（上传后的相对路径，或完整 http(s) 外链） */
    private boolean isServerReachableAvatar(String avatar) {
        return avatar.startsWith("/avatars/")
                || avatar.startsWith("http://")
                || avatar.startsWith("https://");
    }

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = findById(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("原密码错误");
        }
        // 新密码强度校验（与注册/邮箱改密共用同一策略）
        String passwordError = PasswordPolicy.validate(newPassword);
        if (passwordError != null) {
            throw new RuntimeException(passwordError);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    @Override
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    /**
     * 删除用户并级联清理其全部关联数据。
     *
     * <p>bookshelf / reading_progress 通过 @ManyToOne 建立了到 user 的外键，
     * 不先清理会导致删除用户直接抛外键约束异常（管理后台报 500，
     * 表现为「用户删不掉」）；其余表虽无外键，但同属用户私有数据，一并清理避免孤儿行。
     * 全程包在一个事务内，任一步失败即整体回滚，不会出现「数据删了一半」的中间态。
     */
    @Override
    @Transactional
    public void deleteUserCascade(Long id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("用户不存在");
        }
        bookshelfRepository.deleteByUserId(id);
        bookmarkRepository.deleteByUserId(id);
        readingProgressRepository.deleteByUserId(id);
        readTimeRecordRepository.deleteByUserId(id);
        externalBookshelfRepository.deleteByUserId(id);
        externalBookmarkRepository.deleteByUserId(id);
        externalReadingRecordRepository.deleteByUserId(id);
        messageRepository.deleteByUserId(id);
        feedbackRepository.deleteByUserId(id);
        userReadingStatService.deleteByUserId(id);
        userRepository.deleteById(id);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 统一构建登录响应：为老用户补全 userId（历史数据迁移）、生成 JWT Token 并填充用户信息。
     * 供 {@link #login} / {@link #loginByEmailCode} / {@link #registerByEmail} 共用，避免四处重复。
     */
    private LoginResponse buildLoginResponse(User user) {
        // 为老用户补全 userId（历史数据迁移）
        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            user.setUserId(generateUserId());
            user = userRepository.save(user);
        }

        // 生成 JWT Token（使用数据库主键 userId 作为标识）
        String token = jwtUtils.generateToken(user.getUsername(), user.getId());

        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setId(user.getId());
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setNewUser(false);
        return response;
    }

    /**
     * 未注册邮箱首次验证码登录时自动创建账号。
     * 密码写入不可猜的随机占位值（用户后续可通过邮箱验证码重置密码）。
     */
    private User createAutoRegisteredUser(String email) {
        String derivedUsername = deriveUsername(email);
        String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        String nickname = localPart.length() > 50 ? localPart.substring(0, 50) : localPart;
        if (nickname.isEmpty()) {
            nickname = derivedUsername;
        }

        User user = new User();
        user.setUserId(generateUserId());
        user.setUsername(derivedUsername);
        // 占位密码：随机 UUID 编码，任何人无法凭此登录；真实密码需由用户后续设置
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setNickname(nickname);
        user.setEmail(email);
        user.setGender(0);
        user.setStatus(1);
        User saved = userRepository.save(user);
        // 建阅读统计记录：首次注册登录的当天即算连续阅读第 1 天
        userReadingStatService.initialize(saved.getId(), 1);
        return saved;
    }

    /**
     * 由邮箱派生一个符合规则且全局唯一的用户名：
     * 取 @ 前部分 → 非 [A-Za-z0-9_] 字符替换为 "_" → 不以字母开头则前缀补 "u"
     * → 补足到 ≥3、截断到 ≤20 → 冲突时追加 "_" + 4 位随机数字（仍保证 ≤20）。
     */
    private String deriveUsername(String email) {
        String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;

        StringBuilder sb = new StringBuilder(localPart.length());
        for (int i = 0; i < localPart.length(); i++) {
            char c = localPart.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            sb.append(allowed ? c : '_');
        }
        String base = sb.toString();

        // 必须以字母开头
        if (base.isEmpty() || !isAsciiLetter(base.charAt(0))) {
            base = "u" + base;
        }
        // 补足到最少 USERNAME_MIN 个字符
        while (base.length() < USERNAME_MIN) {
            base = base + (char) ('0' + secureRandom.nextInt(10));
        }
        // 截断到最多 REGISTER_USERNAME_MAX 个字符
        if (base.length() > REGISTER_USERNAME_MAX) {
            base = base.substring(0, REGISTER_USERNAME_MAX);
        }

        // 唯一性：冲突时追加 "_" + 4 位随机数字，保证追加后仍 ≤ 20
        String candidate = base;
        int attempt = 0;
        while (userRepository.existsByUsername(candidate)) {
            attempt++;
            if (attempt > 100) {
                throw new RuntimeException("生成用户名失败，请稍后重试");
            }
            String prefix = base.length() > 15 ? base.substring(0, 15) : base;
            int suffix = 1000 + secureRandom.nextInt(9000);
            candidate = prefix + "_" + suffix;
        }
        return candidate;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
