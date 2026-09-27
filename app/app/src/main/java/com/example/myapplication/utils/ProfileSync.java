package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;

import java.io.File;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 资料云端对齐：把服务器上的最新资料（昵称 / 性别 / 邮箱 / 头像）拉回本地 SP。
 *
 * <p>背景：App 端资料此前只在登录时写入一次 SP，Web 端改了头像/昵称后，
 * App 必须退出重登才能看到。接入本工具后，「我的」页与「个人资料」页进入时
 * 会拉取 {@code GET /api/user/profile} 并与 SP 对齐，无需重登。
 *
 * <p>头像对齐策略：SP 新增 {@code avatar_server} 记录上次已对齐的服务器头像
 * 相对地址。服务器地址与它不一致（含被清空 → 空串）即视为变化：
 * <ul>
 *   <li>变化且非空 → {@link AvatarCache#download} 后台下载，成功才更新
 *       {@code avatar}（本地路径）与 {@code avatar_server}；下载失败不写标记，
 *       下次进入自动重试（离线容错）。</li>
 *   <li>变化且为空 → 清空 {@code avatar} 与 {@code avatar_server}（Web 端
 *       恢复默认头像后 App 端同步回落）。</li>
 * </ul>
 *
 * <p>老版本升级自愈：老安装没有 {@code avatar_server}（读出空串），首次同步
 * 必然按「变化」处理并补齐标记，不需要任何迁移逻辑。
 *
 * <p>线程约定：{@link #sync} 任意线程可调；回调保证在主线程执行。
 * 内置 10 秒节流：「我的」页随 tab 切换频繁 onResume，避免高频打接口。
 */
public final class ProfileSync {

    /** 同步完成回调（主线程）。{@code changed} 为 true 表示 SP 有变化，调用方应刷新 UI。 */
    public interface SyncCallback {
        void onResult(boolean changed);
    }

    private static final long THROTTLE_MS = 10_000L;
    private static long lastSyncAt = 0L;

    private ProfileSync() {
    }

    /**
     * 拉取云端资料并对齐本地 SP。
     *
     * @param cb 结果回调（主线程）；未登录 / 节流期内直接回调 changed=false
     */
    public static void sync(Context ctx, SyncCallback cb) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        final SyncCallback callback = cb != null ? cb : changed -> { };
        SharedPreferences sp = app.getSharedPreferences("user_info", Context.MODE_PRIVATE);

        // 游客态：没有服务端账号可对齐
        if (sp.getString("token", "").isEmpty()) {
            deliverMain(callback, false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSyncAt < THROTTLE_MS) {
            deliverMain(callback, false);
            return;
        }
        lastSyncAt = now;

        RetrofitClient.getApiService().getProfile()
                .enqueue(new Callback<ApiResponse<Map<String, Object>>>() {
                    @Override
                    public void onResponse(@androidx.annotation.NonNull Call<ApiResponse<Map<String, Object>>> call,
                                           @androidx.annotation.NonNull Response<ApiResponse<Map<String, Object>>> response) {
                        ApiResponse<Map<String, Object>> body = response.body();
                        if (body == null || !body.isSuccess() || body.getData() == null) {
                            deliverMain(callback, false);
                            return;
                        }
                        applyProfile(app, sp, body.getData(), callback);
                    }

                    @Override
                    public void onFailure(@androidx.annotation.NonNull Call<ApiResponse<Map<String, Object>>> call,
                                          @androidx.annotation.NonNull Throwable t) {
                        deliverMain(callback, false);
                    }
                });
    }

    /** 把云端字段写回 SP（文字字段同步比对；头像变化走后台下载）。 */
    private static void applyProfile(Context app, SharedPreferences sp,
                                     Map<String, Object> data, SyncCallback callback) {
        boolean changed = false;
        SharedPreferences.Editor ed = sp.edit();

        String nickname = str(data.get("nickname"));
        if (!nickname.equals(sp.getString("nickname", ""))) {
            ed.putString("nickname", nickname);
            changed = true;
        }

        String username = str(data.get("username"));
        if (!username.isEmpty() && !username.equals(sp.getString("username", ""))) {
            ed.putString("username", username);
            changed = true;
        }

        String email = str(data.get("email"));
        if (!email.equals(sp.getString("email", ""))) {
            ed.putString("email", email);
            changed = true;
        }

        int gender = data.get("gender") instanceof Number ? ((Number) data.get("gender")).intValue() : 0;
        if (gender != sp.getInt("gender", 0)) {
            ed.putInt("gender", gender);
            changed = true;
        }

        // —— 头像：以 avatar_server 标记位判断服务端是否变化 ——
        final String serverAvatar = str(data.get("avatar"));
        final String known = sp.getString("avatar_server", "");
        if (!serverAvatar.equals(known)) {
            if (serverAvatar.isEmpty()) {
                // Web 端恢复默认头像：清空本地
                ed.putString("avatar_server", "");
                ed.putString("avatar", "");
                ed.apply();
                deliverMain(callback, true);
            } else {
                ed.apply(); // 先把文字字段落盘
                final boolean textChanged = changed;
                // 后台下载成功才认账；失败不写标记，下次自动重试
                new Thread(() -> {
                    File f = AvatarCache.download(app, serverAvatar);
                    if (f != null) {
                        sp.edit()
                                .putString("avatar_server", serverAvatar)
                                .putString("avatar", f.getAbsolutePath())
                                .apply();
                        deliverMain(callback, true);
                    } else {
                        deliverMain(callback, textChanged);
                    }
                }, "profile-avatar-sync").start();
            }
            return;
        }

        if (changed) {
            ed.apply();
        }
        deliverMain(callback, changed);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void deliverMain(SyncCallback cb, boolean changed) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onResult(changed));
    }
}
