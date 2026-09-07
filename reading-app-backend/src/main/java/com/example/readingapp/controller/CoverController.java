package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cover")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CoverController {

    private final BookRepository bookRepository;

    @Value("${file.upload.cover-path:uploads/covers/}")
    private String coverPath;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "gif", "webp");

    @PostConstruct
    public void init() {
        File dir = new File(coverPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    // 上传封面
    @PostMapping("/upload/{bookId}")
    public ApiResponse<String> uploadCover(@PathVariable Long bookId,
                                           @RequestParam("file") MultipartFile file) {
        try {
            // 验证书籍是否存在
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("书籍不存在"));

            // 验证文件
            if (file == null || file.isEmpty()) {
                return ApiResponse.error("请选择要上传的文件");
            }

            // 验证文件类型
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !originalFilename.contains(".")) {
                return ApiResponse.error("无效的文件名");
            }

            String ext = getFileExtension(originalFilename).toLowerCase();
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                return ApiResponse.error("不支持的文件格式，支持的格式：jpg, jpeg, png, gif, webp");
            }

            // 验证文件大小（最大5MB）
            if (file.getSize() > 5 * 1024 * 1024) {
                return ApiResponse.error("文件大小不能超过5MB");
            }

            // 确保目录存在
            File dir = new File(coverPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 生成唯一文件名
            String fileName = UUID.randomUUID().toString() + "." + ext;
            Path filePath = Paths.get(coverPath, fileName);
            Files.write(filePath, file.getBytes());

            // 删除旧封面
            deleteOldCover(book);

            // 更新书籍封面 URL
            String coverUrl = "/covers/" + fileName;
            book.setCover(coverUrl);
            bookRepository.save(book);

            return ApiResponse.success("封面上传成功", coverUrl);
        } catch (IOException e) {
            return ApiResponse.error("上传失败: " + e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 删除封面
    @DeleteMapping("/{bookId}")
    public ApiResponse<String> deleteCover(@PathVariable Long bookId) {
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("书籍不存在"));

            deleteOldCover(book);

            book.setCover(null);
            bookRepository.save(book);
            return ApiResponse.success("封面已删除", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    private void deleteOldCover(Book book) {
        if (book.getCover() != null) {
            try {
                String fileName = book.getCover().replace("/covers/", "");
                File file = new File(coverPath + fileName);
                if (file.exists()) {
                    file.delete();
                }
            } catch (Exception e) {
                // 忽略删除旧文件的错误
            }
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "jpg";
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }
}