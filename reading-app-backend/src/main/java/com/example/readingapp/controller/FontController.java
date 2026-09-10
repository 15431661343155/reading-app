package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Font;
import com.example.readingapp.repository.FontRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/fonts")
@CrossOrigin(origins = "*")
public class FontController {

    @Autowired
    private FontRepository fontRepository;

    @Value("${file.upload.font-path:uploads/fonts/}")
    private String fontPath;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Font>>> getFonts() {
        List<Font> fonts = fontRepository.findAllByOrderBySortOrderAsc();
        return ResponseEntity.ok(ApiResponse.success(fonts));
    }

    /**
     * 按字体 id 流式返回字体文件。
     * 通过 id 从磁盘读取真实文件，避免直接在 URL 中暴露中文文件名
     * （Tomcat/Spring 静态资源映射对中文路径解码会 400/500）。
     */
    @GetMapping("/file/{id}")
    public ResponseEntity<Resource> downloadFontFile(@PathVariable Long id) {
        Font font = fontRepository.findById(id).orElse(null);
        if (font == null || font.getFileUrl() == null || font.getFileUrl().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String fileUrl = font.getFileUrl();
        String fileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
        Path path = Paths.get(fontPath, fileName);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            return ResponseEntity.notFound().build();
        }
        try {
            Resource resource = new FileSystemResource(path.toFile());
            String contentType = determineContentType(fileName);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(Files.size(path))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String determineContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
}
