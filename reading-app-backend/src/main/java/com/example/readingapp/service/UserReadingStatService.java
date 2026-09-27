package com.example.readingapp.service;

import com.example.readingapp.entity.UserReadingStat;
import com.example.readingapp.repository.UserReadingStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 用户阅读统计（连续天数 / 累计时长）的账号级实现。
 *
 * <p>连续天数按**自然日**判定，规则与 App 端本地版本保持一致，便于从旧版本平滑迁移：
 * <ul>
 *   <li>同一天重复打卡：幂等，天数不变；</li>
 *   <li>上次阅读是昨天：天数 +1；</li>
 *   <li>更早（断签）或首次：天数重置为 1。</li>
 * </ul>
 * 用 {@link LocalDate} 比较而不是时间戳差值，避免跨时区/夏令时算出 0.96 天这类误差。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserReadingStatService {

    private final UserReadingStatRepository statRepo;

    /**
     * 取统计记录；不存在则按「起始天数 = initialStreak」补建。
     *
     * <p>补建而不是返回 null，是为了兼容两类历史数据：注册流程上线前创建的老账号、
     * 以及接口被直接调用时还没建记录的用户。这样调用方无需到处判空。
     *
     * @param initialStreak 补建时的起始连续天数（注册当天传 1，运行期兜底传 1）
     */
    @Transactional
    public UserReadingStat getOrCreate(Long userId, int initialStreak) {
        return statRepo.findByUserId(userId).orElseGet(() -> {
            UserReadingStat stat = new UserReadingStat();
            stat.setUserId(userId);
            int init = Math.max(initialStreak, 0);
            stat.setStreakDays(init);
            stat.setMaxStreakDays(init);
            stat.setTotalReadSeconds(0L);
            stat.setTotalReadDays(init > 0 ? 1 : 0);
            // 补建时不写 lastReadDate：没有可靠的「最后一次阅读日」，留空表示尚未打卡，
            // 由首次打卡逻辑决定是重置为 1 还是延续。
            return statRepo.save(stat);
        });
    }

    /**
     * 补建统计记录（注册时调用）。已存在则原样返回，不覆盖。
     *
     * @param initialStreak 起始连续天数，首次注册登录传 1
     */
    @Transactional
    public UserReadingStat initialize(Long userId, int initialStreak) {
        if (statRepo.existsByUserId(userId)) {
            return statRepo.findByUserId(userId).orElse(null);
        }
        return getOrCreate(userId, initialStreak);
    }

    /**
     * 打卡「今天读过」，返回更新后的统计。
     *
     * <p>{@code migratedStreak} 用于承接旧版 App 存在本地的连续天数：仅当服务端
     * **从未打卡过**（{@code lastReadDate == null}）时才采纳，且取两者较大值，
     * 避免重复上报把服务端已有进度冲掉。
     *
     * @param migratedStreak 迁移自客户端本地记录的天数，无则传 0
     */
    @Transactional
    public UserReadingStat checkIn(Long userId, int migratedStreak) {
        UserReadingStat stat = getOrCreate(userId, 1);
        LocalDate today = LocalDate.now();
        LocalDate last = stat.getLastReadDate();

        // 首次打卡且客户端带了本地进度 → 先承接，再按下面的规则推进
        if (last == null && migratedStreak > 0) {
            int base = Math.max(migratedStreak, stat.getStreakDays() == null ? 0 : stat.getStreakDays());
            stat.setStreakDays(base);
            stat.setMaxStreakDays(Math.max(
                    stat.getMaxStreakDays() == null ? 0 : stat.getMaxStreakDays(), base));
        }

        if (today.equals(last)) {
            return stat;                                  // 今天已记过，幂等
        }

        int days;
        if (last != null && last.plusDays(1).equals(today)) {
            days = (stat.getStreakDays() == null ? 0 : stat.getStreakDays()) + 1;   // 昨天读过 → 连上
        } else if (last == null) {
            days = Math.max(stat.getStreakDays() == null ? 1 : stat.getStreakDays(), 1); // 没打过卡 → 保留迁移值或 1
        } else {
            days = 1;                                     // 断签 → 从 1 重新开始
        }

        stat.setStreakDays(days);
        stat.setLastReadDate(today);
        stat.setMaxStreakDays(Math.max(
                stat.getMaxStreakDays() == null ? 0 : stat.getMaxStreakDays(), days));
        // 累计打卡天数：本次是「新的一个自然日」才 +1。
        //
        // 注意不能写成 `totalReadDays + 1`：记录刚由 getOrCreate 补建时 totalReadDays 已经是 1
        // （注册当天就算一次打卡），再加 1 会变成 2 —— 实测「注册 → 首次打卡」重复计数。
        // 因此以 streakDays 为基准直接对齐即可（streakDays 就是「当前这串连续天数」的天数）。
        int total = (stat.getTotalReadDays() == null ? 0 : stat.getTotalReadDays());
        stat.setTotalReadDays(Math.max(total, days));
        return statRepo.save(stat);
    }

    /**
     * 读取当前统计（不修改）。若不存在则补建起始值为 1 的记录。
     */
    @Transactional
    public UserReadingStat getStat(Long userId) {
        return getOrCreate(userId, 1);
    }

    /**
     * 累加阅读时长（秒）。
     *
     * <p>与流水表写入配对使用：流水保明细，本方法维护累计值。
     * 统计记录缺失时先补建，保证累加不会丢。
     */
    @Transactional
    public void addReadSeconds(Long userId, long seconds) {
        if (seconds <= 0) {
            return;
        }
        if (statRepo.addReadSeconds(userId, seconds) == 0) {
            // 记录还没建（老账号）→ 补建后重试一次
            getOrCreate(userId, 1);
            statRepo.addReadSeconds(userId, seconds);
        }
    }

    /** 删除用户时级联清理统计记录。 */
    @Transactional
    public void deleteByUserId(Long userId) {
        statRepo.deleteByUserId(userId);
    }
}
