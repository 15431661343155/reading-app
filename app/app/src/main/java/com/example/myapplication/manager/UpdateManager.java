package com.example.myapplication.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ApkPush;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class UpdateManager {

    private static long downloadId = -1;
    private static ApkPush currentApkPush;
    private static BroadcastReceiver downloadReceiver;
    private static AlertDialog updateDialog;
    private static ProgressBar progressBar;
    private static TextView tvDownloadStatus;
    private static TextView tvDownloadPercent;
    private static LinearLayout layoutButtons;
    private static LinearLayout layoutProgress;
    private static Handler progressHandler;
    private static Button btnCancel;
    private static Button btnUpdate;
    private static boolean isDownloading = false;

    public interface UpdateCheckCallback {
        void onUpdateAvailable(ApkPush apkPush);
        void onNoUpdate();
        void onError(String message);
    }

    public static void checkUpdate(final Context context, final UpdateCheckCallback callback) {
        String versionName = "";
        try {
            versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            versionName = "1.5.4";
        }

        RetrofitClient.getApiService().checkUpdate(versionName).enqueue(new Callback<ApiResponse<ApkPush>>() {
            @Override
            public void onResponse(Call<ApiResponse<ApkPush>> call, Response<ApiResponse<ApkPush>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    ApiResponse<ApkPush> apiResponse = response.body();
                    if (apiResponse.getData() != null) {
                        if (callback != null) callback.onUpdateAvailable(apiResponse.getData());
                    } else {
                        if (callback != null) callback.onNoUpdate();
                    }
                } else {
                    if (callback != null) callback.onError("检查更新失败");
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<ApkPush>> call, Throwable t) {
                if (callback != null) callback.onError("网络错误");
            }
        });
    }

    public static void showUpdateDialog(final Context context, final ApkPush apkPush) {
        currentApkPush = apkPush;
        isDownloading = false;

        final View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update, null);

        final TextView tvVersion = dialogView.findViewById(R.id.tv_version);
        final TextView tvUpdateContent = dialogView.findViewById(R.id.tv_update_content);
        final TextView tvFileSize = dialogView.findViewById(R.id.tv_file_size);
        btnCancel = dialogView.findViewById(R.id.btn_cancel);
        btnUpdate = dialogView.findViewById(R.id.btn_update);
        layoutProgress = dialogView.findViewById(R.id.layout_progress);
        progressBar = dialogView.findViewById(R.id.progress_bar);
        tvDownloadStatus = dialogView.findViewById(R.id.tv_download_status);
        tvDownloadPercent = dialogView.findViewById(R.id.tv_download_percent);
        layoutButtons = dialogView.findViewById(R.id.layout_buttons);

        tvVersion.setText("v" + apkPush.getVersion());
        tvUpdateContent.setText("发现新版本 v" + apkPush.getVersion() + "，请及时更新以获得更好的体验！");
        tvFileSize.setText("大小：" + formatFileSize(apkPush.getFileSize()));

        layoutProgress.setVisibility(View.GONE);
        layoutButtons.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(dialogView);
        builder.setCancelable(true);
        updateDialog = builder.create();

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelDownload(context);
                updateDialog.dismiss();
            }
        });

        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isDownloading) return;
                if (!hasInstallPermission(context)) {
                    showInstallPermissionDialog(context);
                    return;
                }
                startDownload(context);
            }
        });

        updateDialog.show();
    }

    private static boolean hasInstallPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    private static void showInstallPermissionDialog(final Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("需要安装权限");
        builder.setMessage("为了安装更新包，需要开启「允许来自此来源的应用」权限。\n\n请在跳转的设置界面中，开启「书阁」的安装权限后返回。");
        builder.setPositiveButton("去开启", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                dialog.dismiss();
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private static void startDownload(final Context context) {
        isDownloading = true;
        layoutProgress.setVisibility(View.VISIBLE);
        layoutButtons.setVisibility(View.GONE);
        tvDownloadStatus.setText("准备下载...");
        tvDownloadPercent.setText("0%");
        progressBar.setProgress(0);

        // 检查是否已有下载文件
        File cacheDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (cacheDir == null) {
            tvDownloadStatus.setText("存储不可用");
            return;
        }
        File existingFile = new File(cacheDir, currentApkPush.getFileName());
        if (existingFile.exists() && existingFile.length() > 0) {
            String existingMd5 = calculateMD5(existingFile);
            if (existingMd5 != null && existingMd5.equalsIgnoreCase(currentApkPush.getMd5())) {
                tvDownloadStatus.setText("已下载完成，准备安装...");
                tvDownloadPercent.setText("100%");
                progressBar.setProgress(100);
                openInstaller(context, existingFile);
                return;
            } else {
                existingFile.delete();
            }
        }

        // 开始下载
        String downloadUrl = RetrofitClient.getFullImageUrl(currentApkPush.getFilePath());

        android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(Uri.parse(downloadUrl));
        request.setTitle("书阁阅读更新");
        request.setDescription("正在下载新版本...");
        request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, currentApkPush.getFileName());

        android.app.DownloadManager downloadManager = (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            tvDownloadStatus.setText("下载服务不可用");
            isDownloading = false;
            layoutButtons.setVisibility(View.VISIBLE);
            return;
        }
        downloadId = downloadManager.enqueue(request);

        progressHandler = new Handler(Looper.getMainLooper());
        progressHandler.post(new Runnable() {
            @Override
            public void run() {
                queryDownloadProgress(context, downloadId);
            }
        });

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    long id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        handleDownloadComplete(context, downloadId);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        IntentFilter filter = new IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(context, downloadReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    private static void queryDownloadProgress(final Context context, final long downloadId) {
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            android.app.DownloadManager.Query query = new android.app.DownloadManager.Query().setFilterById(downloadId);
            Cursor cursor = dm.query(query);

            if (cursor != null && cursor.moveToFirst()) {
                int bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                int totalSize = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));

                if (totalSize > 0) {
                    int progress = (int) ((bytesDownloaded * 100L) / totalSize);
                    if (progressBar != null) progressBar.setProgress(progress);
                    if (tvDownloadPercent != null) tvDownloadPercent.setText(progress + "%");
                    if (tvDownloadStatus != null) {
                        if (status == android.app.DownloadManager.STATUS_RUNNING) {
                            tvDownloadStatus.setText("下载中 " + formatFileSize((long) bytesDownloaded) + " / " + formatFileSize((long) totalSize));
                        } else if (status == android.app.DownloadManager.STATUS_PAUSED) {
                            tvDownloadStatus.setText("下载已暂停");
                        } else if (status == android.app.DownloadManager.STATUS_PENDING) {
                            tvDownloadStatus.setText("等待下载...");
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (progressHandler != null && isDownloading) {
            progressHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    queryDownloadProgress(context, downloadId);
                }
            }, 500);
        }
    }

    private static void handleDownloadComplete(final Context context, long downloadId) {
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            android.app.DownloadManager.Query query = new android.app.DownloadManager.Query().setFilterById(downloadId);
            Cursor cursor = dm.query(query);

            if (cursor != null && cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
                cursor.close();

                if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                    File cacheDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (cacheDir == null) return;
                    final File apkFile = new File(cacheDir, currentApkPush.getFileName());
                    String fileMd5 = calculateMD5(apkFile);

                    if (fileMd5 != null && fileMd5.equalsIgnoreCase(currentApkPush.getMd5())) {
                        tvDownloadStatus.setText("下载完成，请点击安装");
                        tvDownloadPercent.setText("100%");
                        progressBar.setProgress(100);
                        isDownloading = false;
                        stopProgress();

                        // 显示安装按钮
                        layoutButtons.setVisibility(View.VISIBLE);
                        btnUpdate.setText("立即安装");
                        btnCancel.setText("取消");
                        btnUpdate.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                openInstaller(context, apkFile);
                            }
                        });
                    } else {
                        apkFile.delete();
                        tvDownloadStatus.setText("文件校验失败，即将重新下载...");
                        progressBar.setProgress(0);
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (updateDialog != null && updateDialog.isShowing()) {
                                    startDownload(context);
                                }
                            }
                        }, 2000);
                    }
                } else if (status == android.app.DownloadManager.STATUS_FAILED) {
                    tvDownloadStatus.setText("下载失败");
                    isDownloading = false;
                    stopProgress();
                    layoutButtons.setVisibility(View.VISIBLE);
                    btnUpdate.setText("重新下载");
                    btnCancel.setText("取消");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void openInstaller(Context context, File apkFile) {
        try {
            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apkFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);

            if (updateDialog != null && updateDialog.isShowing()) {
                updateDialog.dismiss();
            }
        } catch (Exception e) {
            e.printStackTrace();
            tvDownloadStatus.setText("打开安装器失败");
        }
    }

    private static void cancelDownload(Context context) {
        isDownloading = false;
        stopProgress();
        if (downloadId != -1) {
            try {
                android.app.DownloadManager dm = (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) dm.remove(downloadId);
            } catch (Exception e) {
                e.printStackTrace();
            }
            downloadId = -1;
        }
        unregisterDownloadReceiver(context);
    }

    private static void stopProgress() {
        if (progressHandler != null) {
            progressHandler.removeCallbacksAndMessages(null);
            progressHandler = null;
        }
    }

    private static void unregisterDownloadReceiver(Context context) {
        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver);
            } catch (Exception e) {
                e.printStackTrace();
            }
            downloadReceiver = null;
        }
    }

    private static String calculateMD5(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream is = new FileInputStream(file);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            } finally {
                try { is.close(); } catch (Exception e) {}
            }
            byte[] digest = md.digest();
            return new BigInteger(1, digest).toString(16).toLowerCase();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String formatFileSize(Long size) {
        if (size == null) return "未知";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }
}
