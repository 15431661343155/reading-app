package com.example.readingapp.dto;

import lombok.Data;

/**
 * 管理后台「编辑用户信息」请求体。
 *
 * <p>刻意不复用 {@link com.example.readingapp.entity.User} 接收请求体：实体上带有
 * password / role / userId / username 等不应由后台表单覆盖的字段（password 虽标注
 * {@code @JsonProperty(WRITE_ONLY)}，但那是「序列化时不输出」，反序列化仍可写入）。
 * 本 DTO 只暴露允许编辑的展示类字段，避免越权改角色或改密。
 *
 * <p>字符串字段语义：{@code null} 表示「不修改」，空串或纯空白表示「清空该字段」。
 */
@Data
public class UserUpdateRequest {

    /** 昵称，最长 50；空串表示清空 */
    private String nickname;

    /** 邮箱，全局唯一；空串表示清空 */
    private String email;

    /** 手机号，全局唯一；空串表示清空 */
    private String phone;

    /** 性别：0 未知 / 1 男 / 2 女；null 表示不修改 */
    private Integer gender;
}
