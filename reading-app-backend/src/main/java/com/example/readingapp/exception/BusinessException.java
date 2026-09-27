package com.example.readingapp.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：用于表达可预期的业务错误（如账号密码错误、参数校验失败、资源不存在等）。
 * 由 GlobalExceptionHandler 统一捕获后返回对应的 HTTP 状态码与可读错误信息，
 * 避免被兜底为 500 服务器内部错误。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST.value(), message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(HttpStatus status, String message) {
        this(status.value(), message);
    }
}
