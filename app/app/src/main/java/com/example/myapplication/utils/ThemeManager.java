package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.myapplication.R;


public class ThemeManager {
    private static final String SP_NAME = "app_theme";
    private static final String KEY_THEME = "current_theme";
    // 主题常量
    public static final int THEME_DEFAULT = 0;
    public static final int THEME_SEASIDE = 1;

    // 保存主题
    public static void saveTheme(Context context, int theme) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_THEME, theme).apply();
    }

    // 获取当前主题
    public static int getCurrentTheme(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_THEME, THEME_DEFAULT);
    }

    // 根据主题ID应用对应样式
    public static int getThemeRes(int themeId) {
        switch (themeId) {
            case THEME_SEASIDE:
                return R.style.Theme_MyApp_Seaside;
            default:
                return R.style.Theme_MyApp;
        }
    }
}