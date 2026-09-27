package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * 外站书籍（本地书架 + 阅读记录）按登录用户隔离的 SP 管理。
 *
 * 需求：登录用户的外站书跨登出保留（注销后再次登录同一账号可恢复），
 * 且不同账号 / 游客之间互不串数据。
 *
 * 实现：每个登录用户一份文件 external_bookshelf_<userId> / external_reading_records_<userId>；
 * 游客（未登录）读取 external_bookshelf_guest（恒为空，因为游客不能加入外站书）。
 * 旧版全局文件 external_bookshelf / external_reading_records 在首次登录时一次性迁移到当前用户文件。
 */
public final class ExternalPrefs {

    private static final String LEGACY_SHELF = "external_bookshelf";
    private static final String LEGACY_RECORDS = "external_reading_records";
    private static final String BOOKMARK = "external_bookmarks";

    private ExternalPrefs() {}

    private static String suffix(Context ctx) {
        String id = LoginHelper.getUserId(ctx);
        return (id == null || id.isEmpty()) ? "guest" : id;
    }

    /** 当前登录用户的外站书架 SP 名（未登录为 guest 命名空间，恒空）。 */
    public static String shelfName(Context ctx) {
        return LEGACY_SHELF + "_" + suffix(ctx);
    }

    /** 当前登录用户的外站阅读记录 SP 名。 */
    public static String recordsName(Context ctx) {
        return LEGACY_RECORDS + "_" + suffix(ctx);
    }

    /** 当前登录用户的外站书签 SP 名（按用户隔离，未登录为 guest 命名空间）。 */
    public static String bookmarkName(Context ctx) {
        return BOOKMARK + "_" + suffix(ctx);
    }

    // 注意：登出不再清除本地缓存（本地按用户命名空间隔离，保留才能"退出后重进不丢"
    // 且在服务器不可用时兜底）。故不再提供 clearUserCache。

    /**
     * 把旧版全局外站数据迁移到当前登录用户的命名空间。幂等：迁移后旧文件被清空，
     * 之后再次调用因目标已非空而跳过拷贝、只清空残留旧文件。仅登录态触发。
     */
    public static synchronized void migrateIfNeeded(Context ctx) {
        if (ctx == null || !LoginHelper.isLoggedIn(ctx)) return;
        String id = LoginHelper.getUserId(ctx);
        migrateOne(ctx, LEGACY_SHELF, LEGACY_SHELF + "_" + id);
        migrateOne(ctx, LEGACY_RECORDS, LEGACY_RECORDS + "_" + id);
    }

    private static void migrateOne(Context ctx, String legacy, String target) {
        SharedPreferences src = ctx.getSharedPreferences(legacy, Context.MODE_PRIVATE);
        SharedPreferences dst = ctx.getSharedPreferences(target, Context.MODE_PRIVATE);
        Map<String, ?> all = src.getAll();
        if (all == null || all.isEmpty()) return;
        if (!dst.getAll().isEmpty()) {
            // 目标已有数据，只需清掉旧全局文件，避免重复
            src.edit().clear().apply();
            return;
        }
        SharedPreferences.Editor e = dst.edit();
        for (Map.Entry<String, ?> en : all.entrySet()) {
            String k = en.getKey();
            Object v = en.getValue();
            if (v instanceof String) e.putString(k, (String) v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v);
        }
        e.apply();
        src.edit().clear().apply();
    }
}
