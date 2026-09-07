package com.example.readingapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeRequest {
    private String phone;
    private String email;
    private String operation; // bind 或 unbind
    private String type; // 兼容旧字段
}