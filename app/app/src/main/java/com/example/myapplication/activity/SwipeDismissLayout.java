package com.example.myapplication.activity;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;

import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
/**
 * 跟手下滑关闭容器：作为 PopupWindow 的 contentView（全屏透明），内部承载底部面板。
 * <p>之所以用「自定义 ViewGroup + onInterceptTouchEvent」而不是 OnTouchListener：
 * 面板里有 ViewPager2 / RecyclerView / 按钮，它们会消费 ACTION_DOWN，挂在父布局上的
 * OnTouchListener 根本收不到后续 MOVE；而 onInterceptTouchEvent 发生在子 View 之前，
 * 能正确抢下手势。同时全屏容器保证面板下移时不会被 PopupWindow 的半屏窗口边界裁掉。
 */
class SwipeDismissLayout extends FrameLayout {
    private View panel;
    private View retractView;    // 收回动画的作用对象（被容器裁剪的面板本体）
    private View scrollable;
    private Runnable dismissAction;
    private float startRawX, startRawY;
    private boolean dragging;
    private boolean maybeDrag;
    private boolean outsideDown;
    private VelocityTracker vt;
    private final float slop;
    private final float threshold;

    SwipeDismissLayout(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        slop = 8 * d;
        threshold = 90 * d;
        setClickable(true);          // 保证空白区域的 DOWN 也能进入 onTouchEvent
        setClipChildren(false);      // 面板下移时不被容器裁掉
    }

    void setPanel(View v) { panel = v; }
    void setRetractView(View v) { retractView = v; }
    void setScrollable(View v) { scrollable = v; }
    void setDismissAction(Runnable r) { dismissAction = r; }

    /** 可滚动内容是否已在顶部（只有置顶时才允许下拉关闭） */
    private boolean canDrag() {
        if (scrollable == null) return true;
        if (scrollable instanceof ScrollView) return ((ScrollView) scrollable).getScrollY() <= 0;
        if (scrollable instanceof AbsListView) {
            AbsListView lv = (AbsListView) scrollable;
            return lv.getChildCount() == 0
                    || (lv.getFirstVisiblePosition() == 0 && lv.getChildAt(0).getTop() >= 0);
        }
        if (scrollable instanceof RecyclerView) return !((RecyclerView) scrollable).canScrollVertically(-1);
        return scrollable.getScrollY() <= 0;
    }

    /** 跟手关闭：外层容器（panel）继续向下滑出屏幕后关闭，全程可见 */
    void dismissByDrag() {
        animateOut(panel, 180);
    }

    /** 收回关闭：面板（retractView，被容器裁剪）收回到「目录/夜间/设置」行内后关闭 */
    void dismissAnimated() {
        animateOut(retractView != null ? retractView : panel, 200);
    }

    private void animateOut(View target, long duration) {
        if (target == null) { if (dismissAction != null) dismissAction.run(); return; }
        float from = target.getTranslationY();
        float to = target.getHeight() > 0 ? target.getHeight() : from + 300f;
        if (to <= from) to = from + 1f;
        target.animate().cancel();
        target.animate().translationY(to)
                .setDuration(duration)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    target.setTranslationY(0f);
                    if (dismissAction != null) dismissAction.run();
                }).start();
    }

    /** 从任意子 View 向上找到承载它的 SwipeDismissLayout（用于关闭按钮触发带动画关闭） */
    static SwipeDismissLayout findHost(View v) {
        android.view.ViewParent p = v != null ? v.getParent() : null;
        while (p != null) {
            if (p instanceof SwipeDismissLayout) return (SwipeDismissLayout) p;
            p = p.getParent();
        }
        return null;
    }

    /** 按下点是否落在面板上方（面板之外的空白区域） */
    private boolean isOutside(MotionEvent ev) {
        if (panel == null) return false;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        return ev.getRawY() < loc[1] - 1;
    }

    private boolean shouldStartDrag(MotionEvent ev) {
        if (panel == null || outsideDown) return false;
        float dy = ev.getRawY() - startRawY;
        float adx = Math.abs(ev.getRawX() - startRawX);
        return dy > slop && dy > adx && canDrag();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (panel == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startRawX = ev.getRawX();
                startRawY = ev.getRawY();
                dragging = false;
                maybeDrag = true;
                outsideDown = isOutside(ev);
                obtainVt(ev);
                return false; // 先让子 View 正常处理 DOWN
            case MotionEvent.ACTION_MOVE:
                if (maybeDrag && !dragging && shouldStartDrag(ev)) {
                    dragging = true;   // 抢下手势，子 View 会收到 ACTION_CANCEL
                }
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return dragging;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (panel == null) return false;
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) obtainVt(ev);
        else if (vt != null) vt.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startRawX = ev.getRawX();
                startRawY = ev.getRawY();
                dragging = false;
                maybeDrag = true;
                outsideDown = isOutside(ev);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (maybeDrag && !dragging && shouldStartDrag(ev)) dragging = true;
                if (dragging) {
                    panel.setTranslationY(Math.max(0f, ev.getRawY() - startRawY));
                    return true;
                }
                return outsideDown; // 面板外按下：保持消费，便于抬起时关闭
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = ev.getActionMasked() == MotionEvent.ACTION_UP;
                if (dragging) {
                    if (vt != null) vt.computeCurrentVelocity(1000);
                    float yv = vt != null ? vt.getYVelocity() : 0f;
                    float ty = panel.getTranslationY();
                    if (ty > threshold || yv > 1200) {
                        dismissByDrag();   // 跟手：外层容器继续下滑出屏幕
                    } else {
                        panel.animate().translationY(0f).setDuration(160).start();
                    }
                    dragging = false;
                    maybeDrag = false;
                    releaseVt();
                    return true;
                }
                maybeDrag = false;
                releaseVt();
                if (outsideDown && up && Math.abs(ev.getRawY() - startRawY) < slop
                        && Math.abs(ev.getRawX() - startRawX) < slop) {
                    dismissAnimated();   // 点击面板外部关闭
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private void obtainVt(MotionEvent ev) {
        if (vt == null) vt = VelocityTracker.obtain(); else vt.clear();
        vt.addMovement(ev);
    }

    private void releaseVt() {
        if (vt != null) { vt.recycle(); vt = null; }
    }

/** 用跟手下滑容器包住底部面板，并以全屏透明窗口承载（保证下滑时不被窗口边界裁掉） */
static PopupWindow showPopup(Activity act, View popupView, int panelHeight, String logTag) {
    // 其它浮窗保持原有呈现：面板直接贴屏幕底（不抬高、不做容器裁剪），
    // 仅用全屏透明窗口承载，使跟手下拉时面板不会被窗口边界裁掉。
    SwipeDismissLayout host = new SwipeDismissLayout(act);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            panelHeight > 0 ? panelHeight : ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.gravity = Gravity.BOTTOM;
    popupView.setLayoutParams(lp);
    host.addView(popupView);
    host.setPanel(popupView);
    View sc = popupView.findViewById(R.id.popup_scroll);
    if (sc != null) host.setScrollable(sc);

    PopupWindow popup = new PopupWindow(host,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true);
    // 不能用整窗位移动画：slide_in_bottom 的 100% 是相对「窗口高度」，
    // 窗口改成全屏后位移 = 整屏高，面板前一半路程都在屏幕外，看上去就是「从屏幕底部飞入」。
    // 改为取消整窗动画，只对面板本身做入场位移（仅在其最终位置范围内滑出）。
    popup.setAnimationStyle(0);
    popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    host.setDismissAction(() -> popup.dismiss());
    popup.showAtLocation(act.findViewById(android.R.id.content), Gravity.BOTTOM, 0, 0);

    // 入场：必须在首帧绘制「之前」把面板移到容器下沿之外。
    // 用 post() 会在首帧绘制「之后」才执行，于是先按最终位置画出完整面板（一闪），
    // 接着才被移到屏幕外（消失），再滑回来 —— 即「全部显现 → 消失 → 从底部滑出」。
    // 故与设置面板一致改用 OnPreDrawListener：首帧绘制前完成位移。
    popupView.setVisibility(View.INVISIBLE); // INVISIBLE 仍参与测量布局，仅不绘制，避免首帧闪现
    host.getViewTreeObserver().addOnPreDrawListener(
            new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    float h = popupView.getHeight() > 0 ? popupView.getHeight() : (float) panelHeight;
                    if (h <= 0) return true; // 尚未测量完成，等下一帧
                    popupView.getViewTreeObserver().removeOnPreDrawListener(this);
                    popupView.setVisibility(View.VISIBLE);
                    popupView.setTranslationY(h);
                    popupView.animate().translationY(0f).setDuration(220)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    return true;
                }
            });
    return popup;
}
}
