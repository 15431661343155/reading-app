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
 * 阅读器「更多设置」弹窗控制器（页眉页脚/音量键/滑动翻页/亮屏/自动翻页速度）。
 *
 * 2026-10-02 从 ReadActivity 拆出（结构优化）：纯代码搬移，行为不变。
 * 与宿主同包，直接读写 activity 的包私有成员。
 */
class ReadMoreSettingsPopupController {

    private final ReadActivity activity;

    ReadMoreSettingsPopupController(ReadActivity activity) {
        this.activity = activity;
    }

    /** 打开更多设置弹窗（原 ReadActivity.showMoreSettingsDialog） */
    void show() {
        showMoreSettingsDialog();
    }

    /**
     * 显示更多设置弹窗（页眉页脚等）
     */
    @SuppressLint("InflateParams")
    private void showMoreSettingsDialog() {
        View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_more_settings, null);

        SwitchCompat switchHeaderFooter = popupView.findViewById(R.id.switch_header_footer);
        switchHeaderFooter.setChecked(activity.showHeaderFooter);

        LinearLayout layoutHeaderFooterSize = popupView.findViewById(R.id.layout_header_footer_size);
        layoutHeaderFooterSize.setAlpha(activity.showHeaderFooter ? 1.0f : 0.4f);
        layoutHeaderFooterSize.setEnabled(activity.showHeaderFooter);

        // 电量时间开关（次级设置，跟随页眉页脚）
        LinearLayout layoutBatteryTime = popupView.findViewById(R.id.layout_battery_time);
        SwitchCompat switchBatteryTime = popupView.findViewById(R.id.switch_battery_time);
        switchBatteryTime.setChecked(activity.showBatteryTime);
        layoutBatteryTime.setAlpha(activity.showHeaderFooter ? 1.0f : 0.4f);
        layoutBatteryTime.setEnabled(activity.showHeaderFooter);

        switchHeaderFooter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            activity.showHeaderFooter = isChecked;
            layoutHeaderFooterSize.setAlpha(isChecked ? 1.0f : 0.4f);
            layoutHeaderFooterSize.setEnabled(isChecked);
            layoutBatteryTime.setAlpha(isChecked ? 1.0f : 0.4f);
            layoutBatteryTime.setEnabled(isChecked);
            activity.webView.evaluateJavascript("setShowHeaderFooter(" + isChecked + ")", null);
            if (!isChecked) {
                activity.webView.evaluateJavascript("setShowBatteryTime(false)", null);
                activity.timeUpdateHandler.removeCallbacks(activity.timeUpdateRunnable);
            } else if (activity.showBatteryTime) {
                activity.webView.evaluateJavascript("setShowBatteryTime(true)", null);
                activity.updateBatteryAndTime();
                activity.timeUpdateHandler.removeCallbacks(activity.timeUpdateRunnable);
                activity.timeUpdateHandler.postDelayed(activity.timeUpdateRunnable, 60000);
            }
            activity.saveReadingPreferences();
        });

        switchBatteryTime.setOnCheckedChangeListener((buttonView, isChecked) -> {
            activity.showBatteryTime = isChecked;
            activity.webView.evaluateJavascript("setShowBatteryTime(" + isChecked + ")", null);
            if (isChecked) {
                activity.updateBatteryAndTime();
                activity.timeUpdateHandler.removeCallbacks(activity.timeUpdateRunnable);
                activity.timeUpdateHandler.postDelayed(activity.timeUpdateRunnable, 60000);
            } else {
                activity.timeUpdateHandler.removeCallbacks(activity.timeUpdateRunnable);
            }
            activity.saveReadingPreferences();
        });

        layoutBatteryTime.setOnClickListener(v -> switchBatteryTime.setChecked(!switchBatteryTime.isChecked()));

        // 音量键翻页开关
        SwitchCompat switchVolumeKeyPage = popupView.findViewById(R.id.switch_volume_key_page);
        switchVolumeKeyPage.setChecked(activity.volumeKeyPageTurn);
        switchVolumeKeyPage.setOnCheckedChangeListener((buttonView, isChecked) -> {
            activity.volumeKeyPageTurn = isChecked;
            activity.saveReadingPreferences();
        });

        // 滑动翻页开关
        SwitchCompat switchSwipePage = popupView.findViewById(R.id.switch_swipe_page);
        switchSwipePage.setChecked(activity.swipePageTurn);
        switchSwipePage.setOnCheckedChangeListener((buttonView, isChecked) -> {
            activity.swipePageTurn = isChecked;
            // 同步给前端：关闭后 reader.html 不再启用跟手拖拽
            if (activity.isWebViewReady) activity.webView.evaluateJavascript("setSwipeEnabled(" + activity.swipePageTurn + ")", null);
            activity.saveReadingPreferences();
        });

        // ===== 屏幕亮屏时间（跟随系统 / 1分钟 / 5分钟 / 常亮）=====
        // 复用自动翻页速度同款胶囊选中高亮（ios_blue / 灰底）
        TextView tvScreenSystem = popupView.findViewById(R.id.tv_screen_system);
        TextView tvScreen1Min = popupView.findViewById(R.id.tv_screen_1min);
        TextView tvScreen5Min = popupView.findViewById(R.id.tv_screen_5min);
        TextView tvScreenAlways = popupView.findViewById(R.id.tv_screen_always);
        final TextView[] screenViews = {tvScreenSystem, tvScreen1Min, tvScreen5Min, tvScreenAlways};
        final long[] screenValues = {0L, 60_000L, 300_000L, -1L};
        int screenSel = 0;
        for (int i = 0; i < screenValues.length; i++) {
            if (screenValues[i] == activity.screenOnTimeoutMs) { screenSel = i; break; }
        }
        final int[] screenSelRef = {screenSel};
        activity.updateAutoSpeedUI(screenViews, screenSelRef[0]);
        for (int i = 0; i < screenViews.length; i++) {
            final int idx = i;
            screenViews[i].setOnClickListener(v -> {
                screenSelRef[0] = idx;
                activity.screenOnTimeoutMs = screenValues[idx];
                activity.updateAutoSpeedUI(screenViews, idx);
                activity.applyScreenKeepAlive();
                activity.saveReadingPreferences();
                if (idx == 0) {
                    Hint.show(activity, "亮屏：跟随系统");
                } else if (idx == 3) {
                    Hint.show(activity, "阅读时屏幕常亮");
                } else {
                    Hint.show(activity, "阅读亮屏：" + (screenValues[idx] / 60000) + " 分钟（无操作后熄屏）");
                }
            });
        }

        // ===== 自动翻页速度（慢10s / 中5s / 快3s）=====
        TextView tvAutoSlow = popupView.findViewById(R.id.tv_auto_slow);
        TextView tvAutoNormal = popupView.findViewById(R.id.tv_auto_normal);
        TextView tvAutoFast = popupView.findViewById(R.id.tv_auto_fast);
        final TextView[] speedViews = {tvAutoSlow, tvAutoNormal, tvAutoFast};
        final int[] speedValues = {10000, 5000, 3000};
        // 当前选中的下标
        int selIdx = 1;
        for (int i = 0; i < speedValues.length; i++) {
            if (speedValues[i] == activity.autoPageInterval) { selIdx = i; break; }
        }
        final int[] selRef = {selIdx};
        activity.updateAutoSpeedUI(speedViews, selRef[0]);
        for (int i = 0; i < speedViews.length; i++) {
            final int idx = i;
            speedViews[i].setOnClickListener(v -> {
                selRef[0] = idx;
                activity.autoPageInterval = speedValues[idx];
                activity.updateAutoSpeedUI(speedViews, idx);
                activity.saveReadingPreferences();
                // 若正在自动翻页，立即以新间隔重启
                if (activity.autoPageEnabled) {
                    activity.stopAutoPage();
                    if (!activity.autoPageSuspended) activity.startAutoPage();
                }
                Hint.show(activity, "翻页间隔：" + (activity.autoPageInterval / 1000) + " 秒");
            });
        }

        TextView tvHeaderFooterMinus = popupView.findViewById(R.id.tv_header_footer_minus);
        TextView tvHeaderFooterPlus = popupView.findViewById(R.id.tv_header_footer_plus);
        TextView tvHeaderFooterSizeDisplay = popupView.findViewById(R.id.tv_header_footer_size_display);

        tvHeaderFooterSizeDisplay.setText(String.valueOf((int) activity.headerFooterFontSize));

        tvHeaderFooterMinus.setOnClickListener(v -> {
            if (activity.headerFooterFontSize > 8) { // 最小值改为8
                activity.headerFooterFontSize -= 1;
                tvHeaderFooterSizeDisplay.setText(String.valueOf((int) activity.headerFooterFontSize));
                activity.webView.evaluateJavascript("setHeaderFooterFontSize(" + (int)activity.headerFooterFontSize + ")", null);
                activity.saveReadingPreferences();
            }
        });

        tvHeaderFooterPlus.setOnClickListener(v -> {
            if (activity.headerFooterFontSize < 24) { // 最大值改为24
                activity.headerFooterFontSize += 1;
                tvHeaderFooterSizeDisplay.setText(String.valueOf((int) activity.headerFooterFontSize));
                activity.webView.evaluateJavascript("setHeaderFooterFontSize(" + (int)activity.headerFooterFontSize + ")", null);
                activity.saveReadingPreferences();
            }
        });

        popupView.findViewById(R.id.tv_more_settings_done).setOnClickListener(v -> {
            SwipeDismissLayout h = SwipeDismissLayout.findHost(v);
            if (h != null) h.dismissAnimated();
            else if (activity.moreSettingsPopupWindow != null) activity.moreSettingsPopupWindow.dismiss();
        });

        activity.themeViewTree(popupView);

        // 弹窗高度固定为屏幕一半，内容过多时由内部 ScrollView 滚动
        android.util.DisplayMetrics msDm = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(msDm);
        int msPopupHeight = msDm.heightPixels / 2;

        // 以全屏透明窗口 + 跟手下滑容器承载面板
        activity.moreSettingsPopupWindow = activity.showSwipeDismissPopup(popupView, msPopupHeight, "more");
        // 联动：弹窗滑出时设置面板收回；弹窗收起（完成按钮/跟手下滑任一路径 dismiss）时面板重新滑出
        activity.retractSettingsPanelForSub();
        activity.suspendAutoPage();

        activity.mainHandler.removeCallbacks(activity.hideNavRunnable);
        activity.moreSettingsPopupWindow.setOnDismissListener(() -> {
            activity.resetAutoHideTimer();
            activity.resumeAutoPageIfSuspended();
            activity.restoreSettingsPanelFromSub();
        });
    }
}
