package com.example.myapplication.utils;

import android.content.Context;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

/**
 * 主题属性取色。
 * 配色风格（素白 / 宣纸）落地后，颜色不再只有一个 @color 名字，而是要看当前主题给了什么值，
 * 所以代码里动态取色的地方必须先解析 ?attr/appXxx，再按解析出的资源 id 取色。
 */
public class ThemeAttrs {

    /** 解析主题属性指向的颜色；属性缺失时返回 fallback，避免非主题上下文拿到 0 画出透明 */
    public static int color(Context context, int attr, int fallbackColor) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return fallbackColor;
    }

    /** 解析主题属性指向的 drawable 资源 id；缺失时返回 0，交给调用方自己兜底 */
    public static int drawableRes(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.resourceId != 0) {
            return value.resourceId;
        }
        return 0;
    }
}
