package com.example.myapplication.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.myapplication.R;
import com.google.android.material.card.MaterialCardView;

import java.util.Random;

/**
 * 实时磨砂玻璃容器：采样背后指定内容（frostBackdrop）做模糊，叠加 tint / sheen / 颗粒，
 * 形成 iOS 风格的毛玻璃底栏。磨砂层每帧在 OnPreDraw 时重采样背后内容（节流 33ms）。
 *
 * <p>切换 tab 的淡入淡出 + 横移动画期间，背后内容剧烈变化会导致模糊亮度局部起伏，
 * 表现为一条近白长条闪过。{@link #setCaptureFrozen(boolean)} 可在动画期间冻结采样、
 * 动画结束后解冻，消除该闪条。
 *
 * <pre>
 * app:frostBackdrop      背景采样源：采集该 View 位于本控件下方的区域做模糊
 * app:frostTint          磨砂叠加色（决定通透度，alpha 越小越透）
 * app:frostBaseColor     采样区底色：背后内容透明时的兜底填充
 * app:frostSheen         顶部玻璃高光色
 * app:frostBlurRadius    模糊半径（dp）
 * app:frostDownscale     降采样倍率，越大越快、越糊
 * app:frostNoiseAlpha    磨砂颗粒强度 0~255
 * app:frostCornerRadius  圆角（缺省时跟随 cardCornerRadius）
 * app:frostEnabled       关闭实时模糊时退化为纯半透明色块
 * 描边 / 投影沿用 MaterialCardView 的 strokeColor / strokeWidth / cardElevation
 * </pre>
 */
public class FrostedNavCardView extends MaterialCardView {

    private static final long MIN_CAPTURE_INTERVAL_MS = 33L;
    private static final int NOISE_SIZE = 128;

    private final Path mClipPath = new Path();
    private final RectF mDstRect = new RectF();
    private final Paint mBackdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    private int mBackdropId;
    private int mTintColor = 0x40FFFFFF;
    private int mBaseColor = 0x80F2F2F7;
    private int mSheenColor = 0x1AFFFFFF;
    private int mDownscale = 3;
    private float mBlurRadiusPx = 16f;
    private int mNoiseAlpha = 55;
    private float mCornerRadiusPx = -1f;
    private boolean mFrostEnabled = true;

    private long mLastCaptureAt = 0;
    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;
    private boolean mDebugLogged = false;
    private boolean mCaptureFrozen = false;

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
        mBackdropId = a.getResourceId(R.styleable.FrostedNavCardView_frostBackdrop, 0);
        mTintColor = a.getColor(R.styleable.FrostedNavCardView_frostTint, mTintColor);
        mBaseColor = a.getColor(R.styleable.FrostedNavCardView_frostBaseColor, mBaseColor);
        mSheenColor = a.getColor(R.styleable.FrostedNavCardView_frostSheen, mSheenColor);
        mBlurRadiusPx = a.getDimension(R.styleable.FrostedNavCardView_frostBlurRadius, mBlurRadiusPx);
        mDownscale = a.getInt(R.styleable.FrostedNavCardView_frostDownscale, mDownscale);
        mNoiseAlpha = a.getInt(R.styleable.FrostedNavCardView_frostNoiseAlpha, mNoiseAlpha);
        mCornerRadiusPx = a.getDimension(R.styleable.FrostedNavCardView_frostCornerRadius, -1f);
        mFrostEnabled = a.getBoolean(R.styleable.FrostedNavCardView_frostEnabled, mFrostEnabled);
        a.recycle();

        // 卡片自身背景透明，磨砂内容由本控件绘制；保留 MaterialCardView 的圆角投影
        setCardBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(false);

        mBackdropPaint.setFilterBitmap(true);
        mBasePaint.setColor(mBaseColor);
        mTintPaint.setColor(mTintColor);
        mSheenPaint.setColor(mSheenColor);

        if (mNoiseAlpha > 0) {
            mNoiseBitmap = createNoiseBitmap(NOISE_SIZE);
            mNoisePaint.setShader(new android.graphics.BitmapShader(
                    mNoiseBitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT));
            mNoisePaint.setAlpha(mNoiseAlpha);
        }

        mPreDrawListener = () -> onBeforeDraw();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mPreDrawListener != null) {
            getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mPreDrawListener != null) {
            getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
        }
        releaseBitmaps();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        releaseBitmaps();
        if (w > 0 && h > 0) {
            int down = Math.max(1, mDownscale);
            int sw = Math.max(1, w / down);
            int sh = Math.max(1, h / down);
            mSrcBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            mBlurBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            mSrcCanvas = new Canvas(mSrcBitmap);
            int n = sw * sh;
            mBlurA = new int[n];
            mBlurB = new int[n];
            mPrevPixels = new int[n];
            mCurrPixels = new int[n];
        }
    }

    private boolean onBeforeDraw() {
        if (mCaptureFrozen) {
            return true;
        }
        if (!mFrostEnabled) {
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mLastCaptureAt < MIN_CAPTURE_INTERVAL_MS) {
            return true;
        }
        mLastCaptureAt = now;
        captureBackdrop();
        return true;
    }

    private boolean captureBackdrop() {
        View src = resolveBackdrop();
        if (src == null || mSrcBitmap == null || mBlurBitmap == null || mSrcCanvas == null) {
            return false;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        src.getLocationOnScreen(mLocSrc);
        getLocationOnScreen(mLocSelf);
        int left = mLocSelf[0] - mLocSrc[0];
        int top = mLocSelf[1] - mLocSrc[1];

        int sw = mSrcBitmap.getWidth();
        int sh = mSrcBitmap.getHeight();
        float scale = 1f / Math.max(1, mDownscale);

        mSrcCanvas.save();
        // 先缩放再平移（负偏移）：设备坐标 = (q - offset) * scale，
        // 恰好把「本控件正后方」的那块 src 区域采进降采样位图
        mSrcCanvas.scale(scale, scale);
        mSrcCanvas.translate(-left, -top);
        src.draw(mSrcCanvas);
        mSrcCanvas.restore();

        int radius = Math.max(1, (int) (mBlurRadiusPx * scale));
        fastBlur(mSrcBitmap, mBlurBitmap, mBlurA, mBlurB, radius);
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            float r = getCornerRadiusPx();
            mClipPath.rewind();
            mClipPath.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mClipPath);

            if (mFrostEnabled && mBlurBitmap != null) {
                mDstRect.set(0f, 0f, w, h);
                canvas.drawBitmap(mBlurBitmap, null, mDstRect, mBackdropPaint);
            } else {
                canvas.drawRect(0f, 0f, w, h, mBasePaint);
            }
            if (mTintColor != 0) {
                canvas.drawRect(0f, 0f, w, h, mTintPaint);
            }
            if (mSheenColor != 0) {
                canvas.drawRect(0f, 0f, w, h, mSheenPaint);
            }
            if (mNoiseBitmap != null && mNoiseAlpha > 0) {
                canvas.drawRect(0f, 0f, w, h, mNoisePaint);
            }
            canvas.restore();
        }
        // 让 MaterialCardView 继续处理：圆形描边 rim + 桌面浮起的柔和投影
        super.onDraw(canvas);
    }

    @Nullable
    private View resolveBackdrop() {
        if (mBackdropId == 0) {
            return null;
        }
        View root = getRootView();
        if (root == null) {
            return null;
        }
        return root.findViewById(mBackdropId);
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

    /** 可分离盒式模糊：先水平后垂直各一趟，结果写回 dst。 */
    private static void fastBlur(Bitmap src, Bitmap dst, int[] a, int[] b, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        src.getPixels(a, 0, w, 0, 0, w, h);
        boxPass(a, b, w, h, radius, true);
        boxPass(b, a, w, h, radius, false);
        dst.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void boxPass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int radius = r < 1 ? 1 : r;
        int window = radius * 2 + 1;
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                int rowStart = y * w;
                long aSum = 0, rSum = 0, gSum = 0, bSum = 0;
                for (int k = -radius; k <= radius; k++) {
                    int p = src[rowStart + clampIdx(k, w - 1)];
                    aSum += (p >> 24) & 0xff;
                    rSum += (p >> 16) & 0xff;
                    gSum += (p >> 8) & 0xff;
                    bSum += p & 0xff;
                }
                for (int x = 0; x < w; x++) {
                    int ai = (int) (aSum / window) & 0xff;
                    int ri = (int) (rSum / window) & 0xff;
                    int gi = (int) (gSum / window) & 0xff;
                    int bi = (int) (bSum / window) & 0xff;
                    dst[rowStart + x] = (ai << 24) | (ri << 16) | (gi << 8) | bi;
                    int pL = src[rowStart + clampIdx(x - radius, w - 1)];
                    int pR = src[rowStart + clampIdx(x + radius + 1, w - 1)];
                    aSum += ((pR >> 24) & 0xff) - ((pL >> 24) & 0xff);
                    rSum += ((pR >> 16) & 0xff) - ((pL >> 16) & 0xff);
                    gSum += ((pR >> 8) & 0xff) - ((pL >> 8) & 0xff);
                    bSum += (pR & 0xff) - (pL & 0xff);
                }
            }
        } else {
            for (int x = 0; x < w; x++) {
                long aSum = 0, rSum = 0, gSum = 0, bSum = 0;
                for (int k = -radius; k <= radius; k++) {
                    int p = src[clampIdx(k, h - 1) * w + x];
                    aSum += (p >> 24) & 0xff;
                    rSum += (p >> 16) & 0xff;
                    gSum += (p >> 8) & 0xff;
                    bSum += p & 0xff;
                }
                for (int y = 0; y < h; y++) {
                    int ai = (int) (aSum / window) & 0xff;
                    int ri = (int) (rSum / window) & 0xff;
                    int gi = (int) (gSum / window) & 0xff;
                    int bi = (int) (bSum / window) & 0xff;
                    dst[y * w + x] = (ai << 24) | (ri << 16) | (gi << 8) | bi;
                    int pT = src[clampIdx(y - radius, h - 1) * w + x];
                    int pB = src[clampIdx(y + radius + 1, h - 1) * w + x];
                    aSum += ((pB >> 24) & 0xff) - ((pT >> 24) & 0xff);
                    rSum += ((pB >> 16) & 0xff) - ((pT >> 16) & 0xff);
                    gSum += ((pB >> 8) & 0xff) - ((pT >> 8) & 0xff);
                    bSum += (pB & 0xff) - (pT & 0xff);
                }
            }
        }
    }

    private static int clampIdx(int i, int max) {
        if (i < 0) return 0;
        if (i > max) return max;
        return i;
    }

    public void setFrostEnabled(boolean enabled) {
        mFrostEnabled = enabled;
        mLastCaptureAt = 0;
        invalidate();
    }

    public void setFrostTint(int color) {
        mTintColor = color;
        mTintPaint.setColor(color);
        invalidate();
    }

    public void refreshFrost() {
        mLastCaptureAt = 0;
        invalidate();
    }

    public void setCaptureFrozen(boolean frozen) {
        mCaptureFrozen = frozen;
        if (!frozen) {
            // 解冻后立即允许重采样（绕过节流），动画结束瞬间刷新一帧真实模糊
            mLastCaptureAt = 0;
            invalidate();
        }
    }
}
