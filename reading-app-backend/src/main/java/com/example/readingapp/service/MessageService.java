package com.example.readingapp.service;

import com.example.readingapp.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MessageService {

    Page<Message> getMessagesByUserId(Long userId, Pageable pageable);

    Page<Message> getMessagesByUserIdAndType(Long userId, String type, Pageable pageable);

    long getUnreadCount(Long userId);

    com.example.readingapp.dto.UnreadCountResponse getUnreadCountDetail(Long userId);

    Message markAsRead(Long messageId);

    int markAllAsRead(Long userId);

    Message createMessage(Long userId, String title, String content, String type);
}