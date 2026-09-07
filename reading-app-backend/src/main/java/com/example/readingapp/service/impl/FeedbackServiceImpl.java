package com.example.readingapp.service.impl;

import com.example.readingapp.dto.FeedbackRequest;
import com.example.readingapp.entity.Feedback;
import com.example.readingapp.entity.User;
import com.example.readingapp.repository.FeedbackRepository;
import com.example.readingapp.repository.UserRepository;
import com.example.readingapp.service.FeedbackService;
import com.example.readingapp.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class FeedbackServiceImpl implements FeedbackService {

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageService messageService;

    @Override
    public Feedback submitFeedback(FeedbackRequest request) {
        Feedback feedback = new Feedback();
        feedback.setUserId(request.getUserId());
        feedback.setContent(request.getContent());
        feedback.setContact(request.getContact());
        feedback.setType(request.getType());
        feedback.setStatus(0);

        // 如果有 userId，获取用户名
        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId()).orElse(null);
            if (user != null) {
                feedback.setUsername(user.getUsername());
            }
        }

        return feedbackRepository.save(feedback);
    }

    @Override
    public Feedback createFeedback(Feedback feedback) {
        return feedbackRepository.save(feedback);
    }

    @Override
    public Feedback getFeedbackById(Long id) {
        return feedbackRepository.findById(id).orElse(null);
    }

    @Override
    public Page<Feedback> getAllFeedbacks(Pageable pageable) {
        return feedbackRepository.findAll(pageable);
    }

    @Override
    public Page<Feedback> getFeedbacksByStatus(Integer status, Pageable pageable) {
        return feedbackRepository.findByStatus(status, pageable);
    }

    @Override
    public Page<Feedback> searchFeedbacks(String keyword, Pageable pageable) {
        return feedbackRepository.findByUsernameContainingOrContactContaining(keyword, keyword, pageable);
    }

    @Override
    @Transactional
    public Feedback replyFeedback(Long id, String replyContent) {
        Feedback feedback = feedbackRepository.findById(id).orElse(null);
        if (feedback != null) {
            feedback.setReplyContent(replyContent);
            feedback.setReplyTime(LocalDateTime.now());
            feedback.setStatus(1);
            Feedback updated = feedbackRepository.save(feedback);

            // 生成 reply 类型消息通知用户
            if (feedback.getUserId() != null) {
                String summary = replyContent.length() > 100
                        ? replyContent.substring(0, 100) + "..."
                        : replyContent;
                messageService.createMessage(
                        feedback.getUserId(),
                        "反馈回复",
                        summary,
                        "reply"
                );
            }

            return updated;
        }
        return null;
    }

    @Override
    public Feedback closeFeedback(Long id) {
        Feedback feedback = feedbackRepository.findById(id).orElse(null);
        if (feedback != null) {
            feedback.setStatus(2);
            return feedbackRepository.save(feedback);
        }
        return null;
    }

    @Override
    public void deleteFeedback(Long id) {
        feedbackRepository.deleteById(id);
    }

    @Override
    public long countByStatus(Integer status) {
        return feedbackRepository.countByStatus(status);
    }
}