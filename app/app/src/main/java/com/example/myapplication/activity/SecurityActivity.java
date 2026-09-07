package com.example.myapplication.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.utils.ThemeManager;

public class SecurityActivity extends BaseActivity {

    private TextView tvUid;
    private TextView tvPhone;
    private TextView tvEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security);

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

        tvUid = findViewById(R.id.tv_uid);
        tvPhone = findViewById(R.id.tv_phone);
        tvEmail = findViewById(R.id.tv_email);

        loadData();

        // 手机绑定点击
        findViewById(R.id.btn_phone).setOnClickListener(v -> {
            startActivity(new Intent(SecurityActivity.this, PhoneBindingActivity.class));
        });

        // 邮箱绑定点击
        findViewById(R.id.btn_email).setOnClickListener(v -> {
            startActivity(new Intent(SecurityActivity.this, EmailBindingActivity.class));
        });

        // 修改密码点击
        findViewById(R.id.btn_change_password).setOnClickListener(v -> {
            startActivity(new Intent(SecurityActivity.this, ChangePasswordActivity.class));
        });

        // 注销账号点击
        findViewById(R.id.btn_cancel_account).setOnClickListener(v -> {
            showCancelAccountDialog();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);

        // UID
        String userId = sp.getString("userId", "");
        tvUid.setText(userId.isEmpty() ? "未登录" : userId);

        // 手机号
        String phone = sp.getString("phone", "");
        tvPhone.setText(phone.isEmpty() ? "未绑定" : maskPhone(phone));
        tvPhone.setTextColor(phone.isEmpty() ? 0xFF8E8E93 : 0xFF1D1D1F); // iOS 次文字 / 主文字

        // 邮箱
        String email = sp.getString("email", "");
        tvEmail.setText(email.isEmpty() ? "未绑定" : maskEmail(email));
        tvEmail.setTextColor(email.isEmpty() ? 0xFF8E8E93 : 0xFF1D1D1F); // iOS 次文字 / 主文字
    }

    private String maskPhone(String phone) {
        if (phone.length() >= 7) {
            return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        }
        return phone;
    }

    private String maskEmail(String email) {
        if (email.contains("@")) {
            String[] parts = email.split("@");
            String name = parts[0];
            if (name.length() > 3) {
                return name.substring(0, 3) + "***@" + parts[1];
            }
        }
        return email;
    }

    private void showCancelAccountDialog() {
        new AlertDialog.Builder(this)
                .setTitle("注销账号")
                .setMessage("注销后您的所有数据将被永久删除，无法恢复。确定要注销账号吗？")
                .setPositiveButton("确定注销", (dialog, which) -> {
                    // TODO: 调用后端注销接口
                    // 这里先清除本地数据并跳转到登录页
                    SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
                    sp.edit().clear().apply();
                    Intent intent = new Intent(this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}