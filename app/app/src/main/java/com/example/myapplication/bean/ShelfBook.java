package com.example.myapplication.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "shelf_books")
public class ShelfBook {
    @PrimaryKey(autoGenerate = true)
    private int id;

    private String bookName;
    private String author;
    private String intro;
    private String coverUrl;
    private int auditStatus;
    private long addTime; // 加入书架时间
    private long lastReadTime; // 最后阅读时间

    public ShelfBook() {
        this.bookName = "";
    }

    @Ignore
    public ShelfBook(Book book) {
        this.bookName = book.getBookName();
        this.author = book.getAuthor();
        this.intro = book.getIntro();
        this.coverUrl = book.getCoverUrl();
        this.auditStatus = book.getAuditStatus();
        this.addTime = System.currentTimeMillis();
        this.lastReadTime = System.currentTimeMillis();
    }

    // Getter/Setter
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getBookName() { return bookName; }
    public void setBookName(String bookName) { this.bookName = bookName; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public int getAuditStatus() { return auditStatus; }
    public void setAuditStatus(int auditStatus) { this.auditStatus = auditStatus; }

    public long getAddTime() { return addTime; }
    public void setAddTime(long addTime) { this.addTime = addTime; }

    public long getLastReadTime() { return lastReadTime; }
    public void setLastReadTime(long lastReadTime) { this.lastReadTime = lastReadTime; }

    // 转换为Book对象
    @Ignore
    public Book toBook() {
        Book book = new Book(bookName, author, intro);
        book.setCoverUrl(coverUrl);
        book.setAuditStatus(auditStatus);
        return book;
    }
}