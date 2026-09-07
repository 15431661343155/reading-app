package com.example.readingapp.service.impl;

import com.example.readingapp.entity.ApkPush;
import com.example.readingapp.repository.ApkPushRepository;
import com.example.readingapp.service.ApkPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ApkPushServiceImpl implements ApkPushService {

    @org.springframework.beans.factory.annotation.Value("${apk.upload-dir:/www/app/apk}")
    private String apkDir;

    private final ApkPushRepository apkPushRepository;

    @Override
    @Transactional
    public ApkPush pushApk(String fileName, String version) {
        apkPushRepository.clearAllPushed();

        Optional<ApkPush> existing = apkPushRepository.findAll().stream()
                .filter(a -> a.getFileName().equals(fileName))
                .findFirst();

        ApkPush apkPush;
        if (existing.isPresent()) {
            apkPush = existing.get();
            apkPush.setIsPushed(true);
            apkPush.setPushTime(LocalDateTime.now());
            if (version != null && !version.isEmpty()) {
                apkPush.setVersion(version);
            }
        } else {
            apkPush = new ApkPush();
            apkPush.setFileName(fileName);
            apkPush.setFilePath(apkDir + "/" + fileName);
            apkPush.setVersion(version != null ? version : "1.0.0");
            apkPush.setIsPushed(true);
            apkPush.setPushTime(LocalDateTime.now());
        }

        // 计算APK文件大小
        File apkFile = new File(apkDir, fileName);
        if (apkFile.exists()) {
            apkPush.setFileSize(apkFile.length());
            // 计算MD5
            String md5 = calculateMD5(apkFile);
            apkPush.setMd5(md5);
        }

        return apkPushRepository.save(apkPush);
    }

    private String calculateMD5(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            byte[] digest = md.digest();
            return new BigInteger(1, digest).toString(16).toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ApkPush getCurrentPushedApk() {
        return apkPushRepository.findCurrentPushedApk().orElse(null);
    }

    @Override
    public List<ApkPush> getAllApkPushRecords() {
        return apkPushRepository.findAll();
    }

    @Override
    @Transactional
    public void deleteApkPush(Long id) {
        apkPushRepository.deleteById(id);
    }
}
