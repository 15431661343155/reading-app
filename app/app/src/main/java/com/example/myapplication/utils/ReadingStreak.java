package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 连续阅读天数（本地统计）。
 *
 * <p>后端没有这个数据，纯本地按自然日记录：每天首次进入阅读器时调 {@link #markReadToday}，
 * 若上次阅读是「昨天」则天数 +1，是「今天」不变，更早则重置为 1。
 *
 * <p>用日期字符串（yyyyMMdd）比较而不是时间戳差值，避免跨夏令时/跨时区时算出 0.96 天这类误差。
 */
public final class ReadingStreak {

    private static final String SP_NAME = "reading_streak";
    private static final String KEY_LAST_DAY = "last_day";
    private static final String KEY_DAYS = "days";
    /**
     * 是否已把本地天数迁移到服务端。迁移成功后置 true，
     * 之后以账号级数据为准，不再上报本地值（避免覆盖服务端已有进度）。
     */
    private static final String KEY_MIGRATED = "migrated";

    private ReadingStreak() {
    }

    /** 本地记录是否已迁移到账号（决定要不要上报 migratedStreak）。 */
    public static boolean isMigrated(Context context) {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MIGRATED, false);
    }

    /** 标记本地天数已迁移到服务端。 */
    public static void markMigrated(Context context) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MIGRATED, true).apply();
    }

    /** 今天（yyyyMMdd）。 */
    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
    }

    /** 昨天（yyyyMMdd）。 */
    private static String yesterday() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -1);
        return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(c.getTime());
    }

    /**
     * 标记「今天读过」，返回更新后的连续天数。
     * 同一天重复调用不会把天数刷上去。
     */
    public static int markReadToday(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String today = today();
        String lastDay = sp.getString(KEY_LAST_DAY, "");
        int days = sp.getInt(KEY_DAYS, 0);

        if (today.equals(lastDay)) {
            return days;                       // 今天已经记过，幂等
        }
        if (yesterday().equals(lastDay)) {
            days += 1;                         // 昨天读过 → 连上了
        } else {
            days = 1;                          // 断了或首次 → 从 1 重新开始
        }

        sp.edit().putString(KEY_LAST_DAY, today).putInt(KEY_DAYS, days).apply();
        return days;
    }

    /**
     * 读取当前连续天数（不修改记录）。
     * 若上次阅读既不是今天也不是昨天，说明已断签，返回 0。
     */
    public static int getStreak(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String lastDay = sp.getString(KEY_LAST_DAY, "");
        if (today().equals(lastDay) || yesterday().equals(lastDay)) {
            return sp.getInt(KEY_DAYS, 0);
        }
        return 0;
    }
}
