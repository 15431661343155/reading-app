package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公开（无需登录）的安装包信息 / 下载接口。
 *
 * <p>用途：官网静态下载页（web/官网/Download.html）通过本接口获取「最新安装包」的
 * 版本 / 大小 / 更新时间 / 文件名，并拿到公开下载地址。与后台 {@code /api/admin/apk/*}
 * 区分：后台接口需管理员登录，不适配匿名公开下载；这里刻意放在 {@code /api/app/**}
 * 下（SecurityConfig 已对该前缀 permitAll），无需鉴权。</p>
 *
 * <p>数据来源：扫描 {@code apk.upload-dir} 目录，按文件最后修改时间取最新的一份 apk。</p>
 */
@RestController
@RequestMapping("/api/app/apk")
public class PublicApkController {

    @org.springframework.beans.factory.annotation.Value("${apk.upload-dir:/www/app/apk}")
    private String apkDir;

    /** 返回最新安装包的元信息（公开）。 */
    @GetMapping("/latest")
    public ApiResponse<ApkInfo> latest() {
        try {
            File dir = new File(apkDir);
            if (!dir.exists()) {
                return ApiResponse.error("尚未上传安装包");
            }
            File[] files = dir.listFiles((d, n) -> n != null && n.toLowerCase().endsWith(".apk"));
            if (files == null || files.length == 0) {
                return ApiResponse.error("尚未上传安装包");
            }
            File latest = Arrays.stream(files)
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(files[0]);

            String fileName = latest.getName();
            String base = fileName.toLowerCase().endsWith(".apk")
                    ? fileName.substring(0, fileName.length() - 4) : fileName;
            String[] parts = base.split("_");

            String appName = "书阁阅读";
            String version = "";
            String date = LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(latest.lastModified()),
                    java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            Pattern vPattern = Pattern.compile("[vV]?(\\d+\\.\\d+\\.\\d+)");
            for (int i = 0; i < parts.length; i++) {
                Matcher m = vPattern.matcher(parts[i]);
                if (m.find()) {
                    version = m.group(1);
                    StringBuilder name = new StringBuilder();
                    for (int j = 0; j < i; j++) {
                        if (j > 0) name.append("_");
                        name.append(parts[j]);
                    }
                    if (name.length() > 0) appName = name.toString();
                    if (i + 1 < parts.length && parts[i + 1].matches("\\d{8}")) {
                        date = parts[i + 1].substring(0, 4) + "-"
                                + parts[i + 1].substring(4, 6) + "-"
                                + parts[i + 1].substring(6, 8);
                    }
                    break;
                }
            }

            long bytes = latest.length();
            String fileSize = formatSize(bytes);
            String downloadUrl = "/api/app/apk/download/" + URLEncoder.encode(fileName, StandardCharsets.UTF_8);

            ApkInfo info = new ApkInfo(appName, version, "v" + version, date, fileSize, bytes, fileName, downloadUrl);
            return ApiResponse.success("获取成功", info);
        } catch (Exception e) {
            return ApiResponse.error("获取安装包信息失败：" + e.getMessage());
        }
    }

    /** 公开下载安装包（无需登录）。与后台下载接口逻辑一致，但放在公开前缀下。 */
    @GetMapping("/download/{fileName}")
    public void download(@PathVariable String fileName, HttpServletResponse response) {
        try {
            String decoded = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
            File file = new File(apkDir, decoded);
            if (!file.exists() || !file.isFile()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在");
                return;
            }
            response.setContentType("application/vnd.android.package-archive");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + URLEncoder.encode(decoded, StandardCharsets.UTF_8) + "\"");
            response.setContentLength((int) file.length());
            try (InputStream is = Files.newInputStream(file.toPath());
                 OutputStream os = response.getOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
            }
        } catch (IOException e) {
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "下载失败");
            } catch (IOException ignored) {
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format("约 %.1f KB", bytes / 1024.0);
        } else if (bytes < 1024.0 * 1024 * 1024) {
            return String.format("约 %.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("约 %.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /** 安装包元信息载体。 */
    public static class ApkInfo {
        public String appName;
        public String version;
        public String versionDisplay;
        public String date;
        public String fileSize;
        public long fileSizeBytes;
        public String fileName;
        public String downloadUrl;

        public ApkInfo(String appName, String version, String versionDisplay, String date,
                      String fileSize, long fileSizeBytes, String fileName, String downloadUrl) {
            this.appName = appName;
            this.version = version;
            this.versionDisplay = versionDisplay;
            this.date = date;
            this.fileSize = fileSize;
            this.fileSizeBytes = fileSizeBytes;
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
        }
    }
}
