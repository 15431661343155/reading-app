package com.example.myapplication.activity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;

public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar_back);
        toolbar.setNavigationOnClickListener(v -> finish());

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
    }
}
