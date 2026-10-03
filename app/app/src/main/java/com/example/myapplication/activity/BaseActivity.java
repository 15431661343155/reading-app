package com.example.myapplication.activity;

import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.example.myapplication.R;
import com.example.myapplication.utils.ActivityTransition;
import com.example.myapplication.utils.ThemeAttrs;
import com.example.myapplication.utils.ThemeManager;

public class BaseActivity extends AppCompatActivity {

    protected int statusBarHeight = 0;  // 状态栏高度

    /** 本页是否用主页专用主题（窗口底与开屏图同色）。仅 MainActivity 重写为 true。 */
    protected boolean usesMainTheme() {
        return false;
    }

    /**
     * 本页是否跟随「配色风格」。阅读器锁死日间且有自己的一套背景色板、登录页是独立的毛玻璃紫，
     * 两者重写返回 false 保持素白，避免和皮肤互相覆盖。
     */
    protected boolean followSkin() {
        return true;
    }

    /** onCreate 时生效的风格，onResume 用它判断是否需要重建 */
    private int appliedSkin = ThemeManager.SKIN_CLASSIC;

    /**
     * 当前页面是否使用「从右向左滑入」转场。
     * 默认 true —— 全站统一左右滑动；个别页面（如需要淡入淡出）重写返回 false 即可。
     */
    protected boolean useSlideTransition() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 配色风格在 super.onCreate 之前落到主题上：主题一旦参与布局解析就改不了了。
        // 日夜仍走清单里的 @style/Theme.MyApp + values-night，不在这里分支。
        if (followSkin()) {
            appliedSkin = ThemeManager.getSkin(this);
            setTheme(ThemeManager.themeResId(this, usesMainTheme()));
        }
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

        // 状态栏默认与标题栏同色（二级页 Toolbar 用 appSurface），MainActivity Fragment 覆盖为页面底色
        getWindow().setStatusBarColor(ThemeAttrs.color(this, R.attr.appSurface, 0));

        // 导航栏统一用页面底色
        getWindow().setNavigationBarColor(ThemeAttrs.color(this, R.attr.appPageBg, 0));

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
        // 配色风格变了不在这里 setTheme()（会覆盖 Fragment 设置的状态栏颜色），而是整页重建：
        // 栈里其余页面各自在回到前台时这样补一次，用户不需要重启 App 就能看到全站换肤。
        if (followSkin() && ThemeManager.getSkin(this) != appliedSkin) {
            recreate();
        }
    }
}
