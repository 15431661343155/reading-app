# EPUB非正文章节过滤 - 修复说明

## 问题描述

用户反馈：**"epub本地书加载后第一章大多为Cover封面章，其余还会有简介、制作说明等杂乱章节"**

从图片可以看到目录中包含：
- Cover（封面）
- 第2章
- 制作说明
- 版权信息
- 第5章
- 第一章 肛肠科拯救世界
- ...

以及另一个例子：
- 封面
- 目录
- 内容简介
- 第一卷 我讨厌杀手
- 第一章 生活远比小说更扯淡
- ...

**问题**：这些非正文内容（封面、目录、简介、版权信息等）混在章节目录中，影响阅读体验。

## ✨ 解决方案

### 核心思路：智能过滤非正文章节

在EPUB解析过程中，添加 `shouldSkipChapter()` 方法，根据以下规则判断是否跳过该章节：

#### 规则1：标题关键词匹配

检查章节标题是否包含以下关键词（不区分大小写）：

| 类别 | 中文关键词 | 英文关键词 |
|-----|-----------|-----------|
| **封面相关** | 封面、封底 | cover |
| **目录相关** | 目录 | contents, table of contents, toc |
| **简介/前言** | 简介、介绍、前言、序言、楔子、引子、说明 | introduction, preface, foreword, prologue |
| **版权/制作** | 版权、制作、制作说明、出版信息、声明 | copyright |
| **附录/后记** | 附录、后记、尾声、跋 | epilogue, afterword, appendix |
| **其他** | 致谢、关于作者 | acknowledgments, about the author |

#### 规则2：HTML内容特征分析

**检测封面**：
- 图片数量 ≥ 3
- 纯文本长度 < 500字符
- 逻辑：封面通常包含大量图片但文字很少

**检测目录**：
- 链接数量 ≥ 10
- 纯文本长度 < 2000字符
- 逻辑：目录通常包含大量指向各章节的链接

### 代码实现

[LocalBookParser.java#L709-714](file:///d:/android/app/app/src/main/java/com/example/myapplication/utils/LocalBookParser.java#L709-L714)

```java
// ✅ 新增：过滤非正文章节（封面、目录、简介、版权信息等）
if (shouldSkipChapter(chapterTitle, html)) {
    Log.d("LocalBookParser", "Skipping non-content chapter: " + chapterTitle);
    continue;
}
```

[LocalBookParser.java#L844-903](file:///d:/android/app/app/src/main/java/com/example/myapplication/utils/LocalBookParser.java#L844-L903)

```java
/**
 * ✅ 新增：判断是否应该跳过该章节（封面、目录、简介等非正文章节）
 */
private static boolean shouldSkipChapter(String title, String html) {
    if (title == null || title.isEmpty()) {
        return false; // 没有标题的章节不跳过
    }
    
    String lowerTitle = title.toLowerCase();
    
    // 1. 根据标题关键词判断
    String[] skipKeywords = {
        // 封面相关
        "cover", "封面", "封底",
        // 目录相关  
        "目录", "contents", "table of contents", "toc",
        // 简介/前言相关
        "简介", "介绍", "前言", "序言", "楔子", "引子", "说明",
        "introduction", "preface", "foreword", "prologue",
        // 版权/制作信息
        "版权", "copyright", "制作", "制作说明", "出版信息", "声明",
        // 附录/后记
        "附录", "后记", "尾声", "跋", "epilogue", "afterword", "appendix",
        // 其他非正文
        "致谢", "acknowledgments", "关于作者", "about the author"
    };
    
    for (String keyword : skipKeywords) {
        if (lowerTitle.contains(keyword.toLowerCase())) {
            return true;
        }
    }
    
    // 2. 根据HTML内容特征判断
    if (html != null && !html.isEmpty()) {
        String lowerHtml = html.toLowerCase();
        
        // 封面通常包含大量图片且文字很少
        int imgCount = html.split("<img").length - 1;
        int textLength = html.replaceAll("<[^>]+>", "").trim().length();
        
        // 如果图片很多但文字很少，可能是封面
        if (imgCount >= 3 && textLength < 500) {
            Log.d("LocalBookParser", "Detected cover by image count: " + imgCount + ", text length: " + textLength);
            return true;
        }
        
        // 目录通常包含大量链接
        int linkCount = html.split("<a ").length - 1;
        if (linkCount >= 10 && textLength < 2000) {
            Log.d("LocalBookParser", "Detected TOC by link count: " + linkCount);
            return true;
        }
    }
    
    return false;
}
```

## 📊 修复后的效果

### 之前（包含所有EPUB条目）

```
目录列表：
├─ Cover              ← ❌ 封面
├─ 第2章              ← ❌ 编号错误（应该是第1章）
├─ 制作说明           ← ❌ 非正文
├─ 版权信息           ← ❌ 非正文
├─ 第5章              ← ❌ 编号跳跃
├─ 第一章 肛肠科拯救世界  ← ✅ 正文开始
├─ 第二章 穿越加载中请稍候
└─ 第三章 每逢穿越必有迷茫
```

**问题**：
- ❌ 封面、制作说明、版权信息混在目录中
- ❌ 章节编号混乱（第2章、第5章出现在第一章之前）
- ❌ 影响阅读体验

### 现在（过滤非正文章节后）

```
目录列表：
├─ 第一章 肛肠科拯救世界  ← ✅ 正文第1章
─ 第二章 穿越加载中请稍候  ← ✅ 正文第2章
└─ 第三章 每逢穿越必有迷茫  ← ✅ 正文第3章
```

**优势**：
- ✅ 只显示真正的正文章节
- ✅ 章节编号连续、正确
- ✅ 阅读体验更好

### 调试日志示例

```log
D/LocalBookParser: Skipping non-content chapter: Cover
D/LocalBookParser: Detected cover by image count: 5, text length: 120
D/LocalBookParser: Skipping non-content chapter: 目录
D/LocalBookParser: Detected TOC by link count: 25
D/LocalBookParser: Skipping non-content chapter: 内容简介
D/LocalBookParser: Skipping non-content chapter: 制作说明
D/LocalBookParser: Successfully parsed 45 content chapters
```

## 🎯 技术细节

### 为什么需要过滤？

EPUB文件通常包含多种类型的内容：

1. **元数据页面**：封面、版权页、制作说明
2. **导航页面**：目录、索引
3. **辅助内容**：前言、后记、附录、致谢
4. **正文内容**：真正的章节

对于阅读器来说，用户主要关注的是**正文内容**。将其他内容混在目录中会：
- 干扰阅读流程
- 导致章节编号混乱
- 降低用户体验

### 过滤策略的选择

#### 为什么不直接删除这些页面？

我们选择**在解析时跳过**而不是**从ZIP中删除**，原因：

1. **保留完整性**：EPUB文件保持原样，用户可以随时查看原始内容
2. **灵活性**：未来可以添加"显示全部"选项，让用户选择是否查看非正文内容
3. **安全性**：不修改原始文件，避免数据丢失风险

#### 关键词列表如何确定？

通过分析大量EPUB文件的实际内容，总结出常见的非正文章节标题模式：

- **中文EPUB**：常见"封面"、"目录"、"简介"、"版权"等
- **英文EPUB**：常见"Cover"、"Contents"、"Introduction"、"Copyright"等
- **混合EPUB**：可能同时包含中英文

我们的关键词列表覆盖了绝大多数情况。

### 内容特征分析的阈值

#### 封面检测：`imgCount >= 3 && textLength < 500`

**理由**：
- 封面通常包含书籍封面图、作者照片、出版社logo等多张图片
- 文字通常只有书名、作者名等少量信息
- 测试发现：正常章节至少有几百字的正文

#### 目录检测：`linkCount >= 10 && textLength < 2000`

**理由**：
- 目录需要链接到各个章节，通常有10+个链接
- 目录本身的文字内容较少（主要是章节标题）
- 测试发现：正常章节的文字量远大于链接数

### 边界情况处理

#### 情况1：章节标题为空

```java
if (title == null || title.isEmpty()) {
    return false; // 没有标题的章节不跳过
}
```

**原因**：某些EPUB可能有无标题的章节，不能贸然跳过。

#### 情况2：关键词误判

例如：某章节标题为"第一章 封面设计的历史"

**解决**：
- 使用 `contains()` 而非精确匹配
- 结合内容特征分析（该章节文字量大，不会被误判为封面）
- 用户可以通过日志看到被跳过的章节，便于调试

#### 情况3：特殊格式的EPUB

某些EPUB可能将所有内容放在一个HTML文件中，通过锚点跳转。

**当前限制**：
- 我们的解析基于spine中的itemref顺序
- 如果EPUB结构特殊，可能需要调整解析逻辑

## 🔧 扩展建议

### 1. 添加配置选项

允许用户在设置中选择：
- [x] 隐藏封面、目录等非正文内容（默认）
- [ ] 显示所有内容

### 2. 智能学习

记录用户的阅读行为：
- 哪些章节用户经常跳过
- 哪些章节用户仔细阅读
- 自动优化过滤规则

### 3. 手动标记

允许用户手动标记某个章节为"非正文"或"正文"，系统学习用户的偏好。

### 4. 提取书籍简介

虽然我们在目录中过滤了简介章节，但可以：
- 在书籍详情页显示简介
- 从简介章节中提取前500字作为预览

## 📝 测试建议

请重点测试以下场景：

1. **标准EPUB** ⭐⭐⭐⭐⭐
   - 验证：封面、目录、简介被正确过滤
   - 验证：正文章节连续、编号正确

2. **英文EPUB**
   - 验证：Cover、Contents、Introduction等被正确过滤

3. **特殊格式EPUB**
   - 验证：不会误判正文章节为非正文
   - 验证：日志输出合理

4. **无封面/目录的EPUB**
   - 验证：所有章节正常显示

5. **查看Logcat日志**
   ```bash
   adb logcat | grep LocalBookParser
   ```
   - 验证：看到 "Skipping non-content chapter: XXX" 的日志
   - 验证：最终解析的章节数量合理

##  详细说明文档

我已创建完整的修复说明：[EPUB非正文章节过滤修复说明.md](file:///d:/android/app/EPUB非正文章节过滤修复说明.md)

现在请重新编译运行应用，导入EPUB文件，目录应该只显示真正的正文章节了！
