package com.example.myapplication.utils;

/**
 * 阅读时长展示格式化（全 App 唯一实现）。
 *
 * <p>书架页与「我的」页共用，保证两页对同一个累计秒数永远显示相同的数字——
 * 此前「我的」页用 {@code %.1f 时} 四舍五入（88 分钟显示成 1.5 时），
 * 与书架页的「1 小时 28 分钟」对不上，故统一收敛到本类。
 *
 * <p>入参为秒（后端 {@code total_read_seconds} 与
 * {@code GET /api/user/readtime/total} 均以秒为准），向下取整到分钟：
 * <ul>
 *   <li>不足 1 小时：{@code "46 分钟"}</li>
 *   <li>满整小时：{@code "2 小时"}</li>
 *   <li>其余：{@code "1 小时 28 分"}</li>
 * </ul>
 */
public final class ReadTimeText {

    private ReadTimeText() {
    }

    /** 把累计秒数格式化为紧凑且精确到分钟的文案（不四舍五入）。 */
    public static String format(long seconds) {
        if (seconds < 0) seconds = 0;
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long mins = minutes % 60;
        if (mins == 0) {
            return hours + " 小时";
        }
        return hours + " 小时 " + mins + " 分";
    }
}
