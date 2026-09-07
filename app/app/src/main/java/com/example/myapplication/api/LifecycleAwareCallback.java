package com.example.myapplication.api;

import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 安全的生命周期感知Callback
 * 结合了SafeCallback和NetworkRequestManager的功能
 * 自动处理生命周期检查和请求取消
 */
public abstract class LifecycleAwareCallback<T> implements Callback<T> {

    private final LifecycleHelper lifecycleHelper;

    /**
     * 为Activity创建Callback
     * @param activity 绑定的Activity
     * @param requestManager 可选的请求管理器
     */
    public LifecycleAwareCallback(@NonNull Activity activity, @Nullable NetworkRequestManager.ScopedRequestManager requestManager) {
        this.lifecycleHelper = new ActivityLifecycleHelper(activity, requestManager);
    }

    /**
     * 为Fragment创建Callback
     * @param fragment 绑定的Fragment
     * @param requestManager 可选的请求管理器
     */
    public LifecycleAwareCallback(@NonNull Fragment fragment, @Nullable NetworkRequestManager.ScopedRequestManager requestManager) {
        this.lifecycleHelper = new FragmentLifecycleHelper(fragment, requestManager);
    }

    @Override
    public final void onResponse(@NonNull Call<T> call, @NonNull Response<T> response) {
        if (lifecycleHelper.onBeforeResponse(call)) {
            onSafeResponse(call, response);
            lifecycleHelper.onAfterResponse(call);
        }
    }

    @Override
    public final void onFailure(@NonNull Call<T> call, @NonNull Throwable t) {
        if (lifecycleHelper.onBeforeFailure(call)) {
            onSafeFailure(call, t);
            lifecycleHelper.onAfterFailure(call);
        }
    }

    /**
     * 当响应成功且组件仍然存活时调用
     * 在主线程执行
     */
    protected abstract void onSafeResponse(@NonNull Call<T> call, @NonNull Response<T> response);

    /**
     * 当请求失败且组件仍然存活时调用
     * 在主线程执行
     */
    protected abstract void onSafeFailure(@NonNull Call<T> call, @NonNull Throwable t);

    /**
     * 生命周期助手接口
     */
    private interface LifecycleHelper {
        boolean onBeforeResponse(Call<?> call);
        boolean onBeforeFailure(Call<?> call);
        void onAfterResponse(Call<?> call);
        void onAfterFailure(Call<?> call);
    }

    /**
     * Activity生命周期助手
     */
    private static class ActivityLifecycleHelper implements LifecycleHelper {
        private final Activity activity;
        private final NetworkRequestManager.ScopedRequestManager requestManager;
        private String callId;

        ActivityLifecycleHelper(Activity activity, NetworkRequestManager.ScopedRequestManager requestManager) {
            this.activity = activity;
            this.requestManager = requestManager;
        }

        @Override
        public boolean onBeforeResponse(Call<?> call) {
            callId = registerIfNecessary(call);
            return !activity.isFinishing() && !activity.isDestroyed();
        }

        @Override
        public boolean onBeforeFailure(Call<?> call) {
            callId = registerIfNecessary(call);
            return !activity.isFinishing() && !activity.isDestroyed();
        }

        @Override
        public void onAfterResponse(Call<?> call) {
            completeIfNecessary();
        }

        @Override
        public void onAfterFailure(Call<?> call) {
            completeIfNecessary();
        }

        private String registerIfNecessary(Call<?> call) {
            if (requestManager != null && callId == null) {
                return requestManager.register(call);
            }
            return callId;
        }

        private void completeIfNecessary() {
            if (requestManager != null && callId != null) {
                NetworkRequestManager.getInstance().completeCall(callId);
                callId = null;
            }
        }
    }

    /**
     * Fragment生命周期助手
     */
    private static class FragmentLifecycleHelper implements LifecycleHelper {
        private final Fragment fragment;
        private final NetworkRequestManager.ScopedRequestManager requestManager;
        private String callId;

        FragmentLifecycleHelper(Fragment fragment, NetworkRequestManager.ScopedRequestManager requestManager) {
            this.fragment = fragment;
            this.requestManager = requestManager;
        }

        @Override
        public boolean onBeforeResponse(Call<?> call) {
            callId = registerIfNecessary(call);
            return fragment.isAdded() && !fragment.isDetached()
                    && fragment.getActivity() != null
                    && !fragment.getActivity().isFinishing()
                    && !fragment.getActivity().isDestroyed();
        }

        @Override
        public boolean onBeforeFailure(Call<?> call) {
            callId = registerIfNecessary(call);
            return fragment.isAdded() && !fragment.isDetached()
                    && fragment.getActivity() != null
                    && !fragment.getActivity().isFinishing()
                    && !fragment.getActivity().isDestroyed();
        }

        @Override
        public void onAfterResponse(Call<?> call) {
            completeIfNecessary();
        }

        @Override
        public void onAfterFailure(Call<?> call) {
            completeIfNecessary();
        }

        private String registerIfNecessary(Call<?> call) {
            if (requestManager != null && callId == null) {
                return requestManager.register(call);
            }
            return callId;
        }

        private void completeIfNecessary() {
            if (requestManager != null && callId != null) {
                NetworkRequestManager.getInstance().completeCall(callId);
                callId = null;
            }
        }
    }
}