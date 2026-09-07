package com.example.myapplication.activity;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.google.android.material.card.MaterialCardView;

/**
 * ⚠️ 注意：这是一个示例代码文件
 * 
 * 使用方法：
 * 1. 将此文件中的代码复制到你现有的 ThemeSettingActivity.java 中
 * 2. 或者重命名此文件为 ThemeSettingActivity.java 并替换原有文件
 * 3. 确保在 AndroidManifest.xml 中注册此 Activity
 * 
 * 当前显示的语法错误是因为此文件不在正确的Android项目结构中
 * 当代码被正确放置到项目中后，这些错误会自动消失
 */
public class ThemeSettingActivity extends AppCompatActivity {

    private MaterialCardView cardOcean, cardViolet, cardForest, cardSunset;
    private ImageView ivOceanCheck, ivVioletCheck, ivForestCheck, ivSunsetCheck;
    private String currentTheme;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在super.onCreate之前应用当前主题
        applyCurrentTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_setting);

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        initViews();
        loadCurrentTheme();
        setupClickListeners();
    }

    /**
     * 应用当前保存的主题
     */
    private void applyCurrentTheme() {
        currentTheme = prefs.getString("theme", "ocean");

        switch (currentTheme) {
            case "ocean":
                setTheme(R.style.Theme_MyApp_Ocean);
                break;
            case "violet":
                setTheme(R.style.Theme_MyApp_Violet);
                break;
            case "forest":
                setTheme(R.style.Theme_MyApp_Forest);
                break;
            case "sunset":
                setTheme(R.style.Theme_MyApp_Sunset);
                break;
            default:
                setTheme(R.style.Theme_MyApp_Ocean);
                break;
        }
    }

    /**
     * 初始化视图
     */
    private void initViews() {
        Toolbar toolbar = findViewById(R.id.toolbar_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        cardOcean = findViewById(R.id.card_ocean);
        cardViolet = findViewById(R.id.card_violet);
        cardForest = findViewById(R.id.card_forest);
        cardSunset = findViewById(R.id.card_sunset);

        ivOceanCheck = findViewById(R.id.iv_ocean_check);
        ivVioletCheck = findViewById(R.id.iv_violet_check);
        ivForestCheck = findViewById(R.id.iv_forest_check);
        ivSunsetCheck = findViewById(R.id.iv_sunset_check);
    }

    /**
     * 加载当前主题并显示选中状态
     */
    private void loadCurrentTheme() {
        // 隐藏所有选中标记
        ivOceanCheck.setVisibility(View.GONE);
        ivVioletCheck.setVisibility(View.GONE);
        ivForestCheck.setVisibility(View.GONE);
        ivSunsetCheck.setVisibility(View.GONE);

        // 重置所有卡片边框为默认灰色
        resetCardBorders();

        // 显示当前主题的选中标记和高亮边框
        switch (currentTheme) {
            case "ocean":
                ivOceanCheck.setVisibility(View.VISIBLE);
                highlightCard(cardOcean, R.color.ocean_primary);
                break;
            case "violet":
                ivVioletCheck.setVisibility(View.VISIBLE);
                highlightCard(cardViolet, R.color.violet_primary);
                break;
            case "forest":
                ivForestCheck.setVisibility(View.VISIBLE);
                highlightCard(cardForest, R.color.forest_primary);
                break;
            case "sunset":
                ivSunsetCheck.setVisibility(View.VISIBLE);
                highlightCard(cardSunset, R.color.sunset_primary);
                break;
        }
    }

    /**
     * 设置点击监听器
     */
    private void setupClickListeners() {
        cardOcean.setOnClickListener(v -> selectTheme("ocean"));
        cardViolet.setOnClickListener(v -> selectTheme("violet"));
        cardForest.setOnClickListener(v -> selectTheme("forest"));
        cardSunset.setOnClickListener(v -> selectTheme("sunset"));
    }

    /**
     * 选择主题
     * @param theme 主题名称：ocean, violet, forest, sunset
     */
    private void selectTheme(String theme) {
        if (theme.equals(currentTheme)) {
            // 如果选择的是当前主题，不做任何操作
            return;
        }

        // 保存主题选择
        prefs.edit().putString("theme", theme).apply();

        // 显示提示
        Toast.makeText(this, "主题已切换，重启应用生效", Toast.LENGTH_SHORT).show();

        // 延迟1秒后重启应用以应用新主题
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            restartApp();
        }, 1000);
    }

    /**
     * 重启应用
     */
    private void restartApp() {
        // 启动MainActivity并清除任务栈
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * 重置所有卡片边框为默认灰色
     */
    private void resetCardBorders() {
        cardOcean.setStrokeColor(Color.parseColor("#E0E0E0"));
        cardViolet.setStrokeColor(Color.parseColor("#E0E0E0"));
        cardForest.setStrokeColor(Color.parseColor("#E0E0E0"));
        cardSunset.setStrokeColor(Color.parseColor("#E0E0E0"));
    }

    /**
     * 高亮显示选中的卡片
     * @param card 卡片视图
     * @param colorRes 颜色资源ID
     */
    private void highlightCard(MaterialCardView card, int colorRes) {
        int color = getResources().getColor(colorRes, getTheme());
        card.setStrokeColor(color);
    }
}
