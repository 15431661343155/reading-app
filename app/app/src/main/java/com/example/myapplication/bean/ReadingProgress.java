package com.example.myapplication.bean;

public class ReadingProgress {
    private Long id;
    private Long userId;
    private Long bookId;
    private Long chapterId;
    private int chapterIndex;
    private int scrollPosition;
    private String chapterTitle;
    /** 服务端最后写入时间（ISO-8601，服务器本地时间，无时区）。
     *  与 App 本地记录的 record_readTime_ 比对，决定「最后阅读的那一条」用谁的。 */
    private String updatedAt;
    /** 服务端下发的绝对时间戳（epoch 毫秒，与设备时区无关）。
     *  比较「最后阅读时间」优先用它，缺失（老版本后端）才回退解析 updatedAt。 */
    private Long updatedAtEpoch;

    /** 服务端 recent 接口随进度 EAGER 下发的书籍对象（含 title/author/cover/chapterCount），
     *  阅读记录页据此还原书名封面与进度百分比。单本 getProgress 不依赖此字段。 */
    private Book book;
    /** 服务端随进度下发的章节对象，用于取章节标题展示"最后读到"。 */
    private Chapter chapter;

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

    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public Long getUpdatedAtEpoch() { return updatedAtEpoch; }
    public void setUpdatedAtEpoch(Long updatedAtEpoch) { this.updatedAtEpoch = updatedAtEpoch; }

    public Book getBook() { return book; }
    public void setBook(Book book) { this.book = book; }

    public Chapter getChapter() { return chapter; }
    public void setChapter(Chapter chapter) { this.chapter = chapter; }
}
