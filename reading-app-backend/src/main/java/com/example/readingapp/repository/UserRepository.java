package com.example.readingapp.repository;

import com.example.readingapp.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 根据用户名查找用户
    Optional<User> findByUsername(String username);

    // 根据邮箱查找用户
    Optional<User> findByEmail(String email);

    // 根据手机号查找用户
    Optional<User> findByPhone(String phone);

    // 检查用户名是否存在
    boolean existsByUsername(String username);

    // 检查邮箱是否存在
    boolean existsByEmail(String email);

    // 检查手机号是否存在
    boolean existsByPhone(String phone);

    // 检查 userId 是否存在
    boolean existsByUserId(String userId);

    // 根据字符串 userId 查找用户
    Optional<User> findByUserId(String userId);

    // 是否存在指定角色的用户（用于管理员账号初始化判断）
    boolean existsByRole(String role);

    // ===== 管理后台用户列表按角色过滤 =====

    /**
     * 普通用户列表：role 为指定值（忽略大小写）或 NULL（历史数据 role 列允许为空，一律视为普通用户）。
     */
    @Query("SELECT u FROM User u WHERE u.role IS NULL OR LOWER(u.role) = LOWER(:role)")
    Page<User> findByRoleOrDefault(@Param("role") String role, Pageable pageable);

    /**
     * 内部人员列表：role 在给定集合内（忽略大小写），如 ADMIN + STAFF。
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.role) IN :roles")
    Page<User> findByRoleInIgnoreCase(@Param("roles") Collection<String> roles, Pageable pageable);

    /**
     * 统计指定角色（忽略大小写）的用户数量，用于「最后一个管理员」保护。
     */
    long countByRoleIgnoreCase(String role);
}