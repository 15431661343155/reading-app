package com.example.readingapp.repository;

import com.example.readingapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}