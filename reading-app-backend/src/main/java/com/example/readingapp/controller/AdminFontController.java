package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Font;
import com.example.readingapp.repository.FontRepository;
import com.example.readingapp.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/fonts")
@CrossOrigin(origins = "*")
public class AdminFontController {

    @Autowired
    private FontRepository fontRepository;

    @Autowired
    private OperationLogService operationLogService;

    private static final String FONT_DIR = "uploads/fonts/";

    @GetMapping
    public ResponseEntity<ApiResponse<List<Font>>> list() {
        List<Font> fonts = fontRepository.findAll(Sort.by(Sort.Direction.ASC, "sortOrder"));
        return ResponseEntity.ok(ApiResponse.success(fonts));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Font>> create(@RequestBody Map<String, String> req) {
        Font font = new Font();
        font.setName(req.get("name"));
        font.setCssName(req.get("cssName"));
        font.setFileUrl(req.get("fileUrl"));
        font.setFileSize(req.get("fileSize"));
        try {
            font.setSortOrder(Integer.parseInt(req.getOrDefault("sortOrder", "0")));
        } catch (Exception ignored) {
            font.setSortOrder(0);
        }
        Font saved = fontRepository.save(font);
        operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                "新增字体：" + font.getName(), null, getClientIp(null));
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Font>> update(@PathVariable Long id, @RequestBody Map<String, String> req) {
        Font font = fontRepository.findById(id).orElse(null);
        if (font == null) {
            return ResponseEntity.ok(ApiResponse.error("字体不存在"));
        }
        if (req.containsKey("name")) font.setName(req.get("name"));
        if (req.containsKey("cssName")) font.setCssName(req.get("cssName"));
        if (req.containsKey("fileUrl")) font.setFileUrl(req.get("fileUrl"));
        if (req.containsKey("fileSize")) font.setFileSize(req.get("fileSize"));
        if (req.containsKey("sortOrder")) {
            try {
                font.setSortOrder(Integer.parseInt(req.get("sortOrder")));
            } catch (Exception ignored) {}
        }
        Font saved = fontRepository.save(font);
        operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                "更新字体：" + font.getName(), null, getClientIp(null));
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        Font font = fontRepository.findById(id).orElse(null);
        if (font == null) {
            return ResponseEntity.ok(ApiResponse.error("字体不存在"));
        }
        fontRepository.delete(font);
        operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                "删除字体：" + font.getName(), null, getClientIp(null));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadFont(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "fileName", required = false) String displayName) {
        try {
            File dir = new File(FONT_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".ttf")
                    && !originalFilename.toLowerCase().endsWith(".otf")) {
                return ResponseEntity.ok(ApiResponse.error("请上传TTF或OTF格式的字体文件"));
            }

            String ext = originalFilename.substring(originalFilename.lastIndexOf("."));

            String safeName;
            if (displayName != null && !displayName.trim().isEmpty()) {
                safeName = displayName.trim() + ext.toLowerCase();
            } else {
                safeName = originalFilename;
            }
            safeName = safeName.replaceAll("[\\/:*?\"<>|]", "_");

            Path target = Paths.get(FONT_DIR + safeName);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            long size = file.getSize();
            String fileSizeStr;
            if (size < 1024 * 1024) fileSizeStr = (size / 1024) + " KB";
            else fileSizeStr = String.format("%.1f MB", size / (1024.0 * 1024.0));

            operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                    "上传字体文件：" + originalFilename, null, getClientIp(null));

            Map<String, String> result = Map.of(
                    "fileName", safeName,
                    "fileUrl", "/fonts/" + safeName,
                    "fileSize", fileSizeStr
            );
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("上传失败：" + e.getMessage()));
        }
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
        return ip;
    }
}
