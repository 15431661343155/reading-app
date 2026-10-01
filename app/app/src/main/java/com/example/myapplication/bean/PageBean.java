package com.example.myapplication.bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 页面数据模型 - 包含一页的所有绘制信息
 */
public class PageBean {
    private int chapterIndex;           // 所属章节索引
    private int pageIndexInChapter;     // 章内页码（从0开始）
    private String chapterTitle;        // 章节标题（页眉用）
    private List<LineData> lines;       // 该页的所有行数据

    public PageBean() {
        this.lines = new ArrayList<>();
    }

    // Getter/Setter
    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

    public int getPageIndexInChapter() { return pageIndexInChapter; }
    public void setPageIndexInChapter(int pageIndexInChapter) { this.pageIndexInChapter = pageIndexInChapter; }

    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }

    public List<LineData> getLines() { return lines; }
    public void setLines(List<LineData> lines) { this.lines = lines; }

    /**
     * 行数据 - 描述一行文本/元素的绘制信息
     */
    public static class LineData {
        public String text;             // 文本内容
        public float x;                 // 绘制X坐标
        public float y;                 // 绘制Y坐标（基线）
        public LineType type;           // 行类型

        public enum LineType {
            HEADER_TITLE,    // 页眉标题
            HEADER_SEPARATOR,// 页眉分隔线
            CONTENT,         // 正文
            FOOTER           // 页脚页码
        }

        public static LineData createHeaderTitle(String title, float x, float y) {
            LineData data = new LineData();
            data.text = title;
            data.x = x;
            data.y = y;
            data.type = LineType.HEADER_TITLE;
            return data;
        }

        public static LineData createHeaderSeparator(float x1, float y, float x2) {
            LineData data = new LineData();
            data.x = x1;
            data.y = y;
            data.text = String.valueOf(x2); // 用text存储x2坐标
            data.type = LineType.HEADER_SEPARATOR;
            return data;
        }

        public static LineData createContent(String text, float x, float y) {
            LineData data = new LineData();
            data.text = text;
            data.x = x;
            data.y = y;
            data.type = LineType.CONTENT;
            return data;
        }

        public static LineData createFooter(String text, float x, float y) {
            LineData data = new LineData();
            data.text = text;
            data.x = x;
            data.y = y;
            data.type = LineType.FOOTER;
            return data;
        }
    }
}