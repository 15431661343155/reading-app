package com.example.myapplication.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.example.myapplication.R;

/**
 * 全站统一的「加载中」动画：奔跑的插画（GIF）+「加载中」+ 三个依次出现、同时消失的点。
 *
 * <p>用法：布局里放一个
 * {@code <com.example.myapplication.widget.LoadingView android:id="@+id/loading_view" .../>}，
 * 需要加载时 {@code setVisibility(VISIBLE)}，加载结束 {@code setVisibility(GONE)}。
 *
 * <p><b>不用手动启停</b>：可见性一变（自己变、或被父容器连带隐藏）就会自动启停「三个点」的动画；
 * GIF 本身由 {@code Drawable#setVisible} 在不可见时自动暂停，所以隐藏时不会白耗帧。
 *
 * <p>点的节奏（一个周期 {@link #CYCLE_MS}，前 3/4 依次点亮、后 1/4 三个一起熄灭）：
 * <pre>
 *   0.0 – 0.3s   •
 *   0.3 – 0.6s   ••
 *   0.6 – 0.9s   •••
 *   0.9 – 1.2s   （空，三点同时消失）
 * </pre>
 * 三个点占<b>固定宽度</b>（{@link #DOT_SIZE_DP}×3 + 间隔×2），所以「加载中」四个字不会随点数左右跳动。
 *
 * <p><b>系统关闭动画时（动画时长缩放 = 0，常见于省电模式 / 开发者选项 / 部分 ROM 默认值）
 * 退化为静态「•••」</b>：{@link ValueAnimator} 在缩放为 0 时不会推进回调，若仍靠它点亮圆点，
 * 三个点会一个都看不到（实测踩过）。
 */
public class LoadingView extends LinearLayout {

    /** 一个完整周期（毫秒）。 */
    private static final int CYCLE_MS = 1200;
    private static final int DOT_COUNT = 3;
    /** 圆点直径（dp）。 */
    private static final int DOT_SIZE_DP = 5;
    /** 圆点之间、以及圆点与文字之间的间距（dp）。 */
    private static final int DOT_GAP_DP = 5;
    /** 插画默认边长（dp）。 */
    private static final int DEFAULT_GIF_SIZE_DP = 160;

    private final ImageView gifView;
    private final View[] dots = new View[DOT_COUNT];

    private ValueAnimator dotsAnimator;
    /** 加载 GIF 用的 RequestManager：绑当前 View 的生命周期，detach 时用它精确取消请求 */
    private RequestManager gifManager;
    private boolean gifRequested;

    public LoadingView(Context context) {
        this(context, null);
    }

    public LoadingView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LoadingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        // 用 CENTER（而非 CENTER_HORIZONTAL）：本控件若被铺满父容器（如整页加载遮罩），
        // 需要连垂直方向一起居中；wrap_content 时两者表现一致。
        setGravity(Gravity.CENTER);

        int gifSize = dp(DEFAULT_GIF_SIZE_DP);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.LoadingView);
            gifSize = a.getDimensionPixelSize(R.styleable.LoadingView_lvGifSize, gifSize);
            a.recycle();
        }

        // 1) 奔跑插画
        gifView = new ImageView(context);
        gifView.setLayoutParams(new LayoutParams(gifSize, gifSize));
        gifView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gifView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(gifView);

        // 2) 一行：「加载中」 + 三个点
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(HORIZONTAL);
        // 点靠行内垂直居中 → 视觉上落在文字的中腰，而不是贴着基线
        line.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams lineLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lineLp.topMargin = dp(16);
        line.setLayoutParams(lineLp);
        addView(line);

        TextView label = new TextView(context);
        label.setText("加载中");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTextColor(0xFF8E8E93);
        label.setLetterSpacing(0.03f);
        label.setIncludeFontPadding(false);
        line.addView(label);

        // 点槽位固定宽度：3 个点 + 2 个间隔，保证文字不随点数左右跳动
        LinearLayout dotBox = new LinearLayout(context);
        dotBox.setOrientation(HORIZONTAL);
        dotBox.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams boxLp = new LayoutParams(
                dp(DOT_SIZE_DP * DOT_COUNT + DOT_GAP_DP * (DOT_COUNT - 1)),
                LayoutParams.WRAP_CONTENT);
        boxLp.setMarginStart(dp(3));
        dotBox.setLayoutParams(boxLp);
        line.addView(dotBox);

        for (int i = 0; i < DOT_COUNT; i++) {
            View dot = new View(context);
            LayoutParams lp = new LayoutParams(dp(DOT_SIZE_DP), dp(DOT_SIZE_DP));
            if (i > 0) lp.setMarginStart(dp(DOT_GAP_DP));
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_loading_dot);
            dot.setAlpha(0f);
            dotBox.addView(dot);
            dots[i] = dot;
        }
    }

    // ==================== 动画 ====================

    private void startDots() {
        if (!animatorsEnabled()) {
            // 系统关了动画：ValueAnimator 不会推进，静态点亮三个点，否则「加载中」后面会空着
            for (View d : dots) d.setAlpha(1f);
            return;
        }
        if (dotsAnimator != null && dotsAnimator.isRunning()) return;
        if (dotsAnimator == null) {
            dotsAnimator = ValueAnimator.ofFloat(0f, 1f);
            dotsAnimator.setDuration(CYCLE_MS);
            dotsAnimator.setRepeatCount(ValueAnimator.INFINITE);
            dotsAnimator.setInterpolator(new LinearInterpolator());
            dotsAnimator.addUpdateListener(anim -> {
                float t = (float) anim.getAnimatedValue() * CYCLE_MS;
                for (int i = 0; i < DOT_COUNT; i++) {
                    // 第 i 个点在第 i 个 1/4 处亮起；到 3/4 处三个一起熄灭
                    boolean on = t >= i * (CYCLE_MS / 4f) && t < CYCLE_MS * 3f / 4f;
                    dots[i].setAlpha(on ? 1f : 0f);
                }
            });
        }
        dotsAnimator.start();
    }

    private void stopDots() {
        if (dotsAnimator != null) dotsAnimator.cancel();
        for (View d : dots) d.setAlpha(0f);
    }

    /** 系统当前是否允许动画（动画时长缩放 ≠ 0）。读不到就交给 ValueAnimator 自己判断。 */
    private boolean animatorsEnabled() {
        try {
            float scale = Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale > 0f;
        } catch (Exception e) {
            return true;
        }
    }

    // ==================== 可见性驱动启停 ====================

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isShown()) {
            ensureGif();
            startDots();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopDots();
        releaseGif();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!isAttachedToWindow()) return;
        // 用 isShown() 而不是入参 visibility：父容器被隐藏时，子 View 收到的
        // visibility 仍是自身的 VISIBLE，只看入参会漏掉「被父容器连带隐藏」的情况。
        if (isShown()) {
            ensureGif();
            startDots();
        } else {
            stopDots();
        }
    }

    // ==================== GIF ====================

    private void ensureGif() {
        if (gifRequested || gifView == null) return;
        gifRequested = true;
        gifManager = Glide.with(this);
        gifManager.asGif().load(R.drawable.loading_running).into(gifView);
    }

    private void releaseGif() {
        if (!gifRequested) return;
        gifRequested = false;
        if (gifManager != null) {
            gifManager.clear(gifView);
            gifManager = null;
        }
    }

    // ==================== 工具 ====================

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
