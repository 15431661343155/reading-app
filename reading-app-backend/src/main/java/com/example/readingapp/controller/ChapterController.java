package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.repository.ChapterRepository;
import com.example.readingapp.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/chapters")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChapterController {

    private final ChapterRepository chapterRepository;
    private final BookRepository bookRepository;
    private final OperationLogService operationLogService;

    // 获取某本书的所有章节
    @GetMapping("/book/{bookId}")
    public ApiResponse<List<Chapter>> getChaptersByBook(@PathVariable Long bookId) {
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        //chapters.forEach(c -> c.setContent(null));
        return ApiResponse.success(chapters);
    }

    // 获取某个章节的详细内容
    @GetMapping("/{id}")
    public ApiResponse<Chapter> getChapterById(@PathVariable Long id) {
        Chapter chapter = chapterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("章节不存在"));
        return ApiResponse.success(chapter);
    }

    // 统计所有章节字数
    @PostMapping("/recount/{bookId}")
    @Transactional
    public ApiResponse<String> recountWords(@PathVariable Long bookId) {
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        for (Chapter c : chapters) {
            if (c.getContent() != null) {
                // 去掉空格和换行后再计算
                String text = c.getContent().replaceAll("\\s+", "");
                c.setWordCount(text.length());
                chapterRepository.save(c);
            }
        }
        // 更新书籍的字数和章节数统计
        updateBookStats(bookId);
        return ApiResponse.success("字数重新统计完成", null);
    }

    // 获取某本书的指定章节
    @GetMapping("/book/{bookId}/order/{sortOrder}")
    public ApiResponse<Chapter> getChapterByOrder(
            @PathVariable Long bookId,
            @PathVariable Integer sortOrder) {
        Chapter chapter = chapterRepository.findByBookIdAndSortOrder(bookId, sortOrder)
                .orElseThrow(() -> new RuntimeException("章节不存在"));
        return ApiResponse.success(chapter);
    }

    // 获取章节总数
    @GetMapping("/count/{bookId}")
    public ApiResponse<Long> getChapterCount(@PathVariable Long bookId) {
        long count = chapterRepository.countByBookId(bookId);
        return ApiResponse.success(count);
    }

    // 获取某本书的最大排序序号
    @GetMapping("/max-sort-order/{bookId}")
    public ApiResponse<Integer> getMaxSortOrder(@PathVariable Long bookId) {
        Integer maxOrder = chapterRepository.findMaxSortOrderByBookId(bookId);
        return ApiResponse.success(maxOrder != null ? maxOrder : 0);
    }

    // 添加章节
    @PostMapping
    @Transactional
    public ApiResponse<Chapter> addChapter(@RequestBody Chapter chapter, HttpServletRequest request) {
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(chapter.getBookId());
        String bookTitle = bookRepository.findById(chapter.getBookId()).map(b -> b.getTitle()).orElse("未知书籍");

        // 根据标题自动匹配序号
        int targetOrder = chapters.size() + 1;
        int extractedNumber = extractChapterNumber(chapter.getTitle());
        if (extractedNumber > 0) {
            // 查找应该插入的位置
            int insertIndex = chapters.size();
            for (int i = 0; i < chapters.size(); i++) {
                if (getTitleNumber(chapters.get(i).getTitle()) > extractedNumber) {
                    insertIndex = i;
                    break;
                }
            }
            // 从插入位置开始，所有后续章节序号 +1
            for (int i = insertIndex; i < chapters.size(); i++) {
                Chapter c = chapters.get(i);
                c.setSortOrder(c.getSortOrder() + 1);
                c.setSortKey("1-" + String.format("%04d", c.getSortOrder()));
                chapterRepository.save(c);
            }

            targetOrder = insertIndex + 1;
        }
        String sortKey = "1-" + String.format("%04d", targetOrder);
        chapter.setSortOrder(targetOrder);
        chapter.setSortKey(sortKey);
        Chapter saved = chapterRepository.save(chapter);

        // 更新书籍字数和章节数统计
        updateBookStats(chapter.getBookId());

        operationLogService.logChapter(OperationLogService.TYPE_CHAPTER_ADD, 
            "为《" + bookTitle + "》添加章节：" + saved.getTitle() + "（第" + targetOrder + "章）", 
            saved.getId(), getClientIp(request));
        return ApiResponse.success("添加成功", saved);
    }
    private int getTitleNumber(String title) {
        if (title == null) return 0;
        Pattern p = Pattern.compile("第([\\u4e00-\\u9fa5\\d]+)章");
        Matcher m = p.matcher(title);
        if (m.find()) {
            String numStr = m.group(1);
            try {
                return Integer.parseInt(numStr);
            } catch (NumberFormatException e) {
                return convertChineseNumber(numStr);
            }
        }
        return 0;
    }

    // 更新章节
    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<Chapter> updateChapter(@PathVariable Long id, @RequestBody Chapter chapter, HttpServletRequest request) {
        Chapter existing = chapterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("章节不存在"));
        int oldOrder = existing.getSortOrder();
        int newOrder = chapter.getSortOrder();
        Long bookId = existing.getBookId();
        String oldTitle = existing.getTitle();
        String bookTitle = bookRepository.findById(bookId).map(b -> b.getTitle()).orElse("未知书籍");
        
        // 先更新基本字段
        existing.setTitle(chapter.getTitle());
        existing.setContent(chapter.getContent());
        existing.setSortOrder(newOrder);
        existing.setSortKey("1-" + String.format("%04d", newOrder));
        existing.setBookId(bookId);
        chapterRepository.save(existing);

        // 然后处理其他章节的序号冲突
        List<Chapter> allChapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);

        for (Chapter c : allChapters) {
            if (c.getId().equals(id)) continue;

            // 向前移（169 → 166）：中间的 166-168 都 +1
            if (newOrder < oldOrder && c.getSortOrder() >= newOrder && c.getSortOrder() <= oldOrder) {
                c.setSortOrder(c.getSortOrder() + 1);
                c.setSortKey("1-" + String.format("%04d", c.getSortOrder()));
                chapterRepository.save(c);
            }
        }
        
        // 记录日志
        String logDesc;
        if (!oldTitle.equals(chapter.getTitle())) {
            logDesc = "重命名《" + bookTitle + "》第" + oldOrder + "章：\"" + oldTitle + "\" → \"" + chapter.getTitle() + "\"";
            operationLogService.logChapter(OperationLogService.TYPE_CHAPTER_RENAME, logDesc, id, getClientIp(request));
        } else {
            logDesc = "修改《" + bookTitle + "》第" + oldOrder + "章（" + chapter.getTitle() + "）内容";
            operationLogService.logChapter(OperationLogService.TYPE_CHAPTER_UPDATE, logDesc, id, getClientIp(request));
        }

        // 更新书籍字数和章节数统计
        updateBookStats(bookId);

        return ApiResponse.success("更新成功", existing);
    }

    @PostMapping("/renumber/{bookId}")
    @Transactional
    public ApiResponse<String> renumberChapters(@PathVariable Long bookId, @RequestParam(defaultValue = "false") boolean layered) {
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);

        // 按 sortKey 排序
        chapters.sort((a, b) -> {
            int numA = getTitleNumber(a.getTitle());
            int numB = getTitleNumber(b.getTitle());
            return Integer.compare(numA, numB);
        });

        if (layered) {
            // 分层模式
            int majorLevel = 1;
            int minorIndex = 0;
            int prevNumber = -1;
            for (Chapter c : chapters) {
                int currentNum = getTitleNumber(c.getTitle());
                if (currentNum > 0 && prevNumber != -1 && currentNum <= prevNumber / 2) {
                    majorLevel++;
                    minorIndex = 1;
                } else {
                    minorIndex++;
                }
                prevNumber = currentNum;
                c.setSortKey(majorLevel + "-" + String.format("%04d", minorIndex));
                c.setSortOrder(minorIndex);
                chapterRepository.save(c);
            }
        } else {
            // 连续模式
            for (int i = 0; i < chapters.size(); i++) {
                Chapter c = chapters.get(i);
                c.setSortKey("1-" + String.format("%04d", i + 1));
                c.setSortOrder(i + 1);
                chapterRepository.save(c);
            }
        }

        return ApiResponse.success("序号已更新，共" + chapters.size() + "章", null);
    }

    // 删除章节
    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Void> deleteChapter(@PathVariable Long id, HttpServletRequest request) {
        Chapter chapter = chapterRepository.findById(id).orElse(null);
        if (chapter != null) {
            String bookTitle = bookRepository.findById(chapter.getBookId()).map(b -> b.getTitle()).orElse("未知书籍");
            String chapterTitle = chapter.getTitle();
            int sortOrder = chapter.getSortOrder();
            Long bookId = chapter.getBookId();

            chapterRepository.deleteById(id);

            // 更新书籍字数和章节数统计
            updateBookStats(bookId);

            operationLogService.logChapter(OperationLogService.TYPE_CHAPTER_DELETE, 
                "删除《" + bookTitle + "》第" + sortOrder + "章：" + chapterTitle, 
                id, getClientIp(request));
        }
        return ApiResponse.success("删除成功", null);
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

    // 重新排序章节
    @PostMapping("/reorder/{bookId}")
    @Transactional
    public ApiResponse<String> reorderChapters(@PathVariable Long bookId) {
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);

        // 按标题中的数字排序
        chapters.sort((a, b) -> {
            String keyA = a.getSortKey() != null ? a.getSortKey() : "";
            String keyB = b.getSortKey() != null ? b.getSortKey() : "";
            return keyA.compareTo(keyB);
        });

        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            c.setSortOrder(i + 1);
            chapterRepository.save(c);
        }
        return ApiResponse.success("章节序号已重新排列，共" + chapters.size() + "章", null);
    }
    private int extractChapterNumber(String title) {
        if (title == null) return Integer.MAX_VALUE;
        String numStr = title.replaceFirst("^第", "").replaceFirst("章.*$", "").trim();
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException ignored) {}
        return convertChineseNumber(numStr);
    }

    private int convertChineseNumber(String cn) {
        int result = 0;
        int temp = 0;
        for (char c : cn.toCharArray()) {
            switch (c) {
                case '零': break;
                case '一': temp += 1; break;
                case '二': temp += 2; break;
                case '三': temp += 3; break;
                case '四': temp += 4; break;
                case '五': temp += 5; break;
                case '六': temp += 6; break;
                case '七': temp += 7; break;
                case '八': temp += 8; break;
                case '九': temp += 9; break;
                case '十': temp = (temp == 0) ? 10 : temp * 10; break;
                case '百': temp *= 100; result += temp; temp = 0; break;
                case '千': temp *= 1000; result += temp; temp = 0; break;
            }
        }
        return result + temp;
    }

    /**
     * 更新书籍的字数和章节数统计
     */
    private void updateBookStats(Long bookId) {
        if (bookId == null) return;
        bookRepository.findById(bookId).ifPresent(book -> {
            List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
            int chapterCount = chapters.size();
            long wordCount = 0;
            for (Chapter c : chapters) {
                if (c.getContent() != null) {
                    // 去掉空格和换行后计算字数
                    String text = c.getContent().replaceAll("\\s+", "");
                    wordCount += text.length();
                }
            }
            book.setChapterCount(chapterCount);
            book.setWordCount((int) wordCount);
            bookRepository.save(book);
        });
    }
}
