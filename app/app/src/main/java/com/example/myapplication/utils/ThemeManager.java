package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 外观（夜间模式）管理。
 * 三档取值直接复用 AppCompatDelegate 的常量，不再另建一层主题 ID 映射；
 * 存储沿用原主题偏好的 SP 文件（历史 current_theme 键已随海滨主题一起废弃）。
 */
public class ThemeManager {
    private static final String SP_NAME = "app_theme";
    private static final String KEY_NIGHT_MODE = "night_mode";

    public static int getNightMode(Context context) {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static void saveNightMode(Context context, int mode) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_NIGHT_MODE, mode).apply();
    }

    /** 进程启动时调用一次，之后新建/恢复的 Activity 都会按偏好解析日夜资源 */
    public static void applyNightMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(getNightMode(context));
    }

    /**
     * 当前配置是否处于夜间。
     * 只认 Configuration：「跟随系统」时最终结果只有它知道，而阅读器把本地模式锁死为日间
     * （setLocalNightMode），各自的 uiMode 也不同，比对全局默认模式会判错。
     */
    public static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
