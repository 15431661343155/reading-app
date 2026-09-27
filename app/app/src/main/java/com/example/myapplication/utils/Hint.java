package com.example.myapplication.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.myapplication.R;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;

/**
 * 应用内统一提示（替代系统 Toast）。
 *
 * 设计要点：
 * 1. 质感：深色半透明圆角卡片 + 细描边 + 阴影，浅色/深色主题下都清晰可读。
 * 2. 动效：进场「轻微上移 + 淡入」，停留足够时长后「上移淡出」平滑退场。
 * 3. 不叠罗汉：同一时刻只显示一条。排队期间来了新提示，当前这条会先移开让位，
 *    退场动画结束后下一条才进场，绝不会多条挤在同一位置（相同文案还会自动合并）。
 * 4. 载体：卡片挂在当前页面 DecorView 的最上层，且该容器完全不参与触摸分发，
 *    因此提示显示期间用户对页面的点击照常生效（这是选它而非 Dialog 的原因：
 *    Dialog 窗口会吃掉其覆盖区域的点击，PopupWindow 又会被页面自带的 Dialog 盖住）。
 * 5. 兜底：页面失去焦点说明有 Dialog / 弹窗盖在最上层（卡片会被遮住），
 *    此时退回系统 Toast —— 宁可样式降级，也不能让用户看不到提示。
 * 6. 跨页接棒：页面切换 / 销毁时，未显示完的提示会转交给当前可见页面继续显示，
 *    避免"点击后立刻跳页"导致提示随页面一起消失。
 * 7. 容错：任何异常都不影响调用方业务。
 *
 * 用法：把原来的 Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() 换成
 *      Hint.show(ctx, msg)；LENGTH_LONG 换成 Hint.showLong(ctx, msg)。
 */
public final class Hint {

    /** 短提示停留时长（毫秒） */
    private static final long DURATION_SHORT = 2000L;
    /** 长提示停留时长（毫秒） */
    private static final long DURATION_LONG = 3600L;
    /** 被新提示顶掉前的最短展示时长：确保「让位」不会让前一条一闪而过 */
    private static final long MIN_VISIBLE = 1000L;
    /** 进场动画时长 */
    private static final long ENTER_DURATION = 300L;
    /** 退场动画时长 */
    private static final long EXIT_DURATION = 240L;
    /** 进场起始下移量 */
    private static final float ENTER_OFFSET_DP = 30f;
    /** 退场结束时上移量 */
    private static final float EXIT_OFFSET_DP = 22f;
    /** 卡片距系统栏（导航栏）之上的额外留白 */
    private static final int BASE_BOTTOM_DP = 26;
    /** 拿不到系统栏信息时的兜底底部留白（覆盖绝大多数机型的导航栏高度） */
    private static final int FALLBACK_BOTTOM_DP = 92;
    /** 卡片左右两侧预留空间，保证长文案不会顶到屏幕边缘 */
    private static final int WIDTH_RESERVE_DP = 88;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    /** 待显示队列（FIFO） */
    private static final ArrayDeque<Item> QUEUE = new ArrayDeque<>();

    /** 最近处于 resumed 状态的页面（弱引用，避免持有页面导致泄漏） */
    private static WeakReference<Activity> resumedActivity;
    private static boolean lifecycleHooked;

    /** 当前承载容器及其所属页面 */
    private static WeakReference<Activity> hostOwner;
    private static LinearLayout hostRoot;

    /** 当前正在展示的卡片 */
    private static View showingCard;
    private static CharSequence showingText;
    private static long shownAt;
    private static long showingDuration;
    /** 是否正在播放退场动画（此期间不再插入新卡片，避免前后两条重叠） */
    private static boolean dismissing;

    private static final Runnable DISMISS = () -> dismissCurrent();

    private Hint() {}

    // ==================== 对外 API ====================

    /** 显示一条短提示（等价于原来的 Toast.LENGTH_SHORT）。 */
    public static void show(Context context, CharSequence text) {
        enqueue(context, text, false);
    }

    /** 显示一条短提示，文案取字符串资源。 */
    public static void show(Context context, int resId) {
        enqueue(context, stringOf(context, resId), false);
    }

    /** 显示一条长提示（等价于原来的 Toast.LENGTH_LONG）。 */
    public static void showLong(Context context, CharSequence text) {
        enqueue(context, text, true);
    }

    /** 显示一条长提示，文案取字符串资源。 */
    public static void showLong(Context context, int resId) {
        enqueue(context, stringOf(context, resId), true);
    }

    // ==================== 入队与调度 ====================

    private static void enqueue(final Context context, final CharSequence rawText, final boolean longDuration) {
        final CharSequence text = rawText == null ? "" : rawText;
        runOnMain(() -> {
            try {
                Activity activity = pickActivity(context);
                if (activity == null) {
                    // 没有可用页面：清掉残留队列（页面已不存在，留着只会过期），退回系统 Toast 保住本条
                    QUEUE.clear();
                    detachHost();
                    fallbackToast(context, text, longDuration);
                    return;
                }
                hookLifecycle(activity.getApplicationContext());

                // 页面失去焦点通常意味着有 Dialog / 弹窗盖在上层（卡片会被遮住），
                // 此时退回系统 Toast：宁可样式降级，也不能让用户看不到提示。
                if (!activity.hasWindowFocus()) {
                    fallbackToast(activity, text, longDuration);
                    return;
                }

                // 去重：同样的文案正在显示或已在排队时忽略，避免连点堆出一长串重复提示
                if (isDuplicate(text)) {
                    return;
                }
                QUEUE.addLast(new Item(text, longDuration));

                if (showingCard != null) {
                    // 当前还有卡片：让它尽快让位（新提示到，前一条先移开）
                    yieldCurrent();
                } else if (!dismissing) {
                    // 退场动画进行中则什么都不做，动画结束后会自动接上下一条
                    showNext(activity);
                }
            } catch (Throwable ignored) {
                // 提示组件自身绝不影响业务
            }
        });
    }

    private static void showNext(Activity activity) {
        Item item = QUEUE.pollFirst();
        if (item == null) {
            showingCard = null;
            showingText = null;
            return;
        }
        if (!ensureHost(activity)) {
            // 宿主不可用：队列里剩下的全部交给系统 Toast（系统自身会排队）
            fallbackToast(activity, item.text, item.longDuration);
            Item rest;
            while ((rest = QUEUE.pollFirst()) != null) {
                fallbackToast(activity, rest.text, rest.longDuration);
            }
            detachHost();
            return;
        }

        View card = createCard(activity, item.text);
        hostRoot.addView(card);
        showingCard = card;
        showingText = item.text;
        showingDuration = item.remaining > 0L
                ? item.remaining
                : (item.longDuration ? DURATION_LONG : DURATION_SHORT);
        shownAt = SystemClock.uptimeMillis();

        // 进场：轻微上移 + 淡入
        card.setAlpha(0f);
        card.setTranslationY(dp(activity, ENTER_OFFSET_DP));
        card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ENTER_DURATION)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();

        scheduleDismiss(showingDuration);
    }

    private static void scheduleDismiss(long delay) {
        HANDLER.removeCallbacks(DISMISS);
        HANDLER.postDelayed(DISMISS, Math.max(0L, delay));
    }

    /** 新提示到来时让当前这条提前让位，但保证至少展示 MIN_VISIBLE。 */
    private static void yieldCurrent() {
        long elapsed = SystemClock.uptimeMillis() - shownAt;
        long wait = MIN_VISIBLE - elapsed;
        if (wait <= 0L) {
            HANDLER.removeCallbacks(DISMISS);
            dismissCurrent();
        } else {
            scheduleDismiss(wait);
        }
    }

    /** 平滑退场，动画结束后自动显示下一条。 */
    private static void dismissCurrent() {
        final View card = showingCard;
        if (card == null) {
            if (!dismissing) {
                Activity activity = pickActivity(null);
                if (activity != null) {
                    showNext(activity);
                }
            }
            return;
        }
        HANDLER.removeCallbacks(DISMISS);
        showingCard = null;
        showingText = null;
        dismissing = true;

        card.animate()
                .alpha(0f)
                .translationY(-dp(card.getContext(), EXIT_OFFSET_DP))
                .setDuration(EXIT_DURATION)
                .setInterpolator(new AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    detachView(card);
                    dismissing = false;
                    Activity activity = pickActivity(null);
                    if (activity != null) {
                        showNext(activity);
                    }
                })
                .start();
    }

    // ==================== 卡片与宿主容器 ====================

    private static View createCard(Activity activity, CharSequence text) {
        View card = LayoutInflater.from(activity).inflate(R.layout.view_hint_card, hostRoot, false);
        TextView tv = card.findViewById(R.id.tv_hint_text);
        tv.setText(text);
        int maxWidth = activity.getResources().getDisplayMetrics().widthPixels
                - dp(activity, WIDTH_RESERVE_DP);
        if (maxWidth > 0) {
            tv.setMaxWidth(maxWidth);
        }
        ViewCompat.setElevation(card, dp(activity, 8));
        return card;
    }

    /**
     * 确保承载容器挂在目标页面 DecorView 的最上层。容器只覆盖卡片自身区域
     * （宽高 wrap_content、贴屏幕底部），且完全不参与触摸分发
     * （dispatchTouchEvent 恒为 false），因此提示显示期间用户对页面的点击照常生效。
     */
    private static boolean ensureHost(Activity activity) {
        if (!isUsable(activity)) {
            return false;
        }
        if (hostRoot != null && hostOwner != null && hostOwner.get() == activity
                && hostRoot.isAttachedToWindow()) {
            return true;
        }
        detachHost();

        View decor;
        try {
            android.view.Window window = activity.getWindow();
            decor = window == null ? null : window.getDecorView();
        } catch (Throwable t) {
            decor = null;
        }
        if (!(decor instanceof ViewGroup)) {
            return false;
        }

        LinearLayout host = new LinearLayout(activity) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                // 不参与触摸分发，事件直接交给下层页面内容
                return false;
            }
        };
        host.setOrientation(LinearLayout.VERTICAL);
        host.setGravity(Gravity.CENTER_HORIZONTAL);
        host.setClipChildren(false);
        host.setClipToPadding(false);
        host.setClickable(false);
        host.setFocusable(false);
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(activity, FALLBACK_BOTTOM_DP);
        host.setLayoutParams(lp);

        try {
            ((ViewGroup) decor).addView(host);
        } catch (Throwable t) {
            return false;
        }

        hostRoot = host;
        hostOwner = new WeakReference<>(activity);
        applyInsets(activity, host);
        return true;
    }

    /** 按系统栏实际高度修正底部留白，避免卡片压在导航栏上。 */
    private static void applyInsets(Activity activity, LinearLayout host) {
        try {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(host);
            if (insets == null) {
                return;
            }
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            ViewGroup.LayoutParams lp = host.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) lp).bottomMargin = bottom + dp(activity, BASE_BOTTOM_DP);
                host.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
            // 保持兜底留白即可
        }
    }

    private static void detachHost() {
        HANDLER.removeCallbacks(DISMISS);
        detachView(hostRoot);
        hostRoot = null;
        hostOwner = null;
        showingCard = null;
        showingText = null;
        dismissing = false;
    }

    private static void detachView(View view) {
        if (view == null) {
            return;
        }
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            try {
                ((ViewGroup) parent).removeView(view);
            } catch (Throwable ignored) {
                // 宿主可能已在销毁流程中
            }
        }
    }

    // ==================== 页面生命周期 ====================

    private static void hookLifecycle(Context context) {
        if (lifecycleHooked) {
            return;
        }
        Context app = context == null ? null : context.getApplicationContext();
        if (!(app instanceof Application)) {
            return;
        }
        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                resumedActivity = new WeakReference<>(activity);
                // 页面切换：正在显示的提示要跟着搬到新页面，否则用户按了按钮跳页后什么都看不到
                if (showingCard != null) {
                    promotePendingToQueue();
                }
                // 上个页面没显示完的提示，由当前页面接棒继续显示
                if (showingCard == null && !dismissing && !QUEUE.isEmpty()) {
                    showNext(activity);
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (hostOwner == null || hostOwner.get() != activity) {
                    return;
                }
                // 页面销毁（例如"点击后立即返回/跳转"）：正在显示的那条放回队首，
                // 交给当前仍然可见的页面继续显示，避免提示随页面一起消失。
                promotePendingToQueue();
                Activity next = pickActivity(null);
                if (next != null && next != activity && !QUEUE.isEmpty()) {
                    showNext(next);
                }
            }
        });
        lifecycleHooked = true;
    }

    /** 把正在显示的提示收拢回队首（用于页面切换/销毁时的接棒），保留剩余展示时长。 */
    private static void promotePendingToQueue() {
        CharSequence pending = showingText;
        long remaining = 0L;
        if (pending != null) {
            long elapsed = SystemClock.uptimeMillis() - shownAt;
            remaining = Math.max(MIN_VISIBLE, showingDuration - elapsed);
        }
        boolean longDuration = showingDuration == DURATION_LONG;
        detachHost();
        if (pending != null) {
            QUEUE.addFirst(new Item(pending, longDuration, remaining));
        }
    }

    /**
     * 选择承载提示的页面：优先当前处于 resumed 的页面，
     * 避免「后台页面发出的提示被挂到已不可见的 Activity 上、用户根本看不到」。
     */
    private static Activity pickActivity(Context context) {
        Activity resumed = resumedActivity == null ? null : resumedActivity.get();
        boolean resumedUsable = isUsable(resumed);
        if (context instanceof Activity) {
            Activity fromContext = (Activity) context;
            // 发起者本身就是当前可见页面（或尚未记录到 resumed 页面）时直接用它
            if (isUsable(fromContext) && (!resumedUsable || resumed == fromContext)) {
                return fromContext;
            }
        }
        if (resumedUsable) {
            return resumed;
        }
        if (context instanceof Activity && isUsable((Activity) context)) {
            return (Activity) context;
        }
        return null;
    }

    private static boolean isUsable(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    // ==================== 工具方法 ====================

    private static void fallbackToast(Context context, CharSequence text, boolean longDuration) {
        try {
            Context ctx = context;
            if (ctx == null) {
                Activity activity = pickActivity(null);
                if (activity == null) {
                    return;
                }
                ctx = activity;
            }
            Toast.makeText(ctx, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 连系统 Toast 都弹不出来时静默忽略
        }
    }

    private static CharSequence stringOf(Context context, int resId) {
        Context ctx = context;
        if (ctx == null) {
            ctx = pickActivity(null);
        }
        if (ctx != null) {
            try {
                return ctx.getString(resId);
            } catch (Throwable ignored) {
                // 资源不存在时退化为空串
            }
        }
        return "";
    }

    private static boolean isDuplicate(CharSequence text) {
        if (showingCard != null && TextUtils.equals(showingText, text)) {
            return true;
        }
        for (Item item : QUEUE) {
            if (TextUtils.equals(item.text, text)) {
                return true;
            }
        }
        return false;
    }

    private static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            HANDLER.post(action);
        }
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 队列元素 */
    private static final class Item {
        final CharSequence text;
        final boolean longDuration;
        /** >0 表示沿用这个剩余时长（跨页接棒时用，避免提示因跳页被无限续命） */
        final long remaining;

        Item(CharSequence text, boolean longDuration) {
            this(text, longDuration, 0L);
        }

        Item(CharSequence text, boolean longDuration, long remaining) {
            this.text = text;
            this.longDuration = longDuration;
            this.remaining = remaining;
        }
    }
}
