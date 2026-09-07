package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.entity.MajorChapter;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.repository.ChapterRepository;
import com.example.readingapp.repository.MajorChapterRepository;
import com.example.readingapp.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@RestController
@RequestMapping("/api/admin/import")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminImportController {

    private final JdbcTemplate jdbcTemplate;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final MajorChapterRepository majorChapterRepository;
    private final OperationLogService operationLogService;

    // 执行自定义 SQL
    @PostMapping("/sql")
    public ApiResponse<String> importSQL(@RequestBody Map<String, String> request) {
        try {
            String sql = request.get("sql");
            if (sql == null || sql.trim().isEmpty()) {
                return ApiResponse.error("SQL语句不能为空");
            }
            
            // 安全检查：禁止危险操作
            String upperSql = sql.toUpperCase();
            if (upperSql.contains("DROP") || upperSql.contains("TRUNCATE") || 
                upperSql.contains("DELETE FROM") || upperSql.contains("UPDATE ")) {
                return ApiResponse.error("禁止执行危险的SQL操作（DROP/TRUNCATE/DELETE/UPDATE）");
            }
            
            String[] statements = sql.split(";");
            int count = 0;
            for (String stmt : statements) {
                stmt = stmt.trim();
                if (!stmt.isEmpty()) {
                    jdbcTemplate.execute(stmt);
                    count++;
                }
            }
            return ApiResponse.success("成功执行 " + count + " 条SQL语句", null);
        } catch (Exception e) {
            return ApiResponse.error("执行失败: " + e.getMessage());
        }
    }

    // 快速生成测试数据
    @PostMapping("/generate")
    @Transactional
    public ApiResponse<String> generateData(@RequestBody Map<String, Object> request) {
        try {
            String title = (String) request.get("title");
            String author = (String) request.get("author");
            String category = (String) request.get("category");
            String intro = (String) request.get("intro");
            int chapterCount = (int) request.getOrDefault("chapterCount", 10);

            // 创建书籍
            Book book = new Book();
            book.setTitle(title);
            book.setAuthor(author);
            book.setCategory(category);
            book.setIntro(intro);
            book.setChapterCount(chapterCount);
            book.setStatus(chapterCount > 50 ? 1 : 0);
            book = bookRepository.save(book);

            // 生成章节
            String lorem = "这是一个关于修仙的故事。天元大陆，以武为尊，强者可翻云覆雨，弱者只能任人宰割。少年自小父母双亡，在家族中受尽欺凌。但他从未放弃变强的信念。一日，在后山采药时，意外发现一处神秘洞府...";

            for (int i = 1; i <= chapterCount; i++) {
                Chapter chapter = new Chapter();
                chapter.setBookId(book.getId());
                chapter.setTitle("第" + i + "章");
                chapter.setSortOrder(i);
                chapter.setWordCount(lorem.length() + 50);
                chapter.setContent(lorem + "\n\n（第" + i + "章内容）");
                chapterRepository.save(chapter);
            }

            // 更新书籍总字数（去空格计算）
            List<Chapter> generatedChapters = chapterRepository.findByBookIdOrderBySortOrderAsc(book.getId());
            int totalWordCount = 0;
            for (Chapter c : generatedChapters) {
                if (c.getContent() != null) {
                    totalWordCount += c.getContent().replaceAll("\\s+", "").length();
                }
            }
            book.setWordCount(totalWordCount);
            bookRepository.save(book);

            return ApiResponse.success("成功生成《" + title + "》共 " + chapterCount + " 章", null);
        } catch (Exception e) {
            return ApiResponse.error("生成失败: " + e.getMessage());
        }
    }

    // 上传 TXT 自动分章（支持自动提取书名、作者、简介）
    @PostMapping("/txt")
    @Transactional
    public ApiResponse<String> uploadTxt(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "autoExtract", defaultValue = "true") boolean autoExtract,
            HttpServletRequest request) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            String fullContent = content.toString();
            
            // 如果启用自动提取，尝试从内容中提取书名、作者、简介
            String extractedTitle = title;
            String extractedAuthor = author;
            String extractedIntro = "";
            String chapterContent = fullContent;

            if (autoExtract) {
                BookMetadata metadata = extractBookMetadata(fullContent);
                if (metadata != null) {
                    if (title == null || title.trim().isEmpty()) {
                        extractedTitle = metadata.title;
                    }
                    if (author == null || author.trim().isEmpty()) {
                        extractedAuthor = metadata.author;
                    }
                    extractedIntro = metadata.intro;
                    chapterContent = metadata.remainingContent;
                }
            }

            // 验证必要字段
            if (extractedTitle == null || extractedTitle.trim().isEmpty()) {
                return ApiResponse.error("无法从文件中提取书名，请手动输入");
            }
            if (extractedAuthor == null || extractedAuthor.trim().isEmpty()) {
                extractedAuthor = "佚名";
            }

            // 创建书籍
            Book book = new Book();
            book.setTitle(extractedTitle.trim());
            book.setAuthor(extractedAuthor.trim());
            book.setCategory(category != null ? category.trim() : "");
            book.setIntro(extractedIntro.trim());
            book.setChapterCount(0);
            book.setStatus(0);
            book = bookRepository.save(book);

            String regex = "(?=第[\\u4e00-\\u9fa5\\d]+章)";
            String[] parts = chapterContent.split(regex);

            Pattern titlePattern = Pattern.compile("第[\\u4e00-\\u9fa5\\d]+章\\s*[^\n]*");

            // 第一步：收集所有章节数字，检测层级
            List<Integer> chapterNumbers = new ArrayList<>();
            for (String part : parts) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                Matcher m = titlePattern.matcher(p);
                if (m.find()) {
                    chapterNumbers.add(extractChapterNumber(m.group()));
                } else {
                    chapterNumbers.add(null);
                }
            }

            // 第二步：生成文本序号
            int majorLevel = 1;
            int minorIndex = 0;
            List<String> sortKeys = new ArrayList<>();
            List<String> majorTitles = new ArrayList<>();
            int prevNumber = -1;

            for (Integer num : chapterNumbers) {
                if (num == null) {
                    sortKeys.add("0-0000"); // 非章节内容
                    continue;
                }
                if (prevNumber != -1 && num < prevNumber) {
                    majorLevel++;
                    minorIndex = 1;
                    String majorTitle = "第" + convertToChineseNumber(majorLevel) + "部分";
                    majorTitles.add(majorTitle);
                } else if (prevNumber == -1) {
                    minorIndex = 1;
                } else {
                    minorIndex++;
                }
                prevNumber = num;
                sortKeys.add(majorLevel + "-" + String.format("%04d", minorIndex));
            }

            // 第三步：正式处理章节
            int chapterCount = 0;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.isEmpty()) continue;

                Matcher matcher = titlePattern.matcher(part);
                String chapterTitle;
                String chapterContentPart;

                if (matcher.find()) {
                    chapterTitle = matcher.group().trim();
                    chapterContentPart = part.substring(matcher.end()).trim();
                } else {
                    continue;
                }

                Chapter chapter = new Chapter();
                chapter.setBookId(book.getId());
                chapter.setTitle(chapterTitle);
                chapter.setSortOrder(extractChapterNumber(chapterTitle));
                if (chapterCount < sortKeys.size()) {
                    chapter.setSortKey(sortKeys.get(i));
                }
                chapter.setContent(chapterContentPart);
                chapter.setWordCount(chapterContentPart.length());
                chapterRepository.save(chapter);
                chapterCount++;
            }

            // 更新章节数和总字数
            book.setChapterCount(chapterCount);
            List<Chapter> savedChapters = chapterRepository.findByBookIdOrderBySortOrderAsc(book.getId());
            int totalWordCount = 0;
            for (Chapter c : savedChapters) {
                if (c.getContent() != null) {
                    totalWordCount += c.getContent().replaceAll("\\s+", "").length();
                }
            }
            book.setWordCount(totalWordCount);
            bookRepository.save(book);

            // 自动创建大章节记录
            if (majorLevel > 1) {
                for (int level = 1; level <= majorLevel; level++) {
                    if (!majorChapterRepository.existsByBookIdAndSortKey(book.getId(), String.valueOf(level))) {
                        MajorChapter mc = new MajorChapter();
                        mc.setBookId(book.getId());
                        mc.setTitle("第" + level + "部分");
                        mc.setSortKey(String.valueOf(level));
                        mc.setSortOrder(level);
                        majorChapterRepository.save(mc);
                    }
                }
            }

            List<Chapter> allChapters = chapterRepository.findByBookIdOrderBySortOrderAsc(book.getId());
            allChapters.sort((a, b) -> {
                String keyA = a.getSortKey() != null ? a.getSortKey() : "";
                String keyB = b.getSortKey() != null ? b.getSortKey() : "";
                return keyA.compareTo(keyB);
            });
            for (int i = 0; i < allChapters.size(); i++) {
                Chapter c = allChapters.get(i);
                c.setSortOrder(i + 1);
                chapterRepository.save(c);
            }

            String resultMsg = "成功导入《" + extractedTitle + "》共 " + chapterCount + " 章";
            if (!extractedAuthor.equals("佚名")) {
                resultMsg += "，作者：" + extractedAuthor;
            }
            if (!extractedIntro.isEmpty()) {
                resultMsg += "，已提取简介";
            }
            
            // 记录日志
            operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                "导入TXT书籍：《" + extractedTitle + "》（" + chapterCount + "章）",
                book.getId(), getClientIp(request));
            
            return ApiResponse.success(resultMsg, null);
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    /**
     * 书籍元数据类
     */
    private static class BookMetadata {
        String title;
        String author;
        String intro;
        String remainingContent;
    }

    /**
     * 从TXT内容中提取书籍元数据（书名、作者、简介）
     */
    private BookMetadata extractBookMetadata(String content) {
        BookMetadata metadata = new BookMetadata();
        metadata.title = "";
        metadata.author = "";
        metadata.intro = "";
        metadata.remainingContent = content;

        // 只扫描前50行寻找书名和作者
        String[] lines = content.split("\n");
        int scanLimit = Math.min(lines.length, 50);
        StringBuilder prefaceBuilder = new StringBuilder();
        boolean foundChapterStart = false;
        int chapterStartLine = lines.length;

        // 章节标题模式
        Pattern chapterPattern = Pattern.compile("第[\\u4e00-\\u9fa5\\d]+[章节卷部回]");
        
        // 书名提取模式
        Pattern[] titlePatterns = {
            Pattern.compile("(?i)^\\s*书名\\s*[：:]\\s*(.+)$"),
            Pattern.compile("(?i)^\\s*作品名\\s*[：:]\\s*(.+)$"),
            Pattern.compile("(?i)^\\s*小说名\\s*[：:]\\s*(.+)$"),
            Pattern.compile("《([^》]+)》"),
            Pattern.compile("^\\s*([^《\\s\\n].{1,30})\\s*$"), // 第一行作为书名
        };
        
        // 作者提取模式
        Pattern[] authorPatterns = {
            Pattern.compile("(?i)^\\s*作者\\s*[：:]\\s*(.+)$"),
            Pattern.compile("(?i)^\\s*著者\\s*[：:]\\s*(.+)$"),
            Pattern.compile("(?i)^\\s*作者\\s+(.+)$"),
            Pattern.compile("(?i)^\\s*[\\[(（【]\\s*作者\\s*[\\])）】]?\\s*(.+)$"),
            Pattern.compile("(?i)^\\s*(.+?)\\s*[\\[(（【]\\s*著\\s*[\\])）】]?\\s*$"),
        };
        
        // 简介提取模式（标记行）
        Pattern[] introSkipPatterns = {
            Pattern.compile("(?i).*(书名|作者|著者|作品名|小说名|分类|类别|状态|连载|完结).*"),
            Pattern.compile("(?i).*目录.*"),
            Pattern.compile("(?i).*(简介|内容简介|作品简介|序|楔子|前言|引子|第一章前).*"),
            Pattern.compile("(?i)^\\s*[-=*_]+\\s*$"), // 分隔线
            Pattern.compile("^\\s*第[\\u4e00-\\u9fa5\\d]+[部分卷]\\s*$"),
        };

        // 扫描前50行寻找书名和作者
        for (int i = 0; i < scanLimit; i++) {
            String line = lines[i].trim();
            
            // 检查是否遇到章节开始
            if (chapterPattern.matcher(line).find() && i > 5) {
                chapterStartLine = i;
                foundChapterStart = true;
                break;
            }
            
            // 提取书名（只提取第一个）
            if (metadata.title.isEmpty()) {
                for (Pattern p : titlePatterns) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        String candidate = m.group(1).trim();
                        // 过滤掉明显不是书名的内容
                        if (!candidate.isEmpty() && 
                            !candidate.contains("第") && 
                            !candidate.contains("章") && 
                            candidate.length() <= 50) {
                            metadata.title = candidate;
                            break;
                        }
                    }
                }
                // 如果前几行有《书名》格式，优先使用
                if (metadata.title.isEmpty() && line.contains("《") && line.contains("》")) {
                    Matcher m = Pattern.compile("《([^》]+)》").matcher(line);
                    if (m.find()) {
                        metadata.title = m.group(1).trim();
                    }
                }
            }
            
            // 提取作者（只提取第一个）
            if (metadata.author.isEmpty()) {
                for (Pattern p : authorPatterns) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        String candidate = m.group(1).trim();
                        if (!candidate.isEmpty() && candidate.length() <= 20) {
                            metadata.author = candidate;
                            break;
                        }
                    }
                }
            }
        }

        // 如果还没找到书名，尝试用前几行的第一个非空短行作为书名
        if (metadata.title.isEmpty()) {
            for (int i = 0; i < Math.min(10, lines.length); i++) {
                String line = lines[i].trim();
                if (!line.isEmpty() && line.length() <= 30 && line.length() >= 2 &&
                    !chapterPattern.matcher(line).find() &&
                    !line.matches("(?i).*(作者|书名|简介).*")) {
                    metadata.title = line;
                    break;
                }
            }
        }

        // 如果还没找到作者，用"佚名"作为默认值
        if (metadata.author.isEmpty()) {
            metadata.author = "佚名";
        }

        // 查找章节开始位置
        if (!foundChapterStart) {
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (chapterPattern.matcher(line).find()) {
                    chapterStartLine = i;
                    foundChapterStart = true;
                    break;
                }
            }
        }

        // 提取简介（章节开始之前的内容）
        if (foundChapterStart && chapterStartLine > 2) {
            StringBuilder introBuilder = new StringBuilder();
            for (int i = 0; i < chapterStartLine; i++) {
                String line = lines[i].trim();
                
                // 跳过空行
                if (line.isEmpty()) continue;
                
                // 检查是否是应该跳过的行
                boolean skip = false;
                for (Pattern p : introSkipPatterns) {
                    if (p.matcher(line).matches()) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;
                
                // 跳过包含已提取书名的行
                if (!metadata.title.isEmpty() && line.contains(metadata.title)) {
                    continue;
                }
                
                // 跳过包含已提取作者的行
                if (!metadata.author.isEmpty() && metadata.author.length() > 1 && 
                    line.contains(metadata.author)) {
                    continue;
                }
                
                if (introBuilder.length() > 0) {
                    introBuilder.append("\n");
                }
                introBuilder.append(line);
            }
            
            metadata.intro = introBuilder.toString().trim();
            // 限制简介长度
            if (metadata.intro.length() > 500) {
                metadata.intro = metadata.intro.substring(0, 500) + "...";
            }
            
            // 从章节开始位置提取剩余内容
            StringBuilder remainingBuilder = new StringBuilder();
            for (int i = chapterStartLine; i < lines.length; i++) {
                remainingBuilder.append(lines[i]).append("\n");
            }
            metadata.remainingContent = remainingBuilder.toString();
        }

        // 清理书名中的多余信息
        if (!metadata.title.isEmpty()) {
            // 去掉常见后缀
            metadata.title = metadata.title.replaceAll("\\s*[（(].*?[）)]\\s*$", "").trim();
            // 如果书名太长，截断
            if (metadata.title.length() > 50) {
                metadata.title = metadata.title.substring(0, 50);
            }
        }

        return metadata;
    }

    private String convertToChineseNumber(int num) {
        String[] units = {"", "十", "百", "千", "万"};
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        String str = String.valueOf(num);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            int d = str.charAt(i) - '0';
            if (d != 0) {
                sb.append(digits[d]).append(units[str.length() - i - 1]);
            }
        }
        return sb.toString().replaceAll("零+$", "");
    }

    private int extractChapterNumber(String title) {
        if (title == null) return 0;
        String numStr = title.replaceFirst("^第", "").replaceFirst("章.*$", "").trim();

        // 尝试阿拉伯数字
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException ignored) {}
        // 中文数字转换
        return convertChineseNumber(numStr);
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

    // 上传EPUB文件自动解析（使用纯Java实现）
    @PostMapping("/epub")
    @Transactional
    public ApiResponse<String> uploadEpub(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "category", required = false) String category,
            HttpServletRequest request) {
        try {
            // 将MultipartFile保存到临时文件
            File tempFile = File.createTempFile("epub_", ".tmp");
            file.transferTo(tempFile);
            
            // EPUB是ZIP格式，使用ZipFile读取
            ZipFile zipFile = new ZipFile(tempFile);
            Map<String, String> fileContents = new HashMap<>();
            Map<String, String> manifest = new HashMap<>();
            List<String> spineList = new ArrayList<>();
            
            String containerXml = null;
            
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName().toLowerCase();
                
                // 跳过目录
                if (entry.isDirectory()) {
                    continue;
                }
                
                // 读取文件内容
                StringBuilder content = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                reader.close();
                
                String fileContent = content.toString();
                fileContents.put(entryName, fileContent);
                
                // 保存container.xml用于定位OPF文件
                if (entryName.equals("meta-inf/container.xml")) {
                    containerXml = fileContent;
                }
            }
            zipFile.close();
            tempFile.delete(); // 删除临时文件

            // 解析container.xml获取OPF文件路径
            String opfPath = "OEBPS/content.opf"; // 默认路径
            if (containerXml != null) {
                Pattern opfPattern = Pattern.compile("full-path=\"([^\"]+)\"");
                Matcher m = opfPattern.matcher(containerXml);
                if (m.find()) {
                    opfPath = m.group(1);
                }
            }

            // 解析OPF文件获取元数据和章节列表
            String opfContent = fileContents.get(opfPath.toLowerCase());
            if (opfContent == null) {
                // 尝试其他可能的路径
                for (String key : fileContents.keySet()) {
                    if (key.endsWith(".opf")) {
                        opfContent = fileContents.get(key);
                        opfPath = key;
                        break;
                    }
                }
            }
            
            if (opfContent == null) {
                return ApiResponse.error("无法解析EPUB结构");
            }

            // 提取元数据
            String bookTitle = title;
            String bookAuthor = author;
            String bookIntro = "";

            // 从OPF中提取标题
            if (bookTitle == null || bookTitle.trim().isEmpty()) {
                Pattern titlePattern = Pattern.compile("<dc:title[^>]*>([^<]+)</dc:title>", Pattern.CASE_INSENSITIVE);
                Matcher m = titlePattern.matcher(opfContent);
                if (m.find()) {
                    bookTitle = m.group(1).trim();
                }
            }
            
            // 从OPF中提取作者
            if (bookAuthor == null || bookAuthor.trim().isEmpty()) {
                Pattern authorPattern = Pattern.compile("<dc:creator[^>]*>([^<]+)</dc:creator>", Pattern.CASE_INSENSITIVE);
                Matcher m = authorPattern.matcher(opfContent);
                if (m.find()) {
                    bookAuthor = m.group(1).trim();
                }
            }
            
            // 从OPF中提取描述/简介
            Pattern descPattern = Pattern.compile("<dc:description[^>]*>([^<]+)</dc:description>", Pattern.CASE_INSENSITIVE);
            Matcher m = descPattern.matcher(opfContent);
            if (m.find()) {
                bookIntro = m.group(1).trim();
                if (bookIntro.length() > 500) {
                    bookIntro = bookIntro.substring(0, 500) + "...";
                }
            }

            // 提取manifest中的id和href映射
            Pattern itemPattern = Pattern.compile("<item[^>]+id=\"([^\"]+)\"[^>]+href=\"([^\"]+)\"[^>]*/>", Pattern.CASE_INSENSITIVE);
            m = itemPattern.matcher(opfContent);
            while (m.find()) {
                manifest.put(m.group(1), m.group(2));
            }
            
            // 如果上面的正则没匹配到，尝试另一种格式
            if (manifest.isEmpty()) {
                itemPattern = Pattern.compile("<item[^>]+href=\"([^\"]+)\"[^>]+id=\"([^\"]+)\"[^>]*/>", Pattern.CASE_INSENSITIVE);
                m = itemPattern.matcher(opfContent);
                while (m.find()) {
                    manifest.put(m.group(2), m.group(1));
                }
            }

            // 提取spine中的章节顺序
            Pattern spinePattern = Pattern.compile("<itemref[^>]+idref=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            m = spinePattern.matcher(opfContent);
            while (m.find()) {
                String idref = m.group(1);
                String href = manifest.get(idref);
                if (href != null) {
                    spineList.add(href);
                }
            }

            // ✅ 获取OPF文件所在目录（提前定义，给NCX解析用）
            String opfDir = "";
            int lastSlash0 = opfPath.lastIndexOf('/');
            if (lastSlash0 > 0) {
                opfDir = opfPath.substring(0, lastSlash0 + 1);
            }

            // ✅ 【新增】解析 NCX 文件建立 href → 标题 映射（最可靠的章节标题来源）
            Map<String, String> ncxHrefToTitle = new HashMap<>();
            String ncxPath = "";
            // 先找 OPF 里的 ncx item
            Pattern ncxItemPattern = Pattern.compile("<item[^>]+id=\"ncx\"[^>]+href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher ncxM = ncxItemPattern.matcher(opfContent);
            if (ncxM.find()) {
                ncxPath = ncxM.group(1);
            } else {
                // 找任何 .ncx 文件
                for (String key : fileContents.keySet()) {
                    if (key.endsWith(".ncx")) {
                        ncxPath = key;
                        break;
                    }
                }
            }
            String ncxContent = null;
            if (!ncxPath.isEmpty()) {
                String fullNcxPath;
                if (ncxPath.startsWith("/")) {
                    fullNcxPath = ncxPath.substring(1);
                } else {
                    fullNcxPath = opfDir + ncxPath;
                }
                fullNcxPath = fullNcxPath.replaceAll("//+", "/").toLowerCase();
                ncxContent = fileContents.get(fullNcxPath);
                if (ncxContent == null) {
                    for (String key : fileContents.keySet()) {
                        if (key.equalsIgnoreCase(fullNcxPath)) {
                            ncxContent = fileContents.get(key);
                            break;
                        }
                    }
                }
            }
            if (ncxContent != null) {
                // 解析每个 navPoint：<navLabel><text>标题</text></navLabel><content src="Text/chapter_X.html"/>
                Pattern navPointPattern = Pattern.compile(
                    "<navPoint[^>]*>\\s*<navLabel>\\s*<text[^>]*>([^<]*)</text>\\s*</navLabel>\\s*<content[^>]+src=\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
                );
                Matcher npM = navPointPattern.matcher(ncxContent);
                while (npM.find()) {
                    String navTitle = npM.group(1).trim();
                    String navSrc = npM.group(2).trim().toLowerCase();
                    if (!navTitle.isEmpty() && !navSrc.isEmpty()) {
                        ncxHrefToTitle.put(navSrc, navTitle);
                    }
                }
                System.out.println("[EPUB NCX] 解析到 " + ncxHrefToTitle.size() + " 个导航点");
            }

            // 验证和设置默认值
            if (bookTitle == null || bookTitle.trim().isEmpty()) {
                String fileName = file.getOriginalFilename();
                if (fileName != null) {
                    bookTitle = fileName.replaceAll("\\.epub$", "");
                }
            }
            if (bookTitle == null || bookTitle.trim().isEmpty()) {
                bookTitle = "未命名书籍";
            }
            if (bookAuthor == null || bookAuthor.trim().isEmpty()) {
                bookAuthor = "佚名";
            }

            // 创建书籍
            Book book = new Book();
            book.setTitle(bookTitle.trim());
            book.setAuthor(bookAuthor.trim());
            book.setCategory(category != null ? category.trim() : "");
            book.setIntro(bookIntro.trim());
            book.setChapterCount(0);
            book.setStatus(0);
            book = bookRepository.save(book);

            // 处理章节内容
            int chapterCount = 0;
            int majorLevel = 1;
            int minorIndex = 0;
            int prevChapterNum = -1;
            Pattern chapterPattern = Pattern.compile("第?([\\d]+)[章节卷部回]");

            // opfDir 已在前面定义

            // 用于从章节内容中提取分类信息
            String extractedCategory = "";
            
            for (int i = 0; i < spineList.size(); i++) {
                String chapterHref = spineList.get(i);
                
                // 跳过非HTML文件
                if (!chapterHref.toLowerCase().matches(".*\\.(x?html?|htm)$")) {
                    continue;
                }
                
                // 构建完整路径
                String chapterPath;
                if (chapterHref.startsWith("/")) {
                    chapterPath = chapterHref.substring(1);
                } else {
                    chapterPath = opfDir + chapterHref;
                }
                chapterPath = chapterPath.replaceAll("//+", "/").toLowerCase();

                String chapterContent = fileContents.get(chapterPath);
                if (chapterContent == null) {
                    // 尝试其他大小写
                    for (String key : fileContents.keySet()) {
                        if (key.equalsIgnoreCase(chapterPath)) {
                            chapterContent = fileContents.get(key);
                            break;
                        }
                    }
                }
                
                if (chapterContent == null || chapterContent.trim().isEmpty()) {
                    continue;
                }

                // ✅ 【新增1】从 NCX 映射里先查标题（最可靠来源）
                String ncxTitle = null;
                if (ncxHrefToTitle.containsKey(chapterHref.toLowerCase())) {
                    ncxTitle = ncxHrefToTitle.get(chapterHref.toLowerCase());
                } else if (ncxHrefToTitle.containsKey(chapterPath.toLowerCase())) {
                    ncxTitle = ncxHrefToTitle.get(chapterPath.toLowerCase());
                }

                // ✅ 【新增2】按文件名/NCX标题过滤非正文章节
                if (shouldSkipEpubChapter(chapterHref, ncxTitle)) {
                    System.out.println("[EPUB] 跳过非正文章节: " + chapterHref + " (NCX标题=" + ncxTitle + ")");
                    continue;
                }

                // 从HTML中提取纯文本
                String textContent = extractTextFromHtml(chapterContent);
                
                // ✅ 【新增3】从HTML heading 提取标题（支持嵌套 span/br）
                String headingTitle = extractHeadingTitleRobust(chapterContent);

                // ✅ 【新增4】多路竞争选最佳标题
                String chapterTitle = pickBestChapterTitle(ncxTitle, headingTitle, chapterContent, chapterHref, i, textContent);

                // ✅ 【新增5】清理正文开头可能残留的标题行
                textContent = stripLeadingTitleFromContent(textContent, chapterTitle);
                
                // 如果还没有设置分类，尝试从内容中提取
                if ((category == null || category.trim().isEmpty()) && 
                    extractedCategory.isEmpty()) {
                    // 简单尝试
                    TextContentInfo ci = parseSpecialContentFormat(textContent);
                    if (ci.category != null && !ci.category.isEmpty()) {
                        extractedCategory = ci.category;
                    }
                }
                
                System.out.println("[EPUB] 章节#" + (chapterCount + 1) + " 文件=" + chapterHref + " → 标题=" + chapterTitle + " (来源: NCX=" + ncxTitle + ", heading=" + headingTitle + ")");

                // 计算排序键
                String sortKey;
                Matcher chapterMatcher = chapterPattern.matcher(chapterTitle);
                if (chapterMatcher.find()) {
                    int chapterNum = Integer.parseInt(chapterMatcher.group(1));
                    if (prevChapterNum != -1 && chapterNum < prevChapterNum) {
                        majorLevel++;
                    }
                    prevChapterNum = chapterNum;
                    minorIndex++;
                    sortKey = majorLevel + "-" + String.format("%04d", minorIndex);
                } else {
                    minorIndex++;
                    sortKey = majorLevel + "-" + String.format("%04d", minorIndex);
                }

                // 创建章节
                Chapter chapter = new Chapter();
                chapter.setBookId(book.getId());
                chapter.setTitle(chapterTitle);
                chapter.setContent(textContent);
                chapter.setSortOrder(chapterCount + 1);
                chapter.setSortKey(sortKey);
                chapter.setWordCount(textContent.length());
                chapterRepository.save(chapter);
                chapterCount++;
            }
            
            // 如果从内容中提取到了分类，更新书籍分类
            if ((category == null || category.trim().isEmpty()) && 
                !extractedCategory.isEmpty()) {
                book.setCategory(extractedCategory);
                bookRepository.save(book);
            }

            // 更新书籍章节数和总字数
            book.setChapterCount(chapterCount);
            List<Chapter> epubChapters = chapterRepository.findByBookIdOrderBySortOrderAsc(book.getId());
            int epubWordCount = 0;
            for (Chapter c : epubChapters) {
                if (c.getContent() != null) {
                    epubWordCount += c.getContent().replaceAll("\\s+", "").length();
                }
            }
            book.setWordCount(epubWordCount);
            bookRepository.save(book);

            // 创建大章节记录
            if (majorLevel > 1) {
                for (int level = 1; level <= majorLevel; level++) {
                    MajorChapter mc = new MajorChapter();
                    mc.setBookId(book.getId());
                    mc.setTitle("第" + level + "部分");
                    mc.setSortKey(String.valueOf(level));
                    mc.setSortOrder(level);
                    majorChapterRepository.save(mc);
                }
            }

            String resultMsg = "成功导入《" + bookTitle + "》共 " + chapterCount + " 章";
            if (!bookAuthor.equals("佚名")) {
                resultMsg += "，作者：" + bookAuthor;
            }
            if (!bookIntro.isEmpty()) {
                resultMsg += "，已提取简介";
            }
            
            // 记录日志
            operationLogService.logBook(OperationLogService.TYPE_IMPORT,
                "导入EPUB书籍：《" + bookTitle + "》（" + chapterCount + "章）",
                book.getId(), getClientIp(request));
            
            return ApiResponse.success(resultMsg, null);

        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error("EPUB导入失败: " + e.getMessage());
        }
    }

    /**
     * 从HTML内容中提取纯文本
     */
    private String extractTextFromHtml(String html) {
        if (html == null) return "";
        // 移除script和style标签及其内容
        html = html.replaceAll("(?i)<script[^>]*>.*?</script>", "");
        html = html.replaceAll("(?i)<style[^>]*>.*?</style>", "");
        // 替换块级标签为空格
        html = html.replaceAll("(?i)<(br|p|div|h[1-6]|li|tr)[^>]*>", " ");
        html = html.replaceAll("(?i)</(br|p|div|h[1-6]|li|tr)[^>]*>", " ");
        // 移除所有HTML标签
        html = html.replaceAll("<[^>]+>", "");
        // 解码HTML实体
        html = html.replace("&nbsp;", " ");
        html = html.replace("&amp;", "&");
        html = html.replace("&lt;", "<");
        html = html.replace("&gt;", ">");
        html = html.replace("&quot;", "\"");
        html = html.replace("&#39;", "'");
        // 清理多余空白
        html = html.replaceAll("\\s+", " ").trim();
        return html;
    }

    /**
     * 从HTML内容中提取章节标题
     */
    private String extractChapterTitleFromHtml(String html, String href, int index) {
        // 1. 优先从h1-h6标签提取
        Pattern hPattern = Pattern.compile("(?i)<h[1-6][^>]*>([^<]+)</h[1-6]>");
        Matcher m = hPattern.matcher(html);
        if (m.find()) {
            String title = stripHtmlTags(m.group(1)).trim();
            if (!title.isEmpty() && title.length() < 100) {
                return cleanTitle(title);
            }
        }

        // 2. 从title标签提取（需要排除一些非章节标题）
        Pattern titlePattern = Pattern.compile("(?i)<title[^>]*>([^<]+)</title>");
        m = titlePattern.matcher(html);
        if (m.find()) {
            String title = m.group(1).trim();
            // 排除常见非章节标题
            if (!isNonChapterTitle(title) && !title.isEmpty() && title.length() < 100) {
                return cleanTitle(title);
            }
        }

        // 3. 从class或id属性中查找标题（很多EPUB有结构化的标题）
        Pattern classPattern = Pattern.compile("(?i)<[^>]*(?:class|id)=\"[^\"]*title[^\"]*\"[^>]*>([^<]+)<");
        m = classPattern.matcher(html);
        if (m.find()) {
            String title = stripHtmlTags(m.group(1)).trim();
            if (!title.isEmpty() && title.length() < 100) {
                return cleanTitle(title);
            }
        }

        // 4. 从文件名提取
        if (href != null) {
            String fileName = href.replaceAll(".*/", "").replaceAll("\\.(x?html?|htm)$", "");
            if (!fileName.isEmpty() && !fileName.equals("index")) {
                return cleanTitle(fileName);
            }
        }

        return "第" + (index + 1) + "章";
    }

    /**
     * 清理标题文字
     */
    private String cleanTitle(String title) {
        if (title == null) return "";
        // 去掉前后空格
        title = title.trim();
        // ✅ 不再去掉章节号前缀（之前的逻辑反了！）
        // 去掉HTML实体残留
        title = title.replace("&nbsp;", " ").replace("&amp;", "&");
        return title.trim();
    }

    /**
     * 剥离HTML标签获取纯文本
     */
    private String stripHtmlTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").trim();
    }

    /**
     * 判断是否是非章节标题（封面、简介、版权、目录等）
     */
    private boolean isNonChapterTitle(String title) {
        if (title == null) return true;
        String lower = title.toLowerCase();
        // 常见非章节关键词
        String[] keywords = {
            "封面", "封皮", "cover", "扉页",
            "版权", "版权信息", "copyright", "版权声明",
            "目录", "目次", "contents", "table of contents",
            "制作", "制作说明", "制作信息", "production",
            "出版", "出版社", "publisher",
            "前言", "序言", "序", "preface", "foreword",
            "后记", "尾声", "epilogue",
            "致谢", "致谢辞", "acknowledgment", "acknowledgement",
            "作者", "作者简介", "about the author",
            "内容简介", "简介", "summary", "description",
            "untitled", "无标题", "index"
        };
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文本内容信息类（用于存储解析结果）
     */
    private static class TextContentInfo {
        String cleanContent;
        String title;
        String category;
        
        TextContentInfo(String cleanContent, String title, String category) {
            this.cleanContent = cleanContent;
            this.title = title;
            this.category = category;
        }
    }

    /**
     * 解析特殊格式的章节内容（如：chapter 2 - 0 | 都市 | 挑书 第二章 ...）
     */
    private TextContentInfo parseSpecialContentFormat(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new TextContentInfo(content, null, null);
        }

        String cleanContent = content;
        String title = null;
        String category = null;

        // 匹配格式：chapter X - Y | 分类 | 来源 章节标题 ...
        Pattern specialPattern = Pattern.compile(
            "^\\s*chapter\\s+\\d+\\s*-\\s*\\d+\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s+(.*)$",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m = specialPattern.matcher(content);
        
        if (m.find()) {
            String part1 = m.group(1).trim();
            String part2 = m.group(2).trim();
            String rest = m.group(3).trim();
            
            // 判断哪个是分类（通常是单个词或常见分类名）
            String[] knownCategories = {"都市", "玄幻", "仙侠", "奇幻", "武侠", "科幻", "言情", 
                                       "历史", "悬疑", "推理", "恐怖", "穿越", "重生", "校园",
                                       "游戏", "竞技", "军事", "官场", "职场", "商战", "种田"};
            
            // 判断哪个部分是分类
            boolean part1IsCategory = false;
            boolean part2IsCategory = false;
            
            for (String cat : knownCategories) {
                if (part1.equals(cat)) {
                    part1IsCategory = true;
                }
                if (part2.equals(cat)) {
                    part2IsCategory = true;
                }
            }
            
            // 如果part1是分类，则part2可能是来源
            if (part1IsCategory) {
                category = part1;
                // part2可能是来源或标题的一部分，尝试从rest中提取标题
                title = extractTitleFromRest(rest);
                cleanContent = rest;
            } else if (part2IsCategory) {
                category = part2;
                // part1可能是来源，尝试从rest中提取标题
                title = extractTitleFromRest(rest);
                cleanContent = rest;
            } else {
                // 不确定哪个是分类，part1更可能是分类
                // 检查长度和内容
                if (part1.length() <= 4 && !part1.contains("第")) {
                    category = part1;
                }
                title = extractTitleFromRest(part2 + " " + rest);
                cleanContent = part2 + " " + rest;
            }
        }
        
        // 如果没有匹配到特殊格式，尝试简单的"| 分类 |"格式
        if (category == null) {
            Pattern simpleCatPattern = Pattern.compile("^[^|]*\\|\\s*([^|\\s]+?)\\s*\\|");
            m = simpleCatPattern.matcher(content);
            if (m.find()) {
                String potentialCat = m.group(1).trim();
                // 验证是否为常见分类
                for (String cat : new String[]{"都市", "玄幻", "仙侠", "奇幻", "武侠", "科幻", "言情"}) {
                    if (potentialCat.equals(cat)) {
                        category = potentialCat;
                        break;
                    }
                }
            }
        }

        // 清理内容开头的特殊标记
        cleanContent = cleanContent.replaceAll("^\\s*chapter\\s+\\d+\\s*-\\s*\\d+\\s*", "");
        cleanContent = cleanContent.replaceAll("^\\|[^|]+\\|", "");
        cleanContent = cleanContent.replaceAll("^\\s*[|]+\\s*", "");
        cleanContent = cleanContent.trim();

        return new TextContentInfo(cleanContent, title, category);
    }

    /**
     * 从剩余文本中提取标题（只取第X章后面的第一个短语）
     */
    private String extractTitleFromRest(String rest) {
        if (rest == null || rest.isEmpty()) {
            return null;
        }
        
        // 尝试提取"第二章 XXX"格式的标题，只取到第一个空格为止
        Pattern titlePattern = Pattern.compile("(第[\\u4e00-\\u9fa5\\d]+章)[\\s：:]*(\\S+)");
        Matcher m = titlePattern.matcher(rest);
        if (m.find()) {
            // 只返回章节编号后的第一个词/短语
            String chapterNum = m.group(1).trim();
            String titlePart = m.group(2).trim();
            return chapterNum + " " + titlePart;
        }
        
        // 尝试提取第一句话作为标题（短于30字符）
        String[] parts = rest.split("[\\n。！？]");
        if (parts.length > 0 && parts[0].length() <= 30) {
            return parts[0].trim();
        }
        
        return null;
    }

    /**
     * 判断HTML内容是否包含有意义的章节正文
     */
    private boolean isValidChapterContent(String text, String fileName) {
        if (text == null || text.trim().length() < 100) {
            return false;
        }

        String lowerFileName = fileName != null ? fileName.toLowerCase() : "";

        // 过滤已知非章节文件
        String[] excludeKeywords = {
            "cover", "封面", "title-page", "扉页",
            "copyright", "版权", "colophon", "制作信息", "制作说明",
            "nav", "toc", "目录", "contents",
            "imprint", "出版", "preface", "序", "前言",
            "dedication", "献词", "acknowledgment", "致谢",
            "about", "关于", "index", "索引"
        };
        for (String keyword : excludeKeywords) {
            if (lowerFileName.contains(keyword)) {
                return false;
            }
        }

        // 过滤只有链接没有正文的目录页
        Pattern navPattern = Pattern.compile("(?i)<nav[^>]*>.*?</nav>", Pattern.DOTALL);
        if (navPattern.matcher(text).find()) {
            // 如果页面主要是导航，提取正文部分
            String textWithoutNav = text.replaceAll("(?i)<nav[^>]*>.*?</nav>", "");
            if (stripHtmlTags(textWithoutNav).length() < 100) {
                return false;
            }
        }

        return true;
    }

    /**
     * 从章节内容开头提取标题
     */
    private String extractTitleFromContent(String content, int fallbackIndex) {
        if (content == null || content.trim().isEmpty()) {
            return "第" + fallbackIndex + "章";
        }

        // 尝试匹配开头几行的第X章格式
        String[] lines = content.split("\\r?\\n");
        int checkLines = Math.min(lines.length, 5);
        
        for (int i = 0; i < checkLines; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            // 匹配"第X章 标题"格式
            Pattern chapterTitlePattern = Pattern.compile("第[\\u4e00-\\u9fa5\\d]+章[\\s：:]*([^\\n]{0,50})");
            Matcher m = chapterTitlePattern.matcher(line);
            if (m.find()) {
                String fullTitle = m.group(0).trim();
                if (fullTitle.length() < 100 && !isNonChapterTitle(fullTitle)) {
                    return cleanTitle(fullTitle);
                }
            }
            
            // 匹配第X回/卷/节
            Pattern altTitlePattern = Pattern.compile("第[\\u4e00-\\u9fa5\\d]+[回卷节][\\s：:]*([^\\n]{0,50})");
            m = altTitlePattern.matcher(line);
            if (m.find()) {
                String fullTitle = m.group(0).trim();
                if (fullTitle.length() < 100 && !isNonChapterTitle(fullTitle)) {
                    return cleanTitle(fullTitle);
                }
            }
            
            // 如果第一行很短且可能是标题（不含标点）
            if (i == 0 && line.length() <= 30 && line.length() >= 2 && 
                !line.matches(".*[。！？，、；：\"'].*") &&
                !isNonChapterTitle(line)) {
                return line.trim();
            }
        }
        
        return "第" + fallbackIndex + "章";
    }

    // ======================== EPUB 新辅助方法 ========================

    /**
     * 【新增A】按文件名 + NCX标题 判断是否跳过此章节
     */
    private boolean shouldSkipEpubChapter(String fileName, String ncxTitle) {
        String lowerName = fileName != null ? fileName.toLowerCase() : "";
        String ncx = ncxTitle != null ? ncxTitle.trim() : "";

        // 1. 文件名匹配非正文章节模式
        String[] skipNamePatterns = {
            "^cover",       // cover.html / cover.xhtml
            "cover\\.",     // .../cover.html
            "intro\\.",     // intro.html
            "^intro",
            "copyright\\.",
            "^copyright",
            "instruction\\.",
            "^instruction",
            "pretxt\\.",
            "^pretxt",
            "tiaoshu\\.",
            "^tiaoshu",
            "toc\\.",
            "^toc",
            "nav\\.",
            "^nav"
        };
        for (String pat : skipNamePatterns) {
            if (lowerName.matches(".*" + pat + ".*")) {
                return true;
            }
        }

        // 2. NCX 标题是非正文章节（但只有100字以上才跳过，避免误删正文章节标题里包含这些词的）
        if (!ncx.isEmpty()) {
            String[] skipTitleKeywords = {
                "封面", "内容简介", "前言", "序言", "楔子",
                "版权", "制作说明", "制作信息", "出版信息",
                "目录", "目次", "致谢", "后记", "尾声"
            };
            for (String kw : skipTitleKeywords) {
                if (ncx.equals(kw) || ncx.startsWith(kw) || ncx.startsWith(kw + "：") || ncx.startsWith(kw + ":")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 【新增B】鲁棒提取 heading 标题（支持 <h2><span>第一章</span><br />肛肠科拯救世界</h2> 这种嵌套）
     */
    private String extractHeadingTitleRobust(String html) {
        if (html == null) return null;
        // 找所有 h1-h6，依次尝试
        for (int level = 1; level <= 6; level++) {
            Pattern hPattern = Pattern.compile("(?i)<h" + level + "\\b[^>]*>(.*?)</h" + level + ">", Pattern.DOTALL);
            Matcher m = hPattern.matcher(html);
            if (m.find()) {
                // 把 heading 内部所有子标签替换为空格/空
                String inner = m.group(1);
                // <br> <br/> → 空格
                inner = inner.replaceAll("(?i)<br\\s*/?>", " ");
                // 其他所有标签 → 空
                inner = inner.replaceAll("<[^>]+>", "");
                String title = inner.trim();
                // 合并多余空白
                title = title.replaceAll("\\s+", " ");
                if (!title.isEmpty() && title.length() < 150) {
                    // 跳过 "Chapter" 这种通用占位
                    if (title.toLowerCase().equals("chapter") || title.toLowerCase().equals("chapter ")) {
                        continue;
                    }
                    return title;
                }
            }
        }
        return null;
    }

    /**
     * 【新增C】多路竞争：从 NCX / heading / title 标签 / 内容开头 选最佳标题
     */
    private String pickBestChapterTitle(String ncxTitle, String headingTitle, String html, String href, int index, String textContent) {
        // 所有候选源
        String[] candidates = new String[4];
        int[] scores = new int[4];

        candidates[0] = ncxTitle;   // NCX
        candidates[1] = headingTitle; // heading
        candidates[2] = extractTitleTagOnly(html); // 纯 title 标签，仅做 fallback
        candidates[3] = null; // 占位，下面可能从内容提取

        // 质量评分
        Pattern chapterNumPattern = Pattern.compile("第[\\d一二三四五六七八九十百千万零〇两]+[章卷回部集篇]|chapter\\s*\\d+", Pattern.CASE_INSENSITIVE);
        java.util.Set<String> genericWords = new java.util.HashSet<>(java.util.Arrays.asList(
            "chapter", "章节", "章", "part", "section", "prologue", "epilogue",
            "cover", "封面", "copyright", "版权", "untitled"
        ));

        for (int i = 0; i < 3; i++) {
            String t = candidates[i];
            if (t == null || t.trim().isEmpty()) continue;
            t = t.trim();
            scores[i] = 0;
            // 有章节号前缀 → +10（最重要）
            if (chapterNumPattern.matcher(t).find()) scores[i] += 10;
            // 通用占位词 → -20
            String lower = t.toLowerCase();
            boolean isGeneric = false;
            for (String gw : genericWords) {
                if (lower.equals(gw) || lower.startsWith(gw + " ") || lower.equals(gw + " " + index)) {
                    isGeneric = true; break;
                }
            }
            if (isGeneric) scores[i] -= 20;
            // 长度加分
            int len = t.length();
            if (len >= 6) scores[i] += 2;
            if (len >= 12) scores[i] += 3;
            if (len >= 30) scores[i] += 1;
        }

        // 选最高分
        int bestIdx = -1;
        int bestScore = -999;
        for (int i = 0; i < 3; i++) {
            if (candidates[i] != null && scores[i] > bestScore) {
                bestScore = scores[i];
                bestIdx = i;
            }
        }

        String result;
        if (bestIdx >= 0 && bestScore >= 0) {
            result = candidates[bestIdx].trim();
        } else {
            // 所有候选都不可靠，从内容开头提取
            result = extractTitleFromContent(textContent, index + 1);
        }

        // 最终兜底
        if (result == null || result.trim().isEmpty()) {
            result = "第" + (index + 1) + "章";
        }

        return result.trim();
    }

    /**
     * 只从 <title> 标签提取（作为最低优先级 fallback）
     */
    private String extractTitleTagOnly(String html) {
        if (html == null) return null;
        Pattern p = Pattern.compile("(?i)<title[^>]*>([^<]+)</title>");
        Matcher m = p.matcher(html);
        if (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    /**
     * 【新增D】清理正文开头的标题残留行
     */
    private String stripLeadingTitleFromContent(String content, String chapterTitle) {
        if (content == null || content.trim().isEmpty()) return content;
        String c = content.trim();

        // 1. 精确匹配标题本身
        if (chapterTitle != null && !chapterTitle.isEmpty()) {
            if (c.startsWith(chapterTitle)) {
                c = c.substring(chapterTitle.length()).trim();
                // 去掉开头多余的标点/空格
                c = c.replaceAll("^[\\s，。：:、；;！？\\-—]+", "").trim();
            }
        }

        // 2. 通用：开头的 "Chapter" 字样
        c = c.replaceAll("(?i)^chapter\\s*\\d*\\s*-\\s*\\d*\\s*\\|?\\s*", "");
        c = c.replaceAll("(?i)^chapter\\s*\\d+\\s*", "");

        // 3. 通用：开头独立的 "第X章 XXX" 行
        Pattern leadingChapterLine = Pattern.compile(
            "^\\s*第[\\d一二三四五六七八九十百千万零〇两]+[章卷回部集篇][\\s：:、]*(?:.{1,50})?\\s*[\\n。！？]",
            Pattern.MULTILINE
        );
        Matcher m = leadingChapterLine.matcher(c);
        if (m.find()) {
            // 只删第一行（如果紧跟的是句号或换行）
            String matched = m.group(0);
            int cutLen = matched.length();
            if (cutLen > c.length()) cutLen = c.length();
            c = c.substring(cutLen).trim();
        }

        return c;
    }

    // 批量导入章节
    @PostMapping("/chapters")
    @Transactional
    public ApiResponse<String> importChapters(@RequestBody Map<String, Object> request) {
        try {
            Long bookId = Long.valueOf(request.get("bookId").toString());
            List<Map<String, Object>> chapters = (List<Map<String, Object>>) request.get("chapters");

            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("书籍不存在"));

            int count = 0;
            for (Map<String, Object> ch : chapters) {
                Chapter chapter = new Chapter();
                chapter.setBookId(bookId);
                chapter.setTitle((String) ch.get("title"));
                chapter.setContent((String) ch.getOrDefault("content", ""));
                chapter.setSortOrder(count + 1);
                chapter.setSortKey("1-" + String.format("%04d", count + 1));
                chapter.setWordCount(((String) ch.getOrDefault("content", "")).length());
                chapterRepository.save(chapter);
                count++;
            }

            book.setChapterCount(book.getChapterCount() + count);
            // 重新计算书籍总字数（去空格）
            List<Chapter> allChapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
            int totalWordCount = 0;
            for (Chapter c : allChapters) {
                if (c.getContent() != null) {
                    totalWordCount += c.getContent().replaceAll("\\s+", "").length();
                }
            }
            book.setWordCount(totalWordCount);
            bookRepository.save(book);

            return ApiResponse.success("成功导入 " + count + " 个章节", null);
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

}