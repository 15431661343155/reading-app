package com.example.myapplication.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.BuildConfig;
import com.example.myapplication.R;

/**
 * 全屏启动页 —— 阁檐墨韵 · 国风典雅风
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY = 2500; // 启动页延时 2.5 秒
    private static final long ANIM_DURATION = 800;  // 单次动画时长
    private static final long STAGGER = 200;        // 动画级联间隔

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // 获取各视图组件
        View char1      = findViewById(R.id.tv_title_char1);
        View char2      = findViewById(R.id.tv_title_char2);
        View divider    = findViewById(R.id.iv_divider);
        View subtitle   = findViewById(R.id.tv_subtitle);
        View seal       = findViewById(R.id.iv_seal);
        View mountains  = findViewById(R.id.iv_mountains);
        TextView version = findViewById(R.id.tv_version);
        version.setText(getString(R.string.app_version));

        // ——— 初始状态：全部透明 ———
        View[] views = {char1, char2, divider, subtitle, seal, mountains, version};
        for (int i = 0; i < views.length; i++) {
            views[i].setAlpha(0f);
        }

        // 标题字稍微缩小，待动画放大至正常大小
        char1.setScaleX(0.85f);
        char1.setScaleY(0.85f);
        char2.setScaleX(0.85f);
        char2.setScaleY(0.85f);

        // 山峦初始略微下移
        mountains.setTranslationY(60f);

        // ——— 延迟一帧后开始动画（等待布局完成）———
        getWindow().getDecorView().post(() -> {
            long delay = 100;

            // 1.「书」淡入 + 放大
            animateFadeIn(char1, delay, true);
            delay += STAGGER;

            // 2.「阁」淡入 + 放大
            animateFadeIn(char2, delay, true);
            delay += STAGGER;

            // 3. 印章淡入
            animateFadeIn(seal, delay, false);
            delay += STAGGER / 2;

            // 4. 装饰线淡入
            animateFadeIn(divider, delay, false);
            delay += STAGGER;

            // 5. 副标题淡入
            animateFadeIn(subtitle, delay, false);
            delay += STAGGER;

            // 6. 山峦淡入 + 上移
            animateMountainRise(mountains, delay);

            // 7. 版本号淡入
            animateFadeIn(version, delay + STAGGER, false);
        });

        // ——— 延时跳转到主页面或登录页 ———
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
            boolean isLoggedIn = !sp.getString("userId", "").isEmpty();

            Intent intent;
            if (isLoggedIn) {
                intent = new Intent(SplashActivity.this, MainActivity.class);
            } else {
                intent = new Intent(SplashActivity.this, LoginActivity.class);
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }, SPLASH_DELAY);
    }

    /**
     * 通用淡入动画（可选附带缩放效果）
     */
    private void animateFadeIn(View view, long startDelay, boolean withScale) {
        view.animate()
                .alpha(1f)
                .setDuration(ANIM_DURATION)
                .setStartDelay(startDelay)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(null)
                .start();

        if (withScale) {
            view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(ANIM_DURATION + 200)
                    .setStartDelay(startDelay)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /**
     * 山峦升起动画
     */
    private void animateMountainRise(View view, long startDelay) {
        view.animate()
                .alpha(0.6f)
                .translationY(0f)
                .setDuration(ANIM_DURATION + 400)
                .setStartDelay(startDelay)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
