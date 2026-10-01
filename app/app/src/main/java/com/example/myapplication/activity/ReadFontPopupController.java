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
            else if (activity.fontsPopupWindow != null) activity.fontsPopupWindow.dismiss();
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

            // 切换到下载进度 UI
            TextView tvName = card.findViewWithTag("font_name");
            TextView tvProgress = card.findViewWithTag("font_progress_text");
            android.widget.ProgressBar pb = card.findViewWithTag("font_progress_bar");
            if (tvName != null) tvName.setAlpha(0.6f);
            if (tvProgress != null) {
                tvProgress.setVisibility(View.VISIBLE);
                tvProgress.setText("0%");
            }
            if (pb != null) {
                pb.setVisibility(View.VISIBLE);
                pb.setProgress(0);
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
    private View buildFontCard(FontCardItem item, float density, int cardHeight, boolean downloaded) {
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

        // 卡片配色跟随「当前阅读调色板」，而非写死日/夜两套值：
        // 这样更换阅读背景/夜间后，卡片与字体弹窗背景同色系，永不脱节、始终协调。
        // 选中态用 sAccent（默认墨韵主题即朱印红）表达；描边进一步弱化（1dp + 向底色混 50%）以显高级。
        boolean isDark = ReadThemeController.isChromeDark();
        int chrome  = ReadThemeController.getChromeBgColor();            // 字体弹窗主底，卡片与之同色系
        int textPri = ReadThemeController.getTextPrimaryColor();              // 卡片主文字（随主题取深/浅）
        int accent  = ReadThemeController.getAccentColor();             // 当前主题强调色（朱印红系）
        // 普通卡片：在弹窗底色上做「顶受光、底背光」的细微明度偏移，浮出层次
        int cardTop = ReadThemeController.mixColors(chrome, 0xFFFFFF, isDark ? 0.05f : 0.07f);
        int cardMid = chrome;
        int cardBot = ReadThemeController.mixColors(chrome, 0x000000, isDark ? 0.06f : 0.04f);
        int strokeN = ReadThemeController.mixColors(chrome, 0x000000, isDark ? 0.18f : 0.12f);
        // 选中卡片：底色向强调色轻微晕染（克制、不突兀）；描边用弱化后的强调色（宽度仅 1dp）
        int washTop = ReadThemeController.mixColors(chrome, accent, isDark ? 0.14f : 0.10f);
        int washMid = ReadThemeController.mixColors(chrome, accent, isDark ? 0.10f : 0.07f);
        int washBot = ReadThemeController.mixColors(chrome, accent, isDark ? 0.07f : 0.05f);
        int selStroke = ReadThemeController.mixColors(accent, chrome, 0.50f);

        // 卡片容器
        android.widget.FrameLayout card = new android.widget.FrameLayout(activity);
        int radius = (int) (12 * density);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        if (isCurrent) {
            bg.setColors(new int[]{ washTop, washMid, washBot });
            bg.setStroke((int) (1 * density), selStroke);
        } else {
            bg.setColors(new int[]{ cardTop, cardMid, cardBot });
            bg.setStroke((int) (1 * density), strokeN);
        }
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        // 字体卡片是「可选项 + 选中态」的功能性组件：底色必须表达自身状态，
        // 不能被浮窗重着色（否则白色底色会被映射成阅读器派生底色，切换字体重建卡片时
        // 整片卡片颜色跳变，且选中/未选中的区分消失；深色背景下卡片文字还会被改成白字而不可见）。
        card.setTag(R.id.tag_keep_own_color, true);
        card.setClickable(true);
        card.setFocusable(true);
        card.setForeground(getRippleOrNull());

        // 字体名（居中）
        TextView tvName = new TextView(activity);
        tvName.setTag("font_name");
        tvName.setText(item.displayName);
        tvName.setTextSize(16);
        tvName.setTextColor(isCurrent ? accent : textPri);
        tvName.setGravity(android.view.Gravity.CENTER);
        if (preview != null) tvName.setTypeface(preview);
        if (isDownloading) tvName.setAlpha(0.6f);
        android.widget.FrameLayout.LayoutParams nameLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        tvName.setLayoutParams(nameLp);
        card.addView(tvName);

        // 下载进度：百分比文字 + 水平进度条（未下载时默认隐藏，点击后显示）
        TextView tvProgress = new TextView(activity);
        tvProgress.setTag("font_progress_text");
        tvProgress.setText("0%");
        tvProgress.setTextSize(10);
        tvProgress.setTextColor(accent);
        tvProgress.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
        android.widget.FrameLayout.LayoutParams pctLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        pctLp.bottomMargin = (int) (10 * density);
        tvProgress.setLayoutParams(pctLp);
        card.addView(tvProgress);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setTag("font_progress_bar");
        pb.setIndeterminate(false);
        pb.setProgressDrawable(activity.getResources().getDrawable(android.R.drawable.progress_horizontal, activity.getTheme()));
        pb.getProgressDrawable().mutate().setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN);
        pb.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
        pb.setProgress(0);
        pb.setMax(100);
        int pbWidth = (int) (cardHeight * 1.2f);
        android.widget.FrameLayout.LayoutParams pbLp = new android.widget.FrameLayout.LayoutParams(
                pbWidth, (int) (3 * density),
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        pbLp.bottomMargin = (int) (5 * density);
        pb.setLayoutParams(pbLp);
        card.addView(pb);

        // 立体感：悬浮阴影（近淡远深；选中态用强调色投影，更突出「抬起」）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            card.setOutlineSpotShadowColor(isCurrent ? accent : ReadThemeController.mixColors(chrome, 0x000000, 0.20f));
        }
        card.setElevation(isCurrent ? 8f * density : 3f * density);

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
            View card = buildFontCard(item, density, cardHeight,
                    item.isBackend && activity.downloadedFonts.contains(item.cssName));
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
     * 更新卡片下载进度 UI
     */
    private void updateFontCardProgress(View card, int pct) {
        TextView tvProgress = card.findViewWithTag("font_progress_text");
        android.widget.ProgressBar pb = card.findViewWithTag("font_progress_bar");
        if (tvProgress != null) tvProgress.setText(pct + "%");
        if (pb != null) pb.setProgress(pct);
    }
}
