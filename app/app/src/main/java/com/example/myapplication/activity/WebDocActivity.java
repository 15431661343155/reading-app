package com.example.myapplication.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import com.example.myapplication.R;

/**
 * 协议文档内嵌页：《用户服务协议》《隐私政策》共用。
 *
 * <p>文档与 Web 端指向服务器上的同一份 HTML，协议内容更新后 App 无需发版即可生效。
 * 文档里若出现站外链接，交给系统浏览器处理，避免用户被困在 App 内的网页里。
 */
public class WebDocActivity extends BaseActivity {

    public static final String EXTRA_TITLE = "doc_title";
    public static final String EXTRA_URL = "doc_url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_doc);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String url = getIntent().getStringExtra(EXTRA_URL);

        TextView tvTitle = findViewById(R.id.tv_doc_title);
        tvTitle.setText(title == null ? "" : title);

        findViewById(R.id.iv_doc_back).setOnClickListener(v -> finish());

        WebView webView = findViewById(R.id.web_doc);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 只放行文档所在目录内的链接；文档里的「返回首页」等站内其它页面交给系统浏览器，
        // 避免用户被带进 App 内嵌的网页版书城后回不来
        final String allowedPrefix = (url == null || !url.contains("/"))
                ? ""
                : url.substring(0, url.lastIndexOf('/') + 1);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (!allowedPrefix.isEmpty() && target.toString().startsWith(allowedPrefix)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (Exception ignored) {
                    // 没有可处理该链接的应用时保持当前页面
                }
                return true;
            }
        });

        if (url != null && !url.isEmpty()) {
            webView.loadUrl(url);
        }
    }

    @Override
    public void onBackPressed() {
        WebView webView = findViewById(R.id.web_doc);
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }
}
