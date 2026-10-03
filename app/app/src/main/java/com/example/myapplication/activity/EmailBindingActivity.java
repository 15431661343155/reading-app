package com.example.myapplication.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.BindRequest;
import com.example.myapplication.bean.SendCodeRequest;
import com.example.myapplication.utils.ThemeAttrs;
import com.example.myapplication.utils.ThemeManager;
import com.example.myapplication.utils.Hint;
import com.example.myapplication.widget.MorphSubmitButton;

import retrofit2.Call;
import retrofit2.Response;

public class EmailBindingActivity extends BaseActivity {

    private TextView tvCurrentEmail;
    private EditText etEmail;
    private EditText etCode;
    private Button btnSendCode;
    private MorphSubmitButton btnBind;
    private LinearLayout layoutCurrentEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_binding);

        Toolbar toolbar = findViewById(R.id.toolbar_back);
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing

        toolbar.setNavigationIcon(R.drawable.ic_back_black);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvCurrentEmail = findViewById(R.id.tv_current_email);
        etEmail = findViewById(R.id.et_email);
        etCode = findViewById(R.id.et_code);
        btnSendCode = findViewById(R.id.btn_send_code);
        btnBind = findViewById(R.id.btn_bind);
        layoutCurrentEmail = findViewById(R.id.layout_current_email);

        loadCurrentEmail();

        btnSendCode.setOnClickListener(v -> sendVerificationCode());
        btnBind.setIdleColor(ThemeAttrs.color(this, R.attr.appAccent, 0xFF007AFF));
        btnBind.setIdleText("绑定邮箱");
        btnBind.setOnClickListener(v -> bindEmail());
    }

    private void loadCurrentEmail() {
        SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
        String email = sp.getString("email", "");
        if (!email.isEmpty()) {
            tvCurrentEmail.setText(maskEmail(email));
            layoutCurrentEmail.setVisibility(LinearLayout.VISIBLE);
        } else {
            tvCurrentEmail.setText("未绑定");
            layoutCurrentEmail.setVisibility(LinearLayout.VISIBLE);
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

    private void sendVerificationCode() {
        String email = etEmail.getText().toString().trim();
        if (email.isEmpty()) {
            Hint.show(this, "请输入邮箱");
            return;
        }
        if (!email.contains("@") || !email.contains(".")) {
            Hint.show(this, "邮箱格式不正确");
            return;
        }

        SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
        String userId = sp.getString("userId", "");

        SendCodeRequest request = new SendCodeRequest(email, "bind");
        RetrofitClient.getApiService().sendEmailCode(request).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        Hint.show(EmailBindingActivity.this, "验证码已发送");
                        startCountdown();
                    } else {
                        String msg = (response.body() != null) ? response.body().getMessage() : "发送失败";
                        Hint.show(EmailBindingActivity.this, msg);
                    }
                },
                (call, t) -> {
                    Hint.show(EmailBindingActivity.this, "网络错误: " + t.getMessage());
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

    private void bindEmail() {
        String email = etEmail.getText().toString().trim();
        String code = etCode.getText().toString().trim();

        if (email.isEmpty()) {
            Hint.show(this, "请输入邮箱");
            return;
        }
        if (!email.contains("@") || !email.contains(".")) {
            Hint.show(this, "邮箱格式不正确");
            return;
        }
        if (code.isEmpty()) {
            Hint.show(this, "请输入验证码");
            return;
        }
        if (code.length() != 6) {
            Hint.show(this, "验证码为6位数字");
            return;
        }

        SharedPreferences sp2 = getSharedPreferences("user_info", MODE_PRIVATE);
        String userId = sp2.getString("userId", "");

        BindRequest request = new BindRequest(email, code, "bind");
        btnBind.startLoading();
        RetrofitClient.getApiService().bindEmail(request).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        sp2.edit().putString("email", email).apply();
                        btnBind.succeed("邮箱绑定成功");
                        btnBind.postDelayed(() -> finish(), 1200);
                    } else {
                        String msg = (response.body() != null) ? response.body().getMessage() : "绑定失败";
                        btnBind.fail(msg);
                    }
                },
                (call, t) -> {
                    btnBind.fail("网络错误: " + t.getMessage());
                }
        ));
    }
}