package com.example.readingapp.controller;

import com.example.readingapp.exception.BusinessException;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.ProfileUpdateRequest;
import com.example.readingapp.entity.User;
import com.example.readingapp.service.UserService;
import com.example.readingapp.utils.SecurityUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户自助个人资料（App「个人信息」页 / Web「个人中心」页）。
 *
 * <p>安全要点：
 * <ul>
 *   <li>目标用户一律取自 JWT（{@link SecurityUtils#getCurrentUserId()}），
 *       <b>请求体里不接受、也不信任 userId</b>，因此不存在改他人资料的可能；</li>
 *   <li>只暴露昵称 / 性别 / 头像三个展示类字段，邮箱手机号必须走 {@code /api/user/bind/**} 的验证码链路；</li>
 *   <li>两个接口都落在 {@code /api/user/**} 下，由 SecurityConfig 要求 {@code authenticated()}。</li>
 * </ul>
 *
 * <p>头像落盘方式与 {@link CoverController} 保持一致：存到本地 uploads/avatars/，
 * 通过 {@code /avatars/**} 静态资源映射对外提供，数据库里只存相对路径。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class UserProfileController {

    private final UserService userService;

    @Value("${file.upload.avatar-path:uploads/avatars/}")
    private String avatarPath;

    /** 头像允许的扩展名，与封面保持一致 */
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "gif", "webp");

    /** 头像文件大小上限：5MB */
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;

    @PostConstruct
    public void init() {
        File dir = new File(avatarPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /** 修改昵称 / 性别 / 头像（null 字段表示不修改） */
    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(@RequestBody ProfileUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error(401, "未登录，请先登录");
        }
        try {
            User updated = userService.updateProfile(userId, request);
            return ApiResponse.success("保存成功", toProfileMap(updated));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
            } catch (Exception e) {
            return ApiResponse.error("保存失败");
        }
    }

    /** 读取当前登录用户的资料（个人中心回填表单用；password 由 WRITE_ONLY 自动剔除） */
    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> getProfile() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error(401, "未登录，请先登录");
        }
        try {
            return ApiResponse.success(toProfileMap(userService.findById(userId)));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
            } catch (Exception e) {
            return ApiResponse.error("读取失败");
        }
    }

    /**
     * 注销当前登录账号：删除自己并级联清理全部私有数据（书架 / 进度 / 书签 / 阅读统计 / 外站数据等）。
     *
     * <p>安全要点与 {@code /profile} 一致：目标用户一律取自 JWT（{@link SecurityUtils#getCurrentUserId()}），
     * <b>不接受、也不信任请求体里的 userId</b>，因此不存在越权删除他人账号的可能。
     * 级联删除由 {@link com.example.readingapp.service.UserService#deleteUserCascade(Long)} 在一个事务内完成，
     * 任一步失败即整体回滚，不会出现「账号删了但数据残留」的中间态。
     */
    @DeleteMapping("/account")
    public ApiResponse<Void> cancelAccount() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error(401, "未登录，请先登录");
        }
        try {
            userService.deleteUserCascade(userId);
            return ApiResponse.success("注销成功", null);
        } catch (Exception e) {
            log.error("注销账号失败 userId={}: {}", userId, e.getMessage(), e);
            return ApiResponse.error("注销失败，请稍后重试");
        }
    }

    /** 上传头像，返回可直接使用的相对路径（形如 /avatars/xxx.png） */
    @PostMapping("/avatar")
    public ApiResponse<Map<String, Object>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error(401, "未登录，请先登录");
        }
        try {
            if (file == null || file.isEmpty()) {
                return ApiResponse.error("请选择要上传的图片");
            }
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !originalFilename.contains(".")) {
                return ApiResponse.error("无效的文件名");
            }
            String ext = getFileExtension(originalFilename).toLowerCase();
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                return ApiResponse.error("不支持的文件格式，支持的格式：jpg, jpeg, png, gif, webp");
            }
            if (file.getSize() > MAX_AVATAR_BYTES) {
                return ApiResponse.error("图片大小不能超过 5MB");
            }

            File dir = new File(avatarPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path target = Paths.get(avatarPath, fileName);
            try (java.io.InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            String relativeUrl = "/avatars/" + fileName;
            // 落库：走统一的资料更新逻辑，便于后续加校验/审计
            ProfileUpdateRequest patch = new ProfileUpdateRequest();
            patch.setAvatar(relativeUrl);
            userService.updateProfile(userId, patch);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("avatar", relativeUrl);
            return ApiResponse.success("上传成功", data);
        } catch (Exception e) {
            log.error("上传头像失败 userId={}: {}", userId, e.getMessage(), e);
            return ApiResponse.error("上传失败，请稍后重试");
        }
    }

    private String getFileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1);
    }

    /** 只回传前端需要的字段，避免把整行用户数据（含 role/status/时间戳）暴露给客户端 */
    private Map<String, Object> toProfileMap(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("userId", user.getUserId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("avatar", user.getAvatar());
        data.put("gender", user.getGender());
        data.put("email", user.getEmail());
        data.put("phone", user.getPhone());
        return data;
    }
}
