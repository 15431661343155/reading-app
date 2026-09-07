package com.example.myapplication.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.BindRequest;
import com.example.myapplication.bean.SendCodeRequest;
import com.example.myapplication.utils.ThemeManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ChangePasswordActivity extends BaseActivity {

    private TextView tvPasswordVerify, tvEmailVerify;
    private LinearLayout layoutPasswordVerify, layoutEmailVerify;
    private TextView tvBoundEmail;

    private EditText etOldPassword, etNewPassword, etConfirmPassword;
    private EditText etEmailCode, etEmailNewPassword, etEmailConfirmPassword;
    private Button btnPasswordChange, btnEmailChange, btnSendCode;

    private SharedPreferences sp;
    private boolean isPasswordMode = true;
    private String boundEmail = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

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

        sp = getSharedPreferences("user_info", MODE_PRIVATE);

        initView();
        loadUserInfo();
        setupListeners();
    }

    private void initView() {
        tvPasswordVerify = findViewById(R.id.tv_password_verify);
        tvEmailVerify = findViewById(R.id.tv_email_verify);
        layoutPasswordVerify = findViewById(R.id.layout_password_verify);
        layoutEmailVerify = findViewById(R.id.layout_email_verify);
        tvBoundEmail = findViewById(R.id.tv_bound_email);

        etOldPassword = findViewById(R.id.et_old_password);
        etNewPassword = findViewById(R.id.et_new_password);
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        etEmailCode = findViewById(R.id.et_email_code);
        etEmailNewPassword = findViewById(R.id.et_email_new_password);
        etEmailConfirmPassword = findViewById(R.id.et_email_confirm_password);
        btnPasswordChange = findViewById(R.id.btn_password_change);
        btnEmailChange = findViewById(R.id.btn_email_change);
        btnSendCode = findViewById(R.id.btn_send_code);
    }

    private void loadUserInfo() {
        boundEmail = sp.getString("email", "");
        if (!boundEmail.isEmpty()) {
            tvBoundEmail.setText("您的邮箱：" + maskEmail(boundEmail));
        }
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

    private void setupListeners() {
        tvPasswordVerify.setOnClickListener(v -> switchToPasswordMode());
        tvEmailVerify.setOnClickListener(v -> handleEmailVerifyClick());

        btnPasswordChange.setOnClickListener(v -> handlePasswordChange());
        btnEmailChange.setOnClickListener(v -> handleEmailChange());
        btnSendCode.setOnClickListener(v -> sendEmailCode());
    }

    private void switchToPasswordMode() {
        isPasswordMode = true;
        tvPasswordVerify.setBackgroundResource(R.drawable.bg_search_radius);
        tvPasswordVerify.setTextColor(getResources().getColor(R.color.white));
        tvEmailVerify.setBackgroundResource(R.drawable.bg_search_radius_outline);
        tvEmailVerify.setTextColor(getResources().getColor(R.color.black));
        layoutPasswordVerify.setVisibility(View.VISIBLE);
        layoutEmailVerify.setVisibility(View.GONE);
    }

    private void switchToEmailMode() {
        isPasswordMode = false;
        tvEmailVerify.setBackgroundResource(R.drawable.bg_search_radius);
        tvEmailVerify.setTextColor(getResources().getColor(R.color.white));
        tvPasswordVerify.setBackgroundResource(R.drawable.bg_search_radius_outline);
        tvPasswordVerify.setTextColor(getResources().getColor(R.color.black));
        layoutPasswordVerify.setVisibility(View.GONE);
        layoutEmailVerify.setVisibility(View.VISIBLE);
    }

    private void handleEmailVerifyClick() {
        if (boundEmail.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("邮箱未绑定，是否前往绑定？")
                    .setPositiveButton("是", (dialog, which) -> {
                        startActivity(new Intent(this, EmailBindingActivity.class));
                    })
                    .setNegativeButton("否", null)
                    .show();
        } else {
            switchToEmailMode();
        }
    }

    private void handlePasswordChange() {
        String oldPassword = etOldPassword.getText().toString().trim();
        String newPassword = etNewPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        if (oldPassword.isEmpty()) {
            Toast.makeText(this, "请输入当前密码", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPassword.isEmpty()) {
            Toast.makeText(this, "请输入新密码", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPassword.length() < 6) {
            Toast.makeText(this, "新密码长度至少6位", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show();
            return;
        }

        BindRequest request = BindRequest.forPasswordChange(oldPassword, newPassword);
        doChangePassword(request, "密码修改成功");
    }

    private void sendEmailCode() {
        if (boundEmail.isEmpty()) {
            Toast.makeText(this, "邮箱未绑定", Toast.LENGTH_SHORT).show();
            return;
        }

        SendCodeRequest request = new SendCodeRequest(boundEmail, "change_password");
        RetrofitClient.getApiService().sendEmailCode(request).enqueue(new Callback<ApiResponse<Void>>() {
            @Override
            public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    Toast.makeText(ChangePasswordActivity.this, "验证码已发送", Toast.LENGTH_SHORT).show();
                    startCountdown();
                } else {
                    String msg = response.body() != null ? response.body().getMessage() : "发送失败";
                    Toast.makeText(ChangePasswordActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                Toast.makeText(ChangePasswordActivity.this, "网络错误", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startCountdown() {
        btnSendCode.setEnabled(false);
        new CountDownTimer(60000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                btnSendCode.setText(millisUntilFinished / 1000 + "秒");
            }

            @Override
            public void onFinish() {
                btnSendCode.setText("发送验证码");
                btnSendCode.setEnabled(true);
            }
        }.start();
    }

    private void handleEmailChange() {
        String code = etEmailCode.getText().toString().trim();
        String newPassword = etEmailNewPassword.getText().toString().trim();
        String confirmPassword = etEmailConfirmPassword.getText().toString().trim();

        if (code.isEmpty()) {
            Toast.makeText(this, "请输入验证码", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPassword.isEmpty()) {
            Toast.makeText(this, "请输入新密码", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPassword.length() < 6) {
            Toast.makeText(this, "新密码长度至少6位", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show();
            return;
        }

        BindRequest request = BindRequest.forEmailChangePassword(boundEmail, code, newPassword);
        doChangePassword(request, "密码修改成功");
    }

    private void doChangePassword(BindRequest request, String successMessage) {
        RetrofitClient.getApiService().changePassword(request).enqueue(new Callback<ApiResponse<Void>>() {
            @Override
            public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    Toast.makeText(ChangePasswordActivity.this, successMessage, Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    String msg = response.body() != null ? response.body().getMessage() : "修改失败";
                    Toast.makeText(ChangePasswordActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                Toast.makeText(ChangePasswordActivity.this, "网络错误", Toast.LENGTH_SHORT).show();
            }
        });
    }
}