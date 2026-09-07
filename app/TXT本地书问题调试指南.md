# TXT本地书数据不显示问题 - 调试指南

## 当前状态

已经添加了详细的调试日志来帮助诊断问题。

## 如何使用日志诊断问题

### 步骤1：运行应用并复现问题

1. 导入一本TXT本地书
2. 阅读某一章节
3. 退出阅读器
4. 重新进入同一章节
5. 观察是否出现空白页面

### 步骤2：查看Logcat日志

在Android Studio中打开Logcat，过滤标签为 `ReadActivity`。

### 步骤3：分析日志输出

#### 正常情况的日志示例

```
D/ReadActivity: loadLocalBookChapters: bookId=123456, targetChapter=5, totalBooks=3
D/ReadActivity: Found book at index 0, chapterCount=10
D/ReadActivity: Chapter 0 content length: 5234
D/ReadActivity: Chapter 1 content length: 4521
D/ReadActivity: Chapter 2 content length: 6789
D/ReadActivity: Loaded 10 chapters, first chapter content length: 5234
```

**说明**：
- ✅ 找到了书籍（bookId匹配）
- ✅ 章节数量正确（chapterCount=10）
- ✅ 内容有数据（content length > 0）

#### 异常情况1：找不到书籍

```
D/ReadActivity: loadLocalBookChapters: bookId=123456, targetChapter=5, totalBooks=3
(没有 "Found book at index" 的日志)
D/ReadActivity: Loaded 0 chapters, first chapter content length: 0
```

**原因**：SharedPreferences 中没有找到匹配的 bookId

**可能的问题**：
1. 书籍被删除了
2. bookId 不匹配（可能是类型转换问题）
3. SharedPreferences 数据损坏

**解决方案**：
- 检查书籍是否正确导入
- 检查 bookId 的值是否正确
- 尝试重新导入书籍

#### 异常情况2：章节内容为空

```
D/ReadActivity: loadLocalBookChapters: bookId=123456, targetChapter=5, totalBooks=3
D/ReadActivity: Found book at index 0, chapterCount=10
D/ReadActivity: Chapter 0 content length: 0
D/ReadActivity: Chapter 1 content length: 0
D/ReadActivity: Chapter 2 content length: 0
D/ReadActivity: Loaded 10 chapters, first chapter content length: 0
```

**原因**：SharedPreferences 中章节内容为空字符串

**可能的问题**：
1. **SharedPreferences 大小限制**：内容太大，超出限制（通常最大1MB）
2. **写入失败**：导入时保存失败
3. **数据被清除**：其他操作清除了数据

**解决方案**：
- 检查章节内容的大小
- 如果内容很大（>100KB），考虑使用文件存储
- 重新导入书籍

#### 异常情况3：重新加载仍为空

```
D/ReadActivity: reloadLocalChapterContent: bookId=123456, chapterIndex=5, totalBooks=3
D/ReadActivity: Found book at index 0, chapterCount=10
D/ReadActivity: Read content length: 0, isEmpty: true
E/ReadActivity: Content is empty! Key: chapter_content_0_5
```

**原因**：即使重新从 SharedPreferences 读取，内容仍然是空的

**说明**：这确认了 SharedPreferences 中确实没有数据

**解决方案**：
- 必须重新导入书籍
- 或者修改存储方式（使用文件或数据库）

#### 异常情况4：章节索引越界

```
D/ReadActivity: reloadLocalChapterContent: bookId=123456, chapterIndex=15, totalBooks=3
D/ReadActivity: Found book at index 0, chapterCount=10
E/ReadActivity: Chapter index out of range: 15, chCount=10
```

**原因**：请求的章节索引超出了实际章节数量

**可能的问题**：
1. 阅读记录中的 chapterIndex 错误
2. 书籍章节数量发生了变化

**解决方案**：
- 清除阅读记录
- 重新从第一章开始阅读

## 常见问题和解决方案

### 问题1：SharedPreferences 大小限制

**症状**：
- 小章节正常显示
- 大章节（>50KB）显示空白
- 日志显示 content length: 0

**原因**：
SharedPreferences 有大小限制（通常1MB左右），如果所有章节内容加起来超过限制，后面的内容可能无法保存。

**解决方案A：使用文件存储（推荐）**

修改 `UploadBookActivity.java` 中的 `cacheLocalBook` 方法：

```java
private void cacheLocalBook(long bookId, String bookName, String author, LocalBookParser.BookInfo bookInfo) {
    // 保存书籍元数据到 SharedPreferences
    SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
    SharedPreferences.Editor editor = sp.edit();
    
    int count = sp.getInt("count", 0);
    int index = count;
    editor.putInt("count", count + 1);
    editor.putLong("book_id_" + index, bookId);
    editor.putString("book_name_" + index, bookName);
    editor.putString("book_author_" + index, author);
    editor.putString("book_cover_" + index, bookInfo.cover != null ? bookInfo.cover : "");
    editor.putString("book_cover_path_" + index, bookInfo.coverPath != null ? bookInfo.coverPath : "");
    editor.putInt("chapter_count_" + index, bookInfo.chapters.size());
    editor.apply();
    
    // ✅ 将章节内容保存到文件
    File chapterDir = new File(getFilesDir(), "chapters_" + bookId);
    if (!chapterDir.exists()) {
        chapterDir.mkdirs();
    }
    
    for (int i = 0; i < bookInfo.chapters.size(); i++) {
        // 保存标题
        File titleFile = new File(chapterDir, "title_" + i + ".txt");
        writeFile(titleFile, bookInfo.chapters.get(i).title);
        
        // 保存内容
        File contentFile = new File(chapterDir, "content_" + i + ".txt");
        writeFile(contentFile, bookInfo.chapters.get(i).content);
    }
}

private void writeFile(File file, String content) {
    try (FileWriter writer = new FileWriter(file)) {
        writer.write(content);
    } catch (IOException e) {
        e.printStackTrace();
    }
}
```

修改 `ReadActivity.java` 中的读取方法：

```java
private void reloadLocalChapterContent(int chapterIndex) {
    // 从文件读取内容
    File chapterDir = new File(getFilesDir(), "chapters_" + currentBook.getId());
    File contentFile = new File(chapterDir, "content_" + chapterIndex + ".txt");
    File titleFile = new File(chapterDir, "title_" + chapterIndex + ".txt");
    
    if (contentFile.exists() && contentFile.length() > 0) {
        String content = readFile(contentFile);
        String title = readFile(titleFile);
        
        if (content != null && !content.isEmpty()) {
            if (title != null && !title.isEmpty()) {
                chapterList.get(chapterIndex).setTitle(title);
            }
            chapterContents.set(chapterIndex, content);
        }
    }
}

private String readFile(File file) {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
            sb.append("\n");
        }
    } catch (IOException e) {
        e.printStackTrace();
        return null;
    }
    return sb.toString();
}
```

**优势**：
- 没有大小限制
- 读写性能更好
- 更适合大文本存储

**解决方案B：使用 Room 数据库**

创建实体类：

```java
@Entity(tableName = "chapter_cache")
public class ChapterCache {
    @PrimaryKey(autoGenerate = true)
    public long id;
    
    @ColumnInfo(name = "book_id")
    public long bookId;
    
    @ColumnInfo(name = "chapter_index")
    public int chapterIndex;
    
    @ColumnInfo(name = "title")
    public String title;
    
    @ColumnInfo(name = "content")
    public String content;
    
    @ColumnInfo(name = "timestamp")
    public long timestamp;
}
```

**优势**：
- 结构化存储
- 支持查询和索引
- 事务安全

### 问题2：并发访问导致数据不一致

**症状**：
- 有时正常，有时空白
- 退出重进多次后出现问题

**原因**：
多个地方同时读写 SharedPreferences，导致数据不一致。

**解决方案**：
使用同步机制：

```java
private static final Object LOCK = new Object();

private void reloadLocalChapterContent(int chapterIndex) {
    synchronized (LOCK) {
        // 原有的读取逻辑
    }
}

private void cacheLocalBook(...) {
    synchronized (LOCK) {
        // 原有的保存逻辑
    }
}
```

### 问题3：书籍索引变化导致key不匹配

**症状**：
- 删除某本书后，其他书的内容错乱
- 读取到错误的章节内容

**原因**：
删除书籍后，后续书籍的索引发生变化，但key仍然使用旧索引。

**解决方案**：
使用 bookId 作为key的一部分，而不是索引：

```java
// 保存时
editor.putString("chapter_content_" + bookId + "_" + chapterIndex, content);

// 读取时
String content = sp.getString("chapter_content_" + bookId + "_" + chapterIndex, "");
```

这样即使书籍被删除或重新排序，key也不会变化。

## 调试建议

### 1. 检查导入时的日志

在 `UploadBookActivity` 中添加日志：

```java
private void cacheLocalBook(long bookId, String bookName, String author, LocalBookParser.BookInfo bookInfo) {
    Log.d("UploadBook", "Saving book: " + bookName + ", chapters: " + bookInfo.chapters.size());
    
    for (int i = 0; i < bookInfo.chapters.size(); i++) {
        String content = bookInfo.chapters.get(i).content;
        Log.d("UploadBook", "Chapter " + i + " content length: " + (content != null ? content.length() : 0));
        
        editor.putString("chapter_content_" + index + "_" + i, content);
    }
    
    editor.apply();
    Log.d("UploadBook", "Book saved successfully");
}
```

### 2. 检查 SharedPreferences 的实际内容

可以使用 Device File Explorer 查看 SharedPreferences 文件：

```
/data/data/com.example.myapplication/shared_prefs/local_books.xml
```

或者使用 adb 命令：

```bash
adb shell run-as com.example.myapplication cat shared_prefs/local_books.xml
```

### 3. 测试不同大小的章节

创建测试文件：
- 小章节：1KB
- 中等章节：10KB
- 大章节：100KB
- 超大章节：500KB

测试每种大小是否能正常保存和读取。

## 推荐的长期解决方案

### 方案1：混合存储（推荐）

- **SharedPreferences**：存储书籍元数据（书名、作者、章节数等）
- **文件系统**：存储章节内容（每个章节一个文件）

**优势**：
- 元数据快速访问
- 章节内容无大小限制
- 易于管理和备份

### 方案2：Room 数据库

使用 SQLite 数据库存储所有数据。

**优势**：
- 结构化存储
- 支持复杂查询
- 事务安全
- 性能优秀

### 方案3：保持 SharedPreferences，但优化

- 压缩章节内容后再存储
- 只缓存最近阅读的章节
- 定期清理旧缓存

**优势**：
- 改动最小
- 实现简单

**劣势**：
- 仍有大小限制
- 需要额外的压缩/清理逻辑

## 下一步行动

1. **运行应用，复现问题**
2. **查看 Logcat 日志，确定具体是哪种异常情况**
3. **根据日志输出，选择对应的解决方案**
4. **如果确认是 SharedPreferences 大小限制，实施方案1（文件存储）**

请提供 Logcat 的输出结果，我可以帮你进一步分析问题所在。
