package com.example.myapplication.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.myapplication.utils.Hint;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;

import java.io.IOException;

public class RetrofitClient {
    //宝塔Linux面板IP地址
    private static final String BASE_URL = "http://8.148.8.146:8080/";
    private static Retrofit retrofit;
    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static ApiService getApiService() {
        if (retrofit == null) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(logging)
                    .addInterceptor(new TokenInterceptor())
                    .build();

            retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit.create(ApiService.class);
    }

    public static String getFullImageUrl(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return "";
        }
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
            return relativePath;
        }
        // ✅ 本地文件（例如导入电子书解析出的封面，保存在应用私有目录）直接返回 file:// URI。
        //    否则会被拼成 "http://服务器/data/user/0/.../cover_x.jpg" 这种根本不存在的地址，
        //    Glide 加载失败 → 一直显示默认占位封面（书架看上去就是「本书暂无封面」）。
        if (relativePath.startsWith("file://") || relativePath.startsWith("content://")) {
            return relativePath;
        }
        if (relativePath.startsWith("/") && new java.io.File(relativePath).exists()) {
            return "file://" + relativePath;
        }
        String baseUrl = BASE_URL;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (relativePath.startsWith("/")) {
            return baseUrl + relativePath;
        } else {
            return baseUrl + "/" + relativePath;
        }
    }

    private static class TokenInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            String token = "";
            if (appContext != null) {
                SharedPreferences sp = appContext.getSharedPreferences("user_info", Context.MODE_PRIVATE);
                token = sp.getString("token", "");
            }

            // 若请求已自带 Authorization（例如退出登录时的同步上传，token 在清理 user_info 前
            // 已显式捕获），则不再用可能已清空的 SP token 覆盖，避免登出瞬间上传 401 丢数据。
            Request.Builder builder = original.newBuilder();
            if (original.header("Authorization") == null) {
                builder.header("Authorization", "Bearer " + token);
            }

            Request request = builder
                    .method(original.method(), original.body())
                    .build();

            Response response = chain.proceed(request);
            if (response.code() == 401) {
                onUnauthorized();
            }
            return response;
        }
    }

    /** 401 提示的节流间隔：并发请求同时拿到 401 时只提示一次 */
    private static final long UNAUTHORIZED_HINT_INTERVAL_MS = 5000L;
    private static volatile long lastUnauthorizedHintAt = 0L;

    /**
     * 登录态失效（HTTP 401）的统一兜底。
     *
     * <p>后端已把 /api/user/**（书架/进度/书签）、/api/users/** 等接口收紧为「必须登录」，
     * token 过期或失效时会返回 401。若不做处理，App 只会「静默失败」——用户看到数据莫名
     * 丢失却没有任何提示。这里清掉本地 token，使 LoginHelper.isLoggedIn() 变为 false，
     * 后续需要登录的功能会正常弹出「去登录」提醒，并给一次文字提示说明原因。
     *
     * <p>只清 token、不清 userId：userId 是本地外站书缓存的隔离键（ExternalPrefs），
     * 清掉会导致该用户已缓存的外站书读不出来。
     */
    private static void onUnauthorized() {
        final Context context = appContext;
        if (context == null) return;

        SharedPreferences sp = context.getSharedPreferences("user_info", Context.MODE_PRIVATE);
        if (sp.getString("token", "").isEmpty()) {
            // 本来就是游客态（无 token），不是登录态失效，不打扰用户
            return;
        }
        sp.edit().putString("token", "").apply();

        long now = System.currentTimeMillis();
        if (now - lastUnauthorizedHintAt < UNAUTHORIZED_HINT_INTERVAL_MS) return;
        lastUnauthorizedHintAt = now;
        Hint.showLong(context, "登录已过期，请重新登录");
    }
}
