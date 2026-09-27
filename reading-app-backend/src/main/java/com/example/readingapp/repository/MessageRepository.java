package com.example.readingapp.repository;

import com.example.readingapp.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Message> findByUserIdAndTypeOrderByCreatedAtDesc(Long userId, String type, Pageable pageable);

    long countByUserIdAndIsReadFalse(Long userId);

    long countByUserIdAndIsReadFalseAndType(Long userId, String type);

    @Query("SELECT m.type, COUNT(m) FROM Message m WHERE m.userId = :userId AND m.isRead = false GROUP BY m.type")
    List<Object[]> countUnreadByTypeGrouped(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Message m SET m.isRead = true WHERE m.userId = :userId AND m.isRead = false")
    int markAllAsRead(@Param("userId") Long userId);

    /** 删除某用户的全部站内消息（删除用户时级联清理）。派生删除方法必须带事务。 */
    @Transactional
    void deleteByUserId(Long userId);
}