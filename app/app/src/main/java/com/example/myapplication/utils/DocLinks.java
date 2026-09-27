package com.example.myapplication.utils;

import android.content.Context;
import android.content.Intent;

import com.example.myapplication.R;
import com.example.myapplication.activity.WebDocActivity;

/**
 * 协议文档入口的唯一来源。
 *
 * <p>《用户协议》《隐私政策》等文档与 Web 端指向服务器上的同一份 HTML，
 * 协议内容更新后 App 无需发版即可生效。登录页与「关于书阁」共用这里的常量与跳转，
 * 新增文档只改这一个文件。
 */
public final class DocLinks {

    private DocLinks() {
    }

    /** 文档所在目录（与 Web 端一致） */
    private static final String DOC_BASE = "http://8.148.8.146:8089/文档资料/";

    /** 用户协议 */
    public static final String TERMS = DOC_BASE + "terms.html";
    /** 隐私政策 */
    public static final String PRIVACY = DOC_BASE + "privacy.html";

    /**
     * 打开内嵌文档页。
     *
     * @param title 文档标题（显示在顶部）
     * @param url   文档地址，取本类的常量
     */
    public static void open(Context context, String title, String url) {
        Intent intent = new Intent(context, WebDocActivity.class);
        intent.putExtra(WebDocActivity.EXTRA_TITLE, title);
        intent.putExtra(WebDocActivity.EXTRA_URL, url);
        context.startActivity(intent);
    }

    /** 打开用户协议 */
    public static void openTerms(Context context) {
        open(context, context.getString(R.string.doc_terms), TERMS);
    }

    /** 打开隐私政策 */
    public static void openPrivacy(Context context) {
        open(context, context.getString(R.string.doc_privacy), PRIVACY);
    }
}
