package com.example.myapplication.bean;

public class ApkPush {
    private Long id;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String version;
    private String md5;
    private String updateNotes;
    private Boolean isPushed;
    private String pushTime;
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }
    public String getUpdateNotes() { return updateNotes; }
    public void setUpdateNotes(String updateNotes) { this.updateNotes = updateNotes; }
    public Boolean getIsPushed() { return isPushed; }
    public void setIsPushed(Boolean isPushed) { this.isPushed = isPushed; }
    public String getPushTime() { return pushTime; }
    public void setPushTime(String pushTime) { this.pushTime = pushTime; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
