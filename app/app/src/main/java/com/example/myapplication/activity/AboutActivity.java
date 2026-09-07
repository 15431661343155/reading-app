package com.example.myapplication.activity;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.manager.UpdateManager;
import com.example.myapplication.utils.ThemeManager;

public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar_back);
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing

        int currentTheme = ThemeManager.getCurrentTheme(this);
        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF000000);
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF1D1D1F);
        }
        setLightStatusBar(currentTheme == ThemeManager.THEME_SEASIDE);

        toolbar.setNavigationOnClickListener(v -> finish());

        TextView tvVersion = findViewById(R.id.tv_version);
        tvVersion.setText(getString(R.string.app_version));

        // 检查更新点击
        findViewById(R.id.btn_check_update).setOnClickListener(v -> checkUpdate());
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
}
