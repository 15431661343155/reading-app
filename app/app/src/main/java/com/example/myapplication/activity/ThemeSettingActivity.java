package com.example.myapplication.activity;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.utils.ThemeManager;

/**
 * 外观设置：两个正交维度。
 * <ul>
 *   <li>外观模式（跟随系统 / 日间 / 夜间）：选中项写入偏好后调用 AppCompatDelegate 全局切换，
 *       所有在册页面由 AppCompat 自动重建生效；</li>
 *   <li>配色风格（素白 / 宣纸）：换的是主题而不是日夜配置，AppCompat 不会替我们重建，
 *       所以写入偏好后由 {@link BaseActivity#reskinAll} 把全站在册页面当场重建，
 *       本页也在其中（按下发顺序排在最后一步）。</li>
 * </ul>
 */
public class ThemeSettingActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_setting);

        Toolbar toolbar = findViewById(R.id.toolbar_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        bindRow(R.id.row_follow_system, R.id.check_follow_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        bindRow(R.id.row_light, R.id.check_light, AppCompatDelegate.MODE_NIGHT_NO);
        bindRow(R.id.row_dark, R.id.check_dark, AppCompatDelegate.MODE_NIGHT_YES);

        bindSkinRow(R.id.row_skin_classic, R.id.check_skin_classic, ThemeManager.SKIN_CLASSIC);
        bindSkinRow(R.id.row_skin_xuanzhi, R.id.check_skin_xuanzhi, ThemeManager.SKIN_XUANZHI);
    }

    private void bindRow(int rowId, int checkId, int nightMode) {
        boolean selected = ThemeManager.getNightMode(this) == nightMode;
        // INVISIBLE 而非 GONE：勾选位常驻，切换选中项时行内容不位移
        findViewById(checkId).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        findViewById(rowId).setOnClickListener(v -> {
            if (ThemeManager.getNightMode(this) == nightMode) {
                return;
            }
            ThemeManager.saveNightMode(this, nightMode);
            AppCompatDelegate.setDefaultNightMode(nightMode);
        });
    }

    private void bindSkinRow(int rowId, int checkId, int skin) {
        boolean selected = ThemeManager.getSkin(this) == skin;
        findViewById(checkId).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        findViewById(rowId).setOnClickListener(v -> {
            if (ThemeManager.getSkin(this) == skin) {
                return;
            }
            ThemeManager.saveSkin(this, skin);
            // 全站（含本页）当场重建：此刻其余页面都压在下面，用户看不到重建，
            // 回退到「我的」页时新皮肤已经就位，不会先看到旧风格再跳一次。
            BaseActivity.reskinAll(this);
            // 本页压掉转场：换配色风格是「就地变色」，不该演一次推开新页面。
            // 只压调用方这一个，后台页重建本来就无窗口动画可言。
            overridePendingTransition(0, 0);
        });
    }
}
