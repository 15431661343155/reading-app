package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.BookCategory;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.repository.*;
import com.example.readingapp.service.BookService;
import com.example.readingapp.service.OperationLogService;
import com.example.readingapp.util.BookCategories;
import com.example.readingapp.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BookController {

    private final BookService bookService;
    private final BookRepository bookRepository;
    private final BookCategoryRepository bookCategoryRepository;
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

    // 分页获取所有书籍（管理后台用：支持关键词 / 主分类 / 子分类 / 状态筛选）
    @GetMapping
    public ApiResponse<Page<Book>> getAllBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> subCategory,
            @RequestParam(required = false) Integer status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sort).descending());
        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
        boolean hasCategory = category != null && !category.trim().isEmpty();
        List<String> subs = new ArrayList<>();
        if (subCategory != null) {
            for (String s : subCategory) {
                if (s != null && !s.trim().isEmpty()) {
                    subs.add(s.trim());
                }
            }
        }
        boolean hasSub = !subs.isEmpty();
        // 无任何筛选条件时走原查询，保证老调用方行为完全不变
        if (!hasKeyword && !hasCategory && !hasSub && status == null) {
            return ApiResponse.success(bookService.findAll(pageable));
        }
        String kw = hasKeyword ? keyword.trim().toLowerCase() : null;
        String cat = hasCategory ? category.trim() : null;
        Specification<Book> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (kw != null) {
                String like = "%" + kw + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("author")), like)));
            }
            if (cat != null) {
                ps.add(cb.equal(root.get("category"), cat));
            }
            if (hasSub) {
                // 多选子分类 = 命中任意一个（OR）
                List<Predicate> subPs = new ArrayList<>();
                for (String s : subs) {
                    subPs.add(cb.like(root.get("subCategories"), subLike(s)));
                }
                ps.add(cb.or(subPs.toArray(new Predicate[0])));
            }
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return ApiResponse.success(bookRepository.findAll(spec, pageable));
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

    /**
     * 书籍列表（书城「本站藏书」用，排除公版标记）。
     *
     * <p>筛选参数：
     * <ul>
     *   <li>{@code mainCategory} —— 主分类（男生 / 女生 / 后台自定义）。命中口径＝
     *       {@code category} 精确匹配 <b>或</b> 书籍子分类命中该主分类名下的任一子分类
     *       （历史上存在「只填了子分类、没填主分类」的书，只按 category 精确匹配会让
     *       这些书在整个主分类页里查不到）</li>
     *   <li>{@code subCategory} —— 子分类（多选中的一个），按 JSON 数组精确命中</li>
     *   <li>{@code category} —— <b>兼容老客户端</b>：同时匹配「主分类」与「子分类」。
     *       改造前 category 列存的是子分类语义（玄幻/都市…），老版本 App 仍会用它做二级分类 Tab；
     *       若只按主分类匹配，老客户端所有分类 Tab 都会查空，故此处做并集匹配。</li>
     *   <li>{@code sortBy} —— viewCount(按阅读量) / hot(阅读量+点赞) / createdAt(最新导入) / id 等</li>
     * </ul>
     *
     * <p>公版标记：<b>不带任何分类筛选时</b>排除公版书（默认书城列表只收非公版）；
     * 一旦按分类筛选，就以管理员的分类归属为准（后台把公版书归到某个分类下就应当能查到）。
     */
    @GetMapping("/fiction")
    public ApiResponse<Page<Book>> getFictionBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String mainCategory,
            @RequestParam(required = false) String subCategory,
            @RequestParam(required = false) String sortBy) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? 10 : Math.min(size, 100),
                fictionSort(sortBy));
        String main = trimToNull(mainCategory);
        String sub = trimToNull(subCategory);
        String legacy = trimToNull(category);

        // 主分类名 → 其名下子分类名（提前查好，Specification 里只做拼装）
        final List<String> mainSubs = main == null ? new ArrayList<>() : subsOfMain(main);
        final boolean categoryFiltered = (main != null || sub != null || legacy != null);

        Specification<Book> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            // 排除公版书；publicDomain 为 NULL 的历史数据按「非公版」处理。
            // 按分类筛选时不做此排除：后台已明确归类的书，就算标了公版也要能在该分类下查到。
            if (!categoryFiltered) {
                ps.add(cb.or(cb.isFalse(root.get("publicDomain")), cb.isNull(root.get("publicDomain"))));
            }
            if (main != null) {
                Predicate byMain = cb.equal(root.get("category"), main);
                if (!mainSubs.isEmpty()) {
                    List<Predicate> subHits = new ArrayList<>();
                    for (String s : mainSubs) {
                        subHits.add(cb.like(root.get("subCategories"), subLike(s)));
                    }
                    byMain = cb.or(byMain, cb.or(subHits.toArray(new Predicate[0])));
                }
                ps.add(byMain);
            }
            if (sub != null) {
                ps.add(cb.like(root.get("subCategories"), subLike(sub)));
            }
            if (legacy != null) {
                ps.add(cb.or(cb.equal(root.get("category"), legacy),
                        cb.like(root.get("subCategories"), subLike(legacy))));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Book> books = bookRepository.findAll(spec, pageable);

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null) {
            books.forEach(book -> {
                boolean inShelf = bookshelfRepository.existsByUserIdAndBookId(userId, book.getId());
                book.setIsInShelf(inShelf);
            });
        }
        return ApiResponse.success(books);
    }

    /**
     * 分类树（书城子页面 + 后台级联选择框的唯一数据源）：
     * 主分类列表（含各自子分类）+ 书库里出现过但不在分类表中的子分类名（后台「添加新分类」的候选）。
     * 书城页的「新书」不是分类，是前端固定加的虚拟页，故不出现在这里。
     */
    @GetMapping("/category-tree")
    public ApiResponse<CategoryTree> getCategoryTree() {
        CategoryTree tree = new CategoryTree();
        List<CategoryNode> mains = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        for (BookCategory main : bookCategoryRepository.findByParentIdIsNullOrderBySortOrderAscIdAsc()) {
            CategoryNode node = new CategoryNode();
            node.setId(main.getId());
            node.setName(main.getName());
            List<String> subs = new ArrayList<>();
            for (BookCategory child : bookCategoryRepository.findByParentIdOrderBySortOrderAscIdAsc(main.getId())) {
                subs.add(child.getName());
                known.add(child.getName());
            }
            node.setSubs(subs);
            mains.add(node);
        }
        tree.setMains(mains);

        // 书库里出现过的子分类（老数据迁移后可能还没有归入分类表）→ 作为候选项回给后台
        Set<String> librarySubs = new LinkedHashSet<>();
        for (String raw : bookRepository.findAllSubCategoriesRaw()) {
            for (String name : BookCategories.parse(raw)) {
                if (!known.contains(name)) {
                    librarySubs.add(name);
                }
            }
        }
        tree.setLibrarySubs(new ArrayList<>(librarySubs));
        return ApiResponse.success(tree);
    }

    /** 子分类排序：默认按阅读量/热度，createdAt = 最新导入时间 */
    private Sort fictionSort(String sortBy) {
        String s = sortBy == null ? "" : sortBy.trim();
        switch (s) {
            case "hot":
                // 热度：阅读量为主、点赞为辅（书城「本站藏书」与分类书单用）
                return Sort.by(Sort.Direction.DESC, "viewCount")
                        .and(Sort.by(Sort.Direction.DESC, "likeCount"))
                        .and(Sort.by(Sort.Direction.DESC, "id"));
            case "viewCount":
                return Sort.by(Sort.Direction.DESC, "viewCount").and(Sort.by(Sort.Direction.DESC, "id"));
            case "createdAt":
            case "new":
                return Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
            case "updatedAt":
                return Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "id"));
            case "likeCount":
                return Sort.by(Sort.Direction.DESC, "likeCount").and(Sort.by(Sort.Direction.DESC, "id"));
            case "wordCount":
                return Sort.by(Sort.Direction.DESC, "wordCount").and(Sort.by(Sort.Direction.DESC, "id"));
            default:
                return Sort.by(Sort.Direction.DESC, "id");
        }
    }

    /** 子分类命中：存储是 JSON 数组串，按 "名" 精确匹配，转义 LIKE 通配符 */
    private String subLike(String name) {
        return "%\"" + name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "\"%";
    }

    /**
     * 主分类名 → 其名下子分类名列表。
     *
     * <p>用于主分类筛选的兜底命中：历史数据里存在「只写了子分类、主分类列为空」的书，
     * 只按 {@code category} 精确匹配会让这些书在主分类页里全部查不到。
     * 分类表里没有这个主分类名时返回空表（此时退化为只按 category 匹配）。
     */
    private List<String> subsOfMain(String mainName) {
        List<String> subs = new ArrayList<>();
        if (mainName == null) {
            return subs;
        }
        for (BookCategory main : bookCategoryRepository.findByParentIdIsNullOrderBySortOrderAscIdAsc()) {
            if (!mainName.equals(main.getName())) {
                continue;
            }
            for (BookCategory child : bookCategoryRepository.findByParentIdOrderBySortOrderAscIdAsc(main.getId())) {
                String n = child.getName() == null ? "" : child.getName().trim();
                if (!n.isEmpty()) {
                    subs.add(n);
                }
            }
            break;
        }
        return subs;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    @lombok.Data
    public static class CategoryNode {
        private Long id;
        private String name;
        private List<String> subs;
    }

    @lombok.Data
    public static class CategoryTree {
        private List<CategoryNode> mains;
        /** 书库里出现过、但未登记进分类表的子分类名 */
        private List<String> librarySubs;
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
        // 分类统一规范化：主分类去空白，子分类转成规范 JSON 数组串（null/脏值一律兜住）
        book.setCategory(book.getCategory() == null ? "" : book.getCategory().trim());
        book.setSubCategories(BookCategories.toStorage(book.getSubCategories()));
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
        existing.setCategory(book.getCategory() == null ? "" : book.getCategory().trim());
        existing.setSubCategories(BookCategories.toStorage(book.getSubCategories()));
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

    // 批量更新分类（主分类 + 子分类多选）
    @PutMapping("/batch-category")
    public ApiResponse<Integer> batchUpdateCategory(@RequestBody BatchCategoryRequest req, HttpServletRequest request) {
        try {
            // 只带子分类、不带主分类时也允许（保留原主分类），但两者都空则视为无效
            boolean hasMain = req.getCategory() != null && !req.getCategory().trim().isEmpty();
            if (!hasMain && (req.getSubCategories() == null || req.getSubCategories().trim().isEmpty())) {
                return ApiResponse.error("请至少选择主分类或子分类");
            }
            bookService.batchUpdateCategory(req.getIds(), req.getCategory(), req.getSubCategories());
            String desc = (hasMain ? "主分类「" + req.getCategory().trim() + "」" : "原主分类不变")
                    + " / 子分类「" + BookCategories.joinForDisplay(req.getSubCategories()) + "」";
            operationLogService.logBook(OperationLogService.TYPE_BOOK_UPDATE,
                "批量更新 " + req.getIds().size() + " 本书分类为 " + desc, null, getClientIp(request));
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
        /** 主分类名（可空：为空时保留各书原主分类） */
        private String category;
        /** 子分类（JSON 数组串或逗号分隔，可空＝清空子分类） */
        private String subCategories;
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