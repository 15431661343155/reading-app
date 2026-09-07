package com.example.myapplication.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.myapplication.R;
import com.google.android.material.card.MaterialCardView;

import java.util.Arrays;
import java.util.Random;

/**
 * 磨砂玻璃卡片（iOS frosted glass）。
 *
 * <p>原理：在每一帧绘制前，把「背景采样源」（如内容容器）位于本控件下方的区域
 * 绘制到一张降采样位图上，做快速高斯近似模糊，再把模糊结果放大绘制到自身圆角区域内，
 * 最后叠加半透明色 + 磨砂颗粒 + 顶部高光，形成真正的半透毛玻璃质感。
 *
 * <p>为了不产生无谓开销：只有在采样结果与上一帧不同（内容真的变了）时才 invalidate，
 * 界面静止时不会持续触发重绘。
 *
 * <pre>
 * app:frostBackdrop="@id/fragment_container"  采样源
 * app:frostTint="@color/nav_frost_tint"       叠加色（alpha 决定通透度）
 * app:frostBlurRadius="16dp"                  模糊半径
 * </pre>
 */
public class FrostedNavCardView extends MaterialCardView {

    /** 两次采样之间的最小间隔，限制为 ~30fps，避免高频滚动时过度采样 */
    private static final long MIN_CAPTURE_INTERVAL_MS = 33L;
    private static final int NOISE_SIZE = 128;

    private final Path mClipPath = new Path();
    private final RectF mDstRect = new RectF();
    private final Paint mBackdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNoisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int[] mLocSelf = new int[2];
    private final int[] mLocSrc = new int[2];

    private Bitmap mSrcBitmap;
    private Bitmap mBlurBitmap;
    private Canvas mSrcCanvas;
    private int[] mBlurA;
    private int[] mBlurB;
    private int[] mPrevPixels;
    private int[] mCurrPixels;
    private Bitmap mNoiseBitmap;

    private int mBackdropId = View.NO_ID;
    private int mTintColor = Color.parseColor("#99FFFFFF");
    private int mBaseColor = Color.parseColor("#FFF2F2F7");
    private int mSheenColor = Color.parseColor("#5CFFFFFF");
    private int mDownscale = 4;
    private float mBlurRadiusPx = 12f;
    private int mNoiseAlpha = 55;
    private float mCornerRadiusPx = -1f;
    private boolean mFrostEnabled = true;

    private long mLastCaptureAt;
    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;
    private boolean mDebugLogged;

    public FrostedNavCardView(@NonNull Context context) {
        this(context, null);
    }

    public FrostedNavCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, com.google.android.material.R.attr.materialCardViewStyle);
    }

    public FrostedNavCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    private void init(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.FrostedNavCardView, defStyleAttr, 0);
        mBackdropId = a.getResourceId(R.styleable.FrostedNavCardView_frostBackdrop, View.NO_ID);
        mTintColor = a.getColor(R.styleable.FrostedNavCardView_frostTint, mTintColor);
        mBaseColor = a.getColor(R.styleable.FrostedNavCardView_frostBaseColor, mBaseColor);
        mSheenColor = a.getColor(R.styleable.FrostedNavCardView_frostSheen, mSheenColor);
        mDownscale = a.getInt(R.styleable.FrostedNavCardView_frostDownscale, mDownscale);
        if (mDownscale < 1) {
            mDownscale = 1;
        }
        float blurDp = a.getDimension(R.styleable.FrostedNavCardView_frostBlurRadius, 16f);
        mBlurRadiusPx = Math.max(1f, blurDp / mDownscale);
        mNoiseAlpha = a.getInt(R.styleable.FrostedNavCardView_frostNoiseAlpha, mNoiseAlpha);
        mCornerRadiusPx = a.getDimension(R.styleable.FrostedNavCardView_frostCornerRadius, -1f);
        mFrostEnabled = a.getBoolean(R.styleable.FrostedNavCardView_frostEnabled, true);
        a.recycle();

        // 卡片自身背景必须透明，磨砂层由本控件绘制
        setCardBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(false);

        mTintPaint.setColor(mTintColor);
        mBasePaint.setColor(mBaseColor);
        if (mNoiseAlpha > 0 && mFrostEnabled) {
            mNoiseBitmap = createNoiseBitmap(NOISE_SIZE);
            mNoisePaint.setShader(new BitmapShader(mNoiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            mNoisePaint.setAlpha(mNoiseAlpha);
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewTreeObserver vto = getViewTreeObserver();
        if (vto.isAlive()) {
            if (mPreDrawListener == null) {
                mPreDrawListener = this::onBeforeDraw;
            }
            vto.removeOnPreDrawListener(mPreDrawListener);
            vto.addOnPreDrawListener(mPreDrawListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ViewTreeObserver vto = getViewTreeObserver();
        if (vto.isAlive() && mPreDrawListener != null) {
            vto.removeOnPreDrawListener(mPreDrawListener);
        }
        releaseBitmaps();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        releaseBitmaps();
        if (w > 0 && h > 0) {
            mSheenPaint.setShader(new LinearGradient(
                    0f, 0f, 0f, h * 0.85f,
                    mSheenColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        } else {
            mSheenPaint.setShader(null);
        }
        mLastCaptureAt = 0L;
    }

    // ------------------------------------------------------------------ 采样 & 绘制

    /** 每一帧绘制前：采样一次背景，内容有变化才请求重绘 */
    private boolean onBeforeDraw() {
        if (!mFrostEnabled || !isShown() || getWidth() <= 0 || getHeight() <= 0) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLastCaptureAt < MIN_CAPTURE_INTERVAL_MS) {
            return true;
        }
        mLastCaptureAt = now;
        if (captureBackdrop()) {
            invalidate();
        }
        return true;
    }

    /**
     * 采集背景采样源位于本控件下方的区域并模糊。
     *
     * @return 本次结果与上一次不同（说明背后内容变了）
     */
    private boolean captureBackdrop() {
        View src = resolveBackdrop();
        int w = getWidth();
        int h = getHeight();
        if (src == null || w <= 0 || h <= 0 || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return false;
        }

        int sw = Math.max(1, w / mDownscale);
        int sh = Math.max(1, h / mDownscale);
        if (mSrcBitmap == null || mSrcBitmap.getWidth() != sw || mSrcBitmap.getHeight() != sh) {
            releaseBitmaps();
            mSrcBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            mBlurBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            mSrcCanvas = new Canvas(mSrcBitmap);
            int len = sw * sh;
            mBlurA = new int[len];
            mBlurB = new int[len];
            mPrevPixels = new int[len];
            mCurrPixels = new int[len];
            Arrays.fill(mPrevPixels, 0);
        }

        src.getLocationOnScreen(mLocSrc);
        getLocationOnScreen(mLocSelf);
        int left = mLocSelf[0] - mLocSrc[0];
        int top = mLocSelf[1] - mLocSrc[1];

        mSrcBitmap.eraseColor(mBaseColor);
        mSrcCanvas.save();
        mSrcCanvas.scale(1f / mDownscale, 1f / mDownscale);
        mSrcCanvas.translate(-left, -top);
        try {
            src.draw(mSrcCanvas);
        } catch (Throwable ignored) {
            // 个别硬件层 View 无法绘制到软件画布，忽略即可（保留兜底底色）
        }
        mSrcCanvas.restore();

        fastBlur(mSrcBitmap, mBlurBitmap, mBlurA, mBlurB, Math.round(mBlurRadiusPx));

        mBlurBitmap.getPixels(mCurrPixels, 0, sw, 0, 0, sw, sh);
        if (Arrays.equals(mCurrPixels, mPrevPixels)) {
            return false;
        }
        System.arraycopy(mCurrPixels, 0, mPrevPixels, 0, mCurrPixels.length);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mDebugLogged) {
            mDebugLogged = true;
            android.util.Log.d("FrostDebug", "tint=" + Integer.toHexString(mTintColor)
                    + " base=" + Integer.toHexString(mBaseColor)
                    + " sheen=" + Integer.toHexString(mSheenColor)
                    + " noise=" + mNoiseAlpha + " enabled=" + mFrostEnabled
                    + " radius=" + getCornerRadiusPx()
                    + " size=" + getWidth() + "x" + getHeight()
                    + " blur=" + (mBlurBitmap != null));
        }
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            float r = getCornerRadiusPx();
            mClipPath.rewind();
            mClipPath.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW);

            canvas.save();
            canvas.clipPath(mClipPath);

            if (mBlurBitmap != null && !mBlurBitmap.isRecycled()) {
                mDstRect.set(0f, 0f, w, h);
                canvas.drawBitmap(mBlurBitmap, null, mDstRect, mBackdropPaint);
            } else {
                // 模糊位图尚未生成或捕获失败时，使用半透明磨砂兜底底色
                canvas.drawPath(mClipPath, mBasePaint);
            }
            // 叠加色：决定整体明暗与通透度
            canvas.drawPath(mClipPath, mTintPaint);
            // 顶部高光：玻璃边缘反光
            if (mSheenPaint.getShader() != null) {
                canvas.drawRect(0f, 0f, w, h, mSheenPaint);
            }
            // 磨砂颗粒
            if (mNoiseBitmap != null && !mNoiseBitmap.isRecycled()) {
                canvas.drawRect(0f, 0f, w, h, mNoisePaint);
            }
            canvas.restore();
        }
        super.onDraw(canvas);
    }

    @Nullable
    private View resolveBackdrop() {
        if (mBackdropId != View.NO_ID) {
            View root = getRootView();
            View v = root != null ? root.findViewById(mBackdropId) : null;
            if (v != null && v != this) {
                return v;
            }
        }
        // 兜底：取父布局中第一个不是自己的子 View（通常是内容容器）
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != this) {
                    return child;
                }
            }
        }
        return null;
    }

    private float getCornerRadiusPx() {
        if (mCornerRadiusPx >= 0f) {
            return mCornerRadiusPx;
        }
        float r = getRadius();
        if (r > 0f) {
            return r;
        }
        return 14f * getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------ 工具

    private void releaseBitmaps() {
        if (mSrcBitmap != null && !mSrcBitmap.isRecycled()) {
            mSrcBitmap.recycle();
        }
        if (mBlurBitmap != null && !mBlurBitmap.isRecycled()) {
            mBlurBitmap.recycle();
        }
        mSrcBitmap = null;
        mBlurBitmap = null;
        mSrcCanvas = null;
        mBlurA = null;
        mBlurB = null;
        mPrevPixels = null;
        mCurrPixels = null;
    }

    /**
     * 噪点图：黑白点各半且透明度随机，叠加后均值接近中性，
     * 不会整体压暗或提亮，只留下一层磨砂颗粒感。
     */
    private static Bitmap createNoiseBitmap(int size) {
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] px = new int[size * size];
        Random random = new Random(20240904L);
        for (int i = 0; i < px.length; i++) {
            int v = random.nextBoolean() ? 255 : 0;
            int alpha = random.nextInt(100);
            px[i] = Color.argb(alpha, v, v, v);
        }
        bm.setPixels(px, 0, size, 0, 0, size, size);
        return bm;
    }

    /** 分离式方框模糊 ×2，近似高斯，纯 Java 实现，全版本可用 */
    private static void fastBlur(Bitmap src, Bitmap dst, int[] bufA, int[] bufB, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        src.getPixels(bufA, 0, w, 0, 0, w, h);
        if (radius < 1) {
            dst.setPixels(bufA, 0, w, 0, 0, w, h);
            return;
        }
        boxPass(bufA, bufB, w, h, radius, true);
        boxPass(bufB, bufA, w, h, radius, false);
        boxPass(bufA, bufB, w, h, radius, true);
        boxPass(bufB, bufA, w, h, radius, false);
        dst.setPixels(bufA, 0, w, 0, 0, w, h);
    }

    private static void boxPass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h;          // 模糊轴长度
        int m = horizontal ? h : w;          // 线条数量
        float scale = 1f / (2 * r + 1);
        int winStart = -r;
        int winEnd = r;

        for (int line = 0; line < m; line++) {
            int base = horizontal ? line * w : line;
            int step = horizontal ? 1 : w;

            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = winStart; k <= winEnd; k++) {
                int c = in[base + clampIdx(k, n) * step];
                sa += (c >>> 24);
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = ((int) (sa * scale) << 24)
                        | ((int) (sr * scale) << 16)
                        | ((int) (sg * scale) << 8)
                        | (int) (sb * scale);

                int cOut = in[base + clampIdx(i - r, n) * step];
                int cIn = in[base + clampIdx(i + r + 1, n) * step];
                sa += (cIn >>> 24) - (cOut >>> 24);
                sr += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                sg += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                sb += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static int clampIdx(int k, int n) {
        if (k < 0) {
            return 0;
        }
        return k >= n ? n - 1 : k;
    }

    // ------------------------------------------------------------------ 对外开放

    /** 运行时开关实时磨砂（关闭后退化为纯半透明色块） */
    public void setFrostEnabled(boolean enabled) {
        if (mFrostEnabled == enabled) {
            return;
        }
        mFrostEnabled = enabled;
        if (!enabled) {
            releaseBitmaps();
        }
        invalidate();
    }

    public void setFrostTint(int color) {
        mTintColor = color;
        mTintPaint.setColor(color);
        invalidate();
    }

    /** 内容变化后强制刷新一次（如 Fragment 切换） */
    public void refreshFrost() {
        mLastCaptureAt = 0L;
        if (captureBackdrop()) {
            invalidate();
        }
    }
}
