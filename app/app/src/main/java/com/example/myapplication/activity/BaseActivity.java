package com.example.myapplication.activity;

import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.example.myapplication.R;
import com.example.myapplication.utils.ThemeManager;

public class BaseActivity extends AppCompatActivity {

    protected int statusBarHeight = 0;  // 状态栏高度

    /**
     * 当前页面是否使用「从右向左滑入」转场。
     * 默认淡入淡出；子类（如阅读器）重写返回 true，即可让所有入口统一生效。
     */
    protected boolean useSlideTransition() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用主题
        int theme = ThemeManager.getCurrentTheme(this);
        setTheme(ThemeManager.getThemeRes(theme));
        if (getResources() == null) {
            return;
        }
        super.onCreate(savedInstanceState);

        // 获取状态栏高度
        getStatusBarHeight();
        
        // 状态栏适配 - 让内容绘制在状态栏下方，保持状态栏显示
        setupStatusBar();

        // 2. 添加Activity入场动画，实现丝滑切换
        // 默认淡入淡出；使用滑入转场时改为「新页面从右滑入，旧页面向左滑出」
        overridePendingTransition(getCustomEnterAnim(), getCustomExitAnim());
    }

    /**
     * 入场动画：新页面从右侧滑入；出场动画：旧页面向左滑出
     */
    private int getCustomEnterAnim() {
        return useSlideTransition() ? R.anim.slide_in_right : R.anim.fade_in;
    }

    private int getCustomExitAnim() {
        return useSlideTransition() ? R.anim.slide_out_left : R.anim.fade_out;
    }

    @Override
    public void finish() {
        super.finish();
        // 返回时播放反向动画：上一个页面从左滑回，当前页面向右滑出
        if (useSlideTransition()) {
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        }
    }

    /**
     * 获取状态栏高度
     */
    private void getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
    }

    /**
     * 设置状态栏与导航栏颜色
     * 使用 setDecorFitsSystemWindows(true) 让系统自动避让状态栏和导航栏
     * 默认状态栏用白色 ios_bg（匹配二级 Activity 白色 Toolbar）
     * MainActivity Fragment 和特殊 Activity 在各自的 updateStatusBarColor() 里覆盖
     */
    protected void setupStatusBar() {
        // 恢复系统默认避让：内容不延伸到状态栏/导航栏后面
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        // 状态栏默认白色（匹配白色 Toolbar），MainActivity Fragment 覆盖为灰色
        getWindow().setStatusBarColor(
            getResources().getColor(R.color.ios_bg, null));

        // 导航栏统一用 grouped 背景色
        getWindow().setNavigationBarColor(
            getResources().getColor(R.color.ios_bg_grouped, null));

        // 状态栏图标：浅色背景用深色
        getWindow().getDecorView().setSystemUiVisibility(
            getWindow().getDecorView().getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    /**
     * 为顶部导航栏设置延伸效果（与阅读器类似）
     * 导航栏高度增加，背景延伸到状态栏区域，内容保持在状态栏下方
     */
    protected void extendToolbarToStatusBar(View toolbar) {
        if (toolbar == null || statusBarHeight == 0) return;
        
        // 设置导航栏高度 = 状态栏高度 + 原有高度
        int toolbarHeight = toolbar.getLayoutParams().height;
        if (toolbarHeight <= 0) {
            // 如果高度未设置，使用默认56dp
            toolbarHeight = (int) (56 * getResources().getDisplayMetrics().density);
        }
        toolbar.getLayoutParams().height = statusBarHeight + toolbarHeight;
        
        // 设置paddingTop让内容显示在状态栏下方
        toolbar.setPadding(toolbar.getPaddingLeft(), 
                statusBarHeight, 
                toolbar.getPaddingRight(), 
                toolbar.getPaddingBottom());
    }

    /**
     * 设置状态栏图标颜色
     * @param light true=深色图标（浅色背景），false=浅色/白色图标（深色背景）
     */
    protected void setLightStatusBar(boolean light) {
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /**
     * 为根视图添加状态栏padding，避免内容被遮挡
     */
    protected void applyStatusBarPadding(View rootView) {
        if (rootView == null) return;
        
        // 直接设置padding，让内容避开状态栏区域
        rootView.setPadding(rootView.getPaddingLeft(), 
                statusBarHeight, 
                rootView.getPaddingRight(), 
                rootView.getPaddingBottom());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 不在此处 setTheme()：会覆盖 Fragment 设置的状态栏颜色
        // 主题在 onCreate 和用户切换时已正确设置
    }
}
