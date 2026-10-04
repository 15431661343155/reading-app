package com.example.myapplication.activity;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.myapplication.R;
import com.example.myapplication.utils.ActivityTransition;
import com.example.myapplication.utils.SystemBarInsets;
import com.example.myapplication.utils.ThemeAttrs;
import com.example.myapplication.utils.ThemeManager;

public class BaseActivity extends AppCompatActivity {

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

    /**
     * 本页是否交给 {@link #setupStatusBar()} 做系统栏出血：窗口铺满整屏、系统栏涂透明，
     * 顶部与底部的颜色由页面自己的背景提供（接法见 {@link SystemBarInsets}）。
     *
     * <p>默认全站开启。阅读器与登录页有自己的沉浸／全屏逻辑，头像裁剪页是纯黑取景底，
     * 三者重写返回 false 回到「系统避让 + 窗口染色」的老路。
     */
    protected boolean edgeToEdge() {
        return true;
    }

    /**
     * 出血之后，是否由 {@link #onContentChanged()} 统一接管头部长高与底部避让。
     *
     * <p>主页自己按标签页决定 inset 落在哪一层（「我的」要铺到屏幕顶），所以重写返回 false。
     */
    protected boolean autoBleedSystemBars() {
        return true;
    }

    /** onCreate 时生效的风格，换肤广播与 {@link #onResume()} 兜底都用它判断是否需要重建 */
    private int appliedSkin = ThemeManager.SKIN_CLASSIC;

    /**
     * 在册、跟随配色风格的页面。换肤时由 {@link #reskinAll} 逐个当场重建，
     * 免得页面只能等回到前台（旧皮肤已经画了一帧）才补重建。
     * 弱引用 + onCreate 登记 / onDestroy 摘除，重写 followSkin() 为 false 的页面不入册。
     */
    private static final List<WeakReference<BaseActivity>> sSkinFollowers = new CopyOnWriteArrayList<>();

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
            sSkinFollowers.add(new WeakReference<>(this));
        }
        if (getResources() == null) {
            return;
        }
        super.onCreate(savedInstanceState);

        // 状态栏适配：出血页把两根栏涂透明，其余页面维持系统避让 + 窗口染色
        setupStatusBar();

        // 2. 添加Activity入场动画，实现丝滑切换
        // 默认「新页面从右滑入，旧页面向左滑出」；重写 useSlideTransition() 可改为淡入淡出
        if (useSlideTransition()) {
            ActivityTransition.applyEnter(this);
        } else {
            ActivityTransition.applyFade(this);
        }
    }

    /**
     * setContentView 之后统一接管二级页的系统栏：头部色带长到状态栏底下，内容按导航条 inset 避让，
     * 两根栏的图标深浅按出血区实际亮度判定。
     *
     * <p>头部认 {@code toolbar_back}（全站 MaterialToolbar 的通用 id）、{@code layout_header}、
     * {@code app_header} 三种；都没有时整页根布局上下一起避让。
     */
    @Override
    public void onContentChanged() {
        super.onContentChanged();
        if (!edgeToEdge() || !autoBleedSystemBars()) {
            return;
        }
        View root = contentRoot();
        if (root == null) {
            return;
        }
        View header = headerView();
        if (header == null) {
            SystemBarInsets.bleedVertical(root);
        } else {
            SystemBarInsets.extendHeader(header);
            SystemBarInsets.bleedBottom(root);
        }
        int pageBg = ThemeAttrs.color(this, R.attr.appPageBg, 0);
        applyBarIcons(SystemBarInsets.solidColor(header, pageBg),
                SystemBarInsets.solidColor(root, pageBg));
    }

    private View contentRoot() {
        View content = findViewById(android.R.id.content);
        return content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0
                ? ((ViewGroup) content).getChildAt(0) : null;
    }

    private View headerView() {
        int[] ids = {R.id.toolbar_back, R.id.layout_header, R.id.app_header};
        for (int id : ids) {
            View v = findViewById(id);
            if (v != null) {
                return v;
            }
        }
        return null;
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
     * 设置状态栏与导航栏
     *
     * <p>接入系统栏出血的页面（{@link #edgeToEdge()}）把状态栏涂透明，颜色由页面背景自己铺过去，
     * 图标深浅交给 {@link #applyBarIcons} 按实际底色判定；导航栏另走显式染色，理由见下。
     * 其余页面维持「系统避让 + 窗口染色」的老逻辑。
     */
    protected void setupStatusBar() {
        if (edgeToEdge()) {
            SystemBarInsets.enableEdgeToEdge(getWindow());
            // 导航条不能只涂透明：三键导航的机型会替应用兜一层近白（夜间近灰）的条，把页面底盖掉，
            // 于是底部永远差一档色。这里主动报一个和页面底相同的值——内容本就按 inset 避让过，
            // 导航条底下没有东西被遮，染完与真出血逐像素一致。
            getWindow().setNavigationBarColor(ThemeAttrs.color(this, R.attr.appPageBg, 0));
            return;
        }

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
     * 按系统栏背后那块出血区的实际底色，自动决定两根栏的图标深浅：亮底配深色图标、暗底配浅色图标。
     * 供接入 {@link SystemBarInsets} 的页面在每次可见时调用，取代按日夜档位写死。
     */
    public void applyBarIcons(int topColor, int bottomColor) {
        SystemBarInsets.setLightStatusIcons(getWindow(), !SystemBarInsets.isDark(topColor));
        SystemBarInsets.setLightNavigationIcons(getWindow(), !SystemBarInsets.isDark(bottomColor));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 兜底：换肤广播（{@link #reskinAll}）漏掉的页面——例如广播途中刚建好的实例——
        // 回到前台时补一次。配色风格变了不能在这里 setTheme()（主题已参与过一轮布局解析，改不干净），
        // 只能整页重建。
        if (followSkin() && ThemeManager.getSkin(this) != appliedSkin) {
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 重建会走一遍 onDestroy：把本页的在册引用摘掉，换肤广播才不会碰到已销毁的实例，
        // 也避免新旧两个实例同时在册导致重复重建。
        for (WeakReference<BaseActivity> ref : sSkinFollowers) {
            BaseActivity page = ref.get();
            if (page == null || page == this) {
                sSkinFollowers.remove(ref);
            }
        }
    }

    /**
     * 配色风格变更后立刻重建全站在册页面。
     *
     * <p>调用方是当时唯一可见的页面（外观设置），其余在册页面都压在它下面、用户看不到重建过程，
     * 等回退到任意页面时新皮肤已经画好——而不是先露一帧旧皮肤再当场跳变。
     * 只重建风格确实对不上的页面；按下发顺序重建，调用方自己在最后一步换肤。
     */
    public static void reskinAll(Context context) {
        int skin = ThemeManager.getSkin(context);
        for (WeakReference<BaseActivity> ref : sSkinFollowers) {
            BaseActivity page = ref.get();
            if (page != null && page.appliedSkin != skin && !page.isFinishing()) {
                page.recreate();
            }
        }
    }
}
