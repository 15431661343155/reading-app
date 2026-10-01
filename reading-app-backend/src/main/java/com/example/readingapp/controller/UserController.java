package com.example.readingapp.controller;

import com.example.readingapp.exception.BusinessException;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.InternalUserRequest;
import com.example.readingapp.dto.PasswordUpdateRequest;
import com.example.readingapp.dto.RoleUpdateRequest;
import com.example.readingapp.entity.User;
import com.example.readingapp.repository.UserRepository;
import com.example.readingapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理接口（管理后台使用）。
 *
 * <p>安全：整个 /api/users/** 已在 SecurityConfig 中收紧为 hasRole("ADMIN")，
 * 匿名或普通用户请求会被鉴权层直接拒绝（401/403），不再能读取用户列表或删除/禁用任意账号。
 * 响应体中的 password 由 User 实体的 @JsonProperty(WRITE_ONLY) 自动剔除。
 *
 * <p>角色过滤：GET /api/users?role=USER 返回普通用户（role 为 NULL 的历史数据视为 USER）；
 * GET /api/users?role=ADMIN,STAFF 返回内部人员（管理员 + 内部人员）；
 * 不带 role 参数时保持原有全量行为。创建内部账号 / 改密码 / 改角色也走本控制器。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final UserService userService;

    // 分页获取用户列表（支持按角色过滤）
    @GetMapping
    public ApiResponse<Page<User>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String role) {
        Pageable pageable = PageRequest.of(page, size);
        Page<User> result;
        if (role == null || role.isBlank()) {
            // 未指定角色：保持原有全量行为（兼容旧调用方）
            result = userRepository.findAll(pageable);
        } else if (role.contains(",")) {
            // 多角色过滤（如 ADMIN,STAFF → 内部人员列表）
            Set<String> roles = Arrays.stream(role.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toUpperCase)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            result = userRepository.findByRoleInIgnoreCase(roles, pageable);
        } else {
            // 单角色过滤；普通用户 = role 为 NULL 的历史数据 + role=USER
            result = userRepository.findByRoleOrDefault(role.trim(), pageable);
        }
        return ApiResponse.success(result);
    }

    // 获取用户总数
    @GetMapping("/count")
    public ApiResponse<Long> getUserCount() {
        return ApiResponse.success(userRepository.count());
    }

    // 获取单个用户（编辑时回填表单；password 由 WRITE_ONLY 自动剔除，不会回显哈希）
    @GetMapping("/{id}")
    public ApiResponse<User> getUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("用户不存在"));
    }

    // 禁用/启用用户（防自禁 + 最后一个管理员保护）
    @PutMapping("/{id}/status")
    public ApiResponse<User> updateUserStatus(
            @PathVariable Long id,
            @RequestParam Integer status,
            Authentication authentication) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (status != null && status == 0) {
            String guardError = internalAccountGuard(user, authentication, "禁用");
            if (guardError != null) {
                return ApiResponse.error(guardError);
            }
        }
        user.setStatus(status);
        return ApiResponse.success("更新成功", userRepository.save(user));
    }

    // 创建内部账号（管理员 ADMIN / 内部人员 STAFF；普通用户由 App 端注册产生）
    @PostMapping
    public ApiResponse<User> createInternalUser(@RequestBody InternalUserRequest request) {
        try {
            User created = userService.createInternalUser(
                    request.getUsername(),
                    request.getPassword(),
                    request.getNickname(),
                    request.getRole());
            return ApiResponse.success("创建成功", created);
        } catch (Exception e) {
            log.warn("创建内部账号失败: {}", e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    // 重置用户密码（管理员强制改密，无需旧密码；新密码走统一强度策略）
    @PutMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(
            @PathVariable Long id,
            @RequestBody PasswordUpdateRequest request) {
        try {
            userService.resetPasswordByAdmin(id, request.getNewPassword());
            return ApiResponse.success("密码修改成功", null);
        } catch (Exception e) {
            log.warn("重置密码失败 id={}: {}", id, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    // 恢复默认密码（12345678）
    @PutMapping("/{id}/password/reset-default")
    public ApiResponse<Void> resetPasswordToDefault(@PathVariable Long id) {
        try {
            userService.resetPasswordToDefault(id);
            return ApiResponse.success("已恢复默认密码：" + UserService.DEFAULT_RESET_PASSWORD, null);
        } catch (Exception e) {
            log.warn("恢复默认密码失败 id={}: {}", id, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    // 调整内部人员角色（ADMIN / STAFF 互转；不允许操作自己 + 最后一个管理员保护）
    @PutMapping("/{id}/role")
    public ApiResponse<User> updateRole(
            @PathVariable Long id,
            @RequestBody RoleUpdateRequest request,
            Authentication authentication) {
        try {
            return ApiResponse.success("更新成功",
                    userService.updateRoleByAdmin(id, request.getRole(), currentOperatorId(authentication)));
        } catch (Exception e) {
            log.warn("调整角色失败 id={}: {}", id, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    // 更新用户信息（昵称/邮箱/电话/性别）。前端只传可编辑字段；
    // username/role/status/password 不在本接口处理，避免越权修改。
    @PutMapping("/{id}")
    public ApiResponse<User> updateUser(
            @PathVariable Long id,
            @RequestBody User user) {
        return ApiResponse.success("更新成功", userService.updateUser(id, user));
    }

    // 删除用户（级联清理其全部关联数据，避免外键约束导致删除失败；防自删 + 最后一个管理员保护）
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id, Authentication authentication) {
        if (!userRepository.existsById(id)) {
            return ApiResponse.error("用户不存在");
        }
        User user = userRepository.findById(id).orElse(null);
        if (user != null) {
            String guardError = internalAccountGuard(user, authentication, "删除");
            if (guardError != null) {
                return ApiResponse.error(guardError);
            }
        }
        try {
            userService.deleteUserCascade(id);
            return ApiResponse.success("删除成功", null);
        } catch (Exception e) {
            // 不让异常落到全局兜底（只会返回无细节的「服务器内部错误」），
            // 而是把真实原因回传给前端，便于定位删除失败的根因。
            log.error("删除用户失败 id={}: {}", id, e.getMessage(), e);
            return ApiResponse.error("删除失败：" + e.getMessage());
        }
    }

    // ==================== 内部辅助方法 ====================

    /** 内部账号危险操作（禁用/删除）防护：返回 null 表示放行，否则返回给前端的错误文案 */
    private String internalAccountGuard(User target, Authentication authentication, String action) {
        Long operatorId = currentOperatorId(authentication);
        if (operatorId != null && operatorId.equals(target.getId())) {
            return "不能" + action + "当前登录的账号";
        }
        String role = target.getRole() == null ? "" : target.getRole().trim().toUpperCase();
        if ("ADMIN".equals(role) && userRepository.countByRoleIgnoreCase("ADMIN") <= 1) {
            return "系统至少需要保留一个管理员账号";
        }
        return null;
    }

    /** 当前操作者主键（JWT subject = user 表主键）；解析失败返回 null（不阻断） */
    private Long currentOperatorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
