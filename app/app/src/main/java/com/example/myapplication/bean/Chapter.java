package com.example.myapplication.bean;

import java.io.Serializable;

//章节实体类
public class Chapter implements Serializable{
    private int id;
    private int bookId;
    private String title;
    private String content;
    private int chapterIndex;
    private String filePath;  // 本地文件路径

    public Chapter() {}

    public Chapter(String title, String content) {
        this.title = title;
        this.content = content;
    }
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getBookId() { return bookId; }
    public void setBookId(int bookId) { this.bookId = bookId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
}
