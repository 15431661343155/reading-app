package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 章节内容缓存管理
 */
public class ChapterCacheManager {

    private static final String CACHE_NAME = "chapter_cache";
    private static final String KEY_CONTENT_PREFIX = "content_";
    private static final String KEY_CHAPTER_LIST_PREFIX = "chapter_list_";

    private final SharedPreferences sp;
    private final Gson gson;

    public ChapterCacheManager(Context context) {
        sp = context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    /**
     * 缓存章节列表（标题等元数据）
     */
    public void saveChapterList(long bookId, List<ChapterSimple> chapters) {
        String json = gson.toJson(chapters);
        sp.edit().putString(KEY_CHAPTER_LIST_PREFIX + bookId, json).apply();
    }

    /**
     * 读取缓存的章节列表
     */
    public List<ChapterSimple> getChapterList(long bookId) {
        String json = sp.getString(KEY_CHAPTER_LIST_PREFIX + bookId, null);
        if (json == null) return null;
        Type type = new TypeToken<List<ChapterSimple>>(){}.getType();
        return gson.fromJson(json, type);
    }

    /**
     * 缓存单章内容
     */
    public void saveChapterContent(long bookId, int chapterIndex, String content) {
        String key = KEY_CONTENT_PREFIX + bookId + "_" + chapterIndex;
        sp.edit().putString(key, content).apply();
    }

    /**
     * 读取缓存的单章内容
     */
    public String getChapterContent(long bookId, int chapterIndex) {
        String key = KEY_CONTENT_PREFIX + bookId + "_" + chapterIndex;
        return sp.getString(key, null);
    }

    /**
     * 清除书籍的所有缓存
     */
    public void clearBookCache(long bookId) {
        SharedPreferences.Editor editor = sp.edit();
        editor.remove(KEY_CHAPTER_LIST_PREFIX + bookId);
        // 清除所有章节内容
        List<ChapterSimple> list = getChapterList(bookId);
        if (list != null) {
            for (ChapterSimple ch : list) {
                editor.remove(KEY_CONTENT_PREFIX + bookId + "_" + ch.index);
            }
        }
        editor.apply();
    }

    /**
     * 简单的章节元数据
     */
    public static class ChapterSimple {
        public int index;
        public String title;

        public ChapterSimple() {}
        public ChapterSimple(int index, String title) {
            this.index = index;
            this.title = title;
        }
    }
}