package com.example.myapplication.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.core.graphics.ColorUtils;

/**
 * 液态进度滑块（阅读器底部导航栏用，替代原生 SeekBar）。
 *
 * 结构：等径轨道（固定 18dp 圆条、两端内收 6dp 与两侧按钮留距、垂直居中于控件，不随按下列加粗）+ 已读填充 + 圆形滑钮 + 页码浮窗。
 * 动画：滑钮缩放与浮窗浮现由同一个 ValueAnimator 驱动同一个 activeAmount 派生，
 * 共用同一时长（260ms）与同一条弹性缓动（PathInterpolator 0.34,1.3,0.55,1），严格同频。
 * 浮窗：中心始终对准滑钮，绘制在控件顶界之外（向上溢出底部导航栏，需父容器链
 * clipChildren=false），拖动时浮现、松手淡出，显示「当前页 / 章节总页数」。
 * 滑钮拖动时按手速轻微拉伸，产生水滴感；活动范围仅向内收一个半径（thumbR）。
 * 配色：setChrome(chrome, textMain, dark) 从导航栏面板底色派生各层中性色，
 * 供日/夜平滑渐变逐帧调用，自动适配任意主题背景。
 * 回调：拖动中仅通过 onDragPage 轻量更新页码文字；松手时 onDragEnd 回调一次最终页码，
 * 由调用方执行真正的跳页，避免逐帧触发 WebView 重排造成卡顿。
 */
public class LiquidSlider extends View {

    /** 交互回调，方法均在主线程调用 */
    public interface Listener {
        /** 手指按下滑块（已取消导航栏自动隐藏等由调用方处理） */
        void onDragStart();
        /** 拖动中页码变化（仅用于轻量 UI 更新，勿在此跳页） */
        void onDragPage(int page);
        /** 松手，page 为最终停留页（1..totalPages），调用方据此跳页 */
        void onDragEnd(int page);
    }

    /** 浮窗文字格式化：用于把 int 值映射为自定义文案（如「1.8×」「22」）；null 时显示默认「cur / total」 */
    public interface TipFormatter { String format(int value); }

    private static final long ANIM_DURATION_MS = 260;
    private static final PathInterpolator EASE = new PathInterpolator(0.34f, 1.3f, 0.55f, 1f);
    private static final float MAX_STRETCH = 0.22f;
    /** 轨道两端相对控件边缘的内收量：与上一章/下一章按钮保持舒适间距 */
    private static final float TRACK_INSET_DP = 6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path triPath = new Path();

    // 几何（px）
    private float thumbR;
    private float tipTextWidth;

    // 进度
    private int totalPages = 1;
    private int currentPage = 1;
    private String tipText = "1 / 1";

    // 动画状态
    private float activeAmount = 0f;
    private ValueAnimator activeAnim;
    private boolean dragging = false;
    private float stretch = 0f;
    private float lastX, lastT;

    // 配色（setChrome 派生）
    private int trackColor, fillColor, thumbColor, thumbStrokeColor;
    private int tipBgColor, tipTextColor, tipStrokeColor;

    private Listener listener;
    private TipFormatter tipFormatter;

    public LiquidSlider(Context context) {
        super(context);
        init();
    }

    public LiquidSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LiquidSlider(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setChrome(Color.WHITE, 0xFF2C2C2A, false);
        tipPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** 设置浮窗文字格式化器（导航栏进度条无需设置，保持默认「cur / total」） */
    public void setTipFormatter(TipFormatter f) {
        tipFormatter = f;
        recomputeTipMetrics();
        invalidate();
    }

    /**
     * 从导航栏面板底色派生滑块配色。日/夜平滑渐变可逐帧调用（代价仅为几次颜色混合）。
     *
     * @param chrome   导航栏面板底色
     * @param textMain 主文字色（浮窗文字用）
     * @param dark     是否深色主题
     */
    public void setChrome(int chrome, int textMain, boolean dark) {
        int toward = dark ? Color.WHITE : Color.BLACK;
        trackColor = ColorUtils.blendARGB(chrome, toward, dark ? 0.09f : 0.09f);
        fillColor = ColorUtils.blendARGB(chrome, toward, 0.20f);
        thumbColor = dark ? ColorUtils.blendARGB(chrome, Color.WHITE, 0.88f) : Color.WHITE;
        thumbStrokeColor = dark ? Color.TRANSPARENT : ColorUtils.blendARGB(chrome, Color.BLACK, 0.22f);
        tipBgColor = dark ? ColorUtils.blendARGB(chrome, Color.WHITE, 0.13f)
                          : ColorUtils.blendARGB(chrome, Color.BLACK, 0.05f);
        tipStrokeColor = dark ? ColorUtils.blendARGB(chrome, Color.WHITE, 0.20f)
                              : ColorUtils.blendARGB(chrome, Color.BLACK, 0.16f);
        tipTextColor = textMain;
        invalidate();
    }

    /**
     * 更新进度与总页数（页码从 1 计）。totalPages 变化会重算浮窗宽度与两端内收量。
     */
    public void setProgressInfo(int page, int totalPagesInChapter) {
        this.totalPages = Math.max(1, totalPagesInChapter);
        this.currentPage = Math.min(Math.max(1, page), this.totalPages);
        recomputeTipMetrics();
        invalidate();
    }

    /** 当前对外呈现的页码 */
    public int getCurrentPage() {
        return currentPage;
    }

    private float ratio() {
        return totalPages > 1 ? (currentPage - 1) / (float) (totalPages - 1) : 0f;
    }

    private float thumbX() {
        float inset = dp(TRACK_INSET_DP);
        return inset + ratio() * (getWidth() - inset * 2);
    }

    private float trackCy() {
        return getHeight() / 2f;
    }

    private float trackTop() {
        return trackCy() - thumbR;
    }

    private void recomputeTipMetrics() {
        tipText = (tipFormatter != null) ? tipFormatter.format(currentPage)
                                         : currentPage + " / " + totalPages;
        tipPaint.setTextSize(dp(12f));
        tipTextWidth = tipPaint.measureText(tipText);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        thumbR = dp(9);
        recomputeTipMetrics();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        if (w <= 0) return;
        float cy = trackCy();
        float top = trackTop();
        float bottom = cy + thumbR;
        float tx = thumbX();

        // 轨道底：两端内收 TRACK_INSET，与两侧按钮保持间距
        float inset = dp(TRACK_INSET_DP);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColor);
        rect.set(inset, top, w - inset, bottom);
        canvas.drawRoundRect(rect, thumbR, thumbR, paint);

        // 已读填充：右端延伸到滑钮中心之后（被滑钮遮住），不超过轨道右端；ratio=1 时恰好铺满全轨道
        if (tx > inset + 0.5f) {
            paint.setColor(fillColor);
            rect.set(inset, top, Math.min(tx + thumbR, w - inset), bottom);
            canvas.drawRoundRect(rect, thumbR, thumbR, paint);
        }

        // 滑钮：缩放(同频) × 手速拉伸(水滴感)
        float scale = 1f + 0.2f * activeAmount;
        canvas.save();
        canvas.translate(tx, cy);
        canvas.scale(scale * (1f + stretch), scale * (1f - stretch * 0.7f));
        paint.setColor(thumbColor);
        canvas.drawCircle(0, 0, thumbR, paint);
        if (thumbStrokeColor != Color.TRANSPARENT) {
            paint.setColor(thumbStrokeColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.75f));
            canvas.drawCircle(0, 0, thumbR - paint.getStrokeWidth() / 2f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        canvas.restore();

        drawTip(canvas, tx);
    }

    private void drawTip(Canvas canvas, float tx) {
        if (activeAmount <= 0.01f) return;
        int alpha = Math.round(255 * Math.min(1f, activeAmount));
        float capH = dp(21);
        float triH = dp(5);
        float capBottom = trackTop() - dp(6) - triH;
        float capTop = capBottom - capH;
        float offsetY = (1f - activeAmount) * dp(4);
        canvas.save();
        canvas.translate(0, offsetY);

        float halfW = tipTextWidth / 2 + dp(16);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tipBgColor);
        rect.set(tx - halfW, capTop, tx + halfW, capBottom);
        canvas.drawRoundRect(rect, dp(9), dp(9), paint);

        // 圆角方形描边
        if (tipStrokeColor != Color.TRANSPARENT) {
            paint.setColor(tipStrokeColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.5f));
            canvas.drawRoundRect(rect, dp(9), dp(9), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // 三角指向滑钮
        paint.setColor(tipBgColor);
        triPath.reset();
        triPath.moveTo(tx - dp(5), capBottom - dp(0.5f));
        triPath.lineTo(tx + dp(5), capBottom - dp(0.5f));
        triPath.lineTo(tx, capBottom + triH - dp(0.5f));
        triPath.close();
        canvas.drawPath(triPath, paint);

        // 页码文字（垂直居中）
        Paint.FontMetrics fm = tipPaint.getFontMetrics();
        tipPaint.setColor(tipTextColor);
        tipPaint.setAlpha(alpha);
        float baseline = capTop + capH / 2 - (fm.ascent + fm.descent) / 2;
        canvas.drawText(tipText, tx, baseline, tipPaint);
        tipPaint.setAlpha(255);
        canvas.restore();
        paint.setAlpha(255);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                lastX = event.getX();
                lastT = event.getEventTime();
                animateActive(1f);
                applyTouch(event.getX());
                if (listener != null) listener.onDragStart();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                float dt = Math.max(1, event.getEventTime() - lastT);
                float vx = (event.getX() - lastX) / dt;
                stretch = Math.max(-MAX_STRETCH, Math.min(MAX_STRETCH, vx * 0.05f));
                lastX = event.getX();
                lastT = event.getEventTime();
                applyTouch(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return true;
                dragging = false;
                stretch = 0f;
                animateActive(0f);
                if (listener != null) listener.onDragEnd(currentPage);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void applyTouch(float x) {
        float w = getWidth();
        if (w <= 0) return;
        float inset = dp(TRACK_INSET_DP);
        float clamped = Math.max(inset, Math.min(w - inset, x));
        float ratio = (clamped - inset) / (w - inset * 2);
        int page = 1 + Math.round(ratio * (totalPages - 1));
        page = Math.min(Math.max(1, page), totalPages);
        if (page != currentPage) {
            currentPage = page;
            recomputeTipMetrics();
            invalidate();
            if (listener != null) listener.onDragPage(page);
        }
    }

    private void animateActive(float to) {
        if (activeAnim != null) activeAnim.cancel();
        activeAnim = ValueAnimator.ofFloat(activeAmount, to);
        activeAnim.setDuration(ANIM_DURATION_MS);
        activeAnim.setInterpolator(EASE);
        activeAnim.addUpdateListener(anim -> {
            activeAmount = (float) anim.getAnimatedValue();
            invalidate();
        });
        activeAnim.start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
