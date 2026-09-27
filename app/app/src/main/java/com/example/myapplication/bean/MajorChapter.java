package com.example.myapplication.bean;

/**
 * 大章节（分卷）实体：字段与后端 {@code MajorChapter} 一一对应，Gson 才映射得上。
 * 后台导入 EPUB 时写入 major_chapter 表，App 端「目录」据此做分卷折叠展示。
 */
public class MajorChapter {
    private Long id;
    private Long bookId;
    private String title;
    /** 卷键，形如 "1".."24"，与章节 ChapterDto.sortKey 中 '-' 之前的前缀匹配 */
    private String sortKey;
    private Integer sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSortKey() { return sortKey; }
    public void setSortKey(String sortKey) { this.sortKey = sortKey; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
