package com.example.myapplication.bean;

public class Bookshelf {
    private Long id;
    private Long userId;
    private Long bookId;
    private String addedAt;
    private String lastReadAt;
    private Book book;  // 关联的书籍信息

    // Getter
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getBookId() { return bookId; }
    public String getAddedAt() { return addedAt; }
    public String getLastReadAt() { return lastReadAt; }
    public Book getBook() { return book; }

    // Setter
    public void setId(Long id) { this.id = id; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public void setAddedAt(String addedAt) { this.addedAt = addedAt; }
    public void setLastReadAt(String lastReadAt) { this.lastReadAt = lastReadAt; }
    public void setBook(Book book) { this.book = book; }
}