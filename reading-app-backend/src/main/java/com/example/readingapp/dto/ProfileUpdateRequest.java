package com.example.readingapp.dto;

import lombok.Data;

/**
 * 用户「自助修改个人资料」请求体（App 的「个人信息」页 / Web 的「个人中心」页）。
 *
 * <p>与 {@link UserUpdateRequest}（管理后台编辑用户）刻意分开，两者可写字段不同：
 * <ul>
 *   <li>本 DTO 面向用户本人，只放行展示类字段：昵称、性别、头像；</li>
 *   <li><b>不含</b> username / email / phone / role / status / password —— 邮箱与手机号是登录与找回凭证，
 *       改它们必须走 {@code /api/user/bind/**} 的验证码链路，不能靠改资料接口直接覆盖，
 *       否则等于「拿到一个失效的 JWT 就能把账号邮箱改走」。</li>
 * </ul>
 *
 * <p>字段语义：{@code null} 表示「不修改」，空串或纯空白表示「清空该字段」。
 */
@Data
public class ProfileUpdateRequest {

    /** 昵称，最长 50；空串表示清空（展示时会回退到 username） */
    private String nickname;

    /** 性别：0 未知 / 1 男 / 2 女；null 表示不修改 */
    private Integer gender;

    /**
     * 头像地址。只接受两种形式：
     * <ul>
     *   <li>本服务上传后返回的相对路径，形如 {@code /avatars/xxx.jpg}；</li>
     *   <li>完整的 {@code http://} / {@code https://} 外链。</li>
     * </ul>
     * 空串表示恢复默认头像（置空）。App 端本地文件路径（{@code /data/user/0/...}）会被拒绝，
     * 因为那种路径只存在于用户手机上，服务端既读不到也没法给别的端展示。
     */
    private String avatar;
}
