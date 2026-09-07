package com.example.myapplication.bean;

import java.io.Serializable;

//书签实体类

public class Bookmark implements Serializable{
    private Long id;
    private Long userId;
    private Long bookId;
    private Long chapterId;
    private String bookName;
    private int chapterIndex;
    private String chapterTitle;
    private int scrollPosition;

    private String previewText;  // 书签位置的文本预览
    private String contentPreview;

    private String createdAt;
    private String note;

    public Bookmark() {
    }

    public Bookmark(String bookName, int chapterIndex, String chapterTitle,
                    int scrollPosition, String previewText) {
         this.bookName = bookName;
        this.chapterIndex = chapterIndex;
        this.chapterTitle = chapterTitle;
        this.scrollPosition = scrollPosition;
        this.previewText = previewText;
    }

    // Getter/Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }

    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }

    public String getBookName() {
        return bookName;
    }
    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public int getChapterIndex() {
        return chapterIndex;
    }
    public void setChapterIndex(int chapterIndex) {
        this.chapterIndex = chapterIndex;
    }

    public String getChapterTitle() {
        return chapterTitle;
    }
    public void setChapterTitle(String chapterTitle) {
        this.chapterTitle = chapterTitle;
    }

    public int getScrollPosition() {
        return scrollPosition;
    }
    public void setScrollPosition(int scrollPosition) {
        this.scrollPosition = scrollPosition;
    }

    public String getPreviewText() {
        return previewText;
    }
    public void setPreviewText(String previewText) {
        this.previewText = previewText;
    }

    public String getContentPreview() { return contentPreview; }
    public void setContentPreview(String contentPreview) { this.contentPreview = contentPreview; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
