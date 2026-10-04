package com.example.myapplication.activity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.example.myapplication.R;
import com.google.android.material.tabs.TabLayout;

/**
 * 阅读器配色与主题（2026-10-02 自 ReadActivity 拆出，纯搬移，行为不变）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>背景派生配色</b>：导航栏/浮窗底色由当前背景色按亮度派生（updateChromePalette），
 *       深浅文字与强调色自动切换；</li>
 *   <li><b>日间 ⇄ 夜间平滑过渡</b>：ValueAnimator 逐帧补间原生导航栏，
 *       WebView 侧由 JS animateNightMode 逐帧重绘；</li>
 *   <li><b>浮窗配色跟随</b>：递归重着色浮窗视图树（themeViewTree），带宿主前台判定与
 *       Context 链回溯，杜绝书城/书架等外部页被染色。</li>
 * </ul>
 *
 * <p>状态模型：派生色值为静态字段（{@code sChrome1} 等），配合「历史派发色集合」
 * {@code sIssuedChrome*} 保证反复重着色时无条件收敛到当前色；前台活跃实例
 * {@code sActiveInstance} 由宿主 onResume/onPause 经 noteActive/clearActive 维护。
 */
class ReadThemeController {

    // ==================== 背景派生配色（上下导航栏 / 浮窗跟随当前背景色） ====================
    // 目标：导航栏与所有浮窗的底色不再是「日间白 / 夜间黑」两个写死值，而是由当前选中的
    // 背景色派生出「比它深一点」的同色系底色；文字与图标按背景明暗自动切换深浅，
    // 于是选黑色背景时天然等价于夜间模式，选牛皮纸/护眼绿时导航条也跟着变成同色系深色。
    //
    // 派生规则（压暗强度：浅色 6%，深色 20% —— 深色背景等比压暗几乎看不出差别，需额外补偿 14%）：
    //   L  = 相对亮度(base)；isDark = L < 0.42
    //   c1 = mix(base, #000, isDark ? 0.20 : 0.06)      // 上下导航 + 浮窗主背景
    //   c2 = mix(c1,   #000, isDark ? 0.20 : 0.12)      // 二级底（分组底 / 胶囊 / TabLayout）
    //   ln = isDark ? mix(c1, #FFF, 0.13) : mix(c1, #000, 0.16)   // 分隔线
    //   t1 = isDark ? #FFFFFF : #1D1D1F                  // 主文字 & 图标 tint
    //   t2 = isDark ? #98989D : #8E8E93                  // 次文字
    //   ac = isDark ? #0A84FF : #007AFF                  // 强调色（进度条 / 返回键）
    private static final float CHROME_SHADE       = 0.06f;  // 浅色背景压暗比例
    private static final float CHROME_SHADE_DARK  = 0.20f;  // 深色背景压暗比例（6% + 14% 补偿）
    private static final float CHROME_SHADE_2     = 0.12f;  // 二级底（浅色）
    private static final float CHROME_SHADE_2_DARK= 0.20f;  // 二级底（深色）
    private static final double DARK_LUM_THRESHOLD = 0.42;  // 低于该亮度即视为深色背景

    // 目录浮窗「定位当前章节」悬浮按钮：圆盘 = 浮窗主底向主文字色微调 TOC_LOCATE_DISC_TINT_RATIO，
    // 再叠加 TOC_LOCATE_DISC_ALPHA 的半透明；图标用主文字色（浅色主题即近黑，夜间自动转近白）。
    private static final float TOC_LOCATE_DISC_TINT_RATIO = 0.10f;
    private static final int   TOC_LOCATE_DISC_ALPHA      = 0xC0;

    static int sChrome1 = 0xFFEFEFEF;   // 当前派生：导航 / 浮窗主底（白底 6% 压暗的初值）
    private static int sChrome2 = 0xFFD2D2D2;   // 当前派生：二级底
    private static int sLine    = 0xFFC8C8C8;   // 当前派生：分隔线
    static int sText1   = 0xFF1D1D1F;   // 当前派生：主文字
    private static int sText2   = 0xFF8E8E93;   // 当前派生：次文字
    static int sAccent  = 0xFF007AFF;   // 当前派生：强调色
    private static int sNavText = 0xFF1D1D1F;   // 当前派生：上下导航栏文字（浅色模式用近黑，深色模式沿用浅灰）
    static boolean sIsDark = false;
    /** 日间⇄夜间切换的过渡时长（毫秒）。对应预览中选中的「平滑色彩渐变 / 1000ms」。 */
    private static final long NIGHT_TRANSITION_MS = 1000;
    // 历史上派发过的派生色集合：浮窗会被反复重着色（换背景 / 切夜间 / 换页），
    // 单靠「上一次」的 sPrev* 在「连续派生两次但只染一次」的场景会漏掉陈旧底色，
    // 用集合记录所有派发过的色值即可无条件收敛到当前色。
    private static final java.util.Set<Integer> sIssuedChrome1 = new java.util.HashSet<>();
    private static final java.util.Set<Integer> sIssuedChrome2 = new java.util.HashSet<>();
    private static final java.util.Set<Integer> sIssuedLine    = new java.util.HashSet<>();

    /** 文字在派生配色中的角色，见 {@link #applyTextColor}。 */
    private static final int TEXT_ROLE_NONE = 0;
    private static final int TEXT_ROLE_PRIMARY = 1;
    private static final int TEXT_ROLE_SECONDARY = 2;

    /**
     * 当前前台活跃的 ReadActivity 实例（仅在 onResume/onPause 维护）。
     * <p>
     * 用于 themeViewTree 的宿主判定：只有当前 Activity 是某个 ReadActivity 实例时，浮窗/列表
     * 才能被派生配色覆盖。书城/书架/设置等外部页在前台时，sActiveInstance == null，
     * 任何迟到的 themeViewTree 调用都会被静默忽略，杜绝「书城等也跟随背景色」。
     * <p>
     * 设计要点：用「前台实例引用」而非上一版的 isReaderHost(ctx)。后者在 PopupWindow +
     * ViewPager2 嵌套时 itemView.getContext() 的 ContextWrapper 层级与判定不一致，
     * 导致阅读器自己的目录/书签浮窗也被错判拒绝——上一轮反馈「目录书签页不跟随」。
     * 现在改为 Activity 生命周期级别的判定：PopupWindow 不会触发 onPause，
     * 所以目录浮窗打开时 ReadActivity 仍在 onResume → sActiveInstance 仍指向自己 → 染色成功。
     */
    private static volatile ReadActivity sActiveInstance;

    /** 当前派生配色是否为深色（决定文字/图标用浅色还是深色）。 */
    public static boolean isChromeDark() { return sIsDark; }

    // ---- 派生色取值接口：供浮窗内 Fragment / Adapter 直接取用，避免再写死 #007AFF 之类 ----
    public static int getAccentColor()            { return sAccent;  }  // 强调色（当前章节 / 进度条）
    public static int getTextPrimaryColor()       { return sText1;   }  // 主文字
    public static int getTextSecondaryColor()     { return sText2;   }  // 次文字
    public static int getChromeBgColor()          { return sChrome1; }  // 浮窗主底
    public static int getChromeBgSecondaryColor() { return sChrome2; }  // 二级底 / 分组底
    public static int getChromeLineColor()        { return sLine;    }  // 分隔线
    public static int getNavTextColor()           { return sNavText; }  // 导航栏文字

    /** sRGB 相对亮度（WCAG 公式），用于判定背景明暗。 */
    private static double relativeLuminance(int color) {
        double r = ((color >> 16) & 0xFF) / 255.0;
        double g = ((color >> 8) & 0xFF) / 255.0;
        double b = (color & 0xFF) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** 按 a 的比例把 c1 混向 c2（a=0 得 c1，a=1 得 c2）。 */
    static int mixColors(int c1, int c2, float a) {
        int r = (int) ((((c1 >> 16) & 0xFF) + ((((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * a)));
        int g = (int) ((((c1 >> 8) & 0xFF) + ((((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * a)));
        int b = (int) (((c1 & 0xFF) + (((c2 & 0xFF) - (c1 & 0xFF)) * a)));
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 取派生配色的基准色 = 页面实际显示的背景色（effectiveBgBase）。
     * 纹理背景用其预览底色，夜间模式固定 #1A1A1A。
     */
    private int getChromeBaseColor() {
        return activity.effectiveBgBase;
    }

    /** 由当前背景色重新计算派生配色（不动 UI，仅更新静态色值）。 */
    private void updateChromePalette() {
        int base = getChromeBaseColor();
        boolean dark = relativeLuminance(base) < DARK_LUM_THRESHOLD;

        sIsDark  = dark;
        sChrome1 = mixColors(base, Color.BLACK, dark ? CHROME_SHADE_DARK : CHROME_SHADE);
        sChrome2 = mixColors(sChrome1, Color.BLACK, dark ? CHROME_SHADE_2_DARK : CHROME_SHADE_2);
        sLine    = dark ? mixColors(sChrome1, Color.WHITE, 0.13f)
                        : mixColors(sChrome1, Color.BLACK, 0.16f);
        sText1   = dark ? Color.WHITE : Color.parseColor("#1D1D1F");
        sText2   = dark ? Color.parseColor("#98989D") : Color.parseColor("#8E8E93");
        // 上下导航栏文字：浅色模式统一用近黑（原灰色 sText2 改黑），深色模式沿用浅灰保证对比度
        sNavText = dark ? sText2 : Color.parseColor("#1D1D1F");
        sAccent  = dark ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");

        // 记录本轮派发过的色值，供后续重着色时识别「陈旧派生色」
        sIssuedChrome1.add(sChrome1);
        sIssuedChrome2.add(sChrome2);
        sIssuedLine.add(sLine);
    }

    /**
     * 应用派生配色：状态栏图标明暗 + 上下导航栏 + 图标/文字，并让已显示的浮窗实时跟随。
     * 取代原先只按 isNightMode 二值切换的 applyNightModeToNavOnly()。
     */
    void applyChromeTheme() {
        updateChromePalette();
        applyChromeColorsWith(sChrome1, sChrome2, sLine,
                sText1, sText2, sNavText, sAccent, sIsDark);
        // 已显示的浮窗（目录 / 设置 / 更多 / 背景 / 字体）实时跟随
        themeShowingPopups();
    }

    /**
     * 用给定的一组配色刷新系统导航条、上下导航栏、图标、文字与状态栏图标明暗。
     *
     * <p>系统导航条必须显式染色：涂透明的话，三键导航机型会自己兜一条近白/近灰的底，
     * 换任何纸张都不动（羊皮纸上尤其割裂）。色带取本帧面板底色 chrome1——这条带只在阅读器
     * 上下导航栏滑出时可见（见 {@code ReadActivity#showSystemBars}），跟底栏同色才接得上。
     * 图标深浅按这条色带的实色判定，日间⇄夜间的 1000ms 补间里才不会出现在同色底上看不清。
     */
    private void applyChromeColorsWith(int chrome1, int chrome2, int line,
                                       int text1, int text2, int navText, int accent, boolean dark) {
        // 状态栏图标：浅色背景用深色图标，深色背景用浅色图标
        View decorView = activity.getWindow().getDecorView();
        int flags = decorView.getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        // 系统导航条：色带取本帧面板底色，图标深浅按这条色带的实色判，不按目标档位判
        activity.getWindow().setNavigationBarColor(chrome1);
        if (relativeLuminance(chrome1) < DARK_LUM_THRESHOLD) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decorView.setSystemUiVisibility(flags);

        activity.layoutTopNav.setBackgroundColor(chrome1);
        activity.layoutBottomNav.setBackgroundColor(chrome1);
        activity.tvToolbarTitle.setTextColor(text1);

        // 底栏顶部 0.5dp 分隔线（布局里的第一个子 View）
        if (activity.layoutBottomNav instanceof ViewGroup && ((ViewGroup) activity.layoutBottomNav).getChildCount() > 0) {
            View sep = ((ViewGroup) activity.layoutBottomNav).getChildAt(0);
            if (sep != null) sep.setBackgroundColor(line);
        }
        // 章节进度条（液态滑块）：从面板底色派生中性配色，随日/夜平滑渐变逐帧刷新
        try {
            activity.liquidSlider.setChrome(chrome1, text1, dark);
        } catch (Throwable ignored) { }

        activity.ivNightModeIcon.setImageResource(dark ? R.drawable.ic_day : R.drawable.ic_night);
        activity.tvNightModeText.setText(dark ? "日间" : "夜间");
        activity.tvNightModeText.setTextColor(navText);

        setIconsForThemeWith(text1, accent, navText, dark);
    }

    private void setIconsForTheme() {
        setIconsForThemeWith(sText1, sAccent, sNavText, sIsDark);
    }

    private void setIconsForThemeWith(int text1, int accent, int navText, boolean dark) {
        // 图标 drawable 自身颜色会被 ImageView 的 tint 覆盖，因此仅切换 src 无效，
        // 必须同步切换 tint 才能让图标在深色背景上都清晰可见。
        // 配色不再按日/夜二值写死，而是取当前背景派生出的主文字色与强调色。
        int tint = text1;

        ImageView ivBack = activity.findViewById(R.id.iv_back);
        ImageView ivMore = activity.findViewById(R.id.iv_more);
        ivBack.setImageResource(dark ? R.drawable.ic_back_white : R.drawable.ic_back_black);
        ivMore.setImageResource(dark ? R.drawable.ic_more_vert_white : R.drawable.ic_more_vert_black);
        // 返回键使用强调色，其余图标与主文字同色
        ivBack.setImageTintList(ColorStateList.valueOf(accent));
        ivMore.setImageTintList(ColorStateList.valueOf(tint));

        ImageView ivCatalog = activity.findViewById(R.id.iv_catalog_icon);
        ImageView ivSettings = activity.findViewById(R.id.iv_settings_icon);
        ivCatalog.setImageResource(dark ? R.drawable.ic_list_white : R.drawable.ic_list_black);
        ivSettings.setImageResource(dark ? R.drawable.ic_settings_white : R.drawable.ic_settings_black);
        ivCatalog.setImageTintList(ColorStateList.valueOf(tint));
        ivSettings.setImageTintList(ColorStateList.valueOf(tint));

        // 夜间/日间切换图标自身也要跟随 tint（XML 中 tint 固定为深色，夜间会看不清）
        activity.ivNightModeIcon.setImageTintList(ColorStateList.valueOf(tint));

        int textColor = navText;
        ((TextView) activity.findViewById(R.id.tv_prev_chapter)).setTextColor(textColor);
        ((TextView) activity.findViewById(R.id.tv_next_chapter)).setTextColor(textColor);
        ((TextView) activity.findViewById(R.id.tv_catalog_text)).setTextColor(textColor);
        ((TextView) activity.findViewById(R.id.tv_settings_text)).setTextColor(textColor);
        // 主题切换会重置图标 tint；若此时设置浮窗仍开着，需把「设置」图标的选中态补回来
        if (activity.settingsPopupWindow != null && activity.settingsPopupWindow.isShowing()) activity.setSettingsNavActive(true);
    }

    // ==================== 日间 ⇄ 夜间 平滑过渡（平滑色彩渐变） ====================
    /**
     * 带「平滑色彩渐变」过渡地切换日/夜间：
     * <ul>
     *   <li>原生上下导航栏：用 {@link ValueAnimator} + {@link ArgbEvaluator} 在「旧配色→新配色」间逐帧补间；</li>
     *   <li>WebView 阅读区：调用 JS {@code animateNightMode(...)}，由 Canvas 逐帧重绘背景与文字色；</li>
     *   <li>过渡时长见 {@link #NIGHT_TRANSITION_MS}（默认 1000ms）。</li>
     * </ul>
     * 浮窗（目录/设置等）随动画逐帧重染，与导航栏、阅读区同步渐变，
     * 避免「阅读区已渐变到位、面板还停在旧配色最后跳一下」。
     */
    void animateNightModeToggle() {
        animateNightModeTo(!activity.isNightMode);
    }

    /**
     * 带目标态的夜间切换动画。底栏日/夜按钮与背景色块（黑色≡夜间）共用。
     * 统一语义：黑色背景(#1A1A1A)与夜间模式是同一个状态——
     * 退出夜间时若日间背景残留为黑色（旧版本数据），回退纯白，
     * 避免「黑→黑」两端同色导致动画看似无反应。
     */
    void animateNightModeTo(boolean toNight) {
        if (!toNight && activity.currentBgColor == 3) activity.currentBgColor = 0;

        // ---- WebView 阅读区起止颜色 ----
        final String dayBg  = dayWebBgHex();
        final String dayTx  = dayWebTextHex();
        final String nightBg = "#1A1A1A";
        final String nightTx = "#AAAAAA";
        final String fromBg, fromTx, toBg, toTx;
        if (toNight) { fromBg = dayBg;  fromTx = dayTx;  toBg = nightBg; toTx = nightTx; }
        else         { fromBg = nightBg; fromTx = nightTx; toBg = dayBg;  toTx = dayTx;  }

        // ---- 原生导航栏：先快照「旧」配色，翻转后再算「新」配色 ----
        final int fromC1 = sChrome1, fromC2 = sChrome2, fromLine = sLine;
        final int fromT1 = sText1,   fromT2 = sText2,   fromNav = sNavText, fromAcc = sAccent;
        final boolean fromDark = sIsDark;

        activity.isNightMode = toNight;
        activity.effectiveBgBase = toNight ? Color.parseColor("#1A1A1A") : activity.resolveBaseForMode(activity.currentBgColor);
        updateChromePalette(); // 此时静态字段 = 目标配色
        final int toC1 = sChrome1, toC2 = sChrome2, toLine = sLine;
        final int toT1 = sText1,   toT2 = sText2,   toNav = sNavText, toAcc = sAccent;
        // 动画期间把静态字段回退为「旧」，避免其它读取方（浮窗/Getter）出现半成品
        sChrome1 = fromC1; sChrome2 = fromC2; sLine = fromLine;
        sText1 = fromT1;   sText2 = fromT2;   sNavText = fromNav; sAccent = fromAcc; sIsDark = fromDark;

        // 状态栏图标按目标明暗立即切换（系统层不做补间）
        applyChromeColorsWith(fromC1, fromC2, fromLine, fromT1, fromT2, fromNav, fromAcc, toNight);

        // ---- WebView 阅读区补间（逐帧重绘）----
        String js = "animateNightMode(" + toNight + "," + NIGHT_TRANSITION_MS + ",'"
                + fromBg + "','" + fromTx + "','" + toBg + "','" + toTx + "')";
        activity.webView.evaluateJavascript(js, null);

        // ---- 原生导航栏补间 ----
        final ArgbEvaluator eval = new ArgbEvaluator();
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(NIGHT_TRANSITION_MS);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            int c1 = (int) eval.evaluate(t, fromC1, toC1);
            int c2 = (int) eval.evaluate(t, fromC2, toC2);
            int ln = (int) eval.evaluate(t, fromLine, toLine);
            int t1 = (int) eval.evaluate(t, fromT1, toT1);
            int t2 = (int) eval.evaluate(t, fromT2, toT2);
            int nv = (int) eval.evaluate(t, fromNav, toNav);
            int ac = (int) eval.evaluate(t, fromAcc, toAcc);

            // 浮窗逐帧跟随：静态色先置为本帧中间值，themeShowingPopups 才能补间染色。
            // 中间值登记进「已派发色集合」——mapBgColor 靠集合识别「上一帧染出来的底色」，
            // 不登记的话下一帧就认不出这是主底/二级底，浮窗会停在第一帧颜色不再前进。
            // 集合不会无限增长：同一日/夜两端的补间端点固定，中间值与上次切换完全重复。
            sChrome1 = c1; sChrome2 = c2; sLine = ln;
            sText1 = t1;   sText2 = t2;   sNavText = nv; sAccent = ac;
            sIssuedChrome1.add(c1); sIssuedChrome2.add(c2); sIssuedLine.add(ln);

            applyChromeColorsWith(c1, c2, ln, t1, t2, nv, ac, toNight);
            themeShowingPopups();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                // 落定为目标配色
                sChrome1 = toC1; sChrome2 = toC2; sLine = toLine;
                sText1 = toT1;   sText2 = toT2;   sNavText = toNav; sAccent = toAcc; sIsDark = toNight;
                applyChromeTheme(); // 完整刷新（含图标/状态栏/浮窗）
                // 退出夜间模式后恢复当前选中的背景纹理/纯色（与原逻辑一致）
                if (!toNight) activity.applyBackgroundColorToWebView(activity.currentBgColor);
                activity.saveReadingPreferences();
            }
        });
        anim.start();
    }

    /** 当前选中背景（日间）在 WebView 中的底色十六进制串。 */
    private String dayWebBgHex() {
        int m = activity.currentBgColor;
        if (m >= 10) {
            int tex = m - 10;
            if (tex >= 0 && tex < ReadActivity.BG_TEXTURE_PREVIEW_COLORS.length) return toHex(ReadActivity.BG_TEXTURE_PREVIEW_COLORS[tex]);
            return "#FFFFFF";
        }
        if (m < 0 || m >= ReadActivity.BG_COLORS.length) m = 0;
        return ReadActivity.BG_COLORS[m];
    }

    /** 当前选中背景（日间）在 WebView 中的文字色十六进制串。 */
    private String dayWebTextHex() {
        int m = activity.currentBgColor;
        if (m >= 10) return "#1D1D1F"; // 纹理模式统一用近黑文字
        if (m < 0 || m >= ReadActivity.BG_TEXT_COLORS.length) m = 0;
        return ReadActivity.BG_TEXT_COLORS[m];
    }

    /** 打包色值 → "#RRGGBB"（供 JS 调用）。 */
    private static String toHex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    // ==================== 浮窗配色跟随当前背景色 ====================
    // 浮窗布局里写死的是 iOS 浅色（ios_bg / ios_bg_grouped / ios_separator / ios_text_*），
    // 之前靠一张固定的「浅↔深」映射表在夜间模式下整体翻色，与具体背景色无关。
    // 现在改为映射到「由当前背景色派生出的配色」（见 updateChromePalette），
    // 于是护眼绿背景得到偏绿的导航/浮窗底，黑色背景得到夜间效果。
    // 旧映射表里的日/夜两套硬编码色仍然保留在识别列表中，保证历史颜色也能被正确收敛。
    private static final int LEGACY_NIGHT_BG_PRIMARY   = 0xFF000000;
    private static final int LEGACY_NIGHT_BG_SECONDARY = 0xFF1C1C1E;
    private static final int LEGACY_NIGHT_DIVIDER      = 0xFF38383A;
    private static final int LEGACY_CAPSULE_NORMAL     = 0xFF2C2C2E;
    private static final int LEGACY_CAPSULE_INNER      = 0xFF48484A;
    private static final int LEGACY_CAPSULE_PRESSED    = 0xFF3A3A3C;
    private static final int LEGACY_DAY_TEXT_SECONDARY = 0xFF8E8E93;
    private static final int LEGACY_NIGHT_TEXT_SECONDARY = 0xFF98989D;

    /**
     * 递归为浮窗视图树应用当前派生配色：背景、分割线、文字、TabLayout。
     * <p>宿主判定：当前台 Activity 不是某个 ReadActivity 实例时（例如书城/书架/设置在前台，
     * 而某个迟到的异步回调仍想调本方法），本方法会直接 return，杜绝外部页被染色。
     * <p>判定依据是 {@link #sActiveInstance}（生命周期维护），而不是 root.getContext() 的
     * ContextWrapper 链——后者在 PopupWindow + ViewPager2 嵌套时不可靠。
     */
    public static void themeViewTree(@Nullable View root) {
        if (root == null) return;
        ReadActivity owner = sActiveInstance;
        if (owner == null || owner.isFinishing() || owner.isDestroyed()) return;
        // 二次校验：owner 必须仍在 onResume（防御 paused 状态下的漏判）；
        // root 的 Context 必须能从 ContextWrapper 链回溯到 owner，否则拒绝染色。
        // 阅读器内的 popup（chapter/settings/moreMenu 等）虽然走独立 Window，
        // 但它们的 Context 都是从 ReadActivity(this) 出发创建的，链上一定能找到 owner。
        // BookDetailActivity 等外部页面的 view，Context 链只能回到 BookDetailActivity 自身，
        // 永远找不到 owner → 被拒绝，杜绝「阅读器外页被染色」。
        if (!owner.activityResumed) return;
        if (!isContextTraceableTo(root, owner)) return;
        applyThemeRecursive(root);
    }

    /**
     * 沿 ContextWrapper 链向上追溯 root.getContext()，看是否能找到 owner Activity。
     * 命中则说明 root 是 owner 这条线创建的（阅读器内的 view 或内部 popup）；
     * 追不到说明 root 来自外部 Activity（书城 / 书架 / 详情等），必须拒绝。
     */
    private static boolean isContextTraceableTo(View root, ReadActivity owner) {
        try {
            Context ctx = root.getContext();
            int safety = 0;
            while (ctx != null && safety++ < 16) {
                if (ctx == owner) return true;
                if (!(ctx instanceof ContextWrapper)) break;
                Context base = ((ContextWrapper) ctx).getBaseContext();
                if (base == ctx) break;
                ctx = base;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyThemeRecursive(View view) {
        // 打了 tag_keep_own_color 的子树（如背景色块）必须显示自己的真实颜色，跳过
        if (Boolean.TRUE.equals(view.getTag(R.id.tag_keep_own_color))) return;

        // 0) 目录浮窗「定位当前章节」悬浮按钮：半透明圆盘 + 主文字色图标。
        //    圆盘底色由浮窗主底向主文字色微调（浅色主题=奶油白盘+深图标、夜间=深灰盘+浅图标），
        //    因此不能走下面那张「已知色 → 派生色」的映射表（自定义混色不在表内），
        //    这里直接按当前调色板生成，夜间切换时由 themeShowingPopups → 本方法实时重刷。
        if (view.getId() == R.id.btn_toc_locate) {
            try {
                android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
                disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                int base = mixColors(sChrome1, sText1, TOC_LOCATE_DISC_TINT_RATIO);
                disc.setColor((TOC_LOCATE_DISC_ALPHA << 24) | (base & 0x00FFFFFF));
                view.setBackground(disc);
                if (view instanceof ImageView) {
                    ((ImageView) view).setImageTintList(ColorStateList.valueOf(sText1));
                }
            } catch (Throwable ignored) { }
            return;
        }

        // 1) 背景：纯色 / shape / selector / layer-list 递归重着色
        //    本项目的夜间模式是「手动」的（未调用 AppCompatDelegate），
        //    因此 res/drawable-night 资源限定符不会生效；胶囊这类 <selector> 必须在代码里
        //    逐个子 <shape> 重着色，否则深色背景下仍是浅底 + 白字 = 内容不可见。
        if (themeDrawable(view.getBackground())) view.invalidate();

        // 1b) SeekBar 的进度轨道不是 background，需单独重着色
        if (view instanceof android.widget.SeekBar) {
            try {
                if (themeDrawable(((android.widget.SeekBar) view).getProgressDrawable())) {
                    view.invalidate();
                }
            } catch (Throwable ignored) { }
        }

        // 2) 文字颜色
        if (view instanceof TextView && !(view instanceof android.widget.Button)) {
            applyTextColor((TextView) view);
        }

        // 3) TabLayout：标签栏背景与文字（不递归进其内部 tab 子视图，避免与 setTabTextColors 冲突）
        if (view instanceof TabLayout) {
            TabLayout tl = (TabLayout) view;
            tl.setBackgroundColor(sChrome2);
            tl.setTabTextColors(sText2, sAccent);
            tl.setSelectedTabIndicatorColor(sAccent);
            return;
        }

        // 4) 递归子节点（RecyclerView 仅返回已 attach 的可见项，遍历成本可控）
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyThemeRecursive(vg.getChildAt(i));
            }
        }
    }

    /**
     * 把布局里写死的浅色体系映射到当前派生配色。
     * 除了原始色值，也识别「历史上派发过的派生值」（sIssuedChrome*），
     * 这样反复重着色时不依赖调用顺序，也不会残留旧底色。
     */
    private static int mapBgColor(int c) {
        // 主背景：ios_bg / 旧夜间主底 / 任意一轮派发过的主底
        if (c == 0xFFFFFFFF || c == 0xFFFAFAFA || c == 0xFFF5F5F5
                || c == LEGACY_NIGHT_BG_PRIMARY || sIssuedChrome1.contains(c)) {
            return sChrome1;
        }
        // 二级底：ios_bg_grouped / 旧夜间次底 / 任意一轮派发过的二级底
        if (c == 0xFFF2F2F7 || c == 0xFFEFEFF4
                || c == LEGACY_NIGHT_BG_SECONDARY || c == LEGACY_CAPSULE_NORMAL
                || sIssuedChrome2.contains(c)) {
            return sChrome2;
        }
        // 分隔线：ios_separator / 旧夜间分割线 / 任意一轮派发过的分隔线
        if (c == 0xFFE5E5EA || c == 0xFFC6C7CC || c == 0xFFE0E0E0 || c == 0xFFEEEEEE
                || c == LEGACY_NIGHT_DIVIDER || c == LEGACY_CAPSULE_PRESSED
                || sIssuedLine.contains(c)) {
            return sLine;
        }
        // 胶囊内胆（白胶囊）：与浮窗主底同级
        if (c == LEGACY_CAPSULE_INNER) return sChrome1;
        return c;
    }

    /** 文字颜色：主文字 / 次文字 / 三级文字 → 派生文字色。 */
    private static int mapTextColor(int c) {
        int rgb = c & 0x00FFFFFF;   // 去掉 alpha：?android:attr/textColorSecondary 会解析出带透明度的灰
        if (rgb == 0x1D1D1F || rgb == 0x333333 || rgb == 0x000000 || rgb == 0xFFFFFF) return sText1;
        if (rgb == 0x8E8E93 || rgb == 0x98989D || rgb == 0xC7C7CC
                || rgb == 0x666666 || rgb == 0x999999) return sText2;
        // 通用兜底：布局里用 ?android:attr/textColorPrimary / textColorSecondary 时，
        // 解析出来的系统灰阶色（#000000、#8A000000…）不在上面的清单里，
        // 会导致书签条目的标题/预览在深色背景上依旧是深色字（看起来"没跟随"）。
        // 规则：低饱和（灰阶）文字 → 近黑/近白归主文字、中间灰归次文字；
        //      有彩色（橙色备注 #FF6600、红色 #FF4444 等强调色）保持原样。
        float[] hsv = new float[3];
        Color.colorToHSV(0xFF000000 | rgb, hsv);
        if (hsv[1] <= 0.25f) {
            return (hsv[2] >= 0.85f || hsv[2] <= 0.45f) ? sText1 : sText2;
        }
        return c;
    }

    /**
     * 文字着色：优先按上次归类的「角色」取色。
     * <p>夜间切换现在逐帧重染浮窗，主文字会从近黑补间到近白，途中必然经过中间灰；
     * 而 {@link #mapTextColor} 的灰阶兜底把中间灰归为「次文字」，于是下一帧起该文字
     * 再也不会回到主文字色（结尾落定成灰色，对比度不足）。归类结果记在 tag 上，
     * 同一视图后续各帧与重刷都按角色取色，只有首次（或彩色等不归管的文字）才做归类。
     */
    private static void applyTextColor(TextView tv) {
        int tc = tv.getCurrentTextColor();
        Object role = tv.getTag(R.id.tag_theme_text_role);
        int mapped;
        if (Integer.valueOf(TEXT_ROLE_PRIMARY).equals(role)) {
            mapped = sText1;
        } else if (Integer.valueOf(TEXT_ROLE_SECONDARY).equals(role)) {
            mapped = sText2;
        } else {
            mapped = mapTextColor(tc);
        }
        if (mapped != tc) tv.setTextColor(mapped);
        int newRole = mapped == sText1 ? TEXT_ROLE_PRIMARY
                    : mapped == sText2 ? TEXT_ROLE_SECONDARY : TEXT_ROLE_NONE;
        if (!Integer.valueOf(newRole).equals(role)) tv.setTag(R.id.tag_theme_text_role, newRole);
    }

    /**
     * 对 drawable 重着色，并递归处理 selector / layer-list / inset / scale 的子图。
     * 全部走同一张派生色表（胶囊与面板已在派生规则里区分层级：胶囊底 = 二级底）。
     * @return 是否发生变化
     */
    private static boolean themeDrawable(Drawable d) {
        if (d == null) return false;
        // 关键修复：先 mutate() 脱离「共享 ConstantState」。
        // 从同一资源（@color/ios_bg、@drawable/bg_xxx 等）inflate 出来的 Drawable 在
        // 进程内共享同一份 ConstantState；直接 setColor() 会改到全局共享状态，导致书城 /
        // 书架 / 设置 / 详情等所有使用 ios_bg 的界面背景被「染色」跟随阅读器背景。
        // mutate() 让本实例拿到一份独立状态，着色只作用于当前浮窗，不再泄漏到全 App。
        d = d.mutate();
        boolean changed = false;
        try {
            if (d instanceof ColorDrawable) {
                int c = ((ColorDrawable) d).getColor();
                int mapped = mapBgColor(c);
                if (mapped != c) {
                    ((ColorDrawable) d).setColor(mapped);
                    changed = true;
                }
            } else if (d instanceof GradientDrawable) {
                android.content.res.ColorStateList csl = ((GradientDrawable) d).getColor(); // API29+
                if (csl != null) {
                    int c = csl.getDefaultColor();
                    int mapped = mapBgColor(c);
                    if (mapped != c) {
                        ((GradientDrawable) d).setColor(mapped);
                        changed = true;
                    }
                }
            } else if (d instanceof android.graphics.drawable.StateListDrawable) {
                // <selector>：逐个重着色子 <shape>（常态 / 按下态）
                android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
                if (cs instanceof android.graphics.drawable.DrawableContainer.DrawableContainerState) {
                    android.graphics.drawable.DrawableContainer.DrawableContainerState dcs =
                            (android.graphics.drawable.DrawableContainer.DrawableContainerState) cs;
                    for (int i = 0; i < dcs.getChildCount(); i++) {
                        changed |= themeDrawable(dcs.getChild(i));
                    }
                }
            } else if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                    changed |= themeDrawable(ld.getDrawable(i));
                }
            } else if (d instanceof android.graphics.drawable.InsetDrawable) {
                changed |= themeDrawable(((android.graphics.drawable.InsetDrawable) d).getDrawable());
            } else if (d instanceof android.graphics.drawable.ScaleDrawable) {
                changed |= themeDrawable(((android.graphics.drawable.ScaleDrawable) d).getDrawable());
            }
        } catch (Throwable ignored) { }
        return changed;
    }

    /** 夜间模式切换时，对当前已显示的浮窗视图树重新着色，实现实时跟随。 */
    private void themeShowingPopups() {
        themeIfShowing(activity.chapterPopupWindow);
        themeIfShowing(activity.moreMenuPopupWindow);
        themeIfShowing(activity.settingsPopupWindow);
        themeIfShowing(activity.moreSettingsPopupWindow);
        themeIfShowing(activity.bgColorsPopupWindow);
        themeIfShowing(activity.fontsPopupWindow);
        // 翻页分段：指示器底色需按新主题重着色（thumb 打了 keep_own_color 不走通用重刷），文字选中恒深需重设
        if (activity.settingsPopupWindow != null && activity.settingsPopupWindow.isShowing() && activity.settingsPageSegRefresher != null) {
            activity.settingsPageSegRefresher.run();
        }
    }

    private void themeIfShowing(PopupWindow pw) {
        if (pw != null && pw.isShowing()) themeViewTree(pw.getContentView());
    }

    /**
     * 设置系列浮窗的定位偏移：让浮窗底缘停在底部「目录/夜间/设置」这一行（第二行）的上沿，
     * 从而盖住其上方的「上一章/下一章」行，同时露出底栏本行、不被覆盖也不变暗。
     * 底栏第二行固定高度 60dp，故偏移取该值（含 0.5dp 分隔线误差可忽略）。
     */
    int getNavBarHeightPx() {
        return (int) (60 * activity.getResources().getDisplayMetrics().density);
    }
    // ---- 前台活跃实例（宿主生命周期维护） ----


    static void noteActive(ReadActivity a) { sActiveInstance = a; }

    static void clearActive(ReadActivity a) {
        if (sActiveInstance == a) sActiveInstance = null;
    }

    private final ReadActivity activity;

    ReadThemeController(ReadActivity activity) {
        this.activity = activity;
    }
}
