package com.example.myapplication;

import android.app.Application;

import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.utils.ThemeManager;

public class AppApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 日夜偏好必须在全局首个页面/对话框创建前生效，否则首帧会按日间资源解析
        ThemeManager.applyNightMode(this);
        RetrofitClient.init(this);
    }
}
