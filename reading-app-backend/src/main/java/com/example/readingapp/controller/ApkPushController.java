package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.ApkPush;
import com.example.readingapp.service.ApkPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/apk-push")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ApkPushController {

    private final ApkPushService apkPushService;

    @PostMapping("/push")
    public ResponseEntity<ApiResponse<ApkPush>> pushApk(
            @RequestParam String fileName,
            @RequestParam(required = false) String version) {
        ApkPush apkPush = apkPushService.pushApk(fileName, version);
        return ResponseEntity.ok(ApiResponse.success("推送成功", apkPush));
    }

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<ApkPush>> getCurrentPushedApk() {
        ApkPush apkPush = apkPushService.getCurrentPushedApk();
        if (apkPush == null) {
            return ResponseEntity.ok(ApiResponse.success("暂无推送", null));
        }
        return ResponseEntity.ok(ApiResponse.success(apkPush));
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<ApkPush>>> getAllRecords() {
        List<ApkPush> list = apkPushService.getAllApkPushRecords();
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRecord(@PathVariable Long id) {
        apkPushService.deleteApkPush(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}