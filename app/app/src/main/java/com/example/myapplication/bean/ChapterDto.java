package com.example.myapplication.bean;

public class ChapterDto {
    private Long id;
    private Long bookId;
    private String title;
    private String content;
    private int sortOrder;
    /**
     * 章节分卷键（后端形如 "1-0001"：前缀为卷号，对应 major_chapter.sort_key）。
     * 供目录分卷折叠展示推导所属卷使用；旧数据可能为空，此时按平铺处理。
     */
    private String sortKey;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getSortKey() { return sortKey; }
    public void setSortKey(String sortKey) { this.sortKey = sortKey; }
}
