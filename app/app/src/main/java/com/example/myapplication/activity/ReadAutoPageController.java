package com.example.myapplication.activity;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.example.myapplication.R;

/**
 * 自动翻页控制器：翻页定时、页眉倒计时、设置弹窗打开时的挂起/恢复。
 * 开关状态（autoPageEnabled / autoPageInterval / autoPageSuspended）由宿主持有并持久化，
 * 本类只负责调度与执行；宿主经一行委托桩调用。
 */
class ReadAutoPageController {

    private final ReadActivity activity;

    ReadAutoPageController(ReadActivity activity) { this.activity = activity; }

    private final Handler autoPageHandler = new Handler(Looper.getMainLooper());
    private Runnable autoPageRunnable;

    private long autoPageCycleStart = 0; // 当前翻页周期起点（用于页眉倒计时）
    private final Runnable autoPageCountdownRunnable = new Runnable() {
        @Override public void run() {
            if (!activity.autoPageEnabled || activity.autoPageSuspended || !activity.activityResumed) return;
            if (activity.autoPageInterval <= 0) return;
            long elapsed = System.currentTimeMillis() - autoPageCycleStart;
            long remaining = activity.autoPageInterval - (elapsed % activity.autoPageInterval);
            int sec = (int) Math.ceil(remaining / 1000.0);
            if (sec <= 0) sec = 1;
            if (activity.isWebViewReady) activity.webView.evaluateJavascript("setAutoPageCountdown(" + sec + ")", null);
            autoPageHandler.postDelayed(this, 1000);
        }
    };

    void startAutoPage() {
        if (!activity.autoPageEnabled) return;
        stopAutoPage();
        autoPageCycleStart = System.currentTimeMillis();
        autoPageRunnable = () -> {
            // 仅在阅读器就绪且当前没有弹窗遮挡时翻页；翻页动画中 nextPage 会自动忽略
            if (activity.isWebViewReady && !isAnySettingsPopupShowing()) {
                activity.webView.evaluateJavascript("nextPage()", null);
            }
            // 翻页后开启新周期，让倒计时同步归位
            autoPageCycleStart = System.currentTimeMillis();
            autoPageHandler.postDelayed(autoPageRunnable, activity.autoPageInterval);
        };
        autoPageHandler.postDelayed(autoPageRunnable, activity.autoPageInterval);
        // 启动页眉倒计时刷新（每秒一次）
        autoPageHandler.removeCallbacks(autoPageCountdownRunnable);
        autoPageHandler.postDelayed(autoPageCountdownRunnable, 1000);
    }

    /** 仅移除定时回调，保留 autoPageEnabled 状态（用于挂起/恢复）；同时隐藏页眉倒计时 */
    void stopAutoPage() {
        if (autoPageRunnable != null) autoPageHandler.removeCallbacks(autoPageRunnable);
        autoPageHandler.removeCallbacks(autoPageCountdownRunnable);
        if (activity.isWebViewReady) activity.webView.evaluateJavascript("setAutoPageCountdown(0)", null);
    }



    /** 打开设置弹窗时挂起自动翻页，避免页面在面板背后继续翻动 */
    void suspendAutoPage() {
        if (activity.autoPageEnabled && autoPageRunnable != null) {
            stopAutoPage();
            activity.autoPageSuspended = true;
        }
    }

    /** 设置弹窗关闭后，若仍处于开启状态且 Activity 在前台则恢复自动翻页 */
    void resumeAutoPageIfSuspended() {
        if (activity.autoPageEnabled && activity.autoPageSuspended && activity.activityResumed) {
            activity.autoPageSuspended = false;
            startAutoPage();
        }
    }

    /** 是否有任意设置类弹窗正在显示（自动翻页应暂停） */
    private boolean isAnySettingsPopupShowing() {
        return (activity.settingsPopupWindow != null && activity.settingsPopupWindow.isShowing())
                || (activity.moreSettingsPopupWindow != null && activity.moreSettingsPopupWindow.isShowing())
                || (activity.fontsPopupWindow != null && activity.fontsPopupWindow.isShowing())
                || (activity.bgColorsPopupWindow != null && activity.bgColorsPopupWindow.isShowing());
    }

    /** 书末自动关闭时，若设置弹窗正打开则同步开关 UI */
    void syncAutoPageSwitchUI() {
        if (activity.settingsPopupWindow != null && activity.settingsPopupWindow.isShowing()) {
            SwitchCompat sw = activity.settingsPopupWindow.getContentView().findViewById(R.id.switch_auto_page);
            if (sw != null) sw.setChecked(false);
        }
    }

    /** 高亮当前选中的自动翻页速度按钮（蓝字为选中） */
    void updateAutoSpeedUI(TextView[] views, int idx) {
        for (int i = 0; i < views.length; i++) {
            views[i].setTextColor(i == idx ? activity.getColor(R.color.ios_blue) : activity.getColor(R.color.ios_text_secondary));
        }
    }

}
