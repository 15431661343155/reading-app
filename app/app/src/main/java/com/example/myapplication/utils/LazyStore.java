package com.example.myapplication.utils;

/**
 * 本地书「回源懒解析」仓储的统一接口：不落整本正文，只靠一份几 KB 的索引 + 源文件现场重建任意一章。
 *
 * <p>两个实现：EPUB 走 {@link EpubLazyStore}（索引 = 每章 zip 条目名，重建含保留样式的 HTML），
 * TXT 走 {@link TxtLazyStore}（索引 = 每章源文件字节区间，只有纯文本正文，{@link #html} 恒返回 null）。
 *
 * <p>实例与一本已打开的书绑定（内部持有源文件句柄），用完必须 {@link #close()}。
 */
public interface LazyStore {

    /** 章数（= 索引长度）；调用方用它校验索引与书架记录的章节数是否还对得上 */
    int chapterCount();

    /** 该章「保留样式的 HTML」；没有（如 TXT）或源条目缺失返回 null，调用方退回纯文本 */
    String html(int chapterIndex);

    /** 该章纯文本正文；源缺失返回 null（调用方据此判定本书不可读） */
    String text(int chapterIndex, String chapterTitle);

    void close();
}
