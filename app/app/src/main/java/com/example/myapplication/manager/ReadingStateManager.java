package com.example.myapplication.manager;

/**
 * 阅读状态管理器
 * 统一管理ReadActivity中的所有状态变量
 */
public class ReadingStateManager {
    
    // ========== 章节状态 ==========
    private int currentChapterIndex = 0;
    private int currentPageInChapter = 1;
    private int totalPagesInChapter = 1;
    
    // ========== WebView状态 ==========
    private boolean webViewReady = false;
    private boolean positionRestored = false;
    private boolean progressRestored = false;
    
    // ========== 加载状态 ==========
    private boolean hasRestoredFromLocal = false;
    private boolean chapterRestoredFromCache = false;
    
    // ========== 阅读设置状态 ==========
    private float currentFontSize = 28f;
    private float headerFooterFontSize = 12f;
    private boolean showHeaderFooter = true;
    private boolean isNightMode = false;
    private int currentBgColor = 0;  // 0:米黄 1:护眼绿 2:羊皮纸 3:夜间
    
    // ========== 功能开关 ==========
    private boolean autoPageEnabled = false;
    private boolean followSystemBrightness = false;
    private int currentBrightness = 128;
    private int pageAnimMode = 0;
    
    // ========== 书籍信息 ==========
    private boolean isLocalBook = false;
    
    /**
     * 重置所有状态到初始值
     */
    public void reset() {
        currentChapterIndex = 0;
        currentPageInChapter = 1;
        totalPagesInChapter = 1;
        webViewReady = false;
        positionRestored = false;
        progressRestored = false;
        hasRestoredFromLocal = false;
        chapterRestoredFromCache = false;
    }
    
    // ========== Getters and Setters ==========
    
    public int getCurrentChapterIndex() {
        return currentChapterIndex;
    }
    
    public void setCurrentChapterIndex(int currentChapterIndex) {
        this.currentChapterIndex = currentChapterIndex;
    }
    
    public int getCurrentPageInChapter() {
        return currentPageInChapter;
    }
    
    public void setCurrentPageInChapter(int currentPageInChapter) {
        this.currentPageInChapter = currentPageInChapter;
    }
    
    public int getTotalPagesInChapter() {
        return totalPagesInChapter;
    }
    
    public void setTotalPagesInChapter(int totalPagesInChapter) {
        this.totalPagesInChapter = totalPagesInChapter;
    }
    
    public boolean isWebViewReady() {
        return webViewReady;
    }
    
    public void setWebViewReady(boolean webViewReady) {
        this.webViewReady = webViewReady;
    }
    
    public boolean isPositionRestored() {
        return positionRestored;
    }
    
    public void setPositionRestored(boolean positionRestored) {
        this.positionRestored = positionRestored;
    }
    
    public boolean isProgressRestored() {
        return progressRestored;
    }
    
    public void setProgressRestored(boolean progressRestored) {
        this.progressRestored = progressRestored;
    }
    
    public boolean isHasRestoredFromLocal() {
        return hasRestoredFromLocal;
    }
    
    public void setHasRestoredFromLocal(boolean hasRestoredFromLocal) {
        this.hasRestoredFromLocal = hasRestoredFromLocal;
    }
    
    public boolean isChapterRestoredFromCache() {
        return chapterRestoredFromCache;
    }
    
    public void setChapterRestoredFromCache(boolean chapterRestoredFromCache) {
        this.chapterRestoredFromCache = chapterRestoredFromCache;
    }
    
    public float getCurrentFontSize() {
        return currentFontSize;
    }
    
    public void setCurrentFontSize(float currentFontSize) {
        this.currentFontSize = currentFontSize;
    }
    
    public float getHeaderFooterFontSize() {
        return headerFooterFontSize;
    }
    
    public void setHeaderFooterFontSize(float headerFooterFontSize) {
        this.headerFooterFontSize = headerFooterFontSize;
    }
    
    public boolean isShowHeaderFooter() {
        return showHeaderFooter;
    }
    
    public void setShowHeaderFooter(boolean showHeaderFooter) {
        this.showHeaderFooter = showHeaderFooter;
    }
    
    public boolean isNightMode() {
        return isNightMode;
    }
    
    public void setNightMode(boolean nightMode) {
        isNightMode = nightMode;
    }
    
    public int getCurrentBgColor() {
        return currentBgColor;
    }
    
    public void setCurrentBgColor(int currentBgColor) {
        this.currentBgColor = currentBgColor;
    }
    
    public boolean isAutoPageEnabled() {
        return autoPageEnabled;
    }
    
    public void setAutoPageEnabled(boolean autoPageEnabled) {
        this.autoPageEnabled = autoPageEnabled;
    }
    
    public boolean isFollowSystemBrightness() {
        return followSystemBrightness;
    }
    
    public void setFollowSystemBrightness(boolean followSystemBrightness) {
        this.followSystemBrightness = followSystemBrightness;
    }
    
    public int getCurrentBrightness() {
        return currentBrightness;
    }
    
    public void setCurrentBrightness(int currentBrightness) {
        this.currentBrightness = currentBrightness;
    }
    
    public int getPageAnimMode() {
        return pageAnimMode;
    }
    
    public void setPageAnimMode(int pageAnimMode) {
        this.pageAnimMode = pageAnimMode;
    }
    
    public boolean isLocalBook() {
        return isLocalBook;
    }
    
    public void setLocalBook(boolean localBook) {
        isLocalBook = localBook;
    }
    
    /**
     * 更新页面信息（从JS回调）
     */
    public void updatePageInfo(int page, int totalPages) {
        this.currentPageInChapter = page;
        this.totalPagesInChapter = totalPages;
    }
    
    /**
     * 检查是否可以跳转到指定页码
     */
    public boolean canJumpToPage(int page) {
        return page >= 1 && page <= totalPagesInChapter;
    }
    
    /**
     * 检查是否有下一章
     */
    public boolean hasNextChapter(int totalChapters) {
        return currentChapterIndex < totalChapters - 1;
    }
    
    /**
     * 检查是否有上一章
     */
    public boolean hasPrevChapter() {
        return currentChapterIndex > 0;
    }
    
    @Override
    public String toString() {
        return "ReadingStateManager{" +
                "chapter=" + currentChapterIndex +
                ", page=" + currentPageInChapter + "/" + totalPagesInChapter +
                ", fontSize=" + currentFontSize +
                ", nightMode=" + isNightMode +
                ", webViewReady=" + webViewReady +
                ", positionRestored=" + positionRestored +
                '}';
    }
}
