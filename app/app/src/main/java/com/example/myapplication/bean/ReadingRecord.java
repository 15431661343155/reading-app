package com.example.myapplication.bean;

import java.io.Serializable;

/**
 * 阅读记录实体类
 */
public class ReadingRecord implements Serializable {
    private long bookId;
    private String bookName;
    private String author;
    private String cover;              // 封面URL
    private int chapterIndex;          // 最后阅读章节索引
    private int currentPage;        // 当前章内页码
    private int totalPages;         // 当前章总页数
    private int globalPageIndex;    // 全局页索引
    private String chapterTitle;       // 最后阅读章节标题
    private int totalChapters;         // 总章节数
    private long readTime;             // 最后阅读时间戳
    private int progress;              // 阅读百分比 0-100
    private int chapterProgress;

    // ===== 外站书籍扩展字段 =====
    private boolean external;           // 是否外站书籍
    private String sourceType;          // 书源类型（外站书籍用）
    private String sourceUrl;           // 书源书籍URL（外站书籍唯一标识）

    public ReadingRecord() {}

    // Getter/Setter
    public long getBookId() { return bookId; }
    public void setBookId(long bookId) { this.bookId = bookId; }

    public String getBookName() { return bookName; }
    public void setBookName(String bookName) { this.bookName = bookName; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }

    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

    public int getCurrentPage() { return currentPage; }
    public void setCurrentPage(int currentPage) { this.currentPage = currentPage; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public int getGlobalPageIndex() { return globalPageIndex; }
    public void setGlobalPageIndex(int globalPageIndex) { this.globalPageIndex = globalPageIndex; }
    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }

    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }

    public long getReadTime() { return readTime; }
    public void setReadTime(long readTime) { this.readTime = readTime; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public int getChapterProgress() { return chapterProgress; }
    public void setChapterProgress(int chapterProgress) { this.chapterProgress = chapterProgress; }

    // ===== 外站书籍扩展 getter/setter =====
    public boolean isExternal() { return external; }
    public void setExternal(boolean external) { this.external = external; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
}
