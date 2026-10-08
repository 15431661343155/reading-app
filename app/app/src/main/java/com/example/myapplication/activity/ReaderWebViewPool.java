package com.example.myapplication.activity;

import android.content.Context;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 阅读器 WebView 复用池。
 *
 * 整个 App 生命周期内只创建并加载一次 reader.html；进入 / 退出阅读器时通过
 * detach / attach 复用同一个 WebView 实例，从而消除「每次开书都重建 WebView + 重载 reader.html」
 * 的冷启动开销（这是除分页重排之外、进书时另一处明显的加载感来源）。
 *
 * 关键约定：
 * 1. WebView 用 Application Context 创建，避免持有 Activity 引用造成泄漏。
 * 2. 永不调用 destroy()；Activity 销毁时由 ReadActivity 负责 removeView 归还给池子。
 * 3. 每个 ReadActivity 在 attach 时重新 setWebViewClient / addJavascriptInterface，绑定到当前
 *    Activity；上一个 Activity 的绑定随替换而失效，不会串台。
 */
public final class ReaderWebViewPool {
    /** 阅读器唯一的文档地址；复用时按它判断池里的 WebView 还在不在正文上。 */
    private static final String READER_URL = "file:///android_asset/reader.html";

    private static WebView sWebView;
    private static boolean sLoaded; // reader.html 是否已加载完成
    /** 池里这份文档当前显示的是哪本书（{@code ReadActivity#bookRecordKey()}）；null = 还没渲染过正文 */
    private static String sBookKey;
    private static final Object LOCK = new Object();

    private ReaderWebViewPool() {
    }

    /** 预加载（创建并异步加载一次 reader.html）；可重复调用，仅首次生效。 */
    public static void preload(Context context) {
        obtain(context);
    }

    /** 获取复用池里的 WebView：首次调用会创建实例并异步加载 reader.html。 */
    public static WebView obtain(Context context) {
        synchronized (LOCK) {
            if (sWebView == null) {
                Context appCtx = context != null ? context.getApplicationContext() : null;
                WebView wv = new WebView(appCtx);
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(true);
                s.setAllowUniversalAccessFromFileURLs(true); // 允许加载内部存储字体文件
                s.setUseWideViewPort(true);
                s.setLoadWithOverviewMode(true);
                wv.setVerticalScrollBarEnabled(false);
                wv.setHorizontalScrollBarEnabled(false);
                // 池子自带的 client：仅负责标记「已加载」，不绑定任何 Activity，
                // 用于在「预加载后、尚无 Activity 接管」的窗口里记录加载完成状态。
                wv.setWebChromeClient(new WebChromeClient());
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        synchronized (LOCK) {
                            sLoaded = true;
                        }
                    }
                });
                try {
                    wv.loadUrl(READER_URL);
                } catch (Exception e) {
                    android.util.Log.e("ReaderWebViewPool", "preload reader.html failed", e);
                }
                // 关键修复：必须在 reader.html「加载阶段」就注入 JS 桥接（Android 全局对象）。
                //    复用池里若先 loadUrl、等进入 ReadActivity 后再 addJavascriptInterface，则对「已加载完成」
                //    的页面本机（Vivo 等）不会重新注入，导致 JS 里 Android 始终为 undefined —— 所有
                //    Web→Java 回调（onPageChanged / onChapterEnd 等）全部静默失败，表现为「续读无法精准到页」
                //    「翻到章末无法换章」。这里在页面加载前注册，确保 reader.html 一就绪即可调用 Android.*。
                try {
                    wv.addJavascriptInterface(ReadActivity.JsBridge.getInstance(), "Android");
                } catch (Exception ignored) {}
                sWebView = wv;
            } else {
                String current = sWebView.getUrl();
                // 自愈：池里的 WebView 曾被书里的链接带去过外部页面，而 sLoaded 是进程级标志、不会随换书
                //    复位 —— 不重新拉回 reader.html 的话，之后每一本书打开的都是那个网页。
                //    current 为 null 只可能是「首帧预加载还没提交」，那时 sLoaded 本就是 false，别重复加载。
                if (current != null && !isReaderDocument(current)) {
                    sLoaded = false;
                    sBookKey = null;        // 文档已换掉，场上不再显示任何一本书
                    try {
                        sWebView.loadUrl(READER_URL);
                    } catch (Exception e) {
                        android.util.Log.e("ReaderWebViewPool", "restore reader.html failed", e);
                    }
                }
            }
            return sWebView;
        }
    }

    /** 这个地址是不是阅读器自己那份文档（带 #锚点也算）。 */
    private static boolean isReaderDocument(String url) {
        return url != null && url.startsWith(READER_URL);
    }

    public static boolean isLoaded() {
        synchronized (LOCK) {
            return sLoaded;
        }
    }

    /** 由 ReadActivity 在 onPageFinished 时调用，确保复用判定标志被正确置位。 */
    public static void markLoaded() {
        synchronized (LOCK) {
            sLoaded = true;
        }
    }

    /** 主动回收（极低内存时调用）；下一次 obtain 会重新预热。 */
    public static void recycle() {
        synchronized (LOCK) {
            if (sWebView != null) {
                try {
                    sWebView.destroy();
                } catch (Exception ignored) {
                }
                sWebView = null;
                sLoaded = false;
                sBookKey = null;
            }
        }
    }

    /** 池里这份文档现在显示的是哪本书；null 表示还没渲染过任何正文（首帧预加载后就是这种状态）。 */
    public static String displayedBookKey() {
        synchronized (LOCK) {
            return sBookKey;
        }
    }

    /** 记下「这本书即将占用池里的文档」，由挂接 WebView 的 ReadActivity 调用。 */
    public static void markDisplayedBookKey(String key) {
        synchronized (LOCK) {
            sBookKey = key;
        }
    }
}
