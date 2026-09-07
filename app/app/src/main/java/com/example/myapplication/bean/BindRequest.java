package com.example.myapplication.bean;

public class BindRequest {
    private String phone;
    private String email;
    private String code;
    private String userId;
    private String operation;
    private String password;
    private String newPassword;

    public BindRequest() {}

    // 邮箱验证码绑定（旧的构造函数，保留兼容性）
    public BindRequest(String email, String code, String operation) {
        this.email = email;
        this.code = code;
        this.operation = operation;
    }

    public BindRequest(String phone, String code, String userId, String operation) {
        this.phone = phone;
        this.code = code;
        this.userId = userId;
        this.operation = operation;
    }

    // 邮箱验证码绑定
    public static BindRequest forEmailBind(String email, String code, String operation) {
        BindRequest request = new BindRequest();
        request.email = email;
        request.code = code;
        request.operation = operation;
        return request;
    }

    // 邮箱修改密码
    public static BindRequest forEmailChangePassword(String email, String code, String newPassword) {
        BindRequest request = new BindRequest();
        request.email = email;
        request.code = code;
        request.newPassword = newPassword;
        request.operation = "email";
        return request;
    }

    // 密码修改
    public static BindRequest forPasswordChange(String password, String newPassword) {
        BindRequest request = new BindRequest();
        request.password = password;
        request.newPassword = newPassword;
        request.operation = "password";
        return request;
    }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}