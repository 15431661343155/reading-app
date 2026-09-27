package com.example.myapplication.activity;

import android.os.Bundle;

import com.google.android.material.card.MaterialCardView;
import com.example.myapplication.R;
import com.example.myapplication.utils.ThemeManager;
import android.content.Intent;
import androidx.appcompat.widget.Toolbar;
import com.example.myapplication.activity.MainActivity;
import com.example.myapplication.utils.Hint;

public class ThemeSettingActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int currentTheme = ThemeManager.getCurrentTheme(this);
        setTheme(ThemeManager.getThemeRes(currentTheme));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_setting);

        Toolbar toolbar = findViewById(R.id.toolbar_back);
        // 让Toolbar延伸到状态栏区域
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing
        
        // 自动判断当前主题是否为暗色
        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF000000);
        }
        // 默认主题 = 深色背景 → 白色箭头
        else {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF1D1D1F);
        }

        // 海滨主题工具栏为浅色，状态栏图标用深色
        setLightStatusBar(currentTheme == ThemeManager.THEME_SEASIDE);

        // 返回事件
        toolbar.setNavigationOnClickListener(v -> finish());

        MaterialCardView cardDefault = findViewById(R.id.card_default);
        MaterialCardView cardSeaside = findViewById(R.id.card_seaside);

        // 默认主题
        cardDefault.setOnClickListener(v -> {
            ThemeManager.saveTheme(this, ThemeManager.THEME_DEFAULT);
            restartToApply();
        });

        // 海滨主题
        cardSeaside.setOnClickListener(v -> {
            ThemeManager.saveTheme(this, ThemeManager.THEME_SEASIDE);
            restartToApply();
        });
    }

    // 重启界面，让主题生效
    private void restartToApply() {
        Hint.show(this, "主题已应用");

        // 刷新当前页面，停留在设置页
        finish();

        if (MainActivity.instance != null) {
            MainActivity.instance.switchToMine();
            MainActivity.instance.recreate();
        }
    }
}