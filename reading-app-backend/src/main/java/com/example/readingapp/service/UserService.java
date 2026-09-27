package com.example.readingapp.service;

import com.example.readingapp.dto.LoginRequest;
import com.example.readingapp.dto.LoginResponse;
import com.example.readingapp.dto.ProfileUpdateRequest;
import com.example.readingapp.dto.RegisterRequest;
import com.example.readingapp.entity.User;

public interface UserService {

    // 用户注册
    User register(RegisterRequest request);

    // 用户登录
    LoginResponse login(LoginRequest request);

    /**
     * 邮箱验证码登录：未注册的邮箱首次登录会自动注册并返回可用的登录会话。
     *
     * @param email 邮箱
     * @param code  邮箱验证码（用途 auth_email）
     * @return 登录响应；若为自动注册，{@code newUser} 为 true
     */
    LoginResponse loginByEmailCode(String email, String code);

    /**
     * 邮箱注册：校验邮箱/用户名/密码规则、唯一性与验证码后创建账号并直接返回登录会话。
     *
     * @param email    邮箱
     * @param code     邮箱验证码（用途 auth_email）
     * @param username 用户名（3~20，字母开头，仅字母/数字/下划线，全局唯一）
     * @param password 密码（6~64）
     * @return 登录响应，{@code newUser} 为 true
     */
    LoginResponse registerByEmail(String email, String code, String username, String password);

    // 根据用户名查找用户
    User findByUsername(String username);

    // 根据ID查找用户
    User findById(Long id);

    // 更新用户信息
    User updateUser(Long id, User user);

    /**
     * 用户自助修改个人资料（昵称 / 性别 / 头像）。
     *
     * <p>与管理后台的 {@link #updateUser(Long, User)} 分开：本方法只允许改展示类字段，
     * 邮箱 / 手机号 / 用户名 / 角色 / 状态 / 密码一概不在此处理。
     *
     * @param userId  当前登录用户主键（由控制器从 JWT 取，不接受请求体传入）
     * @param request 待修改字段；{@code null} 字段表示不修改
     * @return 更新后的用户
     */
    User updateProfile(Long userId, ProfileUpdateRequest request);

    // 修改密码
    void changePassword(Long userId, String oldPassword, String newPassword);

    // 检查用户名是否存在
    boolean existsByUsername(String username);

    // 检查邮箱是否存在
    boolean existsByEmail(String email);

    // 删除用户，并级联清理其全部关联数据（书架/进度/书签/时长/外站数据/站内消息）
    void deleteUserCascade(Long id);
}
