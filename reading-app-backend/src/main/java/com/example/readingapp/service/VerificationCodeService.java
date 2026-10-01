package com.example.readingapp.service;

import com.example.readingapp.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private static final SecureRandom random = new SecureRandom();
    private static final long CODE_EXPIRE_TIME = 300000; // 5分钟过期
    private static final int CODE_LENGTH = 6;

    private final SmsService smsService;
    private final EmailService emailService;

    private final Map<String, CodeCache> codeCache = new ConcurrentHashMap<>();

    private static class CodeCache {
        String code;
        long expireTime;
        int sendCount;
        /** 校验失败次数：达到 {@link #CODE_MAX_FAILS} 次立即作废，防 6 位码穷举爆破 */
        int failCount;

        CodeCache(String code) {
            this.code = code;
            this.expireTime = System.currentTimeMillis() + CODE_EXPIRE_TIME;
            this.sendCount = 1;
        }
    }

    /** 单个验证码允许的最大校验失败次数（超过即作废，需重新发送） */
    private static final int CODE_MAX_FAILS = 5;

    public String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    public void sendSmsCode(String phone, String type) {
        String key = "sms_" + phone + "_" + type;
        CodeCache cache = codeCache.get(key);

        if (cache != null && cache.sendCount >= 5) {
            log.warn("手机号 {} 验证码发送次数已达上限", phone);
            throw new BusinessException("验证码发送次数已达上限，请稍后再试");
        }

        if (cache != null && System.currentTimeMillis() < cache.expireTime + 60000) {
            log.warn("手机号 {} 验证码发送过于频繁", phone);
            throw new BusinessException("发送过于频繁，请稍后再试");
        }

        String code = generateCode();

        if (cache == null) {
            codeCache.put(key, new CodeCache(code));
        } else {
            cache.code = code;
            cache.expireTime = System.currentTimeMillis() + CODE_EXPIRE_TIME;
            cache.sendCount++;
        }

        smsService.sendVerificationCode(phone, code);
        log.info("短信验证码已发送至 {}", phone);
    }

    public void sendEmailCode(String email, String type) throws MessagingException {
        String key = "email_" + email + "_" + type;
        CodeCache cache = codeCache.get(key);

        if (cache != null && cache.sendCount >= 5) {
            log.warn("邮箱 {} 验证码发送次数已达上限", email);
            throw new BusinessException("验证码发送次数已达上限，请稍后再试");
        }

        if (cache != null && System.currentTimeMillis() < cache.expireTime + 60000) {
            log.warn("邮箱 {} 验证码发送过于频繁", email);
            throw new BusinessException("发送过于频繁，请稍后再试");
        }

        String code = generateCode();

        if (cache == null) {
            codeCache.put(key, new CodeCache(code));
        } else {
            cache.code = code;
            cache.expireTime = System.currentTimeMillis() + CODE_EXPIRE_TIME;
            cache.sendCount++;
        }

        emailService.sendVerificationCode(email, code);
        log.info("邮件验证码已发送至 {}", email);
    }

    public boolean verifySmsCode(String phone, String code, String type) {
        String key = "sms_" + phone + "_" + type;
        CodeCache cache = codeCache.get(key);

        if (cache == null) {
            log.warn("手机号 {} 未获取验证码", phone);
            return false;
        }

        if (System.currentTimeMillis() > cache.expireTime) {
            codeCache.remove(key);
            log.warn("手机号 {} 验证码已过期", phone);
            return false;
        }

        boolean valid = code.equals(cache.code);
        if (valid) {
            codeCache.remove(key);
            log.info("手机号 {} 验证码验证成功", phone);
        } else {
            cache.failCount++;
            if (cache.failCount >= CODE_MAX_FAILS) {
                // 失败次数达上限：作废验证码，防穷举爆破（需重新发送）
                codeCache.remove(key);
                log.warn("手机号 {} 验证码失败 {} 次，已作废", phone, cache.failCount);
            } else {
                log.warn("手机号 {} 验证码验证失败（{}/{})", phone, cache.failCount, CODE_MAX_FAILS);
            }
        }

        return valid;
    }

    public boolean verifyEmailCode(String email, String code, String type) {
        String key = "email_" + email + "_" + type;
        CodeCache cache = codeCache.get(key);

        if (cache == null) {
            log.warn("邮箱 {} 未获取验证码", email);
            return false;
        }

        if (System.currentTimeMillis() > cache.expireTime) {
            codeCache.remove(key);
            log.warn("邮箱 {} 验证码已过期", email);
            return false;
        }

        boolean valid = code.equals(cache.code);
        if (valid) {
            codeCache.remove(key);
            log.info("邮箱 {} 验证码验证成功", email);
        } else {
            cache.failCount++;
            if (cache.failCount >= CODE_MAX_FAILS) {
                // 失败次数达上限：作废验证码，防穷举爆破（需重新发送）
                codeCache.remove(key);
                log.warn("邮箱 {} 验证码失败 {} 次，已作废", email, cache.failCount);
            } else {
                log.warn("邮箱 {} 验证码验证失败（{}/{})", email, cache.failCount, CODE_MAX_FAILS);
            }
        }

        return valid;
    }
}