package com.example.myapplication.bean;

public class SendCodeRequest {
    private String phone;
    private String email;
    private String userId;
    private String operation;

    public SendCodeRequest() {}

    public SendCodeRequest(String phone, String userId, String operation) {
        this.phone = phone;
        this.userId = userId;
        this.operation = operation;
    }

    public SendCodeRequest(String email, String operation) {
        this.email = email;
        this.operation = operation;
    }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
}
