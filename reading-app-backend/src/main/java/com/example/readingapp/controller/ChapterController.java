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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    // 目录/列表场景只需要元数据（id/标题/序号/字数）。全书 1300+ 章的正文 content/contentHtml
    // 有 10MB+，是 App 目录弹窗每次加载 30 秒的根因；正文由 /api/chapters/{id} 按章获取。
    @GetMapping("/book/{bookId}")
    public ApiResponse<List<Chapter>> getChaptersByBook(@PathVariable Long bookId) {
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        chapters.forEach(c -> {
            c.setContent(null);
            c.setContentHtml(null);
        });
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

        // 插入位置解析，两条优先级从高到低的路：
        //   ① 标题里能解析出章号 → 按章号找第一个「比它大」的章节，插到它前面。
        //      这是普通补章最准的判定（漏掉第 25 章，就插到第 26 章之前）。
        //   ② 标题解析不出章号（「番外：xxx」「第2.5章」等）→ 用调用方给的 sortOrder 当插入位。
        //      前端「在此章之后补章」正是靠这条路：它把 sortOrder 传成「锚点章节序号 + 1」。
        //      此前这里忽略入参、一律追加到末尾，导致行内补章总是跑到最后一章后面。
        int targetOrder;
        int extractedNumber = extractChapterNumber(chapter.getTitle());
        Integer requestedOrder = chapter.getSortOrder();

        int insertIndex = chapters.size();
        if (extractedNumber > 0) {
            for (int i = 0; i < chapters.size(); i++) {
                if (getTitleNumber(chapters.get(i).getTitle()) > extractedNumber) {
                    insertIndex = i;
                    break;
                }
            }
        } else if (requestedOrder != null && requestedOrder > 0 && requestedOrder <= chapters.size()) {
            // 请求的序号落在合法区间内 → 插到这个位置（0 基下标 = 序号 - 1）
            insertIndex = requestedOrder - 1;
        }

        if (insertIndex < chapters.size()) {
            // 从插入位置开始，所有后续章节序号 +1
            for (int i = insertIndex; i < chapters.size(); i++) {
                Chapter c = chapters.get(i);
                c.setSortOrder(c.getSortOrder() + 1);
                c.setSortKey("1-" + String.format("%04d", c.getSortOrder()));
                chapterRepository.save(c);
            }
            targetOrder = insertIndex + 1;
        } else {
            // 插到末尾
            targetOrder = chapters.size() + 1;
        }
        String sortKey = "1-" + String.format("%04d", targetOrder);
        chapter.setSortOrder(targetOrder);
        chapter.setSortKey(sortKey);
        // 新插入章节按正文计算单章字数（与 recount 口径一致），否则前端会把它判成「空章」
        String addContent = chapter.getContent();
        chapter.setWordCount(addContent != null ? addContent.replaceAll("\\s+", "").length() : 0);
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
        // 编辑章节时同步重算单章字数（与 recount 口径一致），否则前端仍判空章
        String updContent = existing.getContent();
        existing.setWordCount(updContent != null ? updContent.replaceAll("\\s+", "").length() : 0);
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

    /* ===================== 批量删除 ===================== */

    /**
     * 批量删除章节。
     *
     * <p>请求体：{@code {"bookId":123,"ids":[1,2,3],"mode":"ids"|"range"|"all",
     * "keepFirst":n,"keepLast":n,"renumber":true}}
     *
     * <p>三种选择模式：
     * <ul>
     *   <li>{@code ids} —— 前端勾选的章节，按 id 逐个删（跨书 id 会被 bookId 过滤掉）</li>
     *   <li>{@code range} —— 按「保留前 N 章 / 保留后 M 章」删中间段，用于清掉中间的大段错误章节</li>
     *   <li>{@code all} —— 清空本书全部章节</li>
     * </ul>
     *
     * <p>删除后可选调 {@code renumberContiguous} 把 sortKey 重排回连续编号 —— 否则章节序号
     * 会留着空档，App 目录上跳转会出现「第 100 章 → 第 130 章」这种断口。
     */
    @PostMapping("/batch-delete")
    @Transactional
    public ApiResponse<Map<String, Object>> batchDeleteChapters(@RequestBody Map<String, Object> request,
                                                               HttpServletRequest httpRequest) {
        Long bookId = toLong(request.get("bookId"));
        if (bookId == null) {
            return ApiResponse.error(400, "缺少 bookId");
        }
        String bookTitle = bookRepository.findById(bookId).map(b -> b.getTitle()).orElse("未知书籍");

        String mode = request.get("mode") == null ? "ids" : String.valueOf(request.get("mode")).trim();
        if (mode.isEmpty()) { mode = "ids"; }

        List<Chapter> all = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        List<Chapter> doomed;

        if ("all".equalsIgnoreCase(mode)) {
            doomed = new ArrayList<>(all);
        } else if ("range".equalsIgnoreCase(mode)) {
            int keepFirst = Math.max(0, toInt(request.get("keepFirst")));
            int keepLast = Math.max(0, toInt(request.get("keepLast")));
            if (keepFirst + keepLast >= all.size()) {
                // 前后保留下来的已经覆盖全书 → 没有任何中间段可删
                return ApiResponse.error(400,
                        "保留的前 " + keepFirst + " 章 + 后 " + keepLast + " 章已覆盖本书全部 "
                                + all.size() + " 章，没有可删除的中间章节");
            }
            doomed = new ArrayList<>(all.subList(keepFirst, all.size() - keepLast));
        } else {
            List<Long> ids = new ArrayList<>();
            Object raw = request.get("ids");
            if (raw instanceof Collection<?> col) {
                for (Object o : col) {
                    Long v = toLong(o);
                    if (v != null) { ids.add(v); }
                }
            }
            if (ids.isEmpty()) {
                return ApiResponse.error(400, "没有选中任何章节");
            }
            // 以「库中该书的章节」为准做交集：既防跨书误删，也忽略已被别人删掉的 id
            doomed = chapterRepository.findByBookIdAndIdIn(bookId, ids);
            if (doomed.isEmpty()) {
                return ApiResponse.error(400, "选中的章节都不属于本书（可能已被删除）");
            }
        }

        int deleted = doomed.size();
        if (deleted > 0) {
            // 先取一批标题样本，删除后拼进操作日志，便于事后追溯到底删了什么
            List<String> samples = doomed.size() <= 3
                    ? doomed.stream().map(c -> "第" + c.getSortOrder() + "章 " + c.getTitle()).toList()
                    : List.of("第" + doomed.get(0).getSortOrder() + "章 " + doomed.get(0).getTitle()
                            + " … 第" + doomed.get(deleted - 1).getSortOrder() + "章 "
                            + doomed.get(deleted - 1).getTitle());

            chapterRepository.deleteAll(doomed);
            chapterRepository.flush();

            boolean renumber = !Boolean.FALSE.equals(request.get("renumber"));
            String renumberNote = "";
            if (renumber) {
                renumberContiguous(bookId);
                renumberNote = "，序号已重排连续";
            }

            updateBookStats(bookId);
            operationLogService.logBook(OperationLogService.TYPE_CHAPTER_DELETE,
                    "批量删除《" + bookTitle + "》" + deleted + " 章（" + String.join("；", samples) + "）" + renumberNote,
                    bookId, getClientIp(httpRequest));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deleted", deleted);
            data.put("remaining", all.size() - deleted);
            data.put("renumbered", renumber);
            data.put("mode", mode);
            return ApiResponse.success("已删除 " + deleted + " 章" + renumberNote, data);
        }
        return ApiResponse.success("没有需要删除的章节", new LinkedHashMap<>(Map.of("deleted", 0)));
    }

    /**
     * 删除后的安全网：按当前顺序把章节 sortOrder/sortKey 重排成连续编号。
     *
     * <p>刻意不用 bulk UPDATE —— 同一事务里刚被 {@code deleteAll} 处理过的持久化上下文
     * 与本方法加载的实体是同一批对象，bulk 语句绕过上下文会造成脏写入覆盖。
     */
    private void renumberContiguous(Long bookId) {
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            c.setSortOrder(i + 1);
            c.setSortKey("1-" + String.format("%04d", i + 1));
        }
        chapterRepository.saveAll(chapters);
    }

    /** 宽松的数值转换：前端传 Integer/Long/String 都能吃下，非法值回 null */
    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            String s = String.valueOf(o).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 同上，转 int；空值 / 非法值回 0 */
    private int toInt(Object o) {
        Long v = toLong(o);
        return v == null ? 0 : v.intValue();
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
