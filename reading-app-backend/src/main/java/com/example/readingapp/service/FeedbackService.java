package com.example.readingapp.service;

import com.example.readingapp.dto.FeedbackRequest;
import com.example.readingapp.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FeedbackService {

    Feedback submitFeedback(FeedbackRequest request);

    Feedback createFeedback(Feedback feedback);

    Feedback getFeedbackById(Long id);

    Page<Feedback> getAllFeedbacks(Pageable pageable);

    Page<Feedback> getFeedbacksByStatus(Integer status, Pageable pageable);

    Page<Feedback> searchFeedbacks(String keyword, Pageable pageable);

    Feedback replyFeedback(Long id, String replyContent);

    Feedback closeFeedback(Long id);

    void deleteFeedback(Long id);

    long countByStatus(Integer status);
}