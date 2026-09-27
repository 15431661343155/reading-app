package com.example.myapplication.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.example.myapplication.R;

/**
 * 全站统一的「加载失败」覆盖层：失败插画 + 灰字文案 + 蓝色胶囊「再试一次」按钮。
 *
 * <p>排版与「加载失败」效果图一致；根布局透明，透出宿主页面的背景/纹理。
 * 用法：
 * <pre>
 *   failView.setOnRetryClick(v -> reloadData());
 *   failView.show("加载出错，请稍后再试");   // 显示并替换文案
 *   failView.hide();                        // 数据恢复后收起
 * </pre>
 *
 * <p>目前用于：分类页（CategoryFragment）、书城本站子页面（LocalStorePageAdapter）、
 * 书城外站模式（BookStoreFragment）、阅读器章节加载失败（activity_read.xml 内的
 * {@code layout_load_fail} 为先落地的同款内联实现，视觉与本控件一致）。
 */
public class LoadFailView extends FrameLayout {

    private final TextView tvMsg;
    private final TextView btnRetry;

    public LoadFailView(Context context) {
        this(context, null);
    }

    public LoadFailView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LoadFailView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        inflate(context, R.layout.view_load_fail, this);
        tvMsg = findViewById(R.id.tv_fail_msg);
        btnRetry = findViewById(R.id.btn_fail_retry);
    }

    /** 设置重试按钮点击回调（不负责隐藏自身——由调用方在重试开始时按需处理）。 */
    public void setOnRetryClick(View.OnClickListener l) {
        btnRetry.setOnClickListener(l);
    }

    /** 显示失败层；msg 传 null/空串用默认文案「加载出错，请稍后再试」。 */
    public void show(CharSequence msg) {
        if (msg != null && msg.length() > 0) {
            tvMsg.setText(msg);
        }
        setVisibility(VISIBLE);
    }

    public void show() {
        show(null);
    }

    public void hide() {
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /** 自定义文案颜色（如阅读器夜间底色用 #AAAAAA）。 */
    public void setMessageColor(int color) {
        tvMsg.setTextColor(color);
    }
}
