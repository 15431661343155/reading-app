package com.example.myapplication.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 方形头像裁剪视图。
 *
 * 交互模型：图片固定居中显示（contain 适配），用户在图片上拖动一个
 * 正方形裁剪框来选择区域；裁剪框可整体拖动移动，也可通过右下角手柄
 * 或双指捏合来放大/缩小。裁剪框始终被限制在图片范围内，且保持正方形。
 *
 * 输出为裁剪框对应的正方形 Bitmap；上层（ProfileActivity）再以圆形
 * drawable 显示，因此最终头像为圆形。
 */
public class CropSquareView extends View {

    private Bitmap bitmap;

    // 图片在视图中的放置（contain 适配后的显示矩形）
    private float imgScale;
    private float imgLeft;
    private float imgTop;
    private float imgW; // 图片显示宽
    private float imgH; // 图片显示高

    // 裁剪框（视图坐标系，正方形）
    private float boxX, boxY, boxSize;
    private float minBoxSize;

    // 缩放手柄边长（px）
    private static final float HANDLE = 44f;

    // 手势模式
    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_RESIZE = 2;
    private static final int MODE_PINCH = 3;
    private int mode = MODE_NONE;
    private float lastX, lastY;
    private float lastDist;
    private float pinchBoxSize;
    private float pinchCx, pinchCy;

    // 绘制相关
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CropSquareView(Context context) {
        this(context, null);
    }

    public CropSquareView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropSquareView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(0x99000000); // 60% 黑色遮罩
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStrokeWidth(2f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(0x66FFFFFF);
        gridPaint.setStrokeWidth(1f);
        handlePaint.setStyle(Paint.Style.FILL);
    }

    /** 设置待裁剪的原始图片 */
    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        if (bmp == null) return;
        if (getWidth() > 0 && getHeight() > 0) {
            layoutImage();
            resetBox();
            invalidate();
        } else {
            // 视图尚未完成布局，等下一帧再定位
            post(() -> {
                layoutImage();
                resetBox();
                invalidate();
            });
        }
    }

    /** 计算图片在视图中的 contain 放置 */
    private void layoutImage() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float vw = getWidth();
        float vh = getHeight();
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        imgScale = Math.min(vw / bw, vh / bh);
        imgW = bw * imgScale;
        imgH = bh * imgScale;
        imgLeft = (vw - imgW) / 2f;
        imgTop = (vh - imgH) / 2f;
    }

    /** 重置裁剪框：居中、取图片短边的 80% */
    private void resetBox() {
        if (imgW <= 0 || imgH <= 0) return;
        float maxBox = maxBox();
        minBoxSize = Math.max(60f, maxBox * 0.15f);
        boxSize = Math.min(maxBox * 0.8f, maxBox);
        if (boxSize < minBoxSize) boxSize = minBoxSize;
        boxX = imgLeft + (imgW - boxSize) / 2f;
        boxY = imgTop + (imgH - boxSize) / 2f;
        clampBox();
    }

    private float maxBox() {
        return Math.min(imgW, imgH);
    }

    /** 将裁剪框限制在图片范围内（保持正方形） */
    private void clampBox() {
        float maxBox = maxBox();
        if (boxSize > maxBox) boxSize = maxBox;
        if (boxSize < minBoxSize) boxSize = minBoxSize;
        float maxX = imgLeft + imgW - boxSize;
        float maxY = imgTop + imgH - boxSize;
        if (boxX < imgLeft) boxX = imgLeft;
        if (boxX > maxX) boxX = maxX;
        if (boxY < imgTop) boxY = imgTop;
        if (boxY > maxY) boxY = maxY;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (bitmap != null) {
            layoutImage();
            resetBox();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int W = getWidth();
        int H = getHeight();

        // 1. 绘制图片（contain 适配）
        if (bitmap != null && !bitmap.isRecycled()) {
            canvas.drawBitmap(bitmap, null,
                    new RectF(imgLeft, imgTop, imgLeft + imgW, imgTop + imgH), bitmapPaint);
        }

        // 2. 遮罩：裁剪框外半透明变黑
        int sc = canvas.saveLayer(0, 0, W, H, null, Canvas.ALL_SAVE_FLAG);
        canvas.drawRect(0, 0, W, H, maskPaint);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        canvas.drawRect(boxX, boxY, boxX + boxSize, boxY + boxSize, clear);
        canvas.restoreToCount(sc);

        // 3. 三分网格线
        float gx = boxSize / 3f;
        float gy = boxSize / 3f;
        for (int i = 1; i <= 2; i++) {
            canvas.drawLine(boxX + gx * i, boxY, boxX + gx * i, boxY + boxSize, gridPaint);
            canvas.drawLine(boxX, boxY + gy * i, boxX + boxSize, boxY + gy * i, gridPaint);
        }

        // 4. 裁剪框边框
        canvas.drawRect(boxX, boxY, boxX + boxSize, boxY + boxSize, borderPaint);

        // 5. 右下角缩放手柄
        float hx = boxX + boxSize;
        float hy = boxY + boxSize;
        handlePaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(hx, hy, HANDLE * 0.5f, handlePaint);
        handlePaint.setColor(0xFF3A7BFF);
        canvas.drawCircle(hx, hy, HANDLE * 0.30f, handlePaint);
    }

    // ==================== 手势处理 ====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX();
                float y = event.getY();
                // 命中右下角缩放手柄？
                float hx = boxX + boxSize;
                float hy = boxY + boxSize;
                if (Math.abs(x - hx) <= HANDLE && Math.abs(y - hy) <= HANDLE) {
                    mode = MODE_RESIZE;
                } else if (x >= boxX && x <= boxX + boxSize && y >= boxY && y <= boxY + boxSize) {
                    mode = MODE_MOVE;
                } else {
                    mode = MODE_NONE;
                }
                lastX = x;
                lastY = y;
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (event.getPointerCount() >= 2) {
                    mode = MODE_PINCH;
                    lastDist = pointerDist(event);
                    pinchBoxSize = boxSize;
                    pinchCx = boxX + boxSize / 2f;
                    pinchCy = boxY + boxSize / 2f;
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_MOVE && event.getPointerCount() == 1) {
                    // 拖动移动裁剪框（限制在图片内）
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    boxX += dx;
                    boxY += dy;
                    lastX = event.getX();
                    lastY = event.getY();
                    clampBox();
                } else if (mode == MODE_RESIZE && event.getPointerCount() == 1) {
                    // 以左上角为锚点，按右下角指针位置决定边长（取较大者，保持正方形）
                    float x = event.getX();
                    float y = event.getY();
                    float newSize = Math.max(x - boxX, y - boxY);
                    float upper = Math.min(imgW - (boxX - imgLeft), imgH - (boxY - imgTop));
                    if (upper > maxBox()) upper = maxBox();
                    if (newSize < minBoxSize) newSize = minBoxSize;
                    if (newSize > upper) newSize = upper;
                    boxSize = newSize;
                    clampBox();
                } else if (mode == MODE_PINCH && event.getPointerCount() >= 2) {
                    // 双指捏合缩放，以捏合中心为锚点（限制在图片内）
                    float dist = pointerDist(event);
                    if (lastDist > 0) {
                        float factor = dist / lastDist;
                        float ns = pinchBoxSize * factor;
                        if (ns < minBoxSize) ns = minBoxSize;
                        if (ns > maxBox()) ns = maxBox();
                        float nx = pinchCx - ns / 2f;
                        float ny = pinchCy - ns / 2f;
                        float maxX = imgLeft + imgW - ns;
                        float maxY = imgTop + imgH - ns;
                        if (nx < imgLeft) nx = imgLeft;
                        if (nx > maxX) nx = maxX;
                        if (ny < imgTop) ny = imgTop;
                        if (ny > maxY) ny = maxY;
                        boxX = nx;
                        boxY = ny;
                        boxSize = ns;
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() <= 2) {
                    mode = MODE_NONE;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = MODE_NONE;
                return true;
        }
        return true;
    }

    private float pointerDist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ==================== 裁剪输出 ====================

    /**
     * 根据当前裁剪框，从原始 Bitmap 中截取对应正方形区域并缩放到 512×512。
     * 返回正方形 Bitmap（上层会再裁成圆形显示）。
     */
    public Bitmap getCroppedBitmap() {
        if (bitmap == null || bitmap.isRecycled() || imgScale <= 0) return null;

        float sx = (boxX - imgLeft) / imgScale;
        float sy = (boxY - imgTop) / imgScale;
        float ss = boxSize / imgScale;

        int iw = bitmap.getWidth();
        int ih = bitmap.getHeight();
        int ix = Math.max(0, Math.round(sx));
        int iy = Math.max(0, Math.round(sy));
        int is = Math.round(ss);
        if (is < 1) is = 1;
        if (ix + is > iw) is = iw - ix;
        if (iy + is > ih) is = ih - iy;
        if (is <= 0) return null;

        Bitmap region = Bitmap.createBitmap(bitmap, ix, iy, is, is);

        int OUT = 512;
        Bitmap out = Bitmap.createScaledBitmap(region, OUT, OUT, true);
        if (out != region) region.recycle();

        return out;
    }
}
