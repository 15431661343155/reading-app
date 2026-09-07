package com.example.readingapp.repository;

import com.example.readingapp.entity.ApkPush;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ApkPushRepository extends JpaRepository<ApkPush, Long> {

    // 获取当前推送的APK
    @Query("SELECT a FROM ApkPush a WHERE a.isPushed = true ORDER BY a.pushTime DESC LIMIT 1")
    Optional<ApkPush> findCurrentPushedApk();

    // 取消所有推送状态
    @Modifying
    @Query("UPDATE ApkPush a SET a.isPushed = false")
    void clearAllPushed();
}