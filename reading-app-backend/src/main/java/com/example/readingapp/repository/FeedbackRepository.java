package com.example.readingapp.repository;

import com.example.readingapp.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    Page<Feedback> findByStatus(Integer status, Pageable pageable);

    Page<Feedback> findByUsernameContainingOrContactContaining(String username, String contact, Pageable pageable);

    List<Feedback> findTop10ByOrderByCreatedAtDesc();

    long countByStatus(Integer status);

    /** 删除某用户的全部反馈（删除用户时级联清理）。派生删除方法必须带事务。 */
    @Transactional
    @Modifying
    void deleteByUserId(Long userId);
}