package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户阅读统计（账号级）。
 *
 * <p>与 {@link ReadTimeRecord} 的分工：
 * <ul>
 *   <li>{@code read_time_record} 是「每本书每次阅读一条」的流水明细，用于按书统计、审计；</li>
 *   <li>本表是「每用户一条」的汇总快照，用于个人中心直接读累计值，避免每次都 SUM 全表。</li>
 * </ul>
 * 保存阅读时长时两边同时写（流水 + 累加），查询总时长走本表。
 *
 * <p>{@code userId} 唯一：一个账号只允许一条统计记录。注册时即创建，起始连续天数 = 1
 * （「首次注册登录」当天算作第 1 天）。
 */
@Data
@Entity
@Table(name = "user_reading_stat",
        uniqueConstraints = @UniqueConstraint(name = "uk_stat_user", columnNames = "user_id"))
public class UserReadingStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 数据库主键形式的用户 id（与其它业务表一致）。 */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 当前连续阅读天数。 */
    @Column(name = "streak_days", nullable = false, columnDefinition = "INT DEFAULT 0")
    private Integer streakDays = 0;

    /** 历史最长连续阅读天数，用于「断签后仍可展示纪录」。 */
    @Column(name = "max_streak_days", nullable = false, columnDefinition = "INT DEFAULT 0")
    private Integer maxStreakDays = 0;

    /**
     * 最近一次阅读的自然日（yyyy-MM-dd）。
     * 只存日期不存时间：连续天数按自然日判定，跨天即为新的一天。
     */
    @Column(name = "last_read_date")
    private LocalDate lastReadDate;

    /** 累计阅读时长（秒）。 */
    @Column(name = "total_read_seconds", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private Long totalReadSeconds = 0L;

    /** 累计打卡天数（含断签前的），与 streakDays 不同：只增不减。 */
    @Column(name = "total_read_days", nullable = false, columnDefinition = "INT DEFAULT 0")
    private Integer totalReadDays = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
