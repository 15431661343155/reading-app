package com.example.myapplication.bean;

/**
 * 密码登录请求：{@code {account, username, password}}。
 * {@code account} 为登录账号（邮箱或用户名）；{@code username} 保留以兼容旧字段，
 * 二者取同一输入值，后端优先使用 {@code account}。
 */
public class LoginRequest {
    private String account;
    private String username;
    private String password;

    public LoginRequest(String account, String password) {
        this.account = account;
        this.username = account;
        this.password = password;
    }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
