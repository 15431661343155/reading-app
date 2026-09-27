package com.example.myapplication.utils;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Bookshelf;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 书架数据「开屏预取」：
 * MainActivity 冷启动时（开屏浮层展示期间）提前请求 getBookshelf，结果暂存于此；
 * BookShelfFragment 首次 loadBookshelf 时命中即同步上屏，省去揭示书架后的网络等待。
 *
 * 只服务「冷启动首屏」这一个场景：
 * - consume 命中一次即清空，后续下拉刷新/返回书架走原有网络路径；
 * - 请求失败 / 未登录 / userId 不匹配均静默放弃（置结束标记），Fragment 回落原路径，行为不变。
 */
public final class ShelfPrefetch {

    /** 预取结果：userId 匹配才有效（防止登录态极短时间内变化导致串号）。 */
    private static volatile Result sResult;

    /** 预取是否已结束（成功/失败/无需预取均算结束），供开屏判断能否进入主页。 */
    private static volatile boolean sFinished;

    private ShelfPrefetch() {}

    /** 开屏窗口内调用：提前发起书架请求（异步 enqueue，不阻塞开屏）。 */
    public static void prefetch(Context context) {
        String uid = context.getSharedPreferences("user_info", 0).getString("userId", "");
        if (uid.isEmpty()) {
            sFinished = true; // 游客：书架只展示本地书，无需预取
            return;
        }
        final long userId = Long.parseLong(uid);
        final long startAt = SystemClock.elapsedRealtime();
        RetrofitClient.getApiService().getBookshelf(userId).enqueue(new Callback<ApiResponse<List<Bookshelf>>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<List<Bookshelf>>> call,
                                   @NonNull Response<ApiResponse<List<Bookshelf>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    sResult = new Result(userId, response.body());
                    Log.d("WSX", "ShelfPrefetch_ok " + (SystemClock.elapsedRealtime() - startAt) + "ms");
                }
                sFinished = true;
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse<List<Bookshelf>>> call, @NonNull Throwable t) {
                Log.d("WSX", "ShelfPrefetch_fail " + t.getMessage());
                sFinished = true;
            }
        });
    }

    /**
     * 预取是否已结束：开屏据此决定「数据好了再进主页」（网络过慢时由调用方兜底超时）。
     */
    public static boolean isDone() {
        return sFinished;
    }

    /**
     * Fragment 首次加载时消费预取结果：userId 匹配则返回并清空，否则返回 null。
     */
    public static ApiResponse<List<Bookshelf>> consume(long userId) {
        Result r = sResult;
        sResult = null;
        if (r == null || r.userId != userId) return null;
        return r.body;
    }

    private static final class Result {
        final long userId;
        final ApiResponse<List<Bookshelf>> body;

        Result(long userId, ApiResponse<List<Bookshelf>> body) {
            this.userId = userId;
            this.body = body;
        }
    }
}
