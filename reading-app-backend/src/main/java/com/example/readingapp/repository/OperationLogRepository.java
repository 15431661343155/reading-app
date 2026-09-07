package com.example.readingapp.repository;

import com.example.readingapp.entity.OperationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    
    // 查询最近的操作日志
    List<OperationLog> findTop50ByOrderByCreatedAtDesc();
    
    // 分页查询操作日志
    List<OperationLog> findByOrderByCreatedAtDesc(Pageable pageable);
    
    // 根据目标类型和ID查询
    List<OperationLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, Long targetId);
}
