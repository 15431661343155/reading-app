package com.example.myapplication.activity;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.manager.UpdateManager;
import com.example.myapplication.utils.DocLinks;
import com.example.myapplication.utils.ThemeManager;

public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar_back);

        toolbar.setNavigationOnClickListener(v -> finish());

        // 版本号来自 build.gradle 的 versionName（@string/app_version），布局里已绑定，这里兜底防 resValue 缺失
        TextView tvVersion = findViewById(R.id.tv_version);
        tvVersion.setText(getString(R.string.app_version));

        // 版本与更新
        findViewById(R.id.btn_check_update).setOnClickListener(v -> checkUpdate());
        findViewById(R.id.btn_version).setOnClickListener(v -> showVersionInfo());

        // 法律条款：与登录页共用同一份文档（见 DocLinks）
        findViewById(R.id.btn_terms).setOnClickListener(v -> DocLinks.openTerms(this));
        findViewById(R.id.btn_privacy).setOnClickListener(v -> DocLinks.openPrivacy(this));

        // 联系我们
        findViewById(R.id.btn_qq_group).setOnClickListener(v -> showQqGroup());
    }

    private void checkUpdate() {
        UpdateManager.checkUpdate(this, new UpdateManager.UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(com.example.myapplication.bean.ApkPush apkPush) {
                UpdateManager.showUpdateDialog(AboutActivity.this, apkPush);
            }

            @Override
            public void onNoUpdate() {
                new AlertDialog.Builder(AboutActivity.this)
                        .setTitle("检查更新")
                        .setMessage("已是最新版本")
                        .setPositiveButton("确定", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                new AlertDialog.Builder(AboutActivity.this)
                        .setTitle("检查更新")
                        .setMessage(message)
                        .setPositiveButton("确定", null)
                        .show();
            }
        });
    }

    private void showVersionInfo() {
        new AlertDialog.Builder(this)
                .setTitle("版本信息")
                .setMessage("书阁 " + getString(R.string.app_version))
                .setPositiveButton("确定", null)
                .show();
    }

    private void showQqGroup() {
        String group = ((TextView) findViewById(R.id.tv_qq_group)).getText().toString();
        new AlertDialog.Builder(this)
                .setTitle("官方QQ群")
                .setMessage(group + "\n\n可复制群号搜索加入，反馈问题与建议。")
                .setPositiveButton("确定", null)
                .show();
    }
}
