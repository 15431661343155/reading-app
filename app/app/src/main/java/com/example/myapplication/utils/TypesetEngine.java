package com.example.myapplication.utils;

import android.graphics.Paint;

import com.example.myapplication.bean.PageBean;
import com.example.myapplication.bean.PageBean.LineData;

import java.util.ArrayList;
import java.util.List;

/**
 * 排版引擎 - 负责将章节内容分页，并计算每页每行的绘制坐标
 */
public class TypesetEngine {

    public static class Config {
        public int pageWidth;
        public int pageHeight;
        public int paddingLeft = 50;
        public int paddingRight = 50;
        public int paddingTop = 60;
        public int paddingBottom = 70;

        // 画笔
        public Paint textPaint;
        public Paint headerPaint;
        public Paint footerPaint;

        // 行间距倍数
        public float lineSpacingMultiplier = 1.6f;
        //页眉页脚开关
        public boolean showHeaderFooter = true;
    }

    private final Config config;

    public TypesetEngine(Config config) {
        this.config = config;
    }

    /**
     * 对一章内容进行排版分页
     */
    public List<PageBean> typesetChapter(int chapterIndex, String chapterTitle, String content) {
        List<PageBean> pages = new ArrayList<>();

        int contentWidth = config.pageWidth - config.paddingLeft - config.paddingRight;

        float lineHeight = config.textPaint.getFontSpacing() * config.lineSpacingMultiplier;
        float headerFontHeight = config.headerPaint.getFontSpacing();
        float footerFontHeight = config.footerPaint.getFontSpacing();

        // ========== 正文区域（根据开关动态调整） ==========
        float bodyTop;
        float bodyBottom;

        if (config.showHeaderFooter) {
            // 页眉区域
            float headerTitleY = config.paddingTop + headerFontHeight;
            float headerSeparatorY = headerTitleY + headerFontHeight * 0.8f;
            bodyTop = headerSeparatorY + headerFontHeight * 3.0f;

            // 页脚区域
            float footerY = config.pageHeight - config.paddingBottom + footerFontHeight;
            bodyBottom = footerY - footerFontHeight - lineHeight * 0.5f;
        } else {
            // 不显示页眉页脚时，正文占满整页
            bodyTop = config.paddingTop;
            bodyBottom = config.pageHeight - config.paddingBottom;
        }

        // 每页最多正文行数
        int maxLinesPerPage = Math.max(1, (int) ((bodyBottom - bodyTop) / lineHeight));

        int totalLength = content.length();
        int currentPos = 0;
        int pageNum = 0;

        while (currentPos < totalLength) {
            PageBean page = new PageBean();
            page.setChapterIndex(chapterIndex);
            page.setPageIndexInChapter(pageNum);
            page.setChapterTitle(chapterTitle);
            page.setLines(new ArrayList<>());

            // ========== 页眉（开关控制） ==========
            if (config.showHeaderFooter) {
                float headerTitleY = config.paddingTop + headerFontHeight;
                float headerSeparatorY = headerTitleY + headerFontHeight * 0.8f;

                page.getLines().add(
                        LineData.createHeaderTitle(chapterTitle, config.paddingLeft, headerTitleY)
                );
                page.getLines().add(
                        LineData.createHeaderSeparator(config.paddingLeft, headerSeparatorY,
                                config.pageWidth - config.paddingRight)
                );
            }

            // ========== 填充正文 ==========
            int linesFilled = 0;
            float currentY = bodyTop;

            while (linesFilled < maxLinesPerPage && currentPos < totalLength) {
                if (currentPos < totalLength && content.charAt(currentPos) == '\n') {
                    currentPos++;
                    if (currentY + lineHeight * 0.5f <= bodyBottom) {
                        currentY += lineHeight * 0.5f;
                    }
                    continue;
                }

                String line = fetchLine(content, currentPos, contentWidth, config.textPaint);
                if (line.isEmpty()) {
                    currentPos++;
                    continue;
                }

                if (currentY + lineHeight > bodyBottom + 1) {
                    break;
                }

                page.getLines().add(
                        LineData.createContent(line, config.paddingLeft, currentY)
                );

                currentPos += line.length();
                currentY += lineHeight;
                linesFilled++;
            }

            // ========== 页脚（开关控制） ==========
            if (config.showHeaderFooter) {
                float footerY = config.pageHeight - config.paddingBottom + footerFontHeight;
                String footerText = "— " + (pageNum + 1) + " —";
                float footerTextWidth = config.footerPaint.measureText(footerText);
                float footerX = (config.pageWidth - footerTextWidth) / 2f;

                page.getLines().add(
                        LineData.createFooter(footerText, footerX, footerY)
                );
            }

            pages.add(page);
            pageNum++;
        }

        if (pages.isEmpty()) {
            PageBean emptyPage = createEmptyPage(chapterIndex, chapterTitle);
            pages.add(emptyPage);
        }

        return pages;
    }

    /**
     * 从当前位置截取一行文本（不包含换行符）
     */
    private String fetchLine(String text, int startPos, float maxWidth, Paint paint) {
        if (startPos >= text.length()) return "";

        char firstChar = text.charAt(startPos);
        if (firstChar == '\n') {
            return "";
        }

        // 先找换行符位置
        int newlinePos = text.indexOf('\n', startPos);
        if (newlinePos == startPos) {
            return "";
        }

        int endPos = startPos;

        // 逐字符测量宽度
        float measuredWidth = 0;
        while (endPos < text.length()) {
            char c = text.charAt(endPos);

            // 遇到换行符就停止
            if (c == '\n') {
                break;
            }

            float charWidth = paint.measureText(text, endPos, endPos + 1);
            if (measuredWidth + charWidth > maxWidth) {
                break;
            }

            measuredWidth += charWidth;
            endPos++;
        }

        if (endPos == startPos) {
            // 至少返回一个字符（防止死循环）
            endPos = Math.min(startPos + 1, text.length());
        }

        return text.substring(startPos, endPos);
    }

    /**
     * 创建空白页
     */
    private PageBean createEmptyPage(int chapterIndex, String chapterTitle) {
        PageBean page = new PageBean();
        page.setChapterIndex(chapterIndex);
        page.setPageIndexInChapter(0);
        page.setChapterTitle(chapterTitle);
        page.setLines(new ArrayList<>());

        if (config.showHeaderFooter) {
            float headerTitleY = config.paddingTop + config.headerPaint.getFontSpacing();
            page.getLines().add(
                    LineData.createHeaderTitle(chapterTitle, config.paddingLeft, headerTitleY)
            );
        }

        page.getLines().add(
                LineData.createContent("暂无内容", config.paddingLeft, config.pageHeight / 2f)
        );

        if (config.showHeaderFooter) {
            float footerY = config.pageHeight - config.paddingBottom + config.footerPaint.getFontSpacing();
            String footerText = "— 1 —";
            float footerTextWidth = config.footerPaint.measureText(footerText);
            float footerX = (config.pageWidth - footerTextWidth) / 2f;
            page.getLines().add(
                    LineData.createFooter(footerText, footerX, footerY)
            );
        }
        return page;
    }
}