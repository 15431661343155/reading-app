package com.example.myapplication.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.activity.LoginActivity;
import com.example.myapplication.activity.ThemeSettingActivity;
import com.example.myapplication.activity.ProfileActivity;
import com.example.myapplication.activity.SecurityActivity;
import com.example.myapplication.activity.AboutActivity;
import com.example.myapplication.utils.ThemeManager;

public class SettingsActivity extends BaseActivity {

    private int savedTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        savedTheme = ThemeManager.getCurrentTheme(this);
        setContentView(R.layout.activity_settings);

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

        LinearLayout btnProfile = findViewById(R.id.btn_profile);
        btnProfile.setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, ProfileActivity.class));
        });

        LinearLayout btnSecurity = findViewById(R.id.btn_security);
        btnSecurity.setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, SecurityActivity.class));
        });

        LinearLayout btnTheme = findViewById(R.id.btn_theme);
        btnTheme.setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, ThemeSettingActivity.class));
        });

        LinearLayout btnBookSource = findViewById(R.id.btn_book_source);
        if (btnBookSource != null) {
            btnBookSource.setOnClickListener(v -> {
                startActivity(new Intent(SettingsActivity.this, BookSourceActivity.class));
            });
        }

        LinearLayout btnAbout = findViewById(R.id.btn_about);
        btnAbout.setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
        });

        LinearLayout btnLogout = findViewById(R.id.btn_logout);
        btnLogout.setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
            sp.edit().clear().apply();
            startActivity(new Intent(SettingsActivity.this, LoginActivity.class));
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        int currentTheme = ThemeManager.getCurrentTheme(this);
        if (currentTheme != savedTheme) {
            recreate();
        }
    }
}
