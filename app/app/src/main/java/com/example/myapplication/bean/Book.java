package com.example.myapplication.bean;
//书籍实体
import java.io.Serializable;

public class Book  implements Serializable{
    private Long id;
    private String title;
    private String bookName;
    private String author;
    private String intro;
    private String cover;
    private String coverUrl;
    private int auditStatus; // 0待审核 1通过 2驳回
    private int chapterCount;
    private int status;          // 0连载 1完结
    private Long viewCount;
    private Long likeCount;
    private String category;
    private Integer wordCount;
    private Boolean isInShelf;   // 是否已在书架（由服务端返回）
    private String sourceType;   // 书源类型
    private Long sourceId;       // 书源ID
    private String sourceUrl;    // 书源中书籍的URL（外站书籍标识）
    private String lastChapter;  // 最新章节（外站书籍详情API返回）
    private Boolean publicDomain; // 是否公版书

    public Book() {}

    public Book(String bookName, String author, String intro) {
        this.bookName = bookName;
        this.title = bookName;
        this.author = author;
        this.intro = intro;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title != null ? title : bookName; }
    public void setTitle(String title) { this.title = title; this.bookName = title; }

    public String getBookName() { return bookName != null ? bookName : title; }
    public void setBookName(String bookName) { this.bookName = bookName; this.title = bookName; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }

    public String getCover() { return cover != null ? cover : coverUrl; }
    public void setCover(String cover) { this.cover = cover; this.coverUrl = cover; }

    public String getCoverUrl() { return coverUrl != null ? coverUrl : cover; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; this.cover = coverUrl; }

    public int getAuditStatus() { return auditStatus; }
    public void setAuditStatus(int auditStatus) { this.auditStatus = auditStatus; }

    public int getChapterCount() { return chapterCount; }
    public void setChapterCount(int chapterCount) { this.chapterCount = chapterCount; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }

    public Long getLikeCount() { return likeCount; }
    public void setLikeCount(Long likeCount) { this.likeCount = likeCount; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Integer getWordCount() { return wordCount; }
    public void setWordCount(Integer wordCount) { this.wordCount = wordCount; }

    public Boolean getIsInShelf() { return isInShelf; }
    public void setIsInShelf(Boolean isInShelf) { this.isInShelf = isInShelf; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getLastChapter() { return lastChapter; }
    public void setLastChapter(String lastChapter) { this.lastChapter = lastChapter; }

    public Boolean getPublicDomain() { return publicDomain; }
    public void setPublicDomain(Boolean publicDomain) { this.publicDomain = publicDomain; }
}
