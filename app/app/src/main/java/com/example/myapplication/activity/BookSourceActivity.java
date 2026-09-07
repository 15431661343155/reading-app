package com.example.myapplication.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.api.ApiService;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.BookSource;
import com.example.myapplication.bean.ImportResult;
import com.example.myapplication.utils.ThemeManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class BookSourceActivity extends BaseActivity {

    private LinearLayout sourceListLayout;
    private ApiService apiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_source);

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

        sourceListLayout = findViewById(R.id.sourceListLayout);
        apiService = RetrofitClient.getApiService();

        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.btnImportUrl).setOnClickListener(v -> showImportUrlDialog());

        loadSources();
    }

    private void loadSources() {
        apiService.getOnlineSources().enqueue(new Callback<ApiResponse<List<com.example.myapplication.bean.SourceInfo>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<com.example.myapplication.bean.SourceInfo>>> call,
                                   Response<ApiResponse<List<com.example.myapplication.bean.SourceInfo>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    renderSources(response.body().getData());
                } else {
                    showEmpty();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<List<com.example.myapplication.bean.SourceInfo>>> call, Throwable t) {
                showEmpty();
            }
        });
    }

    private void renderSources(List<com.example.myapplication.bean.SourceInfo> sources) {
        sourceListLayout.removeAllViews();
        if (sources == null || sources.isEmpty()) {
            showEmpty();
            return;
        }
        for (com.example.myapplication.bean.SourceInfo s : sources) {
            View item = getLayoutInflater().inflate(R.layout.item_book_source, null);
            TextView tvName = item.findViewById(R.id.tvName);
            TextView tvDesc = item.findViewById(R.id.tvDesc);
            TextView tvType = item.findViewById(R.id.tvType);
            tvName.setText(s.getName());
            tvDesc.setText(s.getDescription() != null ? s.getDescription() : "");
            tvType.setText(s.getType());
            sourceListLayout.addView(item);
        }
    }

    private void showEmpty() {
        sourceListLayout.removeAllViews();
        TextView emptyTv = new TextView(this);
        emptyTv.setText("暂无书源\n点击「网络导入」添加书源");
        emptyTv.setGravity(android.view.Gravity.CENTER);
        emptyTv.setPadding(0, 100, 0, 0);
        emptyTv.setTextColor(0xFF8E8E93); // iOS 次文字
        sourceListLayout.addView(emptyTv);
    }

    private void showAddDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_book_source, null);
        EditText etName = view.findViewById(R.id.etName);
        EditText etType = view.findViewById(R.id.etType);
        EditText etConfig = view.findViewById(R.id.etConfig);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加书源")
                .setView(view)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String type = etType.getText().toString().trim();
            String config = etConfig.getText().toString().trim();
            if (name.isEmpty() || type.isEmpty()) {
                Toast.makeText(this, "请填写完整", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "请在管理后台配置书源", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    private void showImportUrlDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_import_url, null);
        EditText etUrl = view.findViewById(R.id.etUrl);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("🌐 网络导入书源")
                .setView(view)
                .setPositiveButton("开始导入", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入书源链接", Toast.LENGTH_SHORT).show();
                return;
            }
            doImportUrl(url, dialog);
        });
    }

    private void doImportUrl(String url, AlertDialog dialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("导入中...");

        Map<String, String> body = new HashMap<>();
        body.put("url", url);

        apiService.importBookSourceFromUrl(body).enqueue(new Callback<ApiResponse<ImportResult>>() {
            @Override
            public void onResponse(Call<ApiResponse<ImportResult>> call, Response<ApiResponse<ImportResult>> response) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("开始导入");

                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    ImportResult result = response.body().getData();
                    StringBuilder msg = new StringBuilder();
                    msg.append("导入完成\n\n");
                    msg.append("成功: ").append(result.getSuccess()).append(" 条\n");
                    msg.append("跳过: ").append(result.getSkipped()).append(" 条\n");
                    msg.append("失败: ").append(result.getFailed()).append(" 条\n");
                    msg.append("总计: ").append(result.getTotal()).append(" 条\n\n");
                    if (result.getMessages() != null && !result.getMessages().isEmpty()) {
                        msg.append("详情:\n");
                        for (String m : result.getMessages()) {
                            msg.append("• ").append(m).append("\n");
                        }
                    }
                    new AlertDialog.Builder(BookSourceActivity.this)
                            .setTitle("导入结果")
                            .setMessage(msg.toString())
                            .setPositiveButton("确定", null)
                            .show();
                    loadSources();
                } else {
                    String errMsg = response.body() != null ? response.body().getMessage() : "导入失败";
                    Toast.makeText(BookSourceActivity.this, errMsg, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<ImportResult>> call, Throwable t) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("开始导入");
                Toast.makeText(BookSourceActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
