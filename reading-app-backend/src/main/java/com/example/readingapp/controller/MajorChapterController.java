package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.MajorChapter;
import com.example.readingapp.repository.MajorChapterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/major-chapters")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MajorChapterController {

    private final MajorChapterRepository majorChapterRepository;

    // 获取某本书的所有大章节
    @GetMapping("/book/{bookId}")
    public ApiResponse<List<MajorChapter>> getByBookId(@PathVariable Long bookId) {
        return ApiResponse.success(majorChapterRepository.findByBookIdOrderBySortOrderAsc(bookId));
    }

    // 更新大章节名
    @PutMapping("/{id}")
    public ApiResponse<MajorChapter> updateTitle(@PathVariable Long id, @RequestBody MajorChapter mc) {
        MajorChapter existing = majorChapterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("大章节不存在"));
        existing.setTitle(mc.getTitle());
        return ApiResponse.success(majorChapterRepository.save(existing));
    }

    // 添加大章节
    @PostMapping
    public ApiResponse<MajorChapter> add(@RequestBody MajorChapter mc) {
        return ApiResponse.success(majorChapterRepository.save(mc));
    }

    // 删除大章节
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        majorChapterRepository.deleteById(id);
        return ApiResponse.success(null);
    }
}
