package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.myapplication.bean.Book;

import java.util.HashSet;
import java.util.Set;

/**
 * 书架置顶存储：按用户隔离的本地排序权重（置顶键集合，SharedPreferences 持久化）。
 * <p>
 * 排序口径（2026-10-01 与用户确认的效果图方案）：置顶集合只是客户端排序权重——
 * 书架列表渲染时「置顶优先、其余保持原序（最后阅读时间倒序）」，不写服务器。
 * 键规则与外站阅读记录同款：外站书 = ext_sourceType|sourceUrl，普通/本地书 = b_{id}。
 * 删除书籍时调用方需同步 {@link #unpin} 清理，避免残留键。
 */
public final class ShelfPinStore {
    private static final String SP_NAME = "shelf_pin";

    private ShelfPinStore() {}

    /** 书籍的置顶键（外站书按来源 URL，本站/本地书按 id；无法建键返回 null 即视为不可置顶） */
    public static String keyOf(Book b) {
        if (b == null) return null;
        if (b.getStatus() == -2 && b.getSourceType() != null && b.getSourceUrl() != null) {
            return "ext_" + b.getSourceType() + "|" + b.getSourceUrl();
        }
        Long id = b.getId();
        return id == null ? null : "b_" + id;
    }

    /** 读取某用户的置顶键集合（副本，调用方可安全修改） */
    public static Set<String> load(Context ctx, long userId) {
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        Set<String> raw = sp.getStringSet("pins_" + userId, null);
        return raw == null ? new HashSet<>() : new HashSet<>(raw);
    }

    private static void save(Context ctx, long userId, Set<String> pins) {
        ctx.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet("pins_" + userId, pins).apply();
    }

    /** 集合版 contains（供排序比较器批量使用，避免逐本重复读 SP） */
    public static boolean contains(Set<String> pins, String key) {
        return key != null && pins.contains(key);
    }

    public static boolean isPinned(Context ctx, long userId, String key) {
        return contains(load(ctx, userId), key);
    }

    public static void pin(Context ctx, long userId, String key) {
        if (key == null) return;
        Set<String> pins = load(ctx, userId);
        if (pins.add(key)) save(ctx, userId, pins);
    }

    public static void unpin(Context ctx, long userId, String key) {
        if (key == null) return;
        Set<String> pins = load(ctx, userId);
        if (pins.remove(key)) save(ctx, userId, pins);
    }
}
