package com.example.readingapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BindRequest {
    private String phone;
    private String email;
    private String code;
    private String operation; // bind, unbind, change_password
    private String password;
    private String newPassword;
}