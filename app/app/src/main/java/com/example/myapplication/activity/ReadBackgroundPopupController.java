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
 * 阅读器「更多背景」弹窗控制器（纯色 + 纹理网格）。
 *
 * 2026-10-02 从 ReadActivity 拆出（结构优化）：纯代码搬移，行为不变。
 * 与宿主同包，直接读写 activity 的包私有成员。
 */
class ReadBackgroundPopupController {

    private final ReadActivity activity;

    ReadBackgroundPopupController(ReadActivity activity) {
        this.activity = activity;
    }

    /** 打开背景选择弹窗（原 ReadActivity.showBgColorsDialog） */
    void show() {
        showBgColorsDialog();
    }

    /**
     * 显示更多背景弹窗（纯色 + 纹理）
     */
    @SuppressLint({"InflateParams", "SetTextI18n"})
    private void showBgColorsDialog() {
        View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_bg_colors, null);
        LinearLayout solidContainer = popupView.findViewById(R.id.container_solid_colors);
        LinearLayout texContainer = popupView.findViewById(R.id.container_textures);

        float density = activity.getResources().getDisplayMetrics().density;
        int swatchSize = (int) (44 * density);
        int ringSize = swatchSize;
        int frameSize = (int) (52 * density);
        int marginEnd = (int) (8 * density);
        float ringCorner = 8 * density;

        // ===== 构建纯色网格 =====
        int colsPerRow = 5;
        LinearLayout currentRow = null;
        for (int i = 0; i < activity.BG_COLORS.length; i++) {
            if (i % colsPerRow == 0) {
                currentRow = new LinearLayout(activity);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = (int) (8 * density);
                solidContainer.addView(currentRow, rowParams);
            }
            FrameLayout frame = new FrameLayout(activity);
            frame.setLayoutParams(new LinearLayout.LayoutParams(frameSize, frameSize));
            ((LinearLayout.LayoutParams) frame.getLayoutParams()).setMargins(0, 0, marginEnd, 0);

            // 色块（圆角长方形）
            View swatch = new View(activity);
            FrameLayout.LayoutParams swatchLp = new FrameLayout.LayoutParams(swatchSize, swatchSize);
            swatchLp.gravity = android.view.Gravity.CENTER;
            swatch.setLayoutParams(swatchLp);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.RECTANGLE);
            circle.setCornerRadius(8 * density);
            circle.setColor(Color.parseColor(activity.BG_COLORS[i]));
            if (activity.BG_COLORS[i].equals("#FFFFFF")) circle.setStroke(2, Color.parseColor("#CCCCCC"));
            swatch.setBackground(circle);
            // 色块必须显示真实背景色，不能被「跟随背景色」重着色
            swatch.setTag(R.id.tag_keep_own_color, true);

            // 选中环
            View ring = new View(activity);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
            ringLp.gravity = android.view.Gravity.CENTER;
            ring.setLayoutParams(ringLp);
            ring.setBackgroundResource(R.drawable.bg_color_ring);
            try { GradientDrawable rd = (GradientDrawable) ring.getBackground(); rd.mutate(); rd.setCornerRadius(ringCorner); } catch (Exception ignore) {}
            ring.setVisibility((i == activity.currentBgColor || (i == 3 && activity.isNightMode)) ? View.VISIBLE : View.GONE);

            frame.addView(swatch);
            frame.addView(ring);

            // 点击选择
            final int idx = i;
            swatch.setOnClickListener(v -> {
                if (idx == 3) {
                    // 黑色背景 ≡ 夜间模式（同主面板处理器，统一状态不写 currentBgColor=3）
                    if (!activity.isNightMode) activity.animateNightModeTo(true);
                } else {
                    activity.currentBgColor = idx;
                    // 关键修复：选中自定义背景即退出夜间模式（详见主面板纯色处理器说明）
                    activity.isNightMode = false;
                    activity.applyBackgroundColorToWebView(idx);
                    activity.applyChromeTheme();   // 导航栏 / 浮窗底色跟随新背景
                    activity.saveReadingPreferences();
                }
                refreshPopupSelection(solidContainer, texContainer);
            });
            currentRow.addView(frame);
        }

        // ===== 构建纹理网格 =====
        LinearLayout texRow = null;
        for (int i = 0; i < activity.BG_TEXTURE_FILES.length; i++) {
            if (i % colsPerRow == 0) {
                texRow = new LinearLayout(activity);
                texRow.setOrientation(LinearLayout.HORIZONTAL);
                texRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = (int) (8 * density);
                texContainer.addView(texRow, rowParams);
            }
            FrameLayout frame = new FrameLayout(activity);
            frame.setLayoutParams(new LinearLayout.LayoutParams(frameSize, frameSize));
            ((LinearLayout.LayoutParams) frame.getLayoutParams()).setMargins(0, 0, marginEnd, 0);

            // 纹理预览图（从assets加载bitmap，裁为圆形）
            ImageView swatch = new ImageView(activity);
            FrameLayout.LayoutParams swatchLp = new FrameLayout.LayoutParams(swatchSize, swatchSize);
            swatchLp.gravity = android.view.Gravity.CENTER;
            swatch.setLayoutParams(swatchLp);
            swatch.setScaleType(ImageView.ScaleType.CENTER_CROP);
            swatch.setContentDescription(activity.BG_TEXTURE_LABELS[i]);
            try {
                InputStream is = activity.getAssets().open("backgrounds/" + activity.BG_TEXTURE_FILES[i]);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, swatchSize, swatchSize, true);
                RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(activity.getResources(), scaled);
                drawable.setCornerRadius(8 * density);
                swatch.setImageDrawable(drawable);
            } catch (Exception e) {
                // 加载失败时用预览色兜底（圆角长方形）
                GradientDrawable fallback = new GradientDrawable();
                fallback.setShape(GradientDrawable.RECTANGLE);
                fallback.setCornerRadius(8 * density);
                fallback.setColor(activity.BG_TEXTURE_PREVIEW_COLORS[i]);
                swatch.setBackground(fallback);
            }

            // 选中环
            View ring = new View(activity);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
            ringLp.gravity = android.view.Gravity.CENTER;
            ring.setLayoutParams(ringLp);
            ring.setBackgroundResource(R.drawable.bg_color_ring);
            try { GradientDrawable rd = (GradientDrawable) ring.getBackground(); rd.mutate(); rd.setCornerRadius(ringCorner); } catch (Exception ignore) {}
            int texModeIdx = 10 + i;
            ring.setVisibility(texModeIdx == activity.currentBgColor ? View.VISIBLE : View.GONE);

            frame.addView(swatch);
            frame.addView(ring);

            // 点击选择纹理
            final int texIdx = texModeIdx;
            swatch.setOnClickListener(v -> {
                activity.currentBgColor = texIdx;
                // 关键修复：选中纹理背景即退出夜间模式（详见主面板纯色处理器说明）
                activity.isNightMode = false;
                activity.applyBackgroundColorToWebView(texIdx);
                activity.applyChromeTheme();   // 导航栏 / 浮窗底色跟随新背景（纹理取预览底色）
                activity.saveReadingPreferences();
                refreshPopupSelection(solidContainer, texContainer);
            });
            texRow.addView(frame);
        }

        // 关闭按钮（色块点击即生效，无需"确定"按钮）
        popupView.findViewById(R.id.iv_bg_popup_close).setOnClickListener(v -> {
            SwipeDismissLayout h = SwipeDismissLayout.findHost(v);
            if (h != null) h.dismissAnimated();
            else if (activity.bgColorsPopupWindow != null) activity.bgColorsPopupWindow.dismiss();
        });

        // 背景色选择浮窗外壳跟随主题（色块本身为实际背景色，不在映射表内故不会被改）
        activity.themeViewTree(popupView);

        // 弹窗高度固定为屏幕一半，内容过多时由内部 ScrollView 滚动
        android.util.DisplayMetrics bgDm = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(bgDm);
        int bgPopupHeight = bgDm.heightPixels / 2;

        // 以全屏透明窗口 + 跟手下滑容器承载面板（面板下移时不会被半屏窗口边界裁掉）
        activity.bgColorsPopupWindow = activity.showSwipeDismissPopup(popupView, bgPopupHeight, "bg");
        // 联动：弹窗滑出时设置面板收回；弹窗收起（关闭按钮/跟手下滑任一路径 dismiss）时面板重新滑出
        activity.retractSettingsPanelForSub();
        activity.suspendAutoPage();
        activity.mainHandler.removeCallbacks(activity.hideNavRunnable);
        activity.bgColorsPopupWindow.setOnDismissListener(() -> {
            activity.resetAutoHideTimer();
            activity.resumeAutoPageIfSuspended();
            activity.restoreSettingsPanelFromSub();
        });
    }

    /**
     * 刷新弹窗中所有色块的选中状态
     */
    private void refreshPopupSelection(LinearLayout solidContainer, LinearLayout texContainer) {
        // 纯色
        int solidIdx = 0;
        for (int r = 0; r < solidContainer.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) solidContainer.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                FrameLayout frame = (FrameLayout) row.getChildAt(c);
                View ring = frame.getChildAt(1);
                // 黑色背景(index 3) ≡ 夜间模式：夜间态下黑块显示选中
                ring.setVisibility((solidIdx == activity.currentBgColor || (solidIdx == 3 && activity.isNightMode)) ? View.VISIBLE : View.GONE);
                solidIdx++;
            }
        }
        // 纹理（索引从10开始）
        int texIdx = 0;
        for (int r = 0; r < texContainer.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) texContainer.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                FrameLayout frame = (FrameLayout) row.getChildAt(c);
                View ring = frame.getChildAt(1);
                ring.setVisibility((10 + texIdx) == activity.currentBgColor ? View.VISIBLE : View.GONE);
                texIdx++;
            }
        }
    }
}
