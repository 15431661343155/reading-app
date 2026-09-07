package com.example.myapplication.bean;

/**
 * 字体项（从后端获取）
 */
public class FontItem {
    private long id;
    private String name;       // 显示名称，如 "楷体"
    private String cssName;    // CSS font-family 值，如 "KaiTi"
    private String fileUrl;    // TTF 文件下载地址
    private String fileSize;   // 文件大小显示，如 "5.2 MB"

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCssName() { return cssName; }
    public void setCssName(String cssName) { this.cssName = cssName; }

    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }

    public String getFileSize() { return fileSize; }
    public void setFileSize(String fileSize) { this.fileSize = fileSize; }
}
