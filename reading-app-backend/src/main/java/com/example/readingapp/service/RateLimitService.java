package com.example.readingapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内限流（无外部依赖，重启即清零，满足本项目单实例部署规模）。
 *
 * <p>两类能力：
 * <ul>
 *   <li><b>书源接口频率限制</b>：/api/admin/online-source/** 与 /api/admin/book-source/import-url
 *       匿名开放（App 端依赖），按客户端 IP 限频，防止被当免费代理刷（见 SecurityHardeningFilter）。</li>
 *   <li><b>登录防爆破</b>：按「IP|账号」记录连续失败，超过阈值锁定一段时间；登录成功即清零。
 *       覆盖 /api/auth/login 与 /api/admin/auth/login 两个密码登录入口。</li>
 * </ul>
 */
@Slf4j
@Service
public class RateLimitService {

    // ==================== 1. 通用滑动窗口限频 ====================

    /** 限流桶：key = bucket名|客户端标识 */
    private final ConcurrentHashMap<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    /**
     * 通用滑动窗口限频。
     *
     * @param bucket   桶名（同一桶共享限额语义，实际计数仍按「bucket|client」隔离）
     * @param client   客户端标识（通常为 IP）
     * @param maxReq   窗口内最大请求数
     * @param windowMs 窗口长度（毫秒）
     * @return true=放行，false=超出频率
     */
    public boolean allow(String bucket, String client, int maxReq, long windowMs) {
        String key = bucket + "|" + client;
        long now = System.currentTimeMillis();
        Deque<Long> deque = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean allowed;
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() >= windowMs) {
                deque.pollFirst();
            }
            if (deque.size() < maxReq) {
                deque.addLast(now);
                allowed = true;
            } else {
                allowed = false;
            }
        }
        if (!allowed) {
            log.warn("接口触发限流: bucket={}, client={}", bucket, client);
        }
        // 顺手清理长期不活跃的 key，防止 Map 无限增长
        if (buckets.size() > 10_000) {
            buckets.keySet().removeIf(k -> {
                Deque<Long> d = buckets.get(k);
                if (d == null) return true;
                synchronized (d) { return d.isEmpty(); }
            });
        }
        return allowed;
    }

    /** 书源接口限频（60 次/分/IP），见 SecurityHardeningFilter 规则表 */
    public boolean allowSourceRequest(String clientIp) {
        return allow("source", clientIp, 60, 60_000L);
    }

    // ==================== 2. 登录防爆破：失败计数 + 锁定 ====================

    /** 窗口内最大失败次数 */
    private static final int LOGIN_MAX_FAILURES = 5;
    /** 失败计数窗口（毫秒） */
    private static final long LOGIN_WINDOW_MS = 15 * 60_000L;
    /** 触发后的锁定时长（毫秒） */
    private static final long LOGIN_LOCK_MS = 15 * 60_000L;

    private static class FailureRecord {
        Deque<Long> failTimes = new ArrayDeque<>();
        long blockedUntil = 0;
    }

    private final ConcurrentHashMap<String, FailureRecord> loginFailures = new ConcurrentHashMap<>();

    /** 该「IP|账号」是否处于锁定中 */
    public boolean isLoginBlocked(String key) {
        FailureRecord rec = loginFailures.get(key);
        if (rec == null) return false;
        synchronized (rec) {
            if (rec.blockedUntil > 0 && System.currentTimeMillis() < rec.blockedUntil) {
                return true;
            }
            // 锁定期已过 → 重置，重新计数
            if (rec.blockedUntil > 0) {
                rec.failTimes.clear();
                rec.blockedUntil = 0;
            }
            return false;
        }
    }

    /** 登录失败（密码错误等）记一次 */
    public void recordLoginFailure(String key) {
        FailureRecord rec = loginFailures.computeIfAbsent(key, k -> new FailureRecord());
        long now = System.currentTimeMillis();
        synchronized (rec) {
            while (!rec.failTimes.isEmpty() && now - rec.failTimes.peekFirst() >= LOGIN_WINDOW_MS) {
                rec.failTimes.pollFirst();
            }
            rec.failTimes.addLast(now);
            if (rec.failTimes.size() >= LOGIN_MAX_FAILURES) {
                rec.blockedUntil = now + LOGIN_LOCK_MS;
                log.warn("登录失败次数过多，临时锁定: key={}, 锁定 {} 分钟", key, LOGIN_LOCK_MS / 60_000);
            }
        }
    }

    /** 登录成功清零 */
    public void recordLoginSuccess(String key) {
        loginFailures.remove(key);
    }

    /** 登录限流 Map 过大时清理（正常用户量下极少触发） */
    public void cleanupLoginFailures() {
        if (loginFailures.size() > 10_000) {
            long now = System.currentTimeMillis();
            loginFailures.keySet().removeIf(k -> {
                FailureRecord rec = loginFailures.get(k);
                if (rec == null) return true;
                synchronized (rec) {
                    return rec.blockedUntil < now && rec.failTimes.isEmpty();
                }
            });
        }
    }
}
