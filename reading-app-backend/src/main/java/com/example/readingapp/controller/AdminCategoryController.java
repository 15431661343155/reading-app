package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.BookCategory;
import com.example.readingapp.repository.BookCategoryRepository;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.service.OperationLogService;
import com.example.readingapp.util.BookCategories;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 书籍分类维护（管理后台）：新增 / 重命名 / 删除。
 *
 * <p>分类树同时是书城页「本站藏书」子页面与后台级联选择框的数据源，所以改名与删除
 * 必须级联同步书籍归属（{@code book.category} / {@code book.sub_categories}），
 * 否则会留下「分类树里没有、书上还挂着」的孤儿分类。级联口径：
 * <ul>
 *   <li>主分类改名 → 批量改 book.category（子分类名不受影响）</li>
 *   <li>子分类改名 → 重写受影响书籍的 sub_categories（JSON 数组串，精确换名）</li>
 *   <li>删除主分类 → 连带删掉它的子分类；书籍主分类置空并从子分类里摘掉这些子分类</li>
 *   <li>删除子分类 → 只从受影响书籍的子分类里摘掉，书籍与主分类不动</li>
 * </ul>
 *
 * <p>删除会让书籍失去分类归属（书籍本身保留，只是不再出现在对应书城子页），属有损操作：
 * 前端先查 {@code /{id}/impact} 拿影响面、用户确认后再带 {@code force=true} 调删除；
 * 没带 force 且确实有书在用则直接拒绝。
 *
 * <p>查重必须在 Java 侧做：MySQL 唯一索引不约束 NULL，
 * 同名主分类在库层面拦不住（见 BookCategory 注释）。
 */
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final BookCategoryRepository bookCategoryRepository;
    private final BookRepository bookRepository;
    private final OperationLogService operationLogService;

    /** 新增分类：不带 parentId → 新增主分类；带 parentId → 在該主分类下新增子分类 */
    @PostMapping
    public ApiResponse<BookCategory> addCategory(@RequestBody AddCategoryRequest req,
                                                 HttpServletRequest request) {
        String name = sanitize(req.getName());
        if (name.isEmpty()) {
            return ApiResponse.error("分类名不能为空");
        }
        if (name.length() > BookCategories.MAX_NAME_LEN) {
            return ApiResponse.error("分类名不能超过 " + BookCategories.MAX_NAME_LEN + " 个字符");
        }

        Long parentId = req.getParentId();
        if (parentId == null && req.getParent() != null && !req.getParent().trim().isEmpty()) {
            // 允许前端只传主分类名
            for (BookCategory c : bookCategoryRepository.findByParentIdIsNullOrderBySortOrderAscIdAsc()) {
                if (c.getName().equals(req.getParent().trim())) {
                    parentId = c.getId();
                    break;
                }
            }
            if (parentId == null) {
                return ApiResponse.error("主分类「" + req.getParent().trim() + "」不存在");
            }
        }

        if (parentId == null) {
            if (bookCategoryRepository.existsByNameAndParentIdIsNull(name)) {
                return ApiResponse.error("主分类「" + name + "」已存在");
            }
            BookCategory main = new BookCategory();
            main.setName(name);
            main.setParentId(null);
            main.setSortOrder(nextOrder(bookCategoryRepository.findByParentIdIsNullOrderBySortOrderAscIdAsc()));
            BookCategory saved = bookCategoryRepository.save(main);
            operationLogService.logBook(OperationLogService.TYPE_BOOK_UPDATE,
                    "新增主分类：「" + name + "」", null, getClientIp(request));
            return ApiResponse.success(saved);
        }

        BookCategory parent = bookCategoryRepository.findById(parentId).orElse(null);
        if (parent == null || parent.getParentId() != null) {
            return ApiResponse.error("指定的主分类不存在");
        }
        if (bookCategoryRepository.existsByNameAndParentId(name, parentId)) {
            return ApiResponse.error("主分类「" + parent.getName() + "」下已有子分类「" + name + "」");
        }
        // 同名子分类若已挂在别的主分类下，提示一下（不阻断：不同大类重名是合理的）
        BookCategory child = new BookCategory();
        child.setName(name);
        child.setParentId(parentId);
        child.setSortOrder(nextOrder(bookCategoryRepository.findByParentIdOrderBySortOrderAscIdAsc(parentId)));
        BookCategory saved = bookCategoryRepository.save(child);
        operationLogService.logBook(OperationLogService.TYPE_BOOK_UPDATE,
                "新增子分类：「" + parent.getName() + " / " + name + "」", null, getClientIp(request));
        return ApiResponse.success(saved);
    }

    /* ==================== 分类管理页（增 / 改 / 删） ==================== */

    /** 分类总览：主分类 + 各自子分类 + 书籍数，另附库中出现过但未登记的子分类 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        List<Map<String, Object>> mains = new ArrayList<>();
        Set<String> registered = new LinkedHashSet<>();
        for (BookCategory main : bookCategoryRepository.findByParentIdIsNullOrderBySortOrderAscIdAsc()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", main.getId());
            node.put("name", main.getName());
            node.put("bookCount", bookRepository.countByCategory(main.getName()));
            List<Map<String, Object>> subs = new ArrayList<>();
            for (BookCategory child : bookCategoryRepository.findByParentIdOrderBySortOrderAscIdAsc(main.getId())) {
                subs.add(subNode(child.getId(), child.getName()));
                registered.add(child.getName());
            }
            node.put("subs", subs);
            mains.add(node);
        }

        List<Map<String, Object>> unregistered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : bookRepository.findAllSubCategoriesRaw()) {
            for (String name : BookCategories.parse(raw)) {
                String v = name == null ? "" : name.trim();
                if (v.isEmpty() || registered.contains(v) || !seen.add(v)) {
                    continue;
                }
                unregistered.add(subNode(null, v));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mains", mains);
        data.put("unregisteredSubs", unregistered);
        data.put("totalBooks", bookRepository.count());
        data.put("uncategorizedBooks", bookRepository.countUncategorized());
        return ApiResponse.success(data);
    }

    /** 删除前的影响面：会让多少本书失去该分类（前端据此做二次确认） */
    @GetMapping("/{id}/impact")
    public ApiResponse<Map<String, Object>> impact(@PathVariable Long id) {
        BookCategory c = bookCategoryRepository.findById(id).orElse(null);
        if (c == null) {
            return ApiResponse.error("分类不存在或已被删除");
        }
        boolean isMain = c.getParentId() == null;
        List<String> subNames = new ArrayList<>();
        if (isMain) {
            for (BookCategory child : bookCategoryRepository.findByParentId(id)) {
                subNames.add(child.getName());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", c.getId());
        data.put("name", c.getName());
        data.put("main", isMain);
        data.put("subCount", subNames.size());
        data.put("subNames", subNames);
        data.put("bookCount", affectedBookIds(c.getName(), isMain, subNames).size());
        return ApiResponse.success(data);
    }

    /** 重命名（主分类 / 子分类通用），并级联同步书籍归属 */
    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> rename(@PathVariable Long id,
                                                   @RequestBody AddCategoryRequest req,
                                                   HttpServletRequest request) {
        BookCategory c = bookCategoryRepository.findById(id).orElse(null);
        if (c == null) {
            return ApiResponse.error("分类不存在或已被删除");
        }
        String name = sanitize(req.getName());
        if (name.isEmpty()) {
            return ApiResponse.error("分类名不能为空");
        }
        if (name.length() > BookCategories.MAX_NAME_LEN) {
            return ApiResponse.error("分类名不能超过 " + BookCategories.MAX_NAME_LEN + " 个字符");
        }
        String oldName = c.getName();
        boolean isMain = c.getParentId() == null;
        if (oldName.equals(name)) {
            Map<String, Object> same = new LinkedHashMap<>();
            same.put("changed", false);
            same.put("name", name);
            same.put("bookCount", 0);
            return ApiResponse.success("分类名未变化", same);
        }
        // 同级查重：改名后不能与同级已有分类撞名
        if (isMain ? bookCategoryRepository.existsByNameAndParentIdIsNull(name)
                : bookCategoryRepository.existsByNameAndParentId(name, c.getParentId())) {
            return ApiResponse.error(isMain ? "主分类「" + name + "」已存在"
                    : "同一主分类下已有子分类「" + name + "」");
        }

        c.setName(name);
        bookCategoryRepository.save(c);

        int affected = isMain ? renameMainInBooks(oldName, name) : renameSubInBooks(oldName, name);

        operationLogService.logBook(OperationLogService.TYPE_BOOK_UPDATE,
                (isMain ? "重命名主分类：" : "重命名子分类：")
                        + "「" + oldName + "」→「" + name + "」（同步 " + affected + " 本书）",
                null, getClientIp(request));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("changed", true);
        data.put("name", name);
        data.put("oldName", oldName);
        data.put("bookCount", affected);
        return ApiResponse.success("已重命名为「" + name + "」，同步更新 " + affected + " 本书", data);
    }

    /**
     * 删除分类（连带清掉书籍上的归属）。主分类有书在用时会一并删掉它的子分类。
     *
     * <p>有书在用且未带 {@code force=true} 时不删，返回 409 + 影响面，让前端确认后重试。
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id,
                                                   @RequestParam(value = "force", defaultValue = "false") boolean force,
                                                   HttpServletRequest request) {
        BookCategory c = bookCategoryRepository.findById(id).orElse(null);
        if (c == null) {
            return ApiResponse.error("分类不存在或已被删除");
        }
        boolean isMain = c.getParentId() == null;
        List<String> childNames = new ArrayList<>();
        List<BookCategory> children = new ArrayList<>();
        if (isMain) {
            children = bookCategoryRepository.findByParentId(id);
            for (BookCategory child : children) {
                childNames.add(child.getName());
            }
        }
        // 要从书上摘掉的子分类名：主分类 → 它的全部子分类；子分类 → 它自己
        List<String> removeNames = new ArrayList<>(childNames);
        if (!isMain) {
            removeNames.add(c.getName());
        }

        Set<Long> affected = affectedBookIds(c.getName(), isMain, childNames);
        if (!force && !affected.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", c.getName());
            data.put("bookCount", affected.size());
            data.put("subCount", childNames.size());
            return new ApiResponse<>(409, false,
                    "「" + c.getName() + "」正被 " + affected.size() + " 本书使用，删除后这些书会失去该分类"
                            + (isMain && !childNames.isEmpty() ? "，其下 " + childNames.size() + " 个子分类会一并删除" : "")
                            + "。确认删除请再次提交。", data);
        }

        // 一次把受影响的书全部改完再落库：只用实体更新，避免 bulk UPDATE 与脏实体回写互相覆盖
        Map<Long, Book> touched = new LinkedHashMap<>();
        if (isMain) {
            for (Book b : bookRepository.findByCategory(c.getName())) {
                b.setCategory("");
                touched.put(b.getId(), b);
            }
        }
        for (String n : removeNames) {
            for (Book b : bookRepository.findBySubCategoriesContaining(quoted(n))) {
                touched.put(b.getId(), b);
            }
        }
        List<Book> changed = new ArrayList<>();
        for (Book b : touched.values()) {
            List<String> subs = new ArrayList<>(BookCategories.parse(b.getSubCategories()));
            boolean hit = false;
            for (String n : removeNames) {
                if (subs.remove(n)) {
                    hit = true;
                }
            }
            if (hit) {
                b.setSubCategories(BookCategories.toJson(subs));
                changed.add(b);
            }
        }
        if (!changed.isEmpty()) {
            bookRepository.saveAll(changed);
        }

        if (isMain && !children.isEmpty()) {
            bookCategoryRepository.deleteByParentId(id);
        }
        bookCategoryRepository.delete(c);

        operationLogService.logBook(OperationLogService.TYPE_BOOK_UPDATE,
                (isMain ? "删除主分类：" : "删除子分类：") + "「" + c.getName() + "」"
                        + (isMain && !children.isEmpty() ? "（连带 " + children.size() + " 个子分类）" : "")
                        + "，解除 " + affected.size() + " 本书的分类归属",
                null, getClientIp(request));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", c.getName());
        data.put("main", isMain);
        data.put("subCount", childNames.size());
        data.put("bookCount", affected.size());
        return ApiResponse.success("已删除「" + c.getName() + "」"
                + (isMain && !childNames.isEmpty() ? "及其 " + childNames.size() + " 个子分类" : "")
                + "，解除 " + affected.size() + " 本书的分类归属", data);
    }

    /** 主分类改名：批量改书籍的 category（子分类名不变，所以不用动 sub_categories） */
    private int renameMainInBooks(String oldName, String newName) {
        List<Book> books = bookRepository.findByCategory(oldName);
        if (books.isEmpty()) {
            return 0;
        }
        for (Book b : books) {
            b.setCategory(newName);
        }
        bookRepository.saveAll(books);
        return books.size();
    }

    /** 子分类改名：把书籍 sub_categories 里的旧名换成新名（保持原位置；撞名则只去重） */
    private int renameSubInBooks(String oldName, String newName) {
        List<Book> books = bookRepository.findBySubCategoriesContaining(quoted(oldName));
        List<Book> changed = new ArrayList<>();
        for (Book b : books) {
            List<String> subs = new ArrayList<>(BookCategories.parse(b.getSubCategories()));
            int idx = subs.indexOf(oldName);
            if (idx < 0) {
                continue;
            }
            if (subs.contains(newName)) {
                subs.remove(idx);
            } else {
                subs.set(idx, newName);
            }
            b.setSubCategories(BookCategories.toJson(subs));
            changed.add(b);
        }
        if (!changed.isEmpty()) {
            bookRepository.saveAll(changed);
        }
        return changed.size();
    }

    /** 删除前的影响面：主分类 = 挂在主分类上的书 ∪ 挂在其任一子分类上的书 */
    private Set<Long> affectedBookIds(String mainName, boolean isMain, List<String> childNames) {
        Set<Long> ids = new LinkedHashSet<>();
        if (isMain) {
            for (Book b : bookRepository.findByCategory(mainName)) {
                ids.add(b.getId());
            }
        }
        for (String n : childNames) {
            for (Book b : bookRepository.findBySubCategoriesContaining(quoted(n))) {
                ids.add(b.getId());
            }
        }
        if (!isMain) {
            for (Book b : bookRepository.findBySubCategoriesContaining(quoted(mainName))) {
                ids.add(b.getId());
            }
        }
        return ids;
    }

    /** 分类节点（含使用量）：使用量现算，保证改完名/删完立刻是新数字 */
    private Map<String, Object> subNode(Long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("bookCount", bookRepository.countBySubCategoriesContaining(quoted(name)));
        return m;
    }

    /** 子分类在 JSON 数组串里的精确匹配串：带引号，避免「玄幻」误命中「玄幻言情」 */
    private String quoted(String name) {
        return "\"" + name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "\"";
    }

    /** 分类名清洗：去掉会破坏 JSON / LIKE 匹配的引号与反斜杠 */
    private String sanitize(String raw) {
        return raw == null ? "" : raw.replace("\"", "").replace("\\", "").trim();
    }

    private int nextOrder(List<BookCategory> siblings) {
        int max = 0;
        for (BookCategory c : siblings) {
            if (c.getSortOrder() != null && c.getSortOrder() > max) {
                max = c.getSortOrder();
            }
        }
        return max + 10;
    }

    /** 供前端在「管理分类」入口使用的扁平静态校验：返回库中已存在的同名子分类归属 */
    @GetMapping
    public ApiResponse<List<String>> usedNames() {
        Set<String> names = new LinkedHashSet<>();
        for (BookCategory c : bookCategoryRepository.findAllByOrderBySortOrderAscIdAsc()) {
            names.add(c.getName());
        }
        return ApiResponse.success(new ArrayList<>(names));
    }

    @Data
    public static class AddCategoryRequest {
        private String name;
        /** 主分类 id（新增子分类时必填；传空则新增主分类） */
        private Long parentId;
        /** 主分类名（二选一，便于前端只拿到名字时调用） */
        private String parent;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
