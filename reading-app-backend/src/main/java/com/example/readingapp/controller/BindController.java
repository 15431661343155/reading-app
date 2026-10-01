package com.example.readingapp.controller;

import com.example.readingapp.exception.BusinessException;
import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.BindRequest;
import com.example.readingapp.dto.CodeRequest;
import com.example.readingapp.dto.CodeVerifyRequest;
import com.example.readingapp.entity.User;
import com.example.readingapp.service.BindService;
import com.example.readingapp.service.VerificationCodeService;
import com.example.readingapp.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/user/bind")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BindController {

    private final VerificationCodeService verificationCodeService;
    private final BindService bindService;

    private final Map<String, Object> rateLimitCache = new ConcurrentHashMap<>();

    private String buildCodeType(String operation, String channel) {
        if ("unbind".equalsIgnoreCase(operation)) {
            return "unbind_" + channel;
        }
        // 修改密码走邮箱验证：验证码用途为 change_password，不能被默认映射成 bind_*，
        // 否则 changePasswordByEmail() 校验的 change_password 类型验证码永远取不到码。
        if ("change_password".equalsIgnoreCase(operation)) {
            return "change_password";
        }
        return "bind_" + channel;
    }

    @PostMapping("/send-sms-code")
    public ApiResponse<Void> sendSmsCode(@RequestBody CodeRequest request) {
        try {
            if (request.getPhone() == null || request.getPhone().isEmpty()) {
                return ApiResponse.error("手机号不能为空");
            }

            String operation = request.getOperation();
            String type = buildCodeType(operation, "phone");

            String key = "sms_" + request.getPhone() + "_" + type;
            long now = System.currentTimeMillis();

            Object lastSendObj = rateLimitCache.get(key);
            if (lastSendObj instanceof Long lastSend && now - lastSend < 60000) {
                return ApiResponse.error("发送过于频繁，请稍后再试");
            }

            Object lastHourObj = rateLimitCache.get(key + "_hour");
            if (lastHourObj instanceof Long lastHour && now - lastHour < 3600000) {
                Object countObj = rateLimitCache.getOrDefault(key + "_count", 0);
                int count = countObj instanceof Integer ? (Integer) countObj : 0;
                if (count >= 5) {
                    return ApiResponse.error("验证码发送次数已达上限，请稍后再试");
                }
                rateLimitCache.put(key + "_count", count + 1);
            } else {
                rateLimitCache.put(key + "_count", 1);
                rateLimitCache.put(key + "_hour", now);
            }

            rateLimitCache.put(key, now);

            verificationCodeService.sendSmsCode(request.getPhone(), type);

            return ApiResponse.success("验证码发送成功", null);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @PostMapping("/send-email-code")
    public ApiResponse<Void> sendEmailCode(@RequestBody CodeRequest request) {
        try {
            if (request.getEmail() == null || request.getEmail().isEmpty()) {
                return ApiResponse.error("邮箱不能为空");
            }

            String operation = request.getOperation();
            String type = buildCodeType(operation, "email");

            String key = "email_" + request.getEmail() + "_" + type;
            long now = System.currentTimeMillis();

            Object lastSendObj = rateLimitCache.get(key);
            if (lastSendObj instanceof Long lastSend && now - lastSend < 60000) {
                return ApiResponse.error("发送过于频繁，请稍后再试");
            }

            Object lastHourObj = rateLimitCache.get(key + "_hour");
            if (lastHourObj instanceof Long lastHour && now - lastHour < 3600000) {
                Object countObj = rateLimitCache.getOrDefault(key + "_count", 0);
                int count = countObj instanceof Integer ? (Integer) countObj : 0;
                if (count >= 5) {
                    return ApiResponse.error("验证码发送次数已达上限，请稍后再试");
                }
                rateLimitCache.put(key + "_count", count + 1);
            } else {
                rateLimitCache.put(key + "_count", 1);
                rateLimitCache.put(key + "_hour", now);
            }

            rateLimitCache.put(key, now);

            verificationCodeService.sendEmailCode(request.getEmail(), type);

            return ApiResponse.success("验证码发送成功", null);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @PostMapping("/verify-sms-code")
    public ApiResponse<Void> verifySmsCode(@RequestBody CodeVerifyRequest request) {
        try {
            if (request.getPhone() == null || request.getPhone().isEmpty()) {
                return ApiResponse.error("手机号不能为空");
            }
            if (request.getCode() == null || request.getCode().isEmpty()) {
                return ApiResponse.error("验证码不能为空");
            }

            boolean valid = verificationCodeService.verifySmsCode(request.getPhone(), request.getCode(), "bind_phone");
            if (valid) {
                return ApiResponse.success("验证码验证成功", null);
            } else {
                return ApiResponse.error("验证码无效或已过期");
            }
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @PostMapping("/verify-email-code")
    public ApiResponse<Void> verifyEmailCode(@RequestBody CodeVerifyRequest request) {
        try {
            if (request.getEmail() == null || request.getEmail().isEmpty()) {
                return ApiResponse.error("邮箱不能为空");
            }
            if (request.getCode() == null || request.getCode().isEmpty()) {
                return ApiResponse.error("验证码不能为空");
            }

            boolean valid = verificationCodeService.verifyEmailCode(request.getEmail(), request.getCode(), "bind_email");
            if (valid) {
                return ApiResponse.success("验证码验证成功", null);
            } else {
                return ApiResponse.error("验证码无效或已过期");
            }
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @PostMapping("/phone")
    public ApiResponse<User> bindPhone(@RequestBody BindRequest request) {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId == null) {
                return ApiResponse.error("用户未登录");
            }

            if (request.getPhone() == null || request.getPhone().isEmpty()) {
                return ApiResponse.error("手机号不能为空");
            }
            if (request.getCode() == null || request.getCode().isEmpty()) {
                return ApiResponse.error("验证码不能为空");
            }

            String operation = request.getOperation();
            String type = buildCodeType(operation, "phone");

            User user = bindService.bindPhone(userId, request.getPhone(), request.getCode(), type);
            return ApiResponse.success("手机绑定成功", user);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @PostMapping("/email")
    public ApiResponse<User> bindEmail(@RequestBody BindRequest request) {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId == null) {
                return ApiResponse.error("用户未登录");
            }

            if (request.getEmail() == null || request.getEmail().isEmpty()) {
                return ApiResponse.error("邮箱不能为空");
            }
            if (request.getCode() == null || request.getCode().isEmpty()) {
                return ApiResponse.error("验证码不能为空");
            }

            String operation = request.getOperation();
            String type = buildCodeType(operation, "email");

            User user = bindService.bindEmail(userId, request.getEmail(), request.getCode(), type);
            return ApiResponse.success("邮箱绑定成功", user);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @PostMapping("/unbind-phone")
    public ApiResponse<User> unbindPhone(@RequestBody BindRequest request) {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId == null) {
                return ApiResponse.error("用户未登录");
            }

            if (request.getCode() == null || request.getCode().isEmpty()) {
                return ApiResponse.error("验证码不能为空");
            }

            User user = bindService.unbindPhone(userId, request.getCode());
            return ApiResponse.success("手机解绑成功", user);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @PostMapping("/unbind-email")
    public ApiResponse<User> unbindEmail(@RequestBody BindRequest request) {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId == null) {
                return ApiResponse.error("用户未登录");
            }

            if (request.getCode() == null || request.getCode().isEmpty()) {
                return ApiResponse.error("验证码不能为空");
            }

            User user = bindService.unbindEmail(userId, request.getCode());
            return ApiResponse.success("邮箱解绑成功", user);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody BindRequest request) {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId == null) {
                return ApiResponse.error("用户未登录");
            }

            String operation = request.getOperation();

            if ("password".equals(operation)) {
                // 密码验证方式
                if (request.getPassword() == null || request.getPassword().isEmpty()) {
                    return ApiResponse.error("当前密码不能为空");
                }
                if (request.getNewPassword() == null || request.getNewPassword().isEmpty()) {
                    return ApiResponse.error("新密码不能为空");
                }
                bindService.changePasswordByPassword(userId, request.getPassword(), request.getNewPassword());
            } else if ("email".equals(operation)) {
                // 邮箱验证方式
                if (request.getEmail() == null || request.getEmail().isEmpty()) {
                    return ApiResponse.error("邮箱不能为空");
                }
                if (request.getCode() == null || request.getCode().isEmpty()) {
                    return ApiResponse.error("验证码不能为空");
                }
                if (request.getNewPassword() == null || request.getNewPassword().isEmpty()) {
                    return ApiResponse.error("新密码不能为空");
                }
                bindService.changePasswordByEmail(userId, request.getEmail(), request.getCode(), request.getNewPassword());
            } else {
                return ApiResponse.error("无效的验证方式");
            }

            return ApiResponse.success("密码修改成功", null);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("操作失败，请稍后重试");
        }
    }
}