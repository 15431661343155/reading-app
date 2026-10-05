package com.example.myapplication.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.activity.ReadActivity;

/**
 * 目录抽屉的快速滑动把手：一枚磨砂胶囊，里面是白色 ▲／两条横杠／▼。
 *
 * <p>只做拖拽把手 —— 上下箭头是纯装饰，不可点。胶囊高度固定，不像系统滚动条那样
 * 按「可视比例」伸缩：目录动辄上千章，按比例算出来的高度塞不下这三个字形。</p>
 *
 * <p>{@code recyclerview-fastscroll} 没有替换滑块绘制的钩子，所以列表侧用
 * {@code setThumbEnabled(false)} 关掉自带滑块（它等价于关掉整个 fast-scroll），
 * 位置与拖拽全部由本类按 {@code computeVerticalScroll*} 自己算。</p>
 */
public class TocScrollThumbView extends View {

    /** 胶囊本体 16×38dp；左边再留 8dp 命中区，手指不必精准压在那 16dp 上。 */
    private static final float CAP_W_DP = 16f;
    private static final float CAP_H_DP = 38f;
    private static final float SLOP_DP = 8f;

    /**
     * 轨道底部留白：右下角「定位当前章节」圆盘占 44dp + 16dp 下边距，
     * 滚到末章时胶囊若走到底会压在它上面，所以轨道提前 64dp 收住。
     */
    private static final float TRACK_BOTTOM_INSET_DP = 64f;

    private static final int FILL_DAY = 0x1F000000;     // 12% 黑，压在浅底上
    private static final int FILL_NIGHT = 0x26FFFFFF;   // 15% 白，深底才看得见
    private static final int GLYPH_COLOR = 0xFFFFFFFF;

    private static final long HIDE_DELAY_MS = 2000;
    private static final long HIDE_FADE_MS = 180;

    /** 参考图在 48px 胶囊宽下量到的字形尺寸，按实际胶囊宽等比缩放。 */
    private static final float REF_CAP_PX = 48f;
    private static final float TRI_W = 16f, TRI_H = 11f;
    private static final float BAR_W = 30f, BAR_H = 3f;
    private static final float GROUP_H = 43f;
    private static final float BAR1_Y = 17f, BAR2_Y = 24f, TRI2_Y = 32f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trianglePath = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RecyclerView target;
    private float density = 1f;
    private float capW, capH;
    private boolean dragging;
    private float downRawY, startTranslationY;

    private final Runnable hideRunnable = () -> {
        animate().alpha(0f).setDuration(HIDE_FADE_MS)
                // 收起来必须是 GONE：INVISIBLE 的 View 照样参与命中测试，
                // 会在章节行右侧留一条吃掉点击的死区。
                .withEndAction(() -> setVisibility(GONE))
                .start();
    };

    public TocScrollThumbView(Context context) {
        this(context, null);
    }

    public TocScrollThumbView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TocScrollThumbView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        density = context.getResources().getDisplayMetrics().density;
        capW = CAP_W_DP * density;
        capH = CAP_H_DP * density;
        fillPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setColor(GLYPH_COLOR);
        setAlpha(0f);
        setVisibility(GONE);
    }

    /** 挂到目录列表上：列表滚动即现身，静止 2 秒后淡出（与库原来的 autoHide 节奏一致）。 */
    public void attachTo(RecyclerView rv) {
        target = rv;
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView r, int dx, int dy) {
                if (dragging || dy == 0) return;
                // 打开抽屉时把当前章钉到首行也是一次 onScrolled，那种程序性跳转不该让把手蹦出来
                if (r.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) return;
                showNow();
                updatePosition();
            }
        });
        rv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updatePosition());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.round(capW + SLOP_DP * density);
        int h = Math.round(capH);
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updatePosition();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 抽屉底色由阅读器背景派生，日/夜要换填充色，所以每帧按当前底色重挑一次
        int chrome = ReadActivity.getChromeBgColor();
        float lum = 0.299f * Color.red(chrome) + 0.587f * Color.green(chrome) + 0.114f * Color.blue(chrome);
        fillPaint.setColor(lum < 128f ? FILL_NIGHT : FILL_DAY);

        float right = getWidth();
        float left = right - capW;
        float top = (getHeight() - capH) / 2f;
        canvas.drawRoundRect(left, top, right, top + capH, capW / 2f, capW / 2f, fillPaint);

        float s = capW / REF_CAP_PX;
        float cx = left + capW / 2f;
        float groupTop = top + (capH - GROUP_H * s) / 2f;
        trianglePath.reset();
        trianglePath.moveTo(cx, groupTop);
        trianglePath.lineTo(cx - TRI_W * s / 2f, groupTop + TRI_H * s);
        trianglePath.lineTo(cx + TRI_W * s / 2f, groupTop + TRI_H * s);
        trianglePath.close();
        canvas.drawPath(trianglePath, glyphPaint);
        canvas.drawRect(cx - BAR_W * s / 2f, groupTop + BAR1_Y * s,
                cx + BAR_W * s / 2f, groupTop + BAR1_Y * s + BAR_H * s, glyphPaint);
        canvas.drawRect(cx - BAR_W * s / 2f, groupTop + BAR2_Y * s,
                cx + BAR_W * s / 2f, groupTop + BAR2_Y * s + BAR_H * s, glyphPaint);
        trianglePath.reset();
        trianglePath.moveTo(cx - TRI_W * s / 2f, groupTop + TRI2_Y * s);
        trianglePath.lineTo(cx + TRI_W * s / 2f, groupTop + TRI2_Y * s);
        trianglePath.lineTo(cx, groupTop + TRI2_Y * s + TRI_H * s);
        trianglePath.close();
        canvas.drawPath(trianglePath, glyphPaint);
    }

    private void showNow() {
        handler.removeCallbacks(hideRunnable);
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(1f);
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, HIDE_DELAY_MS);
    }

    /** 把手可走的距离（父容器内，从当前 layout 位置算起，底部让开定位圆盘）。 */
    private float trackRange() {
        if (!(getParent() instanceof View)) return 0f;
        int parentH = ((View) getParent()).getHeight();
        return Math.max(0f, parentH - TRACK_BOTTOM_INSET_DP * density - getTop() - getHeight());
    }

    private LinearLayoutManager layoutManager() {
        if (target == null) return null;
        RecyclerView.LayoutManager lm = target.getLayoutManager();
        return lm instanceof LinearLayoutManager ? (LinearLayoutManager) lm : null;
    }

    /** 首行还允许停在哪一个下标（总数减去一屏能显示的条数）；为 0 表示整页放得下、不需要把手。 */
    private int maxFirstPosition() {
        LinearLayoutManager lm = layoutManager();
        RecyclerView.Adapter<?> ad = target == null ? null : target.getAdapter();
        if (lm == null || ad == null) return 0;
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first < 0 || last <= first) return 0;
        return Math.max(0, ad.getItemCount() - 1 - (last - first));
    }

    /**
     * 当前进度，用「首行下标 + 该行的滚动零头」算，不用 {@code computeVerticalScrollRange}：
     * 后者按已测量条目的均高外推整列表高度，目录里长短行混着时估不准，
     * 拖拽时会出现把手跟手指走了、列表却没跟上的情况。
     */
    private float progress() {
        LinearLayoutManager lm = layoutManager();
        int max = maxFirstPosition();
        if (lm == null || max <= 0) return 0f;
        int first = lm.findFirstVisibleItemPosition();
        if (first < 0) first = 0;
        View child = lm.findViewByPosition(first);
        float within = 0f;
        if (child != null && child.getHeight() > 0) {
            within = clamp((float) -child.getTop() / child.getHeight(), 0f, 1f);
        }
        return clamp((first + within) / (float) max, 0f, 1f);
    }

    private void updatePosition() {
        if (target == null || dragging) return;
        setTranslationY(progress() * trackRange());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        LinearLayoutManager lm = layoutManager();
        int max = maxFirstPosition();
        if (lm == null || max <= 0 || trackRange() <= 0f) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = true;
                downRawY = event.getRawY();
                startTranslationY = getTranslationY();
                showNow();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float track = trackRange();
                float t = clamp(startTranslationY + (event.getRawY() - downRawY), 0f, track);
                setTranslationY(t);
                lm.scrollToPositionWithOffset(Math.round(t / track * max), 0);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                updatePosition();
                scheduleHide();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
