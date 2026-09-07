package com.example.myapplication.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 圆形头像裁剪视图。
 * 用户可以拖动、缩放图片，自主选择裁剪区域；
 * 中间圆形区域为裁剪框，圆外区域半透明遮罩。
 */
public class CropCircleView extends View {

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 图片变换状态 */
    private float[] matrixValues = new float[9];
    private float translateX, translateY;
    private float scale = 1f;
    private float minScale, maxScale;

    /** 手势追踪 */
    private float lastTouchX, lastTouchY;
    private float lastPointerDistance;
    private float lastPointerMidX, lastPointerMidY;
    private int pointerCount;

    /** 裁剪圆参数 */
    private float circleRadius;
    private float circleCenterX, circleCenterY;

    public CropCircleView(Context context) {
        this(context, null);
    }

    public CropCircleView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropCircleView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(0x99000000); // 60% 黑色遮罩
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStrokeWidth(2f);
    }

    /** 设置待裁剪的原始图片 */
    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        if (bmp == null) return;
        resetTransform();
        invalidate();
    }

    /** 重置图片变换：居中并缩放到刚好填满裁剪圆 */
    private void resetTransform() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;

        float viewW = getWidth();
        float viewH = getHeight();
        float bmpW = bitmap.getWidth();
        float bmpH = bitmap.getHeight();

        // 裁剪圆直径 = 视图短边的 70%
        float diameter = Math.min(viewW, viewH) * 0.7f;
        circleRadius = diameter / 2f;
        circleCenterX = viewW / 2f;
        circleCenterY = viewH / 2f;

        // 缩放使图片最短边刚好填满圆直径
        float fitScale = diameter / Math.min(bmpW, bmpH);
        scale = fitScale;
        minScale = fitScale;
        maxScale = fitScale * 4f;

        // 居中
        translateX = (viewW - bmpW * scale) / 2f;
        translateY = (viewH - bmpH * scale) / 2f;

        applyMatrix();
    }

    private void applyMatrix() {
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(translateX, translateY);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (bitmap != null) resetTransform();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. 绘制图片
        if (bitmap != null && !bitmap.isRecycled()) {
            canvas.drawBitmap(bitmap, matrix, bitmapPaint);
        }

        // 2. 绘制圆形遮罩（圆外半透明）
        int saveCount = canvas.saveLayer(0, 0, getWidth(), getHeight(), null, Canvas.ALL_SAVE_FLAG);

        // 绘制全屏遮罩
        canvas.drawRect(0, 0, getWidth(), getHeight(), maskPaint);

        // 用 CLEAR 模式挖出圆形区域
        Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        clearPaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, clearPaint);

        canvas.restoreToCount(saveCount);

        // 3. 绘制裁剪圆边框
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, borderPaint);
    }

    // ==================== 手势处理 ====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointerCount = 1;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                pointerCount = event.getPointerCount();
                if (pointerCount == 2) {
                    lastPointerDistance = getPointerDistance(event);
                    lastPointerMidX = getPointerMidX(event);
                    lastPointerMidY = getPointerMidY(event);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && pointerCount == 1) {
                    // 单指拖动
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    translateX += dx;
                    translateY += dy;
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                } else if (event.getPointerCount() >= 2) {
                    // 双指缩放
                    float newDistance = getPointerDistance(event);
                    if (lastPointerDistance > 0) {
                        float scaleFactor = newDistance / lastPointerDistance;
                        float newScale = scale * scaleFactor;
                        newScale = Math.max(minScale, Math.min(maxScale, newScale));
                        scaleFactor = newScale / scale;

                        float midX = getPointerMidX(event);
                        float midY = getPointerMidY(event);

                        // 以双指中点为中心缩放
                        translateX = midX - (midX - translateX) * scaleFactor;
                        translateY = midY - (midY - translateY) * scaleFactor;
                        scale = newScale;
                    }
                    lastPointerDistance = newDistance;
                    pointerCount = event.getPointerCount();
                }
                applyMatrix();
                invalidate();
                break;

            case MotionEvent.ACTION_POINTER_UP:
                pointerCount = event.getPointerCount() - 1;
                if (pointerCount == 1) {
                    lastTouchX = event.getX(0);
                    lastTouchY = event.getY(0);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pointerCount = 0;
                break;
        }
        return true;
    }

    private float getPointerDistance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float getPointerMidX(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) / 2f;
    }

    private float getPointerMidY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2f;
    }

    // ==================== 裁剪输出 ====================

    /**
     * 根据当前图片位置和裁剪圆，生成圆形裁剪后的 Bitmap。
     * 输出尺寸 = 裁剪圆直径（像素）。
     */
    public Bitmap getCroppedBitmap() {
        if (bitmap == null || bitmap.isRecycled()) return null;

        int diameter = (int) (circleRadius * 2);
        if (diameter <= 0) return null;

        // 计算裁剪圆在原始 Bitmap 坐标系中的区域
        Matrix inverse = new Matrix();
        matrix.invert(inverse);

        float[] circleRect = new float[]{
                circleCenterX - circleRadius, circleCenterY - circleRadius,
                circleCenterX + circleRadius, circleCenterY + circleRadius
        };
        inverse.mapPoints(circleRect);

        float srcLeft = circleRect[0];
        float srcTop = circleRect[1];
        float srcRight = circleRect[2];
        float srcBottom = circleRect[3];
        float srcWidth = srcRight - srcLeft;
        float srcHeight = srcBottom - srcTop;

        // 从原始 Bitmap 中截取对应区域
        int sx = Math.max(0, Math.round(srcLeft));
        int sy = Math.max(0, Math.round(srcTop));
        int sw = Math.min(bitmap.getWidth() - sx, Math.round(srcWidth));
        int sh = Math.min(bitmap.getHeight() - sy, Math.round(srcHeight));
        if (sw <= 0 || sh <= 0) return null;

        Bitmap region = Bitmap.createBitmap(bitmap, sx, sy, sw, sh);

        // 缩放到输出尺寸
        float scaleX = (float) diameter / sw;
        float scaleY = (float) diameter / sh;
        float outputScale = Math.max(scaleX, scaleY);

        int scaledW = Math.round(sw * outputScale);
        int scaledH = Math.round(sh * outputScale);
        Bitmap scaled = Bitmap.createScaledBitmap(region, scaledW, scaledH, true);
        if (scaled != region) region.recycle();

        // 居中裁剪为正方形
        int cropX = (scaledW - diameter) / 2;
        int cropY = (scaledH - diameter) / 2;
        cropX = Math.max(0, cropX);
        cropY = Math.max(0, cropY);
        int cropW = Math.min(diameter, scaledW - cropX);
        int cropH = Math.min(diameter, scaledH - cropY);

        Bitmap squared = Bitmap.createBitmap(scaled, cropX, cropY, cropW, cropH);
        if (squared != scaled) scaled.recycle();

        // 绘制为圆形
        int size = Math.min(squared.getWidth(), squared.getHeight());
        Bitmap circular = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(circular);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.BitmapShader(squared,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP));
        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        squared.recycle();

        return circular;
    }
}