package com.example.myapplication.api;

import android.util.Log;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import retrofit2.Call;

/**
 * 网络请求管理器
 * 跟踪和取消网络请求，防止内存泄漏和资源浪费
 */
public class NetworkRequestManager {

    private static final String TAG = "NetworkRequestManager";

    private static NetworkRequestManager instance;

    private final Map<String, Call<?>> activeCalls = new ConcurrentHashMap<>();

    private NetworkRequestManager() {
    }

    public static synchronized NetworkRequestManager getInstance() {
        if (instance == null) {
            instance = new NetworkRequestManager();
        }
        return instance;
    }

    /**
     * 注册一个网络请求
     * @param call 请求对象
     * @return 请求的唯一标识符
     */
    public String registerCall(Call<?> call) {
        String callId = UUID.randomUUID().toString();
        activeCalls.put(callId, call);
        Log.d(TAG, "Registered call: " + callId + ", active calls: " + activeCalls.size());
        return callId;
    }

    /**
     * 取消指定的网络请求
     * @param callId 请求标识符
     */
    public void cancelCall(String callId) {
        Call<?> call = activeCalls.remove(callId);
        if (call != null && !call.isCanceled() && !call.isExecuted()) {
            call.cancel();
            Log.d(TAG, "Cancelled call: " + callId);
        }
    }

    /**
     * 取消所有网络请求
     */
    @SuppressWarnings("unused")
    public void cancelAllCalls() {
        for (Map.Entry<String, Call<?>> entry : activeCalls.entrySet()) {
            Call<?> call = entry.getValue();
            if (!call.isCanceled() && !call.isExecuted()) {
                call.cancel();
            }
        }
        int count = activeCalls.size();
        activeCalls.clear();
        Log.d(TAG, "Cancelled all calls, count: " + count);
    }

    /**
     * 标记请求完成
     * @param callId 请求标识符
     */
    public void completeCall(String callId) {
        activeCalls.remove(callId);
        Log.d(TAG, "Completed call: " + callId + ", remaining: " + activeCalls.size());
    }

    /**
     * 获取当前活动请求数量
     */
    @SuppressWarnings("unused")
    public int getActiveCallCount() {
        return activeCalls.size();
    }

    /**
     * 取消指定标签的所有请求
     * 需要使用CompositeCallback来设置标签
     * @deprecated 建议使用ScopedRequestManager代替
     */
    @Deprecated
    @SuppressWarnings("unused")
    public void cancelCallsByTag(String tag) {
        // 当前实现不支持标签，建议使用ScopedRequestManager
        Log.w(TAG, "cancelCallsByTag not supported, use ScopedRequestManager instead");
    }

    /**
     * 作用域请求管理器
     * 用于管理特定组件（如Activity/Fragment）的请求
     */
    public static class ScopedRequestManager {

        private final Map<String, String> callIds = new ConcurrentHashMap<>();
        private boolean destroyed = false;

        /**
         * 注册一个请求到当前作用域
         * @param call 请求对象
         * @return 请求的唯一标识符
         */
        public String register(Call<?> call) {
            if (destroyed) {
                Log.w(TAG, "Cannot register call: scope is destroyed");
                return null;
            }
            String callId = NetworkRequestManager.getInstance().registerCall(call);
            callIds.put(callId, callId);
            return callId;
        }

        /**
         * 取消当前作用域的所有请求
         */
        public void cancelAll() {
            if (destroyed) return;
            for (String callId : callIds.keySet()) {
                NetworkRequestManager.getInstance().cancelCall(callId);
            }
            callIds.clear();
        }

        /**
         * 标记作用域已销毁，自动取消所有请求
         */
        @SuppressWarnings("unused")
        public void destroy() {
            if (destroyed) return;
            cancelAll();
            destroyed = true;
        }

        /**
         * 检查作用域是否已销毁
         */
        @SuppressWarnings("unused")
        public boolean isDestroyed() {
            return destroyed;
        }
    }
}