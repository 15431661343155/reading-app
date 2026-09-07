package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/admin/apk")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ApkController {

    private final OperationLogService operationLogService;

    @org.springframework.beans.factory.annotation.Value("${apk.upload-dir:/www/app/apk}")
    private String apkDir;

    @org.springframework.beans.factory.annotation.Value("${apk.backup-dir:/www/apk/backup}")
    private String backupDir;
    
    // APK信息类
    public static class ApkInfo {
        private String fileName;
        private String downloadUrl;
        private long fileSize;
        private String uploadTime;
        
        public ApkInfo(String fileName, String downloadUrl, long fileSize, String uploadTime) {
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
            this.fileSize = fileSize;
            this.uploadTime = uploadTime;
        }
        
        public String getFileName() { return fileName; }
        public String getDownloadUrl() { return downloadUrl; }
        public long getFileSize() { return fileSize; }
        public String getUploadTime() { return uploadTime; }
    }
    
    // 上传APK文件
    @PostMapping("/upload")
    public ApiResponse<ApkInfo> uploadApk(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "appName", defaultValue = "书阁阅读") String appName,
            @RequestParam(name = "version", required = false) String version,
            HttpServletRequest request) {
        try {
            // 确保目录存在
            File dir = new File(apkDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 验证文件类型
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".apk")) {
                return ApiResponse.error("请上传APK格式的文件");
            }

            // 解析版本号：优先使用参数，否则尝试从原文件名解析 vX.Y.Z
            String v = (version != null && !version.trim().isEmpty()) ? version.trim() : "";
            if (v.isEmpty()) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("[vV](\\d+\\.\\d+\\.\\d+)").matcher(originalFilename);
                if (m.find()) v = m.group(1);
            }
            if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);

            // 生成日期：20260613
            String dateStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));

            // 组装文件名：应用名_v版本号_更新日期.apk
            String safeName = appName.trim() + "_v" + v + "_" + dateStr + ".apk";
            safeName = safeName.replaceAll("[\\/:*?\"<>|]", "_");

            File targetFile = new File(dir, safeName);
            file.transferTo(targetFile);

            // 备份到备份目录
            try {
                File backupFolder = new File(backupDir);
                if (!backupFolder.exists()) {
                    backupFolder.mkdirs();
                }
                File backupFile = new File(backupFolder, safeName);
                Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception backupEx) {
                // 备份失败不影响上传结果
            }

            // 记录操作日志
            operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                    "上传APK文件：" + safeName, null, getClientIp(request));

            String downloadUrl = "/api/admin/apk/download/" + URLEncoder.encode(safeName, StandardCharsets.UTF_8);
            String uploadTime = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            ApkInfo info = new ApkInfo(safeName, downloadUrl, targetFile.length(), uploadTime);
            return ApiResponse.success("APK上传成功", info);
        } catch (Exception e) {
            return ApiResponse.error("上传失败: " + e.getMessage());
        }
    }
    
    // 获取APK列表
    @GetMapping("/list")
    public ApiResponse<List<ApkInfo>> listApks() {
        try {
            List<ApkInfo> apkList = new ArrayList<>();
            File dir = new File(apkDir);
            
            if (!dir.exists()) {
                dir.mkdirs();
                return ApiResponse.success(apkList);
            }
            
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".apk"));
            if (files == null || files.length == 0) {
                return ApiResponse.success(apkList);
            }
            
            for (File file : files) {
                String fileName = file.getName();
                String downloadUrl = "/api/admin/apk/download/" + URLEncoder.encode(fileName, StandardCharsets.UTF_8);
                long fileSize = file.length();
                String uploadTime = java.time.LocalDateTime.now().toString();
                
                // 尝试获取文件的最后修改时间
                try {
                    uploadTime = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(file.lastModified()),
                        java.time.ZoneId.systemDefault()
                    ).toString().replace("T", " ");
                } catch (Exception ignored) {}
                
                apkList.add(new ApkInfo(fileName, downloadUrl, fileSize, uploadTime));
            }
            
            // 按修改时间降序排列（最新的在前）
            apkList.sort((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()));
            
            return ApiResponse.success(apkList);
        } catch (Exception e) {
            return ApiResponse.error("获取列表失败: " + e.getMessage());
        }
    }
    
    // 下载APK文件
    @GetMapping("/download/{fileName}")
    public void downloadApk(@PathVariable String fileName, HttpServletResponse response) {
        try {
            String decodedFileName = java.net.URLDecoder.decode(fileName, StandardCharsets.UTF_8);
            File file = new File(apkDir, decodedFileName);
            
            if (!file.exists()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在");
                return;
            }
            
            response.setContentType("application/vnd.android.package-archive");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + 
                URLEncoder.encode(decodedFileName, StandardCharsets.UTF_8) + "\"");
            response.setContentLength((int) file.length());
            
            try (InputStream is = Files.newInputStream(file.toPath());
                 OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "下载失败");
            } catch (IOException ignored) {}
        }
    }
    
    // 删除APK文件
    @DeleteMapping("/delete/{fileName}")
    public ApiResponse<Void> deleteApk(@PathVariable String fileName, HttpServletRequest request) {
        try {
            String decodedFileName = java.net.URLDecoder.decode(fileName, StandardCharsets.UTF_8);
            File file = new File(apkDir, decodedFileName);
            
            if (!file.exists()) {
                return ApiResponse.error("文件不存在");
            }
            
            if (file.delete()) {
                operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                    "删除APK文件：" + decodedFileName, null, getClientIp(request));
                return ApiResponse.success("删除成功", null);
            } else {
                return ApiResponse.error("删除失败");
            }
        } catch (Exception e) {
            return ApiResponse.error("删除失败: " + e.getMessage());
        }
    }
    
    // 获取客户端IP
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
