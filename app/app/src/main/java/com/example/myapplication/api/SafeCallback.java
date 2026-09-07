package com.example.myapplication.api;

import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 安全的Callback包装类
 * 自动处理Activity/Fragment生命周期检查，防止回调执行在已销毁的组件上
 */
public abstract class SafeCallback<T> implements Callback<T> {

    private final LifecycleOwner lifecycleOwner;

    /**
     * 创建一个安全的Callback，使用Activity作为生命周期所有者
     */
    public static <T> SafeCallback<T> from(@NonNull Activity activity, SafeCallback<T> delegate) {
        return new SafeCallback<T>(new ActivityLifecycleOwner(activity)) {
            @Override
            public void onSafeSuccess(Call<T> call, Response<T> response) {
                delegate.onSafeSuccess(call, response);
            }

            @Override
            public void onSafeError(Call<T> call, Throwable t) {
                delegate.onSafeError(call, t);
            }
        };
    }

    /**
     * 创建一个安全的Callback，使用Fragment作为生命周期所有者
     */
    public static <T> SafeCallback<T> from(@NonNull Fragment fragment, SafeCallback<T> delegate) {
        return new SafeCallback<T>(new FragmentLifecycleOwner(fragment)) {
            @Override
            public void onSafeSuccess(Call<T> call, Response<T> response) {
                delegate.onSafeSuccess(call, response);
            }

            @Override
            public void onSafeError(Call<T> call, Throwable t) {
                delegate.onSafeError(call, t);
            }
        };
    }

    /**
     * 创建一个安全的Callback，使用Activity作为生命周期所有者（直接实现，无需delegate）
     */
    public static <T> SafeCallback<T> from(@NonNull Activity activity,
                                           final OnSuccess<T> onSuccess,
                                           final OnError<T> onError) {
        return new SafeCallback<T>(new ActivityLifecycleOwner(activity)) {
            @Override
            protected void onSafeSuccess(Call<T> call, Response<T> response) {
                onSuccess.onSafeSuccess(call, response);
            }

            @Override
            protected void onSafeError(Call<T> call, Throwable t) {
                onError.onSafeError(call, t);
            }
        };
    }

    /**
     * 创建一个安全的Callback，使用Fragment作为生命周期所有者（直接实现，无需delegate）
     */
    public static <T> SafeCallback<T> from(@NonNull Fragment fragment,
                                           final OnSuccess<T> onSuccess,
                                           final OnError<T> onError) {
        return new SafeCallback<T>(new FragmentLifecycleOwner(fragment)) {
            @Override
            protected void onSafeSuccess(Call<T> call, Response<T> response) {
                onSuccess.onSafeSuccess(call, response);
            }

            @Override
            protected void onSafeError(Call<T> call, Throwable t) {
                onError.onSafeError(call, t);
            }
        };
    }

    private SafeCallback(LifecycleOwner lifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner;
    }

    @Override
    public final void onResponse(@NonNull Call<T> call, @NonNull Response<T> response) {
        if (!lifecycleOwner.isAlive()) {
            return;
        }
        onSafeSuccess(call, response);
    }

    @Override
    public final void onFailure(@NonNull Call<T> call, @NonNull Throwable t) {
        if (!lifecycleOwner.isAlive()) {
            return;
        }
        onSafeError(call, t);
    }

    /**
     * 当响应成功且组件仍然存活时调用
     */
    protected abstract void onSafeSuccess(Call<T> call, Response<T> response);

    /**
     * 当请求失败且组件仍然存活时调用
     */
    protected abstract void onSafeError(Call<T> call, Throwable t);

    /**
     * 生命周期所有者接口
     */
    private interface LifecycleOwner {
        boolean isAlive();
    }

    /**
     * 成功回调函数式接口
     */
    public interface OnSuccess<T> {
        void onSafeSuccess(Call<T> call, Response<T> response);
    }

    /**
     * 失败回调函数式接口
     */
    public interface OnError<T> {
        void onSafeError(Call<T> call, Throwable t);
    }

    /**
     * Activity生命周期所有者
     */
    private static class ActivityLifecycleOwner implements LifecycleOwner {
        private final Activity activity;

        ActivityLifecycleOwner(Activity activity) {
            this.activity = activity;
        }

        @Override
        public boolean isAlive() {
            return !activity.isFinishing() && !activity.isDestroyed();
        }
    }

    /**
     * Fragment生命周期所有者
     */
    private static class FragmentLifecycleOwner implements LifecycleOwner {
        private final Fragment fragment;

        FragmentLifecycleOwner(Fragment fragment) {
            this.fragment = fragment;
        }

        @Override
        public boolean isAlive() {
            return fragment.isAdded() && !fragment.isDetached()
                    && (fragment.getActivity() != null
                    && !fragment.getActivity().isFinishing()
                    && !fragment.getActivity().isDestroyed());
        }
    }
}