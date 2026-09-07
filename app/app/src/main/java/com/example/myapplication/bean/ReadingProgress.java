package com.example.myapplication.bean;

public class ReadingProgress {
    private Long id;
    private Long userId;
    private Long bookId;
    private Long chapterId;
    private int chapterIndex;
    private int scrollPosition;
    private float fontSize;
    private int nightMode;
    private int bgColor;
    private String textAnchor;
    private String chapterTitle;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }

    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }

    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

    public int getScrollPosition() { return scrollPosition; }
    public void setScrollPosition(int scrollPosition) { this.scrollPosition = scrollPosition; }

    public float getFontSize() { return fontSize; }
    public void setFontSize(float fontSize) { this.fontSize = fontSize; }

    public int getNightMode() { return nightMode; }
    public void setNightMode(int nightMode) { this.nightMode = nightMode; }

    public int getBgColor() { return bgColor; }
    public void setBgColor(int bgColor) { this.bgColor = bgColor; }

    public String getTextAnchor() { return textAnchor; }
    public void setTextAnchor(String textAnchor) { this.textAnchor = textAnchor; }

    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }
}
