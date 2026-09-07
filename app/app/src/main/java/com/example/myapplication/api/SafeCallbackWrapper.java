package com.example.myapplication.api;

import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 安全的回调包装器
 * 用于在Manager类中包装回调，自动检查Activity/Fragment生命周期
 */
@SuppressWarnings("unused")
public class SafeCallbackWrapper {

    /**
     * 包装Callback，添加Activity生命周期检查
     */
    public static <T> Callback<T> wrapActivity(Activity activity, Callback<T> callback) {
        if (activity == null || callback == null) {
            return callback;
        }
        return new Callback<T>() {
            @Override
            public void onResponse(@NonNull Call<T> call, @NonNull Response<T> response) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    callback.onResponse(call, response);
                }
            }

            @Override
            public void onFailure(@NonNull Call<T> call, @NonNull Throwable t) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    callback.onFailure(call, t);
                }
            }
        };
    }

    /**
     * 包装Callback，添加Fragment生命周期检查
     */
    public static <T> Callback<T> wrapFragment(Fragment fragment, Callback<T> callback) {
        if (fragment == null || callback == null) {
            return callback;
        }
        return new Callback<T>() {
            @Override
            public void onResponse(@NonNull Call<T> call, @NonNull Response<T> response) {
                if (fragment.isAdded() && !fragment.isDetached()
                        && fragment.getActivity() != null
                        && !fragment.getActivity().isFinishing()
                        && !fragment.getActivity().isDestroyed()) {
                    callback.onResponse(call, response);
                }
            }

            @Override
            public void onFailure(@NonNull Call<T> call, @NonNull Throwable t) {
                if (fragment.isAdded() && !fragment.isDetached()
                        && fragment.getActivity() != null
                        && !fragment.getActivity().isFinishing()
                        && !fragment.getActivity().isDestroyed()) {
                    callback.onFailure(call, t);
                }
            }
        };
    }
}