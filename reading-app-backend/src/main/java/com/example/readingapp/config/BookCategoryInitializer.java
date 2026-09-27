package com.example.readingapp.config;

import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.BookCategory;
import com.example.readingapp.repository.BookCategoryRepository;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.util.BookCategories;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 书籍分类体系初始化 + 历史数据迁移。
 *
 * <p><b>1) 种子</b>：book_category 表为空时写入默认的两级分类（男生 / 女生 + 各自子分类）。
 * 已有数据（管理员加过的分类）一概不动，只补不覆盖。
 *
 * <p><b>2) 迁移</b>：把「子分类列上线前」的老书搬进新结构 ——
 * 老数据里 {@code category} 存的是自由文本（如「玄幻」「小说」），而现在 {@code category}
 * 语义变成「主分类」。迁移规则：{@code sub_categories} 为 NULL 的行，
 * 若其 category 不是已知主分类名 → 把旧值搬进 sub_categories、category 置空
 * （这本书就只出现在书城的「新书」子页，等管理员在后台批量指认主分类）；
 * 是已知主分类名 → 只把 sub_categories 补成 "[]"，避免每次启动重复扫描。
 *
 * <p>迁移只认「sub_categories 为 NULL」这一个信号，因此是幂等的：
 * 迁移过 / 新建的书（写入时是 "[]"）永远不会被二次搬运。
 * 关闭该行为：book.category.auto-init=false。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class BookCategoryInitializer implements ApplicationRunner {

    /** 默认两级分类。子分类顺序即前端展示顺序。 */
    private static final Map<String, List<String>> DEFAULT_TREE = new LinkedHashMap<>();

    static {
        DEFAULT_TREE.put("男生", Arrays.asList(
                "玄幻", "都市", "仙侠", "历史", "科幻", "游戏", "悬疑", "军事", "武侠", "体育"));
        DEFAULT_TREE.put("女生", Arrays.asList(
                "现代言情", "古代言情", "青春校园", "玄幻言情", "仙侠奇缘", "悬疑推理", "浪漫青春", "二次元"));
    }

    private final BookCategoryRepository bookCategoryRepository;
    private final BookRepository bookRepository;

    @Value("${book.category.auto-init:true}")
    private boolean autoInit;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!autoInit) {
            log.info("书籍分类体系自动初始化已关闭（book.category.auto-init=false）");
            return;
        }
        seedIfEmpty();
        migrateLegacyBooks();
    }

    /** 表为空才写种子；已有分类时只补缺失的默认项，不动管理员改过的顺序与名称 */
    private void seedIfEmpty() {
        List<BookCategory> exists = bookCategoryRepository.findAllByOrderBySortOrderAscIdAsc();
        if (exists.isEmpty()) {
            int order = 0;
            for (Map.Entry<String, List<String>> e : DEFAULT_TREE.entrySet()) {
                BookCategory main = new BookCategory();
                main.setName(e.getKey());
                main.setParentId(null);
                main.setSortOrder(order += 10);
                BookCategory savedMain = bookCategoryRepository.save(main);

                int subOrder = 0;
                for (String sub : e.getValue()) {
                    BookCategory child = new BookCategory();
                    child.setName(sub);
                    child.setParentId(savedMain.getId());
                    child.setSortOrder(subOrder += 10);
                    bookCategoryRepository.save(child);
                }
            }
            log.info("书籍分类体系初始化完成：{} 个主分类 / {} 个子分类",
                    DEFAULT_TREE.size(), DEFAULT_TREE.values().stream().mapToInt(List::size).sum());
            return;
        }

        // 非空：只补齐缺失的默认主分类与其子分类（例如上线后新增了「女生」大类）
        Set<String> mainNames = new LinkedHashSet<>();
        for (BookCategory c : exists) {
            if (c.getParentId() == null) {
                mainNames.add(c.getName());
            }
        }
        int addedMain = 0;
        int addedSub = 0;
        for (Map.Entry<String, List<String>> e : DEFAULT_TREE.entrySet()) {
            if (!mainNames.contains(e.getKey())) {
                BookCategory main = new BookCategory();
                main.setName(e.getKey());
                main.setParentId(null);
                main.setSortOrder((mainNames.size() + 1) * 10);
                BookCategory savedMain = bookCategoryRepository.save(main);
                addedMain++;
                int subOrder = 0;
                for (String sub : e.getValue()) {
                    BookCategory child = new BookCategory();
                    child.setName(sub);
                    child.setParentId(savedMain.getId());
                    child.setSortOrder(subOrder += 10);
                    bookCategoryRepository.save(child);
                    addedSub++;
                }
            }
        }
        if (addedMain > 0 || addedSub > 0) {
            log.info("书籍分类体系补齐默认项：新增主分类 {} 个 / 子分类 {} 个", addedMain, addedSub);
        }
    }

    /** 老书迁移：见类注释「2) 迁移」 */
    private void migrateLegacyBooks() {
        Set<String> mainNames = new LinkedHashSet<>();
        for (BookCategory c : bookCategoryRepository.findByParentIdIsNullOrderBySortOrderAscIdAsc()) {
            mainNames.add(c.getName());
        }

        List<Book> legacy = bookRepository.findBySubCategoriesIsNull();
        if (legacy.isEmpty()) {
            return;
        }
        List<Book> moved = new ArrayList<>();
        List<Book> normalized = new ArrayList<>();
        for (Book b : legacy) {
            String cat = b.getCategory() == null ? "" : b.getCategory().replace("\"", "").trim();
            if (!cat.isEmpty() && !mainNames.contains(cat)) {
                // 旧分类是子分类语义 → 搬进子分类，主分类留空待后台指认
                b.setSubCategories(BookCategories.toJson(java.util.Collections.singletonList(cat)));
                b.setCategory("");
                moved.add(b);
            } else {
                // 已是主分类（或本来就为空）：补 "[]" 标记，避免每次启动重复扫描
                b.setSubCategories(BookCategories.EMPTY);
                normalized.add(b);
            }
        }
        bookRepository.saveAll(legacy);
        if (!moved.isEmpty()) {
            log.info("老书籍分类迁移完成：{} 本旧分类（如「{}」）已降级为子分类、主分类置空待后台指认",
                    moved.size(), moved.get(0).getSubCategories());
        }
        if (!normalized.isEmpty()) {
            log.info("书籍子分类列已补齐默认值：{} 本", normalized.size());
        }
    }
}
