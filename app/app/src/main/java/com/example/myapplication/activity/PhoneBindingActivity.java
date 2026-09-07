package com.example.myapplication.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.BindRequest;
import com.example.myapplication.bean.SendCodeRequest;
import com.example.myapplication.utils.ThemeManager;

import retrofit2.Call;
import retrofit2.Response;

public class PhoneBindingActivity extends BaseActivity {

    private TextView tvCurrentPhone;
    private EditText etPhone;
    private EditText etCode;
    private Button btnSendCode;
    private Button btnBind;
    private LinearLayout layoutCurrentPhone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_binding);

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

        tvCurrentPhone = findViewById(R.id.tv_current_phone);
        etPhone = findViewById(R.id.et_phone);
        etCode = findViewById(R.id.et_code);
        btnSendCode = findViewById(R.id.btn_send_code);
        btnBind = findViewById(R.id.btn_bind);
        layoutCurrentPhone = findViewById(R.id.layout_current_phone);

        loadCurrentPhone();

        btnSendCode.setOnClickListener(v -> sendVerificationCode());
        btnBind.setOnClickListener(v -> bindPhone());
    }

    private void loadCurrentPhone() {
        SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
        String phone = sp.getString("phone", "");
        if (!phone.isEmpty()) {
            tvCurrentPhone.setText(maskPhone(phone));
            layoutCurrentPhone.setVisibility(LinearLayout.VISIBLE);
        } else {
            tvCurrentPhone.setText("未绑定");
            layoutCurrentPhone.setVisibility(LinearLayout.VISIBLE);
        }
    }

    private String maskPhone(String phone) {
        if (phone.length() >= 7) {
            return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        }
        return phone;
    }

    private void sendVerificationCode() {
        String phone = etPhone.getText().toString().trim();
        if (phone.isEmpty()) {
            Toast.makeText(this, "请输入手机号", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phone.length() != 11) {
            Toast.makeText(this, "手机号格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
        String userId = sp.getString("userId", "");

        SendCodeRequest request = new SendCodeRequest(phone, "bind");
        RetrofitClient.getApiService().sendSmsCode(request).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        Toast.makeText(PhoneBindingActivity.this, "验证码已发送", Toast.LENGTH_SHORT).show();
                        startCountdown();
                    } else {
                        String msg = (response.body() != null) ? response.body().getMessage() : "发送失败";
                        Toast.makeText(PhoneBindingActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                },
                (call, t) -> {
                    Toast.makeText(PhoneBindingActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
        ));
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
                btnSendCode.setText("获取验证码");
                btnSendCode.setEnabled(true);
            }
        }.start();
    }

    private void bindPhone() {
        String phone = etPhone.getText().toString().trim();
        String code = etCode.getText().toString().trim();

        if (phone.isEmpty()) {
            Toast.makeText(this, "请输入手机号", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phone.length() != 11) {
            Toast.makeText(this, "手机号格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code.isEmpty()) {
            Toast.makeText(this, "请输入验证码", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code.length() != 6) {
            Toast.makeText(this, "验证码为6位数字", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences sp2 = getSharedPreferences("user_info", MODE_PRIVATE);
        String userId = sp2.getString("userId", "");

        BindRequest request = new BindRequest(phone, code, userId, "bind");
        RetrofitClient.getApiService().bindPhone(request).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
                        sp.edit().putString("phone", phone).apply();
                        Toast.makeText(PhoneBindingActivity.this, "手机绑定成功", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        String msg = (response.body() != null) ? response.body().getMessage() : "绑定失败";
                        Toast.makeText(PhoneBindingActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                },
                (call, t) -> {
                    Toast.makeText(PhoneBindingActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
        ));
    }
}