package com.example.myapplication.utils;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.myapplication.R;

/**
 * 系统栏出血（edge-to-edge）。
 *
 * <p>窗口铺满整屏、两根系统栏涂透明，顶部与底部的颜色由页面自己的背景提供，内容按 insets 避让。
 * 状态栏区域因此不再是「存在窗口上的一层纯色」：切页时它随页面一起滑动，
 * 也不依赖各家系统对 {@code setStatusBarColor} 的支持程度。
 *
 * <p>三种接法，按页面顶部是什么来选：
 * <ul>
 *   <li>{@link #bleedTop(View)} —— 背景出血、内容下移，给「整页一个底色」的页面用；</li>
 *   <li>{@link #extendHeader(View)} —— 头部连背景带高度一起长高，给顶部是渐变／色块的页面用，
 *       否则固定高度的头部会被 padding 挤掉内容。</li>
 *   <li>{@link #bleedVertical(View)} —— 上下一起避让。一个 View 只能挂一根监听，
 *       两端都要避让时不能再分别调 bleedTop／bleedBottom，否则后挂的会覆盖先挂的。</li>
 * </ul>
 * 每个方法都可重复调用（叠加量记在 view 的 tag 上），换肤重建、切页回到前台都能安全刷新。
 */
public final class SystemBarInsets {

    /** 状态栏 + 导航条 + 刘海：页面顶部与底部真正会被压住的区域。 */
    private static final int BARS =
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();

    private SystemBarInsets() {
    }

    /** 让窗口铺满整屏，并把两根系统栏涂成透明。 */
    public static void enableEdgeToEdge(Window window) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        // Android 10+ 会在透明系统栏上自加一层对比度遮罩，出血方案下反而多出一条灰带，必须关掉
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
    }

    /** 背景出血、内容下移：paddingTop 叠加状态栏 inset，背景仍铺到屏幕顶。 */
    public static void bleedTop(View view) {
        if (view == null) {
            return;
        }
        remember(view, R.id.tag_bar_base_top, view.getPaddingTop());
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int pad = base(v, R.id.tag_bar_base_top) + insets.getInsets(BARS).top;
            v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        requestInsetsAgain(view);
    }

    /** 背景出血、内容上移：paddingBottom 叠加导航条 inset。 */
    public static void bleedBottom(View view) {
        if (view == null) {
            return;
        }
        remember(view, R.id.tag_bar_base_bottom, view.getPaddingBottom());
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int pad = base(v, R.id.tag_bar_base_bottom) + insets.getInsets(BARS).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), pad);
            return insets;
        });
        requestInsetsAgain(view);
    }

    /** 上下一起避让：一根监听里同时改两端，避免与 {@link #bleedTop(View)}／{@link #bleedBottom(View)} 互相覆盖。 */
    public static void bleedVertical(View view) {
        if (view == null) {
            return;
        }
        remember(view, R.id.tag_bar_base_top, view.getPaddingTop());
        remember(view, R.id.tag_bar_base_bottom, view.getPaddingBottom());
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            v.setPadding(v.getPaddingLeft(), base(v, R.id.tag_bar_base_top) + insets.getInsets(BARS).top,
                    v.getPaddingRight(), base(v, R.id.tag_bar_base_bottom) + insets.getInsets(BARS).bottom);
            return insets;
        });
        requestInsetsAgain(view);
    }

    /** 头部整体延伸：高度与 paddingTop 同步加状态栏 inset，色带铺到屏幕顶而内容不被挤压。 */
    public static void extendHeader(View view) {
        if (view == null || view.getLayoutParams() == null) {
            return;
        }
        remember(view, R.id.tag_bar_base_top, view.getPaddingTop());
        remember(view, R.id.tag_bar_base_height, view.getLayoutParams().height);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int top = insets.getInsets(BARS).top;
            v.setPadding(v.getPaddingLeft(), base(v, R.id.tag_bar_base_top) + top,
                    v.getPaddingRight(), v.getPaddingBottom());
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int baseHeight = base(v, R.id.tag_bar_base_height);
            // 只有写死高度的头部才需要跟着长高；match_parent / wrap_content 加 inset 只会撑坏布局
            if (baseHeight > 0 && lp.height != baseHeight + top) {
                lp.height = baseHeight + top;
                v.setLayoutParams(lp);
            }
            return insets;
        });
        requestInsetsAgain(view);
    }

    /**
     * 底部面板弹窗的窗口：让它铺到屏幕物理底边，导航条 inset 改由面板自己让位。
     *
     * <p>浮动窗口（Dialog 默认的 windowIsFloating）的子窗口帧被 WM 按 {@code fitTypes=NAVIGATION_BARS}
     * 收在导航条上方，实测量到帧底只到 2274 —— 加 {@code FLAG_LAYOUT_IN_SCREEN} 也抬不动，
     * 面板底下就留一条没被遮罩盖住的页面色带。真正的开关是 {@code windowIsFloating=false}
     * （见 styles.xml 的 CacheSheetStyle），配合这里关掉 decor 避让后帧才落到 2400。
     *
     * <p>另一条实测约束：窗口高度必须显式 {@code MATCH_PARENT}。用 WRAP_CONTENT 时窗口按
     * 「扣掉系统栏的可用高」测量，整扇窗口（含遮罩）会整体下沉到状态栏底下，顶部反而漏出一条没变暗的带。
     *
     * <p>避让本身由调用方对「贴底且带底色的那块面板」调 {@link #bleedBottom(View)}：表面铺满、内容让位。
     * 导航键压在面板上，图标深浅按面板底色判定（底色由调用方从主题属性取，shape drawable 里读不出来）。
     */
    public static void extendSheetWindow(Window sheet, int surfaceColor) {
        if (sheet == null) {
            return;
        }
        sheet.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        WindowCompat.setDecorFitsSystemWindows(sheet, false);
        sheet.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sheet.setNavigationBarContrastEnforced(false);
        }
        setLightNavigationIcons(sheet, !isDark(surfaceColor));
    }

    /** 相对亮度低于 0.5 视为深底：系统栏图标该用浅色。与阅读器染色同一套算法。 */
    public static boolean isDark(int color) {
        return luminance(color) < 0.5;
    }

    /** 取 View 的纯色背景色；渐变、图片或没设背景时回落到 {@code fallback}。 */
    public static int solidColor(View view, int fallback) {
        if (view != null && view.getBackground() instanceof ColorDrawable) {
            return ((ColorDrawable) view.getBackground()).getColor();
        }
        return fallback;
    }

    /** 状态栏图标深浅：true = 深色图标（浅底），false = 浅色图标（深底）。 */
    public static void setLightStatusIcons(Window window, boolean light) {
        controller(window).setAppearanceLightStatusBars(light);
    }

    /** 导航条图标深浅，同上。 */
    public static void setLightNavigationIcons(Window window, boolean light) {
        controller(window).setAppearanceLightNavigationBars(light);
    }

    private static WindowInsetsControllerCompat controller(Window window) {
        return WindowCompat.getInsetsController(window, window.getDecorView());
    }

    /** 后挂上的监听不会自动收到一次分发，attach 后主动补一次，避免首帧没有 inset。 */
    private static void requestInsetsAgain(View view) {
        view.post(() -> ViewCompat.requestApplyInsets(view));
    }

    private static double luminance(int color) {
        double r = channel((color >> 16) & 0xFF);
        double g = channel((color >> 8) & 0xFF);
        double b = channel(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value8) {
        double v = value8 / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static void remember(View view, int key, int value) {
        if (view.getTag(key) == null) {
            view.setTag(key, value);
        }
    }

    private static int base(View view, int key) {
        Object tag = view.getTag(key);
        return tag instanceof Integer ? (Integer) tag : 0;
    }
}
