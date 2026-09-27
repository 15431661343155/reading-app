package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import com.example.myapplication.api.ApiService;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;

/**
 * 外站书（书架 / 阅读记录 / 书签）的「本地优先 + 异步同步」引擎。
 *
 * 设计（与需求一致：本地不每次调服务器，退出时 / 每隔一段时间自动上传，
 * 登录时或本地无缓存时自动下载）：
 *  - 本地仍以 ExternalPrefs 的按用户 SP 作为缓存（书架/记录已按用户；书签本类改走按用户）。
 *  - pullAll(ctx)：登录成功 / 返回会话时调用，从服务器拉全量覆盖本地缓存
 *    （记录按「最大阅读位置」合并，避免多设备回退）。
 *  - flushAll(ctx)：退出登录 / 每 5 分钟 / 切后台时调用，把本地三类数据上传到服务器（幂等 upsert）。
 *  - 删除/移除是离散的主动操作，立即调服务器删除（避免服务器残留孤儿数据），本地同步删除。
 *
 * 仅登录态生效；游客（userId==0）不拉不传。
 */
public final class ExternalSyncManager {

    private static final long PERIOD_MS = 5 * 60 * 1000L; // 5 分钟
    private static final MediaType JSON = MediaType.parse("application/json");

    private static ExternalSyncManager INSTANCE;

    private final Context appCtx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable periodicTask;
    private boolean periodicStarted = false;

    private ExternalSyncManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static synchronized ExternalSyncManager getInstance(Context ctx) {
        if (INSTANCE == null) INSTANCE = new ExternalSyncManager(ctx);
        return INSTANCE;
    }

    private long uid() {
        return LoginHelper.getUserIdLong(appCtx);
    }

    /**
     * 显式携带 Authorization：退出登录时会先入队上传再清 user_info，
     * 若依赖拦截器在执行时读 SP，token 可能已被清空 → 上传 401 丢数据。
     * 在「调用时刻」捕获 token 烘进请求头，保证登出上传用对凭证。
     */
    private String bearer() {
        String token = LoginHelper.getToken(appCtx);
        return "Bearer " + (token == null ? "" : token);
    }

    // ============ 登录：从服务器拉全量 ============
    public void pullAll() {
        long userId = uid();
        if (userId == 0) return;
        ApiService api = RetrofitClient.getApiService();

        // 三类拉取全部结束后通知界面刷新（否则书架/记录页在响应回来前已加载完，显示为空）
        final java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(3);
        final Runnable onOneDone = () -> { if (pending.decrementAndGet() == 0) notifyPulled(); };

        // 阅读记录
        api.getExternalReadingList(userId).enqueue(new Callback<ApiResponse<List<Map<String, Object>>>>() {
            @Override public void onResponse(Call<ApiResponse<List<Map<String, Object>>>> call,
                                             Response<ApiResponse<List<Map<String, Object>>>> resp) {
                if (resp.isSuccessful() && resp.body() != null && resp.body().isSuccess()) {
                    writeRecords(resp.body().getData(), true);
                }
                onOneDone.run();
            }
            @Override public void onFailure(Call<ApiResponse<List<Map<String, Object>>>> call, Throwable t) {
                onOneDone.run();
            }
        });

        // 书架
        api.getExternalBookshelfList(userId).enqueue(new Callback<ApiResponse<List<Map<String, Object>>>>() {
            @Override public void onResponse(Call<ApiResponse<List<Map<String, Object>>>> call,
                                             Response<ApiResponse<List<Map<String, Object>>>> resp) {
                if (resp.isSuccessful() && resp.body() != null && resp.body().isSuccess()) {
                    writeShelf(resp.body().getData());
                }
                onOneDone.run();
            }
            @Override public void onFailure(Call<ApiResponse<List<Map<String, Object>>>> call, Throwable t) {
                onOneDone.run();
            }
        });

        // 书签
        api.getExternalBookmarkList(userId).enqueue(new Callback<ApiResponse<List<Map<String, Object>>>>() {
            @Override public void onResponse(Call<ApiResponse<List<Map<String, Object>>>> call,
                                             Response<ApiResponse<List<Map<String, Object>>>> resp) {
                if (resp.isSuccessful() && resp.body() != null && resp.body().isSuccess()) {
                    writeBookmarks(resp.body().getData());
                }
                onOneDone.run();
            }
            @Override public void onFailure(Call<ApiResponse<List<Map<String, Object>>>> call, Throwable t) {
                onOneDone.run();
            }
        });
    }

    // ============ 退出/定时：上传本地全量 ============
    public void flushAll() {
        long userId = uid();
        if (userId == 0) return;
        flushRecords(userId);
        flushShelf(userId);
        flushBookmarks(userId);
    }

    // ============ 离散操作：立即上传某一类（用户主动动作，期望即时入库）============
    /** 立即上传外站书架（加入/移出书架后调用）。 */
    public void flushShelfOnly() { long u = uid(); if (u != 0) flushShelf(u); }

    /** 立即上传外站书签（添加书签后调用）。 */
    public void flushBookmarksOnly() { long u = uid(); if (u != 0) flushBookmarks(u); }

    /** 立即上传外站阅读记录。 */
    public void flushRecordsOnly() { long u = uid(); if (u != 0) flushRecords(u); }

    // ============ 周期性上传 ============
    public void startPeriodic() {
        if (periodicStarted) return;
        periodicStarted = true;
        periodicTask = new Runnable() {
            @Override public void run() {
                flushAll();
                if (periodicStarted) handler.postDelayed(this, PERIOD_MS);
            }
        };
        handler.postDelayed(periodicTask, PERIOD_MS);
    }

    public void stopPeriodic() {
        periodicStarted = false;
        if (periodicTask != null) handler.removeCallbacks(periodicTask);
    }

    // ============ 拉取完成通知（供界面在同步完成后刷新，避免"登录后书架空白"） ============
    public interface PullListener { void onPulled(); }

    private final java.util.List<PullListener> pullListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addPullListener(PullListener l) {
        if (l != null && !pullListeners.contains(l)) pullListeners.add(l);
    }

    public void removePullListener(PullListener l) {
        if (l != null) pullListeners.remove(l);
    }

    private void notifyPulled() {
        handler.post(() -> {
            for (PullListener l : pullListeners) {
                try { l.onPulled(); } catch (Throwable ignored) {}
            }
        });
    }

    // ============ 删除：立即同步服务器（离散主动操作） ============
    public void removeShelfRemote(String sourceType, String sourceBookId) {
        long userId = uid();
        if (userId == 0 || sourceType == null || sourceBookId == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("userId", userId);
            o.put("sourceType", sourceType);
            o.put("sourceBookId", sourceBookId);
        } catch (JSONException e) { return; }
        RequestBody body = RequestBody.create(o.toString(), JSON);
        RetrofitClient.getApiService().removeExternalBookshelf(bearer(), body)
                .enqueue(EMPTY_CALLBACK);
    }

    public void deleteBookmarkRemote(String sourceType, String sourceBookId, int chapterIndex) {
        long userId = uid();
        if (userId == 0 || sourceType == null || sourceBookId == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("userId", userId);
            o.put("sourceType", sourceType);
            o.put("sourceBookId", sourceBookId);
            o.put("chapterIndex", chapterIndex);
        } catch (JSONException e) { return; }
        RequestBody body = RequestBody.create(o.toString(), JSON);
        RetrofitClient.getApiService().deleteExternalBookmark(bearer(), body)
                .enqueue(EMPTY_CALLBACK);
    }

    /** 删除外站阅读记录（立即同步服务器）：否则下次启动 pullAll 会把它拉回来。 */
    public void deleteReadingRecordRemote(String sourceType, String sourceBookId) {
        long userId = uid();
        if (userId == 0 || sourceType == null || sourceBookId == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("userId", userId);
            o.put("sourceType", sourceType);
            o.put("sourceBookId", sourceBookId);
        } catch (JSONException e) { return; }
        RequestBody body = RequestBody.create(o.toString(), JSON);
        RetrofitClient.getApiService().deleteExternalReading(bearer(), body)
                .enqueue(EMPTY_CALLBACK);
    }

    // ===================== 本地写入（拉取后） =====================

    private void writeRecords(List<Map<String, Object>> list, boolean mergeMax) {
        if (list == null) return;
        SharedPreferences sp = appCtx.getSharedPreferences(ExternalPrefs.recordsName(appCtx), Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        for (Map<String, Object> m : list) {
            String st = str(m.get("sourceType"));
            String su = str(m.get("sourceBookId"));
            if (st.isEmpty() || su.isEmpty()) continue;
            String base = "ext_" + st + "|" + su;
            int ch = num(m.get("chapterIndex"), -1);
            if (ch < 0) continue;
            int localCh = sp.getInt(base + "_chapterIndex", -1);
            if (mergeMax && localCh >= ch) {
                // 本地更靠后则保留本地
                continue;
            }
            // 完整写入 App 读取所需的全部键（与 ReadActivity.saveReadingRecord 的外站分支一致），
            // 否则 ReadingRecordActivity.loadExternalRecords 读不到书名/作者/封面/书源会显示空白。
            e.putString(base + "_sourceType", st);
            e.putString(base + "_sourceUrl", su);
            e.putString(base + "_bookName", str(m.get("bookTitle")));
            e.putString(base + "_author", str(m.get("bookAuthor")));
            e.putString(base + "_cover", str(m.get("coverUrl")));
            e.putInt(base + "_chapterIndex", ch);
            e.putString(base + "_chapterTitle", str(m.get("chapterTitle")));
            e.putInt(base + "_page", num(m.get("page"), 0));
            e.putLong(base + "_readTime", num(m.get("updateTime"), System.currentTimeMillis()));
        }
        e.apply();
    }

    private void writeShelf(List<Map<String, Object>> list) {
        if (list == null) return;
        SharedPreferences sp = appCtx.getSharedPreferences(ExternalPrefs.shelfName(appCtx), Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        for (Map<String, Object> m : list) {
            String st = str(m.get("sourceType"));
            String su = str(m.get("sourceBookId"));
            if (st.isEmpty() || su.isEmpty()) continue;
            String val = str(m.get("bookTitle")) + "|||"
                    + str(m.get("bookAuthor")) + "|||"
                    + str(m.get("coverUrl")) + "|||"
                    + "" + "|||"
                    + num(m.get("chapterCount"), 0);
            e.putString(st + "|" + su, val);
        }
        e.apply();
    }

    private void writeBookmarks(List<Map<String, Object>> list) {
        if (list == null) return;
        SharedPreferences sp = appCtx.getSharedPreferences(ExternalPrefs.bookmarkName(appCtx), Context.MODE_PRIVATE);
        int count = sp.getInt("bookmark_count", 0);

        // 非破坏性合并：保留本地已有书签，仅追加服务器新增的（按 书源+章节 去重）。
        // 否则服务器为空/未部署时，pullAll 会把本地书签清空。
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            String p = "bm_" + i + "_";
            existing.add(sp.getString(p + "sourceType", "") + "|"
                    + sp.getString(p + "sourceBookId", "") + "|"
                    + sp.getInt(p + "chapterIndex", -1));
        }

        SharedPreferences.Editor e = sp.edit();
        int n = count;
        for (Map<String, Object> m : list) {
            String st = str(m.get("sourceType"));
            String su = str(m.get("sourceBookId"));
            if (st.isEmpty() || su.isEmpty()) continue;
            int ch = num(m.get("chapterIndex"), -1);
            if (ch < 0) continue;
            String key = st + "|" + su + "|" + ch;
            if (existing.contains(key)) continue;
            String p = "bm_" + n + "_";
            e.putString(p + "sourceType", st);
            e.putString(p + "sourceBookId", su);
            e.putInt(p + "chapterIndex", ch);
            e.putString(p + "chapterTitle", str(m.get("chapterTitle")));
            e.putInt(p + "scrollPosition", num(m.get("scrollPosition"), 0));
            e.putString(p + "preview", str(m.get("previewText")));
            e.putString(p + "note", str(m.get("note")));
            e.putLong(p + "time", num(m.get("createdAt"), System.currentTimeMillis()));
            existing.add(key);
            n++;
        }
        e.putInt("bookmark_count", n);
        e.apply();
    }

    // ===================== 本地读取并上传（flush） =====================

    private void flushRecords(long userId) {
        SharedPreferences sp = appCtx.getSharedPreferences(ExternalPrefs.recordsName(appCtx), Context.MODE_PRIVATE);
        for (String key : new ArrayList<>(sp.getAll().keySet())) {
            if (!key.endsWith("_chapterIndex")) continue;
            String base = key.substring(0, key.length() - "_chapterIndex".length());
            // base = "ext_<sourceType>|<sourceBookId>"
            if (!base.startsWith("ext_")) continue;
            String composite = base.substring("ext_".length());
            int sep = composite.indexOf('|');
            if (sep <= 0) continue;
            String st = composite.substring(0, sep);
            String su = composite.substring(sep + 1);
            int ch = sp.getInt(key, -1);
            if (ch < 0) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("userId", userId);
                o.put("sourceType", st);
                o.put("sourceBookId", su);
                o.put("chapterIndex", ch);
                o.put("chapterTitle", sp.getString(base + "_chapterTitle", ""));
                o.put("page", sp.getInt(base + "_page", 0));
                o.put("bookTitle", sp.getString(base + "_bookName", ""));
                o.put("bookAuthor", sp.getString(base + "_author", ""));
                o.put("coverUrl", sp.getString(base + "_cover", ""));
            } catch (JSONException e) { continue; }
            RetrofitClient.getApiService().saveExternalProgress(bearer(), RequestBody.create(o.toString(), JSON))
                    .enqueue(EMPTY_CALLBACK);
        }
    }

    private void flushShelf(long userId) {
        SharedPreferences sp = appCtx.getSharedPreferences(ExternalPrefs.shelfName(appCtx), Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> en : new ArrayList<>(sp.getAll().entrySet())) {
            if (!(en.getValue() instanceof String)) continue;
            String key = en.getKey();
            int sep = key.indexOf('|');
            if (sep <= 0) continue;
            String st = key.substring(0, sep);
            String su = key.substring(sep + 1);
            String val = (String) en.getValue();
            String[] parts = val.split("\\|\\|\\|", -1);
            JSONObject o = new JSONObject();
            try {
                o.put("userId", userId);
                o.put("sourceType", st);
                o.put("sourceBookId", su);
                o.put("bookTitle", parts.length > 0 ? parts[0] : "");
                o.put("bookAuthor", parts.length > 1 ? parts[1] : "");
                o.put("coverUrl", parts.length > 2 ? parts[2] : "");
                o.put("chapterCount", parts.length > 4 ? num(parts[4], 0) : 0);
            } catch (JSONException e) { continue; }
            RetrofitClient.getApiService().addExternalBookshelf(bearer(), RequestBody.create(o.toString(), JSON))
                    .enqueue(EMPTY_CALLBACK);
        }
    }

    private void flushBookmarks(long userId) {
        SharedPreferences sp = appCtx.getSharedPreferences(ExternalPrefs.bookmarkName(appCtx), Context.MODE_PRIVATE);
        int count = sp.getInt("bookmark_count", 0);
        for (int i = 0; i < count; i++) {
            String p = "bm_" + i + "_";
            String st = sp.getString(p + "sourceType", "");
            String su = sp.getString(p + "sourceBookId", "");
            if (st.isEmpty() || su.isEmpty()) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("userId", userId);
                o.put("sourceType", st);
                o.put("sourceBookId", su);
                o.put("chapterIndex", sp.getInt(p + "chapterIndex", 0));
                o.put("chapterTitle", sp.getString(p + "chapterTitle", ""));
                o.put("scrollPosition", sp.getInt(p + "scrollPosition", 0));
                o.put("previewText", sp.getString(p + "preview", ""));
                o.put("note", sp.getString(p + "note", ""));
            } catch (JSONException e) { continue; }
            RetrofitClient.getApiService().addExternalBookmark(bearer(), RequestBody.create(o.toString(), JSON))
                    .enqueue(EMPTY_CALLBACK);
        }
    }

    // ===================== 工具 =====================

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static int num(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt((String) o); } catch (Exception e) { return def; }
        }
        return def;
    }
    private static long num(Object o, long def) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try { return Long.parseLong((String) o); } catch (Exception e) { return def; }
        }
        return def;
    }

    private static final Callback<ApiResponse<Void>> EMPTY_CALLBACK = new Callback<ApiResponse<Void>>() {
        @Override public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> resp) {}
        @Override public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {}
    };
}
