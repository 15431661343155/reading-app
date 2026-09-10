package com.example.myapplication.utils;

import android.app.Activity;

import com.example.myapplication.R;

/**
 * 全站统一的 Activity 转场动画（与首页 tab 切换的「左右滑动」保持同一套观感）。
 *
 * <pre>
 * 前进（打开新页面）：新页面从右侧滑入，旧页面向左滑出
 * 返回（关闭当前页）：上一个页面从左侧滑回，当前页面向右滑出
 * </pre>
 *
 * 用法：
 * <pre>
 *   // 新页面 onCreate() 里
 *   ActivityTransition.applyEnter(this);
 *   // finish() 之后
 *   ActivityTransition.applyExit(this);
 * </pre>
 *
 * 继承 {@link com.example.myapplication.activity.BaseActivity} 的页面已经自动生效，
 * 这里主要给直接继承 AppCompatActivity、拿不到 BaseActivity 的页面用。
 */
public final class ActivityTransition {

    private ActivityTransition() {
    }

    /** 入场：新页面从右侧滑入，旧页面向左滑出 */
    public static void applyEnter(Activity activity) {
        if (activity == null) return;
        activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    /** 出场（返回）：上一个页面从左侧滑回，当前页面向右滑出 */
    public static void applyExit(Activity activity) {
        if (activity == null) return;
        activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    /** 淡入淡出（个别不适合左右滑动的页面可选） */
    public static void applyFade(Activity activity) {
        if (activity == null) return;
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }
}
