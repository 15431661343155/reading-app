package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.ApkPush;
import com.example.readingapp.repository.ApkPushRepository;
import com.example.readingapp.service.ApkPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AppUpdateController {

    private final ApkPushService apkPushService;
    private final ApkPushRepository apkPushRepository;

    // 当前客户端最新版本号（硬编码或从配置文件读取）
    private static final String DEFAULT_VERSION = "1.5.4";

    @GetMapping("/update-check")
    public ResponseEntity<ApiResponse<ApkPush>> checkUpdate(
            @RequestParam(required = false, defaultValue = "1.5.4") String clientVersion) {
        String currentVersion = clientVersion != null && !clientVersion.isEmpty() ? clientVersion : DEFAULT_VERSION;

        ApkPush apkPush = apkPushService.getCurrentPushedApk();
        if (apkPush == null) {
            return ResponseEntity.ok(ApiResponse.success("暂无更新", null));
        }

        // 比较版本号：如果推送的版本 <= 客户端当前版本，则认为没有更新
        if (compareVersion(apkPush.getVersion(), currentVersion) <= 0) {
            return ResponseEntity.ok(ApiResponse.success("暂无更新", null));
        }

        // 设置下载链接：必须用「公开下载接口」（permitAll，App 端 DownloadManager 匿名下载）。
        // ⚠️ 勿改回 /api/admin/apk/download/——该接口需管理员登录，匿名 401，App 内直接「下载失败」。
        String downloadUrl = "/api/app/apk/download/" +
                URLEncoder.encode(apkPush.getFileName(), StandardCharsets.UTF_8);
        apkPush.setFilePath(downloadUrl);

        return ResponseEntity.ok(ApiResponse.success(apkPush));
    }

    // 清除所有推送状态（调试用）
    @PostMapping("/clear-push")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> clearPush() {
        apkPushRepository.clearAllPushed();
        return ResponseEntity.ok(ApiResponse.success("清除成功", null));
    }

    /**
     * 比较版本号
     * @return 1: v1>v2, 0: v1=v2, -1: v1<v2
     */
    private int compareVersion(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;
        String[] arr1 = v1.split("\\.");
        String[] arr2 = v2.split("\\.");
        int len = Math.max(arr1.length, arr2.length);
        for (int i = 0; i < len; i++) {
            int num1 = i < arr1.length ? Integer.parseInt(arr1[i]) : 0;
            int num2 = i < arr2.length ? Integer.parseInt(arr2[i]) : 0;
            if (num1 > num2) return 1;
            if (num1 < num2) return -1;
        }
        return 0;
    }
}