package com.example.myapplication.bean;

/**
 * 用户自助修改个人资料的请求体，对应后端 {@code PUT /api/user/profile}。
 *
 * <p>后端不会信任请求里的身份，当前用户一律由 JWT 决定；这里也不放 userId、username、
 * email、phone、role 等字段 —— 邮箱/手机号要走验证码绑定链路（{@code /api/user/bind/**}），
 * 不能靠改资料接口直接覆盖。
 *
 * <p>字段语义与后端 {@code ProfileUpdateRequest} 对齐：{@code null} = 不修改。
 * 用 Integer 而不是 int，正是为了能把「不修改」表达成 null。
 */
public class ProfileUpdateRequest {

    /** 昵称；null 表示不修改，空串表示清空 */
    private String nickname;

    /** 性别：0 未知 / 1 男 / 2 女；null 表示不修改 */
    private Integer gender;

    /**
     * 头像地址；null 表示不修改。
     * 只接受后端上传接口返回的相对路径（{@code /avatars/xxx.jpg}）或 http(s) 外链，
     * 本地文件路径会被后端拒绝。
     */
    private String avatar;

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}
