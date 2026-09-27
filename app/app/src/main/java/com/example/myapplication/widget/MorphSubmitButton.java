package com.example.myapplication.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * 提交按钮的「形变链」状态反馈控件：点击后在同一容器内依次走
 * <pre>
 *   默认（胶囊）→ 内收成圆 + 环形进度 → 完成（描出对勾）→ 横向展开成结果条
 *   失败：环合拢后转红条 + 抖动，短暂停留后自动复位可重提。
 * </pre>
 * 全程只有一个容器、只改自身绘制尺寸（居中绘制），所以屏幕位置从头到尾不变。
 *
 * <p>用法：
 * <pre>
 *   MorphSubmitButton btn = findViewById(R.id.xxx);
 *   btn.setIdleColor(getColor(R.color.xxx));   // 可选：默认蓝
 *   btn.setIdleText("登录");
 *   btn.setOnClickListener(v -> { btn.startLoading(); doRequest(...); });
 *   // 请求成功：btn.succeed("登录成功");  失败：btn.fail("登录失败");
 * </pre>
 *
 * <p>成功态的「对勾」只在圆形阶段描出（strokeDasharray 等价做法：PathMeasure 裁剪路径），
 * 展开成结果条后只显示结果文案、不再带对勾前缀；失败态与成功态位置一致，仅末端展开成红条。
 */
public class MorphSubmitButton extends View {

    /** 空闲：胶囊，可点击。 */
    public static final int STATE_IDLE = 0;
    /** 加载：已内收成圆，正在画环。 */
    public static final int STATE_LOADING = 1;
    /** 加载完成 → 圆形描勾阶段。 */
    public static final int STATE_SUCCESS = 2;
    /** 已展开成结果条（成功文案）。 */
    public static final int STATE_RESULT = 3;
    /** 失败：已展开成红条 + 抖动。 */
    public static final int STATE_FAIL = 4;

    /** 形变结束回调（success/fail 动画播完时触发）。 */
    public interface OnMorphEndListener {
        void onEnd(int state);
    }

    // ===== 可配置颜色（默认蓝，登录页会改成主题紫）=====
    @ColorInt private int idleColor = 0xFF007AFF;
    @ColorInt private int successColor = 0xFFFFCC00;   // 黄色（用户指定）
    @ColorInt private int failColor = 0xFFFF3B30;       // 红色（用户指定 #FF3B30）
    @ColorInt private int textColor = 0xFFFFFFFF;       // 空闲/加载/失败文案（白）
    @ColorInt private int successTextColor = 0xFF3A3A3F;// 黄底用深色字保证可读
    @ColorInt private int checkColor = 0xFF3A3A3F;      // 黄底用深色勾

    // ===== 尺寸（px）=====
    private int heightPx;
    private float idleWidth;     // 由布局实测宽度（如 match_parent）
    private int circleSize;      // = 高度，圆=正方形
    private float ringStroke;    // 环/勾线宽

    // ===== 动画进度 =====
    private float widthFactor = 1f;  // 1 = 空闲宽，0 = 圆形
    private float ring = 0f;         // 0..1 环绘制比例
    private float check = 0f;        // 0..1 对勾描出比例
    private float bgMix = 0f;        // 0 = 基准色，1 = 终态色
    @ColorInt private int baseColor = idleColor;
    @ColorInt private int finalColor = idleColor;
    private float shake = 0f;        // 横向抖动偏移（px）

    private int state = STATE_IDLE;
    private OnMorphEndListener endListener;
    private AnimatorSet animSet;

    // ===== 文案 =====
    private String idleText = "登录";
    private String resultText = "登录成功";
    private String failText = "登录失败";

    // ===== 画笔 =====
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();

    private final Path checkPath = new Path();
    private final Path checkSeg = new Path();
    private PathMeasure checkMeasure;
    private float checkLen;
    private final ArgbEvaluator argb = new ArgbEvaluator();

    private final float density;

    public MorphSubmitButton(Context context) {
        this(context, null);
    }

    public MorphSubmitButton(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MorphSubmitButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        ringStroke = 4 * density;

        bgPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringTrackPaint.setStyle(Paint.Style.STROKE);
        ringTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        ringTrackPaint.setColor(0x40FFFFFF);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(17 * density);
        textPaint.setFakeBoldText(true);

        // 对勾路径：以圆心为原点，落在圆形内
        checkPath.moveTo(-0.22f * 56 * density, 0f);
        checkPath.lineTo(-0.05f * 56 * density, 0.18f * 56 * density);
        checkPath.lineTo(0.24f * 56 * density, -0.20f * 56 * density);
        checkMeasure = new PathMeasure(checkPath, false);
        checkLen = checkMeasure.getLength();
    }

    // ==================== 配置 ====================

    public void setIdleColor(@ColorInt int color) {
        idleColor = color;
        if (state == STATE_IDLE) {
            baseColor = color;
            finalColor = color;
            invalidate();
        }
    }

    public void setSuccessColor(@ColorInt int color) {
        successColor = color;
    }

    public void setFailColor(@ColorInt int color) {
        failColor = color;
    }

    public void setIdleText(String text) {
        idleText = text == null ? "" : text;
        if (state == STATE_IDLE) invalidate();
    }

    public void setOnMorphEndListener(OnMorphEndListener listener) {
        endListener = listener;
    }

    // ==================== 状态切换 ====================

    /** 进入加载态：内收成圆（0.45s）后开始画环（1.0s）。禁用点击防重复提交。 */
    public void startLoading() {
        if (state != STATE_IDLE && state != STATE_RESULT && state != STATE_FAIL) return;
        cancelAnim();
        state = STATE_LOADING;
        setEnabled(false);
        baseColor = idleColor;
        finalColor = idleColor;
        bgMix = 0f;
        ring = 0f;
        check = 0f;
        shake = 0f;

        ValueAnimator contract = ValueAnimator.ofFloat(1f, 0f);
        contract.setDuration(450);
        contract.setInterpolator(new DecelerateInterpolator());
        contract.addUpdateListener(a -> {
            widthFactor = (float) a.getAnimatedValue();
            invalidate();
        });

        ValueAnimator ringAnim = ValueAnimator.ofFloat(0f, 1f);
        ringAnim.setDuration(1000);
        ringAnim.setStartDelay(450);
        ringAnim.setInterpolator(new LinearInterpolator());
        ringAnim.addUpdateListener(a -> {
            ring = (float) a.getAnimatedValue();
            invalidate();
        });

        animSet = new AnimatorSet();
        animSet.playTogether(contract, ringAnim);
        animSet.start();
    }

    /** 成功：圆形底色转黄（0.2s）→ 描勾（0.36s）→ 展开成结果条（0.42s）。 */
    public void succeed(String text) {
        if (state != STATE_LOADING) return;
        cancelAnim();
        state = STATE_SUCCESS;
        resultText = text == null ? "成功" : text;
        baseColor = idleColor;
        finalColor = successColor;

        ValueAnimator colorAnim = ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(200);
        colorAnim.addUpdateListener(a -> {
            bgMix = (float) a.getAnimatedValue();
            invalidate();
        });

        ValueAnimator checkAnim = ValueAnimator.ofFloat(0f, 1f);
        checkAnim.setDuration(360);
        checkAnim.setStartDelay(200);
        checkAnim.addUpdateListener(a -> {
            check = (float) a.getAnimatedValue();
            invalidate();
        });

        ValueAnimator expand = ValueAnimator.ofFloat(0f, 1f);
        expand.setDuration(420);
        expand.setStartDelay(560);
        expand.setInterpolator(new DecelerateInterpolator());
        expand.addUpdateListener(a -> {
            widthFactor = (float) a.getAnimatedValue();
            invalidate();
        });

        animSet = new AnimatorSet();
        animSet.playTogether(colorAnim, checkAnim, expand);
        animSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                state = STATE_RESULT;
                invalidate();
                if (endListener != null) endListener.onEnd(STATE_SUCCESS);
            }
        });
        animSet.start();
    }

    /** 失败：圆形转红（0.2s）→ 抖动（0.42s）→ 展开成红条（0.42s），停留后自动复位可重提。 */
    public void fail(String text) {
        if (state != STATE_LOADING) return;
        cancelAnim();
        state = STATE_FAIL;
        failText = text == null ? "失败" : text;
        baseColor = idleColor;
        finalColor = failColor;

        ValueAnimator colorAnim = ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(200);
        colorAnim.addUpdateListener(a -> {
            bgMix = (float) a.getAnimatedValue();
            invalidate();
        });

        ValueAnimator shakeAnim = ValueAnimator.ofFloat(0f, 1f);
        shakeAnim.setDuration(420);
        shakeAnim.setStartDelay(200);
        shakeAnim.setInterpolator(new LinearInterpolator());
        shakeAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            shake = (float) (Math.sin(t * Math.PI * 6) * 8 * density * (1 - t));
            invalidate();
        });

        ValueAnimator expand = ValueAnimator.ofFloat(0f, 1f);
        expand.setDuration(420);
        expand.setStartDelay(620);
        expand.setInterpolator(new DecelerateInterpolator());
        expand.addUpdateListener(a -> {
            widthFactor = (float) a.getAnimatedValue();
            invalidate();
        });

        animSet = new AnimatorSet();
        animSet.playTogether(colorAnim, shakeAnim, expand);
        animSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (endListener != null) endListener.onEnd(STATE_FAIL);
                // 红条停留约 1.2s 后复位，让用户能重新提交
                postDelayed(MorphSubmitButton.this::reset, 1200);
            }
        });
        animSet.start();
    }

    /** 复位到空闲态（重新可点击）。 */
    public void reset() {
        cancelAnim();
        state = STATE_IDLE;
        widthFactor = 1f;
        ring = 0f;
        check = 0f;
        bgMix = 0f;
        shake = 0f;
        baseColor = idleColor;
        finalColor = idleColor;
        setEnabled(true);
        invalidate();
    }

    private void cancelAnim() {
        if (animSet != null && animSet.isRunning()) animSet.cancel();
        animSet = null;
    }

    // ==================== 绘制 ====================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) idleWidth = w;
        if (h > 0) heightPx = h;
        circleSize = heightPx;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = idleWidth > 0 ? idleWidth : getWidth();
        float drawnWidth = circleSize + (width - circleSize) * widthFactor;
        float left = (getWidth() - drawnWidth) / 2f + shake;
        float radius = Math.min(drawnWidth, heightPx) / 2f;

        // 背景：空闲且禁用时降透明度，模拟原按钮的禁用态
        int bg = (int) argb.evaluate(bgMix, baseColor, finalColor);
        float alpha = (state == STATE_IDLE && !isEnabled()) ? 0.6f : 1f;
        bgPaint.setColor(bg);
        bgPaint.setAlpha((int) (255 * alpha));
        bgRect.set(left, 0f, left + drawnWidth, heightPx);
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

        float cx = left + drawnWidth / 2f;
        float cy = heightPx / 2f;

        // 环形进度（仅加载态、已收成圆后绘制）
        if (state == STATE_LOADING && widthFactor < 0.1f) {
            float r = circleSize / 2f - ringStroke;
            ringTrackPaint.setStrokeWidth(ringStroke);
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f, false, ringTrackPaint);
            ringPaint.setStrokeWidth(ringStroke);
            ringPaint.setColor(0xFFFFFFFF);
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * ring, false, ringPaint);
        }

        // 对勾（成功圆形阶段、尚未展开时）
        if (state == STATE_SUCCESS && widthFactor < 0.6f) {
            drawCheck(canvas, cx, cy, check);
        }

        // 文案
        if (state == STATE_IDLE) {
            drawText(canvas, idleText, textColor, alpha);
        } else if (state == STATE_SUCCESS || state == STATE_RESULT) {
            if (widthFactor > 0.6f) {
                float a = Math.min(1f, (widthFactor - 0.6f) / 0.4f);
                drawText(canvas, resultText, successTextColor, a);
            }
        } else if (state == STATE_FAIL) {
            if (widthFactor > 0.6f) {
                float a = Math.min(1f, (widthFactor - 0.6f) / 0.4f);
                drawText(canvas, failText, textColor, a);
            }
        }
    }

    private void drawCheck(Canvas canvas, float cx, float cy, float progress) {
        if (progress <= 0f) return;
        checkSeg.reset();
        checkMeasure.getSegment(0f, checkLen * progress, checkSeg, true);
        canvas.save();
        canvas.translate(cx, cy);
        checkPaint.setColor(checkColor);
        checkPaint.setStrokeWidth(ringStroke * 0.9f);
        canvas.drawPath(checkSeg, checkPaint);
        canvas.restore();
    }

    private void drawText(Canvas canvas, String text, @ColorInt int color, float alpha) {
        textPaint.setColor(color);
        textPaint.setAlpha((int) (255 * alpha));
        float baseline = heightPx / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;
        canvas.drawText(text, getWidth() / 2f, baseline, textPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnim();
        super.onDetachedFromWindow();
    }
}
