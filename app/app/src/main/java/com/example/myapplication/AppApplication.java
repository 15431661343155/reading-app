package com.example.myapplication;

import android.app.Application;

import com.example.myapplication.api.RetrofitClient;

public class AppApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        RetrofitClient.init(this);
    }
}
