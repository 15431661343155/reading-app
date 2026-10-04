package com.example.myapplication.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.example.myapplication.R;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 让位式底部导航：只有选中项显示文字（图标在左、文字在右），未选中项只留图标。
 *
 * <p>两种状态之间的「让位」全部由槽位宽度重排提供：选中槽权重 {@link #SELECTED_WEIGHT}，
 * 其余三等分。胶囊本身不做横向位移，只在原位淡出/长出——横向滑动的胶囊会从相邻图标上切过去，
 * 留下半灰半白的脏帧；旧胶囊若同时缩窄，中途会露出「小蓝圆里塞个黑图标」的过渡态，
 * 所以退场一律保持满宽只做淡出。
 *
 * <p>文字要等胶囊长到 {@link #LABEL_REVEAL_START} 之后才显露，否则半个汉字会被胶囊左缘切住。
 * 首尾槽位还需按宿主卡片圆角留出横向余量（{@link #clearanceFor}），胶囊不能顶进圆角。
 *
 * <pre>
 * app:yieldMenu         tab 定义，沿用 menu 资源（android:id / android:title / android:icon）
 * app:yieldCornerRadius 宿主卡片圆角，用于算首尾槽位胶囊的横向上限
 * </pre>
 */
public class YieldBottomNav extends ViewGroup {

    public interface OnTabSelectedListener {
        /** 选中项变化；主页恢复上次停留的 tab 时也回调一次，那一次没有动画 */
        void onTabSelected(int itemId);

        /** 点击了当前已选中的 tab */
        void onTabReselected(int itemId);
    }

    /** 让位动画时长与曲线：FastOutSlowIn，与定稿效果图一致 */
    private static final long DURATION_MS = 300L;
    /** 选中槽权重，剩余由其他槽平分；让位量全部来自槽位重排 */
    private static final float SELECTED_WEIGHT = 0.32f;
    /** 文字显露起点：胶囊长到这个比例之后文字才开始淡入 */
    private static final float LABEL_REVEAL_START = 0.72f;
    private static final float LABEL_REVEAL_SPAN = 1f - LABEL_REVEAL_START;

    private static final float BAR_HEIGHT_DP = 60f;
    private static final float ICON_DP = 18f;
    private static final float PILL_HEIGHT_DP = 40f;
    private static final float PILL_PAD_X_DP = 8f;
    private static final float LABEL_GAP_DP = 5f;
    private static final float TEXT_SIZE_SP = 12f;
    /** 按下回弹手感，与原先作用在 Material item 上的缩放一致 */
    private static final float PRESS_SCALE = 0.92f;
    private static final long PRESS_DOWN_MS = 60L;
    private static final long PRESS_UP_MS = 260L;

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private final List<Tab> tabs = new ArrayList<>();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float density;
    private final float iconSize;
    private final float pillHeight;
    private final float pillPadX;
    private final float labelGap;
    private final int colorNormal;
    private final int colorSelected;
    private final int pillTop;
    private final int pillCenter;
    private final int pillBottom;

    /** 动画起点快照：中途改点选时从当前帧继续，不回静止态重播 */
    private float[] weightFrom = new float[0];
    private float[] alphaFrom = new float[0];
    /** 胶囊宽度过渡量起点：入场从图标宽长起，退场保持满宽只做淡出 */
    private float[] growFrom = new float[0];

    private float cornerRadius;
    private OnTabSelectedListener listener;
    private ValueAnimator animator;
    private int selectedIndex = -1;
    /** 首次落选中态（冷启动恢复）只对齐状态、不播动画 */
    private boolean primed = false;

    public YieldBottomNav(@NonNull Context context) {
        this(context, null);
    }

    public YieldBottomNav(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        iconSize = dp(ICON_DP);
        pillHeight = dp(PILL_HEIGHT_DP);
        pillPadX = dp(PILL_PAD_X_DP);
        labelGap = dp(LABEL_GAP_DP);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextSize(TEXT_SIZE_SP * scaledDensity);

        colorNormal = ContextCompat.getColor(context, R.color.nav_item_normal);
        colorSelected = ContextCompat.getColor(context, R.color.nav_item_selected);

        int[] pillAttrs = {R.attr.appNavPillTop, R.attr.appNavPillCenter, R.attr.appNavPillBottom};
        TypedArray theme = context.obtainStyledAttributes(pillAttrs);
        try {
            pillTop = theme.getColor(0, Color.WHITE);
            pillCenter = theme.getColor(1, Color.WHITE);
            pillBottom = theme.getColor(2, Color.WHITE);
        } finally {
            theme.recycle();
        }

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.YieldBottomNav);
        try {
            int menuRes = a.getResourceId(R.styleable.YieldBottomNav_yieldMenu, 0);
            cornerRadius = a.getDimension(R.styleable.YieldBottomNav_yieldCornerRadius, 0f);
            if (menuRes != 0) {
                inflateMenu(context, menuRes);
            }
        } finally {
            a.recycle();
        }
        initSlotWeights();
    }

    private float dp(float value) {
        return value * density;
    }

    // ── menu ────────────────────────────────────────────────────────────────

    private void inflateMenu(Context context, int menuRes) {
        XmlResourceParser parser = context.getResources().getXml(menuRes);
        try {
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG || !"item".equals(parser.getName())) {
                    continue;
                }
                int id = parser.getAttributeResourceValue(ANDROID_NS, "id", 0);
                if (id == 0) {
                    continue;
                }
                int iconRes = parser.getAttributeResourceValue(ANDROID_NS, "icon", 0);
                String title = parser.getAttributeValue(ANDROID_NS, "title");
                Drawable icon = iconRes == 0 ? null : AppCompatResources.getDrawable(context, iconRes);
                tabs.add(new Tab(context, id, title == null ? "" : title, icon));
            }
        } catch (Exception ignored) {
            // menu 解析失败就留空导航，不阻断主页创建
        } finally {
            parser.close();
        }
        for (Tab tab : tabs) {
            addView(tab);
        }
    }

    private void initSlotWeights() {
        int count = tabs.size();
        weightFrom = new float[count];
        alphaFrom = new float[count];
        growFrom = new float[count];
        for (int i = 0; i < count; i++) {
            tabs.get(i).weight = 1f / count;
            weightFrom[i] = tabs.get(i).weight;
        }
    }

    // ── public api ──────────────────────────────────────────────────────────

    public void setOnTabSelectedListener(OnTabSelectedListener listener) {
        this.listener = listener;
    }

    /** 切换选中项；第一次调用（主页恢复上次 tab）只落状态不播动画 */
    public void setSelectedItemId(int itemId) {
        int index = indexOf(itemId);
        if (index < 0 || index == selectedIndex) {
            return;
        }
        select(index);
    }

    private int indexOf(int itemId) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id == itemId) {
                return i;
            }
        }
        return -1;
    }

    private void select(int index) {
        int previous = selectedIndex;
        selectedIndex = index;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).fadingOut = i == previous;
        }
        // 尺寸未知（onCreate 恢复上次停留的 tab）时只能直接对齐静止态
        if (primed && getWidth() > 0) {
            startTransition();
        } else {
            snapTo();
        }
        primed = true;
        if (listener != null) {
            listener.onTabSelected(tabs.get(index).id);
        }
    }

    private void handleTabClick(Tab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) {
            return;
        }
        if (index == selectedIndex) {
            if (listener != null) {
                listener.onTabReselected(tab.id);
            }
            return;
        }
        select(index);
    }

    // ── layout ──────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize(Math.round(dp(BAR_HEIGHT_DP)), heightMeasureSpec);
        setMeasuredDimension(width, height);
        for (Tab tab : tabs) {
            measureSlot(tab, width, height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (animator == null) {
            layoutSlots();
        }
    }

    /** 按当前权重落位每个槽，并按卡片圆角算出该槽胶囊可画的最大宽度。 */
    private void layoutSlots() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int count = tabs.size();
        float clear = clearanceFor(height);
        float cursor = 0f;
        for (int i = 0; i < count; i++) {
            Tab tab = tabs.get(i);
            int left = Math.round(cursor);
            int right = i == count - 1 ? width : Math.round(cursor + width * tab.weight);
            cursor += width * tab.weight;
            float center = (left + right) / 2f;
            // 圆角处能用的半宽，再乘 2 得胶囊宽度上限；至少容得下「只有图标」那版
            tab.maxPillWidth = Math.max(iconSize + 2f * pillPadX,
                    2f * Math.min(center - clear, width - clear - center));
            measureSlot(tab, width, height);
            tab.layout(left, 0, right, height);
            tab.invalidate();
        }
    }

    private void measureSlot(Tab tab, int width, int height) {
        int slotWidth = Math.max(0, Math.round(width * tab.weight));
        tab.measure(MeasureSpec.makeMeasureSpec(slotWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    /**
     * 圆角留白：胶囊上下各空出 d，卡片圆弧在这一行向内收 {@code clear}。
     * 胶囊半宽必须小于「槽中心到收边后边缘」，否则首尾槽的胶囊会顶进圆角。
     */
    private float clearanceFor(int barHeight) {
        if (cornerRadius <= 0f) {
            return 0f;
        }
        float d = (barHeight - pillHeight) / 2f;
        float inner = cornerRadius * cornerRadius - (cornerRadius - d) * (cornerRadius - d);
        return inner <= 0f ? 0f : cornerRadius - (float) Math.sqrt(inner);
    }

    // ── animation ───────────────────────────────────────────────────────────

    private void snapTo() {
        stopAnimator();
        for (int i = 0; i < tabs.size(); i++) {
            weightFrom[i] = targetWeight(i);
            alphaFrom[i] = i == selectedIndex ? 1f : 0f;
            growFrom[i] = i == selectedIndex ? 1f : 0f;
        }
        interpolate(1f);
        layoutSlots();
        invalidate();
    }

    private void startTransition() {
        stopAnimator();
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            weightFrom[i] = tab.weight;
            alphaFrom[i] = tab.alpha;
            // 已经看不见的胶囊，宽度重新从「只有图标」起算；正在淡出的保持满宽
            growFrom[i] = tab.alpha <= 0.001f ? 0f : tab.grow;
        }
        ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        next.setDuration(DURATION_MS);
        next.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        next.addUpdateListener(animation -> {
            interpolate((float) animation.getAnimatedFraction());
            layoutSlots();
        });
        animator = next;
        next.start();
    }

    /** 逐帧插值：槽权重承载让位，alpha 承载淡入淡出，grow 承载胶囊由图标宽长到满宽。 */
    private void interpolate(float e) {
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            boolean selected = i == selectedIndex;
            tab.weight = lerp(weightFrom[i], targetWeight(i), e);
            tab.alpha = lerp(alphaFrom[i], selected ? 1f : 0f, e);
            // 退场保持满宽（只有新选中的才需要长宽），入场 0→1
            tab.grow = lerp(growFrom[i], selected || alphaFrom[i] > 0.001f ? 1f : 0f, e);
        }
    }

    private float targetWeight(int index) {
        int count = tabs.size();
        if (index == selectedIndex) {
            return SELECTED_WEIGHT;
        }
        return count > 1 ? (1f - SELECTED_WEIGHT) / (count - 1) : 1f;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private void stopAnimator() {
        if (animator != null) {
            ValueAnimator running = animator;
            animator = null;
            running.cancel();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    // ── drawing ─────────────────────────────────────────────────────────────

    private void drawPill(Canvas canvas, float cx, float cy, float width, float alpha) {
        if (alpha <= 0.004f || width <= 0f) {
            return;
        }
        float radius = pillHeight / 2f;
        int a = Math.round(255f * alpha);
        float left = cx - width / 2f;
        float right = cx + width / 2f;

        // 竖向渐变上亮下暗，做出凸起的立体感
        pillPaint.setShader(new LinearGradient(0f, cy - radius, 0f, cy + radius,
                new int[]{pillTop, pillCenter, pillBottom}, new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP));
        pillPaint.setAlpha(a);
        rect.set(left, cy - radius, right, cy + radius);
        canvas.drawRoundRect(rect, radius, radius, pillPaint);
    }

    private void drawContent(Canvas canvas, Tab tab, float cx, float cy, float tint, float reveal) {
        // 图文整体居中：文字淡入的同时图标左移让位，图标始终停在胶囊视觉中心附近
        float groupWidth = iconSize + (labelGap + tab.labelWidth) * reveal;
        float left = cx - groupWidth / 2f;

        Drawable drawable = tab.drawable;
        if (drawable != null) {
            drawable.setColorFilter(ColorUtils.blendARGB(colorNormal, colorSelected, tint),
                    PorterDuff.Mode.SRC_IN);
            drawable.setBounds(Math.round(left), Math.round(cy - iconSize / 2f),
                    Math.round(left + iconSize), Math.round(cy + iconSize / 2f));
            drawable.draw(canvas);
        }

        if (reveal > 0.01f) {
            textPaint.setColor(ColorUtils.blendARGB(colorNormal, colorSelected, tint));
            textPaint.setAlpha(Math.round(255f * reveal));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(tab.title, left + iconSize + labelGap,
                    cy - (fm.ascent + fm.descent) / 2f, textPaint);
        }
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : Math.min(1f, value);
    }

    // ── tab ─────────────────────────────────────────────────────────────────

    /**
     * 一个槽位，自己画胶囊 + 图标 + 文字。视图宽度等于槽宽，所以硬件缩放的中心正落在胶囊中心，
     * 按下回弹的手感和原先缩放 Material item 一致。
     */
    private class Tab extends View {

        final int id;
        final String title;
        @Nullable
        final Drawable drawable;
        final float labelWidth;

        float weight;
        float alpha;
        float grow;
        boolean fadingOut;
        float maxPillWidth = Float.MAX_VALUE;

        Tab(@NonNull Context context, int id, @NonNull String title, @Nullable Drawable drawable) {
            super(context);
            this.id = id;
            this.title = title;
            this.drawable = drawable == null ? null : drawable.mutate();
            this.labelWidth = textPaint.measureText(title);
            setContentDescription(title);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> handleTabClick(this));
            setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            view.animate().cancel();
                            view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                                    .setDuration(PRESS_DOWN_MS)
                                    .setInterpolator(new AccelerateInterpolator()).start();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            view.animate().cancel();
                            view.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(PRESS_UP_MS)
                                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
                            break;
                        default:
                            break;
                    }
                    // 不消费：缩放只是反馈，点选交给 View 自己的 click
                    return false;
                }
            });
        }

        /** 只放图标时的胶囊宽度，也是入场动画的起点宽度 */
        private float iconOnlyPillWidth() {
            return Math.min(iconSize + 2f * pillPadX, maxPillWidth);
        }

        /** 图标 + 文字的满宽 */
        private float fullPillWidth() {
            return Math.min(iconSize + labelGap + labelWidth + 2f * pillPadX, maxPillWidth);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float min = iconOnlyPillWidth();
            float full = fullPillWidth();

            float pillWidth = min + (full - min) * grow;
            float pillAlpha;
            float reveal;
            float tint;
            if (fadingOut) {
                // 退场：满宽淡出，墨色随胶囊一起回到未选中色
                pillAlpha = (float) Math.pow(alpha, 1.4);
                reveal = pillAlpha;
                tint = pillAlpha;
            } else {
                pillAlpha = alpha;
                // 文字只吃胶囊长度的最后一段，避免半个字被左缘切住
                reveal = clamp01((grow - LABEL_REVEAL_START) / LABEL_REVEAL_SPAN);
                // 图标提前转白：胶囊还没铺满时白图标压在浅色底上已经不刺眼，反过来会显脏
                tint = (float) Math.pow(alpha, 0.45);
            }

            drawPill(canvas, cx, cy, pillWidth, pillAlpha);
            drawContent(canvas, this, cx, cy, tint, reveal);
        }
    }
}
