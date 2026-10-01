package com.example.myapplication.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import com.example.myapplication.widget.MorphSubmitButton;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.api.ApiService;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.FeedbackRequest;
import com.example.myapplication.utils.ThemeManager;
import com.example.myapplication.utils.Hint;

import retrofit2.Call;
import retrofit2.Response;

/**
 * 意见反馈页
 */
public class FeedbackActivity extends BaseActivity {

    private RadioGroup rgType;
    private EditText etContent, etContact;
    private MorphSubmitButton btnSubmit;
    private boolean isSubmitting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int currentTheme = ThemeManager.getCurrentTheme(this);
        setTheme(ThemeManager.getThemeRes(currentTheme));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        initView();
        setupToolbar(currentTheme);

        btnSubmit.setOnClickListener(v -> submitFeedback());
    }

    private void initView() {
        rgType    = findViewById(R.id.rg_feedback_type);
        etContent = findViewById(R.id.et_feedback_content);
        etContact = findViewById(R.id.et_feedback_contact);
        btnSubmit = findViewById(R.id.btn_feedback_submit);
        btnSubmit.setIdleColor(0xFF6C5CE7);
        btnSubmit.setIdleText(getString(R.string.feedback_submit));
    }

    /**
     * 配置顶部导航栏（返回按钮 + 主题适配）
     */
    private void setupToolbar(int currentTheme) {
        Toolbar toolbar = findViewById(R.id.toolbar_back);
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing

        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF000000);
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF1D1D1F);
        }
        // 海滨主题工具栏为浅色，状态栏图标用深色
        setLightStatusBar(currentTheme == ThemeManager.THEME_SEASIDE);

        toolbar.setNavigationOnClickListener(v -> finish());
    }

    /**
     * 获取当前选中的反馈类型
     */
    private String getSelectedType() {
        int checkedId = rgType.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_suggestion) {
            return "suggestion";
        } else if (checkedId == R.id.rb_other) {
            return "other";
        }
        return "bug"; // 默认
    }

    /**
     * 提交反馈
     */
    private void submitFeedback() {
        if (isSubmitting) return;

        String content = etContent.getText().toString().trim();
        if (content.isEmpty()) {
            Hint.show(this, R.string.feedback_content_empty);
            return;
        }

        SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
        String userIdStr = sp.getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);

        String contact = etContact.getText().toString().trim();
        String type    = getSelectedType();

        FeedbackRequest request = new FeedbackRequest(userId, content, contact, type);

        // 进入加载态：按钮内收成圆 + 画环
        isSubmitting = true;
        btnSubmit.startLoading();

        ApiService api = RetrofitClient.getApiService();
        api.submitFeedback(request).enqueue(SafeCallback.from(this,
                // onSuccess
                (call, response) -> {
                    isSubmitting = false;
                    ApiResponse<Void> body = response.body();
                    if (body != null && body.isSuccess()) {
                        btnSubmit.succeed(getString(R.string.feedback_submit_success));
                        btnSubmit.postDelayed(() -> finish(), 1200);
                    } else {
                        String msg = body != null ? body.getMessage() : getString(R.string.feedback_submit_fail);
                        btnSubmit.fail(msg);
                    }
                },
                // onError
                (call, t) -> {
                    isSubmitting = false;
                    btnSubmit.fail(getString(R.string.feedback_submit_fail));
                }
        ));
    }

}
