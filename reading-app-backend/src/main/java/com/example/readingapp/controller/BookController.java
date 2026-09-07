package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.repository.*;
import com.example.readingapp.service.BookService;
import com.example.readingapp.service.OperationLogService;
import com.example.readingapp.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BookController {

    private final BookService bookService;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final MajorChapterRepository majorChapterRepository;
    private final BookshelfRepository bookshelfRepository;
    private final ReadingProgressRepository readingProgressRepository;
    private final BookmarkRepository bookmarkRepository;
    private final OperationLogService operationLogService;

    // 获取书籍详情
    @GetMapping("/{id}")
    public ApiResponse<Book> getBookById(@PathVariable Long id,
                                         @RequestParam(required = false) Long userId) {
        try {
            Book book = bookService.findById(id);
            Long currentUserId = SecurityUtils.getCurrentUserId();
            // token 不可用（过期/缺失）时回退到前端传入的 userId，
            // 与加入/移出书架使用同一套 userId，保证书架状态可靠刷新
            if (currentUserId == null && userId != null) {
                currentUserId = userId;
            }
            if (currentUserId != null) {
                boolean inShelf = bookshelfRepository.existsByUserIdAndBookId(currentUserId, id);
                book.setIsInShelf(inShelf);
            }
            return ApiResponse.success(book);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
    @GetMapping("/{id}/wordCount")
    public ApiResponse<Integer> getBookWordCount(@PathVariable Long id) {
        // 直接读取数据库中已保存的字数（在添加/修改/删除章节时会自动更新）
        return bookRepository.findById(id)
                .map(book -> ApiResponse.success(book.getWordCount() != null ? book.getWordCount() : 0))
                .orElse(ApiResponse.success(0));
    }

    // 获取某本书的最后一章信息（用于"连载至"显示，O(1) 查询）
    @GetMapping("/{id}/latest-chapter")
    public ApiResponse<Chapter> getLatestChapter(@PathVariable Long id) {
        List<Chapter> chapters = chapterRepository.findLastChapterByBookId(id);
        if (chapters.isEmpty()) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(chapters.get(0));
    }

    // 分页获取所有书籍
    @GetMapping
    public ApiResponse<Page<Book>> getAllBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sort) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sort).descending());
        Page<Book> books = bookService.findAll(pageable);
        return ApiResponse.success(books);
    }

    // 根据分类获取书籍
    @GetMapping("/category/{category}")
    public ApiResponse<Page<Book>> getBooksByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Book> books = bookService.findByCategory(category, pageable);
        return ApiResponse.success(books);
    }

    // 搜索书籍
    @GetMapping("/search")
    public ApiResponse<Page<Book>> searchBooks(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Book> books = bookService.searchBooks(keyword, pageable);
        return ApiResponse.success(books);
    }

    // 书籍列表（小说等，原"小说"接口，排除公版标记）
    @GetMapping("/fiction")
    public ApiResponse<Page<Book>> getFictionBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        Page<Book> books;
        if (category != null && !category.isEmpty()) {
            books = bookRepository.findByPublicDomainFalseAndCategory(category, pageable);
        } else {
            books = bookRepository.findByPublicDomainFalse(pageable);
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null) {
            books.forEach(book -> {
                boolean inShelf = bookshelfRepository.existsByUserIdAndBookId(userId, book.getId());
                book.setIsInShelf(inShelf);
            });
        }
        return ApiResponse.success(books);
    }

    // 书籍分类列表（原"小说"分类，排除公版标记）
    @GetMapping("/fiction/categories")
    public ApiResponse<List<String>> getFictionCategories() {
        try {
            List<String> categories = bookRepository.findDistinctFictionCategories();
            return ApiResponse.success(categories);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 热门书籍
    @GetMapping("/hot")
    public ApiResponse<List<Book>> getHotBooks() {
        List<Book> books = bookService.getHotBooks();
        return ApiResponse.success(books);
    }

    // 最新上架
    @GetMapping("/new")
    public ApiResponse<List<Book>> getNewBooks() {
        List<Book> books = bookService.getNewBooks();
        return ApiResponse.success(books);
    }

    // 增加阅读量
    @PostMapping("/{id}/view")
    public ApiResponse<Void> incrementViewCount(@PathVariable Long id) {
        try {
            bookService.incrementViewCount(id);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // 添加书籍
    @PostMapping
    public ApiResponse<Book> addBook(@RequestBody Book book, HttpServletRequest request) {
        Book saved = bookService.save(book);
        operationLogService.logBook(OperationLogService.TYPE_BOOK_ADD, 
            "添加书籍：《" + saved.getTitle() + "》", saved.getId(), getClientIp(request));
        return ApiResponse.success(saved);
    }

    // 更新书籍
    @PutMapping("/{id}")
    public ApiResponse<Book> updateBook(@PathVariable Long id, @RequestBody Book book, HttpServletRequest request) {
        Book existing = bookService.findById(id);
        String oldTitle = existing.getTitle();
        existing.setTitle(book.getTitle());
        existing.setAuthor(book.getAuthor());
        existing.setIntro(book.getIntro());
        existing.setCategory(book.getCategory());
        existing.setStatus(book.getStatus());
        Book saved = bookService.save(existing);
        operationLogService.logBook(OperationLogService.TYPE_BOOK_UPDATE, 
            "更新书籍：《" + saved.getTitle() + "》", saved.getId(), getClientIp(request));
        return ApiResponse.success(saved);
    }

    // 删除书籍
    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Void> deleteBook(@PathVariable Long id, HttpServletRequest request) {
        try {
            Book book = bookService.findById(id);
            String bookTitle = book.getTitle();
            
            // 1. 删除该书的所有章节
            chapterRepository.deleteByBookId(id);

            // 2. 删除该书的所有书架记录
            bookshelfRepository.deleteByBookId(id);

            // 3. 删除该书的所有阅读进度
            readingProgressRepository.deleteByBookId(id);

            // 4. 删除该书的所有书签
            bookmarkRepository.deleteByBookId(id);

            // 5. 删除该书的大章节记录
            majorChapterRepository.deleteByBookId(id);

            // 6. 最后删除书籍本身
            bookService.delete(id);
            
            operationLogService.logBook(OperationLogService.TYPE_BOOK_DELETE, 
                "删除书籍：《" + bookTitle + "》", id, getClientIp(request));

            return ApiResponse.success("书籍及相关数据已删除", null);
        } catch (Exception e) {
            return ApiResponse.error("删除失败: " + e.getMessage());
        }
    }

    // 批量删除书籍
    @PostMapping("/batch-delete")
    @Transactional
    public ApiResponse<Integer> batchDeleteBooks(@RequestBody List<Long> ids, HttpServletRequest request) {
        try {
            int count = ids.size();
            bookService.batchDelete(ids);
            operationLogService.logBook(OperationLogService.TYPE_BOOK_DELETE,
                "批量删除 " + count + " 本书籍", null, getClientIp(request));
            return ApiResponse.success(count);
        } catch (Exception e) {
            return ApiResponse.error("批量删除失败: " + e.getMessage());
        }
    }

    // 批量更新状态
    @PutMapping("/batch-status")
    public ApiResponse<Integer> batchUpdateStatus(@RequestBody BatchStatusRequest req, HttpServletRequest request) {
        try {
            bookService.batchUpdateStatus(req.getIds(), req.getStatus());
            operationLogService.logBook(OperationLogService.TYPE_BOOK_UPDATE,
                "批量更新 " + req.getIds().size() + " 本书状态为" + (req.getStatus() == 1 ? "已完结" : "连载中"), null, getClientIp(request));
            return ApiResponse.success(req.getIds().size());
        } catch (Exception e) {
            return ApiResponse.error("批量更新状态失败: " + e.getMessage());
        }
    }

    // 批量更新分类
    @PutMapping("/batch-category")
    public ApiResponse<Integer> batchUpdateCategory(@RequestBody BatchCategoryRequest req, HttpServletRequest request) {
        try {
            bookService.batchUpdateCategory(req.getIds(), req.getCategory());
            operationLogService.logBook(OperationLogService.TYPE_BOOK_UPDATE,
                "批量更新 " + req.getIds().size() + " 本书分类为「" + req.getCategory() + "」", null, getClientIp(request));
            return ApiResponse.success(req.getIds().size());
        } catch (Exception e) {
            return ApiResponse.error("批量更新分类失败: " + e.getMessage());
        }
    }

    @lombok.Data
    public static class BatchStatusRequest {
        private List<Long> ids;
        private Integer status;
    }

    @lombok.Data
    public static class BatchCategoryRequest {
        private List<Long> ids;
        private String category;
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