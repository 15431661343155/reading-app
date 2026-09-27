package com.example.myapplication.utils;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.example.myapplication.R;
import com.example.myapplication.activity.LoginActivity;

/**
 * 登录状态与「需要登录」的统一处理。
 *
 * 设计目标（见需求：初次使用无需强制登录即可阅读；需要登录的功能弹登录提醒）：
 * - isLoggedIn：判断是否已登录（必须同时有 userId 与 token）。
 * - requireLogin：已登录直接执行 onGranted；未登录弹「国风 · 人物探出」提示弹窗 ——
 *   点「这就登录」跳登录页（EXTRA_RETURN_TO_CALLER=true，登录后直接返回原页面，
 *   避免每次登录都被强制带去主页）；点「下次再说」或点弹窗外部关闭。
 *   弹窗外观的唯一实现见 showLoginPrompt() + layout/dialog_login_prompt.xml。
 */
public final class LoginHelper {

    private static final String PREF_NAME = "user_info";

    private LoginHelper() {}

    /** 是否已登录：必须同时具备 userId 与 token。 */
    public static boolean isLoggedIn(Context context) {
        if (context == null) return false;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String userId = sp.getString("userId", "");
        String token = sp.getString("token", "");
        return !userId.isEmpty() && !token.isEmpty();
    }

    /** 取当前 userId（未登录返回空串）。供 ExternalPrefs 做按用户隔离。 */
    public static String getUserId(Context context) {
        if (context == null) return "";
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString("userId", "");
    }

    /** 取当前 userId 为 long（未登录/解析失败返回 0）。供同步引擎判断登录态。 */
    public static long getUserIdLong(Context context) {
        String id = getUserId(context);
        if (id == null || id.isEmpty()) return 0L;
        try { return Long.parseLong(id); } catch (NumberFormatException e) { return 0L; }
    }

    /** 取当前 token（未登录返回空串）。供退出登录时的同步上传显式携带，避免被清理竞态影响。 */
    public static String getToken(Context context) {
        if (context == null) return "";
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString("token", "");
    }

    /**
     * 需要登录时调用：已登录直接执行 onGranted；未登录弹提醒，同意则跳转登录页。
     */
    public static void requireLogin(android.app.Activity activity, Runnable onGranted) {
        // message 传 null = 直接用弹窗内置文案（「还没有登录哦 / 登录后可体验完整功能」）
        requireLogin(activity, null, onGranted);
    }

    /**
     * 同上，可自定义第二行文案。
     */
    public static void requireLogin(android.app.Activity activity, String message, Runnable onGranted) {
        if (activity == null || activity.isFinishing()) return;
        if (isLoggedIn(activity)) {
            if (onGranted != null) onGranted.run();
            return;
        }
        showLoginPrompt(activity, message);
    }

    /**
     * 国风「人物探出卡片」登录提示弹窗 —— 全站登录提醒的唯一实现。
     * 改样式只改这里 + layout/dialog_login_prompt.xml，全站生效。
     *
     * @param message 第二行文案；传 null / 空白则保留布局内置的「登录后可体验完整功能」。
     *                调用方一般会传更具体的原因（如「加入书架需要登录后操作」），信息量更大。
     */
    private static void showLoginPrompt(android.app.Activity activity, String message) {
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_login_prompt, null);

        Dialog dialog = new Dialog(activity, R.style.LoginPromptDialogStyle);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.CENTER);
            // ⚠️ 必须显式给宽度：inflate(layout, null) 会丢掉根布局的 layout_width，
            //    只传 WRAP_CONTENT 的话窗口会被「最宽的固定子元素」（卡片 280dp）决定，
            //    而根会变成 MATCH_PARENT，与设计稿不符。
            //    312 = 卡片 280dp + 左右各 16dp（留卡片投影）。
            float density = activity.getResources().getDisplayMetrics().density;
            window.setLayout((int) (312 * density), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setCanceledOnTouchOutside(true);

        TextView line2 = root.findViewById(R.id.tv_login_prompt_line2);
        if (line2 != null && message != null && !message.trim().isEmpty()) {
            line2.setText(message.trim());
        }

        root.findViewById(R.id.btn_login_prompt_cancel)
                .setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.btn_login_prompt_confirm).setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(activity, LoginActivity.class);
            intent.putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true);
            activity.startActivity(intent);
        });

        dialog.show();
    }
}
