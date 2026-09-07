package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.app.AlertDialog;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.fragment.app.Fragment;
import android.content.res.ColorStateList;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.Bookmark;
import com.example.myapplication.bean.ReadingProgress;
import com.example.myapplication.fragment.PopupBookmarkFragment;
import com.example.myapplication.fragment.PopupChapterFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ReadTimeRequest;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ReadActivity extends BaseActivity {

    // ========== UI 组件 ==========
    private WebView webView;
    private View layoutTopNav, layoutBottomNav;
    private TextView tvToolbarTitle, tvProgressText;
    private View btnPrevChapter, btnNextChapter, btnCatalog, btnNightMode, btnSettings;
    private ImageView ivNightModeIcon;
    private TextView tvNightModeText;
    private SeekBar seekBarProgress;

    // ========== 弹窗 ==========
    private PopupWindow chapterPopupWindow, moreMenuPopupWindow, settingsPopupWindow, moreSettingsPopupWindow, bgColorsPopupWindow, fontsPopupWindow;

    // ========== 阅读设置 ==========
    private int currentBrightness = 128;
    private boolean followSystemBrightness = false;
    private boolean autoPageEnabled = false;
    private int currentBgColor = 0;  // 0-9:纯色（0-3保持旧值兼容）
    private boolean isNightMode = false;

    /** 供弹窗内 Adapter 读取当前夜间模式状态，确保列表项配色跟随主题 */
    public boolean isNightMode() { return isNightMode; }

    /**
     * 从 Context（可能是 ContextThemeWrapper 包裹）解析当前是否夜间模式，
     * 供弹窗内 Fragment/Adapter 在 inflate/bind 时读取实时主题状态。
     */
    public static boolean isNightModeForContext(@Nullable Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof ReadActivity) return ((ReadActivity) ctx).isNightMode;
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return false;
    }

    // 纯色背景（indices 0-9，0-3 为快捷面板，0=纯白为 iOS 默认）
    private static final String[] BG_COLORS = {
        "#FFFFFF", "#C4DFCF", "#E5D5C0", "#1A1A1A",  // 纯白/护眼绿/羊皮纸/夜间
        "#F5F5DC", "#D2B48C", "#F8D7DA", "#2C3E50",  // 米黄/牛皮纸/粉色/深空灰
        "#DCE8F5", "#E8DAEF"                          // 淡蓝/薰衣草
    };
    private static final String[] BG_TEXT_COLORS = {
        "#1D1D1F", "#1D1D1F", "#1D1D1F", "#AAAAAA",
        "#1D1D1F", "#1D1D1F", "#1D1D1F", "#CCCCCC",
        "#1D1D1F", "#1D1D1F"
    };
    private static final String[] BG_LABELS = {
        "纯白", "护眼绿", "羊皮纸", "夜间",
        "米黄", "牛皮纸", "粉色", "深空灰",
        "淡蓝", "薰衣草"
    };

    // 纹理背景（indices 10-16，对应assets/backgrounds/中的图片）
    private static final String[] BG_TEXTURE_FILES = {
        "yangpi1.jpg", "yangpi2.jpg", "cuizhu.jpg", "shuimo.jpg",
        "yuanshan.jpg", "zhisan.jpg", "zhuying.jpg"
    };
    private static final String[] BG_TEXTURE_LABELS = {
        "古纸", "旧纸", "翠竹", "水墨山", "远山", "纸伞", "竹影"
    };
    // 纹理预览底色（用于弹窗中的色块预览边框配色）
    private static final int[] BG_TEXTURE_PREVIEW_COLORS = {
        0xFFF5F0E6, 0xFFF0EBE0, 0xFFE8E4D8, 0xFFD5C4A1,
        0xFFE0E4E8, 0xFFF5E6E0, 0xFFEAE8E0
    };

    // ========== 字体管理 ==========
    // 内置选项
    private static final String FONT_DEFAULT_CSS = "sans-serif";      // 默认字体（app自带）
    private static final String FONT_SYSTEM_CSS  = "__system__";       // 系统字体（跟随手机）
    // 后端字体列表（运行时从 API 获取）
    private java.util.List<com.example.myapplication.bean.FontItem> backendFonts = new java.util.ArrayList<>();

    private String currentFontFamily = "sans-serif"; // 当前使用的CSS字体族
    private java.util.Set<String> downloadedFonts = new java.util.HashSet<>(); // 已下载的字体cssName
    private Book currentBook;
    private int currentChapterIndex = 0;
    private float currentFontSize = 28f;   // px
    private float headerFooterFontSize = 12f; // 初始字号改为12
    private boolean showHeaderFooter = true;
    private boolean showBatteryTime = false;
    private boolean volumeKeyPageTurn = true;
    private boolean swipePageTurn = true;
    private String pageTurnMode = "cover"; // none, cover, slide, updown, fade, simulation
    private boolean isLocalBook = false;
    private boolean isExternalBook = false;       // 外站书籍在线阅读模式
    private String[][] externalChapters;          // 外站章节列表 [[title, url], ...]
    private final android.util.LruCache<String, String> externalContentCache =
            new android.util.LruCache<>(5);        // 缓存最近5章正文
    private android.widget.LinearLayout layoutExternalRetry;  // 外站章节加载失败重试层
    private TextView tvExternalRetryMsg;

    // ========== 电量时间 ==========
    private final Handler timeUpdateHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateBatteryAndTime();
            timeUpdateHandler.postDelayed(this, 60000); // 每分钟更新一次
        }
    };

    // ========== 章节数据 ==========
    private final List<Chapter> chapterList = new ArrayList<>();
    private final List<String> chapterContents = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ========== 导航栏自动隐藏 ==========
    private final Runnable hideNavRunnable = this::hideNavigation;
    private int statusBarHeight = 0;  // 状态栏高度

    // ========== 手势检测（滑动翻页 + 点击翻页） ==========
    private GestureDetector gestureDetector;
    private static final int SWIPE_MIN_DISTANCE = 60;   // dp
    private static final int SWIPE_MIN_VELOCITY = 80;   // px/s
    private static final int TAP_THRESHOLD_PX = 10;     // 触摸移动阈值
    private int swipeMinDistancePx = 60;
    // 去重：同一个 MotionEvent 会被 WebView / 左右热区 / 容器 等多个 View 收到，
    // 若重复投喂给 GestureDetector，会连续触发两次 onSingleTapConfirmed（一次点击翻两页）
    private long lastGestureEventTime = -1L;
    private int lastGestureActionMasked = -1;

    // ========== 自动翻页 ==========
    private final Handler autoPageHandler = new Handler(Looper.getMainLooper());
    private Runnable autoPageRunnable;

    // ========== 阅读时间 ==========
    private long readStartTime;
    private SharedPreferences readTimePref;

    // ========== JS 回调更新的当前进度 ==========
    private int currentPageInChapter = 1;
    private int totalPagesInChapter = 1;

    // ========== 分页缓存 ==========
    private SharedPreferences pageCachePref;

    private boolean positionRestored = false;
    private boolean isWebViewReady  = false;
    private boolean hasRestoredFromLocal = false;
    private boolean chapterRestoredFromCache = false;  // ✅ 新增：标记是否从缓存恢复了章节

    // ==================== Long/Book 安全工具（彻底消灭 Long->long 自动拆箱 NPE） ====================
    /** currentBook.getId() 统一安全入口：外站书为 null → 返回 0，绝不会自动拆箱 */
    private long safeBookId() {
        if (currentBook == null) return 0L;
        Long raw = currentBook.getId();
        return (raw == null || raw <= 0) ? 0L : raw;
    }

    /** 以"外站书的 sourceType+sourceUrl 组成的复合键"作为外站书的记录主键；服务器书走 bookId */
    private String bookRecordKey() {
        if (currentBook == null) return "_null_book_";
        if (isExternalBook) {
            String st = currentBook.getSourceType() == null ? "" : currentBook.getSourceType();
            String su = currentBook.getSourceUrl() == null ? "" : currentBook.getSourceUrl();
            return "ext|" + st + "|" + su;
        }
        long bid = safeBookId();
        return bid > 0 ? ("local|" + bid) : ("tmp_book|" + (currentBook.getBookName() == null ? "" : currentBook.getBookName()));
    }

    /**
     * SharedPreferences.getLong() 与 Long 做等值比较的安全版本：避免 Long==long 触发自动拆箱。
     * 当 boxedLong==null 时直接视为"不相等"返回 false。
     */
    private static boolean safePrefLongEquals(SharedPreferences sp, String key, long def, Long boxedLong) {
        if (boxedLong == null) return false;
        return sp.getLong(key, def) == boxedLong;
    }

    /**
     * 阅读器统一使用「从右向左滑入」转场：书架、书籍详情、目录、阅读记录等
     * 任意入口进入都生效，无需在调用方逐个设置。
     */
    @Override
    protected boolean useSlideTransition() {
        return true;
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ✅ super.onCreate 之前先替换当前线程未捕获异常处理器。
        // 背景：之前给 showChapterPopup / Fragment / renderChapterContent 各点都加了 try/catch，
        // 但 ViewPager2 延迟一帧才执行 onCreateView、FragmentManager.moveToState、
        // RecyclerView onMeasure/onBind 等，这些后续 MessageQueue 中的事件循环抛出的异常
        // 不在我们的 try/catch 调用栈中 → 直接抛给 AMS → 崩成白屏 → AMS finish 当前
        // Activity → 返回到 BookDetailActivity。用 Thread 级 catch-all 兜住，
        // 把这类崩溃转换成友好对话框（或只记日志 + 关弹窗），不再直接崩回去。
        try {
            savedUncaughtHandler = Thread.currentThread().getUncaughtExceptionHandler();
            Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
                android.util.Log.e("ReadActivity", "主线程未捕获异常（已兜住，不再白屏返回）", throwable);
                try {
                    runOnUiThread(() -> {
                        try { dismissPopups(); } catch (Throwable ignored) {}
                        try {
                            showPopupErrorDialog("阅读器发生异常："
                                    + (throwable == null ? "unknown"
                                    : (throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                                                                     : throwable.getMessage())));
                        } catch (Throwable showErr) {
                            // 弹都弹不出来时退化成 Toast
                            try {
                                Toast.makeText(ReadActivity.this,
                                        "阅读器异常已暂停，请返回重试", Toast.LENGTH_LONG).show();
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable inner) {
                    // 终极兜底：一切都失败再交给系统
                    if (savedUncaughtHandler != null) savedUncaughtHandler.uncaughtException(thread, throwable);
                }
            });
        } catch (Throwable ignored) {}

        super.onCreate(savedInstanceState);

        // ✅ 关键：让窗口始终占满物理屏幕，状态栏显隐时不改变窗口高度
        // 这样 WebView 的 window.innerHeight 始终恒定，不会触发重新分页
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            // 旧版本：LAYOUT_FULLSCREEN + LAYOUT_STABLE 确保窗口高度始终 = 屏幕高度
            int decorFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            getWindow().getDecorView().setSystemUiVisibility(decorFlags);
        }
        // 让内容延伸到刘海屏/挖孔屏区域（API 28+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        // 状态栏设为透明，让顶部导航栏（ios_tab_bar 白色毛玻璃）延伸到状态栏区域
        // 避免 BaseActivity 设置的蓝色状态栏与白色顶栏割裂
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(getResources().getColor(R.color.ios_bg_grouped, null));

        setContentView(R.layout.activity_read);

        currentBook = (Book) getIntent().getSerializableExtra("book");
        if (currentBook == null) {
            Toast.makeText(this, "书籍信息缺失，请重新进入", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        currentChapterIndex = getIntent().getIntExtra("chapterIndex", 0);
        currentFontSize = getIntent().getFloatExtra("fontSize", 28f);
        isNightMode = getIntent().getBooleanExtra("nightMode", false);
        isLocalBook = getIntent().getBooleanExtra("isLocal", false);
        isExternalBook = getIntent().getBooleanExtra("isExternal", false);
        externalChapters = (String[][]) getIntent().getSerializableExtra("chapters");

        // ✅ 关键兜底：即使 intent 没传对，也从 local_books 检测是否为本地书
        // 注意：外站书（isExternalBook=true）不能被误判为本地书，否则会走 getChapters(bookId) 死循环
        if (!isLocalBook && !isExternalBook && currentBook.getId() != null && currentBook.getId() > 0) {
            SharedPreferences spLocal = getSharedPreferences("local_books", MODE_PRIVATE);
            int localCount = spLocal.getInt("count", 0);
            for (int i = 0; i < localCount; i++) {
                if (spLocal.getLong("book_id_" + i, 0) == currentBook.getId()) {
                    isLocalBook = true;
                    currentBook.setStatus(-1);
                    break;
                }
            }
        }
        
        // 获取状态栏高度
        getStatusBarHeight();

        initView();
        loadReadingPreferencesNoApply();
        applyNightModeToNavOnly();

        setupWebView();
        setupClickListeners();
        // 初始状态隐藏手机状态栏，只显示阅读器自己的导航栏
        hideSystemStatusBar();
        // 上下导航栏只在「第一次使用本软件」时自动展开一次，之后进入阅读器保持隐藏
        if (consumeFirstUseNavGuide()) {
            showNavigation();   // 内部已启动 5 秒后自动收起的计时
        } else {
            hideNavigationImmediately();
        }
        updateChapterButtons();
        loadChaptersFromServer();
    }

    /**
     * 获取状态栏高度
     */
    private void getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
    }

    /**
     * ✅ 隐藏系统状态栏（仅改变可见性，不改变窗口高度）
     * 使用 LAYOUT_NO_LIMITS + WindowInsetsController，让窗口始终占满物理屏幕，
     * 避免状态栏显隐导致 WebView 高度变化触发重新分页。
     */
    @SuppressWarnings("deprecation")
    private void hideSystemStatusBar() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                // 隐藏状态栏（保持窗口高度不变）
                controller.hide(android.view.WindowInsets.Type.statusBars());
                // 显示系统栏时不重新布局（窗口始终全屏）
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // 旧版本：仅用 SYSTEM_UI_FLAG_FULLSCREEN 改变可见性，
            // 配合 LAYOUT_FULLSCREEN 让窗口高度保持不变
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    /**
     * ✅ 显示系统状态栏（仅改变可见性，不改变窗口高度）
     */
    @SuppressWarnings("deprecation")
    private void showSystemStatusBar() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(android.view.WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                // 日间模式状态栏图标用深色
                if (!isNightMode) {
                    controller.setSystemBarsAppearance(
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                } else {
                    controller.setSystemBarsAppearance(
                            0,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            }
        } else {
            // 旧版本：保持 LAYOUT_FULLSCREEN 让窗口高度始终不变，
            // 只取消 SYSTEM_UI_FLAG_FULLSCREEN 让状态栏可见
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            if (!isNightMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void initView() {
        webView = findViewById(R.id.webview_reader);
        layoutTopNav = findViewById(R.id.layout_top_nav);
        layoutBottomNav = findViewById(R.id.layout_bottom_nav);
        tvToolbarTitle = findViewById(R.id.tv_toolbar_title);
        btnPrevChapter = findViewById(R.id.btn_prev_chapter);
        btnNextChapter = findViewById(R.id.btn_next_chapter);
        btnCatalog = findViewById(R.id.btn_catalog);
        btnNightMode = findViewById(R.id.btn_night_mode);
        btnSettings = findViewById(R.id.btn_settings);
        ivNightModeIcon = findViewById(R.id.iv_night_mode_icon);
        tvNightModeText = findViewById(R.id.tv_night_mode_text);
        seekBarProgress = findViewById(R.id.seekbar_progress);
        tvProgressText = findViewById(R.id.tv_progress_text);
        tvToolbarTitle.setText(currentBook.getBookName());

        readTimePref = getSharedPreferences("read_time", MODE_PRIVATE);
        pageCachePref = getSharedPreferences("page_cache", MODE_PRIVATE);

        // 外站章节加载失败重试层
        layoutExternalRetry = findViewById(R.id.layout_external_retry);
        tvExternalRetryMsg = findViewById(R.id.tv_external_retry_msg);
        findViewById(R.id.btn_external_retry).setOnClickListener(v -> {
            if (layoutExternalRetry != null) layoutExternalRetry.setVisibility(View.GONE);
            loadExternalChapterContent(currentChapterIndex);
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true); // 允许加载内部存储字体文件
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isWebViewReady = true;
                
                // ✅ WebView 加载完成后，如果章节列表已加载但尚未恢复位置，则恢复
                mainHandler.post(() -> {
                    if (!positionRestored && !chapterRestoredFromCache && !chapterList.isEmpty()) {
                        android.util.Log.d("ReadActivity", "WebView ready, restoring position from server...");
                        // 先应用所有设置（字体、背景、页眉页脚等），再恢复位置，避免默认设置闪烁
                        webView.evaluateJavascript("beginSettingsBatch()", null);
                        applyHeaderFooterSettings();
                        applySettingsToWebView_inner();
                        webView.evaluateJavascript("finishSettingsBatch()", null);
                        webView.evaluateJavascript("setPageTurnMode('" + pageTurnMode + "')", null);
                        restoreReadingPosition(currentChapterIndex);
                        updateChapterButtons();
                    }
                });
            }
        });

        // 读取 HTML 并注入初始设置，确保第一次渲染就使用正确的值
        try {
            java.io.InputStream is = getAssets().open("reader.html");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            String html = new String(buffer, "UTF-8");
            String initScript = buildSettingsInitScript();
            html = html.replace("</body>", "<script>" + initScript + "</script>\n</body>");
            webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            android.util.Log.e("ReadActivity", "Failed to inject settings, loading directly", e);
            webView.loadUrl("file:///android_asset/reader.html");
        }
    }

    public class JsBridge {
        /**
         * 前端点击屏幕中间 1/3 时切换导航栏显隐（仿真模式下 Android 手势层不处理点击）
         */
        @JavascriptInterface
        @SuppressWarnings("unused")
        public void toggleNavigation() {
            runOnUiThread(() -> {
                try {
                    resetAutoHideTimer();
                    ReadActivity.this.toggleNavigation();
                } catch (Throwable t) {
                    android.util.Log.w("ReadActivity", "JsBridge.toggleNavigation 异常", t);
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onPageChanged(int page, int totalPages) {
            runOnUiThread(() -> {
                try {
                    currentPageInChapter = page;
                    totalPagesInChapter = totalPages;
                    updateProgressDisplay();
                } catch (Throwable t) {
                    android.util.Log.w("ReadActivity", "onPageChanged 异常", t);
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onChapterEnd() {
            runOnUiThread(() -> {
                try {
                int nextIndex = currentChapterIndex + 1;

                // ✅ 简化：直接检查是否还有下一章
                if (nextIndex >= chapterList.size()) {
                    Toast.makeText(ReadActivity.this, "已经是最后一章", Toast.LENGTH_SHORT).show();
                    return;
                }

                // ✅ 防御：chapterContents 越界
                if (nextIndex >= chapterContents.size()) {
                    while (chapterContents.size() < chapterList.size()) chapterContents.add("【正在加载...】");
                }
                // 检查下一章的状态
                String content = chapterContents.get(nextIndex);

                if (content == null || content.contains("加载中...")) {
                    // 下一章正在加载或尚未加载，触发加载
                    currentChapterIndex = nextIndex;
                    updateChapterButtons();
                    fetchChapterContent(nextIndex);
                    return;
                }

                // 检查内容是否有效
                boolean hasValidContent = content.length() >= 50;

                if (hasValidContent) {
                    // 有有效内容，直接跳转
                    loadChapterContent(nextIndex);
                } else {
                    // 内容无效，尝试找下一个有效章节
                    int searchIndex = nextIndex;
                    while (searchIndex < chapterList.size()) {
                        if (searchIndex >= chapterContents.size()) break;
                        String searchContent = chapterContents.get(searchIndex);
                        boolean isValid = searchContent != null
                                && !searchContent.contains("加载中...")
                                && searchContent.length() >= 50;
                        if (isValid) {
                            loadChapterContent(searchIndex);
                            return;
                        }
                        searchIndex++;
                    }
                    // 没有找到有效章节，尝试加载下一个
                    currentChapterIndex = nextIndex;
                    updateChapterButtons();
                    fetchChapterContent(nextIndex);
                }
                } catch (Throwable t) {
                    android.util.Log.e("ReadActivity", "JsBridge.onChapterEnd 崩溃", t);
                    Toast.makeText(ReadActivity.this, "翻到下一章失败", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onChapterStart() {
            runOnUiThread(() -> {
                try {
                int prevIndex = currentChapterIndex - 1;

                // 检查是否还有上一章
                if (prevIndex < 0) {
                    // 确实是第一章
                    Toast.makeText(ReadActivity.this, "已经是第一章", Toast.LENGTH_SHORT).show();
                    return;
                }

                // ✅ 防御：chapterContents 越界
                if (prevIndex >= chapterContents.size()) {
                    fetchChapterContent(prevIndex);
                    return;
                }
                // 检查上一章的状态
                String content = chapterContents.get(prevIndex);

                if (content == null || content.contains("加载中...")) {
                    // 上一章正在加载或尚未加载，触发加载
                    fetchChapterContent(prevIndex);
                    return;
                }

                // 检查内容是否有效
                boolean hasValidContent = content.length() >= 50;

                if (hasValidContent) {
                    // 有有效内容，直接跳转到最后一页
                    loadChapterContentToLastPage(prevIndex);
                } else {
                    // 内容无效（可能是空章节），尝试加载或跳过
                    if (content.trim().isEmpty() || content.equals("【本章节内容暂缺】")) {
                        // 如果是明确标记为缺失的内容，尝试重新加载
                        fetchChapterContent(prevIndex);
                    } else {
                        // 内容太短，可能是空章节，尝试找上一个有效章节
                        int searchIndex = prevIndex - 1;
                        while (searchIndex >= 0 && searchIndex < chapterContents.size()) {
                            String searchContent = chapterContents.get(searchIndex);
                            boolean isValid = searchContent != null
                                    && !searchContent.contains("加载中...")
                                    && searchContent.length() >= 50;
                            if (isValid) {
                                loadChapterContentToLastPage(searchIndex);
                                return;
                            }
                            searchIndex--;
                        }
                        // 没有找到有效章节，尝试加载上一个
                        fetchChapterContent(prevIndex);
                    }
                }
                } catch (Throwable t) {
                    android.util.Log.e("ReadActivity", "JsBridge.onChapterStart 崩溃", t);
                    Toast.makeText(ReadActivity.this, "翻到上一章失败", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onPaginationComplete(String summaryJson) {
            try {
                JSONObject json = new JSONObject(summaryJson);
            int chIndex = json.getInt("chapterIndex");
            // ✅ 外站书 safeBookId()=0 时退化为"0+sourceType"复合 key，避免 currentBook.getId()=null 自动拆箱 NPE
            long bid = safeBookId();
            String key;
            if (bid > 0) {
                key = "page_info_" + bid + "_" + chIndex + "_" + currentFontSize + "_" + webView.getWidth();
            } else {
                String st = currentBook != null && currentBook.getSourceType() != null ? currentBook.getSourceType() : "";
                String su = currentBook != null && currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "";
                key = "page_info_ext_" + st + "_" + su + "_" + chIndex + "_" + currentFontSize + "_" + webView.getWidth();
            }
            pageCachePref.edit().putString(key, summaryJson).apply();
            } catch (Exception e) {
                android.util.Log.w("ReadActivity", "Failed to parse pagination summary", e);
            }
        }
    }

    /** 外站书籍：根据 externalChapters 构建 chapterList、恢复阅读进度、加载当前章节正文。 */
    private void buildExternalChapterListAndLoad() {
        chapterList.clear();
        chapterContents.clear();
        for (int i = 0; i < externalChapters.length; i++) {
            Chapter dto = new Chapter();
            dto.setId(i);                       // 用 index 做 id
            dto.setIndex(i);
            String[] pair = externalChapters[i];
            String title = (pair != null && pair.length > 0) ? pair[0] : "第" + (i + 1) + "章";
            dto.setTitle(title);
            chapterList.add(dto);
            // 检查章节URL是否有效
            String chapterUrl = (pair != null && pair.length > 1) ? pair[1] : "";
            if (chapterUrl == null || chapterUrl.isEmpty()) {
                chapterContents.add("【章节链接缺失】");
            } else {
                chapterContents.add("【正在加载...】");
            }
        }
        // 从本地记录恢复阅读进度
        String recordKey = "ext_" + (currentBook.getSourceType() != null ? currentBook.getSourceType() : "")
                + "|" + (currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "");
        SharedPreferences pref = getSharedPreferences("external_reading_records", MODE_PRIVATE);
        int savedIdx = pref.getInt(recordKey + "_chapterIndex", currentChapterIndex);
        currentChapterIndex = Math.min(savedIdx, chapterList.size() - 1);
        // ✅ 读取保存的页码，用于恢复到具体页
        int savedPage = pref.getInt(recordKey + "_page", 1);

        tvToolbarTitle.setText(currentBook.getTitle());
        updateChapterButtons();
        // 如果当前章节URL缺失，显示错误提示
        String[] currentPair = externalChapters[currentChapterIndex];
        String currentUrl = (currentPair != null && currentPair.length > 1) ? currentPair[1] : "";
        if (currentUrl == null || currentUrl.isEmpty()) {
            renderChapterContent(currentChapterIndex, chapterList.get(currentChapterIndex).getTitle(),
                    "【章节链接缺失，请返回目录选择其他章节】");
            showExternalRetry("章节链接缺失，无法加载");
        } else {
            // ✅ 标记已恢复位置，阻止 onPageFinished → restoreReadingPosition 重复渲染（会导致闪回第1页）
            positionRestored = true;
            loadExternalChapterContent(currentChapterIndex, savedPage);
        }
    }

    /** 外站章节加载失败：友好对话框，点"返回"回退到详情页，不出现白屏。 */
    private void showExternalLoadErrorDialog(String reason, boolean sourceMayDown) {
        String msg = (reason == null || reason.isEmpty() ? "" : "原因：" + reason + "\n")
                + (sourceMayDown ? "本书源可能已失效或被封禁，请稍后重试或切换书源。"
                                 : "请检查网络后重试，或返回详情页。");
        new android.app.AlertDialog.Builder(this)
                .setTitle("加载失败")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("返回", (d, w) -> finish())
                .setNegativeButton("重试", (d, w) -> loadChaptersFromServer())
                .show();
    }

    private void loadChaptersFromServer() {
        positionRestored = false;

        // ===== 外站书籍：Intent extra 优先 → 共享缓存兜底 → 网络异步拉取 =====
        if (isExternalBook) {
            if (externalChapters == null || externalChapters.length == 0) {
                java.util.List<String[]> cached = BookDetailActivity.getExternalChapters(this, currentBook);
                if (cached != null && !cached.isEmpty()) {
                    externalChapters = cached.toArray(new String[0][]);
                }
            }
            if (externalChapters != null && externalChapters.length > 0) {
                buildExternalChapterListAndLoad();
                return;
            }
            // Intent 和缓存都未命中：占位显示 + 异步 API 拉取；失败弹对话框，不白屏
            if (currentBook.getSourceType() == null || currentBook.getSourceUrl() == null) {
                showExternalLoadErrorDialog("书源信息缺失", true);
                return;
            }
            currentChapterIndex = Math.max(0, currentChapterIndex);
            Chapter tempChapter = new Chapter();
            tempChapter.setIndex(0);
            tempChapter.setId(-1);
            tempChapter.setTitle("加载中...");
            chapterList.add(tempChapter);
            chapterContents.add("【正在加载章节列表...】");
            renderChapterContent(0, tempChapter.getTitle(), "【正在加载章节列表...】");
            tvToolbarTitle.setText(currentBook.getTitle());
            final String st = currentBook.getSourceType();
            final String sb = currentBook.getSourceUrl();
            // 启动章节列表加载超时保护
            final Runnable timeoutTask = () -> {
                if (externalChapters == null || externalChapters.length == 0) {
                    runOnUiThread(() -> showExternalLoadErrorDialog("加载超时，请检查网络后重试", false));
                }
            };
            mainHandler.postDelayed(timeoutTask, 20000);
            RetrofitClient.getApiService().getOnlineChapterList(st, sb).enqueue(new Callback<ApiResponse<java.util.List<String[]>>>() {
                @Override
                public void onResponse(@NonNull Call<ApiResponse<java.util.List<String[]>>> call,
                                       @NonNull Response<ApiResponse<java.util.List<String[]>>> response) {
                    mainHandler.removeCallbacks(timeoutTask);
                    boolean ok = response.isSuccessful() && response.body() != null
                            && response.body().isSuccess() && response.body().getData() != null
                            && !response.body().getData().isEmpty();
                    if (ok) {
                        java.util.List<String[]> list = response.body().getData();
                        externalChapters = list.toArray(new String[0][]);
                        BookDetailActivity.putExternalChapters(ReadActivity.this, currentBook, list);
                        runOnUiThread(() -> buildExternalChapterListAndLoad());
                    } else {
                        String msg = response.body() != null ? response.body().getMessage() : "获取章节失败";
                        if (msg == null || msg.isEmpty()) msg = "获取章节失败";
                        final String fmsg = msg;
                        runOnUiThread(() -> showExternalLoadErrorDialog(fmsg, true));
                    }
                }
                @Override
                public void onFailure(@NonNull Call<ApiResponse<java.util.List<String[]>>> call, @NonNull Throwable t) {
                    mainHandler.removeCallbacks(timeoutTask);
                    final String emsg = "网络错误：" + t.getMessage();
                    runOnUiThread(() -> showExternalLoadErrorDialog(emsg, false));
                }
            });
            return;
        }

        long localBookId = safeBookId();
        int localCh = -1;
        SharedPreferences pref = getSharedPreferences("reading_records", MODE_PRIVATE);
        int cnt = pref.getInt("record_count", 0);
        // ✅ 外站书（bookId==0）按 sourceType+sourceUrl 组合查 external_reading_records；
        //    服务器书（bookId>0）继续走原有 reading_records。
        if (isExternalBook) {
            String recordKey = "ext_" + (currentBook != null && currentBook.getSourceType() != null ? currentBook.getSourceType() : "")
                    + "|" + (currentBook != null && currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "");
            SharedPreferences extPref = getSharedPreferences("external_reading_records", MODE_PRIVATE);
            localCh = extPref.getInt(recordKey + "_chapterIndex", -1);
        } else if (localBookId > 0) {
            for (int i = 0; i < cnt; i++) {
                if (pref.getLong("record_bookId_" + i, 0) == localBookId) {
                    localCh = pref.getInt("record_chapterIndex_" + i, 0);
                    break;
                }
            }
        }
        final int targetChapter = localCh >= 0 ? localCh : currentChapterIndex;

        // ✅ 性能优化：调用并行加载策略
        initChapterListAndRestoreProgress(targetChapter);
    }

    /**
     * ✅ 优化后的并行加载策略 - 简化逻辑，避免索引混乱
     */
    private void initChapterListAndRestoreProgress(int targetChapter) {
        final long bookId = safeBookId();
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long uidParsed = 0;
        try {
            uidParsed = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        } catch (NumberFormatException nfe) {
            uidParsed = 0;
        }
        final long userId = uidParsed;  // effectively final，供匿名内部类引用
        long startTime = System.currentTimeMillis();

        if (isLocalBook) { loadLocalBookChapters(bookId, targetChapter); return; }
        // ✅ 外站书永远走 buildExternalChapterListAndLoad（章节来自 Intent/缓存/服务器 getExternalChapters API）
        if (isExternalBook) {
            if (externalChapters != null && externalChapters.length > 0) {
                buildExternalChapterListAndLoad();
                return;
            }
            // externalChapters 还在 getExternalChapters 回调里，等 onResponse 到来自动构建
            return;
        }
        // bookId==0 但不是外站书：容错回到第一章并提示
        if (bookId <= 0) {
            android.util.Log.w("ReadActivity", "initChapterListAndRestoreProgress: 服务器书 bookId<=0，跳过 API 请求");
            return;
        }

        android.util.Log.d("ReadActivity", "Starting optimized parallel loading...");
        
        // ✅ 步骤1：尝试从本地缓存恢复完整的章节列表
        boolean hasFullCache = restoreFullChapterListFromCache(bookId);
        
        if (hasFullCache) {
            android.util.Log.d("ReadActivity", "Restored full chapter list from cache: " + chapterList.size() + " chapters");

            // ✅ 关键修复：先检查是否有阅读记录（包含页码信息）
            SharedPreferences pref = getSharedPreferences("reading_records", MODE_PRIVATE);
            int cnt = pref.getInt("record_count", 0);
            int savedPage = 1;  // 默认第1页
            boolean foundRecord = false;

            for (int i = 0; i < cnt; i++) {
                if (pref.getLong("record_bookId_" + i, 0) == bookId
                        && pref.getInt("record_chapterIndex_" + i, -1) == targetChapter) {
                    savedPage = pref.getInt("record_page_" + i, 1);
                    foundRecord = true;
                    android.util.Log.d("ReadActivity", "Found reading record: chapter=" + targetChapter + ", page=" + savedPage);
                    break;
                }
            }

            // 立即加载目标章节内容（如果有缓存）
            String cachedContent = getChapterContentCache(bookId, targetChapter);
            if (cachedContent != null && cachedContent.length() >= 50) {
                android.util.Log.d("ReadActivity", "Found cached content for chapter " + targetChapter + ", length=" + cachedContent.length());
                currentChapterIndex = targetChapter;
                // ✅ 先应用设置，再渲染内容，避免默认设置闪烁
                applySettingsToWebView();
                // ✅ 关键修复：直接渲染到 savedPage，避免"先显示第1页再翻页"的动画
                renderChapterContent(targetChapter, chapterList.get(targetChapter).getTitle(), cachedContent, savedPage);
                updateChapterButtons();
                hasRestoredFromLocal = true;
                chapterRestoredFromCache = true;
                positionRestored = true;
            } else {
                // 没有内容缓存，显示占位符并触发加载
                currentChapterIndex = targetChapter;
                renderChapterContent(targetChapter, chapterList.get(targetChapter).getTitle(), "【正在加载章节内容...】");
                fetchChapterContent(targetChapter);
            }

            long cacheLoadTime = System.currentTimeMillis() - startTime;
            android.util.Log.d("ReadActivity", "Cache displayed in " + cacheLoadTime + "ms, now checking server for updates...");
        } else {
            android.util.Log.d("ReadActivity", "No full cache available, waiting for server response...");
            // 显示加载中提示
            currentChapterIndex = targetChapter;
            Chapter tempChapter = new Chapter();
            tempChapter.setIndex(targetChapter);
            tempChapter.setId(-1);
            tempChapter.setTitle("加载中...");
            chapterList.add(tempChapter);
            chapterContents.add("【正在加载章节列表...】");
            renderChapterContent(0, tempChapter.getTitle(), "【正在加载章节列表...】");
        }
        
        // ✅ 步骤2：异步请求服务器章节列表（后台更新）
        RetrofitClient.getApiService().getChapters(bookId).enqueue(new Callback<ApiResponse<List<ChapterDto>>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<List<ChapterDto>>> call, @NonNull Response<ApiResponse<List<ChapterDto>>> response) {
                long serverResponseTime = System.currentTimeMillis() - startTime;
                
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    List<ChapterDto> list = response.body().getData();
                    if (list == null || list.isEmpty()) {
                        android.util.Log.e("ReadActivity", "Server returned empty chapter list");
                        return;
                    }
                    
                    android.util.Log.d("ReadActivity", "Server chapters loaded in " + serverResponseTime + "ms: count=" + list.size());
                    
                    // 缓存章节列表元数据
                    cacheChapterListOnly(bookId, list);
                    
                    // ✅ 关键：用服务器数据替换当前章节列表
                    int savedChapterIndex = currentChapterIndex;
                    android.util.Log.d("ReadActivity", "Before merge: currentChapterIndex=" + savedChapterIndex);
                    
                    mergeServerData(list);
                    
                    // 恢复章节索引（确保不越界）
                    currentChapterIndex = Math.min(savedChapterIndex, chapterList.size() - 1);
                    android.util.Log.d("ReadActivity", "After merge: restored currentChapterIndex=" + currentChapterIndex);
                    
                    mainHandler.post(() -> {
                        android.util.Log.d("ReadActivity", "Server data arrived, refreshing UI...");

                        // ✅ 关键修复：如果已经通过本地缓存正确恢复了位置和内容，
                        // 不再调用 restoreReadingPosition（否则仿真模式下会重复 loadContent 导致闪屏）
                        if (!hasRestoredFromLocal) {
                            // ✅ 关键修复：使用 restoreReadingPosition 来恢复页码
                            restoreReadingPosition(currentChapterIndex);
                        } else {
                            // ✅ 已有缓存内容且已恢复页码，只需更新按钮状态
                            // 不要重新渲染章节内容，否则会清除页码状态导致跳回第一页
                            updateChapterButtons();
                            android.util.Log.d("ReadActivity", "Already restored from cache with page position, skip re-rendering");
                        }

                        long totalLoadTime = System.currentTimeMillis() - startTime;
                        android.util.Log.d("ReadActivity", "Total loading time: " + totalLoadTime + "ms");
                    });
                    
                    // 获取服务器进度（仅在首次打开时）
                    if (targetChapter == 0 && !hasLocalRecord(bookId)) {
                        fetchServerProgress(userId, bookId);
                    }
                } else {
                    android.util.Log.e("ReadActivity", "Server response failed: " + (response.isSuccessful() ? "data error" : "code=" + response.code()));
                }
            }
            
            @Override 
            public void onFailure(@NonNull Call<ApiResponse<List<ChapterDto>>> call, @NonNull Throwable t) {
                long failureTime = System.currentTimeMillis() - startTime;
                android.util.Log.e("ReadActivity", "Failed to load chapters from server in " + failureTime + "ms", t);
                
                mainHandler.post(() -> {
                    if (!hasRestoredFromLocal) {
                        Toast.makeText(ReadActivity.this, "网络请求失败，使用本地缓存", Toast.LENGTH_SHORT).show();
                        loadChapterContent(targetChapter);
                    }
                    applySettingsToWebView();
                    updateChapterButtons();
                });
            }
        });
    }
    
    /**
     * ✅ 新增：从缓存恢复完整的章节列表
     * @return true 如果成功恢复完整列表
     */
    private boolean restoreFullChapterListFromCache(long bookId) {
        SharedPreferences sp = getSharedPreferences("chapter_list_" + bookId, MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        
        if (count <= 0) {
            android.util.Log.d("ReadActivity", "No cached chapter list found");
            return false;
        }
        
        chapterList.clear();
        chapterContents.clear();
        
        for (int i = 0; i < count; i++) {
            String title = getChapterTitleCache(bookId, i);
            long chapterId = getChapterIdCache(bookId, i);
            
            if (title != null) {
                Chapter ch = new Chapter();
                ch.setIndex(i);
                ch.setId(chapterId > 0 ? chapterId : -1);
                ch.setTitle(title);
                chapterList.add(ch);
                
                String content = getChapterContentCache(bookId, i);
                chapterContents.add(content != null ? content : "");
            }
        }
        
        return !chapterList.isEmpty();
    }
    
    /**
     * ✅ 新增：从服务器获取阅读进度
     */
    private void fetchServerProgress(long userId, long bookId) {
        RetrofitClient.getApiService().getProgress(userId, bookId).enqueue(new Callback<ApiResponse<ReadingProgress>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<ReadingProgress>> call, @NonNull Response<ApiResponse<ReadingProgress>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess() && response.body().getData() != null) {
                    int savedChapter = response.body().getData().getChapterIndex();
                    int savedPage = response.body().getData().getScrollPosition();
                    if (savedChapter >= 0 && savedChapter < chapterList.size()) {
                        loadChapterContentWithPage(savedChapter, savedPage);
                        positionRestored = true;
                    }
                }
            }
            @Override 
            public void onFailure(@NonNull Call<ApiResponse<ReadingProgress>> call, @NonNull Throwable t) {
                android.util.Log.d("ReadActivity", "Failed to fetch server progress", t);
            }
        });
    }

    private boolean hasLocalRecord(long bookId) {
        SharedPreferences pref = getSharedPreferences("reading_records", MODE_PRIVATE);
        int cnt = pref.getInt("record_count", 0);
        for (int i = 0; i < cnt; i++) {
            if (pref.getLong("record_bookId_" + i, 0) == bookId) return true;
        }
        return false;
    }

    private void restoreReadingPosition(int chapterIndex) {
        try {
        android.util.Log.d("ReadActivity", "restoreReadingPosition: chapterIndex=" + chapterIndex + ", chapterList.size()=" + chapterList.size());

        // ✅ 防止重复调用：如果已经正确恢复了位置，跳过
        if (positionRestored) {
            android.util.Log.d("ReadActivity", "positionRestored already true, skip restoreReadingPosition");
            return;
        }

        // ✅ 外站书：用 external_reading_records（按 sourceType+sourceUrl 组合键查）
        if (isExternalBook) {
            String recordKey = "ext_" + (currentBook != null && currentBook.getSourceType() != null ? currentBook.getSourceType() : "")
                    + "|" + (currentBook != null && currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "");
            SharedPreferences pref = getSharedPreferences("external_reading_records", MODE_PRIVATE);
            int savedPage = pref.getInt(recordKey + "_page", 1);
            android.util.Log.d("ReadActivity", "外站书从本地记录恢复: page=" + savedPage);
            loadChapterContentWithPage(chapterIndex, savedPage);
            return;
        }

        long bookId = safeBookId();
        SharedPreferences pref = getSharedPreferences("reading_records", MODE_PRIVATE);
        int cnt = pref.getInt("record_count", 0);

        // ✅ bookId<=0 时不进循环按 bookId 精确匹配，回落到直接加载第一页
        if (bookId > 0) {
            for (int i = 0; i < cnt; i++) {
                if (pref.getLong("record_bookId_" + i, 0) == bookId
                        && pref.getInt("record_chapterIndex_" + i, -1) == chapterIndex) {
                    int savedPage = pref.getInt("record_page_" + i, 1);
                    android.util.Log.d("ReadActivity", "Found reading record: page=" + savedPage);
                    loadChapterContentWithPage(chapterIndex, savedPage);
                    return;
                }
            }
        }

        android.util.Log.d("ReadActivity", "No reading record found, loading from first page");
        loadChapterContent(chapterIndex);
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "restoreReadingPosition 异常", t);
            // 兜底：直接加载当前章节
            try { loadChapterContent(chapterIndex); } catch (Throwable ignored) {}
        }
    }

    private void loadChapterContent(int chapterIndex) {
        android.util.Log.d("ReadActivity", "loadChapterContent: chapterIndex=" + chapterIndex + ", chapterList.size()=" + chapterList.size());

        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
            android.util.Log.e("ReadActivity", "loadChapterContent: chapterIndex out of range! Will wait for full chapter list...");
            // ✅ 修正：如果索引超出范围，不直接返回，而是等待完整章节列表加载
            // 这种情况通常发生在章节列表尚未完全加载时
            return;
        }

        String title = chapterList.get(chapterIndex).getTitle();
        // ✅ 关键防御：chapterContents 与 chapterList 必须同步，否则用占位符避免 IndexOutOfBounds
        String content;
        if (chapterIndex < chapterContents.size()) {
            content = chapterContents.get(chapterIndex);
        } else {
            // 补齐 chapterContents 到 chapterList.size 防止后续 .set() 也越界
            while (chapterContents.size() < chapterList.size()) chapterContents.add("【正在加载...】");
            content = chapterContents.get(chapterIndex);
        }

        android.util.Log.d("ReadActivity", "loadChapterContent: title=" + title + ", content length=" + (content != null ? content.length() : 0));

        boolean isPlaceholder = content == null || content.contains("加载中...") || content.length() < 50;

        if (isPlaceholder) {
            android.util.Log.d("ReadActivity", "Content is placeholder, fetching from server");
            // ✅ 修正：立即更新 currentChapterIndex，让用户看到章节切换
            currentChapterIndex = chapterIndex;
            updateChapterButtons();

            // 显示加载提示（如果reader.html有showLoading函数）
            if (isWebViewReady) {
                try {
                    webView.evaluateJavascript("typeof showLoading === 'function' ? showLoading() : ''", null);
                } catch (Throwable ignored) {}
            }
            fetchChapterContent(chapterIndex);
        } else {
            android.util.Log.d("ReadActivity", "Content valid, rendering directly");
            // ✅ 修正：先更新 currentChapterIndex，再渲染内容
            currentChapterIndex = chapterIndex;
            updateChapterButtons();
            renderChapterContent(chapterIndex, title, content);
            // ✅ 修正：成功渲染后，设置 positionRestored 标志
            if (!positionRestored) {
                positionRestored = true;
                android.util.Log.d("ReadActivity", "positionRestored set to true after successful render");
            }
        }
    }

    /**
     * 重新从本地缓存加载指定章节的内容（用于本地书）
     */
    private void reloadLocalChapterContent(int chapterIndex) {
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        
        // ✅ 添加调试日志
        android.util.Log.d("ReadActivity", "reloadLocalChapterContent: bookId=" + currentBook.getId() + ", chapterIndex=" + chapterIndex + ", totalBooks=" + count);
        
        for (int i = 0; i < count; i++) {
            long bookId = sp.getLong("book_id_" + i, 0);
            if (bookId == currentBook.getId()) {
                int chCount = sp.getInt("chapter_count_" + i, 0);
                android.util.Log.d("ReadActivity", "Found book at index " + i + ", chapterCount=" + chCount);
                
                if (chapterIndex >= 0 && chapterIndex < chCount) {
                    String title = sp.getString("chapter_title_" + i + "_" + chapterIndex, "第" + (chapterIndex + 1) + "章");
                    String content = sp.getString("chapter_content_" + i + "_" + chapterIndex, "");
                    
                    android.util.Log.d("ReadActivity", "Read content length: " + content.length() + ", isEmpty: " + content.isEmpty());
                    
                    if (!content.isEmpty()) {
                        chapterList.get(chapterIndex).setTitle(title);
                        // 修正：只存储纯内容，不添加标题，避免重复显示
                        chapterContents.set(chapterIndex, content);
                        android.util.Log.d("ReadActivity", "Content loaded successfully");
                    } else {
                        android.util.Log.e("ReadActivity", "Content is empty! Key: chapter_content_" + i + "_" + chapterIndex);
                    }
                } else {
                    android.util.Log.e("ReadActivity", "Chapter index out of range: " + chapterIndex + ", chCount=" + chCount);
                }
                break;
            }
        }
    }

    /**
     * 外站书籍：通过后端 API 获取章节正文，带 LruCache + 磁盘双层缓存。
     * 加载失败时显示重试按钮（不缓存失败内容，确保重试能真正重新请求）。
     * 成功加载后预取下一章，翻页更顺滑。
     */
    private void loadExternalChapterContent(int chapterIndex) {
        loadExternalChapterContent(chapterIndex, 1);
    }

    /**
     * 加载外站章节内容并渲染到指定页码
     * @param chapterIndex 章节索引
     * @param targetPage 目标页码（从1开始），<=0 时默认第1页
     */
    private void loadExternalChapterContent(int chapterIndex, int targetPage) {
        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) return;
        // ✅ 关键防御：externalChapters 与 chapterList 长度必须一致，否则跳过
        // 避免异步加载途中共享缓存只写入一半时，目录点章触发 IndexOutOfBounds 崩溃
        if (externalChapters == null || chapterIndex >= externalChapters.length) {
            android.util.Log.w("ReadActivity", "loadExternalChapterContent: externalChapters missing for index=" + chapterIndex
                    + ", chapters.len=" + (externalChapters == null ? "null" : externalChapters.length)
                    + ", list.size=" + chapterList.size());
            runOnUiThread(() -> Toast.makeText(this, "章节信息缺失，请稍候重试", Toast.LENGTH_SHORT).show());
            return;
        }
        String[] pair = externalChapters[chapterIndex];
        if (pair == null) {
            runOnUiThread(() -> Toast.makeText(this, "章节信息无效", Toast.LENGTH_SHORT).show());
            return;
        }
        String chapterUrl = pair.length > 1 ? pair[1] : "";
        String title = chapterList.get(chapterIndex).getTitle();
        
        // 检查章节URL是否有效
        if (chapterUrl == null || chapterUrl.isEmpty()) {
            android.util.Log.w("ReadActivity", "loadExternalChapterContent: empty chapterUrl for index=" + chapterIndex);
            String noUrlContent = "【章节链接缺失，请返回目录选择其他章节】";
            chapterContents.set(chapterIndex, noUrlContent);
            if (currentChapterIndex == chapterIndex) {
                final int finalPage = targetPage;
                runOnUiThread(() -> {
                    hideExternalRetry();
                    renderChapterContent(chapterIndex, title, noUrlContent, finalPage);
                    showExternalRetry("章节链接缺失，无法加载");
                });
            }
            return;
        }
        
        String cacheKey = chapterIndex + "|" + chapterUrl;

        // 1. 优先读内存缓存（失败标记不视为有效缓存，继续走磁盘/网络）
        String cached = externalContentCache.get(cacheKey);
        if (cached == null || cached.isEmpty() || isExternalFailureContent(cached)) {
            // 2. 再读磁盘缓存（SharedPreferences，App 重启后仍可用，支持离线阅读）
            cached = readExternalContentFromDisk(cacheKey);
            if (cached != null && !cached.isEmpty() && !isExternalFailureContent(cached)) {
                externalContentCache.put(cacheKey, cached);
            }
        }
        if (cached != null && !cached.isEmpty() && !isExternalFailureContent(cached)) {
            chapterContents.set(chapterIndex, cached);
            final String finalCached = cached;
            final int finalChapterIndex = chapterIndex;
            final String finalTitle = title;
            if (currentChapterIndex == chapterIndex) {
                final int finalPage = targetPage;
                runOnUiThread(() -> {
                    hideExternalRetry();
                    renderChapterContent(finalChapterIndex, finalTitle, finalCached, finalPage);
                });
            }
            // 预加载下一章
            preloadNextExternalChapter(chapterIndex);
            return;
        }

        // 3. 显示加载占位
        if (currentChapterIndex == chapterIndex) {
            final int finalPage = targetPage;
            runOnUiThread(() -> {
                hideExternalRetry();
                renderChapterContent(chapterIndex, title, "【正在加载...】", finalPage);
            });
            // 启动加载超时保护：15秒后若仍未收到内容，显示超时
            final int timeoutChapterIndex = chapterIndex;
            mainHandler.postDelayed(() -> {
                if (currentChapterIndex == timeoutChapterIndex) {
                    String current = chapterContents.get(timeoutChapterIndex);
                    if (current != null && current.contains("正在加载")) {
                        runOnUiThread(() -> {
                            android.util.Log.w("ReadActivity", "章节加载超时: index=" + timeoutChapterIndex);
                            String timeoutContent = "【加载超时，请检查网络后重试】";
                            chapterContents.set(timeoutChapterIndex, timeoutContent);
                            showExternalRetry(timeoutContent);
                            renderChapterContent(timeoutChapterIndex, title, timeoutContent, finalPage);
                        });
                    }
                }
            }, 15000);
        }

        // 4. 调后端 API 获取正文
        String sourceType = currentBook.getSourceType();
        String sourceBookId = currentBook.getSourceUrl();
        RetrofitClient.getApiService().getOnlineChapterContent(sourceType, sourceBookId, chapterUrl)
                .enqueue(new Callback<ApiResponse<String>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<String>> call,
                                           @NonNull Response<ApiResponse<String>> response) {
                        boolean ok = response.isSuccessful() && response.body() != null
                                && response.body().isSuccess() && response.body().getData() != null;
                        String content;
                        if (ok) {
                            content = response.body().getData();
                            externalContentCache.put(cacheKey, content);
                            writeExternalContentToDisk(cacheKey, content);  // 持久化到磁盘
                        } else {
                            content = "【章节加载失败】"
                                    + (response.body() != null ? response.body().getMessage() : "");
                        }
                        chapterContents.set(chapterIndex, content);
                        final String finalContent = content;
                        if (currentChapterIndex == chapterIndex) {
                            final int finalPage = targetPage;
                            runOnUiThread(() -> {
                                if (ok) {
                                    hideExternalRetry();
                                    preloadNextExternalChapter(chapterIndex);  // 预加载下一章
                                } else {
                                    showExternalRetry(finalContent);
                                }
                                renderChapterContent(chapterIndex, title, finalContent, finalPage);
                            });
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<String>> call, @NonNull Throwable t) {
                        String content = "【网络错误】" + t.getMessage();
                        chapterContents.set(chapterIndex, content);
                        final String finalContent = content;
                        if (currentChapterIndex == chapterIndex) {
                            final int finalPage = targetPage;
                            runOnUiThread(() -> {
                                showExternalRetry(finalContent);
                                renderChapterContent(chapterIndex, title, finalContent, finalPage);
                            });
                        }
                    }
                });
    }

    /**
     * 预加载下一章：仅在内存/磁盘都未命中时异步拉取，不渲染当前页（loadExternalChapterContent 内部
     * 通过 currentChapterIndex != chapterIndex 的判断自动跳过渲染）。
     */
    private void preloadNextExternalChapter(int currentIndex) {
        int next = currentIndex + 1;
        if (next >= chapterList.size() || externalChapters == null || next >= externalChapters.length) return;
        String[] np = externalChapters[next];
        if (np == null) return;
        String nextUrl = np.length > 1 ? np[1] : "";
        String nextKey = next + "|" + nextUrl;
        // 已在内存或磁盘，无需重复请求
        if (externalContentCache.get(nextKey) != null) return;
        if (readExternalContentFromDisk(nextKey) != null) return;
        // 异步预取（不会渲染，因为 currentChapterIndex 仍为 currentIndex）
        loadExternalChapterContent(next);
    }

    // ========== 章节正文磁盘持久化（支持离线阅读） ==========

    private String externalContentCacheKeyPrefix() {
        String st = currentBook.getSourceType();
        String sb = currentBook.getSourceUrl();
        st = st == null ? "" : st;
        sb = sb == null ? "" : sb;
        return st + "|" + sb + "|";
    }

    private String readExternalContentFromDisk(String cacheKey) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("external_chapter_content_cache", MODE_PRIVATE);
            String v = sp.getString(externalContentCacheKeyPrefix() + cacheKey, "");
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeExternalContentToDisk(String cacheKey, String content) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("external_chapter_content_cache", MODE_PRIVATE);
            sp.edit().putString(externalContentCacheKeyPrefix() + cacheKey, content).apply();
            // 写入后裁剪：每本书仅保留最近 10 章正文，避免存储无限膨胀
            pruneExternalContentCache();
        } catch (Exception ignored) {
        }
    }

    /**
     * 裁剪当前书的磁盘章节正文缓存：按 chapterIndex 升序保留最近 10 章，
     * 删除更旧的章节正文（chapterUrl 不参与排序，仅作 key 的一部分）。
     */
    private void pruneExternalContentCache() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("external_chapter_content_cache", MODE_PRIVATE);
            String prefix = externalContentCacheKeyPrefix();
            java.util.Map<String, ?> all = sp.getAll();
            // key 形如：prefix + "chapterIndex|chapterUrl"，chapterIndex 为纯数字
            java.util.List<int[]> indexed = new java.util.ArrayList<>(); // {chapterIndex, keys 下标}
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (String k : all.keySet()) {
                if (!k.startsWith(prefix)) continue;
                String rest = k.substring(prefix.length());
                int sep = rest.indexOf('|');
                if (sep <= 0) continue;
                try {
                    int idx = Integer.parseInt(rest.substring(0, sep));
                    indexed.add(new int[]{idx, keys.size()});
                    keys.add(k);
                } catch (NumberFormatException ignored) {
                }
            }
            final int KEEP = 10;
            if (indexed.size() <= KEEP) return;
            // 按 chapterIndex 升序，删除最旧的 (size - KEEP) 章
            indexed.sort((a, b) -> Integer.compare(a[0], b[0]));
            int toRemove = indexed.size() - KEEP;
            android.content.SharedPreferences.Editor ed = sp.edit();
            for (int i = 0; i < toRemove; i++) {
                ed.remove(keys.get(indexed.get(i)[1]));
            }
            ed.apply();
        } catch (Exception ignored) {
        }
    }

    /** 判断外站章节内容是否为失败标记 */
    private static boolean isExternalFailureContent(String content) {
        return content != null
                && (content.startsWith("【章节加载失败】") || content.startsWith("【网络错误】"));
    }

    private void showExternalRetry(String msg) {
        if (layoutExternalRetry == null) return;
        if (tvExternalRetryMsg != null) {
            tvExternalRetryMsg.setText(msg != null && !msg.isEmpty() ? msg : "章节加载失败");
        }
        layoutExternalRetry.setVisibility(View.VISIBLE);
    }

    private void hideExternalRetry() {
        if (layoutExternalRetry != null) {
            layoutExternalRetry.setVisibility(View.GONE);
        }
    }


    private void fetchChapterContent(int chapterIndex) {
        try {
        // ===== 外站书籍：走在线 API 获取章节正文 =====
        if (isExternalBook) {
            loadExternalChapterContent(chapterIndex);
            return;
        }
        if (isLocalBook) {
            // 本地书不应该走网络请求，直接尝试从本地缓存重新加载
            reloadLocalChapterContent(chapterIndex);
            String title = chapterList.get(chapterIndex).getTitle();
            String content = chapterContents.get(chapterIndex);
            if (content != null && !content.contains("加载中...") && content.length() >= 50) {
                renderChapterContent(chapterIndex, title, content);
            } else {
                Toast.makeText(this, "章节内容缺失", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        
        // ✅ 简化：直接检查索引范围
        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
            android.util.Log.e("ReadActivity", "fetchChapterContent: chapterIndex out of range! index=" + chapterIndex + ", size=" + chapterList.size());
            Toast.makeText(this, "章节加载中，请稍后...", Toast.LENGTH_SHORT).show();
            return;
        }
        
        long chapterId = chapterList.get(chapterIndex).getId();
        android.util.Log.d("ReadActivity", "fetchChapterContent: chapterIndex=" + chapterIndex + ", chapterId=" + chapterId);
        
        if (chapterId <= 0) {
            // 章节ID无效通常是章节列表还未加载完成，静默等待服务器数据返回即可
            // 不显示错误Toast，避免打扰用户（占位符已显示，数据到达后会自动更新）
            android.util.Log.d("ReadActivity", "章节ID无效，等待列表加载... index=" + chapterIndex + ", id=" + chapterId);
            return;
        }

        RetrofitClient.getApiService().getChapterContent(chapterId).enqueue(new Callback<ApiResponse<com.example.myapplication.bean.Chapter>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<com.example.myapplication.bean.Chapter>> call,
                                   @NonNull Response<ApiResponse<com.example.myapplication.bean.Chapter>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    com.example.myapplication.bean.Chapter chapterBean = response.body().getData();
                    String realContent = chapterBean.getContent();
                    if (realContent == null || realContent.trim().isEmpty() || realContent.equals("\uFEFF")) {
                        realContent = "【本章节内容暂缺】";
                    }
                    chapterContents.set(chapterIndex, realContent);
                    cacheChapterContent(currentBook.getId(), chapterIndex, realContent);
                    
                    android.util.Log.d("ReadActivity", "fetchChapterContent onResponse: chapterIndex=" + chapterIndex + ", currentChapterIndex=" + currentChapterIndex + ", content length=" + realContent.length());
                    
                    if (currentChapterIndex == chapterIndex) {
                        android.util.Log.d("ReadActivity", "Re-rendering chapter " + chapterIndex + " with fetched content");
                        renderChapterContent(chapterIndex, chapterList.get(chapterIndex).getTitle(), realContent);
                    } else {
                        android.util.Log.d("ReadActivity", "Not current chapter, skip re-render");
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(ReadActivity.this, "章节加载失败", Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse<com.example.myapplication.bean.Chapter>> call, @NonNull Throwable t) {
                runOnUiThread(() -> Toast.makeText(ReadActivity.this, "网络错误", Toast.LENGTH_SHORT).show());
            }
        });
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "fetchChapterContent崩溃: index=" + chapterIndex, t);
            final String msg = t.getMessage() == null ? "未知错误" : t.getMessage();
            runOnUiThread(() -> {
                if (isExternalBook) {
                    showPopupErrorDialog("章节加载失败：" + msg);
                } else {
                    Toast.makeText(ReadActivity.this, "章节加载失败", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void renderChapterContent(int chapterIndex, String title, String content) {
        renderChapterContent(chapterIndex, title, content, 1);
    }

    /**
     * ✅ 支持传入目标页码，避免先显示第1页再动画跳转
     * @param startPage 从1开始的页码；传 <=0 表示不指定(=第1页)
     */
    private void renderChapterContent(int chapterIndex, String title, String content, int startPage) {
        try {
            if (!isWebViewReady) {
                final int finalChapterIndex = chapterIndex;
                final String finalTitle = title;
                final String finalContent = content;
                final int finalPage = startPage;
                mainHandler.postDelayed(() -> renderChapterContent(finalChapterIndex, finalTitle, finalContent, finalPage), 50);
                return;
            }

            String safeTitle = (title == null ? "" : title);
            String safeContent = (content == null ? "" : content);
            String escapedTitle = escapeJavaScript(safeTitle);
            String escapedContent = escapeJavaScript(safeContent);
            int bookProgress = (!chapterList.isEmpty()) ?
                    (int)((currentChapterIndex + 1) * 100f / chapterList.size()) : 0;
            // ✅ -1 表示跳到最后一页（loadChapterContentToLastPage 使用），必须原样传给 JS
            //    否则会被转成 null，JS loadContent 命中 else 分支回到第一页
            String pageArg = (startPage == -1) ? "-1" : (startPage > 0 ? String.valueOf(startPage) : "null");
            // ✅ 包装 loadContent 在 JS try/catch 中，捕获 JS 渲染异常并回传
            String js = "(function(){" +
                    "try{" +
                    "var r=loadContent(" + chapterIndex + "," + escapedTitle + "," + escapedContent + "," +
                    pageArg + "," + isLocalBook + "," + bookProgress + ");" +
                    "return 'ok';" +
                    "}catch(e){" +
                    "return 'error:'+e.message;" +
                    "}" +
                    "})()";
            webView.evaluateJavascript(js, value -> {
                if (value != null && value.startsWith("\"error:")) {
                    String errMsg = value.substring(8, value.length() - 1);
                    android.util.Log.e("ReadActivity", "JS render error: " + errMsg);
                    runOnUiThread(() -> {
                        if (isExternalBook) {
                            String fallback = "【章节渲染异常：" + errMsg + "】";
                            chapterContents.set(chapterIndex, fallback);
                            showExternalRetry(fallback);
                        }
                    });
                }
            });
            currentChapterIndex = chapterIndex;
            updateChapterButtons();

            // 设置已在 onPageFinished 中通过 applySettingsToWebView 预先应用
            // 仅需在加载新内容后重新设置翻页模式（仿真模式需要重建 flipbook）
            webView.evaluateJavascript("setPageTurnMode('" + pageTurnMode + "')", null);
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "renderChapterContent 崩溃: index=" + chapterIndex
                    + ", title.len=" + (title == null ? 0 : title.length())
                    + ", content.len=" + (content == null ? 0 : content.length()), t);
            runOnUiThread(() -> {
                String msg = "章节渲染失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                if (isExternalBook) {
                    showPopupErrorDialog(msg);
                } else {
                    Toast.makeText(ReadActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /**
     * ✅ 应用页眉页脚设置到 WebView
     */
    private void applyHeaderFooterSettings() {
        if (isWebViewReady) {
            webView.evaluateJavascript("setShowHeaderFooter(" + showHeaderFooter + ")", null);
            webView.evaluateJavascript("setHeaderFooterFontSize(" + (int)headerFooterFontSize + ")", null);
            webView.evaluateJavascript("setShowBatteryTime(" + showBatteryTime + ")", null);
            updateBatteryAndTime();
        }
    }

    /**
     * ✅ 应用字体大小到 WebView
     */
    private void applyFontSizeToWebView() {
        if (isWebViewReady) {
            webView.evaluateJavascript("setFontSize(" + currentFontSize + ")", null);
        }
    }

    /**
     * 应用字体族到 WebView
     */
    private void applyFontFamilyToWebView() {
        if (!isWebViewReady) return;
        if (FONT_SYSTEM_CSS.equals(currentFontFamily)) {
            // 系统字体：使用 sans-serif（WebView 默认跟随系统字体）
            webView.evaluateJavascript("setFontFamily('sans-serif')", null);
        } else if (FONT_DEFAULT_CSS.equals(currentFontFamily)) {
            // 默认字体
            webView.evaluateJavascript("setFontFamily('" + FONT_DEFAULT_CSS + "')", null);
        } else {
            // 后端下载的自定义字体：从内部存储加载 FontFace
            java.io.File fontFile = new java.io.File(getFilesDir(), "fonts/" + currentFontFamily + ".ttf");
            if (fontFile.exists()) {
                String fontPath = "file://" + fontFile.getAbsolutePath();
                webView.evaluateJavascript("loadCustomFont('" + fontPath + "', '" + currentFontFamily + "')", null);
            } else {
                // 字体文件不存在，回退到默认
                webView.evaluateJavascript("setFontFamily('" + FONT_DEFAULT_CSS + "')", null);
            }
        }
    }

    /**
     * ✅ 获取当前电量和时间，更新到 WebView
     */
    private void updateBatteryAndTime() {
        if (!isWebViewReady || !showBatteryTime || !showHeaderFooter) return;

        // 获取电量
        Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        String batteryText = "";
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra("level", -1);
            int scale = batteryStatus.getIntExtra("scale", 100);
            int pct = (level * 100) / scale;
            batteryText = pct + "%";
        }

        // 获取当前时间
        String timeText = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());

        String js = "updateBatteryTime(" + JSONObject.quote(batteryText) + "," + JSONObject.quote(timeText) + ")";
        webView.evaluateJavascript(js, null);
    }

    /** 加载章节并直接跳转到指定页（页码从1开始） */
    private void loadChapterContentWithPage(int chapterIndex, int page) {
        try {
        android.util.Log.d("ReadActivity", "loadChapterContentWithPage: chapterIndex=" + chapterIndex + ", page=" + page + ", chapterList.size()=" + chapterList.size());

        // ✅ 防止重复渲染：如果已经正确恢复了位置，跳过（避免 fetchServerProgress 等后续调用导致闪屏）
        if (positionRestored) {
            android.util.Log.d("ReadActivity", "positionRestored already true, skip loadChapterContentWithPage");
            return;
        }

        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
            android.util.Log.e("ReadActivity", "loadChapterContentWithPage: chapterIndex out of range! Will wait for full chapter list...");
            // ✅ 修正：如果索引超出范围，不直接返回，而是等待完整章节列表加载
            // 这种情况通常发生在章节列表尚未完全加载时
            return;
        }

        String title = chapterList.get(chapterIndex).getTitle();
        // ✅ 防御：chapterContents 越界时自动补齐
        if (chapterIndex >= chapterContents.size()) {
            while (chapterContents.size() < chapterList.size()) chapterContents.add("【正在加载...】");
        }
        String content = chapterContents.get(chapterIndex);

        android.util.Log.d("ReadActivity", "loadChapterContentWithPage: title=" + title + ", content length=" + (content != null ? content.length() : 0));

        // ✅ 检查内容是否有效，如果无效则重新从本地缓存加载
        boolean isPlaceholder = content == null || content.isEmpty() || content.contains("加载中...") || content.length() < 50;
        if (isLocalBook && isPlaceholder) {
            android.util.Log.d("ReadActivity", "Local book content is placeholder, reloading");
            // 本地书内容缺失，尝试重新加载
            reloadLocalChapterContent(chapterIndex);
            content = chapterIndex < chapterContents.size() ? chapterContents.get(chapterIndex) : "";
            // 更新 title，因为 reloadLocalChapterContent 可能会更新标题
            title = chapterList.get(chapterIndex).getTitle();
        }

        // 如果仍然没有有效内容，显示提示
        if (content == null || content.isEmpty() || content.contains("加载中...")) {
            Toast.makeText(this, "章节内容加载中...", Toast.LENGTH_SHORT).show();
            // 仍然尝试渲染，让 WebView 显示占位符
            content = content != null ? content : "【章节内容加载中，请稍后...】";
        }

        renderChapterContent(chapterIndex, title, content, page);

        // ✅ 应用设置到 WebView
        applyHeaderFooterSettings();
        applyFontSizeToWebView();
        applyFontFamilyToWebView();
        if (!isNightMode) applyBackgroundColorToWebView(currentBgColor);

        // ✅ 修正：成功调用 loadContent 后，设置 positionRestored 标志
        if (!positionRestored) {
            positionRestored = true;
            android.util.Log.d("ReadActivity", "positionRestored set to true after loadChapterContentWithPage");
        }
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "loadChapterContentWithPage 崩溃: index=" + chapterIndex, t);
            runOnUiThread(() -> {
                String msg = "章节加载失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                if (isExternalBook) showPopupErrorDialog(msg);
                else Toast.makeText(ReadActivity.this, msg, Toast.LENGTH_LONG).show();
            });
        }
    }

    /** 加载章节并直接跳到最后一页 */
    private void loadChapterContentToLastPage(int chapterIndex) {
        try {
        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) return;
        String title = chapterList.get(chapterIndex).getTitle();
        // ✅ 防御：chapterContents 越界自动补齐
        if (chapterIndex >= chapterContents.size()) {
            while (chapterContents.size() < chapterList.size()) chapterContents.add("【正在加载...】");
        }
        String content = chapterContents.get(chapterIndex);

        // ✅ 检查内容是否有效，如果无效则重新从本地缓存加载
        boolean isPlaceholder = content == null || content.isEmpty() || content.contains("加载中...") || content.length() < 50;
        if (isLocalBook && isPlaceholder) {
            // 本地书内容缺失，尝试重新加载
            reloadLocalChapterContent(chapterIndex);
            content = chapterIndex < chapterContents.size() ? chapterContents.get(chapterIndex) : "";
            // 更新 title，因为 reloadLocalChapterContent 可能会更新标题
            title = chapterList.get(chapterIndex).getTitle();
        }

        // 如果仍然没有有效内容，显示提示
        if (content == null || content.isEmpty() || content.contains("加载中...")) {
            Toast.makeText(this, "章节内容加载中...", Toast.LENGTH_SHORT).show();
            // 仍然尝试渲染，让 WebView 显示占位符
            content = content != null ? content : "【章节内容加载中，请稍后...】";
        }

        renderChapterContent(chapterIndex, title, content, -1);

        // ✅ 应用设置到 WebView
        applyHeaderFooterSettings();
        applyFontSizeToWebView();
        applyFontFamilyToWebView();
        if (!isNightMode) applyBackgroundColorToWebView(currentBgColor);
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "loadChapterContentToLastPage 崩溃", t);
            runOnUiThread(() -> Toast.makeText(ReadActivity.this, "章节加载失败", Toast.LENGTH_SHORT).show());
        }
    }

    // ==================== UI 更新 ====================
    @SuppressLint("SetTextI18n")
    private void updateProgressDisplay() {
        // 获取当前章节的实际标题
        String chapterTitle = "";
        if (currentChapterIndex >= 0 && currentChapterIndex < chapterList.size()) {
            chapterTitle = chapterList.get(currentChapterIndex).getTitle();
        }
        
        // 显示实际章节标题，而不是“第X章”，避免特殊章节（如作者的话、第x章(上)等）显示错误
        if (chapterTitle != null && !chapterTitle.isEmpty()) {
            tvProgressText.setText(chapterTitle + " " + currentPageInChapter + "/" + totalPagesInChapter + "页");
        } else {
            tvProgressText.setText("第" + (currentChapterIndex + 1) + "章 " + currentPageInChapter + "/" + totalPagesInChapter + "页");
        }
        
        int max = totalPagesInChapter > 1 ? totalPagesInChapter - 1 : 0;
        seekBarProgress.setMax(max);
        seekBarProgress.setProgress(currentPageInChapter - 1);
    }

    private void updateChapterButtons() {
        btnPrevChapter.setEnabled(currentChapterIndex > 0);
        btnPrevChapter.setAlpha(currentChapterIndex > 0 ? 1.0f : 0.5f);
        btnNextChapter.setEnabled(currentChapterIndex < chapterList.size() - 1);
        btnNextChapter.setAlpha(currentChapterIndex < chapterList.size() - 1 ? 1.0f : 0.5f);
    }

    // ==================== 阅读设置应用 ====================

    /** 构建注入 HTML 的初始设置脚本，确保首次渲染即使用保存的值 */
    private String buildSettingsInitScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("fontSize=").append((int) currentFontSize).append(";");
        sb.append("titleFontSize=fontSize+4;");

        // 字体
        String cssFont;
        if (FONT_SYSTEM_CSS.equals(currentFontFamily)) {
            cssFont = "sans-serif";
        } else {
            cssFont = currentFontFamily;
        }
        sb.append("fontFamily='").append(cssFont.replace("'", "\\'")).append("';");

        // ✅ 如果是已下载的自定义字体，注入 FontFace 加载路径（在 loadContent 后加载并重绘）
        if (!FONT_SYSTEM_CSS.equals(currentFontFamily) && !FONT_DEFAULT_CSS.equals(currentFontFamily)) {
            java.io.File fontFile = new java.io.File(getFilesDir(), "fonts/" + currentFontFamily + ".ttf");
            if (fontFile.exists()) {
                String fontPath = "file://" + fontFile.getAbsolutePath();
                sb.append("_pendingFontPath='").append(fontPath.replace("'", "\\'")).append("';");
                sb.append("_pendingFontName='").append(currentFontFamily.replace("'", "\\'")).append("';");
            }
        }

        // 背景色 / 夜间模式 / 纹理背景
        if (isNightMode) {
            sb.append("isNightMode=true;");
            sb.append("bgColor='#1A1A1A';");
            sb.append("textColor='#AAAAAA';");
        } else {
            int mode = currentBgColor;
            if (mode >= 10 && mode - 10 < BG_TEXTURE_FILES.length) {
                // ✅ 纹理背景：设置基础底色防止首帧白屏，然后启动纹理加载
                int texIdx = mode - 10;
                sb.append("bgColor='#FFFFFF';");
                sb.append("textColor='#1D1D1F';");
                sb.append("_pendingTexture='backgrounds/").append(BG_TEXTURE_FILES[texIdx]).append("';");
            } else {
                if (mode < 0 || mode >= BG_COLORS.length) mode = 0;
                sb.append("bgColor='").append(BG_COLORS[mode]).append("';");
                sb.append("textColor='").append(BG_TEXT_COLORS[mode]).append("';");
            }
        }

        // 页眉页脚
        sb.append("showHeaderFooter=").append(showHeaderFooter).append(";");
        sb.append("headerFooterFontSize=").append((int) headerFooterFontSize).append(";");
        sb.append("showBatteryTime=").append(showBatteryTime).append(";");
        sb.append("pageTurnMode='").append(pageTurnMode).append("';");

        // 更新 body 背景色以避免白闪
        sb.append("document.body.style.backgroundColor=bgColor;");

        // ✅ 启动加载待加载的字体/纹理资源（在 HTML 加载完成后立即开始，比等 loadContent 调用更快）
        sb.append("if (typeof startPendingResources === 'function') { startPendingResources(); }");

        // ✅ 关键修复：如果是仿真模式，立即展示书本占位容器，而不是显示空 canvas
        // 容器会显示正确的背景色/纹理，与最终内容呈现风格一致，避免"空白米黄页"
        if ("simulation".equals(pageTurnMode)) {
            sb.append("if (typeof showSimulationPlaceholder === 'function') { showSimulationPlaceholder(); }");
        }

        return sb.toString();
    }

    private void applySettingsToWebView() {
        webView.evaluateJavascript("beginSettingsBatch()", null);
        applySettingsToWebView_inner();
        webView.evaluateJavascript("finishSettingsBatch()", null);
        webView.evaluateJavascript("setPageTurnMode('" + pageTurnMode + "')", null);
    }

    /** 内部方法：应用字体、背景等设置（不含 batch 包装和翻页模式） */
    private void applySettingsToWebView_inner() {
        webView.evaluateJavascript("setFontSize(" + currentFontSize + ")", null);
        applyFontFamilyToWebView();
        if (isNightMode) {
            webView.evaluateJavascript("setNightMode(true)", null);
        } else {
            applyBackgroundColorToWebView(currentBgColor);
        }
    }

    private void applyBackgroundColorToWebView(int mode) {
        if (mode >= 10 && mode - 10 < BG_TEXTURE_FILES.length) {
            // 纹理模式：加载图片作为背景
            int texIdx = mode - 10;
            webView.evaluateJavascript("setBackgroundTexture('backgrounds/" + BG_TEXTURE_FILES[texIdx] + "')", null);
            webView.evaluateJavascript("setTextColor('#1D1D1F')", null);
        } else {
            // 纯色模式
            if (mode < 0 || mode >= BG_COLORS.length) mode = 0;
            webView.evaluateJavascript("clearBackgroundTexture()", null);
            webView.evaluateJavascript("setBackgroundColor('" + BG_COLORS[mode] + "')", null);
            webView.evaluateJavascript("setTextColor('" + BG_TEXT_COLORS[mode] + "')", null);
        }
    }

    /**
     * 把触摸事件投喂给 GestureDetector（每个 MotionEvent 只投喂一次）。
     * 同一个 MotionEvent 会先经过左右热区、再传到 WebView，最后可能冒泡到容器，
     * 三个 OnTouchListener 都会收到它；重复投喂会让 GestureDetector 连发两次
     * onSingleTapConfirmed（一次点击翻两页）以及错乱的 onFling。
     */
    private void feedGestureDetector(MotionEvent event) {
        if (event == null || gestureDetector == null) return;
        long t = event.getEventTime();
        int action = event.getActionMasked();
        if (t == lastGestureEventTime && action == lastGestureActionMasked) return; // 同一事件的重复投递
        lastGestureEventTime = t;
        lastGestureActionMasked = action;
        gestureDetector.onTouchEvent(event);
    }

    // ==================== 点击监听 ====================
    @SuppressLint("ClickableViewAccessibility")
    private void setupClickListeners() {
        // ===== 手势检测：支持 点击翻页 + 滑动翻页 + 点击切换导航栏 =====
        final float density = getResources().getDisplayMetrics().density;
        swipeMinDistancePx = (int) (SWIPE_MIN_DISTANCE * density);
        final int swipeMinVelocityPx = (int) (SWIPE_MIN_VELOCITY * density);

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                // 仿真翻页模式下点击同样交给前端（reader.html 的触摸处理）统一处理，
                // 与 onFling 在该模式让位的策略保持一致，避免两端各翻一次变成"点一下翻两页"
                if ("simulation".equals(pageTurnMode)) return true;

                float x = e.getX();
                int width = webView.getWidth();
                if (width == 0) width = getResources().getDisplayMetrics().widthPixels;
                float zone = width / 3f;

                if (x < zone) {
                    // 左 1/3：上一页
                    if (isWebViewReady) webView.evaluateJavascript("prevPage()", null);
                } else if (x > zone * 2) {
                    // 右 1/3：下一页
                    if (isWebViewReady) webView.evaluateJavascript("nextPage()", null);
                } else {
                    // 中间：切换导航栏
                    toggleNavigation();
                }
                return true;
            }

            @Override
            public boolean onFling(@NonNull MotionEvent e1, @NonNull MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (!swipePageTurn) return false;
                if (e1 == null || e2 == null) return false;
                // 仿真翻页模式下，滑动手势完全由 turn.js 仿真折页处理（含折页跟随与松手判定），
                // Android 端 onFling 在此模式让位，避免与 turn.js 拖拽翻页叠加导致一次滑动翻两页
                if ("simulation".equals(pageTurnMode)) return false;

                float deltaX = e2.getX() - e1.getX();
                float deltaY = e2.getY() - e1.getY();

                // 只处理水平为主的滑动（避免与垂直滚动冲突）
                if (Math.abs(deltaX) < swipeMinDistancePx
                        || Math.abs(deltaX) < Math.abs(deltaY) * 1.5f) {
                    return false;
                }

                if (Math.abs(velocityX) < swipeMinVelocityPx) return false;

                if (isWebViewReady) {
                    if (deltaX < 0) {
                        // 手指向左滑 → 下一页
                        webView.evaluateJavascript("nextPage()", null);
                    } else {
                        // 手指向右滑 → 上一页
                        webView.evaluateJavascript("prevPage()", null);
                    }
                }
                return true;
            }
        });

        // WebView 与容器都绑定手势，避免被 child view 拦截。
        // 注意：同一串 MotionEvent 会依次经过 左右热区 → WebView → 容器，
        // 必须统一走 feedGestureDetector() 去重，否则 GestureDetector 收到
        // 重复的 down/up 后会在 300ms 延时后连续回调两次 onSingleTapConfirmed，
        // 表现为"点一下翻两页"。
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                feedGestureDetector(event);
                return false; // 不消费，保留 WebView 原生滚动能力
            }
        });

        View container = findViewById(R.id.webview_container);
        container.setOnTouchListener((v, event) -> {
            feedGestureDetector(event);
            return false;
        });

        // 左右热区：改为非 clickable，仅保留视觉占位，由 GestureDetector 统一处理
        View leftTap = findViewById(R.id.view_left_tap);
        leftTap.setClickable(false);
        leftTap.setFocusable(false);
        leftTap.setOnTouchListener((v, event) -> {
            feedGestureDetector(event);
            return false;
        });
        View rightTap = findViewById(R.id.view_right_tap);
        rightTap.setClickable(false);
        rightTap.setFocusable(false);
        rightTap.setOnTouchListener((v, event) -> {
            feedGestureDetector(event);
            return false;
        });

        findViewById(R.id.iv_back).setOnClickListener(v -> {
            saveReadingRecord();
            finish();
        });
        findViewById(R.id.iv_more).setOnClickListener(v -> { resetAutoHideTimer(); showMoreMenu(); });

        btnPrevChapter.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (currentChapterIndex > 0) loadChapterContent(currentChapterIndex - 1);
        });
        btnNextChapter.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (currentChapterIndex < chapterList.size() - 1) loadChapterContent(currentChapterIndex + 1);
        });
        btnCatalog.setOnClickListener(v -> { resetAutoHideTimer(); showChapterPopup(); });
        btnNightMode.setOnClickListener(v -> {
            resetAutoHideTimer();
            isNightMode = !isNightMode;
            applyNightModeToNavOnly();
            // ✅ 目录/设置/书签等浮窗视图树实时跟随日/夜间配色
            themeShowingPopups();
            webView.evaluateJavascript("setNightMode(" + isNightMode + ")", null);
            // 退出夜间模式后恢复当前选中的背景色
            if (!isNightMode) {
                mainHandler.postDelayed(() -> applyBackgroundColorToWebView(currentBgColor), 50);
            }
            // ✅ 修复：夜间模式切换后立即保存
            saveReadingPreferences();
        });
        btnSettings.setOnClickListener(v -> { resetAutoHideTimer(); showSettingsDialog(); });

        seekBarProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && totalPagesInChapter > 1) {
                    webView.evaluateJavascript("jumpToPage(" + (progress + 1) + ")", null);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { mainHandler.removeCallbacks(hideNavRunnable); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { resetAutoHideTimer(); }
        });
    }

    // ==================== 导航栏控制 ====================

    /** 首次使用引导标记所在的 SharedPreferences */
    private static final String PREF_APP_GUIDE = "app_guide";
    private static final String KEY_READER_NAV_GUIDE_SHOWN = "reader_nav_guide_shown";

    /**
     * 判断并消费「首次使用本软件」的导航栏引导机会。
     * 只在软件安装后第一次进入阅读器时返回 true（随即把标记置为已展示），
     * 之后所有进入阅读器的场景都返回 false，不再自动展开上下导航栏。
     */
    private boolean consumeFirstUseNavGuide() {
        SharedPreferences sp = getSharedPreferences(PREF_APP_GUIDE, MODE_PRIVATE);
        if (sp.getBoolean(KEY_READER_NAV_GUIDE_SHOWN, false)) {
            return false;
        }
        sp.edit().putBoolean(KEY_READER_NAV_GUIDE_SHOWN, true).apply();
        return true;
    }

    /**
     * 无动画地直接把上下导航栏收起（进入阅读器时的默认状态）。
     * 与 hideNavigation() 的区别：不播放滑出动画，避免每次进书都闪一下导航栏。
     */
    private void hideNavigationImmediately() {
        hideSystemStatusBar();

        android.view.ViewGroup.LayoutParams params = layoutTopNav.getLayoutParams();
        params.height = (int) (56 * getResources().getDisplayMetrics().density);
        layoutTopNav.setLayoutParams(params);

        layoutTopNav.setPadding(layoutTopNav.getPaddingLeft(),
                0,
                layoutTopNav.getPaddingRight(),
                layoutTopNav.getPaddingBottom());

        mainHandler.removeCallbacks(hideNavRunnable);
        dismissPopups();

        layoutTopNav.animate().cancel();
        layoutBottomNav.animate().cancel();
        layoutTopNav.setTranslationY(0);
        layoutBottomNav.setTranslationY(0);
        layoutTopNav.setVisibility(View.GONE);
        layoutBottomNav.setVisibility(View.GONE);
    }

    private void showNavigation() {
        showSystemStatusBar();

        int navHeight = (int) (56 * getResources().getDisplayMetrics().density);
        android.view.ViewGroup.LayoutParams params = layoutTopNav.getLayoutParams();
        params.height = statusBarHeight + navHeight;
        layoutTopNav.setLayoutParams(params);

        layoutTopNav.setPadding(layoutTopNav.getPaddingLeft(),
                statusBarHeight,
                layoutTopNav.getPaddingRight(),
                layoutTopNav.getPaddingBottom());

        // 确保可见，再从屏幕外滑入
        layoutTopNav.setVisibility(View.VISIBLE);
        layoutBottomNav.setVisibility(View.VISIBLE);
        layoutTopNav.bringToFront();
        layoutBottomNav.bringToFront();

        // 顶部栏：从屏幕顶部滑入（y: -自身高度 -> 0）
        layoutTopNav.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float topSlideDistance = layoutTopNav.getMeasuredHeight();
        layoutTopNav.setTranslationY(-topSlideDistance);
        layoutTopNav.animate().translationY(0).setDuration(250).setListener(null).start();

        // 底部栏：从屏幕底部滑入（y: +自身高度 -> 0）
        layoutBottomNav.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float bottomSlideDistance = layoutBottomNav.getMeasuredHeight();
        layoutBottomNav.setTranslationY(bottomSlideDistance);
        layoutBottomNav.animate().translationY(0).setDuration(250).setListener(null).start();

        resetAutoHideTimer();
    }

    private void hideNavigation() {
        hideSystemStatusBar();

        android.view.ViewGroup.LayoutParams params = layoutTopNav.getLayoutParams();
        params.height = (int) (56 * getResources().getDisplayMetrics().density);
        layoutTopNav.setLayoutParams(params);

        layoutTopNav.setPadding(layoutTopNav.getPaddingLeft(),
                0,
                layoutTopNav.getPaddingRight(),
                layoutTopNav.getPaddingBottom());

        mainHandler.removeCallbacks(hideNavRunnable);
        dismissPopups();

        // 顶部栏：向上滑出屏幕
        layoutTopNav.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float topSlideDistance = layoutTopNav.getMeasuredHeight();
        layoutTopNav.animate().translationY(-topSlideDistance).setDuration(250)
                .withEndAction(() -> {
                    layoutTopNav.setVisibility(View.GONE);
                    layoutTopNav.setTranslationY(0);
                }).start();

        // 底部栏：向下滑出屏幕
        layoutBottomNav.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float bottomSlideDistance = layoutBottomNav.getMeasuredHeight();
        layoutBottomNav.animate().translationY(bottomSlideDistance).setDuration(250)
                .withEndAction(() -> {
                    layoutBottomNav.setVisibility(View.GONE);
                    layoutBottomNav.setTranslationY(0);
                }).start();
    }

    private void toggleNavigation() {
        if (layoutTopNav.getVisibility() == View.VISIBLE) {
            hideNavigation();
        } else {
            showNavigation();
        }
    }

    private void resetAutoHideTimer() {
        mainHandler.removeCallbacks(hideNavRunnable);
        mainHandler.postDelayed(hideNavRunnable, 5000);
    }

    private void dismissPopups() {
        if (chapterPopupWindow != null && chapterPopupWindow.isShowing()) chapterPopupWindow.dismiss();
        if (moreMenuPopupWindow != null && moreMenuPopupWindow.isShowing()) moreMenuPopupWindow.dismiss();
        if (settingsPopupWindow != null && settingsPopupWindow.isShowing()) settingsPopupWindow.dismiss();
        if (moreSettingsPopupWindow != null && moreSettingsPopupWindow.isShowing()) moreSettingsPopupWindow.dismiss();
        if (bgColorsPopupWindow != null && bgColorsPopupWindow.isShowing()) bgColorsPopupWindow.dismiss();
        if (fontsPopupWindow != null && fontsPopupWindow.isShowing()) fontsPopupWindow.dismiss();
    }

    // ==================== 目录 / 书签弹窗 ====================
    @SuppressLint("InflateParams")
    private void showChapterPopup() {
        try {
            if (chapterList == null || chapterList.isEmpty()) {
                Toast.makeText(this, "章节列表还在加载中，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            View popupView = LayoutInflater.from(this).inflate(R.layout.popup_chapter_list, null);
            TabLayout tabLayout = popupView.findViewById(R.id.tab_layout_popup);
            ViewPager2 vpPopup = popupView.findViewById(R.id.view_pager_popup);

            vpPopup.setAdapter(createPopupPagerAdapter());
            new TabLayoutMediator(tabLayout, vpPopup, (tab, position) -> tab.setText(position == 0 ? "目录" : "书签")).attach();

            // ✅ 目录浮窗外壳（TabLayout 等）跟随日/夜间配色；列表项由各 Fragment/Adapter 自行着色
            themeViewTree(popupView, isNightMode);

            int popupWidth = (int)(getResources().getDisplayMetrics().widthPixels * 0.75f);
            chapterPopupWindow = new PopupWindow(popupView, popupWidth, WindowManager.LayoutParams.MATCH_PARENT, true);
            chapterPopupWindow.setAnimationStyle(R.style.LeftSlideAnimation);
            chapterPopupWindow.showAtLocation(layoutBottomNav, Gravity.START, 0, 0);

            // 书签页（最后一页）继续左滑 → 平滑收起目录弹窗
            setupChapterPopupEdgeSwipe(vpPopup);

            mainHandler.removeCallbacks(hideNavRunnable);
            chapterPopupWindow.setOnDismissListener(this::resetAutoHideTimer);
            popupView.setOnTouchListener((v, event) -> { v.performClick(); chapterPopupWindow.dismiss(); return true; });
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "showChapterPopup 崩溃", t);
            showPopupErrorDialog("目录弹窗无法打开：" + t.getMessage());
        }
    }

    /**
     * 书签页（ViewPager2 最后一页，索引 1）继续左滑时，平滑收起目录弹窗。
     * 只在“当前页 == 最后一页”且为左滑（横向位移大于纵向）时触发，
     * 避免与「目录 → 书签」的正常分页滑动冲突。
     */
    private void setupChapterPopupEdgeSwipe(ViewPager2 vpPopup) {
        try {
            RecyclerView pagerRv = (RecyclerView) vpPopup.getChildAt(0);
            if (pagerRv == null) return;
            final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            final float[] downX = {0f};
            final float[] downY = {0f};
            final int[] startPage = {-1};
            RecyclerView.SimpleOnItemTouchListener listener = new RecyclerView.SimpleOnItemTouchListener() {
                private void track(RecyclerView rv, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX[0] = e.getX();
                            downY[0] = e.getY();
                            startPage[0] = vpPopup.getCurrentItem();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            float dx = downX[0] - e.getX();          // 左滑 dx > 0
                            float dy = Math.abs(downY[0] - e.getY());
                            // 必须“按下时已经在书签页”才收起，避免目录页左滑分页到书签时误关闭
                            if (dx > touchSlop && dx > dy && startPage[0] == 1) {
                                dismissChapterPopup();
                            }
                            break;
                    }
                }

                @Override
                public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                    track(rv, e);
                    return false; // 不拦截，交给 ViewPager2 正常处理
                }

                @Override
                public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                    track(rv, e);
                }
            };
            pagerRv.addOnItemTouchListener(listener);
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "setupChapterPopupEdgeSwipe 失败", t);
        }
    }

    /** 收起目录弹窗（带左滑出动画，由 PopupWindow 动画样式决定） */
    private void dismissChapterPopup() {
        if (chapterPopupWindow != null && chapterPopupWindow.isShowing()) {
            chapterPopupWindow.dismiss();
        }
    }

    private PopupPagerAdapter createPopupPagerAdapter() {
        List<Fragment> fragments = new ArrayList<>();
        // 防御性拷贝：避免底层 chapterList 在加载完服务器新列表时被 replaceAll 清空导致 Popup 渲染崩溃/空白
        final List<Chapter> safeCopy = new ArrayList<>(chapterList);
        final int safeIndex = Math.max(0, Math.min(currentChapterIndex, safeCopy.size() - 1));
        PopupChapterFragment chapterFragment = new PopupChapterFragment(safeCopy, safeIndex);
        chapterFragment.setOnChapterSelectedListener(chapterIndex -> {
            if (chapterPopupWindow != null) chapterPopupWindow.dismiss();
            if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
                Toast.makeText(ReadActivity.this, "章节索引无效，列表可能刚更新", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                if (chapterIndex != currentChapterIndex) {
                    loadChapterContent(chapterIndex);
                }
            } catch (Throwable t) {
                android.util.Log.e("ReadActivity", "目录切章崩溃", t);
                showPopupErrorDialog("切换章节失败：" + t.getMessage());
            }
        });
        fragments.add(chapterFragment);
        fragments.add(createBookmarkFragment());
        return new PopupPagerAdapter(this, fragments);
    }

    /** 目录弹窗相关报错时统一弹对话框（替代白屏/崩回详情页） */
    private void showPopupErrorDialog(String reason) {
        String msg = (reason == null ? "" : reason)
                + "\n\n若问题持续，请先返回书籍详情页从「目录」入口打开章节列表。";
        new AlertDialog.Builder(this)
                .setTitle("操作失败")
                .setMessage(msg)
                .setCancelable(true)
                .setNegativeButton("知道了", null)
                .setPositiveButton("返回详情", (d, w) -> finish())
                .show();
    }

    private PopupBookmarkFragment createBookmarkFragment() {
        PopupBookmarkFragment bookmarkFragment = new PopupBookmarkFragment();
        // ✅ 关键修复：外站书 currentBook.getId() 返回 null（Long 对象），
        // 若直接 setBookId(currentBook.getId()) 会触发 Long->long 自动拆箱 NPE：
        // "Attempt to invoke virtual method 'long java.lang.Long.longValue()' on a null object reference"
        Long rawId = currentBook.getId();
        long safeBookId = (rawId == null || rawId <= 0) ? 0L : rawId;
        bookmarkFragment.setBookId(safeBookId);
        bookmarkFragment.setIsLocalBook(isLocalBook);
        bookmarkFragment.setOnBookmarkSelectedListener(bookmark -> {
            try {
                if (bookmark == null) return;
                Integer chIdxObj = bookmark.getChapterIndex();
                Integer scrollObj = bookmark.getScrollPosition();
                int chIdx = chIdxObj == null ? 0 : chIdxObj;
                int scrollPos = scrollObj == null ? 0 : scrollObj;
                if (chIdx < 0 || chIdx >= chapterList.size()) {
                    Toast.makeText(ReadActivity.this, "书签章节索引已失效", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (chIdx != currentChapterIndex) {
                    loadChapterContent(chIdx);
                    webView.postDelayed(() -> {
                        try { webView.evaluateJavascript("jumpToPage(" + scrollPos + ")", null); } catch (Throwable ignored) {}
                    }, 300);
                } else {
                    try { webView.evaluateJavascript("jumpToPage(" + scrollPos + ")", null); } catch (Throwable ignored) {}
                }
                if (chapterPopupWindow != null) chapterPopupWindow.dismiss();
            } catch (Throwable t) {
                android.util.Log.e("ReadActivity", "书签选择回调崩溃", t);
                showPopupErrorDialog("书签跳转失败：" + t.getMessage());
            }
        });
        return bookmarkFragment;
    }

    // ==================== 进度保存 ====================
    private void saveReadingRecord() {
        if (currentBook == null || chapterList.isEmpty()) return;

        // ===== 外站书籍：保存到本地 SharedPreferences + 上传服务器 =====
        if (isExternalBook) {
            String recordKey = "ext_" + (currentBook.getSourceType() != null ? currentBook.getSourceType() : "")
                    + "|" + (currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "");
            SharedPreferences sp = getSharedPreferences("external_reading_records", MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            // 书源标识字段（用于 ReadingRecordActivity 重建记录）
            editor.putString(recordKey + "_sourceType", currentBook.getSourceType());
            editor.putString(recordKey + "_sourceUrl", currentBook.getSourceUrl());
            // 书籍基本信息（阅读记录页展示用）
            editor.putString(recordKey + "_bookName", currentBook.getTitle() != null ? currentBook.getTitle() : "");
            editor.putString(recordKey + "_author", currentBook.getAuthor() != null ? currentBook.getAuthor() : "");
            editor.putString(recordKey + "_cover", currentBook.getCover() != null ? currentBook.getCover() : "");
            // 阅读进度字段
            editor.putInt(recordKey + "_chapterIndex", currentChapterIndex);
            editor.putString(recordKey + "_chapterTitle",
                    currentChapterIndex < chapterList.size() ? chapterList.get(currentChapterIndex).getTitle() : "");
            editor.putInt(recordKey + "_page", currentPageInChapter);
            editor.putLong(recordKey + "_readTime", System.currentTimeMillis());
            editor.apply();

            // 上传到服务器（轻量级阅读记录）
            uploadExternalProgressToServer();
            return;
        }

        // ===== 本地/服务器书籍：原有逻辑 =====
        long bidForSave = safeBookId();
        if (bidForSave <= 0) return;
        SharedPreferences sp = getSharedPreferences("reading_records", MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        int count = sp.getInt("record_count", 0);
        int existIndex = -1;
        for (int i = 0; i < count; i++) {
            if (sp.getLong("record_bookId_" + i, 0) == bidForSave) { existIndex = i; break; }
        }
        if (existIndex == -1) { existIndex = count; editor.putInt("record_count", count + 1); }

        // 保存书籍基本信息
        editor.putLong("record_bookId_" + existIndex, bidForSave);
        editor.putString("record_bookName_" + existIndex, currentBook.getBookName());
        editor.putString("record_author_" + existIndex, currentBook.getAuthor() != null ? currentBook.getAuthor() : "");
        editor.putString("record_cover_" + existIndex, currentBook.getCover() != null ? currentBook.getCover() : "");

        // 保存章节信息
        editor.putInt("record_chapterIndex_" + existIndex, currentChapterIndex);
        String chapterTitle = (currentChapterIndex < chapterList.size()) ?
                chapterList.get(currentChapterIndex).getTitle() : "";
        editor.putString("record_chapterTitle_" + existIndex, chapterTitle);
        editor.putInt("record_totalChapters_" + existIndex, chapterList.size());

        // 保存阅读进度
        editor.putInt("record_page_" + existIndex, currentPageInChapter);
        editor.putInt("record_totalPages_" + existIndex, totalPagesInChapter);
        // 使用实际章节标题来计算进度，避免特殊章节影响
        int bookProgress = (!chapterList.isEmpty()) ?
                (int)((currentChapterIndex + 1) * 100f / chapterList.size()) : 0;
        editor.putInt("record_progress_" + existIndex, bookProgress);

        // 保存时间戳
        editor.putLong("record_readTime_" + existIndex, System.currentTimeMillis());

        editor.apply();

        uploadProgressToServer();
    }

    private void uploadProgressToServer() {
        String uidStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long uid = uidStr.isEmpty() ? 0 : Long.parseLong(uidStr);
        if (uid == 0 || currentBook == null) return;
        long bid = safeBookId();
        // 外站书无服务端 bookId，不调用 saveProgress（应该走 uploadExternalProgressToServer）
        if (bid <= 0) return;
        ReadingProgress p = new ReadingProgress();
        p.setUserId(uid);
        p.setBookId(bid);
        p.setChapterIndex(currentChapterIndex);
        p.setScrollPosition(currentPageInChapter);
        p.setFontSize(currentFontSize);
        p.setNightMode(isNightMode ? 1 : 0);
        p.setBgColor(currentBgColor);
        RetrofitClient.getApiService().saveProgress(p).enqueue(new Callback<ApiResponse<ReadingProgress>>() {
            @Override public void onResponse(@NonNull Call<ApiResponse<ReadingProgress>> call, @NonNull Response<ApiResponse<ReadingProgress>> response) {}
            @Override public void onFailure(@NonNull Call<ApiResponse<ReadingProgress>> call, @NonNull Throwable t) {}
        });
    }

    /**
     * 外站书籍阅读进度上传到服务器（轻量级记录，不保存书籍内容）。
     */
    private void uploadExternalProgressToServer() {
        String uidStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long uid = uidStr.isEmpty() ? 0 : Long.parseLong(uidStr);
        if (uid == 0 || currentBook == null) return;

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("userId", uid);
        body.put("sourceType", currentBook.getSourceType());
        body.put("sourceBookId", currentBook.getSourceUrl());
        body.put("chapterIndex", currentChapterIndex);
        body.put("chapterTitle",
                currentChapterIndex < chapterList.size() ? chapterList.get(currentChapterIndex).getTitle() : "");
        body.put("bookTitle", currentBook.getTitle());
        body.put("bookAuthor", currentBook.getAuthor() != null ? currentBook.getAuthor() : "");
        body.put("coverUrl", currentBook.getCover() != null ? currentBook.getCover() : "");
        body.put("page", currentPageInChapter);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(body);
        okhttp3.RequestBody rb = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json"), json);
        RetrofitClient.getApiService().saveExternalProgress(rb)
                .enqueue(new Callback<ApiResponse<Void>>() {
                    @Override public void onResponse(@NonNull Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> r) {}
                    @Override public void onFailure(@NonNull Call<ApiResponse<Void>> call, @NonNull Throwable t) {}
                });
    }

    // ==================== 阅读偏好与设置 ====================
    private void loadReadingPreferencesNoApply() {
        SharedPreferences sp = getSharedPreferences("read_settings", MODE_PRIVATE);
        currentFontSize = sp.getFloat("font_size", 28f);
        isNightMode = sp.getBoolean("night_mode", false);
        currentBgColor = sp.getInt("bg_color", 0);
        // ✅ 加载页眉页脚设置
        showHeaderFooter = sp.getBoolean("show_header_footer", true);
        headerFooterFontSize = sp.getFloat("header_footer_font_size", 12f); // 默认值改为12
        showBatteryTime = sp.getBoolean("show_battery_time", false);
        volumeKeyPageTurn = sp.getBoolean("volume_key_page_turn", true);
        swipePageTurn = sp.getBoolean("swipe_page_turn", true);
        pageTurnMode = sp.getString("page_turn_mode", "cover");
        // ✅ 加载字体设置
        currentFontFamily = sp.getString("font_family", "sans-serif");
        downloadedFonts = new java.util.HashSet<>(sp.getStringSet("downloaded_fonts", new java.util.HashSet<>()));
    }

    private void saveReadingPreferences() {
        SharedPreferences sp = getSharedPreferences("read_settings", MODE_PRIVATE);
        sp.edit()
                .putFloat("font_size", currentFontSize)
                .putBoolean("night_mode", isNightMode)
                .putInt("brightness", currentBrightness)
                .putBoolean("follow_system_brightness", followSystemBrightness)
                .putInt("bg_color", currentBgColor)
                // ✅ 保存页眉页脚设置
                .putBoolean("show_header_footer", showHeaderFooter)
                .putFloat("header_footer_font_size", headerFooterFontSize)
                .putBoolean("show_battery_time", showBatteryTime)
                .putBoolean("volume_key_page_turn", volumeKeyPageTurn)
                .putBoolean("swipe_page_turn", swipePageTurn)
                .putString("page_turn_mode", pageTurnMode)
                // ✅ 保存字体设置
                .putString("font_family", currentFontFamily)
                .putStringSet("downloaded_fonts", downloadedFonts)
                .apply();
        uploadProgressToServer();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (volumeKeyPageTurn) {
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (isWebViewReady) {
                    webView.evaluateJavascript("nextPage()", null);
                }
                return true;
            } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                if (isWebViewReady) {
                    webView.evaluateJavascript("prevPage()", null);
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void applyNightModeToNavOnly() {
        // 状态栏图标：日间深色（浅色背景），夜间浅色（深色背景）
        View decorView = getWindow().getDecorView();
        int flags = decorView.getSystemUiVisibility();
        if (isNightMode) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decorView.setSystemUiVisibility(flags);
        if (isNightMode) {
            layoutTopNav.setBackgroundColor(Color.parseColor("#171718"));   // iOS 深色毛玻璃
            layoutBottomNav.setBackgroundColor(Color.parseColor("#171718"));
            tvToolbarTitle.setTextColor(Color.WHITE);
            ivNightModeIcon.setImageResource(R.drawable.ic_day);
            tvNightModeText.setText("日间");
            tvNightModeText.setTextColor(Color.WHITE);
        } else {
            layoutTopNav.setBackgroundColor(Color.parseColor("#F9F9F9"));   // iOS 浅色毛玻璃
            layoutBottomNav.setBackgroundColor(Color.parseColor("#F9F9F9"));
            tvToolbarTitle.setTextColor(Color.parseColor("#1D1D1F"));      // iOS 主文字
            ivNightModeIcon.setImageResource(R.drawable.ic_night);
            tvNightModeText.setText("夜间");
            tvNightModeText.setTextColor(Color.parseColor("#8E8E93"));    // iOS 次文字
        }
        setIconsForTheme(isNightMode);
    }

    private void setIconsForTheme(boolean isNight) {
        // 图标 drawable 自身颜色会被 ImageView 的 tint 覆盖，因此仅切换 src 无效，
        // 必须同步切换 tint 才能让图标在日/夜间背景上都清晰可见。
        int dayIcon = ContextCompat.getColor(this, R.color.ios_text_primary);
        int nightIcon = Color.WHITE;
        int tint = isNight ? nightIcon : dayIcon;

        ImageView ivBack = findViewById(R.id.iv_back);
        ImageView ivMore = findViewById(R.id.iv_more);
        ivBack.setImageResource(isNight ? R.drawable.ic_back_white : R.drawable.ic_back_black);
        ivMore.setImageResource(isNight ? R.drawable.ic_more_vert_white : R.drawable.ic_more_vert_black);
        // 返回键日间使用主色蓝，其余图标与主文字同色；夜间统一浅色，确保深色背景上清晰可见
        ivBack.setImageTintList(ColorStateList.valueOf(isNight ? nightIcon : ContextCompat.getColor(this, R.color.ios_blue)));
        ivMore.setImageTintList(ColorStateList.valueOf(tint));

        ImageView ivCatalog = findViewById(R.id.iv_catalog_icon);
        ImageView ivSettings = findViewById(R.id.iv_settings_icon);
        ivCatalog.setImageResource(isNight ? R.drawable.ic_list_white : R.drawable.ic_list_black);
        ivSettings.setImageResource(isNight ? R.drawable.ic_settings_white : R.drawable.ic_settings_black);
        ivCatalog.setImageTintList(ColorStateList.valueOf(tint));
        ivSettings.setImageTintList(ColorStateList.valueOf(tint));

        // 夜间/日间切换图标自身也要跟随 tint（XML 中 tint 固定为深色，夜间会看不清）
        ivNightModeIcon.setImageTintList(ColorStateList.valueOf(tint));

        int textColor = isNight ? Color.WHITE : ContextCompat.getColor(this, R.color.ios_text_secondary);
        ((TextView) findViewById(R.id.tv_prev_chapter)).setTextColor(textColor);
        ((TextView) findViewById(R.id.tv_next_chapter)).setTextColor(textColor);
        ((TextView) findViewById(R.id.tv_catalog_text)).setTextColor(textColor);
        ((TextView) findViewById(R.id.tv_settings_text)).setTextColor(textColor);
    }

    // ==================== 弹窗日/夜间配色跟随（iOS 浅色磨砂玻璃风） ====================
    // 弹窗布局里写死了白底/深色文字，夜间模式下依旧刺眼。这里递归遍历视图树，
    // 将已知的日间配色映射为夜间配色（反之亦然），让目录/设置/书签等浮窗全部跟随主题。
    // 日间用 iOS 浅色调（#FFFFFF/#F2F2F7/#E5E5EA/#1D1D1F/#8E8E93/#C7C7CC/#007AFF），
    // 夜间用 iOS 深色调（#000000/#1C1C1E/#38383A/#FFFFFF/#8E8E93/#0A84FF）。
    private static final int NIGHT_BG_PRIMARY   = 0xFF000000; // 主背景（对应日间 ios_bg #FFFFFF）
    private static final int NIGHT_BG_SECONDARY = 0xFF1C1C1E; // 次背景（对应日间 ios_bg_grouped #F2F2F7）
    private static final int NIGHT_DIVIDER      = 0xFF38383A; // 分割线（对应日间 ios_separator #E5E5EA）
    private static final int NIGHT_TEXT_PRIMARY = 0xFFFFFFFF; // 主文字（对应日间 ios_text_primary #1D1D1F）
    private static final int NIGHT_TEXT_SECONDARY = 0xFF8E8E93; // 次文字（对应日间 ios_text_secondary #8E8E93）
    private static final int NIGHT_TEXT_TERTIARY  = 0xFF8E8E93; // 三级文字（对应日间 ios_text_tertiary #C7C7CC）

    /** 递归为弹窗视图树应用日/夜间配色：背景、分割线、文字、TabLayout。 */
    public static void themeViewTree(@Nullable View root, boolean isNight) {
        if (root == null) return;
        applyThemeRecursive(root, isNight);
    }

    private static void applyThemeRecursive(View view, boolean isNight) {
        // 1) 背景：ColorDrawable / GradientDrawable 纯色
        Drawable bg = view.getBackground();
        if (bg instanceof ColorDrawable) {
            int c = ((ColorDrawable) bg).getColor();
            int mapped = mapBgColor(c, isNight);
            if (mapped != c) view.setBackgroundColor(mapped);
        } else if (bg instanceof GradientDrawable) {
            try {
                android.content.res.ColorStateList csl = ((GradientDrawable) bg).getColor(); // API29+
                if (csl != null) {
                    int c = csl.getDefaultColor();
                    int mapped = mapBgColor(c, isNight);
                    if (mapped != c) ((GradientDrawable) bg).setColor(mapped);
                }
            } catch (Throwable ignored) { }
        }

        // 2) 文字颜色
        if (view instanceof TextView && !(view instanceof android.widget.Button)) {
            TextView tv = (TextView) view;
            int tc = tv.getCurrentTextColor();
            int mapped = mapTextColor(tc, isNight);
            if (mapped != tc) tv.setTextColor(mapped);
        }

        // 3) TabLayout：标签栏背景与文字（不递归进其内部 tab 子视图，避免与 setTabTextColors 冲突）
        if (view instanceof TabLayout) {
            TabLayout tl = (TabLayout) view;
            if (isNight) {
                tl.setBackgroundColor(NIGHT_BG_PRIMARY);
                tl.setTabTextColors(NIGHT_TEXT_SECONDARY, 0xFF0A84FF); // iOS 深色蓝
            } else {
                tl.setBackgroundColor(0xFFF2F2F7); // iOS 分组底
                tl.setTabTextColors(0xFF8E8E93, 0xFF007AFF); // iOS 次灰 / 系统蓝
            }
            tl.setSelectedTabIndicatorColor(isNight ? 0xFF0A84FF : 0xFF007AFF); // iOS 蓝指示器
            return;
        }

        // 4) 递归子节点（RecyclerView 仅返回已 attach 的可见项，遍历成本可控）
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyThemeRecursive(vg.getChildAt(i), isNight);
            }
        }
    }

    private static int mapBgColor(int c, boolean isNight) {
        if (isNight) {
            // 日间 iOS 浅色 → 夜间 iOS 深色
            if (c == 0xFFFFFFFF) return NIGHT_BG_PRIMARY;                       // ios_bg
            if (c == 0xFFF2F2F7 || c == 0xFFEFEFF4) return NIGHT_BG_SECONDARY;  // ios_bg_grouped
            if (c == 0xFFE5E5EA || c == 0xFFC6C7CC) return NIGHT_DIVIDER;       // ios_separator
            // 兼容旧色值（历史弹窗可能仍残留）
            if (c == 0xFFFAFAFA || c == 0xFFF5F5F5) return NIGHT_BG_SECONDARY;
            if (c == 0xFFE0E0E0 || c == 0xFFEEEEEE || c == 0xFFF0F0F0) return NIGHT_DIVIDER;
        } else {
            // 夜间 iOS 深色 → 日间 iOS 浅色
            if (c == NIGHT_BG_PRIMARY) return 0xFFFFFFFF;        // ios_bg
            if (c == NIGHT_BG_SECONDARY) return 0xFFF2F2F7;     // ios_bg_grouped
            if (c == NIGHT_DIVIDER) return 0xFFE5E5EA;          // ios_separator
        }
        return c;
    }

    private static int mapTextColor(int c, boolean isNight) {
        if (isNight) {
            // 日间 iOS 浅色文字 → 夜间 iOS 深色文字
            if (c == 0xFF1D1D1F) return NIGHT_TEXT_PRIMARY;      // ios_text_primary
            if (c == 0xFF8E8E93) return NIGHT_TEXT_SECONDARY;    // ios_text_secondary
            if (c == 0xFFC7C7CC) return NIGHT_TEXT_TERTIARY;     // ios_text_tertiary
            // 兼容旧色值
            if (c == 0xFF333333) return NIGHT_TEXT_PRIMARY;
            if (c == 0xFF666666) return NIGHT_TEXT_SECONDARY;
            if (c == 0xFF999999) return NIGHT_TEXT_TERTIARY;
        } else {
            // 夜间 iOS 深色文字 → 日间 iOS 浅色文字
            if (c == NIGHT_TEXT_PRIMARY) return 0xFF1D1D1F;      // ios_text_primary
            if (c == NIGHT_TEXT_SECONDARY) return 0xFF8E8E93;   // ios_text_secondary
            if (c == NIGHT_TEXT_TERTIARY) return 0xFFC7C7CC;    // ios_text_tertiary
        }
        return c;
    }

    /** 夜间模式切换时，对当前已显示的浮窗视图树重新着色，实现实时跟随。 */
    private void themeShowingPopups() {
        themeIfShowing(chapterPopupWindow);
        themeIfShowing(moreMenuPopupWindow);
        themeIfShowing(settingsPopupWindow);
        themeIfShowing(moreSettingsPopupWindow);
        themeIfShowing(bgColorsPopupWindow);
        themeIfShowing(fontsPopupWindow);
    }

    private void themeIfShowing(PopupWindow pw) {
        if (pw != null && pw.isShowing()) themeViewTree(pw.getContentView(), isNightMode);
    }

    @SuppressLint("InflateParams")
    private void showSettingsDialog() {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_reading_settings, null);
        initSettingsPanel(popupView);
        themeViewTree(popupView, isNightMode);
        settingsPopupWindow = new PopupWindow(popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
        settingsPopupWindow.setAnimationStyle(R.style.BottomSlideAnimation);
        settingsPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        settingsPopupWindow.showAtLocation(layoutBottomNav, Gravity.BOTTOM, 0, 0);
        dimBackground(true);
        mainHandler.removeCallbacks(hideNavRunnable);
        settingsPopupWindow.setOnDismissListener(() -> { dimBackground(false); resetAutoHideTimer(); });
    }

    @SuppressLint("InflateParams")
    private void showMoreMenu() {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_more_menu, null);

        popupView.findViewById(R.id.tv_menu_detail).setOnClickListener(v -> {
            moreMenuPopupWindow.dismiss();
            startActivity(new Intent(this, BookDetailActivity.class).putExtra("book", currentBook));
        });

        popupView.findViewById(R.id.tv_menu_bookmark).setOnClickListener(v -> {
            moreMenuPopupWindow.dismiss();
            addBookmark();
        });

        popupView.findViewById(R.id.tv_menu_share).setOnClickListener(v -> {
            moreMenuPopupWindow.dismiss();
            shareBook();
        });
        themeViewTree(popupView, isNightMode);
        int width = (int) (120 * getResources().getDisplayMetrics().density);
        moreMenuPopupWindow = new PopupWindow(popupView,
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                true);
        moreMenuPopupWindow.setAnimationStyle(android.R.style.Animation_Dialog);
        moreMenuPopupWindow.showAsDropDown(findViewById(R.id.iv_more), 0, 10);
    }

    private void addBookmark() {
        if (currentBook == null) return;
        
        // ✅ 修正：本地书籍使用本地存储，网络书籍使用服务器
        if (isLocalBook) {
            addLocalBookmark();
        } else {
            addNetworkBookmark();
        }
    }
    
    /**
     * 添加本地书籍的书签（使用 SharedPreferences 存储）
     */
    private void addLocalBookmark() {
        String chapterTitle = chapterList.get(currentChapterIndex).getTitle();
        
        // ✅ 修正：从 WebView 获取当前页的内容作为预览
        final String[] contentPreview = {""};
        
        if (isWebViewReady) {
            webView.evaluateJavascript("getCurrentPageText()", value -> {
                contentPreview[0] = processJsPageText(value);
                saveBookmark(chapterTitle, contentPreview[0]);
            });
        } else {
            // 如果 WebView 未就绪，使用章节内容的前100字作为备选
            String fullContent = chapterContents.get(currentChapterIndex);
            if (fullContent != null && !fullContent.isEmpty()) {
                contentPreview[0] = fullContent.length() > 100 ? fullContent.substring(0, 100) + "..." : fullContent;
            }
            saveBookmark(chapterTitle, contentPreview[0]);
        }
    }
    
    /**
     * 保存书签到 SharedPreferences
     */
    private void saveBookmark(String chapterTitle, String contentPreview) {
        // 外站书：用复合 key 命名 SP；本地/服务器书：只有 bookId>0 才保存
        long bid = safeBookId();
        String spName;
        if (isExternalBook) {
            String st = currentBook != null && currentBook.getSourceType() != null ? currentBook.getSourceType() : "";
            String su = currentBook != null && currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "";
            spName = "local_bookmarks_ext_" + st + "_" + su;
        } else if (bid > 0) {
            spName = "local_bookmarks_" + bid;
        } else {
            Toast.makeText(this, "暂不可用：当前书籍没有有效书签空间", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences sp = getSharedPreferences(spName, MODE_PRIVATE);
        int count = sp.getInt("bookmark_count", 0);
        
        // 创建新书签
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt("bookmark_count", count + 1);
        editor.putInt("bookmark_chapterIndex_" + count, currentChapterIndex);
        editor.putString("bookmark_chapterTitle_" + count, chapterTitle);
        editor.putInt("bookmark_page_" + count, currentPageInChapter);
        editor.putString("bookmark_preview_" + count, contentPreview);
        editor.putLong("bookmark_time_" + count, System.currentTimeMillis());
        editor.apply();
        
        Toast.makeText(this, "书签已添加", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 添加网络书籍的书签（使用服务器 API）
     */
    private void addNetworkBookmark() {
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        if (userId == 0) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }

        String chapterTitle = chapterList.get(currentChapterIndex).getTitle();
        
        // ✅ 修正：从 WebView 获取当前页的内容作为预览
        final String[] contentPreview = {""};
        
        if (isWebViewReady) {
            webView.evaluateJavascript("getCurrentPageText()", value -> {
                contentPreview[0] = processJsPageText(value);
                saveNetworkBookmark(userId, chapterTitle, contentPreview[0]);
            });
        } else {
            // 如果 WebView 未就绪，使用章节内容的前100字作为备选
            String fullContent = chapterContents.get(currentChapterIndex);
            if (fullContent != null && !fullContent.isEmpty()) {
                contentPreview[0] = fullContent.length() > 100 ? fullContent.substring(0, 100) + "..." : fullContent;
            }
            saveNetworkBookmark(userId, chapterTitle, contentPreview[0]);
        }
    }
    
    /**
     * 保存网络书籍的书签到服务器
     */
    private void saveNetworkBookmark(long userId, String chapterTitle, String contentPreview) {
        Bookmark bookmark = new Bookmark();
        bookmark.setUserId(userId);
        bookmark.setBookId(currentBook.getId());
        bookmark.setChapterIndex(currentChapterIndex);
        bookmark.setChapterTitle(chapterTitle);
        bookmark.setScrollPosition(currentPageInChapter);   // 使用 WebView 回调更新的页码
        bookmark.setPreviewText(contentPreview);
        bookmark.setBookName(currentBook.getBookName());
        // 从 chapterList 中拿到章节的主键 id，确保后端外键约束不会失败
        if (chapterList != null && currentChapterIndex < chapterList.size()) {
            long chId = chapterList.get(currentChapterIndex).getId();
            bookmark.setChapterId(chId);
        }

        android.util.Log.d("ReadActivity", "saveNetworkBookmark: userId=" + userId
                + ", bookId=" + currentBook.getId()
                + ", chapterIndex=" + currentChapterIndex
                + ", chapterTitle=" + chapterTitle
                + ", chapterId=" + bookmark.getChapterId());

        RetrofitClient.getApiService().addBookmark(bookmark).enqueue(new Callback<ApiResponse<Bookmark>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<Bookmark>> call, @NonNull Response<ApiResponse<Bookmark>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    Toast.makeText(ReadActivity.this, "书签已添加", Toast.LENGTH_SHORT).show();
                } else {
                    String errMsg = "添加失败";
                    try {
                        if (response.body() != null && response.body().getMessage() != null) {
                            errMsg = response.body().getMessage();
                        } else if (response.errorBody() != null) {
                            errMsg = response.errorBody().string();
                        }
                    } catch (Exception ignored) {
                    }
                    android.util.Log.e("ReadActivity", "saveNetworkBookmark failed: code=" + response.code() + ", msg=" + errMsg);
                    Toast.makeText(ReadActivity.this, errMsg, Toast.LENGTH_SHORT).show();
                }
            }
            @Override
            public void onFailure(@NonNull Call<ApiResponse<Bookmark>> call, @NonNull Throwable t) {
                android.util.Log.e("ReadActivity", "saveNetworkBookmark network error", t);
                Toast.makeText(ReadActivity.this, "网络错误：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void shareBook() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT,
                "我正在阅读《" + currentBook.getBookName() + "》，非常精彩！");
        startActivity(Intent.createChooser(shareIntent, "分享书籍"));
    }


    @SuppressLint("SetTextI18n")
    private void initSettingsPanel(View view) {
        // 亮度调节
        SeekBar seekBarBrightness = view.findViewById(R.id.seekbar_brightness);
        TextView tvBrightnessValue = view.findViewById(R.id.tv_brightness_value);
        ImageView ivFollowSystem = view.findViewById(R.id.iv_follow_system);

        seekBarBrightness.setProgress(currentBrightness);
        tvBrightnessValue.setText((currentBrightness * 100 / 255) + "%");
        updateFollowSystemIcon(ivFollowSystem, followSystemBrightness);

        seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentBrightness = progress;
                tvBrightnessValue.setText((progress * 100 / 255) + "%");
                if (!followSystemBrightness) {
                    setScreenBrightness(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        ivFollowSystem.setOnClickListener(v -> {
            followSystemBrightness = !followSystemBrightness;
            seekBarBrightness.setEnabled(!followSystemBrightness);
            setScreenBrightness(followSystemBrightness ? -1 : currentBrightness);
            updateFollowSystemIcon(ivFollowSystem, followSystemBrightness);
            saveReadingPreferences();
        });

        // 字号调节
        TextView tvFontMinus = view.findViewById(R.id.tv_font_minus);
        TextView tvFontPlus = view.findViewById(R.id.tv_font_plus);
        TextView tvFontSizeDisplay = view.findViewById(R.id.tv_font_size_display);

        tvFontSizeDisplay.setText(String.valueOf((int) currentFontSize));
        tvFontMinus.setOnClickListener(v -> {
            if (currentFontSize > 14) { currentFontSize -= 2; applyFontSizeChange(tvFontSizeDisplay); }
        });
        tvFontPlus.setOnClickListener(v -> {
            if (currentFontSize < 72) { currentFontSize += 2; applyFontSizeChange(tvFontSizeDisplay); }
        });

        // 背景颜色选择（主面板只显示4个常用色）
        int mainColorCount = 4;
        View[] bgSwatches = new View[mainColorCount];
        View[] bgRings = new View[mainColorCount];
        for (int i = 0; i < mainColorCount; i++) {
            int swatchId = getResources().getIdentifier("bg_color_" + i, "id", getPackageName());
            int ringId = getResources().getIdentifier("bg_color_ring_" + i, "id", getPackageName());
            bgSwatches[i] = view.findViewById(swatchId);
            bgRings[i] = view.findViewById(ringId);

            // 设置圆形背景色
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.parseColor(BG_COLORS[i]));
            bgSwatches[i].setBackground(circle);

            // 点击事件
            final int idx = i;
            bgSwatches[i].setOnClickListener(v -> {
                currentBgColor = idx;
                applyBackgroundColorToWebView(idx);
                updateBgColorHighlight(bgSwatches, bgRings);
                saveReadingPreferences();
            });
        }
        // 初始高亮当前选中颜色（如果在主面板范围内）
        updateBgColorHighlight(bgSwatches, bgRings);

        // "更多 >"按钮
        view.findViewById(R.id.tv_bg_more).setOnClickListener(v -> showBgColorsDialog());

        // "切换字体 >"按钮
        view.findViewById(R.id.tv_switch_font).setOnClickListener(v -> showFontsDialog());

        // 翻页动画模式选择
        TextView tvPageCover = view.findViewById(R.id.tv_page_cover);
        TextView tvPageSimulation = view.findViewById(R.id.tv_page_simulation);
        TextView tvPageTranslate = view.findViewById(R.id.tv_page_translate);
        TextView tvPageUpDown = view.findViewById(R.id.tv_page_updown);
        TextView tvPageFade = view.findViewById(R.id.tv_page_fade);

        TextView[] pageButtons = {tvPageCover, tvPageSimulation, tvPageTranslate, tvPageUpDown, tvPageFade};
        String[] pageModes = {"cover", "simulation", "slide", "updown", "fade"};

        updatePageTurnButtonHighlight(pageButtons, pageModes);

        for (int i = 0; i < pageButtons.length; i++) {
            final String mode = pageModes[i];
            pageButtons[i].setOnClickListener(v -> {
                pageTurnMode = mode;
                updatePageTurnButtonHighlight(pageButtons, pageModes);
                if (isWebViewReady) {
                    webView.evaluateJavascript("setPageTurnMode('" + mode + "')", null);
                }
                saveReadingPreferences();
            });
        }

        // 自动翻页
        SwitchCompat switchAutoPage = view.findViewById(R.id.switch_auto_page);
        switchAutoPage.setChecked(autoPageEnabled);
        switchAutoPage.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoPageEnabled = isChecked;
            if (isChecked) {
                startAutoPage();
            } else {
                stopAutoPage();
            }
            saveReadingPreferences();
        });

        view.findViewById(R.id.layout_auto_page).setOnClickListener(v -> switchAutoPage.setChecked(!switchAutoPage.isChecked()));

        view.findViewById(R.id.tv_more_settings).setOnClickListener(v -> showMoreSettingsDialog());
    }

    private void updatePageTurnButtonHighlight(TextView[] buttons, String[] modes) {
        for (int i = 0; i < buttons.length; i++) {
            if (modes[i].equals(pageTurnMode)) {
                buttons[i].setTextColor(Color.parseColor("#007AFF"));
                buttons[i].setBackground(getDrawable(R.drawable.bg_search_radius));
            } else {
                buttons[i].setTextColor(Color.parseColor("#8E8E93"));
                buttons[i].setBackground(null);
            }
        }
    }

    /**
     * 更新背景颜色选中高亮
     */
    private void updateBgColorHighlight(View[] swatches, View[] rings) {
        for (int i = 0; i < swatches.length; i++) {
            if (i == currentBgColor) {
                rings[i].setVisibility(View.VISIBLE);
                swatches[i].setScaleX(1.08f);
                swatches[i].setScaleY(1.08f);
            } else {
                rings[i].setVisibility(View.GONE);
                swatches[i].setScaleX(1.0f);
                swatches[i].setScaleY(1.0f);
            }
        }
    }

    /**
     * 显示更多背景弹窗（纯色 + 纹理）
     */
    @SuppressLint({"InflateParams", "SetTextI18n"})
    private void showBgColorsDialog() {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_bg_colors, null);
        LinearLayout solidContainer = popupView.findViewById(R.id.container_solid_colors);
        LinearLayout texContainer = popupView.findViewById(R.id.container_textures);

        float density = getResources().getDisplayMetrics().density;
        int swatchSize = (int) (44 * density);
        int ringSize = (int) (50 * density);
        int frameSize = (int) (52 * density);
        int marginEnd = (int) (8 * density);

        // ===== 构建纯色网格 =====
        int colsPerRow = 5;
        LinearLayout currentRow = null;
        for (int i = 0; i < BG_COLORS.length; i++) {
            if (i % colsPerRow == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = (int) (8 * density);
                solidContainer.addView(currentRow, rowParams);
            }
            FrameLayout frame = new FrameLayout(this);
            frame.setLayoutParams(new LinearLayout.LayoutParams(frameSize, frameSize));
            ((LinearLayout.LayoutParams) frame.getLayoutParams()).setMargins(0, 0, marginEnd, 0);

            // 色块
            View swatch = new View(this);
            FrameLayout.LayoutParams swatchLp = new FrameLayout.LayoutParams(swatchSize, swatchSize);
            swatchLp.gravity = android.view.Gravity.CENTER;
            swatch.setLayoutParams(swatchLp);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.parseColor(BG_COLORS[i]));
            if (BG_COLORS[i].equals("#FFFFFF")) circle.setStroke(2, Color.parseColor("#CCCCCC"));
            swatch.setBackground(circle);

            // 选中环
            View ring = new View(this);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
            ringLp.gravity = android.view.Gravity.CENTER;
            ring.setLayoutParams(ringLp);
            ring.setBackgroundResource(R.drawable.bg_color_ring);
            ring.setVisibility(i == currentBgColor ? View.VISIBLE : View.GONE);

            frame.addView(swatch);
            frame.addView(ring);

            // 点击选择
            final int idx = i;
            swatch.setOnClickListener(v -> {
                currentBgColor = idx;
                applyBackgroundColorToWebView(idx);
                saveReadingPreferences();
                refreshPopupSelection(solidContainer, texContainer);
            });
            currentRow.addView(frame);
        }

        // ===== 构建纹理网格 =====
        LinearLayout texRow = null;
        for (int i = 0; i < BG_TEXTURE_FILES.length; i++) {
            if (i % colsPerRow == 0) {
                texRow = new LinearLayout(this);
                texRow.setOrientation(LinearLayout.HORIZONTAL);
                texRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = (int) (8 * density);
                texContainer.addView(texRow, rowParams);
            }
            FrameLayout frame = new FrameLayout(this);
            frame.setLayoutParams(new LinearLayout.LayoutParams(frameSize, frameSize));
            ((LinearLayout.LayoutParams) frame.getLayoutParams()).setMargins(0, 0, marginEnd, 0);

            // 纹理预览图（从assets加载bitmap，裁为圆形）
            ImageView swatch = new ImageView(this);
            FrameLayout.LayoutParams swatchLp = new FrameLayout.LayoutParams(swatchSize, swatchSize);
            swatchLp.gravity = android.view.Gravity.CENTER;
            swatch.setLayoutParams(swatchLp);
            swatch.setScaleType(ImageView.ScaleType.CENTER_CROP);
            swatch.setContentDescription(BG_TEXTURE_LABELS[i]);
            try {
                InputStream is = getAssets().open("backgrounds/" + BG_TEXTURE_FILES[i]);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, swatchSize, swatchSize, true);
                RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(getResources(), scaled);
                drawable.setCircular(true);
                swatch.setImageDrawable(drawable);
            } catch (Exception e) {
                // 加载失败时用预览色兜底
                GradientDrawable fallback = new GradientDrawable();
                fallback.setShape(GradientDrawable.OVAL);
                fallback.setColor(BG_TEXTURE_PREVIEW_COLORS[i]);
                swatch.setBackground(fallback);
            }

            // 选中环
            View ring = new View(this);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
            ringLp.gravity = android.view.Gravity.CENTER;
            ring.setLayoutParams(ringLp);
            ring.setBackgroundResource(R.drawable.bg_color_ring);
            int texModeIdx = 10 + i;
            ring.setVisibility(texModeIdx == currentBgColor ? View.VISIBLE : View.GONE);

            frame.addView(swatch);
            frame.addView(ring);

            // 点击选择纹理
            final int texIdx = texModeIdx;
            swatch.setOnClickListener(v -> {
                currentBgColor = texIdx;
                applyBackgroundColorToWebView(texIdx);
                saveReadingPreferences();
                refreshPopupSelection(solidContainer, texContainer);
            });
            texRow.addView(frame);
        }

        // 关闭和确定按钮
        popupView.findViewById(R.id.iv_bg_popup_close).setOnClickListener(v -> {
            if (bgColorsPopupWindow != null) bgColorsPopupWindow.dismiss();
        });
        popupView.findViewById(R.id.tv_bg_popup_done).setOnClickListener(v -> {
            if (bgColorsPopupWindow != null) bgColorsPopupWindow.dismiss();
        });

        // ✅ 背景色选择浮窗外壳跟随主题（色块本身为实际背景色，不在映射表内故不会被改）
        themeViewTree(popupView, isNightMode);

        bgColorsPopupWindow = new PopupWindow(popupView,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                true);
        bgColorsPopupWindow.setAnimationStyle(R.style.BottomSlideAnimation);
        bgColorsPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        bgColorsPopupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.BOTTOM, 0, 0);
        dimBackground(true);
        mainHandler.removeCallbacks(hideNavRunnable);
        bgColorsPopupWindow.setOnDismissListener(() -> {
            dimBackground(false);
            resetAutoHideTimer();
        });
    }

    /**
     * 刷新弹窗中所有色块的选中状态
     */
    private void refreshPopupSelection(LinearLayout solidContainer, LinearLayout texContainer) {
        // 纯色
        int solidIdx = 0;
        for (int r = 0; r < solidContainer.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) solidContainer.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                FrameLayout frame = (FrameLayout) row.getChildAt(c);
                View ring = frame.getChildAt(1);
                ring.setVisibility(solidIdx == currentBgColor ? View.VISIBLE : View.GONE);
                solidIdx++;
            }
        }
        // 纹理（索引从10开始）
        int texIdx = 0;
        for (int r = 0; r < texContainer.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) texContainer.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                FrameLayout frame = (FrameLayout) row.getChildAt(c);
                View ring = frame.getChildAt(1);
                ring.setVisibility((10 + texIdx) == currentBgColor ? View.VISIBLE : View.GONE);
                texIdx++;
            }
        }
    }

    /**
     * 显示字体选择弹窗
     */
    @SuppressLint({"InflateParams", "SetTextI18n"})
    private void showFontsDialog() {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_fonts, null);
        LinearLayout container = popupView.findViewById(R.id.container_fonts);

        float density = getResources().getDisplayMetrics().density;

        // 构建字体列表
        rebuildFontList(container);

        // 关闭按钮
        popupView.findViewById(R.id.iv_font_popup_close).setOnClickListener(v -> {
            if (fontsPopupWindow != null) fontsPopupWindow.dismiss();
        });

        themeViewTree(popupView, isNightMode);

        fontsPopupWindow = new PopupWindow(popupView,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                true);
        fontsPopupWindow.setAnimationStyle(R.style.BottomSlideAnimation);
        fontsPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        fontsPopupWindow.showAtLocation(findViewById(android.R.id.content), Gravity.BOTTOM, 0, 0);
        dimBackground(true);
        mainHandler.removeCallbacks(hideNavRunnable);
        fontsPopupWindow.setOnDismissListener(() -> {
            dimBackground(false);
            resetAutoHideTimer();
        });
    }

    /**
     * 从后端获取字体列表
     */
    private void fetchFontsFromBackend(LinearLayout container) {
        RetrofitClient.getApiService().getFonts().enqueue(new retrofit2.Callback<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>>() {
            @Override
            public void onResponse(@NonNull retrofit2.Call<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> call,
                                   @NonNull retrofit2.Response<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getData() != null) {
                    backendFonts = response.body().getData();
                    runOnUiThread(() -> {
                        // 移除加载提示
                        View loading = container.findViewWithTag("tv_loading");
                        if (loading != null) container.removeView(loading);
                        // 渲染字体列表
                        float density = getResources().getDisplayMetrics().density;
                        buildBackendFontRows(container, density);
                    });
                } else {
                    runOnUiThread(() -> {
                        View loading = container.findViewWithTag("tv_loading");
                        if (loading instanceof TextView) {
                            ((TextView) loading).setText("暂无在线字体");
                        }
                    });
                }
            }

            @Override
            public void onFailure(@NonNull retrofit2.Call<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> call,
                                  @NonNull Throwable t) {
                runOnUiThread(() -> {
                    View loading = container.findViewWithTag("tv_loading");
                    if (loading instanceof TextView) {
                        ((TextView) loading).setText("网络异常，请稍后再试");
                    }
                });
            }
        });
    }

    /**
     * 构建后端字体行
     */
    private void buildBackendFontRows(LinearLayout container, float density) {
        for (com.example.myapplication.bean.FontItem font : backendFonts) {
            boolean downloaded = downloadedFonts.contains(font.getCssName());
            boolean isCurrent = font.getCssName().equals(currentFontFamily);
            View row = buildFontRow(font.getName(), font.getCssName(),
                    font.getFileSize() != null ? font.getFileSize() : "",
                    !downloaded, isCurrent, density);
            container.addView(row);

            if (!downloaded) {
                TextView btnDl = row.findViewWithTag("btn_download");
                if (btnDl != null) {
                    btnDl.setOnClickListener(v -> {
                        btnDl.setText("下载中...");
                        btnDl.setTextColor(Color.parseColor("#8E8E93"));
                        btnDl.setEnabled(false);
                        downloadFontFromUrl(font.getFileUrl(), font.getCssName(), font.getName(),
                                btnDl, container);
                    });
                }
            } else {
                final String cssName = font.getCssName();
                row.setOnClickListener(v -> {
                    currentFontFamily = cssName;
                    applyFontFamilyToWebView();
                    saveReadingPreferences();
                    rebuildFontList(container);
                });
            }
        }
    }

    private void addSectionHeader(LinearLayout container, String title, float density) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(13);
        header.setTextColor(Color.parseColor("#8E8E93"));
        header.setPadding((int)(8 * density), (int)(12 * density), 0, (int)(4 * density));
        container.addView(header);
    }

    @SuppressLint("SetTextI18n")
    private View buildFontRow(String displayName, String subInfo, String sizeInfo,
                              boolean downloadable, boolean isCurrent, float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padH = (int) (12 * density);
        int padV = (int) (10 * density);
        row.setPadding(padH, padV, padH, padV);

        // 左侧：字体名称 + 副信息
        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView tvName = new TextView(this);
        tvName.setText(displayName);
        tvName.setTextSize(16);
        tvName.setTextColor(Color.parseColor("#1D1D1F"));
        textBlock.addView(tvName);

        TextView tvSub = new TextView(this);
        tvSub.setText(subInfo);
        tvSub.setTextSize(12);
        tvSub.setTextColor(Color.parseColor("#8E8E93"));
        textBlock.addView(tvSub);

        row.addView(textBlock);

        // 右侧：大小标签 + 操作按钮
        LinearLayout rightBlock = new LinearLayout(this);
        rightBlock.setOrientation(LinearLayout.HORIZONTAL);
        rightBlock.setGravity(android.view.Gravity.CENTER_VERTICAL);

        if (sizeInfo != null && !sizeInfo.isEmpty()) {
            TextView tvSize = new TextView(this);
            tvSize.setText(sizeInfo);
            tvSize.setTextSize(12);
            tvSize.setTextColor(Color.parseColor("#AAAAAA"));
            int marginR = (int) (8 * density);
            LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sizeLp.setMarginEnd(marginR);
            tvSize.setLayoutParams(sizeLp);
            rightBlock.addView(tvSize);
        }

        if (downloadable) {
            // 下载按钮
            TextView btnDl = new TextView(this);
            btnDl.setText("下载");
            btnDl.setTextSize(14);
            btnDl.setTextColor(Color.parseColor("#FF6600"));
            btnDl.setPadding((int)(12 * density), (int)(4 * density),
                    (int)(12 * density), (int)(4 * density));
            btnDl.setTag("btn_download");
            rightBlock.addView(btnDl);
        } else {
            // 选中勾选
            TextView tvCheck = new TextView(this);
            tvCheck.setText(isCurrent ? "✓" : "");
            tvCheck.setTextSize(18);
            tvCheck.setTextColor(Color.parseColor("#007AFF"));
            tvCheck.setTag("check_mark");
            rightBlock.addView(tvCheck);
        }

        row.addView(rightBlock);
        row.setTag("font_row_" + subInfo);

        // wrapper + 分隔线
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setClickable(true);
        wrapper.setFocusable(true);
        android.util.TypedValue typedVal = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedVal, true);
        wrapper.setBackgroundResource(typedVal.resourceId);
        wrapper.addView(row);
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#E5E5EA"));
        wrapper.addView(divider);

        return wrapper;
    }

    /**
     * 刷新字体选择弹窗 — 重建整个列表以保证状态一致
     */
    private void refreshFontSelection(LinearLayout container) {
        rebuildFontList(container);
    }

    /**
     * 构建/重建字体列表
     */
    private void rebuildFontList(LinearLayout container) {
        container.removeAllViews();
        float density = getResources().getDisplayMetrics().density;

        // ===== 本地字体 =====
        addSectionHeader(container, "本地", density);

        // 默认字体
        View defaultRow = buildFontRow("默认字体", "sans-serif",
                "内置", false, FONT_DEFAULT_CSS.equals(currentFontFamily), density);
        container.addView(defaultRow);
        defaultRow.setOnClickListener(v -> {
            currentFontFamily = FONT_DEFAULT_CSS;
            applyFontFamilyToWebView();
            saveReadingPreferences();
            rebuildFontList(container);
        });

        // 系统字体
        View systemRow = buildFontRow("系统字体", "跟随手机",
                "内置", false, FONT_SYSTEM_CSS.equals(currentFontFamily), density);
        container.addView(systemRow);
        systemRow.setOnClickListener(v -> {
            currentFontFamily = FONT_SYSTEM_CSS;
            applyFontFamilyToWebView();
            saveReadingPreferences();
            rebuildFontList(container);
        });

        // ===== 在线字体 =====
        addSectionHeader(container, "在线字体", density);

        if (backendFonts.isEmpty()) {
            TextView tvLoading = new TextView(this);
            tvLoading.setText("加载中...");
            tvLoading.setTextSize(14);
            tvLoading.setTextColor(Color.parseColor("#8E8E93"));
            tvLoading.setPadding((int)(12 * density), (int)(16 * density), 0, (int)(16 * density));
            tvLoading.setTag("tv_loading");
            container.addView(tvLoading);
            fetchFontsFromBackend(container);
        } else {
            buildBackendFontRows(container, density);
        }
    }

    /**
     * 从 URL 下载字体文件到内部存储
     */
    private void downloadFontFromUrl(String fileUrl, String cssName, String displayName,
                                     TextView btnRef, LinearLayout container) {
        // 对路径中的中文/特殊字符做 URL 编码
        String encodedPath;
        try {
            java.net.URI uri = new java.net.URI("http", "47.99.126.75:8080", fileUrl, null);
            encodedPath = uri.toASCIIString();
        } catch (Exception e) {
            encodedPath = RetrofitClient.getFullImageUrl(fileUrl);
        }
        final String fullUrl = encodedPath;
        new Thread(() -> {
            try {
                java.io.File fontsDir = new java.io.File(getFilesDir(), "fonts");
                if (!fontsDir.exists()) fontsDir.mkdirs();
                java.io.File outFile = new java.io.File(fontsDir, cssName + ".ttf");

                // 获取 token 并添加到请求头（修复401错误）
                SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
                String token = sp.getString("token", "");
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(fullUrl)
                        .header("Authorization", "Bearer " + token)
                        .build();
                okhttp3.Response response = client.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    throw new java.io.IOException("下载失败: " + response.code());
                }
                java.io.InputStream is = response.body().byteStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.close();
                is.close();

                runOnUiThread(() -> {
                    downloadedFonts.add(cssName);
                    saveReadingPreferences();
                    Toast.makeText(this, displayName + " 下载完成", Toast.LENGTH_SHORT).show();
                    // 自动切换到新下载的字体
                    currentFontFamily = cssName;
                    applyFontFamilyToWebView();
                    saveReadingPreferences();
                    refreshFontSelection(container);
                    // 更新按钮状态
                    if (btnRef != null) {
                        btnRef.setText("✓");
                        btnRef.setTextColor(Color.parseColor("#007AFF"));
                        btnRef.setEnabled(false);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    if (btnRef != null) {
                        btnRef.setText("重试");
                        btnRef.setTextColor(Color.parseColor("#FF6600"));
                        btnRef.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    /**
     * 显示更多设置弹窗（页眉页脚等）
     */
    @SuppressLint("InflateParams")
    private void showMoreSettingsDialog() {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_more_settings, null);

        SwitchCompat switchHeaderFooter = popupView.findViewById(R.id.switch_header_footer);
        switchHeaderFooter.setChecked(showHeaderFooter);

        LinearLayout layoutHeaderFooterSize = popupView.findViewById(R.id.layout_header_footer_size);
        layoutHeaderFooterSize.setAlpha(showHeaderFooter ? 1.0f : 0.4f);
        layoutHeaderFooterSize.setEnabled(showHeaderFooter);

        // 电量时间开关（次级设置，跟随页眉页脚）
        LinearLayout layoutBatteryTime = popupView.findViewById(R.id.layout_battery_time);
        SwitchCompat switchBatteryTime = popupView.findViewById(R.id.switch_battery_time);
        switchBatteryTime.setChecked(showBatteryTime);
        layoutBatteryTime.setAlpha(showHeaderFooter ? 1.0f : 0.4f);
        layoutBatteryTime.setEnabled(showHeaderFooter);

        switchHeaderFooter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showHeaderFooter = isChecked;
            layoutHeaderFooterSize.setAlpha(isChecked ? 1.0f : 0.4f);
            layoutHeaderFooterSize.setEnabled(isChecked);
            layoutBatteryTime.setAlpha(isChecked ? 1.0f : 0.4f);
            layoutBatteryTime.setEnabled(isChecked);
            // 调用 JavaScript 接口更新页眉页脚显示
            webView.evaluateJavascript("setShowHeaderFooter(" + isChecked + ")", null);
            if (!isChecked) {
                webView.evaluateJavascript("setShowBatteryTime(false)", null);
                timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
            } else if (showBatteryTime) {
                webView.evaluateJavascript("setShowBatteryTime(true)", null);
                updateBatteryAndTime();
                timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
                timeUpdateHandler.postDelayed(timeUpdateRunnable, 60000);
            }
            saveReadingPreferences();
        });

        switchBatteryTime.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showBatteryTime = isChecked;
            webView.evaluateJavascript("setShowBatteryTime(" + isChecked + ")", null);
            if (isChecked) {
                updateBatteryAndTime();
                timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
                timeUpdateHandler.postDelayed(timeUpdateRunnable, 60000);
            } else {
                timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
            }
            saveReadingPreferences();
        });

        layoutBatteryTime.setOnClickListener(v -> switchBatteryTime.setChecked(!switchBatteryTime.isChecked()));

        // 音量键翻页开关
        SwitchCompat switchVolumeKeyPage = popupView.findViewById(R.id.switch_volume_key_page);
        switchVolumeKeyPage.setChecked(volumeKeyPageTurn);
        switchVolumeKeyPage.setOnCheckedChangeListener((buttonView, isChecked) -> {
            volumeKeyPageTurn = isChecked;
            saveReadingPreferences();
        });

        // 滑动翻页开关
        SwitchCompat switchSwipePage = popupView.findViewById(R.id.switch_swipe_page);
        switchSwipePage.setChecked(swipePageTurn);
        switchSwipePage.setOnCheckedChangeListener((buttonView, isChecked) -> {
            swipePageTurn = isChecked;
            saveReadingPreferences();
        });

        TextView tvHeaderFooterMinus = popupView.findViewById(R.id.tv_header_footer_minus);
        TextView tvHeaderFooterPlus = popupView.findViewById(R.id.tv_header_footer_plus);
        TextView tvHeaderFooterSizeDisplay = popupView.findViewById(R.id.tv_header_footer_size_display);

        tvHeaderFooterSizeDisplay.setText(String.valueOf((int) headerFooterFontSize));

        tvHeaderFooterMinus.setOnClickListener(v -> {
            if (headerFooterFontSize > 8) { // 最小值改为8
                headerFooterFontSize -= 1;
                tvHeaderFooterSizeDisplay.setText(String.valueOf((int) headerFooterFontSize));
                // 调用 JavaScript 接口更新页眉页脚字体大小
                webView.evaluateJavascript("setHeaderFooterFontSize(" + (int)headerFooterFontSize + ")", null);
                saveReadingPreferences();
            }
        });

        tvHeaderFooterPlus.setOnClickListener(v -> {
            if (headerFooterFontSize < 24) { // 最大值改为24
                headerFooterFontSize += 1;
                tvHeaderFooterSizeDisplay.setText(String.valueOf((int) headerFooterFontSize));
                // 调用 JavaScript 接口更新页眉页脚字体大小
                webView.evaluateJavascript("setHeaderFooterFontSize(" + (int)headerFooterFontSize + ")", null);
                saveReadingPreferences();
            }
        });

        popupView.findViewById(R.id.tv_more_settings_done).setOnClickListener(v -> {
            if (moreSettingsPopupWindow != null) moreSettingsPopupWindow.dismiss();
        });

        themeViewTree(popupView, isNightMode);

        moreSettingsPopupWindow = new PopupWindow(popupView,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                true);
        moreSettingsPopupWindow.setAnimationStyle(R.style.BottomSlideAnimation);
        moreSettingsPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        moreSettingsPopupWindow.showAtLocation(layoutBottomNav, Gravity.BOTTOM, 0, 0);

        dimBackground(true);
        mainHandler.removeCallbacks(hideNavRunnable);
        moreSettingsPopupWindow.setOnDismissListener(() -> {
            dimBackground(false);
            resetAutoHideTimer();
        });
    }

    private void applyFontSizeChange(TextView display) {
        display.setText(String.valueOf((int) currentFontSize));
        webView.evaluateJavascript("setFontSize(" + currentFontSize + ")", null);
        saveReadingPreferences();
    }

    //更新跟随系统亮度图标的颜色
    private void updateFollowSystemIcon(ImageView iv, boolean follow) {
        iv.setColorFilter(follow ? Color.parseColor("#FF9500") : Color.parseColor("#8E8E93"));
    }

    // 屏幕亮度控制
    private void setScreenBrightness(int brightness) {
        Window window = getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        if (brightness == -1) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            lp.screenBrightness = brightness / 255f;
        }
        window.setAttributes(lp);
    }

    // 背景变暗
    private void dimBackground(boolean dim) {
        Window window = getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        if (dim) {
            lp.alpha = 0.7f;
        } else {
            lp.alpha = 1.0f;
        }
        window.setAttributes(lp);
    }


    // ==================== 自动翻页 ====================
    private void startAutoPage() {
        stopAutoPage();
        autoPageRunnable = () -> {
            webView.evaluateJavascript("nextPage()", null);
            autoPageHandler.postDelayed(autoPageRunnable, 8000);
        };
        autoPageHandler.postDelayed(autoPageRunnable, 8000);
    }
    private void stopAutoPage() {
        if (autoPageRunnable != null) autoPageHandler.removeCallbacks(autoPageRunnable);
    }

    // ==================== 缓存与本地书 ====================
    private void cacheChapterListOnly(long bookId, List<ChapterDto> list) {
        SharedPreferences sp = getSharedPreferences("chapter_list_" + bookId, MODE_PRIVATE);
        sp.edit().putInt("count", list.size()).apply();
        // ✅ 修正：统一使用循环索引 i 缓存标题和ID，不再使用 sortOrder
        for (int i = 0; i < list.size(); i++) {
            ChapterDto dto = list.get(i);
            cacheChapterTitle(bookId, i, dto.getTitle());
            // ✅ 新增：缓存章节ID
            cacheChapterId(bookId, i, dto.getId());
        }
    }
    
    // ✅ 新增：章节ID缓存方法
    private void cacheChapterId(long bookId, int index, long chapterId) {
        getSharedPreferences("chapter_meta_" + bookId, MODE_PRIVATE)
            .edit().putLong("id_" + index, chapterId).apply();
    }
    
    private long getChapterIdCache(long bookId, int index) {
        return getSharedPreferences("chapter_meta_" + bookId, MODE_PRIVATE)
            .getLong("id_" + index, -1);  // 默认返回-1表示无缓存
    }
    private void cacheChapterTitle(long bookId, int index, String title) {
        getSharedPreferences("chapter_meta_" + bookId, MODE_PRIVATE).edit().putString("title_" + index, title).apply();
    }
    private void cacheChapterContent(long bookId, int index, String content) {
        getSharedPreferences("chapter_content_" + bookId, MODE_PRIVATE).edit().putString("content_" + index, content).apply();
    }
    private String getChapterContentCache(long bookId, int index) {
        return getSharedPreferences("chapter_content_" + bookId, MODE_PRIVATE).getString("content_" + index, null);
    }
    private String getChapterTitleCache(long bookId, int index) {
        return getSharedPreferences("chapter_meta_" + bookId, MODE_PRIVATE).getString("title_" + index, null);
    }
    private void mergeServerData(List<ChapterDto> serverList) {
        android.util.Log.d("ReadActivity", "mergeServerData: BEFORE clear, currentChapterIndex=" + currentChapterIndex);
        
        chapterList.clear();
        chapterContents.clear();
        for (int i = 0; i < serverList.size(); i++) {
            ChapterDto dto = serverList.get(i);
            Chapter ch = new Chapter();
            // 统一使用循环索引 i，不再使用 sortOrder，避免前后端索引不一致
            ch.setIndex(i);
            ch.setId(dto.getId());
            ch.setTitle(dto.getTitle());
            chapterList.add(ch);
            // 使用循环索引 i 作为缓存键，与 chapterList 索引保持一致
            // ✅ 外站书不走 chapter_content_ SP 缓存（bookId=0 时 key 全都重名）；
            //    仅服务器/本地书 bookId>0 才走 SP 缓存
            long bid = safeBookId();
            if (isExternalBook || bid <= 0) {
                chapterContents.add("");
            } else {
                String cached = getChapterContentCache(bid, i);
                chapterContents.add(cached != null ? cached : "");
            }
            
            // ✅ 新增：调试日志，检查章节ID
            if (i < 5 || dto.getId() <= 0) {
                android.util.Log.d("ReadActivity", "mergeServerData: index=" + i + ", id=" + dto.getId() + ", title=" + dto.getTitle());
            }
        }
        
        android.util.Log.d("ReadActivity", "mergeServerData: AFTER rebuild, currentChapterIndex=" + currentChapterIndex);
        
        // ✅ 新增：统计无效ID的章节数量
        int invalidIdCount = 0;
        for (Chapter ch : chapterList) {
            if (ch.getId() <= 0) {
                invalidIdCount++;
            }
        }
        if (invalidIdCount > 0) {
            android.util.Log.w("ReadActivity", "Found " + invalidIdCount + " chapters with invalid ID (<=0) out of " + chapterList.size() + " total chapters");
        }
    }
    private void loadLocalBookChapters(long bookId, int targetChapter) {
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);

        android.util.Log.d("ReadActivity", "loadLocalBookChapters: bookId=" + bookId + ", targetChapter=" + targetChapter + ", totalBooks=" + count);

        chapterList.clear();
        chapterContents.clear();
        boolean found = false;
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0) == bookId) {
                found = true;
                int chCount = sp.getInt("chapter_count_" + i, 0);
                android.util.Log.d("ReadActivity", "Found local book at index " + i + ", chapterCount=" + chCount);

                for (int j = 0; j < chCount; j++) {
                    Chapter ch = new Chapter();
                    ch.setIndex(j);
                    ch.setTitle(sp.getString("chapter_title_" + i + "_" + j, "第" + (j + 1) + "章"));
                    chapterList.add(ch);
                    String content = sp.getString("chapter_content_" + i + "_" + j, "");
                    chapterContents.add(content);

                    if (j < 3) {
                        android.util.Log.d("ReadActivity", "Chapter " + j + " content length: " + content.length());
                    }
                }
                break;
            }
        }

        android.util.Log.d("ReadActivity", "Loaded " + chapterList.size() + " chapters" +
                (found ? "" : " [WARNING: book not found in local_books]") +
                ", first chapter content length: " +
                (!chapterContents.isEmpty() && chapterContents.get(0) != null ? chapterContents.get(0).length() : 0));

        // ✅ 关键修复：用 targetChapter 更新 currentChapterIndex，保证 onPageFinished 渲染到正确章节
        if (!chapterList.isEmpty() && targetChapter >= 0 && targetChapter < chapterList.size()) {
            currentChapterIndex = targetChapter;
        }
    }

    private void uploadReadTime(long minutes) {
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        long bid = safeBookId();
        if (userId > 0 && currentBook != null && bid > 0) {
            ReadTimeRequest request = new ReadTimeRequest(userId, bid, minutes);
            RetrofitClient.getApiService().saveReadTime(request).enqueue(new Callback<ApiResponse<Void>>() {
                @Override public void onResponse(@NonNull Call<ApiResponse<Void>> call, @NonNull Response<ApiResponse<Void>> response) {}
                @Override public void onFailure(@NonNull Call<ApiResponse<Void>> call, @NonNull Throwable t) {}
            });
        }
    }

    private String escapeJavaScript(String s) {
        return JSONObject.quote(s);
    }

    /** 处理 JS getCurrentPageText 返回值，提取纯文本并截取预览长度 */
    private String processJsPageText(String value) {
        if (value == null || value.equals("null") || value.isEmpty()) return "";
        String text = value;
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
            text = text.replace("\\n", "\n")
                       .replace("\\t", "\t")
                       .replace("\\\"", "\"")
                       .replace("\\\\", "\\");
        }
        if (text.length() > 100) {
            text = text.substring(0, 100) + "...";
        }
        return text;
    }

    // ==================== 生命周期 ====================
    @Override protected void onPause() {
        super.onPause();
        if (readStartTime > 0) {
            long minutes = (System.currentTimeMillis() - readStartTime) / 60000;
            if (minutes > 0) {
                long total = readTimePref.getLong("total_read_time", 0) + minutes;
                readTimePref.edit().putLong("total_read_time", total).apply();
                uploadReadTime(minutes);
            }
            readStartTime = 0;
        }
        saveReadingRecord();
        mainHandler.removeCallbacks(hideNavRunnable);
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
    }
    @Override protected void onResume() {
        super.onResume();
        readStartTime = System.currentTimeMillis();
        mainHandler.postDelayed(hideNavRunnable, 3000);
        if (showBatteryTime && showHeaderFooter) {
            updateBatteryAndTime();
            timeUpdateHandler.postDelayed(timeUpdateRunnable, 60000);
        }
    }

    /** 上一个全局未捕获异常处理器（进入 Activity 时保存，销毁时恢复） */
    private Thread.UncaughtExceptionHandler savedUncaughtHandler;

    @Override protected void onDestroy() {
        // 恢复原始未捕获异常处理器，避免污染其它 Activity
        try {
            if (savedUncaughtHandler != null) Thread.currentThread().setUncaughtExceptionHandler(savedUncaughtHandler);
        } catch (Throwable ignored) {}
        super.onDestroy();
        stopAutoPage();
        mainHandler.removeCallbacks(hideNavRunnable);
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
        dismissPopups();
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (chapterPopupWindow != null && chapterPopupWindow.isShowing()) {
            chapterPopupWindow.dismiss();
        } else if (moreMenuPopupWindow != null && moreMenuPopupWindow.isShowing()) {
            moreMenuPopupWindow.dismiss();
        } else {
            saveReadingRecord();
            finish();
            return;
        }
        super.onBackPressed();
    }

    public static class Chapter {
        private int index;
        private long id;
        private String title;
        private String content;
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    private static class PopupPagerAdapter extends FragmentStateAdapter {
        private final List<Fragment> fragments;
        PopupPagerAdapter(@NonNull FragmentActivity activity, List<Fragment> fragments) {
            super(activity);
            // 再做一次防御拷贝，避免原列表被异步清空导致 getItemCount/createFragment 不同步
            this.fragments = (fragments == null ? new ArrayList<>() : new ArrayList<>(fragments));
        }
        @NonNull @Override public Fragment createFragment(int position) {
            try {
                if (fragments == null || position < 0 || position >= fragments.size()) {
                    return new BlankFragment();
                }
                Fragment f = fragments.get(position);
                return f != null ? f : new BlankFragment();
            } catch (Throwable t) {
                android.util.Log.e("ReadActivity", "PopupPagerAdapter.createFragment 异常", t);
                return new BlankFragment();
            }
        }
        @Override public int getItemCount() { return fragments == null ? 0 : fragments.size(); }
    }

    /** 占位 Fragment：任何目录/书签弹窗子页创建异常时退回为一个空白页，代替崩回详情页 */
    public static class BlankFragment extends Fragment {
        @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
                @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            android.view.View v = new android.view.View(inflater.getContext());
            v.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return v;
        }
    }
}