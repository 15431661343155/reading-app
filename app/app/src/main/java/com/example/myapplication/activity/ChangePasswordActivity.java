package com.example.myapplication.activity;

import android.app.AlertDialog;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.BindRequest;
import com.example.myapplication.bean.SendCodeRequest;
import com.example.myapplication.utils.ThemeManager;
import com.example.myapplication.utils.Hint;
import com.example.myapplication.utils.PasswordPolicy;
import com.example.myapplication.widget.MorphSubmitButton;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ChangePasswordActivity extends BaseActivity {

    private TextView tvPasswordVerify, tvEmailVerify;
    private View layoutPasswordVerify, layoutEmailVerify;
    private TextView tvBoundEmail;

    // 分段滑动指示器
    private View segTrack, segThumb;
    private float thumbDist = 0f;
    private ValueAnimator labelAnim;

    private EditText etOldPassword, etNewPassword, etConfirmPassword;
    private EditText etEmailCode, etEmailNewPassword, etEmailConfirmPassword;
    private MorphSubmitButton btnPasswordChange, btnEmailChange;
    private Button btnSendCode;

    private ImageView ivOldEye, ivNewEye, ivConfirmEye, ivEmailNewEye, ivEmailConfirmEye;
    private View[] strengthSegs, strengthSegsEmail;
    private TextView strengthLabel, strengthLabelEmail;

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
        segTrack = findViewById(R.id.seg_track);
        segThumb = findViewById(R.id.seg_thumb);
        segTrack.post(this::layoutThumb);

        etOldPassword = findViewById(R.id.et_old_password);
        etNewPassword = findViewById(R.id.et_new_password);
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        etEmailCode = findViewById(R.id.et_email_code);
        etEmailNewPassword = findViewById(R.id.et_email_new_password);
        etEmailConfirmPassword = findViewById(R.id.et_email_confirm_password);
        btnPasswordChange = findViewById(R.id.btn_password_change);
        btnEmailChange = findViewById(R.id.btn_email_change);
        btnSendCode = findViewById(R.id.btn_send_code);
        btnPasswordChange.setIdleColor(0xFF6C5CE7);
        btnPasswordChange.setIdleText("确认修改");
        btnEmailChange.setIdleColor(0xFF6C5CE7);
        btnEmailChange.setIdleText("确认修改");

        ivOldEye = findViewById(R.id.iv_old_eye);
        ivNewEye = findViewById(R.id.iv_new_eye);
        ivConfirmEye = findViewById(R.id.iv_confirm_eye);
        ivEmailNewEye = findViewById(R.id.iv_email_new_eye);
        ivEmailConfirmEye = findViewById(R.id.iv_email_confirm_eye);

        strengthSegs = new View[]{
                findViewById(R.id.strength_seg1),
                findViewById(R.id.strength_seg2),
                findViewById(R.id.strength_seg3)};
        strengthSegsEmail = new View[]{
                findViewById(R.id.strength_seg1_email),
                findViewById(R.id.strength_seg2_email),
                findViewById(R.id.strength_seg3_email)};
        strengthLabel = findViewById(R.id.strength_label);
        strengthLabelEmail = findViewById(R.id.strength_label_email);

        switchToPasswordMode();
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

        ivOldEye.setOnClickListener(v -> togglePassword(etOldPassword, ivOldEye));
        ivNewEye.setOnClickListener(v -> togglePassword(etNewPassword, ivNewEye));
        ivConfirmEye.setOnClickListener(v -> togglePassword(etConfirmPassword, ivConfirmEye));
        ivEmailNewEye.setOnClickListener(v -> togglePassword(etEmailNewPassword, ivEmailNewEye));
        ivEmailConfirmEye.setOnClickListener(v -> togglePassword(etEmailConfirmPassword, ivEmailConfirmEye));

        etNewPassword.addTextChangedListener(new StrengthWatcher(etNewPassword, strengthSegs, strengthLabel));
        etEmailNewPassword.addTextChangedListener(new StrengthWatcher(etEmailNewPassword, strengthSegsEmail, strengthLabelEmail));
    }

    private static final int COL_SEL = 0xFF6C5CE7;
    private static final int COL_UNSEL = 0xFF8E8AA0;

    private void switchToPasswordMode() {
        animateSegment(false);
    }

    private void switchToEmailMode() {
        animateSegment(true);
    }

    /** 分段滑动过渡：指示器平移 + 标签颜色渐变 + 面板交叉淡化（新面板自点击侧滑入） */
    private void animateSegment(boolean targetEmail) {
        if (targetEmail == !isPasswordMode) return; // 已在该模式
        isPasswordMode = !targetEmail;

        final View incoming = targetEmail ? layoutEmailVerify : layoutPasswordVerify;
        final View outgoing = targetEmail ? layoutPasswordVerify : layoutEmailVerify;
        final float dir = targetEmail ? 1f : -1f; // 去右侧 Tab：新面板从右滑入
        float d = 14 * getResources().getDisplayMetrics().density;

        // 先取消进行中的过渡，避免连点错乱（取消时 withEndAction 不会执行）
        if (labelAnim != null) labelAnim.cancel();
        outgoing.animate().cancel();
        incoming.animate().cancel();

        // 指示器滑动
        if (segThumb != null && thumbDist > 0f) {
            segThumb.animate().translationX(targetEmail ? thumbDist : 0f)
                    .setDuration(300L)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .start();
        }

        // 标签颜色渐变
        int selFrom = targetEmail ? COL_UNSEL : COL_SEL;
        int unsFrom = targetEmail ? COL_SEL : COL_UNSEL;
        ArgbEvaluator ev = new ArgbEvaluator();
        labelAnim = ValueAnimator.ofFloat(0f, 1f);
        labelAnim.setDuration(250L);
        labelAnim.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            (targetEmail ? tvEmailVerify : tvPasswordVerify)
                    .setTextColor((int) ev.evaluate(t, selFrom, COL_SEL));
            (targetEmail ? tvPasswordVerify : tvEmailVerify)
                    .setTextColor((int) ev.evaluate(t, unsFrom, COL_UNSEL));
        });
        labelAnim.start();

        // 面板交叉淡化 + 位移
        outgoing.setVisibility(View.VISIBLE);
        incoming.setAlpha(0f);
        incoming.setTranslationX(dir * d);
        incoming.setVisibility(View.VISIBLE);
        outgoing.animate()
                .alpha(0f).translationX(-dir * d)
                .setDuration(260L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    outgoing.setVisibility(View.GONE);
                    outgoing.setAlpha(1f);
                    outgoing.setTranslationX(0f);
                })
                .start();
        incoming.animate()
                .alpha(1f).translationX(0f)
                .setDuration(300L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 依据轨道宽度计算指示器尺寸与位置 */
    private void layoutThumb() {
        if (segTrack == null || segThumb == null) return;
        int w = segTrack.getWidth();
        if (w <= 0) { segTrack.post(this::layoutThumb); return; }
        int pad = Math.round(5 * getResources().getDisplayMetrics().density);
        thumbDist = (w - 2 * pad) / 2f;
        ViewGroup.LayoutParams lp = segThumb.getLayoutParams();
        lp.width = Math.round(thumbDist);
        segThumb.setLayoutParams(lp);
        segThumb.setTranslationX(!isPasswordMode ? thumbDist : 0f);
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
            Hint.show(this, "请输入当前密码");
            return;
        }

        if (newPassword.isEmpty()) {
            Hint.show(this, "请输入新密码");
            return;
        }

        String passwordError = PasswordPolicy.validate(newPassword);
        if (passwordError != null) {
            Hint.show(this, passwordError);
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            Hint.show(this, "两次输入的密码不一致");
            return;
        }

        BindRequest request = BindRequest.forPasswordChange(oldPassword, newPassword);
        doChangePassword(btnPasswordChange, request, "密码修改成功");
    }

    private void sendEmailCode() {
        if (boundEmail.isEmpty()) {
            Hint.show(this, "邮箱未绑定");
            return;
        }

        SendCodeRequest request = new SendCodeRequest(boundEmail, "change_password");
        RetrofitClient.getApiService().sendEmailCode(request).enqueue(new Callback<ApiResponse<Void>>() {
            @Override
            public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    Hint.show(ChangePasswordActivity.this, "验证码已发送");
                    startCountdown();
                } else {
                    String msg = response.body() != null ? response.body().getMessage() : "发送失败";
                    Hint.show(ChangePasswordActivity.this, msg);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                Hint.show(ChangePasswordActivity.this, "网络错误");
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
            Hint.show(this, "请输入验证码");
            return;
        }

        if (newPassword.isEmpty()) {
            Hint.show(this, "请输入新密码");
            return;
        }

        String passwordError = PasswordPolicy.validate(newPassword);
        if (passwordError != null) {
            Hint.show(this, passwordError);
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            Hint.show(this, "两次输入的密码不一致");
            return;
        }

        BindRequest request = BindRequest.forEmailChangePassword(boundEmail, code, newPassword);
        doChangePassword(btnEmailChange, request, "密码修改成功");
    }

    private void doChangePassword(MorphSubmitButton btn, BindRequest request, String successMessage) {
        btn.startLoading();
        RetrofitClient.getApiService().changePassword(request).enqueue(new Callback<ApiResponse<Void>>() {
            @Override
            public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    btn.succeed(successMessage);
                    btn.postDelayed(() -> finish(), 1200);
                } else {
                    String msg = response.body() != null ? response.body().getMessage() : "修改失败";
                    btn.fail(msg);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                btn.fail("网络错误");
            }
        });
    }

    /** 眼睛图标切换明文/密文，并移动光标到末尾避免跳动。 */
    private void togglePassword(EditText et, ImageView eye) {
        boolean showing = Boolean.TRUE.equals(eye.getTag());
        if (showing) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            eye.setImageResource(R.drawable.ic_login_eye_off);
        } else {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            eye.setImageResource(R.drawable.ic_login_eye);
        }
        eye.setTag(!showing);
        et.setSelection(et.getText().length());
    }

    /** 密码强度：0=空 1=弱(不合规) 2=中 3=强。 */
    private int computeLevel(String pwd) {
        if (pwd == null || pwd.isEmpty()) return 0;
        boolean hasLetter = false, hasDigit = false, hasSpecial = false;
        for (char c : pwd.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }
        boolean basic = pwd.length() >= 8 && pwd.length() <= 64 && hasLetter && hasDigit;
        if (!basic) return 1;
        int score = 2;
        if (pwd.length() >= 12) score++;
        else if (hasSpecial) score++;
        return Math.min(score, 3);
    }

    private void updateStrength(String pwd, View[] segs, TextView label) {
        int level = computeLevel(pwd);
        int color;
        String text;
        if (level == 0) {
            color = 0xFFE4E0EE;
            text = "强度：弱";
        } else if (level == 1) {
            color = 0xFFFF5A5A;
            text = "强度：弱";
        } else if (level == 2) {
            color = 0xFFF5C518;
            text = "强度：中";
        } else {
            color = 0xFF34C759;
            text = "强度：强";
        }
        for (int i = 0; i < segs.length; i++) {
            segs[i].setBackgroundColor(i < level ? color : 0xFFE4E0EE);
        }
        label.setText(text);
        label.setTextColor(level == 0 ? 0xFFA29DB8 : 0xFF1D1D1F);
    }

    /** 监听新密码输入，实时刷新强度条。 */
    private class StrengthWatcher implements TextWatcher {
        private final EditText source;
        private final View[] segs;
        private final TextView label;

        StrengthWatcher(EditText source, View[] segs, TextView label) {
            this.source = source;
            this.segs = segs;
            this.label = label;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            updateStrength(s.toString(), segs, label);
        }
    }
}