package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.LoginHelper;
import com.example.myapplication.utils.ExternalPrefs;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.VolumeDeriver;
import android.content.Context;
import android.content.ContextWrapper;
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
import android.widget.ScrollView;
import android.widget.AbsListView;
import android.widget.TextView;

import android.widget.LinearLayout;
import android.app.AlertDialog;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewGroup;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.animation.AccelerateDecelerateInterpolator;

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
import com.example.myapplication.bean.MajorChapter;
import com.example.myapplication.bean.ReadingProgress;
import com.example.myapplication.fragment.PopupBookmarkFragment;
import com.example.myapplication.fragment.PopupChapterFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.example.myapplication.api.RetrofitClient;
import com.bumptech.glide.Glide;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ReadTimeRequest;
import com.example.myapplication.utils.Hint;
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
    private com.example.myapplication.view.LiquidSlider liquidSlider;

    // ========== 弹窗 ==========
    private PopupWindow chapterPopupWindow, moreMenuPopupWindow, settingsPopupWindow, moreSettingsPopupWindow, bgColorsPopupWindow, fontsPopupWindow;
    // 设置面板内容视图：用于窗内裁剪滑动，使其从「目录/夜间/设置」行里滑出/收回
    private View settingsPanelView;

    // ========== 阅读设置 ==========
    private int currentBrightness = 128;
    private boolean followSystemBrightness = false;
    private boolean autoPageEnabled = false;
    private int autoPageInterval = 5000; // 自动翻页间隔(ms)，可配置：慢10s/中5s/快3s
    private int currentBgColor = 0;  // 0-9:纯色（0-3保持旧值兼容）
    // 页面「实际显示」的背景基准色：纯色即自身，纹理取其预览底色，夜间模式为 #1A1A1A。
    // 导航栏/浮窗的派生配色以它为输入，保证与 WebView 里看到的一致。
    private int effectiveBgBase = 0xFFFFFFFF;
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
    private String currentFontDisplay = "默认字体";    // 当前字体展示名（选择时记录，用于设置按钮文案）
    private java.util.Set<String> downloadedFonts = new java.util.HashSet<>(); // 已下载的字体cssName
    private java.util.Set<String> downloadingFonts = new java.util.HashSet<>(); // 正在下载的字体cssName
    private Book currentBook;
    private int currentChapterIndex = 0;
    /** 显式跳章标记：从详情页目录等入口点击具体章节时为 true，
     *  此时无论有无阅读记录都直接进入点击的章节；仅默认入口（继续阅读）才恢复进度 */
    private boolean explicitChapterJump = false;
    // ========== 在途跨章跳转 ==========
    // 翻到章节边界、而目标章节正文还没加载完时，旧实现会先把 currentChapterIndex 推进到目标章，
    // 期间用户再翻一次就会基于「已推进的索引」再 +1，加载完成后直接跳到 N+2（跳两章）。
    // 改为：不推进 currentChapterIndex，只记录 pendingChapterIndex，等正文到位后再统一渲染。
    private int pendingChapterIndex = -1;          // -1 表示没有在途跳转
    private boolean pendingGoLastPage = false;     // true=跳过去后定位到该章最后一页（向前翻章）
    private int pendingChapterPage = -1;           // >0=跳过去后定位到该页（服务端进度同步用）；-1=不指定
    private long pendingChapterTs = 0L;            // 发起时间，超时后允许重新发起（避免异常时永久卡死）
    private static final long PENDING_CHAPTER_TIMEOUT = 10000L;
    private long lastChapterJumpTs = 0L;            // ⚠️ 防御：一次手势只会触发一次 onChapterEnd/onChapterStart；
                                                    //    仿真翻页下 document 级滑动与 turn.js boundary 曾各自触发一次导致连跳两章，
                                                    //    这里按时间窗去重（见 onChapterEnd/onChapterStart）
    /** 服务端进度比本地新时，允许越过 positionRestored 守卫重渲染一次（同一本书只保留最新那条记录） */
    private boolean forceRestorePosition = false;
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
    /** 阅读器内「章节加载中」动画（与全站共用 {@link com.example.myapplication.widget.LoadingView}） */
    private View loadingChapter;
    /** 本站章节加载失败覆盖层（插画 + 文案 + 「再试一次」按钮，透明底适配各种阅读底色） */
    private android.widget.LinearLayout layoutLoadFail;
    private TextView tvLoadFailMsg;

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
    /**
     * 本地书的分卷结构（来自导入时持久化的紧凑卷表）；空列表表示该书无分卷信息，
     * 目录浮窗按平铺章节列表显示。仅在 {@link #loadLocalBookChapters} 中填充。
     * 注意：服务器书（含后台导入 EPUB）的分卷不存这里，而是在打开目录时按最新
     * chapterList 实时推导（见 {@link #currentVolumes()}），以保证卷区间与下标严格一致。
     */
    private final List<LocalBookParser.VolumeInfo> localVolumes = new ArrayList<>();
    /**
     * 服务器书的分卷表（后台导入 EPUB 时写入 major_chapter）。仅在服务器书加载链路拉取；
     * 供 {@link #currentVolumes()} 结合章节 sortKey 推导分卷。为空则目录保持平铺。
     */
    private final List<MajorChapter> serverMajorChapters = new ArrayList<>();

    // 本地书元信息（目录浮窗顶部信息头的兜底来源）：仅在 loadLocalBookChapters 里填充。
    // 从阅读记录/详情页进来时 currentBook 可能缺书名/作者/封面，这里用 local_books 记录补齐。
    private String localBookName = "";
    private String localBookAuthor = "";
    private String localBookCover = "";
    private String localBookCoverPath = "";
    private final List<String> chapterContents = new ArrayList<>();
    /** 保留样式的 HTML（与 chapterContents 一一对应；为空表示用纯文本渲染） */
    private final List<String> chapterHtmlContents = new ArrayList<>();
    /**
     * 本地书章节正文的占位符：打开书时只建章节列表，正文真正翻到该章时才按需读取
     * （见 {@link #reloadLocalChapterContent(int)}）。
     * 必须含「加载中」以命中 {@link #isChapterContentPending(String)}，避免多余的全表扫描。
     */
    private static final String LOCAL_CHAPTER_PLACEHOLDER = "【章节加载中...】";
    /** 本地书已加载正文的保留半径：只留当前章前后各 N 章，避免长时间阅读把全书正文堆进内存 */
    private static final int LOCAL_CONTENT_KEEP_RADIUS = 2;

    /**
     * 章节正文是否仍处于「占位 / 未加载」状态（而不是真实正文）。
     *
     * <p>★ 不要再用 {@code content.length() < 50} 这类「长度启发式」来判断内容是否有效：
     * 卷名页、分节页、题图页这类合法章节的正文可以只有几个字。例如 EasyPub 生成的
     * 《诸天尽头》里 chapter1.html 的正文只有「第一卷 我讨厌杀手」16 个字符，
     * 旧实现把它当成"未加载"→ 既不渲染、又反复触发加载，最后只弹一个
     * 「章节内容缺失」，用户表现为「一翻页就卡住，什么都读不到」。
     *
     * <p>这里统一按「占位符 / 空串」判定；真正空白的章节仍会被判为未就绪，
     * 从而保留原有的「跳过空章」行为。
     */
    private static boolean isChapterContentPending(String content) {
        if (content == null) return true;
        String t = content.trim();
        if (t.isEmpty()) return true;
        // 【章节加载中...】/【正在加载...】等占位文本
        return t.contains("加载中") || t.contains("正在加载");
    }
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ========== 导航栏自动隐藏 ==========
    private final Runnable hideNavRunnable = this::hideNavigation;
    private int statusBarHeight = 0;  // 状态栏高度
    private int bottomNavHeight = 0;  // 底部导航栏高度缓存（避免每次滑动都重新 measure）
    private static final long NAV_ANIM_MS = 250L;  // 上下导航栏滑入/滑出时长

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
    /** 当某个设置弹窗打开时挂起自动翻页，关闭后恢复（仅当开启时） */
    private boolean autoPageSuspended = false;
    /** Activity 是否处于前台（onResume 后为 true，onPause 后为 false） */
    private boolean activityResumed = false;

    // ========== 阅读时间 ==========
    private long readStartTime;

    // ========== JS 回调更新的当前进度 ==========
    private int currentPageInChapter = 1;
    private int totalPagesInChapter = 1;

    // ✅ 复用池修复：恢复阅读位置时记住「目标页码」，正文异步到达后重渲染时沿用，
    //    避免 fetchChapterContent.onResponse 用默认 page=1 重渲染把恢复位置冲掉（导致「恢复总落第 1 页」）。
    private int restoreTargetPage = 1;

    // ✅ 排版缓存：把「章节分页结果」按 layout signature 落盘，下次进入本书同章同排版直接复用，避免每次重排
    private java.io.File layoutCacheDir;
    private java.util.concurrent.ExecutorService layoutExecutor;
    private static final int LAYOUT_CACHE_MAX_FILES = 300;

    private boolean positionRestored = false;
    private boolean isWebViewReady  = false;
    // ✅ 复用池：标记「WebView 就绪后的统一处理」是否已执行，避免首次加载与复用命中两条路径重复触发
    private boolean readerReadyHandled = false;
    // ✅ 复用池：等 WebView 布局就绪（拿到正确宽度）再恢复位置的监听。
    //    池化 WebView 跨 Activity 复用，该监听必须随 Activity 生命周期清理，否则 observer 随 detach 失效后崩溃。
    private android.view.ViewTreeObserver.OnGlobalLayoutListener layoutReadyListener;
    private boolean hasRestoredFromLocal = false;
    private boolean chapterRestoredFromCache = false;  // ✅ 新增：标记是否从缓存恢复了章节
    // ✅ 渲染代次：每次外部调用 renderChapterContent 都生成一个新代次。
    //    WebView 未就绪时会以 50ms 重试渲染，若期间已有更新的渲染请求（如服务器正文到达后渲染目标章），
    //    较早的延迟重试通过比对代次被判定为「过期」并直接丢弃，避免用旧 index（如占位章的 0）
    //    覆盖 currentChapterIndex，导致用户被拉回第一章。
    private int renderGeneration = 0;

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
                                Hint.showLong(ReadActivity.this, "阅读器异常已暂停，请返回重试");
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

        // 记一次「今天读过」，推进「我的」页的连续阅读天数（账号级，幂等，失败不影响阅读）
        reportReadingCheckIn();

        currentBook = (Book) getIntent().getSerializableExtra("book");
        if (currentBook == null) {
            Hint.showLong(this, "书籍信息缺失，请重新进入");
            finish();
            return;
        }

        currentChapterIndex = getIntent().getIntExtra("chapterIndex", 0);
        explicitChapterJump = getIntent().getBooleanExtra("chapterExplicit", false);
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
        // ✅ 进门即展示「章节加载中」遮罩：覆盖 WebView 就绪 + 仿真翻页首屏渲染（同步 toDataURL 多张大图）的空窗，
        //    避免进入阅读器时（尤其仿真模式）出现白屏而非加载动画。内容真正渲染到位后由
        //    renderChapterContentInternal 的回调撤下；占位/异常分支也各自有收口。
        setChapterLoading(true);
        loadReadingPreferencesNoApply();
        // 当前若选中在线字体，后台预拉取字体列表，确保「切换字体」按钮在未打开弹窗时也显示中文名
        preloadBackendFontsIfNeeded();
        // 进入页面时先把「实际显示」的背景基准对齐已保存的偏好（夜间模式 / 纯色 / 纹理），
        // 再由 applyChromeTheme() 派生导航栏与浮窗配色，避免首帧按默认白色算错。
        effectiveBgBase = isNightMode ? Color.parseColor("#1A1A1A")
                : resolveBaseForMode(currentBgColor);
        applyChromeTheme();

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
        // ✅ 复用 WebView 池：把池化实例动态插入内容容器，避免每次开书都 inflate / 重建 WebView + 重载 reader.html。
        //    容器（webview_container）在布局里是空的；池化 WebView 若已挂在其他父容器则先摘离再重新挂接。
        ViewGroup webContainer = findViewById(R.id.webview_container);
        webView = ReaderWebViewPool.obtain(getApplicationContext());
        if (webView.getParent() != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
        // 插到容器最底层（index 0），让布局里的左右点击区 / 加载层 / 重试层等仍处于其上方
        webContainer.addView(webView, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        liquidSlider = findViewById(R.id.seekbar_progress);
        tvProgressText = findViewById(R.id.tv_progress_text);
        tvToolbarTitle.setText(currentBook.getBookName());

        // ✅ 排版缓存目录 + 单线程写入池；启动时清理上次残留的 .tmp 半文件
        layoutCacheDir = new java.io.File(getCacheDir(), "reader_layout");
        if (!layoutCacheDir.exists()) layoutCacheDir.mkdirs();
        if (layoutCacheDir.exists()) {
            java.io.File[] tmpFiles = layoutCacheDir.listFiles((d, n) -> n.endsWith(".tmp"));
            if (tmpFiles != null) for (java.io.File t : tmpFiles) t.delete();
        }
        layoutExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

        // 章节加载中动画 + 本站/外站统一的加载失败层
        loadingChapter = findViewById(R.id.loading_chapter);
        layoutLoadFail = findViewById(R.id.layout_load_fail);
        tvLoadFailMsg = findViewById(R.id.tv_load_fail_msg);
        findViewById(R.id.btn_load_fail_retry).setOnClickListener(v -> {
            if (layoutLoadFail != null) layoutLoadFail.setVisibility(View.GONE);
            if (isExternalBook) loadExternalChapterContent(currentChapterIndex);
            else fetchChapterContent(currentChapterIndex);
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
        // ✅ 每次 attach 都重新绑定到当前 Activity：上一个 Activity 的 WebViewClient / JsBridge
        //    随替换而失效，避免复用池 WebView 时回调串台到已销毁的 Activity。
        // ✅ 复用池 WebView：必须使用同一个 JsBridge 实例注入 JS。
        //    若每次 new JsBridge()，reader.html 的 JS 上下文仍持有第一次注入的旧 bridge 代理，
        //    后续 Activity 的页面变化/章节边界等回调会串台到已销毁的旧 Activity，导致翻页进度无法保存。
        webView.addJavascriptInterface(JsBridge.getInstance(this), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isWebViewReady = true;
                ReaderWebViewPool.markLoaded();
                onReaderWebViewReady();
            }
        });

        // ✅ 复用池 WebView：reader.html 已加载则立即就绪（首开由上面的 onPageFinished 触发）。
        //    不再每次开书 loadDataWithBaseURL 重载 reader.html —— 这是冷启动优化的核心。
        if (ReaderWebViewPool.isLoaded()) {
            isWebViewReady = true;
            // 等 WebView 完成布局、拿到正确宽度后再恢复，否则 0 宽会导致分页 / 缓存 key 出错。
            // ✅ 复用的 WebView 跨 Activity：必须先把可能残留的旧监听清掉（避免叠加），
            //    并在移除时做 isAlive() 保护，否则 observer 随 detach 失效后会抛 IllegalStateException 崩溃。
            if (webView.getWidth() > 0) {
                onReaderWebViewReady();
            } else {
                // 清理上次（上一个 Activity）可能残留、未触发的监听，杜绝跨 Activity 叠加
                if (layoutReadyListener != null) {
                    android.view.ViewTreeObserver prev = webView.getViewTreeObserver();
                    if (prev.isAlive()) prev.removeOnGlobalLayoutListener(layoutReadyListener);
                    layoutReadyListener = null;
                }
                layoutReadyListener = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (webView.getWidth() > 0) {
                            // ✅ 用「当前」observer 并在 alive 时移除，避免捕获的旧 observer 失效抛异常
                            android.view.ViewTreeObserver obs = webView.getViewTreeObserver();
                            if (obs.isAlive()) obs.removeOnGlobalLayoutListener(layoutReadyListener);
                            layoutReadyListener = null;
                            onReaderWebViewReady();
                        }
                    }
                };
                webView.getViewTreeObserver().addOnGlobalLayoutListener(layoutReadyListener);
            }
        }
    }

    /**
     * WebView 就绪后的统一处理：补发页眉页脚 / 电量时间设置，并在「章节列表已加载且尚未恢复位置」时
     * 应用设置 + 恢复阅读进度。首次加载由 onPageFinished 触发，复用池命中时由 setupWebView 直接触发。
     */
    private void onReaderWebViewReady() {
        if (readerReadyHandled) return;
        readerReadyHandled = true;

        // ✅ 把原本注入到 reader.html 的初始化脚本改为 evaluate 执行（复用池后不再重载 HTML），
        //    保证字号 / 字体 / 背景 / 页眉页脚 / 纹理 / 仿真占位等全局变量与首帧样式正确。
        webView.evaluateJavascript(buildSettingsInitScript(), null);

        // ✅ 关键修复：WebView 一就绪就无条件补发一次页眉页脚 / 电量时间设置。
        // 退出阅读器重进时 onResume 先于 onPageFinished 执行，那时 updateBatteryAndTime()
        // 会因 isWebViewReady=false 被跳过；而下面恢复位置的分支只在「首次恢复」时应用设置，
        // 走本地缓存恢复（chapterRestoredFromCache=true）时完全不会走到，
        // 结果就是页脚只有页码、没有电量时间，要等 60 秒的定时刷新才出现。
        applyHeaderFooterSettings();

        // ✅ WebView 加载完成后，如果章节列表已加载但尚未恢复位置，则恢复
        mainHandler.post(() -> {
            if (!positionRestored && !chapterRestoredFromCache && !chapterList.isEmpty()) {
                android.util.Log.d("ReadActivity", "WebView ready, restoring position from server...");
                // 先应用所有设置（字体、背景等），再恢复位置，避免默认设置闪烁
                webView.evaluateJavascript("beginSettingsBatch()", null);
                applySettingsToWebView_inner();
                webView.evaluateJavascript("finishSettingsBatch()", null);
                webView.evaluateJavascript("setPageTurnMode('" + pageTurnMode + "')", null);
                restoreReadingPosition(currentChapterIndex);
                updateChapterButtons();
                // ✅ 若此前开启过自动翻页，阅读器就绪后自动恢复
                if (autoPageEnabled) {
                    autoPageSuspended = false;
                    startAutoPage();
                }
            }
        });
    }

    public static class JsBridge {
        private static JsBridge sInstance;
        private java.lang.ref.WeakReference<ReadActivity> activityRef;

        public static synchronized JsBridge getInstance() {
            if (sInstance == null) {
                sInstance = new JsBridge();
            }
            return sInstance;
        }

        public static synchronized JsBridge getInstance(ReadActivity activity) {
            if (sInstance == null) {
                sInstance = new JsBridge();
            }
            if (activity != null) {
                sInstance.attach(activity);
            }
            return sInstance;
        }

        /** 若当前 bridge 仍指向该 Activity，则主动断开，避免已销毁 Activity 继续接收回调 */
        public static void detach(ReadActivity activity) {
            if (sInstance != null && sInstance.activityRef != null) {
                ReadActivity a = sInstance.activityRef.get();
                if (a == activity) sInstance.activityRef.clear();
            }
        }

        private void attach(ReadActivity activity) {
            this.activityRef = new java.lang.ref.WeakReference<>(activity);
        }

        private ReadActivity getActivity() {
            ReadActivity a = activityRef != null ? activityRef.get() : null;
            return (a != null && !a.isDestroyed()) ? a : null;
        }

        /**
         * 前端点击屏幕中间 1/3 时切换导航栏显隐（仿真模式下 Android 手势层不处理点击）
         */
        @JavascriptInterface
        @SuppressWarnings("unused")
        public void toggleNavigation() {
            ReadActivity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                try {
                    activity.resetAutoHideTimer();
                    activity.toggleNavigation();
                } catch (Throwable t) {
                    android.util.Log.w("ReadActivity", "JsBridge.toggleNavigation 异常", t);
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onPageChanged(int page, int totalPages) {
            ReadActivity activity = getActivity();
            if (activity == null) {
                android.util.Log.w("ReadActivity", "onPageChanged 丢弃：当前无可存活的 Activity（疑似串台到已销毁实例）");
                return;
            }
            android.util.Log.d("ReadActivity", "onPageChanged 收到：page=" + page + "/" + totalPages);
            activity.runOnUiThread(() -> {
                try {
                    activity.currentPageInChapter = page;
                    activity.totalPagesInChapter = totalPages;
                    activity.updateProgressDisplay();
                } catch (Throwable t) {
                    android.util.Log.w("ReadActivity", "onPageChanged 异常", t);
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onChapterEnd() {
            ReadActivity activity = getActivity();
            if (activity == null) return;
            long now = System.currentTimeMillis();
            // ⚠️ 连击去重：同一手势的重复回调（仿真翻页下 document 级滑动分支与 turn.js boundary
            // 各可能触发一次）在 700ms 内直接丢弃，避免一次滑动连跳两章。
            if (now - activity.lastChapterJumpTs < 700) return;
            activity.lastChapterJumpTs = now;
            activity.runOnUiThread(() -> {
                try {
                int nextIndex = activity.currentChapterIndex + 1;

                // ✅ 简化：直接检查是否还有下一章
                if (nextIndex >= activity.chapterList.size()) {
                    Hint.show(activity, "已经是最后一章，自动翻页已关闭");
                    // ✅ 到达书末：停止自动翻页并同步开关状态
                    activity.stopAutoPage();
                    activity.autoPageEnabled = false;
                    activity.autoPageSuspended = false;
                    activity.syncAutoPageSwitchUI();
                    activity.saveReadingPreferences();
                    return;
                }

                // ✅ 已有指向同一章的在途跳转 → 直接忽略，避免连跳多章
                if (activity.shouldIgnoreChapterJump(nextIndex)) {
                    return;
                }

                // ✅ 防御：chapterContents 越界
                if (nextIndex >= activity.chapterContents.size()) {
                    while (activity.chapterContents.size() < activity.chapterList.size()) activity.chapterContents.add("【正在加载...】");
                }
                // 检查下一章的状态
                String content = activity.chapterContents.get(nextIndex);

                if (activity.isChapterContentPending(content)) {
                    // 下一章正在加载或尚未加载，触发加载。
                    // ✅ 关键：这里【不要】推进 currentChapterIndex —— 推进了的话，
                    // 用户在此期间再翻一次页就会基于新索引再 +1，加载完成后会直接跳两章。
                    activity.setPendingChapterJump(nextIndex, false);
                    activity.fetchChapterContent(nextIndex);
                    return;
                }

                // 检查内容是否有效（"有效" = 不是占位/空串，见 isChapterContentPending）
                boolean hasValidContent = !activity.isChapterContentPending(content);

                if (hasValidContent) {
                    // 有有效内容，直接跳转
                    activity.loadChapterContent(nextIndex);
                } else {
                    // 内容无效（占位或空章），尝试找下一个有效章节
                    int searchIndex = nextIndex;
                    while (searchIndex < activity.chapterList.size()) {
                        if (searchIndex >= activity.chapterContents.size()) break;
                        String searchContent = activity.chapterContents.get(searchIndex);
                        boolean isValid = !activity.isChapterContentPending(searchContent);
                        if (isValid) {
                            activity.loadChapterContent(searchIndex);
                            return;
                        }
                        searchIndex++;
                    }
                    // 没有找到有效章节，尝试加载下一个
                    activity.setPendingChapterJump(nextIndex, false);
                    activity.fetchChapterContent(nextIndex);
                }
                } catch (Throwable t) {
                    android.util.Log.e("ReadActivity", "JsBridge.onChapterEnd 崩溃", t);
                    Hint.show(activity, "翻到下一章失败");
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onChapterStart() {
            ReadActivity activity = getActivity();
            if (activity == null) return;
            long now = System.currentTimeMillis();
            // ⚠️ 连击去重：同上 onChapterEnd，避免一次手势重复触发导致连跳两章。
            if (now - activity.lastChapterJumpTs < 700) return;
            activity.lastChapterJumpTs = now;
            activity.runOnUiThread(() -> {
                try {
                int prevIndex = activity.currentChapterIndex - 1;

                // 检查是否还有上一章
                if (prevIndex < 0) {
                    // 确实是第一章
                    Hint.show(activity, "已经是第一章");
                    return;
                }

                // ✅ 已有指向同一章的在途跳转 → 直接忽略，避免连跳多章
                if (activity.shouldIgnoreChapterJump(prevIndex)) return;

                // ✅ 防御：chapterContents 越界
                if (prevIndex >= activity.chapterContents.size()) {
                    activity.setPendingChapterJump(prevIndex, true);
                    activity.fetchChapterContent(prevIndex);
                    return;
                }
                // 检查上一章的状态
                String content = activity.chapterContents.get(prevIndex);

                if (activity.isChapterContentPending(content)) {
                    // 上一章正在加载或尚未加载，触发加载（同样不推进 currentChapterIndex）
                    activity.setPendingChapterJump(prevIndex, true);
                    activity.fetchChapterContent(prevIndex);
                    return;
                }

                // 检查内容是否有效（"有效" = 不是占位/空串，见 isChapterContentPending）
                boolean hasValidContent = !activity.isChapterContentPending(content);

                if (hasValidContent) {
                    // 有有效内容，直接跳转到最后一页
                    activity.loadChapterContentToLastPage(prevIndex);
                } else {
                    // 内容无效（占位或空章），尝试加载或跳过
                    if (content.trim().isEmpty() || content.equals("【本章节内容暂缺】")) {
                        // 如果是明确标记为缺失的内容，尝试重新加载
                        activity.setPendingChapterJump(prevIndex, true);
                        activity.fetchChapterContent(prevIndex);
                    } else {
                        // 仍无法确定有效内容，尝试找上一个有效章节
                        int searchIndex = prevIndex - 1;
                        while (searchIndex >= 0 && searchIndex < activity.chapterContents.size()) {
                            String searchContent = activity.chapterContents.get(searchIndex);
                            boolean isValid = !activity.isChapterContentPending(searchContent);
                            if (isValid) {
                                activity.loadChapterContentToLastPage(searchIndex);
                                return;
                            }
                            searchIndex--;
                        }
                        // 没有找到有效章节，尝试加载上一个
                        activity.setPendingChapterJump(prevIndex, true);
                        activity.fetchChapterContent(prevIndex);
                    }
                }
                } catch (Throwable t) {
                    android.util.Log.e("ReadActivity", "JsBridge.onChapterStart 崩溃", t);
                    Hint.show(activity, "翻到上一章失败");
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void cacheChapterLayout(String key, String json) {
            ReadActivity activity = getActivity();
            if (activity == null) return;
            if (key == null || json == null || json.length() < 2) return;
            activity.writeLayoutCache(key, json);
        }
    }

    // ========== 排版缓存：把「章节分页结果」按 layout signature 落盘，下次进入同章同排版直接复用 ==========
    // layout signature 由「书籍 + 章节 + 字号 + 视口宽高 + 页眉页脚开关 + 字体 + 内容哈希」组成，
    // 任一变动（改字号 / 换字体 / 旋转 / 改内容）都会自然错开缓存 → 自动失效并重排。
    private String computeLayoutKey(int chIndex, String content) {
        long bid = safeBookId();
        String bookPart;
        if (bid > 0) {
            bookPart = "L" + bid;
        } else {
            String st = currentBook != null && currentBook.getSourceType() != null ? currentBook.getSourceType() : "";
            String su = currentBook != null && currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "";
            bookPart = "E" + Integer.toHexString((st + "|" + su).hashCode());
        }
        int w = (webView != null) ? webView.getWidth() : 0;
        int h = (webView != null) ? webView.getHeight() : 0;
        int fh = (currentFontFamily != null) ? currentFontFamily.hashCode() : 0;
        int chc = (content != null) ? content.hashCode() : 0;
        return bookPart + "_" + chIndex + "_" + ((int) currentFontSize) + "_" + w + "_" + h
                + "_" + (showHeaderFooter ? 1 : 0) + "_" + fh + "_" + chc;
    }

    private String readLayoutCache(String key) {
        try {
            java.io.File f = new java.io.File(layoutCacheDir, sha256hex(key) + ".json");
            if (!f.exists() || f.length() > 1536 * 1024) return null; // 超大文件不读，避免主线程卡顿
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            android.util.Log.w("ReadActivity", "readLayoutCache 失败: " + e.getMessage());
            return null;
        }
    }

    private void writeLayoutCache(String key, String json) {
        if (layoutExecutor == null) return;
        layoutExecutor.execute(() -> {
            try {
                String name = sha256hex(key) + ".json";
                java.io.File tmp = new java.io.File(layoutCacheDir, name + ".tmp");
                java.io.File dst = new java.io.File(layoutCacheDir, name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                fos.write(json.getBytes("UTF-8"));
                fos.close();
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) { // 极少数 rename 失败：直接原地写兜底
                    java.io.FileOutputStream fos2 = new java.io.FileOutputStream(dst);
                    fos2.write(json.getBytes("UTF-8"));
                    fos2.close();
                    tmp.delete();
                }
                trimLayoutCache();
            } catch (Exception e) {
                android.util.Log.w("ReadActivity", "writeLayoutCache 失败: " + e.getMessage());
            }
        });
    }

    private void trimLayoutCache() {
        try {
            java.io.File[] files = layoutCacheDir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null || files.length <= LAYOUT_CACHE_MAX_FILES) return;
            java.util.Arrays.sort(files, (a, b) -> java.lang.Long.compare(a.lastModified(), b.lastModified()));
            int remove = files.length - LAYOUT_CACHE_MAX_FILES;
            for (int i = 0; i < remove; i++) files[i].delete();
        } catch (Exception e) { /* ignore */ }
    }

    private static String sha256hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "k" + Integer.toHexString(s.hashCode());
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
        SharedPreferences pref = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
        // ✅ 目录点击（显式跳章）优先于本地记录：点哪章进哪章
        int savedIdx = explicitChapterJump
                ? currentChapterIndex
                : pref.getInt(recordKey + "_chapterIndex", currentChapterIndex);
        currentChapterIndex = Math.min(savedIdx, chapterList.size() - 1);
        // ✅ 读取保存的页码，用于恢复到具体页（显式跳章从第 1 页开始，不沿用其他章节的页码）
        int savedPage = explicitChapterJump ? 1 : pref.getInt(recordKey + "_page", 1);

        tvToolbarTitle.setText(currentBook.getTitle());
        updateChapterButtons();
        // 如果当前章节URL缺失，显示错误提示
        String[] currentPair = externalChapters[currentChapterIndex];
        String currentUrl = (currentPair != null && currentPair.length > 1) ? currentPair[1] : "";
        if (currentUrl == null || currentUrl.isEmpty()) {
            chapterContents.set(currentChapterIndex, "【章节链接缺失，请返回目录选择其他章节】");
            showLoadFail("章节链接缺失，无法加载");
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
            // ⚠️ 标题不要塞「加载中...」：它会被当成真正的章节标题，**同时画在页眉和正文的大标题上**
            //    （用户看到的「正文里还显示加载中…」就是它）。标题未知时留空，
            //    「正在加载」统一由整页加载动画表达，不要再往正文里塞字。
            //    这个临时章节 id=-1，真实章节列表一到就会整体重建，所以留空不会残留。
            tempChapter.setTitle("");
            chapterList.add(tempChapter);
            chapterContents.add("【正在加载章节列表...】");
            // ✅ 修复：占位渲染必须用真实目标章 currentChapterIndex，不能用硬编码 0，
            //    否则 WebView 就绪前的延迟重试会把 currentChapterIndex 覆盖回 0，用户停在第一章
            renderChapterContent(currentChapterIndex, tempChapter.getTitle(), "【正在加载章节列表...】");
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
            SharedPreferences extPref = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
            localCh = extPref.getInt(recordKey + "_chapterIndex", -1);
        } else if (localBookId > 0) {
            for (int i = 0; i < cnt; i++) {
                if (pref.getLong("record_bookId_" + i, 0) == localBookId) {
                    localCh = pref.getInt("record_chapterIndex_" + i, 0);
                    break;
                }
            }
        }
        // ✅ 目录点击（显式跳章）优先于阅读记录：点哪章进哪章，不再被"上次阅读位置"覆盖
        final int targetChapter = (!explicitChapterJump && localCh >= 0) ? localCh : currentChapterIndex;

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
            if (!isChapterContentPending(cachedContent)) {
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
                // ✅ 关键修复（复用池回归）：无本地内容缓存时也要记住「恢复目标页码」，
                //    否则 fetchChapterContent.onResponse 默认 page=1 会把恢复位置冲掉，
                //    导致「重新打开总落第 1 页」。与 loadChapterContentWithPage 保持一致。
                restoreTargetPage = savedPage;
                positionRestored = true;
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
            // ⚠️ 标题不要塞「加载中...」：它会被当成真正的章节标题，**同时画在页眉和正文的大标题上**
            //    （用户看到的「正文里还显示加载中…」就是它）。标题未知时留空，
            //    「正在加载」统一由整页加载动画表达，不要再往正文里塞字。
            //    这个临时章节 id=-1，真实章节列表一到就会整体重建，所以留空不会残留。
            tempChapter.setTitle("");
            chapterList.add(tempChapter);
            chapterContents.add("【正在加载章节列表...】");
            // ✅ 修复：占位渲染用真实目标章 targetChapter（而非硬编码 0），
            //    避免 WebView 就绪前的延迟重试覆盖 currentChapterIndex 导致首次进入落回第一章
            renderChapterContent(targetChapter, tempChapter.getTitle(), "【正在加载章节列表...】");
        }
        
        // ✅ 步骤2：异步请求服务器章节列表（后台更新）
        // 同时拉取分卷表（供阅读器目录浮窗分卷展示）；失败/无分卷时目录保持平铺
        loadServerMajorChapters(bookId);
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
                    
                    // 取服务器阅读进度。web 与 App 共用同一条进度记录，
                    // 谁的最后阅读时间更新就用谁的；已有本地记录也要拉，否则
                    // 在 web 端读到的新位置在 App 上永远看不到。
                    if (!explicitChapterJump && userId > 0 && bookId > 0) {
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
                        Hint.show(ReadActivity.this, "网络请求失败，使用本地缓存");
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
                ch.setSortKey(getChapterSortKeyCache(bookId, i));
                chapterList.add(ch);
                
                String content = getChapterContentCache(bookId, i);
                chapterContents.add(content != null ? content : "");
            }
        }
        
        // 同步还原分卷表（与上面章节来自同一份缓存快照，sortKey 与下标一致）；
        // 缓存里没有卷表时清空，退回平铺
        restoreMajorChaptersFromCache(bookId);
        
        return !chapterList.isEmpty();
    }
    
    /**
     * 从服务器获取阅读进度。
     *
     * <p>web 与 App 共用同一条进度记录，同一本书只保留「最后阅读退出」的那一条，
     * 判定依据是最后阅读时间：服务端 updatedAt 比本地记录时间新才采纳，否则保留本地
     * （本地可能是在离线时读的，比服务端还新）。
     *
     * <p>粒度：web 端不分页，它写入的页数据固定为「该章首页」（1）；App 端写入的是
     * 章节内精确页码。这里无需区分来源 —— 原样取服务端页码即可：
     * web 记录落在该章首页（只对应到章节），App 记录精确到页。
     */
    private void fetchServerProgress(long userId, long bookId) {
        RetrofitClient.getApiService().getProgress(userId, bookId).enqueue(new Callback<ApiResponse<ReadingProgress>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<ReadingProgress>> call, @NonNull Response<ApiResponse<ReadingProgress>> response) {
                if (!response.isSuccessful() || response.body() == null
                        || !response.body().isSuccess() || response.body().getData() == null) {
                    return;
                }
                ReadingProgress server = response.body().getData();
                int savedChapter = server.getChapterIndex();
                if (savedChapter < 0 || savedChapter >= chapterList.size()) return;

                // 按最后阅读时间取舍：服务端不比本地新就保持当前阅读位置，不打断用户
                long localTime = getLocalRecordTime(bookId);
                long serverTime = parseServerTime(server);
                // ✅ 复用池修复：同设备续读时以本地记录为上。服务端进度历史上一度长期存成第 1 页，
                //    即便本地 readTime 尚未被本次打开刷新、服务端时间戳更新，也应优先保留本地已恢复的位置，
                //    否则「打开书 → 本地恢复到第 N 页 → 服务端拉回第 1 页」会把用户弹回首页。
                //    仅当本地无任何阅读记录（换设备 / 首次打开）时才采纳服务端进度。
                if (localTime > 0) {
                    android.util.Log.d("ReadActivity", "Local reading record exists, keep local position, skip server adopt");
                    return;
                }
                if (serverTime > 0 && serverTime <= localTime) {
                    android.util.Log.d("ReadActivity", "Server progress is not newer, keep local position");
                    return;
                }
                // 页码缺失/非法时交给阅读器回落到该章第一页
                int page = server.getScrollPosition() > 0 ? server.getScrollPosition() : 1;
                android.util.Log.d("ReadActivity", "Adopt server progress: chapter=" + savedChapter
                        + ", page=" + page + ", serverTime=" + serverTime + ", localTime=" + localTime);
                adoptServerPosition(savedChapter, page);
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse<ReadingProgress>> call, @NonNull Throwable t) {
                android.util.Log.d("ReadActivity", "Failed to fetch server progress", t);
            }
        });
    }

    /** 采纳服务端位置：正文已在手上就直接重渲染，否则走「在途跳转」等正文到位后按页码渲染。 */
    private void adoptServerPosition(final int chapterIndex, final int page) {
        runOnUiThread(() -> {
            try {
                if (chapterIndex < 0 || chapterIndex >= chapterList.size()) return;
                String content = (chapterIndex < chapterContents.size()) ? chapterContents.get(chapterIndex) : null;
                if (!isChapterContentPending(content)) {
                    currentChapterIndex = chapterIndex;
                    forceRestorePosition = true;
                    loadChapterContentWithPage(chapterIndex, page);
                    positionRestored = true;
                    updateChapterButtons();
                } else {
                    // 正文还没到（换了章 / 还没拉过）：交给既有的在途跳转通道，
                    // 正文到位后按指定页码渲染
                    setPendingChapterJump(chapterIndex, false, page);
                    fetchChapterContent(chapterIndex);
                }
            } catch (Throwable t) {
                android.util.Log.e("ReadActivity", "adoptServerPosition 异常", t);
            }
        });
    }

    /** 本地阅读记录的「最后阅读时间」（毫秒）；没有记录返回 0。 */
    private long getLocalRecordTime(long bookId) {
        if (bookId <= 0) return 0;
        SharedPreferences pref = getSharedPreferences("reading_records", MODE_PRIVATE);
        int cnt = pref.getInt("record_count", 0);
        for (int i = 0; i < cnt; i++) {
            if (pref.getLong("record_bookId_" + i, 0) == bookId) {
                return pref.getLong("record_readTime_" + i, 0);
            }
        }
        return 0;
    }

    /**
     * 取服务端记录的「最后阅读时间」（毫秒）。
     *
     * <p>优先用后端下发的 updatedAtEpoch：它是绝对时间戳，与设备时区无关。
     * 只有老版本后端没有该字段时，才回退到按本机时区解析 updatedAt 字符串
     * （设备时区 ≠ 服务器时区时会有偏差，模拟器为 GMT 时最明显）。
     */
    private long parseServerTime(ReadingProgress p) {
        if (p == null) return 0;
        Long epoch = p.getUpdatedAtEpoch();
        if (epoch != null && epoch > 0) return epoch;
        return parseServerUpdatedAt(p.getUpdatedAt());
    }

    /**
     * 解析服务端 updatedAt（形如 2026-09-16T20:53:37.182799，服务器本地时间、无时区）。
     * 按本机时区解释后与本地记录时间戳比较；解析不出来返回 0（视为「时间未知」，
     * 由调用方决定不据此否决服务端记录）。
     *
     * <p>已被 {@link #parseServerTime(ReadingProgress)} 取代，仅在服务端未下发
     * updatedAtEpoch（老版本后端）时作为回退使用。
     */
    private long parseServerUpdatedAt(String iso) {
        if (iso == null || iso.trim().isEmpty()) return 0;
        try {
            String s = iso.trim().replace('T', ' ');
            int dot = s.indexOf('.');
            if (dot > 0) {
                // 截到毫秒：SimpleDateFormat 处理不了 6 位微秒
                s = s.substring(0, Math.min(s.length(), dot + 4));
            }
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US);
            java.util.Date d = fmt.parse(s);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            android.util.Log.w("ReadActivity", "解析服务端 updatedAt 失败: " + iso, e);
            return 0;
        }
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
            SharedPreferences pref = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
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
        // ✅ 非恢复渲染（跳章/翻章等）：目标页码回到首页，避免沿用上次恢复的目标页
        restoreTargetPage = 1;
        // 主动跳章（目录/进度条等）时取消在途的边界跨章跳转
        clearPendingChapterJump();

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

        boolean isPlaceholder = isChapterContentPending(content);

        if (isPlaceholder) {
            android.util.Log.d("ReadActivity", "Content is placeholder, fetching from server");
            // ✅ 修正：立即更新 currentChapterIndex，让用户看到章节切换
            currentChapterIndex = chapterIndex;
            updateChapterButtons();

            // 显示「章节加载中」动画（原生遮罩，不依赖 WebView 是否就绪，三种翻页模式都可见）。
            // 原实现调的是 reader.html 里的 showLoading() —— 那个函数根本不存在，一直是空操作，
            // 于是加载期间正文只把占位哨兵串「【章节加载中...】」当正文画了出来。
            setChapterLoading(true);
            // ✅ 同时把占位页渲染进 WebView：加载动画/失败层都是透明底，
            //    不清掉旧页的话，离线切章时「上一章正文」会一直衬在加载动画/失败层底下。
            renderChapterContent(chapterIndex, title, "【正在加载...】");
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

        // ✅ 内存控制：只保留当前章附近已加载的正文，其余重置为占位。
        //    否则用户一路读下去，翻过的每一章都会常驻 chapterContents/chapterHtmlContents（含 base64 图片），
        //    同样会把堆撑满（此前开书即 OOM 的另一半原因）。
        trimLocalContentCache(chapterIndex);

        for (int i = 0; i < count; i++) {
            long bookId = sp.getLong("book_id_" + i, 0);
            if (bookId == currentBook.getId()) {
                int chCount = sp.getInt("chapter_count_" + i, 0);
                android.util.Log.d("ReadActivity", "Found book at index " + i + ", chapterCount=" + chCount);
                
                if (chapterIndex >= 0 && chapterIndex < chCount) {
                    String title = sp.getString("chapter_title_" + i + "_" + chapterIndex, "第" + (chapterIndex + 1) + "章");
                    // 正文优先读文件缓存；旧书回退读 SP
                    String content = LocalBookParser.readChapterText(this, bookId, chapterIndex);
                    if (content == null || content.isEmpty()) {
                        content = sp.getString("chapter_content_" + i + "_" + chapterIndex, "");
                    }
                    
                    android.util.Log.d("ReadActivity", "Read content length: " + content.length() + ", isEmpty: " + content.isEmpty());
                    
                    if (!content.isEmpty()) {
                        chapterList.get(chapterIndex).setTitle(title);
                        // 修正：只存储纯内容，不添加标题，避免重复显示
                        setChapterContent(chapterIndex, content);
                        android.util.Log.d("ReadActivity", "Content loaded successfully");
                    } else {
                        android.util.Log.e("ReadActivity", "Content is empty! Key: chapter_content_" + i + "_" + chapterIndex);
                    }

                    // ✅ 同步 HTML（本地书靠它保留书内样式）。此前漏写这一步，
                    //    导致按需加载后只能退化成纯文本渲染，书里的排版/字体样式全部丢失。
                    String html = LocalBookParser.readChapterHtml(this, bookId, chapterIndex);
                    setChapterHtml(chapterIndex, html != null ? html : "");
                } else {
                    android.util.Log.e("ReadActivity", "Chapter index out of range: " + chapterIndex + ", chCount=" + chCount);
                }
                break;
            }
        }
    }

    /** 按索引写入正文，自动补齐到 chapterList 长度，避免越界 */
    private void setChapterContent(int index, String content) {
        if (index < 0 || index >= chapterList.size()) return;
        while (chapterContents.size() < chapterList.size()) chapterContents.add(LOCAL_CHAPTER_PLACEHOLDER);
        chapterContents.set(index, content != null ? content : "");
    }

    /** 按索引写入保留样式的 HTML，自动补齐到 chapterList 长度，避免越界 */
    private void setChapterHtml(int index, String html) {
        if (index < 0 || index >= chapterList.size()) return;
        while (chapterHtmlContents.size() < chapterList.size()) chapterHtmlContents.add("");
        chapterHtmlContents.set(index, html != null ? html : "");
    }

    /**
     * 本地书正文缓存的滑动窗口清理：只保留 [center-RADIUS, center+RADIUS] 范围内的已加载内容，
     * 其余重置为占位。用于控制长时间阅读时的内存占用。
     */
    private void trimLocalContentCache(int center) {
        if (!isLocalBook || chapterContents.isEmpty()) return;
        for (int k = 0; k < chapterContents.size(); k++) {
            if (Math.abs(k - center) <= LOCAL_CONTENT_KEEP_RADIUS) continue;
            if (!LOCAL_CHAPTER_PLACEHOLDER.equals(chapterContents.get(k))) {
                chapterContents.set(k, LOCAL_CHAPTER_PLACEHOLDER);
            }
            if (k < chapterHtmlContents.size() && !chapterHtmlContents.get(k).isEmpty()) {
                chapterHtmlContents.set(k, "");
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
            runOnUiThread(() -> Hint.show(this, "章节信息缺失，请稍候重试"));
            return;
        }
        String[] pair = externalChapters[chapterIndex];
        if (pair == null) {
            runOnUiThread(() -> Hint.show(this, "章节信息无效"));
            return;
        }
        String chapterUrl = pair.length > 1 ? pair[1] : "";
        String title = chapterList.get(chapterIndex).getTitle();
        
        // 检查章节URL是否有效
        if (chapterUrl == null || chapterUrl.isEmpty()) {
            android.util.Log.w("ReadActivity", "loadExternalChapterContent: empty chapterUrl for index=" + chapterIndex);
            String noUrlContent = "【章节链接缺失，请返回目录选择其他章节】";
            chapterContents.set(chapterIndex, noUrlContent);   // 失败标记：重试与缓存守卫仍依赖它
            if (currentChapterIndex == chapterIndex) {
                runOnUiThread(() -> showLoadFail("章节链接缺失，无法加载"));
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
                runOnUiThread(() -> renderChapterContent(finalChapterIndex, finalTitle, finalCached, finalPage));
            }
            // 预加载下一章
            preloadNextExternalChapter(chapterIndex);
            return;
        }

        // 3. 显示加载占位（正文不再画哨兵串，页面由「章节加载中」动画接管）
        if (currentChapterIndex == chapterIndex) {
            final int finalPage = targetPage;
            runOnUiThread(() -> {
                setChapterLoading(true);
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
                            chapterContents.set(timeoutChapterIndex, timeoutContent);   // 失败标记：重试与缓存守卫仍依赖它
                            showLoadFail("加载超时，请稍后再试");
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
                                    preloadNextExternalChapter(chapterIndex);  // 预加载下一章
                                    renderChapterContent(chapterIndex, title, finalContent, finalPage);
                                } else {
                                    // 失败不再把「【章节加载失败】…」画进正文，统一走失败层
                                    showLoadFail("加载出错，请稍后再试");
                                }
                            });
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<String>> call, @NonNull Throwable t) {
                        // 失败标记仍写入 chapterContents（缓存守卫依赖），但不再把「【网络错误】…」画进正文
                        chapterContents.set(chapterIndex, "【网络错误】" + t.getMessage());
                        if (currentChapterIndex == chapterIndex) {
                            runOnUiThread(() -> showLoadFail("网络异常，请检查网络后重试"));
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

    /**
     * 阅读器内「章节加载中」动画的显隐。
     *
     * <p>与全站共用 {@code LoadingView}：可见性一变，三个点的动画与 GIF 自动启停，不用手动 start/stop。
     * 动画是盖在 WebView 之上的原生视图，所以三种翻页模式（滚动 / 仿真 / 普通）都同样可见。
     */
    private void setChapterLoading(boolean loading) {
        if (loadingChapter == null) return;
        Runnable r = () -> {
            int want = loading ? View.VISIBLE : View.GONE;
            if (loadingChapter.getVisibility() != want) loadingChapter.setVisibility(want);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
        // 开始新一轮加载 → 上一轮的失败态自动退场（换章/重试都走这里，统一兜住）
        if (loading) hideLoadFail();
    }

    /**
     * 阅读器内「章节加载失败」覆盖层（本站书）的显隐。
     *
     * <p>排版与「加载失败」效果图一致：插画 + 文案 + 胶囊「再试一次」按钮，整体略高于几何中心。
     * 透明底显示阅读页自己的背景/纹理，日间/夜间只切换文案颜色（夜间用阅读正文色 {@code #AAAAAA}）。
     */
    private void showLoadFail(CharSequence msg) {
        if (layoutLoadFail == null) return;
        Runnable r = () -> {
            // 失败态要压在正文上，此时绝不能让「加载中」动画继续转
            if (loadingChapter != null) loadingChapter.setVisibility(View.GONE);
            if (tvLoadFailMsg != null) {
                tvLoadFailMsg.setText(msg != null && msg.length() > 0 ? msg : "加载出错，请稍后再试");
                // 夜间底色（bgColor index 3）下用夜间正文色，其余底色用 iOS 次文字灰
                tvLoadFailMsg.setTextColor(currentBgColor == 3 ? 0xFFAAAAAA : 0xFF8E8E93);
            }
            layoutLoadFail.setVisibility(View.VISIBLE);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    private void hideLoadFail() {
        if (layoutLoadFail == null) return;
        Runnable r = () -> {
            if (layoutLoadFail.getVisibility() != View.GONE) layoutLoadFail.setVisibility(View.GONE);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    private void fetchChapterContent(int chapterIndex) {
        try {
        // ===== 外站书籍：走在线 API 获取章节正文 =====
        if (isExternalBook) {
            if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();   // 由 loadExternalChapterContent 自行渲染
            loadExternalChapterContent(chapterIndex);
            return;
        }
        if (isLocalBook) {
            // 本地书不应该走网络请求，直接尝试从本地缓存重新加载
            if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();   // 下面会同步渲染
            reloadLocalChapterContent(chapterIndex);
            String title = chapterList.get(chapterIndex).getTitle();
            String content = chapterContents.get(chapterIndex);
            // 有保留样式的 HTML 同样算「有内容」：极少数章节（题图页等）纯文本很短甚至为空，
            // 但 HTML 里有实体，走 HTML 渲染模式照样能显示出来。
            String html = (chapterIndex >= 0 && chapterIndex < chapterHtmlContents.size())
                    ? chapterHtmlContents.get(chapterIndex) : "";
            boolean hasHtml = html != null && !html.isEmpty();
            if (!isChapterContentPending(content) || hasHtml) {
                // ✅ 与服务器章节路径保持一致：渲染前先把「当前章」切过来，
                //    否则翻章后进度、章节标题、上一章/下一章按钮仍停在旧章。
                currentChapterIndex = chapterIndex;
                updateChapterButtons();
                renderChapterContent(chapterIndex, title, content == null ? "" : content);
                if (!positionRestored) positionRestored = true;
            } else {
                setChapterLoading(false);   // 既无正文也无 HTML → 不会触发渲染回调，别让动画一直转
                Hint.show(this, "章节内容缺失");
            }
            return;
        }
        
        // ✅ 简化：直接检查索引范围
        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
            android.util.Log.e("ReadActivity", "fetchChapterContent: chapterIndex out of range! index=" + chapterIndex + ", size=" + chapterList.size());
            if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();
            setChapterLoading(false);   // 该分支不会触发渲染，别让动画一直转
            Hint.show(this, "章节加载中，请稍后...");
            return;
        }
        
        long chapterId = chapterList.get(chapterIndex).getId();
        android.util.Log.d("ReadActivity", "fetchChapterContent: chapterIndex=" + chapterIndex + ", chapterId=" + chapterId);
        
        if (chapterId <= 0) {
            // 章节ID无效通常是章节列表还未加载完成，静默等待服务器数据返回即可
            // 不显示错误Toast，避免打扰用户（占位符已显示，数据到达后会自动更新）
            android.util.Log.d("ReadActivity", "章节ID无效，等待列表加载... index=" + chapterIndex + ", id=" + chapterId);
            // 请求没有真正发出，清掉在途跳转，否则用户会一直翻不动
            if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();
            return;
        }

        RetrofitClient.getApiService().getChapterContent(chapterId).enqueue(new Callback<ApiResponse<com.example.myapplication.bean.Chapter>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<com.example.myapplication.bean.Chapter>> call,
                                   @NonNull Response<ApiResponse<com.example.myapplication.bean.Chapter>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    com.example.myapplication.bean.Chapter chapterBean = response.body().getData();
                    String rawContent = chapterBean.getContent();
                    final String realContent = (rawContent == null || rawContent.trim().isEmpty() || rawContent.equals("\uFEFF"))
                            ? "【本章节内容暂缺】" : rawContent;
                    chapterContents.set(chapterIndex, realContent);
                    cacheChapterContent(currentBook.getId(), chapterIndex, realContent);
                    
                    android.util.Log.d("ReadActivity", "fetchChapterContent onResponse: chapterIndex=" + chapterIndex + ", currentChapterIndex=" + currentChapterIndex + ", content length=" + realContent.length());
                    
                    if (pendingChapterIndex == chapterIndex) {
                        // ✅ 用户是在章节边界翻页触发的加载：正文到位后再切换过去（之前没推进过索引，
                        //    所以这里不会出现"加载完直接跳两章"）
                        boolean goLast = pendingGoLastPage;
                        int pendingPage = pendingChapterPage;
                        clearPendingChapterJump();
                        android.util.Log.d("ReadActivity", "Pending chapter jump done: chapterIndex=" + chapterIndex + ", goLastPage=" + goLast + ", page=" + pendingPage);
                        runOnUiThread(() -> showPendingChapter(chapterIndex, realContent, goLast, pendingPage));
                    } else if (currentChapterIndex == chapterIndex) {
                        // ✅ 复用池修复：正文异步到达时沿用「恢复目标页码」重渲染，
                        //    否则默认 page=1 会把恢复位置冲掉，导致「重新打开总落第 1 页」。
                        //    若 restoreTargetPage<=0（如未指定），则回退首页。
                        int _restorePage = (restoreTargetPage > 0) ? restoreTargetPage : 1;
                        android.util.Log.d("ReadActivity", "Re-rendering chapter " + chapterIndex + " with fetched content, restoreTargetPage=" + _restorePage);
                        renderChapterContent(chapterIndex, chapterList.get(chapterIndex).getTitle(), realContent, _restorePage);
                    } else {
                        android.util.Log.d("ReadActivity", "Not current chapter, skip re-render");
                    }
                } else {
                    if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();
                    runOnUiThread(() -> showLoadFail("加载出错，请稍后再试"));
                }
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse<com.example.myapplication.bean.Chapter>> call, @NonNull Throwable t) {
                if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();
                runOnUiThread(() -> showLoadFail("加载出错，请稍后再试"));
            }
        });
        } catch (Throwable t) {
            if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();
            android.util.Log.e("ReadActivity", "fetchChapterContent崩溃: index=" + chapterIndex, t);
            final String msg = t.getMessage() == null ? "未知错误" : t.getMessage();
            runOnUiThread(() -> {
                if (isExternalBook) {
                    showPopupErrorDialog("章节加载失败：" + msg);
                } else {
                    showLoadFail("加载出错，请稍后再试");
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
        // ✅ 每次外部渲染请求都生成新代次：使在途的旧延迟重试自动失效
        final int myGen = ++renderGeneration;
        renderChapterContentInternal(chapterIndex, title, content, startPage, myGen);
    }

    /**
     * renderChapterContent 的内部实现：携带本次渲染代次 myGen。
     * WebView 未就绪时的 50ms 延迟重试若发现代次已过期（期间有更新的渲染请求），
     * 则直接丢弃——不渲染、不写 currentChapterIndex。
     */
    private void renderChapterContentInternal(int chapterIndex, String title, String content, int startPage, int myGen) {
        try {
            // ★ 占位/未加载：正文用零宽字符顶替 —— 页面由「章节加载中」动画接管，
            //   不再把哨兵串「【章节加载中...】」当正文画出来。
            //   chapterContents 里的哨兵**原样保留**，isChapterContentPending 的判定不受影响。
            final boolean pendingContent = isChapterContentPending(content);
            // 只要渲染的是占位内容，就把加载动画顶上来 —— 阅读器有多个入口会直接渲染占位
            // （首屏恢复进度 / 章节列表还没到 / 外站翻章 / 本地书按需读取），在这里统一兜住，免得逐个入口漏加。
            if (pendingContent) setChapterLoading(true);
            else hideLoadFail();   // 渲染到真实正文 → 失败态自动退场（上次失败重试成功后也走这里）

            // ✅ 关键修复（复用池回归）：渲染必须等 WebView 真正完成布局、拿到有效宽度后再分页。
            //    复用的 WebView 在 onCreate 阶段 isWebViewReady 已被提前置 true（reader.html 预加载完成），
            //    但此时 WebView 尚未 attach/测量，window.innerWidth == 0；若在此宽度分页会得到退化的 pages，
            //    导致「恢复总落到第 1 页」且「翻页/换章失效」。故额外用 getWidth()>0 兜底，
            //    复刻旧实现里「等布局后再分页」的语义（沿用已有的 50ms 代次感知重试）。
            if (!isWebViewReady || webView.getWidth() <= 0) {
                final int finalChapterIndex = chapterIndex;
                final String finalTitle = title;
                final String finalContent = content;
                final int finalPage = startPage;
                if (webView.getWidth() <= 0) {
                    android.util.Log.d("ReadActivity", "renderChapterContentInternal: 宽度未就绪("
                            + webView.getWidth() + ")，延迟分页等待布局, chapter=" + chapterIndex + ", page=" + startPage);
                }
                mainHandler.postDelayed(() -> {
                    // ✅ 过期重试丢弃：已有更新的渲染请求发生（例如服务器正文已渲染目标章），本次作废
                    if (myGen != renderGeneration) return;
                    // 复用本次代次递归，避免自增导致前一次永远过期
                    renderChapterContentInternal(finalChapterIndex, finalTitle, finalContent, finalPage, myGen);
                }, 50);
                return;
            }

            String safeTitle = (title == null ? "" : title);
            String safeContent = (content == null ? "" : content);
            String escapedTitle = escapeJavaScript(safeTitle);
            // 用 U+200B（零宽字符）而不是空串：让 reader.html 仍按「有内容」走正常分页分支，
            // 避免空串在分页/绘制分支上触发边角情况；视觉上则是一片空白页。
            String escapedContent = escapeJavaScript(pendingContent ? "\u200B" : safeContent);
            int bookProgress = (!chapterList.isEmpty()) ?
                    (int)((currentChapterIndex + 1) * 100f / chapterList.size()) : 0;
            // ✅ -1 表示跳到最后一页（loadChapterContentToLastPage 使用），必须原样传给 JS
            //    否则会被转成 null，JS loadContent 命中 else 分支回到第一页
            String pageArg = (startPage == -1) ? "-1" : (startPage > 0 ? String.valueOf(startPage) : "null");

            // ✅ 排版缓存：非占位内容时，按 layout signature 读取上次退出本章时的分页结果；
            //    命中则随 loadContent 直接恢复，跳过整章重排（这是「进入书籍不再每次重排」的关键）。
            //    占位内容不读也不写缓存（避免把零宽占位串的排版缓存成真章节的排版）。
            String cachedJson = null;
            String cacheKey = null;
            if (!pendingContent) {
                cacheKey = computeLayoutKey(chapterIndex, safeContent);
                cachedJson = readLayoutCache(cacheKey);
            }
            String escapedCacheKey = (cacheKey != null) ? escapeJavaScript(cacheKey) : "null";
            String escapedCachedJson = (cachedJson != null) ? escapeJavaScript(cachedJson) : "null";

            // ✅ 本地 EPUB 且存在保留样式的 HTML → 走 HTML 渲染模式（展示样式而非纯文本）
            //    必须「一次性赋值」以保证 effectively final —— 下方 evaluateJavascript 的 lambda 内会引用它
            final String htmlForChapter = (isLocalBook && chapterIndex >= 0
                    && chapterIndex < chapterHtmlContents.size())
                    ? chapterHtmlContents.get(chapterIndex) : "";

            String js;
            if (htmlForChapter != null && !htmlForChapter.isEmpty()) {
                // HTML 渲染模式
                String escapedHtml = escapeJavaScript(htmlForChapter);
                js = "(function(){" +
                        "try{" +
                        "var r=loadHtmlContent(" + chapterIndex + "," + escapedTitle + "," + escapedHtml + "," +
                        pageArg + "," + isLocalBook + "," + bookProgress + ");" +
                        "return 'ok';" +
                        "}catch(e){" +
                        "return 'error:'+e.message;" +
                        "}" +
                        "})()";
            } else {
                // ✅ 包装 loadContent 在 JS try/catch 中，捕获 JS 渲染异常并回传
                js = "(function(){" +
                        "try{" +
                        "var r=loadContent(" + chapterIndex + "," + escapedTitle + "," + escapedContent + "," +
                        pageArg + "," + isLocalBook + "," + bookProgress + "," + escapedCachedJson + "," + escapedCacheKey + ");" +
                        "return 'ok';" +
                        "}catch(e){" +
                        "return 'error:'+e.message;" +
                        "}" +
                        "})()";
            }
            webView.evaluateJavascript(js, value -> {
                // 真实正文已渲染到位 → 收起「章节加载中」动画。
                // 放在 JS 回调里收（而不是发指令前），确保版面确实换成了正文再撤掉遮罩。
                // ⚠️ 本地书存在「纯文本是占位、但带保留样式的 HTML」（题图页等）：这种情况走 HTML 分支
                //    照样渲染成功，纯文本却仍是占位 —— 只按纯文本判会让动画一直转下去，必须一并放行。
                boolean htmlRendered = htmlForChapter != null && !htmlForChapter.isEmpty();
                if (!pendingContent || htmlRendered) setChapterLoading(false);
                if (value != null && value.startsWith("\"error:")) {
                    String errMsg = value.substring(8, value.length() - 1);
                    android.util.Log.e("ReadActivity", "JS render error: " + errMsg);
                    if (isLocalBook && htmlForChapter != null && !htmlForChapter.isEmpty()) {
                        // 本地 HTML 渲染失败 → 回退纯文本，保证可读
                        android.util.Log.w("ReadActivity", "HTML 渲染失败，回退纯文本: " + errMsg);
                        final String fbJs = "(function(){" +
                                "try{var r=loadContent(" + chapterIndex + "," + escapedTitle + "," + escapedContent + "," +
                                pageArg + "," + isLocalBook + "," + bookProgress + "," + escapedCachedJson + "," + escapedCacheKey + ");return 'ok';" +
                                "}catch(e){return 'error:'+e.message;}})()";
                        webView.evaluateJavascript(fbJs, null);
                    } else {
                        runOnUiThread(() -> {
                            if (isExternalBook) {
                                // 渲染异常也统一走失败层，不再把「【章节渲染异常：…】」画进正文
                                chapterContents.set(chapterIndex, "【章节渲染异常：" + errMsg + "】");
                                showLoadFail("章节渲染异常，请重试");
                            } else {
                                // 非外站书渲染异常：别让「加载中」一直转
                                setChapterLoading(false);
                            }
                        });
                    }
                }
            });
            currentChapterIndex = chapterIndex;
            updateChapterButtons();

            // 设置已在 onPageFinished 中通过 applySettingsToWebView 预先应用
            // 仅需在加载新内容后重新设置翻页模式（仿真模式需要重建 flipbook）
            webView.evaluateJavascript("setPageTurnMode('" + pageTurnMode + "')", null);

            // ✅ 关键修复（覆盖全部主题模式）：从排版缓存恢复章节时，loadContent 直接把「上次分页时固化的文字色/
            //    背景」赋值给 pages 并立即绘制；而 onPageFinished 里的 applySettingsToWebView 在 loadContent 之前调用
            //    （彼时 pages 尚为空，setNightMode/setTextColor 遍历空数组无效），chapterRestoredFromCache 分支又跳过了
            //    applySettingsToWebView_inner（含 setNightMode / applyBackgroundColorToWebView）。
            //    因此必须在 loadContent 之后（evaluateJavascript 按提交顺序在 JS 单线程顺序执行，此时 pages 已存在）
            //    重新应用完整主题 —— 夜间走 setNightMode(true)，日间/自定义纯色/纹理走 applyBackgroundColorToWebView，
            //    把缓存页的固化色覆盖为当前主题并重绘，彻底解决「退出重进正文消失 / 颜色不符」。
            webView.evaluateJavascript("beginSettingsBatch()", null);
            applySettingsToWebView_inner();
            webView.evaluateJavascript("finishSettingsBatch()", null);
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "renderChapterContent 崩溃: index=" + chapterIndex
                    + ", title.len=" + (title == null ? 0 : title.length())
                    + ", content.len=" + (content == null ? 0 : content.length()), t);
            runOnUiThread(() -> {
                String msg = "章节渲染失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                if (isExternalBook) {
                    showPopupErrorDialog(msg);
                } else {
                    Hint.showLong(ReadActivity.this, msg);
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
        }
        updateBatteryAndTime();
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

    // ========== 在途跨章跳转：工具方法 ==========
    /** 是否存在尚未落地的跨章跳转（超过 10s 视为已失效，允许重新发起） */
    private boolean hasPendingChapterJump() {
        if (pendingChapterIndex < 0) return false;
        if (System.currentTimeMillis() - pendingChapterTs > PENDING_CHAPTER_TIMEOUT) {
            clearPendingChapterJump();
            return false;
        }
        return true;
    }

    private void setPendingChapterJump(int chapterIndex, boolean goLastPage) {
        setPendingChapterJump(chapterIndex, goLastPage, -1);
    }

    /** @param page 目标页码（章节内，1 基）；&lt;=0 表示不指定（按 goLastPage / 第一页处理） */
    private void setPendingChapterJump(int chapterIndex, boolean goLastPage, int page) {
        pendingChapterIndex = chapterIndex;
        pendingGoLastPage = goLastPage;
        pendingChapterPage = page;
        pendingChapterTs = System.currentTimeMillis();
    }

    private void clearPendingChapterJump() {
        pendingChapterIndex = -1;
        pendingGoLastPage = false;
        pendingChapterPage = -1;
        pendingChapterTs = 0L;
    }

    /**
     * 进入在途跨章跳转前调用：
     * 已有指向同一章的跳转 → 忽略本次翻页（避免重复请求 / 连跳多章）；
     * 指向别的章（用户换了方向）→ 取消旧跳转，按新方向走。
     * @return true 表示本次请求应被忽略
     */
    private boolean shouldIgnoreChapterJump(int targetIndex) {
        if (!hasPendingChapterJump()) return false;
        if (pendingChapterIndex != targetIndex) clearPendingChapterJump();
        return pendingChapterIndex == targetIndex;
    }

    /** 目标章节正文到位后的统一渲染（不经过 loadChapterContent，避免"内容暂缺"时再次发起请求死循环）
     *  @param page 章节内目标页码（1 基）；&lt;=0 时按 goLastPage / 第一页处理 */
    private void showPendingChapter(int chapterIndex, String content, boolean goLastPage, int page) {
        currentChapterIndex = chapterIndex;
        updateChapterButtons();
        int targetPage = page > 0 ? page : (goLastPage ? -1 : 1);
        renderChapterContent(chapterIndex, chapterList.get(chapterIndex).getTitle(),
                content, targetPage);
        applyHeaderFooterSettings();
        applyFontSizeToWebView();
        applyFontFamilyToWebView();
        if (!isNightMode) applyBackgroundColorToWebView(currentBgColor);
        if (!positionRestored) {
            positionRestored = true;
        }
    }

    /**
     * ✅ 获取当前电量和时间，更新到 WebView
     */
    private void updateBatteryAndTime() {
        if (!showBatteryTime || !showHeaderFooter) return;
        if (!isWebViewReady) {
            // WebView 还没就绪（典型场景：onResume 早于 onPageFinished）。
            // 不必在这里硬推，onPageFinished 会再调用一次 applyHeaderFooterSettings() 补发。
            return;
        }

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
        // 服务端进度比本地新时允许越过「已恢复位置」守卫重渲染一次（见 adoptServerPosition）
        boolean forced = forceRestorePosition;
        forceRestorePosition = false;
        // ✅ 复用池修复：记住本次恢复的目标页码，正文异步到达后重渲染沿用（见 fetchChapterContent.onResponse）
        restoreTargetPage = (page > 0) ? page : 1;
        try {
        android.util.Log.d("ReadActivity", "loadChapterContentWithPage: chapterIndex=" + chapterIndex + ", page=" + page + ", chapterList.size()=" + chapterList.size());

        // ✅ 防止重复渲染：如果已经正确恢复了位置，跳过（避免 fetchServerProgress 等后续调用导致闪屏）
        if (positionRestored && !forced) {
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
        boolean isPlaceholder = isChapterContentPending(content);
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
            Hint.show(this, "章节内容加载中...");
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
                else showLoadFail("加载出错，请稍后再试");
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
        boolean isPlaceholder = isChapterContentPending(content);
        if (isLocalBook && isPlaceholder) {
            // 本地书内容缺失，尝试重新加载
            reloadLocalChapterContent(chapterIndex);
            content = chapterIndex < chapterContents.size() ? chapterContents.get(chapterIndex) : "";
            // 更新 title，因为 reloadLocalChapterContent 可能会更新标题
            title = chapterList.get(chapterIndex).getTitle();
        }

        // 如果仍然没有有效内容，显示提示
        if (content == null || content.isEmpty() || content.contains("加载中...")) {
            Hint.show(this, "章节内容加载中...");
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
            runOnUiThread(() -> Hint.show(ReadActivity.this, "章节加载失败"));
        }
    }

    // ==================== UI 更新 ====================
    @SuppressLint("SetTextI18n")
    private void updateProgressDisplay() {
        // 下方仅显示章节名；页码由滑块浮窗与正文页脚呈现，不再重复显示
        tvProgressText.setText(resolveChapterTitle());
        liquidSlider.setProgressInfo(currentPageInChapter, totalPagesInChapter);
    }

    /** 当前章节的展示标题（特殊章节如作者的话用真实标题，否则用“第X章”） */
    private String resolveChapterTitle() {
        if (currentChapterIndex >= 0 && currentChapterIndex < chapterList.size()) {
            String t = chapterList.get(currentChapterIndex).getTitle();
            if (t != null && !t.isEmpty()) return t;
        }
        return "第" + (currentChapterIndex + 1) + "章";
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
        // 滑动翻页开关：同步给前端的跟手拖拽逻辑
        sb.append("if (typeof setSwipeEnabled === 'function') { setSwipeEnabled(").append(swipePageTurn).append("); }");

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
        // ✅ 页眉页脚 / 电量时间也要一起应用：从本地缓存恢复章节时只走这个方法，
        // 之前漏掉它就导致页脚电量时间不显示
        applyHeaderFooterSettings();
        webView.evaluateJavascript("finishSettingsBatch()", null);
        webView.evaluateJavascript("setPageTurnMode('" + pageTurnMode + "')", null);
        webView.evaluateJavascript("setSwipeEnabled(" + swipePageTurn + ")", null);
    }

    /** 内部方法：应用字体、背景等设置（不含 batch 包装和翻页模式） */
    private void applySettingsToWebView_inner() {
        webView.evaluateJavascript("setFontSize(" + currentFontSize + ")", null);
        applyFontFamilyToWebView();
        if (isNightMode) {
            webView.evaluateJavascript("setNightMode(true)", null);
            effectiveBgBase = Color.parseColor("#1A1A1A");
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
            effectiveBgBase = BG_TEXTURE_PREVIEW_COLORS[texIdx];
        } else {
            // 纯色模式
            if (mode < 0 || mode >= BG_COLORS.length) mode = 0;
            webView.evaluateJavascript("clearBackgroundTexture()", null);
            webView.evaluateJavascript("setBackgroundColor('" + BG_COLORS[mode] + "')", null);
            webView.evaluateJavascript("setTextColor('" + BG_TEXT_COLORS[mode] + "')", null);
            effectiveBgBase = Color.parseColor(BG_COLORS[mode]);
        }
    }

    /**
     * 取指定背景模式对应的「基准色」（不改动 UI）：纹理取预览底色，纯色取自身。
     * 导航栏/浮窗的派生配色以它为输入。
     */
    private int resolveBaseForMode(int mode) {
        if (mode >= 10 && mode - 10 < BG_TEXTURE_PREVIEW_COLORS.length) {
            return BG_TEXTURE_PREVIEW_COLORS[mode - 10];
        }
        if (mode < 0 || mode >= BG_COLORS.length) mode = 0;
        return Color.parseColor(BG_COLORS[mode]);
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
                float x = e.getRawX();
                // ⚠️ 关键：MotionEvent 会先派发给最上层的左右热区 View（activity_read.xml 里
                // view_left_tap / view_right_tap 排在 WebView 之后 → 层级更高），
                // e.getX() 是「相对该热区」的坐标，而热区只有 80dp 宽，
                // 直接用会让点右侧也落进左 1/3 分支 → 翻到上一页。
                // 这里统一用 rawX 减去 WebView 在屏幕上的位置，换算成 WebView 内坐标。
                int[] webViewLocation = new int[2];
                webView.getLocationOnScreen(webViewLocation);
                x -= webViewLocation[0];
                int width = webView.getWidth();
                if (width == 0) width = getResources().getDisplayMetrics().widthPixels;
                float zone = width / 3f;

                // 仿真翻页模式：Android 端不自行处理点击，交由前端统一处理。
                // 非 HTML：由 reader.html 的文档级触摸处理（与 onFling 让位策略一致，
                //   避免两端各翻一次变成"点一下翻两页"）。
                // HTML 富文本模式：没有仿真层，document 级处理不会触发，改由 onAndroidTap
                //   做左/中/右三分区兜底 —— 否则中间区点击无人处理，导航栏调不出来。
                if ("simulation".equals(pageTurnMode)) {
                    if (isWebViewReady) {
                        // ★ 必须传「相对宽度的比例」而不是像素：reader.html 里的
                        //   window.innerWidth 是 CSS 像素（1080 物理屏 ≈ 360），
                        //   传物理像素会让屏幕正中间被判成右 1/3 → 只翻页、调不出导航栏。
                        float frac = width > 0
                                ? Math.max(0f, Math.min(1f, x / (float) width))
                                : 0.5f;
                        webView.evaluateJavascript("onAndroidTap(" + frac + ")", null);
                    }
                    return true;
                }

                if ("updown".equals(pageTurnMode)) {
                    // 连续滚动模式：上下滑动即滚动，点击只用于切换导航栏（中间区域），
                    // 左右区域不再翻页，避免与拖拽滚动重复触发
                    if (x >= zone && x <= zone * 2) toggleNavigation();
                    return true;
                }

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

                // 同样必须用 rawX/rawY：手指从热区滑到 WebView 时，e1/e2 可能来自不同的 View，
                // 各自的 getX() 坐标系不同，相减会得到错误的位移。
                float deltaX = e2.getRawX() - e1.getRawX();
                float deltaY = e2.getRawY() - e1.getRawY();

                // 只处理水平为主的滑动（避免与垂直滚动冲突）
                if (Math.abs(deltaX) < swipeMinDistancePx
                        || Math.abs(deltaX) < Math.abs(deltaY) * 1.5f) {
                    return false;
                }

                if (Math.abs(velocityX) < swipeMinVelocityPx) return false;

                if (isWebViewReady) {
                    // 统一交给前端决策：cover/slide/updown 模式由 reader.html 的跟手拖拽
                    // 在 touchend 自行收尾（书页跟手 + 松手判定），这里不再直接翻页，
                    // 否则一次滑动会翻两页。前端未接管时（旧版页面）由下面的回调兜底。
                    webView.evaluateJavascript("onAndroidFling(" + deltaX + ")", value -> {
                        if (!"true".equals(value)) {
                            if (deltaX < 0) {
                                webView.evaluateJavascript("nextPage()", null);
                            } else {
                                webView.evaluateJavascript("prevPage()", null);
                            }
                        }
                    });
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
        // 设置浮窗的窗口下沿收缩到本行上沿后，本行已不被浮窗遮罩覆盖（保持可见、可点）。
        // 此时点本行按钮的语义与「点浮窗外部」一致：先收起设置浮窗，不再触发各自功能。
        btnCatalog.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (isSettingsPopupShowing()) { dismissSettingsAnimated(); return; }
            showChapterPopup();
        });
        btnNightMode.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (isSettingsPopupShowing()) { dismissSettingsAnimated(); return; }
            animateNightModeToggle();
        });
        btnSettings.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (isSettingsPopupShowing()) { dismissSettingsAnimated(); return; }
            showSettingsDialog();
        });

        // 液态滑块：拖动中仅轻量更新页码文字（浮窗/滑钮由控件自绘跟随），松手才真正跳页一次，
        // 替代旧 SeekBar 每帧 evaluateJavascript 造成的 WebView 逐帧重排卡顿
        liquidSlider.setListener(new com.example.myapplication.view.LiquidSlider.Listener() {
            @Override public void onDragStart() { mainHandler.removeCallbacks(hideNavRunnable); }
            @Override public void onDragPage(int page) {
                // 拖动中章节名不变、页码由滑块浮窗实时显示，此处无需更新文字
            }
            @Override public void onDragEnd(int page) {
                resetAutoHideTimer();
                if (totalPagesInChapter > 1 && page != currentPageInChapter) {
                    webView.evaluateJavascript("jumpToPage(" + page + ")", null);
                }
            }
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
        setNavLayer(layoutTopNav, false);
        setNavLayer(layoutBottomNav, false);
        layoutTopNav.setTranslationY(0);
        layoutBottomNav.setTranslationY(0);
        layoutTopNav.setVisibility(View.GONE);
        layoutBottomNav.setVisibility(View.GONE);
    }

    /**
     * 滑动动画期间把导航栏固定到硬件层，使 translationY 只是平移一张纹理，
     * 不每帧重新栅格化（含 elevation 阴影重算），从而消除滑动卡顿。
     * 动画结束必须调回 NONE，避免长期占用 GPU 离屏缓冲。
     */
    private void setNavLayer(android.view.View v, boolean hardware) {
        v.setLayerType(hardware ? android.view.View.LAYER_TYPE_HARDWARE
                                : android.view.View.LAYER_TYPE_NONE, null);
    }

    /**
     * 缓存底部导航栏高度，仅在首次滑入前测量一次。
     * 用屏幕宽度（match_parent）做精确约束，避免每次滑动都 measure 造成起手掉帧。
     */
    private void ensureBottomNavHeight() {
        if (bottomNavHeight <= 0) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY);
            int heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
            layoutBottomNav.measure(widthSpec, heightSpec);
            bottomNavHeight = layoutBottomNav.getMeasuredHeight();
        }
    }

    private void showNavigation() {
        showSystemStatusBar();  // 与滑入同步显示，避免动画结束后状态栏再突现

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

        ensureBottomNavHeight();

        // 顶部栏：从屏幕顶部整块滑入（y: -自身高度 -> 0）。滑距用已知高度推算，避免每次 measure 掉帧。
        final float topSlideDistance = statusBarHeight + navHeight;
        layoutTopNav.setTranslationY(-topSlideDistance);
        setNavLayer(layoutTopNav, true);
        layoutTopNav.animate().translationY(0).setDuration(NAV_ANIM_MS)
                .withEndAction(() -> setNavLayer(layoutTopNav, false)).start();

        // 底部栏：从屏幕底部滑入（y: +自身高度 -> 0）
        final float bottomSlideDistance = bottomNavHeight;
        layoutBottomNav.setTranslationY(bottomSlideDistance);
        setNavLayer(layoutBottomNav, true);
        layoutBottomNav.animate().translationY(0).setDuration(NAV_ANIM_MS)
                .withEndAction(() -> setNavLayer(layoutBottomNav, false)).start();

        resetAutoHideTimer();
    }

    private void hideNavigation() {
        hideSystemStatusBar();  // 与滑出同步隐藏，避免动画结束后状态栏再突隐

        mainHandler.removeCallbacks(hideNavRunnable);
        dismissPopups();

        ensureBottomNavHeight();

        // 顶部栏：连同「覆盖状态栏的整块区域」一起向上滑出屏幕（滑距 = 完整可见高度）。
        // 不再中途改高度/内边距，避免起手先向上跳一下再滑的割裂感。
        final float navHeight = (int) (56 * getResources().getDisplayMetrics().density);
        final float topSlideDistance = statusBarHeight + navHeight;
        setNavLayer(layoutTopNav, true);
        layoutTopNav.animate().translationY(-topSlideDistance).setDuration(NAV_ANIM_MS)
                .withEndAction(() -> {
                    layoutTopNav.setVisibility(View.GONE);
                    layoutTopNav.setTranslationY(0);
                    setNavLayer(layoutTopNav, false);
                }).start();

        // 底部栏：向下滑出屏幕
        final float bottomSlideDistance = bottomNavHeight;
        setNavLayer(layoutBottomNav, true);
        layoutBottomNav.animate().translationY(bottomSlideDistance).setDuration(NAV_ANIM_MS)
                .withEndAction(() -> {
                    layoutBottomNav.setVisibility(View.GONE);
                    layoutBottomNav.setTranslationY(0);
                    setNavLayer(layoutBottomNav, false);
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

    /**
     * 目录/书签浮窗顶部的书籍信息头：封面 / 书名 / 作者。
     * 书名作者优先取 currentBook（从书架/详情/阅读记录带过来），缺失时回落到本地书 SP 记录；
     * 封面取 currentBook 的封面，其次本地封面文件，最后本地书里的封面串（可能是 base64 或 URL）。
     */
    private void bindPopupBookInfoHeader(View popupView) {
        try {
            ImageView ivCover = popupView.findViewById(R.id.iv_popup_book_cover);
            TextView tvTitle = popupView.findViewById(R.id.tv_popup_book_title);
            TextView tvAuthor = popupView.findViewById(R.id.tv_popup_book_author);
            if (ivCover == null || tvTitle == null || tvAuthor == null) return;

            String title = (currentBook == null || currentBook.getTitle() == null)
                    ? "" : currentBook.getTitle().trim();
            if (title.isEmpty() && currentBook != null && currentBook.getBookName() != null) {
                title = currentBook.getBookName().trim();
            }
            if (title.isEmpty() && localBookName != null) title = localBookName.trim();
            tvTitle.setText(title.isEmpty() ? "未知书名" : title);

            String author = (currentBook == null || currentBook.getAuthor() == null)
                    ? "" : currentBook.getAuthor().trim();
            if (author.isEmpty() && localBookAuthor != null) author = localBookAuthor.trim();
            tvAuthor.setText(author.isEmpty() ? "未知作者" : author);

            // 封面优先级：currentBook.cover → 本地封面文件 → 本地封面串
            String cover = (currentBook == null || currentBook.getCover() == null)
                    ? "" : currentBook.getCover().trim();
            if (cover.isEmpty() && localBookCoverPath != null && !localBookCoverPath.isEmpty()
                    && new java.io.File(localBookCoverPath).exists()) {
                cover = localBookCoverPath;
            }
            if (cover.isEmpty() && localBookCover != null) cover = localBookCover.trim();

            if (cover.isEmpty()) {
                ivCover.setImageResource(R.drawable.default_book_cover);
            } else {
                Glide.with(this)
                        .load(RetrofitClient.getFullImageUrl(cover))
                        .placeholder(R.drawable.default_book_cover)
                        .error(R.drawable.default_book_cover)
                        .into(ivCover);
            }
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "目录浮窗书籍信息头填充失败", t);
        }
    }

    @SuppressLint("InflateParams")
    private void showChapterPopup() {
        try {
            if (chapterList == null || chapterList.isEmpty()) {
                Hint.show(this, "章节列表还在加载中，请稍候");
                return;
            }
            View popupView = LayoutInflater.from(this).inflate(R.layout.popup_chapter_list, null);
            TabLayout tabLayout = popupView.findViewById(R.id.tab_layout_popup);
            ViewPager2 vpPopup = popupView.findViewById(R.id.view_pager_popup);

            // 顶部书籍信息头（封面 / 书名 / 作者），目录与书签共用
            bindPopupBookInfoHeader(popupView);

            vpPopup.setAdapter(createPopupPagerAdapter());
            new TabLayoutMediator(tabLayout, vpPopup, (tab, position) -> tab.setText(position == 0 ? "目录" : "书签")).attach();

            // ✅ 目录浮窗外壳（TabLayout 等）跟随日/夜间配色；列表项由各 Fragment/Adapter 自行着色
            themeViewTree(popupView);

            int popupWidth = (int)(getResources().getDisplayMetrics().widthPixels * 0.75f);
            chapterPopupWindow = new PopupWindow(popupView, popupWidth, WindowManager.LayoutParams.MATCH_PARENT, true);
            chapterPopupWindow.setAnimationStyle(R.style.LeftSlideAnimation);
            chapterPopupWindow.showAtLocation(layoutBottomNav, Gravity.START, 0, 0);

            // ViewPager2 里的 Fragment 在 attach 之后才走 onCreateView，首帧着色覆盖不到；
            // 这里 post 一次整体重染，并在切到「书签」页时再染一次，保证两页都跟随背景色。
            popupView.post(() -> themeViewTree(popupView));
            vpPopup.registerOnPageChangeCallback(new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                @Override public void onPageSelected(int position) {
                    popupView.post(() -> themeViewTree(popupView));
                }
            });

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
        // 分卷结构按「当前 chapterList」推得，保证与 safeCopy 下标严格一致
        final List<LocalBookParser.VolumeInfo> safeVolumes = new ArrayList<>(currentVolumes());
        PopupChapterFragment chapterFragment = new PopupChapterFragment(safeCopy, safeIndex, safeVolumes);
        chapterFragment.setOnChapterSelectedListener(chapterIndex -> {
            if (chapterPopupWindow != null) chapterPopupWindow.dismiss();
            if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
                Hint.show(ReadActivity.this, "章节索引无效，列表可能刚更新");
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

    /**
     * 当前书的分卷结构（阅读器目录浮窗用，每次打开目录浮窗都会重新推导）。
     *
     * <p>本地书：直接返回 {@link #localVolumes}（导入时持久化的卷表，其 start/end 即 chapterList 下标）。
     * <p>服务器书：用 {@link #serverMajorChapters} 与「当前 {@link #chapterList} 的 sortKey」实时推导，
     * 因此缓存恢复 / {@link #mergeServerData} / 切章之后，卷区间始终对齐最新 chapterList 的下标，
     * 不会错位；数据缺失或卷数小于 2 时 {@link VolumeDeriver} 返回空，目录退回平铺。
     * <p>外站书：无分卷结构，返回空（保持平铺）。
     */
    private List<LocalBookParser.VolumeInfo> currentVolumes() {
        if (isLocalBook) return localVolumes;
        if (isExternalBook) return new ArrayList<>();
        List<String> sortKeys = new ArrayList<>(chapterList.size());
        for (Chapter c : chapterList) sortKeys.add(c.getSortKey());
        return VolumeDeriver.derive(sortKeys, serverMajorChapters);
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
        if (currentBook != null) {
            bookmarkFragment.setSourceType(currentBook.getSourceType());
            bookmarkFragment.setSourceBookId(currentBook.getSourceUrl());
        }
        bookmarkFragment.setOnBookmarkSelectedListener(bookmark -> {
            try {
                if (bookmark == null) return;
                Integer chIdxObj = bookmark.getChapterIndex();
                Integer scrollObj = bookmark.getScrollPosition();
                int chIdx = chIdxObj == null ? 0 : chIdxObj;
                int scrollPos = scrollObj == null ? 0 : scrollObj;
                if (chIdx < 0 || chIdx >= chapterList.size()) {
                    Hint.show(ReadActivity.this, "书签章节索引已失效");
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
            SharedPreferences sp = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
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
        // 带上章节 id：后端靠它记录「读到哪一章」，后台「用户阅读数据」页要显示章节名。
        // 后端对 null 是跳过更新，所以能给就给，给不了也不影响位置同步。
        if (currentChapterIndex >= 0 && currentChapterIndex < chapterList.size()) {
            long cid = chapterList.get(currentChapterIndex).getId();
            if (cid > 0) {
                p.setChapterId(cid);
            }
        }
        p.setChapterIndex(currentChapterIndex);
        p.setScrollPosition(currentPageInChapter);
        // 阅读偏好（字号/夜间/背景色）刻意不上传：App 的偏好只存本地 read_settings，
        // 后端进度记录是 web 与 App 共用的，上传这些字段会覆盖 web 端的偏好设置。
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
        RetrofitClient.getApiService().saveExternalProgress("Bearer " + LoginHelper.getToken(this), rb)
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
        // ✅ 加载自动翻页设置（间隔与开关状态都持久化）
        autoPageEnabled = sp.getBoolean("auto_page", false);
        autoPageInterval = sp.getInt("auto_page_interval", 5000);
        // 展示名也要持久化：否则重启后未打开字体弹窗时只能回退到 cssName
        currentFontDisplay = sp.getString("font_display", "默认字体");
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
                // ✅ 保存自动翻页设置
                .putBoolean("auto_page", autoPageEnabled)
                .putInt("auto_page_interval", autoPageInterval)
                .putString("font_display", currentFontDisplay)
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

    // ==================== 背景派生配色（上下导航栏 / 浮窗跟随当前背景色） ====================
    // 目标：导航栏与所有浮窗的底色不再是「日间白 / 夜间黑」两个写死值，而是由当前选中的
    // 背景色派生出「比它深一点」的同色系底色；文字与图标按背景明暗自动切换深浅，
    // 于是选黑色背景时天然等价于夜间模式，选牛皮纸/护眼绿时导航条也跟着变成同色系深色。
    //
    // 派生规则（压暗强度：浅色 6%，深色 20% —— 深色背景等比压暗几乎看不出差别，需额外补偿 14%）：
    //   L  = 相对亮度(base)；isDark = L < 0.42
    //   c1 = mix(base, #000, isDark ? 0.20 : 0.06)      // 上下导航 + 浮窗主背景
    //   c2 = mix(c1,   #000, isDark ? 0.20 : 0.12)      // 二级底（分组底 / 胶囊 / TabLayout）
    //   ln = isDark ? mix(c1, #FFF, 0.13) : mix(c1, #000, 0.16)   // 分隔线
    //   t1 = isDark ? #FFFFFF : #1D1D1F                  // 主文字 & 图标 tint
    //   t2 = isDark ? #98989D : #8E8E93                  // 次文字
    //   ac = isDark ? #0A84FF : #007AFF                  // 强调色（进度条 / 返回键）
    private static final float CHROME_SHADE       = 0.06f;  // 浅色背景压暗比例
    private static final float CHROME_SHADE_DARK  = 0.20f;  // 深色背景压暗比例（6% + 14% 补偿）
    private static final float CHROME_SHADE_2     = 0.12f;  // 二级底（浅色）
    private static final float CHROME_SHADE_2_DARK= 0.20f;  // 二级底（深色）
    private static final double DARK_LUM_THRESHOLD = 0.42;  // 低于该亮度即视为深色背景

    // 目录浮窗「定位当前章节」悬浮按钮：圆盘 = 浮窗主底向主文字色微调 TOC_LOCATE_DISC_TINT_RATIO，
    // 再叠加 TOC_LOCATE_DISC_ALPHA 的半透明；图标用主文字色（浅色主题即近黑，夜间自动转近白）。
    private static final float TOC_LOCATE_DISC_TINT_RATIO = 0.10f;
    private static final int   TOC_LOCATE_DISC_ALPHA      = 0xC0;

    private static int sChrome1 = 0xFFEFEFEF;   // 当前派生：导航 / 浮窗主底（白底 6% 压暗的初值）
    private static int sChrome2 = 0xFFD2D2D2;   // 当前派生：二级底
    private static int sLine    = 0xFFC8C8C8;   // 当前派生：分隔线
    private static int sText1   = 0xFF1D1D1F;   // 当前派生：主文字
    private static int sText2   = 0xFF8E8E93;   // 当前派生：次文字
    private static int sAccent  = 0xFF007AFF;   // 当前派生：强调色
    private static int sNavText = 0xFF1D1D1F;   // 当前派生：上下导航栏文字（浅色模式用近黑，深色模式沿用浅灰）
    private static boolean sIsDark = false;
    /** 日间⇄夜间切换的过渡时长（毫秒）。对应预览中选中的「平滑色彩渐变 / 1000ms」。 */
    private static final long NIGHT_TRANSITION_MS = 1000;
    // 历史上派发过的派生色集合：浮窗会被反复重着色（换背景 / 切夜间 / 换页），
    // 单靠「上一次」的 sPrev* 在「连续派生两次但只染一次」的场景会漏掉陈旧底色，
    // 用集合记录所有派发过的色值即可无条件收敛到当前色。
    private static final java.util.Set<Integer> sIssuedChrome1 = new java.util.HashSet<>();
    private static final java.util.Set<Integer> sIssuedChrome2 = new java.util.HashSet<>();
    private static final java.util.Set<Integer> sIssuedLine    = new java.util.HashSet<>();

    /**
     * 当前前台活跃的 ReadActivity 实例（仅在 onResume/onPause 维护）。
     * <p>
     * 用于 themeViewTree 的宿主判定：只有当前 Activity 是某个 ReadActivity 实例时，浮窗/列表
     * 才能被派生配色覆盖。书城/书架/设置等外部页在前台时，sActiveInstance == null，
     * 任何迟到的 themeViewTree 调用都会被静默忽略，杜绝「书城等也跟随背景色」。
     * <p>
     * 设计要点：用「前台实例引用」而非上一版的 isReaderHost(ctx)。后者在 PopupWindow +
     * ViewPager2 嵌套时 itemView.getContext() 的 ContextWrapper 层级与判定不一致，
     * 导致阅读器自己的目录/书签浮窗也被错判拒绝——上一轮反馈「目录书签页不跟随」。
     * 现在改为 Activity 生命周期级别的判定：PopupWindow 不会触发 onPause，
     * 所以目录浮窗打开时 ReadActivity 仍在 onResume → sActiveInstance 仍指向自己 → 染色成功。
     */
    private static volatile ReadActivity sActiveInstance;

    /** 当前派生配色是否为深色（决定文字/图标用浅色还是深色）。 */
    public static boolean isChromeDark() { return sIsDark; }

    // ---- 派生色取值接口：供浮窗内 Fragment / Adapter 直接取用，避免再写死 #007AFF 之类 ----
    public static int getAccentColor()            { return sAccent;  }  // 强调色（当前章节 / 进度条）
    public static int getTextPrimaryColor()       { return sText1;   }  // 主文字
    public static int getTextSecondaryColor()     { return sText2;   }  // 次文字
    public static int getChromeBgColor()          { return sChrome1; }  // 浮窗主底
    public static int getChromeBgSecondaryColor() { return sChrome2; }  // 二级底 / 分组底
    public static int getChromeLineColor()        { return sLine;    }  // 分隔线

    /** sRGB 相对亮度（WCAG 公式），用于判定背景明暗。 */
    private static double relativeLuminance(int color) {
        double r = ((color >> 16) & 0xFF) / 255.0;
        double g = ((color >> 8) & 0xFF) / 255.0;
        double b = (color & 0xFF) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** 按 a 的比例把 c1 混向 c2（a=0 得 c1，a=1 得 c2）。 */
    private static int mixColors(int c1, int c2, float a) {
        int r = (int) ((((c1 >> 16) & 0xFF) + ((((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * a)));
        int g = (int) ((((c1 >> 8) & 0xFF) + ((((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * a)));
        int b = (int) (((c1 & 0xFF) + (((c2 & 0xFF) - (c1 & 0xFF)) * a)));
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 取派生配色的基准色 = 页面实际显示的背景色（effectiveBgBase）。
     * 纹理背景用其预览底色，夜间模式固定 #1A1A1A。
     */
    private int getChromeBaseColor() {
        return effectiveBgBase;
    }

    /** 由当前背景色重新计算派生配色（不动 UI，仅更新静态色值）。 */
    private void updateChromePalette() {
        int base = getChromeBaseColor();
        boolean dark = relativeLuminance(base) < DARK_LUM_THRESHOLD;

        sIsDark  = dark;
        sChrome1 = mixColors(base, Color.BLACK, dark ? CHROME_SHADE_DARK : CHROME_SHADE);
        sChrome2 = mixColors(sChrome1, Color.BLACK, dark ? CHROME_SHADE_2_DARK : CHROME_SHADE_2);
        sLine    = dark ? mixColors(sChrome1, Color.WHITE, 0.13f)
                        : mixColors(sChrome1, Color.BLACK, 0.16f);
        sText1   = dark ? Color.WHITE : Color.parseColor("#1D1D1F");
        sText2   = dark ? Color.parseColor("#98989D") : Color.parseColor("#8E8E93");
        // 上下导航栏文字：浅色模式统一用近黑（原灰色 sText2 改黑），深色模式沿用浅灰保证对比度
        sNavText = dark ? sText2 : Color.parseColor("#1D1D1F");
        sAccent  = dark ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");

        // 记录本轮派发过的色值，供后续重着色时识别「陈旧派生色」
        sIssuedChrome1.add(sChrome1);
        sIssuedChrome2.add(sChrome2);
        sIssuedLine.add(sLine);
    }

    /**
     * 应用派生配色：状态栏图标明暗 + 上下导航栏 + 图标/文字，并让已显示的浮窗实时跟随。
     * 取代原先只按 isNightMode 二值切换的 applyNightModeToNavOnly()。
     */
    private void applyChromeTheme() {
        updateChromePalette();
        applyChromeColorsWith(sChrome1, sChrome2, sLine, sText1, sText2, sNavText, sAccent, sIsDark);
        // 已显示的浮窗（目录 / 设置 / 更多 / 背景 / 字体）实时跟随
        themeShowingPopups();
    }

    /**
     * 用给定的一组配色刷新上下导航栏、图标、文字与状态栏图标明暗。
     * 供「平滑色彩渐变」过渡逐帧调用（传入插值后的中间色）。
     */
    private void applyChromeColorsWith(int chrome1, int chrome2, int line,
                                       int text1, int text2, int navText, int accent, boolean dark) {
        // 状态栏图标：浅色背景用深色图标，深色背景用浅色图标
        View decorView = getWindow().getDecorView();
        int flags = decorView.getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decorView.setSystemUiVisibility(flags);

        layoutTopNav.setBackgroundColor(chrome1);
        layoutBottomNav.setBackgroundColor(chrome1);
        tvToolbarTitle.setTextColor(text1);

        // 底栏顶部 0.5dp 分隔线（布局里的第一个子 View）
        if (layoutBottomNav instanceof ViewGroup && ((ViewGroup) layoutBottomNav).getChildCount() > 0) {
            View sep = ((ViewGroup) layoutBottomNav).getChildAt(0);
            if (sep != null) sep.setBackgroundColor(line);
        }
        // 章节进度条（液态滑块）：从面板底色派生中性配色，随日/夜平滑渐变逐帧刷新
        try {
            liquidSlider.setChrome(chrome1, text1, dark);
        } catch (Throwable ignored) { }

        ivNightModeIcon.setImageResource(dark ? R.drawable.ic_day : R.drawable.ic_night);
        tvNightModeText.setText(dark ? "日间" : "夜间");
        tvNightModeText.setTextColor(navText);

        setIconsForThemeWith(text1, accent, navText, dark);
    }

    private void setIconsForTheme() {
        setIconsForThemeWith(sText1, sAccent, sNavText, sIsDark);
    }

    private void setIconsForThemeWith(int text1, int accent, int navText, boolean dark) {
        // 图标 drawable 自身颜色会被 ImageView 的 tint 覆盖，因此仅切换 src 无效，
        // 必须同步切换 tint 才能让图标在深色背景上都清晰可见。
        // 配色不再按日/夜二值写死，而是取当前背景派生出的主文字色与强调色。
        int tint = text1;

        ImageView ivBack = findViewById(R.id.iv_back);
        ImageView ivMore = findViewById(R.id.iv_more);
        ivBack.setImageResource(dark ? R.drawable.ic_back_white : R.drawable.ic_back_black);
        ivMore.setImageResource(dark ? R.drawable.ic_more_vert_white : R.drawable.ic_more_vert_black);
        // 返回键使用强调色，其余图标与主文字同色
        ivBack.setImageTintList(ColorStateList.valueOf(accent));
        ivMore.setImageTintList(ColorStateList.valueOf(tint));

        ImageView ivCatalog = findViewById(R.id.iv_catalog_icon);
        ImageView ivSettings = findViewById(R.id.iv_settings_icon);
        ivCatalog.setImageResource(dark ? R.drawable.ic_list_white : R.drawable.ic_list_black);
        ivSettings.setImageResource(dark ? R.drawable.ic_settings_white : R.drawable.ic_settings_black);
        ivCatalog.setImageTintList(ColorStateList.valueOf(tint));
        ivSettings.setImageTintList(ColorStateList.valueOf(tint));

        // 夜间/日间切换图标自身也要跟随 tint（XML 中 tint 固定为深色，夜间会看不清）
        ivNightModeIcon.setImageTintList(ColorStateList.valueOf(tint));

        int textColor = navText;
        ((TextView) findViewById(R.id.tv_prev_chapter)).setTextColor(textColor);
        ((TextView) findViewById(R.id.tv_next_chapter)).setTextColor(textColor);
        ((TextView) findViewById(R.id.tv_catalog_text)).setTextColor(textColor);
        ((TextView) findViewById(R.id.tv_settings_text)).setTextColor(textColor);
        // 主题切换会重置图标 tint；若此时设置浮窗仍开着，需把「设置」图标的选中态补回来
        if (settingsPopupWindow != null && settingsPopupWindow.isShowing()) setSettingsNavActive(true);
    }

    // ==================== 日间 ⇄ 夜间 平滑过渡（平滑色彩渐变） ====================
    /**
     * 带「平滑色彩渐变」过渡地切换日/夜间：
     * <ul>
     *   <li>原生上下导航栏：用 {@link ValueAnimator} + {@link ArgbEvaluator} 在「旧配色→新配色」间逐帧补间；</li>
     *   <li>WebView 阅读区：调用 JS {@code animateNightMode(...)}，由 Canvas 逐帧重绘背景与文字色；</li>
     *   <li>过渡时长见 {@link #NIGHT_TRANSITION_MS}（默认 1000ms）。</li>
     * </ul>
     * 浮窗（目录/设置等）在过渡结束后再统一刷新，避免逐帧重染开销。
     */
    private void animateNightModeToggle() {
        final boolean toNight = !isNightMode;

        // ---- WebView 阅读区起止颜色 ----
        final String dayBg  = dayWebBgHex();
        final String dayTx  = dayWebTextHex();
        final String nightBg = "#1A1A1A";
        final String nightTx = "#AAAAAA";
        final String fromBg, fromTx, toBg, toTx;
        if (toNight) { fromBg = dayBg;  fromTx = dayTx;  toBg = nightBg; toTx = nightTx; }
        else         { fromBg = nightBg; fromTx = nightTx; toBg = dayBg;  toTx = dayTx;  }

        // ---- 原生导航栏：先快照「旧」配色，翻转后再算「新」配色 ----
        final int fromC1 = sChrome1, fromC2 = sChrome2, fromLine = sLine;
        final int fromT1 = sText1,   fromT2 = sText2,   fromNav = sNavText, fromAcc = sAccent;
        final boolean fromDark = sIsDark;

        isNightMode = toNight;
        effectiveBgBase = toNight ? Color.parseColor("#1A1A1A") : resolveBaseForMode(currentBgColor);
        updateChromePalette(); // 此时静态字段 = 目标配色
        final int toC1 = sChrome1, toC2 = sChrome2, toLine = sLine;
        final int toT1 = sText1,   toT2 = sText2,   toNav = sNavText, toAcc = sAccent;
        // 动画期间把静态字段回退为「旧」，避免其它读取方（浮窗/Getter）出现半成品
        sChrome1 = fromC1; sChrome2 = fromC2; sLine = fromLine;
        sText1 = fromT1;   sText2 = fromT2;   sNavText = fromNav; sAccent = fromAcc; sIsDark = fromDark;

        // 状态栏图标按目标明暗立即切换（系统层不做补间）
        applyChromeColorsWith(fromC1, fromC2, fromLine, fromT1, fromT2, fromNav, fromAcc, toNight);

        // ---- WebView 阅读区补间（逐帧重绘）----
        String js = "animateNightMode(" + toNight + "," + NIGHT_TRANSITION_MS + ",'"
                + fromBg + "','" + fromTx + "','" + toBg + "','" + toTx + "')";
        webView.evaluateJavascript(js, null);

        // ---- 原生导航栏补间 ----
        final ArgbEvaluator eval = new ArgbEvaluator();
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(NIGHT_TRANSITION_MS);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            applyChromeColorsWith(
                    (int) eval.evaluate(t, fromC1, toC1),
                    (int) eval.evaluate(t, fromC2, toC2),
                    (int) eval.evaluate(t, fromLine, toLine),
                    (int) eval.evaluate(t, fromT1, toT1),
                    (int) eval.evaluate(t, fromT2, toT2),
                    (int) eval.evaluate(t, fromNav, toNav),
                    (int) eval.evaluate(t, fromAcc, toAcc),
                    toNight);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                // 落定为目标配色
                sChrome1 = toC1; sChrome2 = toC2; sLine = toLine;
                sText1 = toT1;   sText2 = toT2;   sNavText = toNav; sAccent = toAcc; sIsDark = toNight;
                applyChromeTheme(); // 完整刷新（含图标/状态栏/浮窗）
                // 退出夜间模式后恢复当前选中的背景纹理/纯色（与原逻辑一致）
                if (!toNight) applyBackgroundColorToWebView(currentBgColor);
                saveReadingPreferences();
            }
        });
        anim.start();
    }

    /** 当前选中背景（日间）在 WebView 中的底色十六进制串。 */
    private String dayWebBgHex() {
        int m = currentBgColor;
        if (m >= 10) {
            int tex = m - 10;
            if (tex >= 0 && tex < BG_TEXTURE_PREVIEW_COLORS.length) return toHex(BG_TEXTURE_PREVIEW_COLORS[tex]);
            return "#FFFFFF";
        }
        if (m < 0 || m >= BG_COLORS.length) m = 0;
        return BG_COLORS[m];
    }

    /** 当前选中背景（日间）在 WebView 中的文字色十六进制串。 */
    private String dayWebTextHex() {
        int m = currentBgColor;
        if (m >= 10) return "#1D1D1F"; // 纹理模式统一用近黑文字
        if (m < 0 || m >= BG_TEXT_COLORS.length) m = 0;
        return BG_TEXT_COLORS[m];
    }

    /** 打包色值 → "#RRGGBB"（供 JS 调用）。 */
    private static String toHex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    // ==================== 浮窗配色跟随当前背景色 ====================
    // 浮窗布局里写死的是 iOS 浅色（ios_bg / ios_bg_grouped / ios_separator / ios_text_*），
    // 之前靠一张固定的「浅↔深」映射表在夜间模式下整体翻色，与具体背景色无关。
    // 现在改为映射到「由当前背景色派生出的配色」（见 updateChromePalette），
    // 于是护眼绿背景得到偏绿的导航/浮窗底，黑色背景得到夜间效果。
    // 旧映射表里的日/夜两套硬编码色仍然保留在识别列表中，保证历史颜色也能被正确收敛。
    private static final int LEGACY_NIGHT_BG_PRIMARY   = 0xFF000000;
    private static final int LEGACY_NIGHT_BG_SECONDARY = 0xFF1C1C1E;
    private static final int LEGACY_NIGHT_DIVIDER      = 0xFF38383A;
    private static final int LEGACY_CAPSULE_NORMAL     = 0xFF2C2C2E;
    private static final int LEGACY_CAPSULE_INNER      = 0xFF48484A;
    private static final int LEGACY_CAPSULE_PRESSED    = 0xFF3A3A3C;
    private static final int LEGACY_DAY_TEXT_SECONDARY = 0xFF8E8E93;
    private static final int LEGACY_NIGHT_TEXT_SECONDARY = 0xFF98989D;

    /**
     * 递归为浮窗视图树应用当前派生配色：背景、分割线、文字、TabLayout。
     * <p>宿主判定：当前台 Activity 不是某个 ReadActivity 实例时（例如书城/书架/设置在前台，
     * 而某个迟到的异步回调仍想调本方法），本方法会直接 return，杜绝外部页被染色。
     * <p>判定依据是 {@link #sActiveInstance}（生命周期维护），而不是 root.getContext() 的
     * ContextWrapper 链——后者在 PopupWindow + ViewPager2 嵌套时不可靠。
     */
    public static void themeViewTree(@Nullable View root) {
        if (root == null) return;
        ReadActivity owner = sActiveInstance;
        if (owner == null || owner.isFinishing() || owner.isDestroyed()) return;
        // ✅ 二次校验：owner 必须仍在 onResume（防御 paused 状态下的漏判）；
        // ✅ root 的 Context 必须能从 ContextWrapper 链回溯到 owner，否则拒绝染色。
        // 阅读器内的 popup（chapter/settings/moreMenu 等）虽然走独立 Window，
        // 但它们的 Context 都是从 ReadActivity(this) 出发创建的，链上一定能找到 owner。
        // BookDetailActivity 等外部页面的 view，Context 链只能回到 BookDetailActivity 自身，
        // 永远找不到 owner → 被拒绝，杜绝「阅读器外页被染色」。
        if (!owner.activityResumed) return;
        if (!isContextTraceableTo(root, owner)) return;
        applyThemeRecursive(root);
    }

    /**
     * 沿 ContextWrapper 链向上追溯 root.getContext()，看是否能找到 owner Activity。
     * 命中则说明 root 是 owner 这条线创建的（阅读器内的 view 或内部 popup）；
     * 追不到说明 root 来自外部 Activity（书城 / 书架 / 详情等），必须拒绝。
     */
    private static boolean isContextTraceableTo(View root, ReadActivity owner) {
        try {
            Context ctx = root.getContext();
            int safety = 0;
            while (ctx != null && safety++ < 16) {
                if (ctx == owner) return true;
                if (!(ctx instanceof ContextWrapper)) break;
                Context base = ((ContextWrapper) ctx).getBaseContext();
                if (base == ctx) break;
                ctx = base;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyThemeRecursive(View view) {
        // 打了 tag_keep_own_color 的子树（如背景色块）必须显示自己的真实颜色，跳过
        if (Boolean.TRUE.equals(view.getTag(R.id.tag_keep_own_color))) return;

        // 0) 目录浮窗「定位当前章节」悬浮按钮：半透明圆盘 + 主文字色图标。
        //    圆盘底色由浮窗主底向主文字色微调（浅色主题=奶油白盘+深图标、夜间=深灰盘+浅图标），
        //    因此不能走下面那张「已知色 → 派生色」的映射表（自定义混色不在表内），
        //    这里直接按当前调色板生成，夜间切换时由 themeShowingPopups → 本方法实时重刷。
        if (view.getId() == R.id.btn_toc_locate) {
            try {
                android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
                disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                int base = mixColors(sChrome1, sText1, TOC_LOCATE_DISC_TINT_RATIO);
                disc.setColor((TOC_LOCATE_DISC_ALPHA << 24) | (base & 0x00FFFFFF));
                view.setBackground(disc);
                if (view instanceof ImageView) {
                    ((ImageView) view).setImageTintList(ColorStateList.valueOf(sText1));
                }
            } catch (Throwable ignored) { }
            return;
        }

        // 1) 背景：纯色 / shape / selector / layer-list 递归重着色
        //    本项目的夜间模式是「手动」的（未调用 AppCompatDelegate），
        //    因此 res/drawable-night 资源限定符不会生效；胶囊这类 <selector> 必须在代码里
        //    逐个子 <shape> 重着色，否则深色背景下仍是浅底 + 白字 = 内容不可见。
        if (themeDrawable(view.getBackground())) view.invalidate();

        // 1b) SeekBar 的进度轨道不是 background，需单独重着色
        if (view instanceof android.widget.SeekBar) {
            try {
                if (themeDrawable(((android.widget.SeekBar) view).getProgressDrawable())) {
                    view.invalidate();
                }
            } catch (Throwable ignored) { }
        }

        // 2) 文字颜色
        if (view instanceof TextView && !(view instanceof android.widget.Button)) {
            TextView tv = (TextView) view;
            int tc = tv.getCurrentTextColor();
            int mapped = mapTextColor(tc);
            if (mapped != tc) tv.setTextColor(mapped);
        }

        // 3) TabLayout：标签栏背景与文字（不递归进其内部 tab 子视图，避免与 setTabTextColors 冲突）
        if (view instanceof TabLayout) {
            TabLayout tl = (TabLayout) view;
            tl.setBackgroundColor(sChrome2);
            tl.setTabTextColors(sText2, sAccent);
            tl.setSelectedTabIndicatorColor(sAccent);
            return;
        }

        // 4) 递归子节点（RecyclerView 仅返回已 attach 的可见项，遍历成本可控）
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyThemeRecursive(vg.getChildAt(i));
            }
        }
    }

    /**
     * 把布局里写死的浅色体系映射到当前派生配色。
     * 除了原始色值，也识别「历史上派发过的派生值」（sIssuedChrome*），
     * 这样反复重着色时不依赖调用顺序，也不会残留旧底色。
     */
    private static int mapBgColor(int c) {
        // 主背景：ios_bg / 旧夜间主底 / 任意一轮派发过的主底
        if (c == 0xFFFFFFFF || c == 0xFFFAFAFA || c == 0xFFF5F5F5
                || c == LEGACY_NIGHT_BG_PRIMARY || sIssuedChrome1.contains(c)) {
            return sChrome1;
        }
        // 二级底：ios_bg_grouped / 旧夜间次底 / 任意一轮派发过的二级底
        if (c == 0xFFF2F2F7 || c == 0xFFEFEFF4
                || c == LEGACY_NIGHT_BG_SECONDARY || c == LEGACY_CAPSULE_NORMAL
                || sIssuedChrome2.contains(c)) {
            return sChrome2;
        }
        // 分隔线：ios_separator / 旧夜间分割线 / 任意一轮派发过的分隔线
        if (c == 0xFFE5E5EA || c == 0xFFC6C7CC || c == 0xFFE0E0E0 || c == 0xFFEEEEEE
                || c == LEGACY_NIGHT_DIVIDER || c == LEGACY_CAPSULE_PRESSED
                || sIssuedLine.contains(c)) {
            return sLine;
        }
        // 胶囊内胆（白胶囊）：与浮窗主底同级
        if (c == LEGACY_CAPSULE_INNER) return sChrome1;
        return c;
    }

    /** 文字颜色：主文字 / 次文字 / 三级文字 → 派生文字色。 */
    private static int mapTextColor(int c) {
        int rgb = c & 0x00FFFFFF;   // 去掉 alpha：?android:attr/textColorSecondary 会解析出带透明度的灰
        if (rgb == 0x1D1D1F || rgb == 0x333333 || rgb == 0x000000 || rgb == 0xFFFFFF) return sText1;
        if (rgb == 0x8E8E93 || rgb == 0x98989D || rgb == 0xC7C7CC
                || rgb == 0x666666 || rgb == 0x999999) return sText2;
        // 通用兜底：布局里用 ?android:attr/textColorPrimary / textColorSecondary 时，
        // 解析出来的系统灰阶色（#000000、#8A000000…）不在上面的清单里，
        // 会导致书签条目的标题/预览在深色背景上依旧是深色字（看起来"没跟随"）。
        // 规则：低饱和（灰阶）文字 → 近黑/近白归主文字、中间灰归次文字；
        //      有彩色（橙色备注 #FF6600、红色 #FF4444 等强调色）保持原样。
        float[] hsv = new float[3];
        Color.colorToHSV(0xFF000000 | rgb, hsv);
        if (hsv[1] <= 0.25f) {
            return (hsv[2] >= 0.85f || hsv[2] <= 0.45f) ? sText1 : sText2;
        }
        return c;
    }

    /**
     * 对 drawable 重着色，并递归处理 selector / layer-list / inset / scale 的子图。
     * 全部走同一张派生色表（胶囊与面板已在派生规则里区分层级：胶囊底 = 二级底）。
     * @return 是否发生变化
     */
    private static boolean themeDrawable(Drawable d) {
        if (d == null) return false;
        // ✅ 关键修复：先 mutate() 脱离「共享 ConstantState」。
        // 从同一资源（@color/ios_bg、@drawable/bg_xxx 等）inflate 出来的 Drawable 在
        // 进程内共享同一份 ConstantState；直接 setColor() 会改到全局共享状态，导致书城 /
        // 书架 / 设置 / 详情等所有使用 ios_bg 的界面背景被「染色」跟随阅读器背景。
        // mutate() 让本实例拿到一份独立状态，着色只作用于当前浮窗，不再泄漏到全 App。
        d = d.mutate();
        boolean changed = false;
        try {
            if (d instanceof ColorDrawable) {
                int c = ((ColorDrawable) d).getColor();
                int mapped = mapBgColor(c);
                if (mapped != c) {
                    ((ColorDrawable) d).setColor(mapped);
                    changed = true;
                }
            } else if (d instanceof GradientDrawable) {
                android.content.res.ColorStateList csl = ((GradientDrawable) d).getColor(); // API29+
                if (csl != null) {
                    int c = csl.getDefaultColor();
                    int mapped = mapBgColor(c);
                    if (mapped != c) {
                        ((GradientDrawable) d).setColor(mapped);
                        changed = true;
                    }
                }
            } else if (d instanceof android.graphics.drawable.StateListDrawable) {
                // <selector>：逐个重着色子 <shape>（常态 / 按下态）
                android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
                if (cs instanceof android.graphics.drawable.DrawableContainer.DrawableContainerState) {
                    android.graphics.drawable.DrawableContainer.DrawableContainerState dcs =
                            (android.graphics.drawable.DrawableContainer.DrawableContainerState) cs;
                    for (int i = 0; i < dcs.getChildCount(); i++) {
                        changed |= themeDrawable(dcs.getChild(i));
                    }
                }
            } else if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                    changed |= themeDrawable(ld.getDrawable(i));
                }
            } else if (d instanceof android.graphics.drawable.InsetDrawable) {
                changed |= themeDrawable(((android.graphics.drawable.InsetDrawable) d).getDrawable());
            } else if (d instanceof android.graphics.drawable.ScaleDrawable) {
                changed |= themeDrawable(((android.graphics.drawable.ScaleDrawable) d).getDrawable());
            }
        } catch (Throwable ignored) { }
        return changed;
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
        if (pw != null && pw.isShowing()) themeViewTree(pw.getContentView());
    }

    /**
     * 设置系列浮窗的定位偏移：让浮窗底缘停在底部「目录/夜间/设置」这一行（第二行）的上沿，
     * 从而盖住其上方的「上一章/下一章」行，同时露出底栏本行、不被覆盖也不变暗。
     * 底栏第二行固定高度 60dp，故偏移取该值（含 0.5dp 分隔线误差可忽略）。
     */
    private int getNavBarHeightPx() {
        return (int) (60 * getResources().getDisplayMetrics().density);
    }

    /**
     * 设置浮窗开启时：「设置」图标由描边齿轮切换为<b>实心齿轮</b>（不加圆底），
     * 齿轮颜色与平时保持一致（不随之变色）；底部「设置」文字颜色也不变。
     * 关闭时恢复为描边齿轮 + 主题次色文字。
     */
    private void setSettingsNavActive(boolean active) {
        ImageView iv = findViewById(R.id.iv_settings_icon);
        TextView tv = findViewById(R.id.tv_settings_text);
        if (iv == null || tv == null) return;

        // 齿轮颜色不随选中态改变：日间跟随主文字色（近黑），夜间跟随其他图标用白色
        int gearColor = sText1;

        iv.setBackground(null);       // 不加圆底
        iv.setPadding(0, 0, 0, 0);
        iv.clearColorFilter();
        iv.setImageResource(active ? R.drawable.ic_settings_filled
                : (sIsDark ? R.drawable.ic_settings_white : R.drawable.ic_settings_black));
        // 用 setImageTintList（与 XML 的 app:tint 同通道），setColorFilter 会被 AppCompat tint 覆盖
        iv.setImageTintList(ColorStateList.valueOf(gearColor));

        if (!active) {
            tv.setTextColor(sNavText);
        }
        // active 时文字颜色保持不变（不改成蓝色）
    }

    @SuppressLint("InflateParams")
    private void showSettingsDialog() {
        // 全屏透明根布局：scrim 负责点击上方区域关闭；面板贴在窗口下沿并贴着
        // 「目录/夜间/设置」行的上沿。裁剪由**窗口下沿**完成（首帧会把窗口高度收缩到该行上沿），
        // 窗口边界是唯一稳定生效的裁剪线，面板向下越界的部分必然被裁掉 ——
        // 视觉上即「从该行里滑出/收回」，而不是整窗位移导致从屏幕底部飞入飞出、并盖住底栏。
        // 用「跟手下滑关闭」容器作为根布局：它能抢在子 View（ViewPager2/按钮）之前拦截下拉手势，
        // 且在按下点位于面板之外时负责点击关闭。
        SwipeDismissLayout root = new SwipeDismissLayout(this);

        View scrim = new View(this);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setOnClickListener(v -> dismissSettingsAnimated());

        FrameLayout panelHost = new FrameLayout(this);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 面板直接贴「窗口」下沿：OnPreDraw 里会把窗口高度收缩到「目录/夜间/设置」行上沿，
        // 于是面板底缘自然停在该行上沿。裁剪由窗口边界完成——面板向下越过窗口下沿的部分
        // 一定被裁掉，因此视觉上必然是从该行里滑出/收回，不再依赖父容器 clipChildren。
        hostLp.gravity = Gravity.BOTTOM;
        panelHost.setLayoutParams(hostLp);
        // 保留容器裁剪，与窗口裁剪形成双重保险
        panelHost.setClipChildren(true);

        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_reading_settings, panelHost, false);
        initSettingsPanel(popupView);
        themeViewTree(popupView);
        panelHost.addView(popupView);
        settingsPanelView = popupView;
        // 阅读设置面板停靠在「目录/夜间/设置」行上沿（窗口被裁到该行上沿），
        // 因此「弹窗 + 底部目录行」整体占满屏幕下半部分。为满足「弹窗 + 底部目录行 = 半屏」，
        // 面板高度 = 半屏 − 底部目录行高度，位置不变（仍贴底栏上沿）。
        android.util.DisplayMetrics rsDm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(rsDm);
        int rsBottomRowH = 0;
        View rsBottomRow = findViewById(R.id.layout_bottom_row);
        if (rsBottomRow != null && rsBottomRow.getHeight() > 0) {
            rsBottomRowH = rsBottomRow.getHeight();
        } else {
            rsBottomRowH = (int) (60f * rsDm.density); // 兜底：底部行固定 60dp
        }
        ViewGroup.LayoutParams rsLp = popupView.getLayoutParams();
        if (rsLp != null) {
            int panelH = rsDm.heightPixels / 2 - rsBottomRowH;
            if (panelH < rsDm.heightPixels / 4) panelH = rsDm.heightPixels / 4; // 安全下限
            rsLp.height = panelH;
            popupView.setLayoutParams(rsLp);
        }
        // 先隐藏，等窗口下沿收缩到该行上沿之后再播放入场，避免中间帧闪现
        popupView.setVisibility(View.INVISIBLE);

        root.addView(scrim);
        root.addView(panelHost);

        // 跟手拖拽平移 panelHost（外层容器）：向下拖动时被窗口下沿（该行上沿）裁掉，
        // 观感即「面版缩回该行」，拖过阈值后 dismiss；
        // 入场/收回动画平移 popupView（同样被窗口下沿裁掉 → 从该行里滑出/收回）。
        // 两者是不同对象，互不干扰，因此这里传 panelHost。
        root.setPanel(panelHost);
        root.setRetractView(popupView);

        // 跟手下滑关闭（面板整体可拖拽；已滑出后直接 dismiss，不再叠加收回动画）
        root.setDismissAction(() -> {
            if (settingsPopupWindow != null && settingsPopupWindow.isShowing()) settingsPopupWindow.dismiss();
        });

        settingsPopupWindow = new PopupWindow(root,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true);
        settingsPopupWindow.setAnimationStyle(0); // 取消整窗位移动画
        settingsPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 关键：弹窗是 focusable（模态）的，窗口高度又被收缩到「目录/夜间/设置」行上沿，
        // 于是这一整行都落在弹窗窗口「之外」。点这一行（含设置按钮）时事件根本到不了 Activity
        // 的按钮，而是被 PopupViewContainer 当成「点击弹窗外部」直接 dismiss() —— 无任何动画，
        // 表现为「直接消失」。这里抢在它之前接管窗口外触摸，统一走带动画的收回。
        settingsPopupWindow.setOutsideTouchable(true);
        settingsPopupWindow.setTouchInterceptor((v, event) -> {
            boolean outside = event.getAction() == android.view.MotionEvent.ACTION_OUTSIDE;
            if (!outside && event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                int x = (int) event.getX();
                int y = (int) event.getY();
                outside = x < 0 || y < 0 || x >= v.getWidth() || y >= v.getHeight();
            }
            if (outside) {
                dismissSettingsAnimated();
                return true; // 吃掉事件，阻止 PopupWindow 自行 dismiss（那是不带动画的）
            }
            return false;
        });
        // 先按全屏显示（保证能拿到稳定坐标系测量），首帧 pre-draw 里再把高度收缩到该行上沿
        settingsPopupWindow.showAtLocation(layoutBottomNav, Gravity.TOP, 0, 0);

        // 返回键：先播放收回动画再关闭
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                dismissSettingsAnimated();
                return true;
            }
            return false;
        });

        setSettingsNavActive(true);
        suspendAutoPage();
        mainHandler.removeCallbacks(hideNavRunnable);
        settingsPopupWindow.setOnDismissListener(() -> {
            settingsPanelDismissing = false;
            resetAutoHideTimer();
            resumeAutoPageIfSuspended();
            setSettingsNavActive(false);
            settingsPanelView = null;
        });

        // 入场：① 把弹窗窗口下沿收缩到「目录/夜间/设置」行上沿（窗口边界即裁剪线）；
        // ② 面板移到窗口下沿之外后滑上来。必须在首帧绘制「之前」完成，
        // 否则会先按最终位置绘制一帧（闪现）再跳下去滑上来，故用 OnPreDrawListener 而非 post()。
        final android.view.ViewTreeObserver.OnPreDrawListener enterListener =
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    private boolean windowSized = false;
                    private int heightBeforeResize = -1;

                    @Override
                    public boolean onPreDraw() {
                        int targetH = measureBottomRowTopInRoot(root);
                        if (!windowSized) {
                            windowSized = true;
                            heightBeforeResize = root.getHeight();
                            if (targetH > 0 && Math.abs(targetH - heightBeforeResize) > 1) {
                                try {
                                    // 收缩窗口：下沿 == 「目录/夜间/设置」行上沿
                                    settingsPopupWindow.update(0, 0,
                                            ViewGroup.LayoutParams.MATCH_PARENT, targetH);
                                } catch (Throwable ignored) { }
                                return true; // 等窗口新尺寸生效后的下一帧再播入场
                            }
                        } else if (targetH > 0 && heightBeforeResize > 0
                                && root.getHeight() == heightBeforeResize) {
                            // 兜底：窗口收缩未生效时，退回「容器下沿对齐该行上沿」的老办法
                            int margin = root.getHeight() - targetH;
                            if (margin > 0) {
                                FrameLayout.LayoutParams lp =
                                        (FrameLayout.LayoutParams) panelHost.getLayoutParams();
                                lp.bottomMargin = margin;
                                panelHost.setLayoutParams(lp);
                            }
                        }
                        root.getViewTreeObserver().removeOnPreDrawListener(this);
                        startSettingsPanelEnter(popupView);
                        return true;
                    }
                };
        root.getViewTreeObserver().addOnPreDrawListener(enterListener);
    }

    /**
     * 计算「目录/夜间/设置」行上沿在弹窗坐标系中的位置，即弹窗窗口应有的高度
     * （窗口下沿将落在该行上沿）。用两个 View 的屏幕坐标差换算，与状态栏 inset、
     * 是否存在系统导航栏全部无关；该行不可见/未测量到时退回「窗口高 - 60dp」。
     */
    private int measureBottomRowTopInRoot(View root) {
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        View bottomRow = findViewById(R.id.layout_bottom_row);
        if (bottomRow != null && bottomRow.getVisibility() == View.VISIBLE
                && bottomRow.getHeight() > 0) {
            int[] rowLoc = new int[2];
            bottomRow.getLocationOnScreen(rowLoc);
            int h = rowLoc[1] - rootLoc[1];
            if (h > 0) return h;
        }
        int fallback = root.getHeight() - getNavBarHeightPx();
        return fallback > 0 ? fallback : root.getHeight();
    }

    /** 设置面板入场：从窗口下沿（=「目录/夜间/设置」行上沿）由下往上滑出 */
    private void startSettingsPanelEnter(View panel) {
        panel.setVisibility(View.VISIBLE);
        int h = panel.getHeight();
        if (h <= 0) return;
        panel.setTranslationY(h);
        panel.animate()
                .translationY(0)
                .setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** 设置浮窗是否正在显示（用于底栏本行按钮的「先收起浮窗」判定） */
    private boolean isSettingsPopupShowing() {
        return settingsPopupWindow != null && settingsPopupWindow.isShowing();
    }

    /** 收回动画是否已在播放中：避免重复触发把动画 cancel 成「瞬间消失」 */
    private boolean settingsPanelDismissing = false;

    /** 设置面板收回：滑回「目录/夜间/设置」行内（被窗口下沿裁掉至消失）后再关闭浮窗 */
    private void dismissSettingsAnimated() {
        if (settingsPopupWindow == null || !settingsPopupWindow.isShowing()) return;
        if (settingsPanelDismissing) return; // 已在收回，忽略重复触发，保证动画完整播放
        final View panel = settingsPanelView;
        int h = panel != null ? panel.getHeight() : 0;
        if (panel == null || h <= 0) {
            settingsPopupWindow.dismiss();
            return;
        }
        settingsPanelDismissing = true;
        panel.animate().cancel();
        panel.animate()
                .translationY(h)
                .setDuration(240)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    settingsPanelDismissing = false;
                    if (settingsPopupWindow != null && settingsPopupWindow.isShowing()) {
                        settingsPopupWindow.dismiss();
                    }
                })
                .start();
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
        themeViewTree(popupView);
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
        long bid = safeBookId();

        // ===== 外站书：按用户隔离命名空间 + bm_<i>_ 键格式（与 PopupBookmarkFragment / ExternalSyncManager 一致）=====
        if (isExternalBook) {
            String st = currentBook != null && currentBook.getSourceType() != null ? currentBook.getSourceType() : "";
            String su = currentBook != null && currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "";
            SharedPreferences sp = getSharedPreferences(ExternalPrefs.bookmarkName(this), MODE_PRIVATE);
            int count = sp.getInt("bookmark_count", 0);
            String p = "bm_" + count + "_";
            sp.edit()
                    .putString(p + "sourceType", st)
                    .putString(p + "sourceBookId", su)
                    .putInt(p + "chapterIndex", currentChapterIndex)
                    .putString(p + "chapterTitle", chapterTitle)
                    .putInt(p + "scrollPosition", currentPageInChapter)
                    .putString(p + "preview", contentPreview)
                    .putString(p + "note", "")
                    .putLong(p + "time", System.currentTimeMillis())
                    .putInt("bookmark_count", count + 1)
                    .apply();

            Hint.show(this, "书签已添加");

            // 立即同步外站书签到服务器（用户主动操作，期望即时入库）
            ExternalSyncManager.getInstance(this).flushBookmarksOnly();
            return;
        }

        // ===== 本地/服务器书：只有 bookId>0 才保存 =====
        if (bid > 0) {
            SharedPreferences sp = getSharedPreferences("local_bookmarks_" + bid, MODE_PRIVATE);
            int count = sp.getInt("bookmark_count", 0);
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("bookmark_count", count + 1);
            editor.putInt("bookmark_chapterIndex_" + count, currentChapterIndex);
            editor.putString("bookmark_chapterTitle_" + count, chapterTitle);
            editor.putInt("bookmark_page_" + count, currentPageInChapter);
            editor.putString("bookmark_preview_" + count, contentPreview);
            editor.putLong("bookmark_time_" + count, System.currentTimeMillis());
            editor.apply();
            Hint.show(this, "书签已添加");
        } else {
            Hint.show(this, "暂不可用：当前书籍没有有效书签空间");
        }
    }
    
    /**
     * 添加网络书籍的书签（使用服务器 API）
     */
    private void addNetworkBookmark() {
        if (!LoginHelper.isLoggedIn(this)) {
            LoginHelper.requireLogin(this, "添加书签需要登录后操作", this::doAddNetworkBookmark);
            return;
        }
        doAddNetworkBookmark();
    }

    private void doAddNetworkBookmark() {
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        if (userId == 0) {
            // 理论上不会走到这里（已通过 LoginHelper 校验），仅作兜底
            Hint.show(this, "请先登录");
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
                    Hint.show(ReadActivity.this, "书签已添加");
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
                    Hint.show(ReadActivity.this, errMsg);
                }
            }
            @Override
            public void onFailure(@NonNull Call<ApiResponse<Bookmark>> call, @NonNull Throwable t) {
                android.util.Log.e("ReadActivity", "saveNetworkBookmark network error", t);
                Hint.show(ReadActivity.this, "网络错误：" + t.getMessage());
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

            // 设置圆角长方形背景色（R角长方形，与选中环匹配）
            int swatchRadius = (int) (8 * getResources().getDisplayMetrics().density);
            // 让选中环的圆角与色块完全一致（按 density 缩放，避免不同屏幕错位）
            try { GradientDrawable rd = (GradientDrawable) bgRings[i].getBackground(); rd.mutate(); rd.setCornerRadius(swatchRadius); } catch (Exception ignore) {}
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.RECTANGLE);
            circle.setCornerRadius(swatchRadius);
            circle.setColor(Color.parseColor(BG_COLORS[i]));
            bgSwatches[i].setBackground(circle);
            // 色块必须显示真实背景色，不能被「跟随背景色」重着色
            bgSwatches[i].setTag(R.id.tag_keep_own_color, true);
            if (bgRings[i] != null) bgRings[i].setTag(R.id.tag_keep_own_color, true);

            // 点击事件
            final int idx = i;
            bgSwatches[i].setOnClickListener(v -> {
                currentBgColor = idx;
                applyBackgroundColorToWebView(idx);
                applyChromeTheme();   // 导航栏 / 浮窗底色跟随新背景
                updateBgColorHighlight(bgSwatches, bgRings);
                saveReadingPreferences();
            });
        }
        // 初始高亮当前选中颜色（如果在主面板范围内）
        updateBgColorHighlight(bgSwatches, bgRings);

        // "更多 >"按钮
        view.findViewById(R.id.tv_bg_more).setOnClickListener(v -> showBgColorsDialog());

        // "切换字体 >"按钮（文字与字体跟随当前字体）
        TextView tvSwitchFont = view.findViewById(R.id.tv_switch_font);
        tvSwitchFont.setOnClickListener(v -> showFontsDialog());
        updateSwitchFontButton(tvSwitchFont);

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
                autoPageSuspended = false;
            }
            saveReadingPreferences();
        });

        // 点击区域只挂在「自动翻页」文字 + 开关这一组上：
        // 若挂整行，点击「更多阅读设置」左侧的空白（占位 View）也会误触开关。
        View autoToggle = view.findViewById(R.id.layout_auto_toggle);
        if (autoToggle != null) {
            autoToggle.setOnClickListener(v -> switchAutoPage.setChecked(!switchAutoPage.isChecked()));
        }

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
        int ringSize = swatchSize;
        int frameSize = (int) (52 * density);
        int marginEnd = (int) (8 * density);
        float ringCorner = 8 * density;

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

            // 色块（圆角长方形）
            View swatch = new View(this);
            FrameLayout.LayoutParams swatchLp = new FrameLayout.LayoutParams(swatchSize, swatchSize);
            swatchLp.gravity = android.view.Gravity.CENTER;
            swatch.setLayoutParams(swatchLp);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.RECTANGLE);
            circle.setCornerRadius(8 * density);
            circle.setColor(Color.parseColor(BG_COLORS[i]));
            if (BG_COLORS[i].equals("#FFFFFF")) circle.setStroke(2, Color.parseColor("#CCCCCC"));
            swatch.setBackground(circle);
            // 色块必须显示真实背景色，不能被「跟随背景色」重着色
            swatch.setTag(R.id.tag_keep_own_color, true);

            // 选中环
            View ring = new View(this);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
            ringLp.gravity = android.view.Gravity.CENTER;
            ring.setLayoutParams(ringLp);
            ring.setBackgroundResource(R.drawable.bg_color_ring);
            try { GradientDrawable rd = (GradientDrawable) ring.getBackground(); rd.mutate(); rd.setCornerRadius(ringCorner); } catch (Exception ignore) {}
            ring.setVisibility(i == currentBgColor ? View.VISIBLE : View.GONE);

            frame.addView(swatch);
            frame.addView(ring);

            // 点击选择
            final int idx = i;
            swatch.setOnClickListener(v -> {
                currentBgColor = idx;
                applyBackgroundColorToWebView(idx);
                applyChromeTheme();   // 导航栏 / 浮窗底色跟随新背景
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
                drawable.setCornerRadius(8 * density);
                swatch.setImageDrawable(drawable);
            } catch (Exception e) {
                // 加载失败时用预览色兜底（圆角长方形）
                GradientDrawable fallback = new GradientDrawable();
                fallback.setShape(GradientDrawable.RECTANGLE);
                fallback.setCornerRadius(8 * density);
                fallback.setColor(BG_TEXTURE_PREVIEW_COLORS[i]);
                swatch.setBackground(fallback);
            }

            // 选中环
            View ring = new View(this);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
            ringLp.gravity = android.view.Gravity.CENTER;
            ring.setLayoutParams(ringLp);
            ring.setBackgroundResource(R.drawable.bg_color_ring);
            try { GradientDrawable rd = (GradientDrawable) ring.getBackground(); rd.mutate(); rd.setCornerRadius(ringCorner); } catch (Exception ignore) {}
            int texModeIdx = 10 + i;
            ring.setVisibility(texModeIdx == currentBgColor ? View.VISIBLE : View.GONE);

            frame.addView(swatch);
            frame.addView(ring);

            // 点击选择纹理
            final int texIdx = texModeIdx;
            swatch.setOnClickListener(v -> {
                currentBgColor = texIdx;
                applyBackgroundColorToWebView(texIdx);
                applyChromeTheme();   // 导航栏 / 浮窗底色跟随新背景（纹理取预览底色）
                saveReadingPreferences();
                refreshPopupSelection(solidContainer, texContainer);
            });
            texRow.addView(frame);
        }

        // 关闭按钮（色块点击即生效，无需"确定"按钮）
        popupView.findViewById(R.id.iv_bg_popup_close).setOnClickListener(v -> {
            SwipeDismissLayout h = SwipeDismissLayout.findHost(v);
            if (h != null) h.dismissAnimated();
            else if (bgColorsPopupWindow != null) bgColorsPopupWindow.dismiss();
        });

        // ✅ 背景色选择浮窗外壳跟随主题（色块本身为实际背景色，不在映射表内故不会被改）
        themeViewTree(popupView);

        // 弹窗高度固定为屏幕一半，内容过多时由内部 ScrollView 滚动
        android.util.DisplayMetrics bgDm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(bgDm);
        int bgPopupHeight = bgDm.heightPixels / 2;

        // 以全屏透明窗口 + 跟手下滑容器承载面板（面板下移时不会被半屏窗口边界裁掉）
        bgColorsPopupWindow = showSwipeDismissPopup(popupView, bgPopupHeight, "bg");
        suspendAutoPage();
        mainHandler.removeCallbacks(hideNavRunnable);
        bgColorsPopupWindow.setOnDismissListener(() -> {
            resetAutoHideTimer();
            resumeAutoPageIfSuspended();
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
     * 字体网格卡片数据项
     */
    private static class FontCardItem {
        final String displayName;
        final String cssName;
        final boolean isBackend;
        final com.example.myapplication.bean.FontItem backendFont;

        FontCardItem(String displayName, String cssName, boolean isBackend,
                     com.example.myapplication.bean.FontItem backendFont) {
            this.displayName = displayName;
            this.cssName = cssName;
            this.isBackend = isBackend;
            this.backendFont = backendFont;
        }
    }

    /**
     * 显示字体选择弹窗
     */
    @SuppressLint({"InflateParams", "SetTextI18n"})
    private void showFontsDialog() {
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_fonts, null);
        LinearLayout container = popupView.findViewById(R.id.container_fonts);

        // 构建字体列表
        rebuildFontList(container);

        // 关闭按钮
        popupView.findViewById(R.id.iv_font_popup_close).setOnClickListener(v -> {
            SwipeDismissLayout h = SwipeDismissLayout.findHost(v);
            if (h != null) h.dismissAnimated();
            else if (fontsPopupWindow != null) fontsPopupWindow.dismiss();
        });

        themeViewTree(popupView);

        // 弹窗高度固定为屏幕一半，内容过多时由内部 ScrollView 滚动
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        int popupHeight = dm.heightPixels / 2;

        // 以全屏透明窗口 + 跟手下滑容器承载面板
        fontsPopupWindow = showSwipeDismissPopup(popupView, popupHeight, "font");
        suspendAutoPage();
        mainHandler.removeCallbacks(hideNavRunnable);
        fontsPopupWindow.setOnDismissListener(() -> {
            resetAutoHideTimer();
            resumeAutoPageIfSuspended();
        });
    }

    /**
     * 从后端获取字体列表。container 为 null 时仅用于后台预加载（不刷新弹窗 UI，
     * 但会在拿到列表后刷新「切换字体」按钮文案，避免未打开弹窗时显示 cssName）。
     */
    private void fetchFontsFromBackend(LinearLayout container) {
        RetrofitClient.getApiService().getFonts().enqueue(new retrofit2.Callback<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>>() {
            @Override
            public void onResponse(@NonNull retrofit2.Call<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> call,
                                   @NonNull retrofit2.Response<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getData() != null) {
                    backendFonts = response.body().getData();
                    runOnUiThread(() -> {
                        if (container != null) {
                            // 移除加载提示并重建 3 列网格
                            View loading = container.findViewWithTag("tv_loading");
                            if (loading != null) container.removeView(loading);
                            rebuildFontList(container);
                        }
                        // 列表就绪后刷新「切换字体」按钮：可能已从 cssName 解析出中文展示名
                        applyCurrentFontToSwitchButton();
                    });
                } else {
                    runOnUiThread(() -> {
                        if (container != null) {
                            View loading = container.findViewWithTag("tv_loading");
                            if (loading instanceof TextView) {
                                ((TextView) loading).setText("暂无在线字体");
                            }
                        }
                    });
                }
            }

            @Override
            public void onFailure(@NonNull retrofit2.Call<ApiResponse<java.util.List<com.example.myapplication.bean.FontItem>>> call,
                                  @NonNull Throwable t) {
                runOnUiThread(() -> {
                    if (container != null) {
                        View loading = container.findViewWithTag("tv_loading");
                        if (loading instanceof TextView) {
                            ((TextView) loading).setText("网络异常，请稍后再试");
                        }
                    }
                });
            }
        });
    }

    /**
     * 启动后后台预拉取字体列表：仅当当前选中的是在线字体且列表尚未加载时，
     * 这样即使从未打开过字体弹窗，「切换字体」按钮也能显示中文名而非 cssName。
     */
    private void preloadBackendFontsIfNeeded() {
        if (!FONT_SYSTEM_CSS.equals(currentFontFamily)
                && !FONT_DEFAULT_CSS.equals(currentFontFamily)
                && backendFonts.isEmpty()) {
            fetchFontsFromBackend(null);
        }
    }

    /**
     * 长按已下载字体 → 弹出删除确认对话框
     */
    private void showDeleteFontDialog(FontCardItem item, LinearLayout container) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除字体")
                .setMessage("确定要删除「" + item.displayName + "」吗？删除后需重新下载。")
                .setPositiveButton("删除", (d, w) -> deleteDownloadedFont(item, container))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 删除已下载的在线字体：删除本地文件 + 移除已下载记录 + 必要时回退当前字体
     */
    private void deleteDownloadedFont(FontCardItem item, LinearLayout container) {
        // 1. 删除内部存储中的字体文件
        java.io.File f = new java.io.File(getFilesDir(), "fonts/" + item.cssName + ".ttf");
        if (f.exists()) f.delete();
        // 2. 从已下载集合移除
        downloadedFonts.remove(item.cssName);
        // 3. 若当前正在使用该字体，回退到默认字体
        if (currentFontFamily.equals(item.cssName)) {
            currentFontFamily = FONT_DEFAULT_CSS;
            applyFontFamilyToWebView();
        }
        // 4. 持久化并刷新列表
        saveReadingPreferences();
        rebuildFontList(container);
        applyCurrentFontToSwitchButton();
        Hint.show(this, "已删除「" + item.displayName + "」");
    }

    /**
     * 处理字体卡片点击：已下载/内置直接切换；未下载在线字体触发下载并显示进度
     */
    private void onFontCardClick(FontCardItem item, View card, LinearLayout container) {
        boolean downloaded = !item.isBackend || downloadedFonts.contains(item.cssName);
        if (item.isBackend && !downloaded) {
            if (downloadingFonts.contains(item.cssName)) return; // 已在下载中
            downloadingFonts.add(item.cssName);

            // 切换到下载进度 UI
            TextView tvName = card.findViewWithTag("font_name");
            TextView tvProgress = card.findViewWithTag("font_progress_text");
            android.widget.ProgressBar pb = card.findViewWithTag("font_progress_bar");
            if (tvName != null) tvName.setAlpha(0.6f);
            if (tvProgress != null) {
                tvProgress.setVisibility(View.VISIBLE);
                tvProgress.setText("0%");
            }
            if (pb != null) {
                pb.setVisibility(View.VISIBLE);
                pb.setProgress(0);
            }

            downloadFontFromUrl(item.backendFont.getId(), item.cssName, item.displayName,
                    card, container);
            return;
        }

        currentFontFamily = item.cssName;
        currentFontDisplay = item.displayName;
        applyFontFamilyToWebView();
        saveReadingPreferences();
        rebuildFontList(container);
        applyCurrentFontToSwitchButton();
    }

    /**
     * 计算当前字体对应的 Typeface（用于「切换字体」按钮文字预览）
     * 默认字体 / 系统字体 → 系统默认样式；在线字体 → 已下载到本地的字体文件
     */
    private android.graphics.Typeface currentFontTypeface() {
        // 默认字体 / 系统字体 用系统默认样式；在线字体用下载到本地的字体文件预览
        if (!FONT_SYSTEM_CSS.equals(currentFontFamily) && !FONT_DEFAULT_CSS.equals(currentFontFamily)) {
            if (downloadedFonts.contains(currentFontFamily)) {
                java.io.File f = new java.io.File(getFilesDir(), "fonts/" + currentFontFamily + ".ttf");
                if (f.exists()) {
                    try {
                        return android.graphics.Typeface.createFromFile(f);
                    } catch (Exception ignored) {
                        // 回退到默认
                    }
                }
            }
        }
        return null;
    }

    /**
     * 计算当前字体的展示名称（用于「切换字体」按钮文字）
     * 默认/系统字体用固定文案；在线字体从后端字体列表中取 displayName
     */
    private String currentFontDisplayName() {
        if (FONT_SYSTEM_CSS.equals(currentFontFamily)) return "系统字体";
        if (FONT_DEFAULT_CSS.equals(currentFontFamily)) return "默认字体";
        if (currentFontDisplay != null && !currentFontDisplay.isEmpty()
                && !"默认字体".equals(currentFontDisplay) && !"系统字体".equals(currentFontDisplay)) {
            return currentFontDisplay;
        }
        for (com.example.myapplication.bean.FontItem font : backendFonts) {
            if (font.getCssName().equals(currentFontFamily)) {
                return font.getName();
            }
        }
        return currentFontFamily;
    }

    /**
     * 更新「切换字体」按钮：文字显示当前字体名 + 字体跟随当前字体样式
     */
    private void updateSwitchFontButton(TextView tv) {
        if (tv == null) return;
        tv.setText(currentFontDisplayName() + " >");
        tv.setTypeface(currentFontTypeface());
    }

    /**
     * 让「切换字体 >」按钮的文字与字体跟随当前选中的字体
     * 仅在阅读设置弹窗显示时生效
     */
    private void applyCurrentFontToSwitchButton() {
        if (settingsPopupWindow == null || !settingsPopupWindow.isShowing()) return;
        TextView tv = settingsPopupWindow.getContentView().findViewById(R.id.tv_switch_font);
        updateSwitchFontButton(tv);
    }

    /**
     * 构建 3 列网格中的单个字体卡片
     */
    @SuppressLint("SetTextI18n")
    private View buildFontCard(FontCardItem item, float density, int cardHeight, boolean downloaded) {
        boolean isCurrent = item.cssName.equals(currentFontFamily);
        boolean isDownloading = item.isBackend && !downloaded && downloadingFonts.contains(item.cssName);

        // 已下载的在线字体：用字体文件本身预览字体名
        android.graphics.Typeface preview = null;
        if (item.isBackend && downloaded) {
            java.io.File f = new java.io.File(getFilesDir(), "fonts/" + item.cssName + ".ttf");
            if (f.exists()) {
                try {
                    preview = android.graphics.Typeface.createFromFile(f);
                } catch (Exception ignored) {
                    preview = null;
                }
            }
        }

        // 卡片配色跟随「当前阅读调色板」，而非写死日/夜两套值：
        // 这样更换阅读背景/夜间后，卡片与字体弹窗背景同色系，永不脱节、始终协调。
        // 选中态用 sAccent（默认墨韵主题即朱印红）表达；描边进一步弱化（1dp + 向底色混 50%）以显高级。
        boolean isDark = sIsDark;
        int chrome  = sChrome1;            // 字体弹窗主底，卡片与之同色系
        int textPri = sText1;              // 卡片主文字（随主题取深/浅）
        int accent  = sAccent;             // 当前主题强调色（朱印红系）
        // 普通卡片：在弹窗底色上做「顶受光、底背光」的细微明度偏移，浮出层次
        int cardTop = mixColors(chrome, 0xFFFFFF, isDark ? 0.05f : 0.07f);
        int cardMid = chrome;
        int cardBot = mixColors(chrome, 0x000000, isDark ? 0.06f : 0.04f);
        int strokeN = mixColors(chrome, 0x000000, isDark ? 0.18f : 0.12f);
        // 选中卡片：底色向强调色轻微晕染（克制、不突兀）；描边用弱化后的强调色（宽度仅 1dp）
        int washTop = mixColors(chrome, accent, isDark ? 0.14f : 0.10f);
        int washMid = mixColors(chrome, accent, isDark ? 0.10f : 0.07f);
        int washBot = mixColors(chrome, accent, isDark ? 0.07f : 0.05f);
        int selStroke = mixColors(accent, chrome, 0.50f);

        // 卡片容器
        android.widget.FrameLayout card = new android.widget.FrameLayout(this);
        int radius = (int) (12 * density);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        if (isCurrent) {
            bg.setColors(new int[]{ washTop, washMid, washBot });
            bg.setStroke((int) (1 * density), selStroke);
        } else {
            bg.setColors(new int[]{ cardTop, cardMid, cardBot });
            bg.setStroke((int) (1 * density), strokeN);
        }
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        // ✅ 字体卡片是「可选项 + 选中态」的功能性组件：底色必须表达自身状态，
        // 不能被浮窗重着色（否则白色底色会被映射成阅读器派生底色，切换字体重建卡片时
        // 整片卡片颜色跳变，且选中/未选中的区分消失；深色背景下卡片文字还会被改成白字而不可见）。
        card.setTag(R.id.tag_keep_own_color, true);
        card.setClickable(true);
        card.setFocusable(true);
        card.setForeground(getRippleOrNull());

        // 字体名（居中）
        TextView tvName = new TextView(this);
        tvName.setTag("font_name");
        tvName.setText(item.displayName);
        tvName.setTextSize(16);
        tvName.setTextColor(isCurrent ? accent : textPri);
        tvName.setGravity(android.view.Gravity.CENTER);
        if (preview != null) tvName.setTypeface(preview);
        if (isDownloading) tvName.setAlpha(0.6f);
        android.widget.FrameLayout.LayoutParams nameLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        tvName.setLayoutParams(nameLp);
        card.addView(tvName);

        // 下载进度：百分比文字 + 水平进度条（未下载时默认隐藏，点击后显示）
        TextView tvProgress = new TextView(this);
        tvProgress.setTag("font_progress_text");
        tvProgress.setText("0%");
        tvProgress.setTextSize(10);
        tvProgress.setTextColor(accent);
        tvProgress.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
        android.widget.FrameLayout.LayoutParams pctLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        pctLp.bottomMargin = (int) (10 * density);
        tvProgress.setLayoutParams(pctLp);
        card.addView(tvProgress);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setTag("font_progress_bar");
        pb.setIndeterminate(false);
        pb.setProgressDrawable(getResources().getDrawable(android.R.drawable.progress_horizontal, getTheme()));
        pb.getProgressDrawable().mutate().setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN);
        pb.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
        pb.setProgress(0);
        pb.setMax(100);
        int pbWidth = (int) (cardHeight * 1.2f);
        android.widget.FrameLayout.LayoutParams pbLp = new android.widget.FrameLayout.LayoutParams(
                pbWidth, (int) (3 * density),
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        pbLp.bottomMargin = (int) (5 * density);
        pb.setLayoutParams(pbLp);
        card.addView(pb);

        // 立体感：悬浮阴影（近淡远深；选中态用强调色投影，更突出「抬起」）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            card.setOutlineSpotShadowColor(isCurrent ? accent : mixColors(chrome, 0x000000, 0.20f));
        }
        card.setElevation(isCurrent ? 8f * density : 3f * density);

        return card;
    }

    /**
     * 获取 Android 波纹前景（如可用），否则返回 null
     */
    private android.graphics.drawable.Drawable getRippleOrNull() {
        android.util.TypedValue typedVal = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedVal, true)) {
            try {
                return getResources().getDrawable(typedVal.resourceId, getTheme());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 刷新字体选择弹窗 — 重建整个列表以保证状态一致
     */
    private void refreshFontSelection(LinearLayout container) {
        rebuildFontList(container);
    }

    /**
     * 构建/重建字体列表：按图中 3 列网格卡片样式
     */
    private void rebuildFontList(LinearLayout container) {
        container.removeAllViews();
        float density = getResources().getDisplayMetrics().density;

        // 收集全部卡片数据：系统字体 + 默认字体 + 在线字体
        java.util.List<FontCardItem> items = new java.util.ArrayList<>();
        items.add(new FontCardItem("系统字体", FONT_SYSTEM_CSS, false, null));
        items.add(new FontCardItem("默认字体", FONT_DEFAULT_CSS, false, null));
        for (com.example.myapplication.bean.FontItem font : backendFonts) {
            items.add(new FontCardItem(font.getName(), font.getCssName(), true, font));
        }

        if (items.size() == 2 && backendFonts.isEmpty()) {
            // 在线字体尚未加载：先显示 loading，同时拉取列表
            TextView tvLoading = new TextView(this);
            tvLoading.setText("加载中...");
            tvLoading.setTextSize(14);
            tvLoading.setTextColor(Color.parseColor("#8E8E93"));
            tvLoading.setPadding((int)(12 * density), (int)(16 * density), 0, (int)(16 * density));
            tvLoading.setTag("tv_loading");
            container.addView(tvLoading);
            fetchFontsFromBackend(container);
            return;
        }

        final int cols = 3;
        int margin = (int) (6 * density);
        int cardHeight = (int) (46 * density);

        LinearLayout currentRow = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                container.addView(currentRow);
            }
            FontCardItem item = items.get(i);
            View card = buildFontCard(item, density, cardHeight, item.isBackend && downloadedFonts.contains(item.cssName));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cardHeight, 1);
            lp.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(lp);
            currentRow.addView(card);

            // 点击切换 / 下载
            card.setOnClickListener(v -> onFontCardClick(item, card, container));

            // 已下载的在线字体可长按删除
            if (item.isBackend && downloadedFonts.contains(item.cssName)) {
                card.setOnLongClickListener(v -> {
                    showDeleteFontDialog(item, container);
                    return true;
                });
            }
        }

        // 补齐最后一行的空白占位，保持网格对齐
        int remainder = items.size() % cols;
        if (currentRow != null && remainder != 0) {
            for (int i = 0; i < cols - remainder; i++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cardHeight, 1);
                lp.setMargins(margin, margin, margin, margin);
                spacer.setLayoutParams(lp);
                currentRow.addView(spacer);
            }
        }
    }

    /**
     * 从 URL 下载字体文件到内部存储（在卡片内显示下载进度）
     */
    private void downloadFontFromUrl(long fontId, String cssName, String displayName,
                                     View card, LinearLayout container) {
        // 通过字体 id 走后端流式下载接口，避免 URL 中出现中文文件名导致下载失败（400/500）
        final String fullUrl = RetrofitClient.getFullImageUrl("/api/fonts/file/" + fontId);
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
                long total = response.body().contentLength();
                java.io.InputStream is = response.body().byteStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);

                byte[] buf = new byte[8192];
                int len;
                long downloadedBytes = 0;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    downloadedBytes += len;
                    if (total > 0 && card != null) {
                        final int pct = (int) (downloadedBytes * 100 / total);
                        runOnUiThread(() -> updateFontCardProgress(card, pct));
                    }
                }
                fos.close();
                is.close();

                runOnUiThread(() -> {
                    downloadingFonts.remove(cssName);
                    downloadedFonts.add(cssName);
                    Hint.show(this, displayName + " 下载完成");
                    // 自动切换到新下载的字体
                    currentFontFamily = cssName;
                    currentFontDisplay = displayName;
                    applyFontFamilyToWebView();
                    saveReadingPreferences();
                    refreshFontSelection(container);
                    applyCurrentFontToSwitchButton();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    downloadingFonts.remove(cssName);
                    Hint.show(this, "下载失败：" + e.getMessage());
                    refreshFontSelection(container);
                });
            }
        }).start();
    }

    /**
     * 更新卡片下载进度 UI
     */
    private void updateFontCardProgress(View card, int pct) {
        TextView tvProgress = card.findViewWithTag("font_progress_text");
        android.widget.ProgressBar pb = card.findViewWithTag("font_progress_bar");
        if (tvProgress != null) tvProgress.setText(pct + "%");
        if (pb != null) pb.setProgress(pct);
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
            // 同步给前端：关闭后 reader.html 不再启用跟手拖拽
            if (isWebViewReady) webView.evaluateJavascript("setSwipeEnabled(" + swipePageTurn + ")", null);
            saveReadingPreferences();
        });

        // ===== 自动翻页速度（慢10s / 中5s / 快3s）=====
        TextView tvAutoSlow = popupView.findViewById(R.id.tv_auto_slow);
        TextView tvAutoNormal = popupView.findViewById(R.id.tv_auto_normal);
        TextView tvAutoFast = popupView.findViewById(R.id.tv_auto_fast);
        final TextView[] speedViews = {tvAutoSlow, tvAutoNormal, tvAutoFast};
        final int[] speedValues = {10000, 5000, 3000};
        // 当前选中的下标
        int selIdx = 1;
        for (int i = 0; i < speedValues.length; i++) {
            if (speedValues[i] == autoPageInterval) { selIdx = i; break; }
        }
        final int[] selRef = {selIdx};
        updateAutoSpeedUI(speedViews, selRef[0]);
        for (int i = 0; i < speedViews.length; i++) {
            final int idx = i;
            speedViews[i].setOnClickListener(v -> {
                selRef[0] = idx;
                autoPageInterval = speedValues[idx];
                updateAutoSpeedUI(speedViews, idx);
                saveReadingPreferences();
                // 若正在自动翻页，立即以新间隔重启
                if (autoPageEnabled) {
                    stopAutoPage();
                    if (!autoPageSuspended) startAutoPage();
                }
                Hint.show(ReadActivity.this, "翻页间隔：" + (autoPageInterval / 1000) + " 秒");
            });
        }

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
            SwipeDismissLayout h = SwipeDismissLayout.findHost(v);
            if (h != null) h.dismissAnimated();
            else if (moreSettingsPopupWindow != null) moreSettingsPopupWindow.dismiss();
        });

        themeViewTree(popupView);

        // 弹窗高度固定为屏幕一半，内容过多时由内部 ScrollView 滚动
        android.util.DisplayMetrics msDm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(msDm);
        int msPopupHeight = msDm.heightPixels / 2;

        // 以全屏透明窗口 + 跟手下滑容器承载面板
        moreSettingsPopupWindow = showSwipeDismissPopup(popupView, msPopupHeight, "more");
        suspendAutoPage();

        mainHandler.removeCallbacks(hideNavRunnable);
        moreSettingsPopupWindow.setOnDismissListener(() -> {
            resetAutoHideTimer();
            resumeAutoPageIfSuspended();
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
    private long autoPageCycleStart = 0; // 当前翻页周期起点（用于页眉倒计时）
    private final Runnable autoPageCountdownRunnable = new Runnable() {
        @Override public void run() {
            if (!autoPageEnabled || autoPageSuspended || !activityResumed) return;
            if (autoPageInterval <= 0) return;
            long elapsed = System.currentTimeMillis() - autoPageCycleStart;
            long remaining = autoPageInterval - (elapsed % autoPageInterval);
            int sec = (int) Math.ceil(remaining / 1000.0);
            if (sec <= 0) sec = 1;
            if (isWebViewReady) webView.evaluateJavascript("setAutoPageCountdown(" + sec + ")", null);
            autoPageHandler.postDelayed(this, 1000);
        }
    };

    private void startAutoPage() {
        if (!autoPageEnabled) return;
        stopAutoPage();
        autoPageCycleStart = System.currentTimeMillis();
        autoPageRunnable = () -> {
            // 仅在阅读器就绪且当前没有弹窗遮挡时翻页；翻页动画中 nextPage 会自动忽略
            if (isWebViewReady && !isAnySettingsPopupShowing()) {
                webView.evaluateJavascript("nextPage()", null);
            }
            // 翻页后开启新周期，让倒计时同步归位
            autoPageCycleStart = System.currentTimeMillis();
            autoPageHandler.postDelayed(autoPageRunnable, autoPageInterval);
        };
        autoPageHandler.postDelayed(autoPageRunnable, autoPageInterval);
        // 启动页眉倒计时刷新（每秒一次）
        autoPageHandler.removeCallbacks(autoPageCountdownRunnable);
        autoPageHandler.postDelayed(autoPageCountdownRunnable, 1000);
    }

    /** 仅移除定时回调，保留 autoPageEnabled 状态（用于挂起/恢复）；同时隐藏页眉倒计时 */
    private void stopAutoPage() {
        if (autoPageRunnable != null) autoPageHandler.removeCallbacks(autoPageRunnable);
        autoPageHandler.removeCallbacks(autoPageCountdownRunnable);
        if (isWebViewReady) webView.evaluateJavascript("setAutoPageCountdown(0)", null);
    }

    /**
     * 跟手下滑关闭容器：作为 PopupWindow 的 contentView（全屏透明），内部承载底部面板。
     * <p>之所以用「自定义 ViewGroup + onInterceptTouchEvent」而不是 OnTouchListener：
     * 面板里有 ViewPager2 / RecyclerView / 按钮，它们会消费 ACTION_DOWN，挂在父布局上的
     * OnTouchListener 根本收不到后续 MOVE；而 onInterceptTouchEvent 发生在子 View 之前，
     * 能正确抢下手势。同时全屏容器保证面板下移时不会被 PopupWindow 的半屏窗口边界裁掉。
     */
    private static class SwipeDismissLayout extends FrameLayout {
        private View panel;
        private View retractView;    // 收回动画的作用对象（被容器裁剪的面板本体）
        private View scrollable;
        private Runnable dismissAction;
        private float startRawX, startRawY;
        private boolean dragging;
        private boolean maybeDrag;
        private boolean outsideDown;
        private VelocityTracker vt;
        private final float slop;
        private final float threshold;

        SwipeDismissLayout(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            slop = 8 * d;
            threshold = 90 * d;
            setClickable(true);          // 保证空白区域的 DOWN 也能进入 onTouchEvent
            setClipChildren(false);      // 面板下移时不被容器裁掉
        }

        void setPanel(View v) { panel = v; }
        void setRetractView(View v) { retractView = v; }
        void setScrollable(View v) { scrollable = v; }
        void setDismissAction(Runnable r) { dismissAction = r; }

        /** 可滚动内容是否已在顶部（只有置顶时才允许下拉关闭） */
        private boolean canDrag() {
            if (scrollable == null) return true;
            if (scrollable instanceof ScrollView) return ((ScrollView) scrollable).getScrollY() <= 0;
            if (scrollable instanceof AbsListView) {
                AbsListView lv = (AbsListView) scrollable;
                return lv.getChildCount() == 0
                        || (lv.getFirstVisiblePosition() == 0 && lv.getChildAt(0).getTop() >= 0);
            }
            if (scrollable instanceof RecyclerView) return !((RecyclerView) scrollable).canScrollVertically(-1);
            return scrollable.getScrollY() <= 0;
        }

        /** 跟手关闭：外层容器（panel）继续向下滑出屏幕后关闭，全程可见 */
        void dismissByDrag() {
            animateOut(panel, 180);
        }

        /** 收回关闭：面板（retractView，被容器裁剪）收回到「目录/夜间/设置」行内后关闭 */
        void dismissAnimated() {
            animateOut(retractView != null ? retractView : panel, 200);
        }

        private void animateOut(View target, long duration) {
            if (target == null) { if (dismissAction != null) dismissAction.run(); return; }
            float from = target.getTranslationY();
            float to = target.getHeight() > 0 ? target.getHeight() : from + 300f;
            if (to <= from) to = from + 1f;
            target.animate().cancel();
            target.animate().translationY(to)
                    .setDuration(duration)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        target.setTranslationY(0f);
                        if (dismissAction != null) dismissAction.run();
                    }).start();
        }

        /** 从任意子 View 向上找到承载它的 SwipeDismissLayout（用于关闭按钮触发带动画关闭） */
        static SwipeDismissLayout findHost(View v) {
            android.view.ViewParent p = v != null ? v.getParent() : null;
            while (p != null) {
                if (p instanceof SwipeDismissLayout) return (SwipeDismissLayout) p;
                p = p.getParent();
            }
            return null;
        }

        /** 按下点是否落在面板上方（面板之外的空白区域） */
        private boolean isOutside(MotionEvent ev) {
            if (panel == null) return false;
            int[] loc = new int[2];
            panel.getLocationOnScreen(loc);
            return ev.getRawY() < loc[1] - 1;
        }

        private boolean shouldStartDrag(MotionEvent ev) {
            if (panel == null || outsideDown) return false;
            float dy = ev.getRawY() - startRawY;
            float adx = Math.abs(ev.getRawX() - startRawX);
            return dy > slop && dy > adx && canDrag();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (panel == null) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = ev.getRawX();
                    startRawY = ev.getRawY();
                    dragging = false;
                    maybeDrag = true;
                    outsideDown = isOutside(ev);
                    obtainVt(ev);
                    return false; // 先让子 View 正常处理 DOWN
                case MotionEvent.ACTION_MOVE:
                    if (maybeDrag && !dragging && shouldStartDrag(ev)) {
                        dragging = true;   // 抢下手势，子 View 会收到 ACTION_CANCEL
                    }
                    return dragging;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return dragging;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (panel == null) return false;
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) obtainVt(ev);
            else if (vt != null) vt.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = ev.getRawX();
                    startRawY = ev.getRawY();
                    dragging = false;
                    maybeDrag = true;
                    outsideDown = isOutside(ev);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (maybeDrag && !dragging && shouldStartDrag(ev)) dragging = true;
                    if (dragging) {
                        panel.setTranslationY(Math.max(0f, ev.getRawY() - startRawY));
                        return true;
                    }
                    return outsideDown; // 面板外按下：保持消费，便于抬起时关闭
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean up = ev.getActionMasked() == MotionEvent.ACTION_UP;
                    if (dragging) {
                        if (vt != null) vt.computeCurrentVelocity(1000);
                        float yv = vt != null ? vt.getYVelocity() : 0f;
                        float ty = panel.getTranslationY();
                        if (ty > threshold || yv > 1200) {
                            dismissByDrag();   // 跟手：外层容器继续下滑出屏幕
                        } else {
                            panel.animate().translationY(0f).setDuration(160).start();
                        }
                        dragging = false;
                        maybeDrag = false;
                        releaseVt();
                        return true;
                    }
                    maybeDrag = false;
                    releaseVt();
                    if (outsideDown && up && Math.abs(ev.getRawY() - startRawY) < slop
                            && Math.abs(ev.getRawX() - startRawX) < slop) {
                        dismissAnimated();   // 点击面板外部关闭
                        return true;
                    }
                    return false;
                }
            }
            return false;
        }

        private void obtainVt(MotionEvent ev) {
            if (vt == null) vt = VelocityTracker.obtain(); else vt.clear();
            vt.addMovement(ev);
        }

        private void releaseVt() {
            if (vt != null) { vt.recycle(); vt = null; }
        }
    }

    /** 用跟手下滑容器包住底部面板，并以全屏透明窗口承载（保证下滑时不被窗口边界裁掉） */
    private PopupWindow showSwipeDismissPopup(View popupView, int panelHeight, String logTag) {
        // 其它浮窗保持原有呈现：面板直接贴屏幕底（不抬高、不做容器裁剪），
        // 仅用全屏透明窗口承载，使跟手下拉时面板不会被窗口边界裁掉。
        SwipeDismissLayout host = new SwipeDismissLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                panelHeight > 0 ? panelHeight : ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        popupView.setLayoutParams(lp);
        host.addView(popupView);
        host.setPanel(popupView);
        View sc = popupView.findViewById(R.id.popup_scroll);
        if (sc != null) host.setScrollable(sc);

        PopupWindow popup = new PopupWindow(host,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true);
        // ❌ 不能用整窗位移动画：slide_in_bottom 的 100% 是相对「窗口高度」，
        // 窗口改成全屏后位移 = 整屏高，面板前一半路程都在屏幕外，看上去就是「从屏幕底部飞入」。
        // 改为取消整窗动画，只对面板本身做入场位移（仅在其最终位置范围内滑出）。
        popup.setAnimationStyle(0);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        host.setDismissAction(() -> popup.dismiss());
        popup.showAtLocation(findViewById(android.R.id.content), Gravity.BOTTOM, 0, 0);

        // 入场：必须在首帧绘制「之前」把面板移到容器下沿之外。
        // 用 post() 会在首帧绘制「之后」才执行，于是先按最终位置画出完整面板（一闪），
        // 接着才被移到屏幕外（消失），再滑回来 —— 即「全部显现 → 消失 → 从底部滑出」。
        // 故与设置面板一致改用 OnPreDrawListener：首帧绘制前完成位移。
        popupView.setVisibility(View.INVISIBLE); // INVISIBLE 仍参与测量布局，仅不绘制，避免首帧闪现
        host.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        float h = popupView.getHeight() > 0 ? popupView.getHeight() : (float) panelHeight;
                        if (h <= 0) return true; // 尚未测量完成，等下一帧
                        popupView.getViewTreeObserver().removeOnPreDrawListener(this);
                        popupView.setVisibility(View.VISIBLE);
                        popupView.setTranslationY(h);
                        popupView.animate().translationY(0f).setDuration(220)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                        return true;
                    }
                });
        return popup;
    }

    /** 打开设置弹窗时挂起自动翻页，避免页面在面板背后继续翻动 */
    private void suspendAutoPage() {
        if (autoPageEnabled && autoPageRunnable != null) {
            stopAutoPage();
            autoPageSuspended = true;
        }
    }

    /** 设置弹窗关闭后，若仍处于开启状态且 Activity 在前台则恢复自动翻页 */
    private void resumeAutoPageIfSuspended() {
        if (autoPageEnabled && autoPageSuspended && activityResumed) {
            autoPageSuspended = false;
            startAutoPage();
        }
    }

    /** 是否有任意设置类弹窗正在显示（自动翻页应暂停） */
    private boolean isAnySettingsPopupShowing() {
        return (settingsPopupWindow != null && settingsPopupWindow.isShowing())
                || (moreSettingsPopupWindow != null && moreSettingsPopupWindow.isShowing())
                || (fontsPopupWindow != null && fontsPopupWindow.isShowing())
                || (bgColorsPopupWindow != null && bgColorsPopupWindow.isShowing());
    }

    /** 书末自动关闭时，若设置弹窗正打开则同步开关 UI */
    private void syncAutoPageSwitchUI() {
        if (settingsPopupWindow != null && settingsPopupWindow.isShowing()) {
            SwitchCompat sw = settingsPopupWindow.getContentView().findViewById(R.id.switch_auto_page);
            if (sw != null) sw.setChecked(false);
        }
    }

    /** 高亮当前选中的自动翻页速度按钮（蓝字为选中） */
    private void updateAutoSpeedUI(TextView[] views, int idx) {
        for (int i = 0; i < views.length; i++) {
            views[i].setTextColor(i == idx ? getColor(R.color.ios_blue) : getColor(R.color.ios_text_secondary));
        }
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
            // ✅ 新增：缓存分卷键，供断网时仍能还原分卷结构（旧缓存无此键则退回平铺）
            cacheChapterSortKey(bookId, i, dto.getSortKey());
        }
    }
    
    // ✅ 新增：章节ID缓存方法
    private void cacheChapterId(long bookId, int index, long chapterId) {
        getSharedPreferences("chapter_meta_" + bookId, MODE_PRIVATE)
            .edit().putLong("id_" + index, chapterId).apply();
    }

    /** 缓存章节分卷键（chapter_meta SP；null 会被 putString 存成 "null" 字面量，这里统一转空串） */
    private void cacheChapterSortKey(long bookId, int index, String sortKey) {
        getSharedPreferences("chapter_meta_" + bookId, MODE_PRIVATE)
            .edit().putString("sortkey_" + index, sortKey == null ? "" : sortKey).apply();
    }

    /** 读取缓存的分卷键；无记录返回 null（视为无分卷，退回平铺） */
    private String getChapterSortKeyCache(long bookId, int index) {
        String v = getSharedPreferences("chapter_meta_" + bookId, MODE_PRIVATE)
            .getString("sortkey_" + index, null);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /**
     * 拉取本书记录的分卷表（后台导入 EPUB 时写入 major_chapter）。
     * 仅用于服务器书目录浮窗的分卷折叠展示：成功则覆盖内存并写入缓存，失败则保留缓存里的旧值；
     * 全程不影响章节列表与阅读。拉取不到分卷时目录浮窗保持平铺。
     */
    private void loadServerMajorChapters(long bookId) {
        RetrofitClient.getApiService().getMajorChapters(bookId).enqueue(new Callback<ApiResponse<List<MajorChapter>>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<List<MajorChapter>>> call,
                                   @NonNull Response<ApiResponse<List<MajorChapter>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    List<MajorChapter> list = response.body().getData();
                    serverMajorChapters.clear();
                    if (list != null) serverMajorChapters.addAll(list);
                    cacheMajorChapters(bookId, serverMajorChapters);
                    android.util.Log.d("ReadActivity", "major chapters loaded: " + serverMajorChapters.size());
                } else {
                    // 接口失败（网络/服务端）：保留缓存里已还原的分卷表，避免「本来有卷却丢卷」
                    android.util.Log.d("ReadActivity", "major chapters unavailable, code=" + response.code());
                }
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse<List<MajorChapter>>> call, @NonNull Throwable t) {
                android.util.Log.d("ReadActivity", "failed to load major chapters", t);
            }
        });
    }

    /** 缓存分卷表（复用 chapter_list_<bookId> SP，与章节列表同生命周期，便于断网还原） */
    private void cacheMajorChapters(long bookId, List<MajorChapter> list) {
        SharedPreferences.Editor e = getSharedPreferences("chapter_list_" + bookId, MODE_PRIVATE).edit();
        int size = (list == null) ? 0 : list.size();
        e.putInt("vol_count", size);
        for (int v = 0; v < size; v++) {
            MajorChapter mc = list.get(v);
            e.putString("vol_title_" + v, (mc == null || mc.getTitle() == null) ? "" : mc.getTitle());
            e.putString("vol_sortkey_" + v, (mc == null || mc.getSortKey() == null) ? "" : mc.getSortKey());
            e.putInt("vol_sortorder_" + v, (mc == null || mc.getSortOrder() == null) ? (v + 1) : mc.getSortOrder());
        }
        e.apply();
    }

    /** 从缓存还原分卷表到内存（缓存为空/为旧版本无卷表时，置空 → 目录保持平铺） */
    private void restoreMajorChaptersFromCache(long bookId) {
        SharedPreferences sp = getSharedPreferences("chapter_list_" + bookId, MODE_PRIVATE);
        int cnt = sp.getInt("vol_count", 0);
        serverMajorChapters.clear();
        if (cnt <= 0) return;
        for (int v = 0; v < cnt; v++) {
            MajorChapter mc = new MajorChapter();
            mc.setTitle(sp.getString("vol_title_" + v, ""));
            mc.setSortKey(sp.getString("vol_sortkey_" + v, ""));
            mc.setSortOrder(sp.getInt("vol_sortorder_" + v, v + 1));
            serverMajorChapters.add(mc);
        }
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
            // 保留分卷键：目录浮窗据此按「最新 chapterList」实时推导分卷，保证下标一致
            ch.setSortKey(dto.getSortKey());
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
        chapterHtmlContents.clear();
        localVolumes.clear();
        localBookName = "";
        localBookAuthor = "";
        localBookCover = "";
        localBookCoverPath = "";
        boolean found = false;
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0) == bookId) {
                found = true;
                int chCount = sp.getInt("chapter_count_" + i, 0);
                android.util.Log.d("ReadActivity", "Found local book at index " + i + ", chapterCount=" + chCount);

                // 书名/作者/封面：目录浮窗顶部信息头用（currentBook 缺字段时兜底）
                localBookName = sp.getString("book_name_" + i, "");
                localBookAuthor = sp.getString("book_author_" + i, "");
                localBookCover = sp.getString("book_cover_" + i, "");
                localBookCoverPath = sp.getString("book_cover_path_" + i, "");

                for (int j = 0; j < chCount; j++) {
                    Chapter ch = new Chapter();
                    ch.setIndex(j);
                    ch.setTitle(sp.getString("chapter_title_" + i + "_" + j, "第" + (j + 1) + "章"));
                    chapterList.add(ch);
                    // ✅ 只建章节列表，不在开书时全量读正文/HTML：
                    //    1800 章正文 + 内联图片的 HTML 常驻内存会撑爆堆（此前主线程 OOM 直接闪退）。
                    //    这里统一填等长占位，真正翻到该章时由 fetchChapterContent → reloadLocalChapterContent 按需读入。
                    chapterContents.add(LOCAL_CHAPTER_PLACEHOLDER);
                    chapterHtmlContents.add("");
                }

                // 分卷结构（导入时写入的紧凑卷表）：供目录浮窗做分卷折叠分组
                int volCount = sp.getInt("book_volume_count_" + i, 0);
                for (int v = 0; v < volCount; v++) {
                    LocalBookParser.VolumeInfo vi = new LocalBookParser.VolumeInfo();
                    vi.index = v + 1;
                    vi.title = sp.getString("book_volume_title_" + i + "_" + v, "");
                    vi.start = sp.getInt("book_volume_start_" + i + "_" + v, 0);
                    vi.childStart = sp.getInt("book_volume_child_" + i + "_" + v, vi.start);
                    vi.end = sp.getInt("book_volume_end_" + i + "_" + v, vi.start);
                    localVolumes.add(vi);
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

    /**
     * 上报「今天读过」，推进账号级连续阅读天数。
     *
     * <p>幂等，同一天重复进入阅读器不会把天数刷上去，所以每次打开阅读器都调即可，
     * 无需在客户端判断「今天是否已打卡」。
     *
     * <p>首次调用会把旧版本存在本机的连续天数一起带上去（{@code migratedStreak}），
     * 迁移成功后置位标记，之后不再上报本地值，避免覆盖服务端已有进度。
     * 未登录/无 userId 时只做本地兜底记录，不影响阅读。
     */
    private void reportReadingCheckIn() {
        try {
            String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE)
                    .getString("userId", "");
            if (userIdStr.isEmpty()) {
                return;                       // 游客：连续天数与账号绑定，不适用
            }
            long userId = Long.parseLong(userIdStr);
            int migrated = com.example.myapplication.utils.ReadingStreak.isMigrated(this)
                    ? 0
                    : com.example.myapplication.utils.ReadingStreak.getStreak(this);

            com.example.myapplication.bean.ReadingCheckInRequest req =
                    new com.example.myapplication.bean.ReadingCheckInRequest(userId, migrated);
            RetrofitClient.getApiService().checkInReading(req).enqueue(
                    new Callback<ApiResponse<com.example.myapplication.bean.UserReadingStat>>() {
                        @Override
                        public void onResponse(
                                @NonNull Call<ApiResponse<com.example.myapplication.bean.UserReadingStat>> call,
                                @NonNull Response<ApiResponse<com.example.myapplication.bean.UserReadingStat>> response) {
                            if (response.isSuccessful() && response.body() != null
                                    && response.body().isSuccess()) {
                                // 服务端已承接本地天数，之后以账号数据为准
                                com.example.myapplication.utils.ReadingStreak
                                        .markMigrated(ReadActivity.this);
                            }
                        }

                        @Override
                        public void onFailure(
                                @NonNull Call<ApiResponse<com.example.myapplication.bean.UserReadingStat>> call,
                                @NonNull Throwable t) {
                            // 打卡失败不影响阅读，静默忽略
                        }
                    });
        } catch (Throwable ignored) {
            // 任何异常都不应影响阅读体验
        }
    }

    /**
     * 上报本次阅读时长。
     *
     * <p><b>单位是秒</b>——后端 {@code ReadTimeRequest.duration} 与累计字段
     * {@code total_read_seconds} 都以秒为准。此处历史上按分钟传过，导致累计时长被少算 60 倍，
     * 现已换算为秒；展示端（个人中心）自行折算成分钟/小时。
     */
    private void uploadReadTime(long minutes) {
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        long bid = safeBookId();
        if (userId > 0 && currentBook != null && bid > 0) {
            // 业务上以分钟计，接口以秒为准 → 传入前换算
            ReadTimeRequest request = new ReadTimeRequest(userId, bid, minutes * 60);
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

    /**
     * ✅ 栈内复用入口：书籍详情页启动阅读器时带 FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP，
     * 当返回栈里已存在同书阅读器实例（典型路径：阅读器→书籍详情→目录选章）时，不再叠出第二个
     * ReadActivity，而是把新 Intent 投递给栈内实例。
     * 背景：复用池里只有一个共享 WebView，第二个阅读器实例 initView 时会把它从旧实例视图树上抢走，
     * 旧阅读器返回后正文空白、无法唤出导航栏、停在旧章节 —— 本方法配合详情页的 flags 从根上消除双实例。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleReuseIntent(intent);
    }

    /** 处理栈内复用时投递的新 Intent：同书→按需跳章；异书（防御）→按新意图整体重载 */
    private void handleReuseIntent(Intent intent) {
        try {
            if (intent == null) return;
            Book newBook = (Book) intent.getSerializableExtra("book");
            boolean newIsExternal = intent.getBooleanExtra("isExternal", false);
            int targetIndex = intent.getIntExtra("chapterIndex", -1);
            boolean explicit = intent.getBooleanExtra("chapterExplicit", false);

            if (!isSameBookInstance(newBook, newIsExternal)) {
                // 防御：正常路径书籍详情只会启动同一本书；若真的换了书，清空旧书状态整体重载
                applyNewBookExtras(intent, newBook, newIsExternal);
                setChapterLoading(true);
                loadChaptersFromServer();
                return;
            }

            if (explicit) {
                // 目录显式跳章：与阅读器内目录弹窗（PopupChapterFragment 回调）同一条跳章路径
                if (targetIndex >= 0 && targetIndex < chapterList.size()) {
                    if (targetIndex != currentChapterIndex) {
                        loadChapterContent(targetIndex);
                    }
                } else {
                    // 章节列表尚未就绪等异常情况：把目标写回字段后整体重载
                    // （loadChaptersFromServer 内部会消费 explicitChapterJump / currentChapterIndex）
                    explicitChapterJump = true;
                    if (targetIndex >= 0) currentChapterIndex = targetIndex;
                    setChapterLoading(true);
                    loadChaptersFromServer();
                }
            }
            // 非 explicit（"开始阅读/继续阅读"）：本实例刚在 onPause 时保存过阅读记录，
            // 当前状态即最新进度，保持原位即可，不做跳转。
        } catch (Throwable t) {
            android.util.Log.e("ReadActivity", "onNewIntent 复用处理异常", t);
        }
    }

    /** 判断投递来的 Intent 是否指向当前正在阅读的同一本书（外站书以 书源类型+书源URL 标识，其 id 可能为 null） */
    private boolean isSameBookInstance(Book newBook, boolean newIsExternal) {
        if (currentBook == null || newBook == null) return false;
        if (isExternalBook != newIsExternal) return false;
        if (isExternalBook) {
            String aType = currentBook.getSourceType(), bType = newBook.getSourceType();
            String aUrl = currentBook.getSourceUrl(), bUrl = newBook.getSourceUrl();
            return (aType == null ? bType == null : aType.equals(bType))
                    && (aUrl == null ? bUrl == null : aUrl.equals(bUrl));
        }
        return currentBook.getId() != null && currentBook.getId().equals(newBook.getId());
    }

    /** 异书复用（防御路径）：按新 Intent 重置书籍相关状态（解析逻辑与 onCreate 保持一致） */
    private void applyNewBookExtras(Intent intent, Book newBook, boolean newIsExternal) {
        if (newBook != null) currentBook = newBook;
        isExternalBook = newIsExternal;
        isLocalBook = intent.getBooleanExtra("isLocal", false);
        explicitChapterJump = intent.getBooleanExtra("chapterExplicit", false);
        currentChapterIndex = intent.getIntExtra("chapterIndex", 0);
        Object chapters = intent.getSerializableExtra("chapters");
        externalChapters = (chapters instanceof String[][]) ? (String[][]) chapters : null;

        // 与 onCreate 一致：即使 intent 没传对，也从 local_books 检测是否为本地书
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

        // 清空旧书的章节状态，避免串书
        chapterList.clear();
        chapterContents.clear();
        chapterHtmlContents.clear();
        localVolumes.clear();
        serverMajorChapters.clear();
        chapterRestoredFromCache = false;
        hasRestoredFromLocal = false;
        positionRestored = false;
        restoreTargetPage = 1;

        if (tvToolbarTitle != null && currentBook.getBookName() != null) {
            tvToolbarTitle.setText(currentBook.getBookName());
        }
        updateChapterButtons();
    }

    @Override protected void onPause() {
        super.onPause();
        activityResumed = false;
        // ✅ 离开前台时清空 sActiveInstance：防止 paused 的 ReadActivity 仍被 themeViewTree
        // 误判为「前台活跃」，导致迟到的回调（比如某些 popup post、RecyclerView bind 等）把
        // 当前 Activity（比如 BookDetailActivity / 书城 / 书架）的 view 染色。
        if (sActiveInstance == this) sActiveInstance = null;
        // 离开阅读器时暂停自动翻页，回到前台再恢复
        suspendAutoPage();
        if (readStartTime > 0) {
            long minutes = (System.currentTimeMillis() - readStartTime) / 60000;
            if (minutes > 0) {
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
        activityResumed = true;
        // 标记本实例为前台活跃，供 themeViewTree 宿主判定使用
        sActiveInstance = this;
        readStartTime = System.currentTimeMillis();
        mainHandler.postDelayed(hideNavRunnable, 3000);
        if (showBatteryTime && showHeaderFooter) {
            updateBatteryAndTime();
            timeUpdateHandler.postDelayed(timeUpdateRunnable, 60000);
        }
        // 从后台回来：若自动翻页被挂起则恢复
        resumeAutoPageIfSuspended();
    }

    /** 上一个全局未捕获异常处理器（进入 Activity 时保存，销毁时恢复） */
    private Thread.UncaughtExceptionHandler savedUncaughtHandler;

    @Override protected void onDestroy() {
        // ✅ 兜底：移除仍挂在复用 WebView 上的「布局就绪」监听（防止 observer 随 detach 失效后崩溃）
        if (layoutReadyListener != null) {
            try {
                android.view.ViewTreeObserver obs = webView.getViewTreeObserver();
                if (obs.isAlive()) obs.removeOnGlobalLayoutListener(layoutReadyListener);
            } catch (Throwable ignored) {}
            layoutReadyListener = null;
        }
        // ✅ 复用池：把 WebView 从本 Activity 视图树摘离并归还给池子（绝不 destroy），
        //    下次进书直接复用，跳过「重建 WebView + 重载 reader.html」的冷启动。
        if (webView != null && webView.getParent() != null) {
            try { ((ViewGroup) webView.getParent()).removeView(webView); } catch (Throwable ignored) {}
        }
        // 恢复原始未捕获异常处理器，避免污染其它 Activity
        try {
            if (savedUncaughtHandler != null) Thread.currentThread().setUncaughtExceptionHandler(savedUncaughtHandler);
        } catch (Throwable ignored) {}
        // ✅ 复用池：切断 JsBridge 对当前 Activity 的引用，避免已销毁 Activity 继续接收前端回调
        try { JsBridge.detach(this); } catch (Throwable ignored) {}
        super.onDestroy();
        stopAutoPage();
        mainHandler.removeCallbacks(hideNavRunnable);
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
        dismissPopups();
        // ✅ 排版缓存写入池：退出时优雅关闭（已提交任务会跑完）
        if (layoutExecutor != null) {
            try { layoutExecutor.shutdown(); } catch (Throwable ignored) {}
        }
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
        /** 服务器书章节分卷键（形如 "1-0001"）：供目录分卷推导使用；本地书/外站书为空 */
        private String sortKey;
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getSortKey() { return sortKey; }
        public void setSortKey(String sortKey) { this.sortKey = sortKey; }
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