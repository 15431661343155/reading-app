package com.example.myapplication.bean;

import java.util.List;

public class PageResponse<T> {
    private List<T> content;
    private int totalPages;
    private long totalElements;
    private int size;
    private int number;
    private boolean first;
    private boolean last;
    private boolean empty;

    // Getter
    public List<T> getContent() { return content; }
    public int getTotalPages() { return totalPages; }
    public long getTotalElements() { return totalElements; }
    public int getSize() { return size; }
    public int getNumber() { return number; }
    public boolean isFirst() { return first; }
    public boolean isLast() { return last; }
    public boolean isEmpty() { return empty; }

    // Setter
    public void setContent(List<T> content) { this.content = content; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }
    public void setSize(int size) { this.size = size; }
    public void setNumber(int number) { this.number = number; }
    public void setFirst(boolean first) { this.first = first; }
    public void setLast(boolean last) { this.last = last; }
    public void setEmpty(boolean empty) { this.empty = empty; }
}
