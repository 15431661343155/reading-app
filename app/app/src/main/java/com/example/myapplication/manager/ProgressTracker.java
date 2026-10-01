package com.example.myapplication.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ReadingProgress;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 进度跟踪器
 * 管理阅读进度的保存、加载和同步
 */
public class ProgressTracker {
    
    private static final String TAG = "ProgressTracker";
    private static final String PREFS_NAME = "reading_records";
    
    // 进度键名前缀
    private static final String KEY_RECORD_COUNT = "record_count";
    private static final String KEY_RECORD_BOOKID = "record_bookId_";
    private static final String KEY_RECORD_CHAPTER = "record_chapterIndex_";
    private static final String KEY_RECORD_PAGE = "record_page_";
    private static final String KEY_RECORD_TIME = "record_time_";
    
    private final SharedPreferences prefs;
    
    public ProgressTracker(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * 保存阅读进度到本地
     */
    public void saveProgress(long bookId, int chapterIndex, int page) {
        int recordCount = prefs.getInt(KEY_RECORD_COUNT, 0);
        int targetIndex = -1;
        
        // 查找是否已有该书的记录
        for (int i = 0; i < recordCount; i++) {
            if (prefs.getLong(KEY_RECORD_BOOKID + i, 0) == bookId) {
                targetIndex = i;
                break;
            }
        }
        
        // 如果没有找到，创建新记录
        if (targetIndex == -1) {
            targetIndex = recordCount;
            prefs.edit().putInt(KEY_RECORD_COUNT, recordCount + 1).apply();
        }
        
        prefs.edit()
            .putLong(KEY_RECORD_BOOKID + targetIndex, bookId)
            .putInt(KEY_RECORD_CHAPTER + targetIndex, chapterIndex)
            .putInt(KEY_RECORD_PAGE + targetIndex, page)
            .putLong(KEY_RECORD_TIME + targetIndex, System.currentTimeMillis())
            .apply();
        
        Log.d(TAG, "Progress saved: book=" + bookId + ", chapter=" + chapterIndex + ", page=" + page);
    }
    
    /**
     * 从本地加载阅读进度
     */
    public ReadingProgress loadProgress(long bookId) {
        int recordCount = prefs.getInt(KEY_RECORD_COUNT, 0);
        
        for (int i = 0; i < recordCount; i++) {
            if (prefs.getLong(KEY_RECORD_BOOKID + i, 0) == bookId) {
                int chapterIndex = prefs.getInt(KEY_RECORD_CHAPTER + i, 0);
                int page = prefs.getInt(KEY_RECORD_PAGE + i, 1);
                long time = prefs.getLong(KEY_RECORD_TIME + i, 0);
                
                ReadingProgress progress = new ReadingProgress();
                progress.setChapterIndex(chapterIndex);
                progress.setScrollPosition(page);  // 使用scrollPosition而不是page
                // 注意：ReadingProgress没有lastReadTime字段，时间信息保存在SharedPreferences中
                
                Log.d(TAG, "Progress loaded: chapter=" + chapterIndex + ", page=" + page);
                return progress;
            }
        }
        
        Log.d(TAG, "No progress found for book: " + bookId);
        return null;
    }
    
    /**
     * 同步进度到服务器
     */
    public void syncToServer(long userId, long bookId, int chapterIndex, int page, SyncCallback callback) {
        ReadingProgress progress = new ReadingProgress();
        progress.setUserId(userId);
        progress.setBookId(bookId);
        progress.setChapterIndex(chapterIndex);
        progress.setScrollPosition(page);  // 使用scrollPosition
        
        RetrofitClient.getApiService().saveProgress(progress).enqueue(new Callback<ApiResponse<ReadingProgress>>() {
            @Override
            public void onResponse(Call<ApiResponse<ReadingProgress>> call,
                    Response<ApiResponse<ReadingProgress>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    Log.d(TAG, "Progress synced to server");
                    if (callback != null) {
                        callback.onSuccess();
                    }
                } else {
                    Log.w(TAG, "Failed to sync progress: " + response.message());
                    if (callback != null) {
                        callback.onError("服务器响应错误");
                    }
                }
            }
            
            @Override
            public void onFailure(Call<ApiResponse<ReadingProgress>> call, Throwable t) {
                Log.e(TAG, "Failed to sync progress", t);
                if (callback != null) {
                    callback.onError("网络错误: " + t.getMessage());
                }
            }
        });
    }
    
    /**
     * 从服务器加载进度
     */
    public void loadFromServer(long userId, long bookId, ServerLoadCallback callback) {
        RetrofitClient.getApiService().getProgress(userId,
                bookId).enqueue(new Callback<ApiResponse<ReadingProgress>>() {
            @Override
            public void onResponse(Call<ApiResponse<ReadingProgress>> call,
                    Response<ApiResponse<ReadingProgress>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    ReadingProgress progress = response.body().getData();
                    if (progress != null) {
                        // 保存到本地（使用scrollPosition作为page）
                        saveProgress(bookId, progress.getChapterIndex(), progress.getScrollPosition());
                        Log.d(TAG, "Progress loaded from server");
                        callback.onSuccess(progress);
                    } else {
                        callback.onError("进度数据为空");
                    }
                } else {
                    callback.onError("服务器响应错误");
                }
            }
            
            @Override
            public void onFailure(Call<ApiResponse<ReadingProgress>> call, Throwable t) {
                Log.e(TAG, "Failed to load progress from server", t);
                callback.onError("网络错误: " + t.getMessage());
            }
        });
    }
    
    /**
     * 获取某本书的最后阅读时间
     */
    public long getLastReadTime(long bookId) {
        int recordCount = prefs.getInt(KEY_RECORD_COUNT, 0);
        
        for (int i = 0; i < recordCount; i++) {
            if (prefs.getLong(KEY_RECORD_BOOKID + i, 0) == bookId) {
                return prefs.getLong(KEY_RECORD_TIME + i, 0);
            }
        }
        
        return 0;
    }
    
    /**
     * 删除某本书的阅读记录
     */
    public void deleteProgress(long bookId) {
        int recordCount = prefs.getInt(KEY_RECORD_COUNT, 0);
        
        for (int i = 0; i < recordCount; i++) {
            if (prefs.getLong(KEY_RECORD_BOOKID + i, 0) == bookId) {
                // 将该记录与最后一条记录交换，然后删除最后一条
                int lastIndex = recordCount - 1;
                if (i != lastIndex) {
                    long lastBookId = prefs.getLong(KEY_RECORD_BOOKID + lastIndex, 0);
                    int lastChapter = prefs.getInt(KEY_RECORD_CHAPTER + lastIndex, 0);
                    int lastPage = prefs.getInt(KEY_RECORD_PAGE + lastIndex, 1);
                    long lastTime = prefs.getLong(KEY_RECORD_TIME + lastIndex, 0);
                    
                    prefs.edit()
                        .putLong(KEY_RECORD_BOOKID + i, lastBookId)
                        .putInt(KEY_RECORD_CHAPTER + i, lastChapter)
                        .putInt(KEY_RECORD_PAGE + i, lastPage)
                        .putLong(KEY_RECORD_TIME + i, lastTime)
                        .apply();
                }
                
                prefs.edit()
                    .remove(KEY_RECORD_BOOKID + lastIndex)
                    .remove(KEY_RECORD_CHAPTER + lastIndex)
                    .remove(KEY_RECORD_PAGE + lastIndex)
                    .remove(KEY_RECORD_TIME + lastIndex)
                    .putInt(KEY_RECORD_COUNT, recordCount - 1)
                    .apply();
                
                Log.d(TAG, "Progress deleted for book: " + bookId);
                break;
            }
        }
    }
    
    /**
     * 清除所有阅读记录
     */
    public void clearAllProgress() {
        prefs.edit().clear().apply();
        Log.d(TAG, "All progress cleared");
    }
    
    /**
     * 回调接口
     */
    public interface SyncCallback {
        void onSuccess();
        void onError(String error);
    }
    
    public interface ServerLoadCallback {
        void onSuccess(ReadingProgress progress);
        void onError(String error);
    }
}
