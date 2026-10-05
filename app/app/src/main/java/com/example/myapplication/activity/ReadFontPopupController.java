package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.LoginHelper;
import com.example.myapplication.utils.ExternalPrefs;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.VolumeDeriver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.AbsListView;
import android.widget.TextView;

import android.widget.LinearLayout;
import android.app.AlertDialog;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewGroup;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.fragment.app.Fragment;
import android.content.res.ColorStateList;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.Bookmark;
import com.example.myapplication.bean.MajorChapter;
import com.example.myapplication.bean.ReadingProgress;
import com.example.myapplication.fragment.PopupBookmarkFragment;
import com.example.myapplication.fragment.PopupChapterFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.view.LiquidSlider;
import com.bumptech.glide.Glide;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ReadTimeRequest;
import com.example.myapplication.utils.Hint;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 阅读器「字体选择」弹窗控制器（列表/下载/删除/预览）。
 *
 * 2026-10-02 从 ReadActivity 拆出（结构优化）：纯代码搬移，行为不变。
 * 与宿主同包，直接读写 activity 的包私有成员。
 */
class ReadFontPopupController {

    private final ReadActivity activity;

    ReadFontPopupController(ReadActivity activity) {
        this.activity = activity;
    }

    /** 打开字体选择弹窗（原 ReadActivity.showFontsDialog） */
    void show() {
        showFontsDialog();
    }

    /** 启动后后台预拉取字体列表（原 preloadBackendFontsIfNeeded） */
    void preloadIfNeeded() {
        preloadBackendFontsIfNeeded();
    }

    /**
     * 字体网格卡片数据项
     */
    private static class FontCardItem {
        final String displayName;
        final String cssName;
        final boolean isBackend;
        final com.example.myapplication.bean.FontItem backendFont;

        FontCardItem(String displayName, String cssName, boolean isBackend,
                     com.example.myapplication.bean.FontItem backendFont) {
            this.displayName = displayName;
            this.cssName = cssName;
            this.isBackend = isBackend;
            this.backendFont = backendFont;
        }
    }

    /**
     * 显示字体选择弹窗
     */
    @SuppressLint({"InflateParams", "SetTextI18n"})
    private void showFontsDialog() {
        View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_fonts, null);
        LinearLayout container = popupView.findViewById(R.id.container_fonts);

        rebuildFontList(container);

        popupView.findViewById(R.id.iv_font_popup_close).setOnClickListener(v -> {
            SwipeDismissLayout h = SwipeDismissLayout.findHost(v);
            if (h != null) h.dismissAnimated();
            else SlideOutPopupWindow.dismissImmediate(activity.fontsPopupWindow);
        });

        activity.themeViewTree(popupView);

        // 弹窗高度固定为屏幕一半，内容过多时由内部 ScrollView 滚动
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
        int popupHeight = dm.heightPixels / 2;

        // 以全屏透明窗口 + 跟手下滑容器承载面板
        activity.fontsPopupWindow = activity.showSwipeDismissPopup(popupView, popupHeight, "font");
        // 联动：弹窗滑出时设置面板收回；弹窗收起（关闭按钮/跟手下滑任一路径 dismiss）时面板重新滑出
        activity.retractSettingsPanelForSub();
        activity.suspendAutoPage();
        activity.mainHandler.removeCallbacks(activity.hideNavRunnable);
        activity.fontsPopupWindow.setOnDismissListener(() -> {
            activity.resetAutoHideTimer();
            activity.resumeAutoPageIfSuspended();
            activity.restoreSettingsPanelFromSub();
        });
    }

    /**
     * 从后端获取字体列表。container 为 null 时仅用于后台预加载（不刷新弹窗 UI，
     * 但会在拿到列表后刷新「切换字体」按钮文案，避免未打开弹窗时显示 cssName）。
     */
    private void fetchFontsFromBackend(LinearLayout container) {
        RetrofitClient.getApiService().getFonts().enqueue(new retrofit2.Callback<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>>() {
            @Override
            public void onResponse(@NonNull retrofit2.Call<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> call,
                                   @NonNull retrofit2.Response<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getData() != null) {
                    activity.backendFonts = response.body().getData();
                    activity.runOnUiThread(() -> {
                        if (container != null) {
                            // 移除加载提示并重建 3 列网格
                            View loading = container.findViewWithTag("tv_loading");
                            if (loading != null) container.removeView(loading);
                            rebuildFontList(container);
                        }
                        // 列表就绪后刷新「切换字体」按钮：可能已从 cssName 解析出中文展示名
                        applyCurrentFontToSwitchButton();
                    });
                } else {
                    activity.runOnUiThread(() -> {
                        if (container != null) {
                            View loading = container.findViewWithTag("tv_loading");
                            if (loading instanceof TextView) {
                                ((TextView) loading).setText("暂无在线字体");
                            }
                        }
                    });
                }
            }

            @Override
            public void onFailure(@NonNull retrofit2.Call<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> call,
                                  @NonNull Throwable t) {
                activity.runOnUiThread(() -> {
                    if (container != null) {
                        View loading = container.findViewWithTag("tv_loading");
                        if (loading instanceof TextView) {
                            ((TextView) loading).setText("网络异常，请稍后再试");
                        }
                    }
                });
            }
        });
    }

    /**
     * 启动后后台预拉取字体列表：仅当当前选中的是在线字体且列表尚未加载时，
     * 这样即使从未打开过字体弹窗，「切换字体」按钮也能显示中文名而非 cssName。
     */
    private void preloadBackendFontsIfNeeded() {
        if (!activity.FONT_SYSTEM_CSS.equals(activity.currentFontFamily)
                && !activity.FONT_DEFAULT_CSS.equals(activity.currentFontFamily)
                && activity.backendFonts.isEmpty()) {
            fetchFontsFromBackend(null);
        }
    }

    /**
     * 长按已下载字体 → 弹出删除确认对话框
     */
    private void showDeleteFontDialog(FontCardItem item, LinearLayout container) {
        new android.app.AlertDialog.Builder(activity)
                .setTitle("删除字体")
                .setMessage("确定要删除「" + item.displayName + "」吗？删除后需重新下载。")
                .setPositiveButton("删除", (d, w) -> deleteDownloadedFont(item, container))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 删除已下载的在线字体：删除本地文件 + 移除已下载记录 + 必要时回退当前字体
     */
    private void deleteDownloadedFont(FontCardItem item, LinearLayout container) {
        // 1. 删除内部存储中的字体文件
        java.io.File f = new java.io.File(activity.getFilesDir(), "fonts/" + item.cssName + ".ttf");
        if (f.exists()) f.delete();
        // 2. 从已下载集合移除
        activity.downloadedFonts.remove(item.cssName);
        // 3. 若当前正在使用该字体，回退到默认字体
        if (activity.currentFontFamily.equals(item.cssName)) {
            activity.currentFontFamily = activity.FONT_DEFAULT_CSS;
            activity.applyFontFamilyToWebView();
        }
        // 4. 持久化并刷新列表
        activity.saveReadingPreferences();
        rebuildFontList(container);
        applyCurrentFontToSwitchButton();
        Hint.show(activity, "已删除「" + item.displayName + "」");
    }

    /**
     * 处理字体卡片点击：已下载/内置直接切换；未下载在线字体触发下载并显示进度
     */
    private void onFontCardClick(FontCardItem item, View card, LinearLayout container) {
        boolean downloaded = !item.isBackend || activity.downloadedFonts.contains(item.cssName);
        if (item.isBackend && !downloaded) {
            if (activity.downloadingFonts.contains(item.cssName)) return; // 已在下载中
            activity.downloadingFonts.add(item.cssName);

            // 切换到下载进度 UI：卡片背景开始按宽度填充，底部出现百分比小字
            TextView tvProgress = card.findViewWithTag("font_progress_text");
            if (card.getBackground() instanceof FontCardDrawable) {
                ((FontCardDrawable) card.getBackground()).setProgress(0);
            }
            if (tvProgress != null) {
                tvProgress.setVisibility(View.VISIBLE);
                tvProgress.setText("0%");
            }

            downloadFontFromUrl(item.backendFont.getId(), item.cssName, item.displayName,
                    card, container);
            return;
        }

        activity.currentFontFamily = item.cssName;
        activity.currentFontDisplay = item.displayName;
        activity.applyFontFamilyToWebView();
        activity.saveReadingPreferences();
        rebuildFontList(container);
        applyCurrentFontToSwitchButton();
    }

    /**
     * 计算当前字体对应的 Typeface（用于「切换字体」按钮文字预览）
     * 默认字体 / 系统字体 → 系统默认样式；在线字体 → 已下载到本地的字体文件
     */
    private android.graphics.Typeface currentFontTypeface() {
        // 默认字体 / 系统字体 用系统默认样式；在线字体用下载到本地的字体文件预览
        if (!activity.FONT_SYSTEM_CSS.equals(activity.currentFontFamily) && !activity.FONT_DEFAULT_CSS.equals(activity.currentFontFamily)) {
            if (activity.downloadedFonts.contains(activity.currentFontFamily)) {
                java.io.File f = new java.io.File(activity.getFilesDir(), "fonts/" + activity.currentFontFamily + ".ttf");
                if (f.exists()) {
                    try {
                        return android.graphics.Typeface.createFromFile(f);
                    } catch (Exception ignored) {
                        // 回退到默认
                    }
                }
            }
        }
        return null;
    }

    /**
     * 计算当前字体的展示名称（用于「切换字体」按钮文字）
     * 默认/系统字体用固定文案；在线字体从后端字体列表中取 displayName
     */
    private String currentFontDisplayName() {
        if (activity.FONT_SYSTEM_CSS.equals(activity.currentFontFamily)) return "系统字体";
        if (activity.FONT_DEFAULT_CSS.equals(activity.currentFontFamily)) return "默认字体";
        if (activity.currentFontDisplay != null && !activity.currentFontDisplay.isEmpty()
                && !"默认字体".equals(activity.currentFontDisplay) && !"系统字体".equals(activity.currentFontDisplay)) {
            return activity.currentFontDisplay;
        }
        for (com.example.myapplication.bean.FontItem font : activity.backendFonts) {
            if (font.getCssName().equals(activity.currentFontFamily)) {
                return font.getName();
            }
        }
        return activity.currentFontFamily;
    }

    /**
     * 更新「切换字体」按钮：文字显示当前字体名 + 字体跟随当前字体样式
     */
    void updateSwitchFontButton(TextView tv) {
        if (tv == null) return;
        tv.setText(currentFontDisplayName() + " >");
        tv.setTypeface(currentFontTypeface());
    }

    /**
     * 让「切换字体 >」按钮的文字与字体跟随当前选中的字体
     * 仅在阅读设置弹窗显示时生效
     */
    private void applyCurrentFontToSwitchButton() {
        if (activity.settingsPopupWindow == null || !activity.settingsPopupWindow.isShowing()) return;
        TextView tv = activity.settingsPopupWindow.getContentView().findViewById(R.id.tv_switch_font);
        updateSwitchFontButton(tv);
    }

    /**
     * 构建 3 列网格中的单个字体卡片
     */
    @SuppressLint("SetTextI18n")
    private View buildFontCard(FontCardItem item, float density, boolean downloaded) {
        boolean isCurrent = item.cssName.equals(activity.currentFontFamily);
        boolean isDownloading = item.isBackend && !downloaded && activity.downloadingFonts.contains(item.cssName);

        // 已下载的在线字体：用字体文件本身预览字体名
        android.graphics.Typeface preview = null;
        if (item.isBackend && downloaded) {
            java.io.File f = new java.io.File(activity.getFilesDir(), "fonts/" + item.cssName + ".ttf");
            if (f.exists()) {
                try {
                    preview = android.graphics.Typeface.createFromFile(f);
                } catch (Exception ignored) {
                    preview = null;
                }
            }
        }

        // 卡片配色全部由「当前阅读调色板」派生，换阅读背景/夜间后自动跟随，不写死日/夜两套值。
        // 分层靠明度：卡片底比弹窗底浅一档（米白压牛皮纸），因此不再画描边——
        // 浅底压深底已经分得开，再加描边就成了一圈白框。
        boolean isDark = ReadThemeController.isChromeDark();
        int chrome  = ReadThemeController.getChromeBgColor();            // 字体弹窗主底
        int textPri = ReadThemeController.getTextPrimaryColor();         // 卡片主文字
        int textSec = ReadThemeController.getTextSecondaryColor();       // 未下载的下载箭头（灰）
        int accent  = ReadThemeController.getAccentColor();              // 无彩底时的回落色
        int selInk  = selectedInkColor(chrome, accent, isDark);          // 选中字色 + 同色小勾
        int cardTop = ReadThemeController.mixColors(chrome, 0xFFFFFF, isDark ? 0.13f : 0.42f);
        int cardBot = ReadThemeController.mixColors(chrome, 0xFFFFFF, isDark ? 0.07f : 0.28f);

        // 卡片容器
        android.widget.FrameLayout card = new android.widget.FrameLayout(activity);
        FontCardDrawable bg = new FontCardDrawable(density);
        bg.setColors(cardTop, cardBot);
        bg.setFillColors(ReadThemeController.mixColors(cardTop, selInk, 0.40f),
                ReadThemeController.mixColors(cardBot, selInk, 0.30f));
        bg.setArrowColor(textSec);
        bg.setTickColor(selInk);
        bg.setRadius(12 * density);
        bg.setShowArrow(!downloaded);        // 未下载：右侧灰色下载箭头
        bg.setShowTick(isCurrent);           // 选中：右上角与字体名同色的小勾
        if (isDownloading) bg.setProgress(0);
        card.setBackground(bg);
        // 字体卡片是「可选项 + 选中态」的功能性组件：底色必须表达自身状态，
        // 不能被浮窗重着色（否则白色底色会被映射成阅读器派生底色，切换字体重建卡片时
        // 整片卡片颜色跳变，且选中/未选中的区分消失；深色背景下卡片文字还会被改成白字而不可见）。
        card.setTag(R.id.tag_keep_own_color, true);
        card.setClickable(true);
        card.setFocusable(true);
        card.setForeground(getRippleOrNull());

        // 字体名（居中；未下载时给右侧下载箭头让位）
        TextView tvName = new TextView(activity);
        tvName.setTag("font_name");
        tvName.setText(item.displayName);
        // 卡片只有约 98dp 宽，五字以上的字体名按 16sp 会撞到下载箭头，先降字号保住全名
        tvName.setTextSize(item.displayName.length() >= 5 ? 13 : 16);
        tvName.setTextColor(isCurrent ? selInk : textPri);
        tvName.setGravity(android.view.Gravity.CENTER);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (preview != null) tvName.setTypeface(preview);
        android.widget.FrameLayout.LayoutParams nameLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        tvName.setLayoutParams(nameLp);
        if (!downloaded) tvName.setPadding(0, 0, (int) (18 * density), 0);
        card.addView(tvName);

        // 下载进度：卡片背景按完成宽度填充（见 FontCardDrawable），这里只留一行百分比小字
        TextView tvProgress = new TextView(activity);
        tvProgress.setTag("font_progress_text");
        tvProgress.setText("0%");
        tvProgress.setTextSize(10);
        tvProgress.setTextColor(selInk);
        tvProgress.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
        android.widget.FrameLayout.LayoutParams pctLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        pctLp.bottomMargin = (int) (7 * density);
        tvProgress.setLayoutParams(pctLp);
        card.addView(tvProgress);

        // 立体感：悬浮阴影（近淡远深）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            card.setOutlineSpotShadowColor(ReadThemeController.mixColors(chrome, 0x000000, 0.20f));
        }
        card.setElevation(isCurrent ? 6f * density : 3f * density);

        return card;
    }

    /**
     * 获取 Android 波纹前景（如可用），否则返回 null
     */
    private android.graphics.drawable.Drawable getRippleOrNull() {
        android.util.TypedValue typedVal = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedVal, true)) {
            try {
                return activity.getResources().getDrawable(typedVal.resourceId, activity.getTheme());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 夜间选中态墨色：墨玉鎏金的「鎏金」 */
    private static final int NIGHT_SELECTED_INK = Color.rgb(216, 176, 104);

    /**
     * 选中态字色：日间取弹窗底色的色相并加深加饱和，保证「比抽屉底更深一档」且与纸色同系
     * （牛皮纸 → 赭橙）。夜间是墨玉无彩底，从底色推色相推不出东西，直接用固定鎏金。
     */
    private static int selectedInkColor(int chrome, int accent, boolean isDark) {
        if (isDark) return NIGHT_SELECTED_INK;
        float[] hsv = new float[3];
        Color.colorToHSV(chrome, hsv);
        if (hsv[1] < 0.08f) return accent;
        hsv[1] = Math.max(0.62f, Math.min(1f, hsv[1] * 2.3f));
        hsv[2] = Math.min(hsv[2], 0.80f);
        return Color.HSVToColor(hsv);
    }

    /**
     * 字体卡片背景：米白渐变底 + 未下载箭头 / 选中勾 / 按完成宽度填充的下载进度 / 完成提亮。
     *
     * 不用 GradientDrawable 是它画不了「按百分比宽度的圆角填充」；箭头与小勾都用直线段现画，
     * 因此也不引入矢量资源（矢量 path 少一个浮点参数会编译安装全过、运行时才炸）。
     */
    private static class FontCardDrawable extends Drawable {
        private final android.graphics.Paint base =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        // 提亮必须用自己的 Paint：base 一旦 setColor 过半透明白，它的 alpha 就留下来了，
        // 而硬件渲染会把 shader 的 alpha 乘上 Paint 的 alpha（SkiaShader::getSkShader(alpha)），
        // 之后每一帧渐变都会按 alpha≈1 画出来 = 卡片底直接消失。
        private final android.graphics.Paint flash =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint glyph =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final float density;

        private int topColor, botColor, fillTopColor, fillBotColor, arrowColor, tickColor;
        private float radius;
        private int progress = -1;      // -1：不画进度填充
        private float brighten;         // 0..1：完成瞬间向白提亮
        private boolean showArrow, showTick;

        FontCardDrawable(float density) {
            this.density = density;
            glyph.setStyle(android.graphics.Paint.Style.STROKE);
            glyph.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            glyph.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        void setColors(int top, int bot) { topColor = top; botColor = bot; }
        void setFillColors(int top, int bot) { fillTopColor = top; fillBotColor = bot; }
        void setArrowColor(int c) { arrowColor = c; }
        void setTickColor(int c) { tickColor = c; }
        void setRadius(float r) { radius = r; }
        void setProgress(int p) { progress = p; invalidateSelf(); }
        void setShowArrow(boolean b) { showArrow = b; }
        void setShowTick(boolean b) { showTick = b; }
        void setBrighten(float b) { brighten = b; invalidateSelf(); }

        @Override
        public void draw(@NonNull android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float l = b.left, t = b.top, r = b.right, bot = b.bottom;
            if (r - l <= 0f || bot - t <= 0f) return;

            // 渐变每次现建，不缓存：卡片可能在布局完成前就被画一帧（bounds 还是 0），
            // 缓存会把那份空渐变一直用下去。
            base.setShader(new android.graphics.LinearGradient(0, t, 0, bot, topColor, botColor,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(l, t, r, bot, radius, radius, base);

            if (progress > 0) {
                float inset = 3 * density;
                float w = (r - l - inset * 2) * progress / 100f;
                base.setShader(new android.graphics.LinearGradient(0, t + inset, 0, bot - inset,
                        fillTopColor, fillBotColor, android.graphics.Shader.TileMode.CLAMP));
                float rr = Math.max(1f, Math.min(radius - inset, w / 2f));
                c.drawRoundRect(l + inset, t + inset, l + inset + w, bot - inset, rr, rr, base);
            }
            base.setShader(null);

            if (brighten > 0f) {
                flash.setColor(Color.argb((int) (42 * brighten), 255, 255, 255));
                c.drawRoundRect(l, t, r, bot, radius, radius, flash);
            }
            if (showArrow) drawArrow(c, r, (t + bot) / 2f, arrowColor);
            if (showTick) drawTick(c, r - 8 * density, t + 8 * density, tickColor);
        }

        /** 下载箭头：竖杆 + 箭头 + 托盘，约 13dp 高，贴在卡片右侧（未下载时才有） */
        private void drawArrow(android.graphics.Canvas c, float right, float cy, int color) {
            float cx = right - 16 * density;
            glyph.setColor(color);
            glyph.setStrokeWidth(1.7f * density);
            c.drawLine(cx, cy - 6.5f * density, cx, cy + 2 * density, glyph);
            path.reset();
            path.moveTo(cx - 4.2f * density, cy - 1.6f * density);
            path.lineTo(cx, cy + 2.6f * density);
            path.lineTo(cx + 4.2f * density, cy - 1.6f * density);
            c.drawPath(path, glyph);
            c.drawLine(cx - 5.6f * density, cy + 6.4f * density,
                    cx + 5.6f * density, cy + 6.4f * density, glyph);
        }

        /** 右上角小勾（仅选中态），与字体名同色 */
        private void drawTick(android.graphics.Canvas c, float right, float top, int color) {
            glyph.setColor(color);
            glyph.setStrokeWidth(1.8f * density);
            path.reset();
            path.moveTo(right - 8.5f * density, top + 2 * density);
            path.lineTo(right - 4.5f * density, top + 6 * density);
            path.lineTo(right, top);
            c.drawPath(path, glyph);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * 刷新字体选择弹窗 — 重建整个列表以保证状态一致
     */
    private void refreshFontSelection(LinearLayout container) {
        rebuildFontList(container);
    }

    /**
     * 构建/重建字体列表：按图中 3 列网格卡片样式
     */
    private void rebuildFontList(LinearLayout container) {
        container.removeAllViews();
        float density = activity.getResources().getDisplayMetrics().density;

        // 收集全部卡片数据：系统字体 + 默认字体 + 在线字体
        java.util.List<FontCardItem> items = new java.util.ArrayList<>();
        items.add(new FontCardItem("系统字体", activity.FONT_SYSTEM_CSS, false, null));
        items.add(new FontCardItem("默认字体", activity.FONT_DEFAULT_CSS, false, null));
        for (com.example.myapplication.bean.FontItem font : activity.backendFonts) {
            items.add(new FontCardItem(font.getName(), font.getCssName(), true, font));
        }

        if (items.size() == 2 && activity.backendFonts.isEmpty()) {
            // 在线字体尚未加载：先显示 loading，同时拉取列表
            TextView tvLoading = new TextView(activity);
            tvLoading.setText("加载中...");
            tvLoading.setTextSize(14);
            tvLoading.setTextColor(Color.parseColor("#8E8E93"));
            tvLoading.setPadding((int)(12 * density), (int)(16 * density), 0, (int)(16 * density));
            tvLoading.setTag("tv_loading");
            container.addView(tvLoading);
            fetchFontsFromBackend(container);
            return;
        }

        final int cols = 3;
        int margin = (int) (6 * density);
        int cardHeight = (int) (46 * density);

        LinearLayout currentRow = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                currentRow = new LinearLayout(activity);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                container.addView(currentRow);
            }
            FontCardItem item = items.get(i);
            // 内置字体（系统/默认）无需下载，永远算「已具备」——否则卡片会挂上下载箭头
            View card = buildFontCard(item, density,
                    !item.isBackend || activity.downloadedFonts.contains(item.cssName));
            // 普通 tag 存 cssName：下载完成后要按它找回这张卡片做提亮回落
            card.setTag(item.cssName);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cardHeight, 1);
            lp.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(lp);
            currentRow.addView(card);

            // 点击切换 / 下载
            card.setOnClickListener(v -> onFontCardClick(item, card, container));

            // 已下载的在线字体可长按删除
            if (item.isBackend && activity.downloadedFonts.contains(item.cssName)) {
                card.setOnLongClickListener(v -> {
                    showDeleteFontDialog(item, container);
                    return true;
                });
            }
        }

        // 补齐最后一行的空白占位，保持网格对齐
        int remainder = items.size() % cols;
        if (currentRow != null && remainder != 0) {
            for (int i = 0; i < cols - remainder; i++) {
                View spacer = new View(activity);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cardHeight, 1);
                lp.setMargins(margin, margin, margin, margin);
                spacer.setLayoutParams(lp);
                currentRow.addView(spacer);
            }
        }
    }

    /**
     * 从 URL 下载字体文件到内部存储（在卡片内显示下载进度）
     */
    private void downloadFontFromUrl(long fontId, String cssName, String displayName,
                                     View card, LinearLayout container) {
        // 通过字体 id 走后端流式下载接口，避免 URL 中出现中文文件名导致下载失败（400/500）
        final String fullUrl = RetrofitClient.getFullImageUrl("/api/fonts/file/" + fontId);
        new Thread(() -> {
            try {
                java.io.File fontsDir = new java.io.File(activity.getFilesDir(), "fonts");
                if (!fontsDir.exists()) fontsDir.mkdirs();
                java.io.File outFile = new java.io.File(fontsDir, cssName + ".ttf");

                // 获取 token 并添加到请求头（修复401错误）
                SharedPreferences sp = activity.getSharedPreferences("user_info", activity.MODE_PRIVATE);
                String token = sp.getString("token", "");
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(fullUrl)
                        .header("Authorization", "Bearer " + token)
                        .build();
                okhttp3.Response response = client.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    throw new java.io.IOException("下载失败: " + response.code());
                }
                long total = response.body().contentLength();
                java.io.InputStream is = response.body().byteStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);

                byte[] buf = new byte[8192];
                int len;
                long downloadedBytes = 0;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    downloadedBytes += len;
                    if (total > 0 && card != null) {
                        final int pct = (int) (downloadedBytes * 100 / total);
                        activity.runOnUiThread(() -> updateFontCardProgress(card, pct));
                    }
                }
                fos.close();
                is.close();

                activity.runOnUiThread(() -> {
                    activity.downloadingFonts.remove(cssName);
                    activity.downloadedFonts.add(cssName);
                    Hint.show(activity, displayName + " 下载完成");
                    // 自动切换到新下载的字体
                    activity.currentFontFamily = cssName;
                    activity.currentFontDisplay = displayName;
                    activity.applyFontFamilyToWebView();
                    activity.saveReadingPreferences();
                    refreshFontSelection(container);
                    flashCardDownloaded(container, cssName);
                    applyCurrentFontToSwitchButton();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    activity.downloadingFonts.remove(cssName);
                    Hint.show(activity, "下载失败：" + e.getMessage());
                    refreshFontSelection(container);
                });
            }
        }).start();
    }

    /**
     * 更新卡片下载进度 UI：填充宽度画在卡片背景里，这里同步百分比小字
     */
    private void updateFontCardProgress(View card, int pct) {
        if (card.getBackground() instanceof FontCardDrawable) {
            ((FontCardDrawable) card.getBackground()).setProgress(pct);
        }
        TextView tvProgress = card.findViewWithTag("font_progress_text");
        if (tvProgress != null) tvProgress.setText(pct + "%");
    }

    /**
     * 下载完成：整卡轻微提亮后自行回落。
     * 提亮不属于任何常驻状态，所以勾与字色都不动——它们只表达「当前字体」。
     */
    private void flashCardDownloaded(LinearLayout container, String cssName) {
        View card = container.findViewWithTag(cssName);
        if (!(card instanceof FrameLayout) || !(card.getBackground() instanceof FontCardDrawable)) return;
        FontCardDrawable bg = (FontCardDrawable) card.getBackground();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f, 1f, 0f);
        va.setDuration(1250);
        va.setInterpolator(new android.view.animation.LinearInterpolator());
        va.addUpdateListener(a -> bg.setBrighten((float) a.getAnimatedValue()));
        va.start();
    }
}
