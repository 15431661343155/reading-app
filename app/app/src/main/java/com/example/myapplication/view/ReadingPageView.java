package com.example.myapplication.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.example.myapplication.bean.PageBean;
import com.example.myapplication.bean.PageBean.LineData;

/**
 * 自定义阅读页面View - 使用Canvas绘制文本
 */
public class ReadingPageView extends View {

    // 画笔
    private Paint textPaint;
    private Paint headerPaint;
    private Paint footerPaint;
    private Paint separatorPaint;

    // 当前页面数据
    private PageBean currentPage;

    // 颜色配置（iOS 浅色磨砂玻璃风）
    private int bgColor = Color.parseColor("#FFFFFF");
    private int textColor = Color.parseColor("#1D1D1F");
    private int headerColor = Color.parseColor("#8E8E93");
    private int footerColor = Color.parseColor("#8E8E93");
    private int separatorColor = Color.parseColor("#E5E5EA");

    // 尺寸配置
    private float fontSize = 18f;        // 默认字号，会被外部覆盖
    private float headerFontSize = 18f;
    private float footerFontSize = 18f;

    public ReadingPageView(Context context) {
        super(context);
        init();
    }

    public ReadingPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 正文画笔
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(textColor);
        textPaint.setTextSize(fontSize);          // 使用成员变量
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setSubpixelText(true);          // 亚像素渲染，更清晰

        // 页眉画笔
        headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(headerColor);
        headerPaint.setTextSize(headerFontSize);

        // 页脚画笔
        footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setColor(footerColor);
        footerPaint.setTextSize(footerFontSize);

        // 分隔线画笔
        separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        separatorPaint.setColor(separatorColor);
        separatorPaint.setStrokeWidth(1f);
        separatorPaint.setStyle(Paint.Style.STROKE);
    }

    /**
     * 设置页面数据
     */
    public void setPage(PageBean page) {
        this.currentPage = page;
        invalidate();
    }

    /**
     * 设置主题颜色
     */
    public void setTheme(int bgColor, int textColor, int headerFooterColor) {
        this.bgColor = bgColor;
        this.textColor = textColor;
        this.headerColor = headerFooterColor;
        this.footerColor = headerFooterColor;
        this.separatorColor = headerFooterColor;

        textPaint.setColor(textColor);
        headerPaint.setColor(headerColor);
        footerPaint.setColor(footerColor);
        separatorPaint.setColor(headerFooterColor);

        setBackgroundColor(bgColor);
        invalidate();
    }

    /**
     * 设置字号（单位sp，与Java代码中的dp一致）
     */
    public void setFontSize(float size) {
        this.fontSize = size;
        textPaint.setTextSize(size);          // 关键：更新画笔字号
        invalidate();
    }

    public void setHeaderFooterFontSize(float size) {
        this.headerFontSize = size;
        this.footerFontSize = size;
        headerPaint.setTextSize(size);
        footerPaint.setTextSize(size);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 绘制背景
        canvas.drawColor(bgColor);

        if (currentPage == null || currentPage.getLines() == null) {
            return;
        }

        // 逐行绘制
        for (LineData line : currentPage.getLines()) {
            switch (line.type) {
                case HEADER_TITLE:
                    canvas.drawText(line.text, line.x, line.y, headerPaint);
                    break;

                case HEADER_SEPARATOR:
                    float endX = Float.parseFloat(line.text);
                    canvas.drawLine(line.x, line.y, endX, line.y, separatorPaint);
                    break;

                case CONTENT:
                    canvas.drawText(line.text, line.x, line.y, textPaint);
                    break;

                case FOOTER:
                    canvas.drawText(line.text, line.x, line.y, footerPaint);
                    break;
            }
        }
    }
}