package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.MessageResponse;
import com.example.readingapp.dto.PageResponse;
import com.example.readingapp.dto.UnreadCountResponse;
import com.example.readingapp.entity.Message;
import com.example.readingapp.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user/message")
public class MessageController {

    @Autowired
    private MessageService messageService;

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<PageResponse<MessageResponse>>> getMessages(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Message> messagePage = messageService.getMessagesByUserIdAndType(userId, type, pageable);

        List<MessageResponse> content = messagePage.getContent().stream()
                .map(MessageResponse::fromEntity)
                .collect(Collectors.toList());

        PageResponse<MessageResponse> pageResponse = new PageResponse<>();
        pageResponse.setContent(content);
        pageResponse.setTotalElements(messagePage.getTotalElements());
        pageResponse.setTotalPages(messagePage.getTotalPages());
        pageResponse.setSize(messagePage.getSize());
        pageResponse.setNumber(messagePage.getNumber());
        pageResponse.setFirst(messagePage.isFirst());
        pageResponse.setLast(messagePage.isLast());
        pageResponse.setEmpty(messagePage.isEmpty());

        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    @GetMapping("/{userId}/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount(@PathVariable Long userId) {
        UnreadCountResponse count = messageService.getUnreadCountDetail(userId);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @PostMapping("/read/{id}")
    public ResponseEntity<ApiResponse<MessageResponse>> markAsRead(@PathVariable Long id) {
        Message message = messageService.markAsRead(id);
        if (message == null) {
            return ResponseEntity.ok(ApiResponse.error("消息不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(MessageResponse.fromEntity(message)));
    }

    @PostMapping("/read-all/{userId}")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllAsRead(@PathVariable Long userId) {
        int count = messageService.markAllAsRead(userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("count", count)));
    }
}
