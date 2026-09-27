package com.example.readingapp.service;

import com.example.readingapp.entity.ApkPush;

import java.util.List;

public interface ApkPushService {

    ApkPush pushApk(String fileName, String version, String updateNotes);

    ApkPush getCurrentPushedApk();

    List<ApkPush> getAllApkPushRecords();

    void deleteApkPush(Long id);
}