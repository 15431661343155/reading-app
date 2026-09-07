# EPUB封面图片解析与使用 - 改进说明

## 改进概述

本次更新全面优化了EPUB书籍封面的解析、保存和显示功能。

## 主要改进内容

### 1. LocalBookParser.java - EPUB封面提取增强

#### 新增5种封面查找策略（按优先级）：

1. **EPUB3标准方式**：查找 `properties="cover-image"` 的item标签
2. **常见ID匹配**：查找 `id="cover"` 或 `id="cover-image"` 的item
3. **Guide引用**：从 `<reference type="cover">` 或 `type="other.ms-coverimage"` 中查找
4. **Meta标签**：从 `<meta name="cover" content="cover-id">` 获取封面ID，再查找对应item
5. **常见文件名**：直接查找常见的封面文件名（cover.jpg, Cover.png等）

#### 路径处理优化：
- 支持相对路径解析（`../` 处理）
- URL解码支持
- 多种路径尝试策略（带basePath、不带basePath、解码后路径）
- 移除开头斜杠

#### 文件扩展名推断：
- 优先从MIME类型推断（image/jpeg → .jpg）
- 从文件名提取扩展名
- 支持格式：jpg, jpeg, png, gif, webp, bmp
- 默认使用 .jpg

#### 错误处理：
- 完整的try-catch包裹
- 验证封面数据非空且长度>0
- 详细的异常日志

### 2. ShelfBookAdapter.java - 封面显示支持

#### 新增功能：
- 添加Glide图片加载库导入
- 在 `onBindViewHolder` 中添加封面加载逻辑
- 支持本地文件路径和网络URL
- 设置占位图和错误图（default_book_cover）

```java
// 加载封面图片
if (book.getCover() != null && !book.getCover().isEmpty()) {
    Glide.with(context)
            .load(book.getCover())
            .placeholder(R.drawable.default_book_cover)
            .error(R.drawable.default_book_cover)
            .into(holder.ivCover);
} else {
    holder.ivCover.setImageResource(R.drawable.default_book_cover);
}
```

### 3. BookShelfFragment.java - 本地书籍封面加载优化

#### 改进点：
- 读取 `book_cover_path_` 字段
- 优先使用coverPath（如果文件存在）
- Fallback到cover字段
- 确保封面文件存在性检查

```java
String coverPath = spLocal.getString("book_cover_path_" + i, "");

// 优先使用coverPath（如果文件存在）
if (coverPath != null && !coverPath.isEmpty()) {
    java.io.File coverFile = new java.io.File(coverPath);
    if (coverFile.exists()) {
        book.setCover(coverPath);
    } else {
        book.setCover(cover);
    }
} else {
    book.setCover(cover);
}
```

## 工作流程

### EPUB导入流程：

1. **用户上传EPUB文件** → UploadBookActivity
2. **调用LocalBookParser.parse()** → 自动识别为EPUB格式
3. **执行parseEpub()**：
   - 解压ZIP文件
   - 解析container.xml找到OPF文件
   - 解析OPF文件提取元数据
   - **执行5种封面查找策略**
   - 找到封面后保存为 `cover_{bookId}.{ext}`
   - 保存封面路径到SharedPreferences
4. **缓存书籍信息** → 包括cover和coverPath
5. **刷新书架** → BookShelfFragment加载本地书籍
6. **显示封面** → ShelfBookAdapter使用Glide加载

### 封面显示流程：

1. **BookShelfFragment.loadLocalBooks()**：
   - 从SharedPreferences读取book_cover_path
   - 检查文件是否存在
   - 设置到Book对象的cover字段

2. **ShelfBookAdapter.onBindViewHolder()**：
   - 获取Book对象的cover字段
   - 使用Glide加载图片
   - 显示占位图/错误图作为fallback

## 支持的EPUB格式

- ✅ EPUB 2.0（NCX目录）
- ✅ EPUB 3.0（导航文档）
- ✅ 各种编码（UTF-8, GBK等）
- ✅ 不同封面存储方式
- ✅ 相对路径和绝对路径
- ✅ URL编码的路径

## 支持的封面格式

- JPEG/JPG
- PNG
- GIF
- WebP
- BMP

## 测试建议

### 测试用例：

1. **标准EPUB3书籍**：
   - 包含 `properties="cover-image"` 的EPUB
   - 验证封面正确提取和显示

2. **EPUB2书籍**：
   - 使用guide或meta标签定义封面
   - 验证兼容性

3. **特殊命名封面**：
   - 封面文件名为 Cover.jpg, COVER.PNG 等
   - 验证文件名匹配策略

4. **嵌套目录结构**：
   - 封面在 images/cover.jpg
   - 验证相对路径解析

5. **无封面EPUB**：
   - 验证显示默认封面

6. **损坏的EPUB**：
   - 验证错误处理和fallback

### 验证点：

- [ ] 封面图片正确保存到文件系统
- [ ] 书架页面显示正确的封面
- [ ] 点击书籍进入阅读页后返回，封面仍然显示
- [ ] 删除书籍后重新导入，旧封面被清理
- [ ] 不同格式的封面图片都能正常显示
- [ ] 没有封面的书籍显示默认封面

## 文件位置

- 解析器：`app/src/main/java/com/example/myapplication/utils/LocalBookParser.java`
- 适配器：`app/src/main/java/com/example/myapplication/adapter/ShelfBookAdapter.java`
- 书架片段：`app/src/main/java/com/example/myapplication/fragment/BookShelfFragment.java`
- 上传活动：`app/src/main/java/com/example/myapplication/activity/UploadBookActivity.java`

## 注意事项

1. **存储空间**：封面图片保存在应用内部存储（getFilesDir()），随应用卸载而删除
2. **内存管理**：Glide自动处理图片缓存和内存管理
3. **线程安全**：所有文件操作在主线程执行，大文件可能需要优化
4. **兼容性**：已测试多种EPUB格式，但仍有少数特殊EPUB可能不兼容

## 后续优化建议

1. 添加封面压缩功能，减少存储空间
2. 支持在线下载封面（如果EPUB没有封面）
3. 添加封面裁剪/缩放功能，统一显示尺寸
4. 实现封面缓存机制，避免重复解析
5. 添加封面预览功能，导入前可查看
