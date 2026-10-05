package com.example.myapplication.activity;

import android.view.View;
import android.widget.PopupWindow;

/**
 * 关闭时先播一段退场动画、再真正关窗的 PopupWindow。
 *
 * <p>可聚焦的 PopupWindow 会在自己的 DecorView 里吃掉返回键（DOWN 直接消费掉，
 * UP 时调用 {@code PopupWindow.dismiss()}），挂在内容视图上的 OnKeyListener 收不到任何
 * 按键事件，所以返回键只能接管 {@link #dismiss()} 本身：第一次 dismiss 换成播放
 * {@code slideOut}，动画收尾时调用 {@link #dismissNow()} 走真正的关窗。</p>
 */
class SlideOutPopupWindow extends PopupWindow {
    private Runnable slideOut;

    SlideOutPopupWindow(View content, int width, int height, boolean focusable) {
        super(content, width, height, focusable);
    }

    /** 登记一次性退场动画；它必须以 {@link #dismissNow()} 收尾。 */
    void setSlideOut(Runnable r) {
        slideOut = r;
    }

    @Override
    public void dismiss() {
        Runnable r = slideOut;
        slideOut = null;   // 只播一次：动画期间再按返回就直接关，避免窗口留在屏幕上
        if (r != null) {
            r.run();
            return;
        }
        super.dismiss();
    }

    /** 跳过退场动画直接关窗：动画收尾、页面销毁、或面板已不可用时走这里。 */
    void dismissNow() {
        slideOut = null;
        super.dismiss();
    }

    /** {@link #dismissNow()} 的静态版，供字段只按 {@link PopupWindow} 声明的地方调用。 */
    static void dismissImmediate(PopupWindow pw) {
        if (pw == null) return;
        if (pw instanceof SlideOutPopupWindow) ((SlideOutPopupWindow) pw).dismissNow();
        else pw.dismiss();
    }
}
