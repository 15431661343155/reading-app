package com.example.readingapp.service;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Slf4j
@Service
public class SmsService {

    @Value("${sms.provider:mock}")
    private String provider;

    @Value("${sms.aliyun.access-key-id:}")
    private String aliyunAccessKeyId;

    @Value("${sms.aliyun.access-key-secret:}")
    private String aliyunAccessKeySecret;

    @Value("${sms.aliyun.sign-name:}")
    private String aliyunSignName;

    @Value("${sms.aliyun.template-code:}")
    private String aliyunTemplateCode;

    private Client aliyunClient;

    @PostConstruct
    public void init() {
        if ("aliyun".equalsIgnoreCase(provider) && aliyunAccessKeyId != null && !aliyunAccessKeyId.isEmpty()) {
            try {
                Config config = new Config()
                        .setAccessKeyId(aliyunAccessKeyId)
                        .setAccessKeySecret(aliyunAccessKeySecret)
                        .setEndpoint("dysmsapi.aliyuncs.com");
                aliyunClient = new Client(config);
                log.info("阿里云短信客户端初始化成功");
            } catch (Exception e) {
                log.error("阿里云短信客户端初始化失败: {}", e.getMessage());
            }
        }
    }

    @Async
    public void sendVerificationCode(String phone, String code) {
        log.info("准备发送短信验证码到 {}, 提供商: {}", phone, provider);

        switch (provider.toLowerCase()) {
            case "aliyun" -> sendByAliyun(phone, code);
            case "tencent" -> sendByTencent(phone, code);
            case "mock" -> sendByMock(phone, code);
            default -> {
                log.warn("未配置的短信提供商: {}", provider);
                log.info("【模拟发送】手机号: {}, 验证码: {}", phone, code);
            }
        }
    }

    private void sendByAliyun(String phone, String code) {
        if (aliyunClient == null) {
            log.error("阿里云短信客户端未初始化，请检查配置");
            log.info("【模拟发送】手机号: {}, 验证码: {}", phone, code);
            return;
        }

        try {
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(aliyunSignName)
                    .setTemplateCode(aliyunTemplateCode)
                    .setTemplateParam("{\"code\":\"" + code + "\"}");

            SendSmsResponse response = aliyunClient.sendSms(request);

            if (response.getBody() != null && "OK".equals(response.getBody().getCode())) {
                log.info("阿里云短信发送成功，手机号: {}, BizId: {}", phone, response.getBody().getBizId());
            } else {
                log.error("阿里云短信发送失败，手机号: {}, 错误码: {}, 错误信息: {}",
                        phone,
                        response.getBody() != null ? response.getBody().getCode() : "unknown",
                        response.getBody() != null ? response.getBody().getMessage() : "unknown");
            }
        } catch (Exception e) {
            log.error("阿里云短信发送异常: {}", e.getMessage());
            log.info("【模拟发送】手机号: {}, 验证码: {}", phone, code);
        }
    }

    private void sendByTencent(String phone, String code) {
        // 腾讯云短信SDK集成示例
        // 实际使用时需要添加腾讯云SDK依赖并配置AppId和AppKey
        log.info("【腾讯云短信】手机号: {}, 验证码: {}", phone, code);
        log.warn("腾讯云短信SDK未集成，请添加依赖并实现发送逻辑");
    }

    private void sendByMock(String phone, String code) {
        // 模拟发送模式，仅打印日志
        // 实际部署时请切换为真实的短信提供商
        log.info("【模拟短信】手机号: {}, 验证码: {} (如需真实发送，请配置sms.provider)", phone, code);
    }
}