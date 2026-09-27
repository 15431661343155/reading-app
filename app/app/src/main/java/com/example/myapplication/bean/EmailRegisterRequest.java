package com.example.myapplication.bean;

/**
 * 邮箱注册请求：{@code {email, code, username, password}}。
 */
public class EmailRegisterRequest {
    private String email;
    private String code;
    private String username;
    private String password;

    public EmailRegisterRequest(String email, String code, String username, String password) {
        this.email = email;
        this.code = code;
        this.username = username;
        this.password = password;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
