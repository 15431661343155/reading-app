package com.example.readingapp.service.impl;

import com.example.readingapp.dto.UnreadCountResponse;
import com.example.readingapp.entity.Message;
import com.example.readingapp.repository.MessageRepository;
import com.example.readingapp.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessageServiceImpl implements MessageService {

    @Autowired
    private MessageRepository messageRepository;

    @Override
    public Page<Message> getMessagesByUserId(Long userId, Pageable pageable) {
        return messageRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    public Page<Message> getMessagesByUserIdAndType(Long userId, String type, Pageable pageable) {
        if (type == null || type.trim().isEmpty()) {
            return messageRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }
        return messageRepository.findByUserIdAndTypeOrderByCreatedAtDesc(userId, type, pageable);
    }

    @Override
    public long getUnreadCount(Long userId) {
        return messageRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Override
    public UnreadCountResponse getUnreadCountDetail(Long userId) {
        List<Object[]> rows = messageRepository.countUnreadByTypeGrouped(userId);
        Map<String, Long> typeCount = new HashMap<>();
        for (Object[] row : rows) {
            String type = (String) row[0];
            Long count = ((Number) row[1]).longValue();
            typeCount.put(type, count);
        }

        long comment = typeCount.getOrDefault("comment", 0L);
        long like = typeCount.getOrDefault("like", 0L);
        long follow = typeCount.getOrDefault("follow", 0L);
        long system = typeCount.getOrDefault("system", 0L)
                + typeCount.getOrDefault("reply", 0L);
        long total = comment + like + follow + system
                + typeCount.getOrDefault("activity", 0L)
                + typeCount.getOrDefault("interaction", 0L);

        UnreadCountResponse r = new UnreadCountResponse();
        r.setTotal(total);
        r.setComment(comment);
        r.setLike(like);
        r.setFollow(follow);
        r.setSystem(system);
        return r;
    }

    @Override
    public Message markAsRead(Long messageId) {
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message != null) {
            message.setIsRead(true);
            return messageRepository.save(message);
        }
        return null;
    }

    @Override
    @Transactional
    public int markAllAsRead(Long userId) {
        return messageRepository.markAllAsRead(userId);
    }

    @Override
    public Message createMessage(Long userId, String title, String content, String type) {
        Message message = new Message();
        message.setUserId(userId);
        message.setTitle(title);
        message.setContent(content);
        message.setType(type);
        message.setIsRead(false);
        return messageRepository.save(message);
    }
}