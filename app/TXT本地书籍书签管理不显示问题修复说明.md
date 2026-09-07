# TXT本地书籍书签管理不显示问题 - 修复说明

## 问题描述

用户反馈：**"显示添加书签成功，但在书签管理内没有"**

**现象**：
1. 打开TXT本地书籍
2. 点击添加书签 → 提示"书签已添加" ✅
3. 打开书签管理弹窗 → **列表为空** ❌
4. 看不到刚才添加的书签

## 问题根源分析

### 问题分析

**添加书签**（ReadActivity.addLocalBookmark）：
```java
// ✅ 正确：保存到 SharedPreferences
SharedPreferences sp = getSharedPreferences("local_bookmarks_" + bookId, MODE_PRIVATE);
editor.putInt("bookmark_count", count + 1);
editor.putInt("bookmark_chapterIndex_" + count, currentChapterIndex);
...
```

**读取书签**（PopupBookmarkFragment.loadBookmarks）：
```java
// ❌ 错误：只从服务器加载，没有检查是否是本地书
private void loadBookmarks() {
    if (userId == 0 || bookId == 0) return;
    
    RetrofitClient.getApiService().getBookmarks(userId, bookId)
        .enqueue(...);  // ← 调用网络API
}
```

**核心问题**：
1. **添加和读取使用不同的存储方式**
   - 添加：保存到 SharedPreferences（本地）
   - 读取：从服务器 API 获取（网络）
   
2. **PopupBookmarkFragment 没有区分局地书和网络书**
   - 总是调用网络API
   - 本地书籍的 userId=0，直接返回
   - 即使有 userId，后端也没有这本书的记录

3. **结果**：
   - 添加成功（本地存储）✅
   - 读取失败（网络API找不到）❌
   - 书签管理列表为空 ❌

## 修复方案

### 核心原则：**添加和读取使用相同的存储方式**

### 修改点1：PopupBookmarkFragment - 添加 isLocalBook 标志

**文件**：[PopupBookmarkFragment.java#L47](file:///d:/android/app/app/src/main/java/com/example/myapplication/fragment/PopupBookmarkFragment.java#L47)

```java
private boolean isLocalBook = false;  // ✅ 新增：标记是否为本地书籍

// ✅ 新增：设置是否为本地书籍
public void setIsLocalBook(boolean isLocalBook) {
    this.isLocalBook = isLocalBook;
}
```

### 修改点2：loadBookmarks - 区分局地书和网络书

**文件**：[PopupBookmarkFragment.java#L94-102](file:///d:/android/app/app/src/main/java/com/example/myapplication/fragment/PopupBookmarkFragment.java#L94-L102)

```java
private void loadBookmarks() {
    // ✅ 修正：区分局地书和网络书
    if (isLocalBook) {
        loadLocalBookmarks();
    } else {
        loadNetworkBookmarks();
    }
}
```

### 修改点3：loadLocalBookmarks - 从 SharedPreferences 读取

**文件**：[PopupBookmarkFragment.java#L104-150](file:///d:/android/app/app/src/main/java/com/example/myapplication/fragment/PopupBookmarkFragment.java#L104-L150)

```java
/**
 * 加载本地书籍的书签（从 SharedPreferences 读取）
 */
private void loadLocalBookmarks() {
    if (bookId == 0) return;
    
    SharedPreferences sp = getActivity().getSharedPreferences("local_bookmarks_" + bookId, Context.MODE_PRIVATE);
    int count = sp.getInt("bookmark_count", 0);
    
    bookmarkList.clear();
    
    for (int i = 0; i < count; i++) {
        Bookmark bookmark = new Bookmark();
        bookmark.setChapterIndex(sp.getInt("bookmark_chapterIndex_" + i, 0));
        bookmark.setChapterTitle(sp.getString("bookmark_chapterTitle_" + i, ""));
        bookmark.setScrollPosition(sp.getInt("bookmark_page_" + i, 1));
        bookmark.setPreviewText(sp.getString("bookmark_preview_" + i, ""));
        
        // 使用时间戳作为 ID（用于删除等操作）
        long time = sp.getLong("bookmark_time_" + i, 0);
        bookmark.setId(time);  // 使用时间戳作为唯一标识
        bookmark.setCreatedAt(formatTimestamp(time));
        
        bookmarkList.add(bookmark);
    }
    
    // 按时间倒序排列（最新的在前）
    bookmarkList.sort((a, b) -> Long.compare(b.getId(), a.getId()));
    
    if (getActivity() != null) {
        getActivity().runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            if (bookmarkList.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
                rvBookmarks.setVisibility(View.GONE);
            } else {
                tvEmpty.setVisibility(View.GONE);
                rvBookmarks.setVisibility(View.VISIBLE);
            }
        });
    }
}
```

**改进**：
1. **从 SharedPreferences 读取**：与添加时使用相同的存储
2. **转换为 Bookmark 对象**：适配现有的 Adapter
3. **使用时间戳作为 ID**：方便删除操作
4. **格式化时间**：显示友好的时间格式
5. **按时间倒序**：最新的书签在前

### 修改点4：loadNetworkBookmarks - 网络书籍逻辑

**文件**：[PopupBookmarkFragment.java#L152-182](file:///d:/android/app/app/src/main/java/com/example/myapplication/fragment/PopupBookmarkFragment.java#L152-L182)

将原有的网络书签加载逻辑提取到独立方法，保持功能不变。

### 修改点5：deleteBookmark - 区分删除逻辑

**文件**：[PopupBookmarkFragment.java#L232-310](file:///d:/android/app/app/src/main/java/com/example/myapplication/fragment/PopupBookmarkFragment.java#L232-L310)

```java
private void deleteBookmark(Bookmark bookmark) {
    // ✅ 修正：区分局地书和网络书的删除逻辑
    if (isLocalBook) {
        deleteLocalBookmark(bookmark);
    } else {
        deleteNetworkBookmark(bookmark);
    }
}

/**
 * 删除本地书籍的书签
 */
private void deleteLocalBookmark(Bookmark bookmark) {
    if (bookId == 0 || bookmark.getId() == null) return;
    
    SharedPreferences sp = getActivity().getSharedPreferences("local_bookmarks_" + bookId, Context.MODE_PRIVATE);
    int count = sp.getInt("bookmark_count", 0);
    
    // 找到要删除的书签索引
    int deleteIndex = -1;
    long targetTime = bookmark.getId();  // ID 就是时间戳
    
    for (int i = 0; i < count; i++) {
        long time = sp.getLong("bookmark_time_" + i, 0);
        if (time == targetTime) {
            deleteIndex = i;
            break;
        }
    }
    
    if (deleteIndex == -1) return;
    
    SharedPreferences.Editor editor = sp.edit();
    
    // 将后面的书签前移
    for (int i = deleteIndex; i < count - 1; i++) {
        editor.putInt("bookmark_chapterIndex_" + i, sp.getInt("bookmark_chapterIndex_" + (i + 1), 0));
        editor.putString("bookmark_chapterTitle_" + i, sp.getString("bookmark_chapterTitle_" + (i + 1), ""));
        editor.putInt("bookmark_page_" + i, sp.getInt("bookmark_page_" + (i + 1), 1));
        editor.putString("bookmark_preview_" + i, sp.getString("bookmark_preview_" + (i + 1), ""));
        editor.putLong("bookmark_time_" + i, sp.getLong("bookmark_time_" + (i + 1), 0));
    }
    
    // 删除最后一个
    editor.remove("bookmark_chapterIndex_" + (count - 1));
    editor.remove("bookmark_chapterTitle_" + (count - 1));
    editor.remove("bookmark_page_" + (count - 1));
    editor.remove("bookmark_preview_" + (count - 1));
    editor.remove("bookmark_time_" + (count - 1));
    
    editor.putInt("bookmark_count", count - 1);
    editor.apply();
    
    // 从列表中移除
    bookmarkList.remove(bookmark);
    adapter.notifyDataSetChanged();
    Toast.makeText(getContext(), "书签已删除", Toast.LENGTH_SHORT).show();
}
```

**改进**：
1. **从 SharedPreferences 删除**：与添加时使用相同的存储
2. **通过时间戳查找**：定位要删除的书签
3. **数组前移**：保持索引连续
4. **更新计数**：减少书签总数

### 修改点6：ReadActivity - 设置 isLocalBook 标志

**文件**：[ReadActivity.java#L968-970](file:///d:/android/app/app/src/main/java/com/example/myapplication/activity/ReadActivity.java#L968-L970)

```java
PopupBookmarkFragment bookmarkFragment = new PopupBookmarkFragment();
bookmarkFragment.setBookId(currentBook.getId());
bookmarkFragment.setIsLocalBook(isLocalBook);  // ✅ 修正：设置是否为本地书籍
bookmarkFragment.setOnBookmarkSelectedListener(bookmark -> {
    ...
});
```

**改进**：
- 传递 `isLocalBook` 标志给 Fragment
- 确保 Fragment 知道如何加载书签

## 数据流对比

### 修复前

```
添加书签：
  ReadActivity.addBookmark()
    → isLocalBook == true
    → addLocalBookmark()
    → 保存到 SharedPreferences ✅
    
读取书签：
  PopupBookmarkFragment.loadBookmarks()
    → userId == 0（本地书）
    → return;  ← ❌ 直接返回，不加载任何书签
    
结果：书签管理列表为空 ❌
```

### 修复后

```
添加书签：
  ReadActivity.addBookmark()
    → isLocalBook == true
    → addLocalBookmark()
    → 保存到 SharedPreferences ✅
    
读取书签：
  PopupBookmarkFragment.loadBookmarks()
    → isLocalBook == true
    → loadLocalBookmarks()
    → 从 SharedPreferences 读取 ✅
    → 转换为 Bookmark 对象 ✅
    → 显示在列表中 ✅
    
结果：书签管理列表正常显示 ✅
```

## 预期的用户体验

### 修复前

```
用户操作：打开TXT本地书籍，添加书签
  → Toast: "书签已添加" ✅
  
用户操作：打开书签管理
  → 列表为空 ❌
  → 用户疑惑："刚才添加的书签呢？"
  → 用户体验差！❌
```

### 修复后

```
用户操作：打开TXT本地书籍，添加书签
  → Toast: "书签已添加" ✅
  
用户操作：打开书签管理
  → 显示刚才添加的书签 ✅
  → 可以点击跳转到对应位置 ✅
  → 可以长按删除 ✅
  → 用户体验好！✅
```

## 测试建议

请重点测试以下场景：

### 1. TXT本地书籍添加和查看书签 ⭐⭐⭐⭐⭐
- 打开一本TXT本地书籍
- 阅读到某一章，添加书签
- 打开书签管理弹窗
- **验证**：
  - 能看到刚才添加的书签 ✅
  - 显示章节标题、页码、预览 ✅
  - 显示添加时间 ✅
  - 点击书签能跳转到对应位置 ✅

### 2. TXT本地书籍删除书签
- 在书签管理中长按某个书签
- 点击删除
- **验证**：
  - 书签被删除 ✅
  - 列表更新 ✅
  - SharedPreferences 中的数据也被删除 ✅

### 3. 多个本地书籍的书签隔离
- 在本地书籍A中添加书签
- 在本地书籍B中添加书签
- **验证**：
  - 每本书只显示自己的书签 ✅
  - 互不影响 ✅

### 4. 网络书籍书签功能
- 打开网络书籍
- 添加书签
- 查看书签管理
- **验证**：
  - 仍然从服务器加载 ✅
  - 功能正常 ✅

### 5. 退出重进后书签仍然存在
- 添加书签后退出阅读器
- 重新进入同一本书
- 打开书签管理
- **验证**：
  - 书签仍然存在 ✅
  - 数据持久化正常 ✅

## 技术细节

### SharedPreferences 存储结构

**文件名**：`local_bookmarks_{bookId}`

**示例**（bookId = 1781011541576，有3个书签）：

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="bookmark_count" value="3" />
    
    <!-- 书签0（最早添加） -->
    <int name="bookmark_chapterIndex_0" value="10" />
    <string name="bookmark_chapterTitle_0">第十一章 新的开始</string>
    <int name="bookmark_page_0" value="5" />
    <string name="bookmark_preview_0">这是一个精彩的章节...</string>
    <long name="bookmark_time_0" value="1717920000000" />
    
    <!-- 书签1 -->
    <int name="bookmark_chapterIndex_1" value="25" />
    <string name="bookmark_chapterTitle_1">第二十六章 转折点</string>
    <int name="bookmark_page_1" value="12" />
    <string name="bookmark_preview_1">故事在这里发生了转折...</string>
    <long name="bookmark_time_1" value="1717930000000" />
    
    <!-- 书签2（最新添加） -->
    <int name="bookmark_chapterIndex_2" value="50" />
    <string name="bookmark_chapterTitle_2">第五十一章 高潮</string>
    <int name="bookmark_page_2" value="8" />
    <string name="bookmark_preview_2">剧情达到了高潮...</string>
    <long name="bookmark_time_2" value="1717940000000" />
</map>
```

### 删除操作的数组前移算法

当删除索引为1的书签时：

```
删除前：
  [0] 书签A
  [1] 书签B ← 要删除
  [2] 书签C
  
删除过程：
  1. 将 [2] 复制到 [1]
  2. 删除 [2]
  3. 更新 count = 2
  
删除后：
  [0] 书签A
  [1] 书签C
```

代码实现：
```java
for (int i = deleteIndex; i < count - 1; i++) {
    // 将 i+1 的数据复制到 i
    editor.putInt("bookmark_chapterIndex_" + i, sp.getInt("bookmark_chapterIndex_" + (i + 1), 0));
    ...
}
// 删除最后一个
editor.remove("bookmark_chapterIndex_" + (count - 1));
...
editor.putInt("bookmark_count", count - 1);
```

## 总结

这次修复解决了**TXT本地书籍书签管理不显示**的问题：

**之前**：
- 添加：保存到 SharedPreferences（本地）
- 读取：从服务器 API 获取（网络）
- 存储方式不一致
- 书签管理列表为空

**现在**：
- 添加：保存到 SharedPreferences（本地）
- 读取：从 SharedPreferences 读取（本地）
- 存储方式一致
- 书签管理列表正常显示

**关键改进**：
1. **统一存储方式**：添加和读取都使用 SharedPreferences
2. **区分局地书和网络书**：根据 isLocalBook 标志选择加载方式
3. **完整的 CRUD**：支持添加、查看、删除本地书签
4. **数据一致性**：添加后立即能在列表中看到

**预期效果**：
- TXT本地书籍的书签能正常显示在管理中 ✅
- 可以点击跳转到对应位置 ✅
- 可以长按删除 ✅
- 退出重进后仍然存在 ✅

请重新运行应用并测试，验证TXT本地书籍的书签管理功能是否正常工作！
