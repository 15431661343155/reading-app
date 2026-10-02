package com.example.myapplication.activity;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.utils.ThemeManager;

/**
 * 外观设置：三档日夜模式（跟随系统 / 日间 / 夜间）。
 * 选中项写入偏好后调用 AppCompatDelegate 全局切换，所有在册页面由 AppCompat 自动重建生效，
 * 页面本身不需要再重启或跳回主页。
 */
public class ThemeSettingActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_setting);

        Toolbar toolbar = findViewById(R.id.toolbar_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        bindRow(R.id.row_follow_system, R.id.check_follow_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        bindRow(R.id.row_light, R.id.check_light, AppCompatDelegate.MODE_NIGHT_NO);
        bindRow(R.id.row_dark, R.id.check_dark, AppCompatDelegate.MODE_NIGHT_YES);
    }

    private void bindRow(int rowId, int checkId, int nightMode) {
        boolean selected = ThemeManager.getNightMode(this) == nightMode;
        // INVISIBLE 而非 GONE：勾选位常驻，切换选中项时行内容不位移
        findViewById(checkId).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        findViewById(rowId).setOnClickListener(v -> {
            if (ThemeManager.getNightMode(this) == nightMode) {
                return;
            }
            ThemeManager.saveNightMode(this, nightMode);
            AppCompatDelegate.setDefaultNightMode(nightMode);
        });
    }
}
