package com.example.readingapp.service.impl;

import com.example.readingapp.entity.ReadingProgress;
import com.example.readingapp.repository.ReadingProgressRepository;
import com.example.readingapp.service.ReadingProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReadingProgressServiceImpl implements ReadingProgressService {

    private final ReadingProgressRepository readingProgressRepository;

    @Override
    @Transactional
    public ReadingProgress saveProgress(ReadingProgress progress) {
        System.out.println("=== 接收到的 textAnchor: " + progress.getTextAnchor() + " ===");
        // 检查是否已存在进度记录
        Optional<ReadingProgress> existing = readingProgressRepository
                .findByUserIdAndBookId(progress.getUserId(), progress.getBookId());

        if (existing.isPresent()) {
            // 更新现有记录
            readingProgressRepository.updateProgress(progress);
            return progress;
        } else {
            // 插入新记录
            return readingProgressRepository.save(progress);
        }
    }

    @Override
    public ReadingProgress getProgress(Long userId, Long bookId) {
        return readingProgressRepository.findByUserIdAndBookId(userId, bookId)
                .orElse(null);
    }

    @Override
    public List<ReadingProgress> getRecentReading(Long userId, int limit) {
        return readingProgressRepository.findRecentReading(userId, PageRequest.of(0, limit));
    }

    @Override
    @Transactional
    public void deleteProgress(Long userId, Long bookId) {
        readingProgressRepository.findByUserIdAndBookId(userId, bookId)
                .ifPresent(readingProgressRepository::delete);
    }
}