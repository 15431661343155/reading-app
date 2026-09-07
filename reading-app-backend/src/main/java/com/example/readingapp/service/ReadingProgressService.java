package com.example.readingapp.service;

import com.example.readingapp.entity.ReadingProgress;

import java.util.List;

public interface ReadingProgressService {

    // 保存或更新阅读进度
    ReadingProgress saveProgress(ReadingProgress progress);

    // 获取用户在某本书的阅读进度
    ReadingProgress getProgress(Long userId, Long bookId);

    // 获取用户最近阅读的书籍
    List<ReadingProgress> getRecentReading(Long userId, int limit);

    // 删除阅读进度
    void deleteProgress(Long userId, Long bookId);
}
