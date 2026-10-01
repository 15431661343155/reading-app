package com.example.readingapp.dto;

import lombok.Data;

/**
 * 管理后台调整内部人员角色请求体（仅 ADMIN / STAFF 互转）。
 */
@Data
public class RoleUpdateRequest {
    /** 目标角色：ADMIN（管理员）或 STAFF（内部人员） */
    private String role;
}
