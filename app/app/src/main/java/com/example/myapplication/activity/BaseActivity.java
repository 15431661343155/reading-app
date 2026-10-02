package com.example.myapplication.activity;

import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.example.myapplication.R;
import com.example.myapplication.utils.ActivityTransition;
import com.example.myapplication.utils.ThemeManager;

public class BaseActivity extends AppCompatActivity {

    protected int statusBarHeight = 0;  // 状态栏高度

    /**
     * 当前页面是否使用「从右向左滑入」转场。
     * 默认 true —— 全站统一左右滑动；个别页面（如需要淡入淡出）重写返回 false 即可。
     */
    protected boolean useSlideTransition() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 主题一律走清单里的 @style/Theme.MyApp：
        // 日间/夜间由 values-night 资源限定符 + AppCompatDelegate 的夜间模式解析，不在这里分支
        if (getResources() == null) {
            return;
        }
        super.onCreate(savedInstanceState);

        getStatusBarHeight();
        
        // 状态栏适配 - 让内容绘制在状态栏下方，保持状态栏显示
        setupStatusBar();

        // 2. 添加Activity入场动画，实现丝滑切换
        // 默认「新页面从右滑入，旧页面向左滑出」；重写 useSlideTransition() 可改为淡入淡出
        if (useSlideTransition()) {
            ActivityTransition.applyEnter(this);
        } else {
            ActivityTransition.applyFade(this);
        }
    }

    @Override
    public void finish() {
        super.finish();
        // 返回时播放反向动画：上一个页面从左滑回，当前页面向右滑出
        if (useSlideTransition()) {
            ActivityTransition.applyExit(this);
        } else {
            ActivityTransition.applyFade(this);
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

        // 系统图标明暗由当前配置决定：浅色底配深色图标，深色底配浅色图标
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (ThemeManager.isNight(this)) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /**
     * 为顶部导航栏设置延伸效果（与阅读器类似）
     * 导航栏高度增加，背景延伸到状态栏区域，内容保持在状态栏下方
     */
    protected void extendToolbarToStatusBar(View toolbar) {
        if (toolbar == null || statusBarHeight == 0) return;
        
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
     * 按当前日夜配置复位状态栏图标：日间深色图标、夜间浅色图标。
     * 供 Fragment（书架/书城/分类）在覆盖状态栏背景色之后调用；
     * 页面顶部是彩色块（如「我的」蓝色英雄区）时不要用它，需自行指定。
     */
    public void applyStatusBarIcons() {
        setLightStatusBar(!ThemeManager.isNight(this));
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
