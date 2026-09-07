package com.example.myapplication.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ChapterDto;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 章节加载器
 * 负责章节数据的加载、缓存和预取
 */
public class ChapterLoader {
    
    private static final String TAG = "ChapterLoader";
    private static final String PREFS_NAME = "chapter_cache";
    private static final int MAX_CACHE_SIZE = 10; // 最多缓存10章
    
    private final Context context;
    private final List<ChapterDto> chapterList;
    private final List<String> chapterContents;
    private final SharedPreferences cachePref;
    
    public interface ChapterLoadCallback {
        void onSuccess(List<ChapterDto> chapters);
        void onError(String error);
    }
    
    public interface ContentLoadCallback {
        void onSuccess(int index, String content);
        void onError(int index, String error);
    }
    
    public ChapterLoader(Context context) {
        this.context = context.getApplicationContext();
        this.chapterList = new ArrayList<>();
        this.chapterContents = new ArrayList<>();
        this.cachePref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * 从服务器加载章节列表
     */
    public void loadChaptersFromServer(long bookId, ChapterLoadCallback callback) {
        Log.d(TAG, "Loading chapters from server for book: " + bookId);
        
        RetrofitClient.getApiService().getChapters(bookId).enqueue(new Callback<ApiResponse<List<ChapterDto>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<ChapterDto>>> call, Response<ApiResponse<List<ChapterDto>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    List<ChapterDto> chapters = response.body().getData();
                    if (chapters != null && !chapters.isEmpty()) {
                        updateChapterList(chapters);
                        callback.onSuccess(chapters);
                        Log.d(TAG, "Loaded " + chapters.size() + " chapters");
                    } else {
                        callback.onError("章节列表为空");
                    }
                } else {
                    callback.onError("服务器响应错误");
                }
            }
            
            @Override
            public void onFailure(Call<ApiResponse<List<ChapterDto>>> call, Throwable t) {
                Log.e(TAG, "Failed to load chapters", t);
                callback.onError("网络错误: " + t.getMessage());
            }
        });
    }
    
    /**
     * 获取指定章节的内容（优先从缓存）
     */
    public void getChapterContent(int index, long bookId, ContentLoadCallback callback) {
        // 检查索引有效性
        if (index < 0 || index >= chapterList.size()) {
            callback.onError(index, "章节索引无效");
            return;
        }
        
        // 检查内存缓存
        if (index < chapterContents.size() && chapterContents.get(index) != null) {
            String cachedContent = chapterContents.get(index);
            if (!cachedContent.contains("加载中...") && cachedContent.length() >= 50) {
                callback.onSuccess(index, cachedContent);
                return;
            }
        }
        
        // 检查磁盘缓存
        String diskCached = getDiskCache(bookId, index);
        if (diskCached != null && diskCached.length() >= 50) {
            updateMemoryCache(index, diskCached);
            callback.onSuccess(index, diskCached);
            return;
        }
        
        // 从服务器加载
        fetchFromServer(index, bookId, callback);
    }
    
    /**
     * 从服务器获取章节内容
     */
    private void fetchFromServer(int index, long bookId, ContentLoadCallback callback) {
        Long chapterId = chapterList.get(index).getId();
        if (chapterId == null) {
            callback.onError(index, "章节ID为空");
            return;
        }
        
        // 设置加载中标记
        updateMemoryCache(index, "加载中...");
        
        RetrofitClient.getApiService().getChapterContent(chapterId).enqueue(new Callback<ApiResponse<com.example.myapplication.bean.Chapter>>() {
            @Override
            public void onResponse(Call<ApiResponse<com.example.myapplication.bean.Chapter>> call, Response<ApiResponse<com.example.myapplication.bean.Chapter>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    com.example.myapplication.bean.Chapter chapter = response.body().getData();
                    if (chapter != null && chapter.getContent() != null && !chapter.getContent().isEmpty()) {
                        String content = chapter.getContent();
                        updateMemoryCache(index, content);
                        saveToDiskCache(bookId, index, content);
                        callback.onSuccess(index, content);
                        Log.d(TAG, "Fetched chapter " + index + " from server");
                        
                        // 预取下一章
                        prefetchNextChapter(index, bookId);
                    } else {
                        callback.onError(index, "章节内容为空");
                    }
                } else {
                    callback.onError(index, "服务器响应错误");
                }
            }
            
            @Override
            public void onFailure(Call<ApiResponse<com.example.myapplication.bean.Chapter>> call, Throwable t) {
                Log.e(TAG, "Failed to fetch chapter content", t);
                callback.onError(index, "网络错误: " + t.getMessage());
            }
        });
    }
    
    /**
     * 预取下一章（异步，不阻塞）
     */
    private void prefetchNextChapter(int currentIndex, long bookId) {
        int nextIndex = currentIndex + 1;
        if (nextIndex < chapterList.size()) {
            // 检查是否已经缓存
            if (nextIndex >= chapterContents.size() || chapterContents.get(nextIndex) == null) {
                getChapterContent(nextIndex, bookId, new ContentLoadCallback() {
                    @Override
                    public void onSuccess(int index, String content) {
                        Log.d(TAG, "Prefetched chapter " + index);
                    }
                    
                    @Override
                    public void onError(int index, String error) {
                        Log.w(TAG, "Prefetch failed for chapter " + index + ": " + error);
                    }
                });
            }
        }
    }
    
    /**
     * 更新章节列表
     */
    public void updateChapterList(List<ChapterDto> newChapters) {
        chapterList.clear();
        chapterList.addAll(newChapters);
        
        // 重置内容缓存
        chapterContents.clear();
        for (int i = 0; i < newChapters.size(); i++) {
            chapterContents.add(null);
        }
    }
    
    /**
     * 更新内存缓存
     */
    private void updateMemoryCache(int index, String content) {
        if (index < chapterContents.size()) {
            chapterContents.set(index, content);
        }
    }
    
    /**
     * 保存到磁盘缓存
     */
    private void saveToDiskCache(long bookId, int chapterIndex, String content) {
        String key = "book_" + bookId + "_chapter_" + chapterIndex;
        cachePref.edit().putString(key, content).apply();
    }
    
    /**
     * 从磁盘缓存读取
     */
    private String getDiskCache(long bookId, int chapterIndex) {
        String key = "book_" + bookId + "_chapter_" + chapterIndex;
        return cachePref.getString(key, null);
    }
    
    /**
     * 清理旧缓存（LRU策略）
     */
    public void evictOldCache(long bookId, int keepFromIndex, int keepCount) {
        SharedPreferences.Editor editor = cachePref.edit();
        
        for (int i = 0; i < chapterList.size(); i++) {
            // 保留最近的章节
            if (i >= keepFromIndex && i < keepFromIndex + keepCount) {
                continue;
            }
            
            String key = "book_" + bookId + "_chapter_" + i;
            editor.remove(key);
        }
        
        editor.apply();
        Log.d(TAG, "Evicted old cache, kept chapters " + keepFromIndex + "-" + (keepFromIndex + keepCount - 1));
    }
    
    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        cachePref.edit().clear().apply();
        chapterList.clear();
        chapterContents.clear();
        Log.d(TAG, "Cleared all cache");
    }
    
    // ========== Getters ==========
    
    public List<ChapterDto> getChapterList() {
        return chapterList;
    }
    
    public List<String> getChapterContents() {
        return chapterContents;
    }
    
    public int getChapterCount() {
        return chapterList.size();
    }
    
    public ChapterDto getChapter(int index) {
        if (index >= 0 && index < chapterList.size()) {
            return chapterList.get(index);
        }
        return null;
    }
    
    public String getContent(int index) {
        if (index >= 0 && index < chapterContents.size()) {
            return chapterContents.get(index);
        }
        return null;
    }
    
    public boolean isChapterLoaded(int index) {
        if (index < 0 || index >= chapterContents.size()) {
            return false;
        }
        String content = chapterContents.get(index);
        return content != null && !content.contains("加载中...") && content.length() >= 50;
    }
}
