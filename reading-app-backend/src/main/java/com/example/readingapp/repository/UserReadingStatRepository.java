package com.example.readingapp.repository;

import com.example.readingapp.entity.UserReadingStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface UserReadingStatRepository extends JpaRepository<UserReadingStat, Long> {

    Optional<UserReadingStat> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    /**
     * 原子累加阅读时长，避免「读-改-写」并发丢更新。
     * 返回受影响行数；0 表示该用户还没有统计记录（调用方需先补建）。
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserReadingStat s SET s.totalReadSeconds = s.totalReadSeconds + :delta, "
            + "s.updatedAt = CURRENT_TIMESTAMP WHERE s.userId = :userId")
    int addReadSeconds(@Param("userId") Long userId, @Param("delta") Long delta);

    /** 删除某用户的统计记录（删除用户时级联清理）。派生删除方法必须带事务。 */
    @Transactional
    void deleteByUserId(Long userId);
}
