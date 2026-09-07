# ReadActivity 管理器使用指南

## ✅ 已完成集成

### 1. 导入管理器类

```java
// 在ReadActivity.java顶部添加
import com.example.myapplication.manager.ReadingStateManager;
import com.example.myapplication.manager.ChapterLoader;
```

### 2. 声明管理器实例

```java
public class ReadActivity extends BaseActivity {
    // ... 其他字段
    
    // ✅ 新增：管理器实例
    private ReadingStateManager stateManager;
    private ChapterLoader chapterLoader;
    
    // ... 其他字段
}
```

### 3. 在onCreate中初始化

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_read);

    currentBook = (Book) getIntent().getSerializableExtra("book");
    if (currentBook == null) { finish(); return; }

    // ✅ 初始化管理器
    stateManager = new ReadingStateManager();
    chapterLoader = new ChapterLoader(this);
    
    // 从Intent获取初始值并设置到管理器
    stateManager.setCurrentChapterIndex(getIntent().getIntExtra("chapterIndex", 0));
    stateManager.setCurrentFontSize(getIntent().getFloatExtra("fontSize", 28f));
    stateManager.setNightMode(getIntent().getBooleanExtra("nightMode", false));
    stateManager.setLocalBook(getIntent().getBooleanExtra("isLocal", false));
    
    // 同步到原有变量（过渡期兼容）
    currentChapterIndex = stateManager.getCurrentChapterIndex();
    currentFontSize = stateManager.getCurrentFontSize();
    isNightMode = stateManager.isNightMode();
    isLocalBook = stateManager.isLocalBook();
    
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    
    // ... 其余初始化代码
}
```

---

## 📖 使用示例

### ReadingStateManager 使用

#### 1. 访问状态

```java
// ❌ 旧方式：直接访问字段
int index = currentChapterIndex;
boolean restored = positionRestored;

// ✅ 新方式：通过管理器
int index = stateManager.getCurrentChapterIndex();
boolean restored = stateManager.isPositionRestored();
```

#### 2. 修改状态

```java
// ❌ 旧方式
currentChapterIndex = nextIndex;
positionRestored = true;

// ✅ 新方式
stateManager.setCurrentChapterIndex(nextIndex);
stateManager.setPositionRestored(true);
```

#### 3. 更新页面信息（从JS回调）

```java
public class JsBridge {
    @JavascriptInterface
    public void onPageChanged(int page, int totalPages) {
        runOnUiThread(() -> {
            // ✅ 使用管理器更新页面信息
            stateManager.updatePageInfo(page, totalPages);
            
            // 更新UI
            updateProgressDisplay();
        });
    }
}
```

#### 4. 状态验证

```java
// 检查是否可以跳转到指定页码
if (stateManager.canJumpToPage(targetPage)) {
    jumpToPage(targetPage);
}

// 检查是否有下一章
if (stateManager.hasNextChapter(chapterLoader.getChapterCount())) {
    loadNextChapter();
}

// 检查是否有上一章
if (stateManager.hasPrevChapter()) {
    loadPrevChapter();
}
```

#### 5. 调试输出

```java
// 打印当前状态
Log.d("ReadActivity", stateManager.toString());
// 输出: ReadingStateManager{chapter=5, page=3/20, fontSize=28.0, nightMode=false, ...}
```

---

### ChapterLoader 使用

#### 1. 加载章节列表

```java
// ❌ 旧方式
private void loadChaptersFromServer() {
    RetrofitClient.getApiService().getChapters(bookId).enqueue(...);
}

// ✅ 新方式
long bookId = currentBook.getId();
chapterLoader.loadChaptersFromServer(bookId, new ChapterLoader.ChapterLoadCallback() {
    @Override
    public void onSuccess(List<ChapterDto> chapters) {
        runOnUiThread(() -> {
            // 更新UI
            chapterList.clear();
            chapterList.addAll(chapters);
            
            // 恢复阅读位置
            restoreReadingPosition(stateManager.getCurrentChapterIndex());
            
            Log.d("ReadActivity", "Loaded " + chapters.size() + " chapters");
        });
    }
    
    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            Toast.makeText(ReadActivity.this, "加载失败: " + error, Toast.LENGTH_SHORT).show();
        });
    }
});
```

#### 2. 获取章节内容

```java
// ❌ 旧方式
private void fetchChapterContent(int index) {
    // 复杂的缓存检查和网络请求逻辑
}

// ✅ 新方式
long bookId = currentBook.getId();
int chapterIndex = stateManager.getCurrentChapterIndex();

chapterLoader.getChapterContent(chapterIndex, bookId, new ChapterLoader.ContentLoadCallback() {
    @Override
    public void onSuccess(int index, String content) {
        runOnUiThread(() -> {
            // 渲染章节内容
            renderChapterContent(index, chapterLoader.getChapter(index).getTitle(), content);
            
            Log.d("ReadActivity", "Chapter " + index + " loaded successfully");
        });
    }
    
    @Override
    public void onError(int index, String error) {
        runOnUiThread(() -> {
            Toast.makeText(ReadActivity.this, "章节加载失败: " + error, Toast.LENGTH_SHORT).show();
        });
    }
});
```

#### 3. 检查章节是否已加载

```java
int nextIndex = stateManager.getCurrentChapterIndex() + 1;

if (chapterLoader.isChapterLoaded(nextIndex)) {
    // 章节已加载，可以直接跳转
    loadChapterContent(nextIndex);
} else {
    // 章节未加载，需要先加载
    chapterLoader.getChapterContent(nextIndex, bookId, callback);
}
```

#### 4. 访问章节数据

```java
// 获取章节列表
List<ChapterDto> chapters = chapterLoader.getChapterList();
int totalChapters = chapterLoader.getChapterCount();

// 获取指定章节
ChapterDto chapter = chapterLoader.getChapter(5);
String title = chapter.getTitle();

// 获取章节内容
String content = chapterLoader.getContent(5);
```

#### 5. 清理缓存

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    
    // 清理缓存，释放内存
    if (chapterLoader != null) {
        chapterLoader.clearAllCache();
    }
}
```

---

## 🔄 迁移策略

### 阶段1：双轨运行（当前阶段）

**目标**：保持原有代码不变，逐步引入管理器

```java
// 同时维护两套变量
private int currentChapterIndex = 0;  // 旧变量
private ReadingStateManager stateManager;  // 新管理器

// 在修改时同步更新
private void switchToChapter(int index) {
    // 更新旧变量
    currentChapterIndex = index;
    
    // 更新管理器
    stateManager.setCurrentChapterIndex(index);
}
```

**优点**：
- ✅ 安全，不会破坏现有功能
- ✅ 可以逐步测试
- ✅ 出现问题容易回滚

**缺点**：
- ⚠️ 代码冗余
- ⚠️ 需要同步维护

### 阶段2：逐步替换

**目标**：将旧的字段访问替换为管理器调用

**步骤**：
1. 使用IDE的"Find Usages"找到所有`currentChapterIndex`的引用
2. 逐个替换为`stateManager.getCurrentChapterIndex()`和`stateManager.setCurrentChapterIndex()`
3. 每次替换后编译测试
4. 重复直到所有状态变量都迁移完成

**示例**：
```java
// 查找: currentChapterIndex
// 替换为: stateManager.getCurrentChapterIndex() / setCurrentChapterIndex()

// 旧代码
if (currentChapterIndex < chapterList.size() - 1) {
    currentChapterIndex++;
}

// 新代码
if (stateManager.hasNextChapter(chapterLoader.getChapterCount())) {
    stateManager.setCurrentChapterIndex(stateManager.getCurrentChapterIndex() + 1);
}
```

### 阶段3：删除旧代码

**目标**：移除所有旧的状态变量和相关方法

```java
// ❌ 删除这些字段
private int currentChapterIndex = 0;
private boolean positionRestored = false;
private boolean webViewReady = false;
// ... 等等

// ✅ 只保留管理器
private ReadingStateManager stateManager;
private ChapterLoader chapterLoader;
```

---

## 💡 最佳实践

### 1. 线程安全

```java
// ChapterLoader的回调在子线程
// 更新UI必须回到主线程
chapterLoader.getChapterContent(index, bookId, new ContentLoadCallback() {
    @Override
    public void onSuccess(int index, String content) {
        runOnUiThread(() -> {
            // ✅ 在主线程更新UI
            renderChapterContent(content);
        });
    }
});
```

### 2. 空值检查

```java
// 始终检查返回值
ChapterDto chapter = chapterLoader.getChapter(index);
if (chapter != null) {
    String title = chapter.getTitle();
    // 使用title
}
```

### 3. 错误处理

```java
chapterLoader.getChapterContent(index, bookId, new ContentLoadCallback() {
    @Override
    public void onSuccess(int index, String content) {
        // 处理成功
    }
    
    @Override
    public void onError(int index, String error) {
        // ✅ 显示友好的错误提示
        Toast.makeText(ReadActivity.this, 
            "第" + (index + 1) + "章加载失败: " + error, 
            Toast.LENGTH_LONG).show();
        
        // 记录日志
        Log.e("ReadActivity", "Failed to load chapter " + index, new Exception(error));
    }
});
```

### 4. 资源管理

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    
    // ✅ 清理资源
    if (chapterLoader != null) {
        chapterLoader.clearAllCache();
    }
    
    // 取消自动翻页
    if (autoPageHandler != null) {
        autoPageHandler.removeCallbacksAndMessages(null);
    }
}
```

---

## 🎯 下一步行动

### 今天完成
1. ✅ 在ReadActivity中初始化管理器（已完成）
2. ⏳ 测试管理器是否正常工作
3. ⏳ 开始替换简单的状态访问

### 本周完成
4. ⏳ 替换所有章节索引相关的代码
5. ⏳ 替换所有页面信息相关的代码
6. ⏳ 迁移章节加载逻辑到ChapterLoader
7. ⏳ 全面测试

### 下周完成
8. ⏳ 创建ReadingSettingsManager
9. ⏳ 创建ProgressTracker
10. ⏳ 删除旧的状态变量
11. ⏳ 最终测试和优化

---

## 📊 进度跟踪

| 任务 | 状态 | 备注 |
|------|------|------|
| 创建ReadingStateManager | ✅ 完成 | 已实现 |
| 创建ChapterLoader | ✅ 完成 | 已实现 |
| 在ReadActivity中初始化 | ✅ 完成 | 已集成 |
| 替换状态访问 | ⏳ 进行中 | 需要逐步替换 |
| 迁移章节加载 | ⏸️ 待开始 | 下一步工作 |
| 创建ReadingSettingsManager | ⏸️ 待开始 | 下周工作 |
| 创建ProgressTracker | ⏸️ 待开始 | 下周工作 |
| 删除旧代码 | ⏸️ 待开始 | 最后阶段 |

---

**开始使用管理器吧！记住：小步快跑，持续测试！** 🚀
