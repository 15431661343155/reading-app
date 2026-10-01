package com.example.myapplication.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.webkit.WebView;

/**
 * 阅读设置管理器
 * 统一管理所有阅读相关的设置（字体、背景、亮度等）
 */
public class ReadingSettingsManager {
    
    private static final String TAG = "ReadingSettingsManager";
    private static final String PREFS_NAME = "reading_settings";
    
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_HEADER_FOOTER_FONT_SIZE = "header_footer_font_size";
    private static final String KEY_SHOW_HEADER_FOOTER = "show_header_footer";
    private static final String KEY_BG_COLOR = "bg_color";
    private static final String KEY_NIGHT_MODE = "night_mode";
    private static final String KEY_BRIGHTNESS = "brightness";
    private static final String KEY_FOLLOW_SYSTEM_BRIGHTNESS = "follow_system_brightness";
    private static final String KEY_PAGE_ANIM_MODE = "page_anim_mode";
    
    // 背景颜色配置
    public static final int BG_COLOR_BEIGE = 0;      // 米黄
    public static final int BG_COLOR_GREEN = 1;      // 护眼绿
    public static final int BG_COLOR_PARCHMENT = 2;  // 羊皮纸
    public static final int BG_COLOR_NIGHT = 3;      // 夜间
    
    private final SharedPreferences prefs;
    
    // ========== 当前设置值 ==========
    private float fontSize = 28f;
    private float headerFooterFontSize = 12f;
    private boolean showHeaderFooter = true;
    private int bgColor = BG_COLOR_BEIGE;
    private boolean nightMode = false;
    private int brightness = 128;
    private boolean followSystemBrightness = false;
    private int pageAnimMode = 0;
    
    public ReadingSettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadSettings();
    }
    
    /**
     * 加载设置
     */
    public void loadSettings() {
        fontSize = prefs.getFloat(KEY_FONT_SIZE, 28f);
        headerFooterFontSize = prefs.getFloat(KEY_HEADER_FOOTER_FONT_SIZE, 12f);
        showHeaderFooter = prefs.getBoolean(KEY_SHOW_HEADER_FOOTER, true);
        bgColor = prefs.getInt(KEY_BG_COLOR, BG_COLOR_BEIGE);
        nightMode = prefs.getBoolean(KEY_NIGHT_MODE, false);
        brightness = prefs.getInt(KEY_BRIGHTNESS, 128);
        followSystemBrightness = prefs.getBoolean(KEY_FOLLOW_SYSTEM_BRIGHTNESS, false);
        pageAnimMode = prefs.getInt(KEY_PAGE_ANIM_MODE, 0);
        
        Log.d(TAG, "Settings loaded: fontSize=" + fontSize + ", bgColor=" + bgColor);
    }
    
    /**
     * 保存设置
     */
    public void saveSettings() {
        prefs.edit()
            .putFloat(KEY_FONT_SIZE, fontSize)
            .putFloat(KEY_HEADER_FOOTER_FONT_SIZE, headerFooterFontSize)
            .putBoolean(KEY_SHOW_HEADER_FOOTER, showHeaderFooter)
            .putInt(KEY_BG_COLOR, bgColor)
            .putBoolean(KEY_NIGHT_MODE, nightMode)
            .putInt(KEY_BRIGHTNESS, brightness)
            .putBoolean(KEY_FOLLOW_SYSTEM_BRIGHTNESS, followSystemBrightness)
            .putInt(KEY_PAGE_ANIM_MODE, pageAnimMode)
            .apply();
        
        Log.d(TAG, "Settings saved");
    }
    
    /**
     * 应用设置到WebView
     */
    public void applyToWebView(WebView webView) {
        if (webView == null) return;
        
        StringBuilder js = new StringBuilder();
        
        js.append("if (typeof applyFontSize === 'function') {");
        js.append("applyFontSize(").append(fontSize).append(");");
        js.append("}");
        
        js.append("if (typeof applyHeaderFooterSettings === 'function') {");
        js.append("applyHeaderFooterSettings(").append(showHeaderFooter ? "true" : "false");
        js.append(", ").append(headerFooterFontSize).append(");");
        js.append("}");
        
        String bgColorHex = getBgColorHex();
        js.append("if (typeof applyBgColor === 'function') {");
        js.append("applyBgColor('").append(bgColorHex).append("');");
        js.append("}");
        
        webView.evaluateJavascript(js.toString(), null);
        Log.d(TAG, "Settings applied to WebView");
    }
    
    /**
     * 切换夜间模式
     */
    public void toggleNightMode() {
        nightMode = !nightMode;
        saveSettings();
        Log.d(TAG, "Night mode toggled: " + nightMode);
    }
    
    /**
     * 改变字体大小
     */
    public void changeFontSize(float newSize) {
        if (newSize < 12f || newSize > 60f) {
            Log.w(TAG, "Font size out of range: " + newSize);
            return;
        }
        this.fontSize = newSize;
        saveSettings();
        Log.d(TAG, "Font size changed: " + newSize);
    }
    
    /**
     * 改变背景颜色
     */
    public void changeBgColor(int colorIndex) {
        if (colorIndex < 0 || colorIndex > 3) {
            Log.w(TAG, "Bg color index out of range: " + colorIndex);
            return;
        }
        this.bgColor = colorIndex;
        saveSettings();
        Log.d(TAG, "Bg color changed: " + colorIndex);
    }
    
    /**
     * 获取背景颜色的十六进制值
     */
    public String getBgColorHex() {
        switch (bgColor) {
            case BG_COLOR_BEIGE:
                return "#F5E6D3";  // 米黄
            case BG_COLOR_GREEN:
                return "#C7EDCC";  // 护眼绿
            case BG_COLOR_PARCHMENT:
                return "#F4ECD8";  // 羊皮纸
            case BG_COLOR_NIGHT:
                return "#2A2A2A";  // 夜间
            default:
                return "#F5E6D3";
        }
    }
    
    /**
     * 获取背景颜色的Android Color值
     */
    public int getBgColorInt() {
        switch (bgColor) {
            case BG_COLOR_BEIGE:
                return Color.parseColor("#F5E6D3");
            case BG_COLOR_GREEN:
                return Color.parseColor("#C7EDCC");
            case BG_COLOR_PARCHMENT:
                return Color.parseColor("#F4ECD8");
            case BG_COLOR_NIGHT:
                return Color.parseColor("#2A2A2A");
            default:
                return Color.parseColor("#F5E6D3");
        }
    }
    
    /**
     * 改变亮度
     */
    public void changeBrightness(int newBrightness) {
        if (newBrightness < 0 || newBrightness > 255) {
            Log.w(TAG, "Brightness out of range: " + newBrightness);
            return;
        }
        this.brightness = newBrightness;
        this.followSystemBrightness = false;
        saveSettings();
        Log.d(TAG, "Brightness changed: " + newBrightness);
    }
    
    /**
     * 切换跟随系统亮度
     */
    public void toggleFollowSystemBrightness() {
        this.followSystemBrightness = !this.followSystemBrightness;
        saveSettings();
        Log.d(TAG, "Follow system brightness: " + followSystemBrightness);
    }
    
    /**
     * 重置为默认设置
     */
    public void resetToDefaults() {
        fontSize = 28f;
        headerFooterFontSize = 12f;
        showHeaderFooter = true;
        bgColor = BG_COLOR_BEIGE;
        nightMode = false;
        brightness = 128;
        followSystemBrightness = false;
        pageAnimMode = 0;
        
        saveSettings();
        Log.d(TAG, "Settings reset to defaults");
    }
    
    // ========== Getters ==========
    
    public float getFontSize() {
        return fontSize;
    }
    
    public float getHeaderFooterFontSize() {
        return headerFooterFontSize;
    }
    
    public boolean isShowHeaderFooter() {
        return showHeaderFooter;
    }
    
    public void setShowHeaderFooter(boolean show) {
        this.showHeaderFooter = show;
        saveSettings();
    }
    
    public int getBgColor() {
        return bgColor;
    }
    
    public boolean isNightMode() {
        return nightMode;
    }
    
    public int getBrightness() {
        return brightness;
    }
    
    public boolean isFollowSystemBrightness() {
        return followSystemBrightness;
    }
    
    public int getPageAnimMode() {
        return pageAnimMode;
    }
    
    public void setPageAnimMode(int mode) {
        this.pageAnimMode = mode;
        saveSettings();
    }
    
    @Override
    public String toString() {
        return "ReadingSettingsManager{" +
                "fontSize=" + fontSize +
                ", bgColor=" + bgColor +
                ", nightMode=" + nightMode +
                ", brightness=" + brightness +
                ", showHeaderFooter=" + showHeaderFooter +
                '}';
    }
}
