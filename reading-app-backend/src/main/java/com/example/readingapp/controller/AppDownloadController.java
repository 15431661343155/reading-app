package com.example.readingapp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
public class AppDownloadController {

    @org.springframework.beans.factory.annotation.Value("${apk.upload-dir:/www/app/apk}")
    private String apkDir;

    private static final String DOWNLOAD_PREFIX = "/api/admin/apk/download/";

    @GetMapping({"/download", "/app", "/apk"})
    public String download(Model model) {
        LatestApk info = getLatestApk();
        model.addAttribute("appName", info.appName);
        model.addAttribute("version", info.version);
        model.addAttribute("versionDisplay", "v" + info.version);
        model.addAttribute("date", info.date);
        model.addAttribute("fileSize", info.fileSize);
        model.addAttribute("fileName", info.fileName);
        model.addAttribute("downloadUrl", DOWNLOAD_PREFIX + encode(info.fileName));
        return "download";
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static class LatestApk {
        String appName = "书阁阅读";
        String version = "1.5.3";
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String fileSize = "8.7 MB";
        String fileName = "书阁阅读_v1.5.3_20260613.apk";
    }

    private LatestApk getLatestApk() {
        LatestApk info = new LatestApk();
        try {
            File dir = new File(apkDir);
            if (!dir.exists()) return info;
            File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".apk"));
            if (files == null || files.length == 0) return info;

            // 按修改时间降序取最新
            File latest = Arrays.stream(files)
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(files[0]);

            String fileName = latest.getName();
            info.fileName = fileName;

            // 解析应用名_v版本号_日期.apk
            String base = fileName;
            if (base.toLowerCase().endsWith(".apk")) {
                base = base.substring(0, base.length() - 4);
            }
            String[] parts = base.split("_");

            // 找版本号部分
            Pattern vPattern = Pattern.compile("[vV]?(\\d+\\.\\d+\\.\\d+)");
            for (int i = 0; i < parts.length; i++) {
                Matcher m = vPattern.matcher(parts[i]);
                if (m.find()) {
                    info.version = m.group(1);
                    // 版本号前面的都是应用名
                    StringBuilder appName = new StringBuilder();
                    for (int j = 0; j < i; j++) {
                        if (j > 0) appName.append("_");
                        appName.append(parts[j]);
                    }
                    if (appName.length() > 0) info.appName = appName.toString();
                    // 后面找日期
                    if (i + 1 < parts.length) {
                        String d = parts[i + 1];
                        if (d.matches("\\d{8}")) info.date = d;
                    }
                    break;
                }
            }

            // 文件大小
            long bytes = latest.length();
            if (bytes < 1024 * 1024) {
                info.fileSize = String.format("%.1f KB", bytes / 1024.0);
            } else if (bytes < 1024 * 1024 * 1024) {
                info.fileSize = String.format("%.1f MB", bytes / (1024.0 * 1024));
            } else {
                info.fileSize = String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
            }
        } catch (Exception ignored) {
        }
        return info;
    }
}
