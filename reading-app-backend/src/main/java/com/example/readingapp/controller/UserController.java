package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.User;
import com.example.readingapp.repository.UserRepository;
import com.example.readingapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理接口（管理后台使用）。
 *
 * <p>安全：整个 /api/users/** 已在 SecurityConfig 中收紧为 hasRole("ADMIN")，
 * 匿名或普通用户请求会被鉴权层直接拒绝（401/403），不再能读取用户列表或删除/禁用任意账号。
 * 响应体中的 password 由 User 实体的 @JsonProperty(WRITE_ONLY) 自动剔除。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final UserService userService;

    // 分页获取所有用户
    @GetMapping
    public ApiResponse<Page<User>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.success(userRepository.findAll(pageable));
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

    // 禁用/启用用户
    @PutMapping("/{id}/status")
    public ApiResponse<User> updateUserStatus(
            @PathVariable Long id,
            @RequestParam Integer status) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setStatus(status);
        return ApiResponse.success("更新成功", userRepository.save(user));
    }

    // 更新用户信息（昵称/邮箱/电话/性别）。前端只传可编辑字段；
    // username/role/status/password 不在本接口处理，避免越权修改。
    @PutMapping("/{id}")
    public ApiResponse<User> updateUser(
            @PathVariable Long id,
            @RequestBody User user) {
        return ApiResponse.success("更新成功", userService.updateUser(id, user));
    }

    // 删除用户（级联清理其全部关联数据，避免外键约束导致删除失败）
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        if (!userRepository.existsById(id)) {
            return ApiResponse.error("用户不存在");
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
}
