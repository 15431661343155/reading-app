package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import com.example.myapplication.R;

/**
 * 外观管理，两个正交维度：
 * <ul>
 *   <li><b>外观模式</b>（跟随系统 / 日间 / 夜间）—— 直接复用 AppCompatDelegate 常量，
 *       由 values-night 资源限定符解析配色；</li>
 *   <li><b>配色风格</b>（素白 / 宣纸）—— 由 {@link #themeResId} 选出对应主题，
 *       主题里只换 {@code ?attr/appXxx} 的取值，布局不感知风格。</li>
 * </ul>
 * 存储沿用原主题偏好的 SP 文件（历史 current_theme 键已随海滨主题一起废弃）。
 */
public class ThemeManager {
    private static final String SP_NAME = "app_theme";
    private static final String KEY_NIGHT_MODE = "night_mode";
    private static final String KEY_SKIN = "skin";

    /** 素白：现状 iOS 磨砂玻璃档 */
    public static final int SKIN_CLASSIC = 0;
    /** 宣纸：弹窗家族的墨纸描金 + 朱砂动作色，扩到全 App */
    public static final int SKIN_XUANZHI = 1;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static int getNightMode(Context context) {
        return prefs(context).getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static void saveNightMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_NIGHT_MODE, mode).apply();
    }

    public static int getSkin(Context context) {
        return prefs(context).getInt(KEY_SKIN, SKIN_CLASSIC);
    }

    public static void saveSkin(Context context, int skin) {
        prefs(context).edit().putInt(KEY_SKIN, skin).apply();
    }

    /**
     * 按当前配色风格解析主题资源。
     *
     * @param main 主页专用（窗口底与开屏图同色，避免冷启动闪灰）
     */
    public static int themeResId(Context context, boolean main) {
        boolean xuanzhi = getSkin(context) == SKIN_XUANZHI;
        if (main) {
            return xuanzhi ? R.style.Theme_MyApp_Main_Xuanzhi : R.style.Theme_MyApp_Main;
        }
        return xuanzhi ? R.style.Theme_MyApp_Xuanzhi : R.style.Theme_MyApp;
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
