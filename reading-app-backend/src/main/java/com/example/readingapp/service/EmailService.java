package com.example.readingapp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    public void sendVerificationCode(String toEmail, String code) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromEmail);
        helper.setTo(toEmail);
        helper.setSubject("【阅读App】您的验证码");

        String htmlContent = buildEmailContent(code);
        helper.setText(htmlContent, true);

        mailSender.send(message);
        log.info("邮件验证码已发送至: {}", toEmail);
    }

    private String buildEmailContent(String code) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f5f5f5; }
                    .container { max-width: 500px; margin: 50px auto; padding: 30px; background: white; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                    .title { color: #333; font-size: 24px; text-align: center; margin-bottom: 30px; }
                    .code { font-size: 36px; color: #4A90E2; text-align: center; letter-spacing: 10px; margin: 30px 0; font-weight: bold; }
                    .tips { color: #999; font-size: 14px; text-align: center; }
                    .warning { color: #ff5722; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="title">阅读App 验证码</div>
                    <div class="code">%s</div>
                    <p class="tips">验证码有效期为5分钟，请勿泄露给他人。</p>
                    <p class="tips warning">如果您未发起验证码申请，请忽略此邮件。</p>
                </div>
            </body>
            </html>
            """.formatted(code);
    }
}