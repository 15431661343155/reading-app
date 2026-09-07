package com.example.readingapp.controller;

import com.example.readingapp.dto.ApiResponse;
import com.example.readingapp.dto.FeedbackRequest;
import com.example.readingapp.entity.Feedback;
import com.example.readingapp.service.FeedbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Autowired
    private FeedbackService feedbackService;

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<Void>> submitFeedback(@RequestBody FeedbackRequest request) {
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("反馈内容不能为空"));
        }
        feedbackService.submitFeedback(request);
        return ResponseEntity.ok(ApiResponse.success("提交成功", null));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Feedback>> createFeedback(@RequestBody Feedback feedback) {
        Feedback created = feedbackService.createFeedback(feedback);
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<Feedback>>> listFeedbacks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Feedback> result;
        
        if (keyword != null && !keyword.isEmpty()) {
            result = feedbackService.searchFeedbacks(keyword, pageable);
        } else if (status != null) {
            result = feedbackService.getFeedbacksByStatus(status, pageable);
        } else {
            result = feedbackService.getAllFeedbacks(pageable);
        }
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Feedback>> getFeedback(@PathVariable Long id) {
        Feedback feedback = feedbackService.getFeedbackById(id);
        if (feedback == null) {
            return ResponseEntity.ok(ApiResponse.error("反馈不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(feedback));
    }

    @PutMapping("/{id}/reply")
    public ResponseEntity<ApiResponse<Feedback>> replyFeedback(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String replyContent = request.get("replyContent");
        Feedback updated = feedbackService.replyFeedback(id, replyContent);
        if (updated == null) {
            return ResponseEntity.ok(ApiResponse.error("反馈不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PostMapping("/reply/{feedbackId}")
    public ResponseEntity<ApiResponse<Feedback>> adminReplyFeedback(
            @PathVariable Long feedbackId,
            @RequestBody Map<String, String> request) {
        String replyContent = request.get("replyContent");
        if (replyContent == null || replyContent.trim().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("回复内容不能为空"));
        }
        Feedback updated = feedbackService.replyFeedback(feedbackId, replyContent);
        if (updated == null) {
            return ResponseEntity.ok(ApiResponse.error("反馈不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PutMapping("/{id}/close")
    public ResponseEntity<ApiResponse<Feedback>> closeFeedback(@PathVariable Long id) {
        Feedback updated = feedbackService.closeFeedback(id);
        if (updated == null) {
            return ResponseEntity.ok(ApiResponse.error("反馈不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteFeedback(@PathVariable Long id) {
        feedbackService.deleteFeedback(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getCount(@RequestParam(required = false) Integer status) {
        if (status != null) {
            long count = feedbackService.countByStatus(status);
            return ResponseEntity.ok(ApiResponse.success(Map.of("count", count)));
        }
        long pending = feedbackService.countByStatus(0);
        long replied = feedbackService.countByStatus(1);
        long closed = feedbackService.countByStatus(2);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "pending", pending,
                "replied", replied,
                "closed", closed
        )));
    }
}