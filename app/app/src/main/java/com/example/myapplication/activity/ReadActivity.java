package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import com.example.myapplication.utils.EpubLazyStore;
import com.example.myapplication.utils.LazyStore;
import com.example.myapplication.utils.TxtLazyStore;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.LocalBookImport;
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
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
import com.example.myapplication.view.LiquidSlider;
import com.bumptech.glide.Glide;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ReadTimeRequest;
import com.example.myapplication.utils.Hint;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
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
    WebView webView;
    View layoutTopNav, layoutBottomNav;
    TextView tvToolbarTitle, tvProgressText;
    private View btnPrevChapter, btnNextChapter, btnCatalog, btnNightMode, btnSettings;
    ImageView ivNightModeIcon;
    TextView tvNightModeText;
    com.example.myapplication.view.LiquidSlider liquidSlider;

    // ========== 弹窗 ==========
    PopupWindow chapterPopupWindow, moreMenuPopupWindow, settingsPopupWindow,
            moreSettingsPopupWindow, bgColorsPopupWindow, fontsPopupWindow;
    // 设置面板内容视图：用于窗内裁剪滑动，使其从「目录/夜间/设置」行里滑出/收回
    private View settingsPanelView;

    // ========== 阅读设置 ==========
    private int currentBrightness = 128;
    private boolean followSystemBrightness = false;
    boolean autoPageEnabled = false;
    int autoPageInterval = 5000; // 自动翻页间隔(ms)，可配置：慢10s/中5s/快3s
    // 阅读亮屏时间：0=跟随系统（不加 KEEP_SCREEN_ON），-1=阅读时常亮，
    // >0=阅读时保持亮屏、无操作超过该毫秒数后允许熄屏（任意触摸重新计时）
    long screenOnTimeoutMs = 0;
    int currentBgColor = 0;  // 0-9:纯色（0-3保持旧值兼容）
    /** 设置面板翻页分段的主题重刷（夜间/背景切换后重设指示器底色与文字颜色）；面板未打开时为上次面板遗留，由 isShowing 守卫 */
    Runnable settingsPageSegRefresher;
    // 页面「实际显示」的背景基准色：纯色即自身，纹理取其预览底色，夜间模式为 #1A1A1A。
    // 导航栏/浮窗的派生配色以它为输入，保证与 WebView 里看到的一致。
    int effectiveBgBase = 0xFFFFFFFF;
    boolean isNightMode = false;

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
    static final String[] BG_COLORS = {
        "#FFFFFF", "#C4DFCF", "#E5D5C0", "#1A1A1A",  // 纯白/护眼绿/羊皮纸/夜间
        "#F5F5DC", "#D2B48C", "#F8D7DA", "#2C3E50",  // 米黄/牛皮纸/粉色/深空灰
        "#DCE8F5", "#E8DAEF"                          // 淡蓝/薰衣草
    };
    static final String[] BG_TEXT_COLORS = {
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
    static final String[] BG_TEXTURE_FILES = {
        "yangpi1.jpg", "yangpi2.jpg", "cuizhu.jpg", "shuimo.jpg",
        "yuanshan.jpg", "zhisan.jpg", "zhuying.jpg"
    };
    static final String[] BG_TEXTURE_LABELS = {
        "古纸", "旧纸", "翠竹", "水墨山", "远山", "纸伞", "竹影"
    };
    // 纹理预览底色（用于弹窗中的色块预览边框配色）
    static final int[] BG_TEXTURE_PREVIEW_COLORS = {
        0xFFF5F0E6, 0xFFF0EBE0, 0xFFE8E4D8, 0xFFD5C4A1,
        0xFFE0E4E8, 0xFFF5E6E0, 0xFFEAE8E0
    };

    // ========== 字体管理 ==========
    // 内置选项
    static final String FONT_DEFAULT_CSS = "sans-serif";      // 默认字体（app自带）
    static final String FONT_SYSTEM_CSS  = "__system__";       // 系统字体（跟随手机）
    // 后端字体列表（运行时从 API 获取）
    java.util.List<com.example.myapplication.bean.FontItem> backendFonts = new java.util.ArrayList<>();

    String currentFontFamily = "sans-serif"; // 当前使用的CSS字体族

    // ========== 间距设置（行距倍数 / 段距行数 / 左右边距 dp / 上下边距 dp） ==========
    // padTB = -1 表示跟随默认公式（JS 侧 topPadding = 左右边距 + 字号行高），保持老用户视觉不变
    float lineSpacingRatio = 1.8f;   // 行距倍数（1.2~2.6）
    float paraGapRatio = 0.5f;       // 段距（0~1.5 行）
    int padLR = 16;                  // 左右边距（8~40 dp）
    int padTB = -1;                  // 上下边距（12~56 dp；-1 = 默认公式）
    String currentFontDisplay = "默认字体";    // 当前字体展示名（选择时记录，用于设置按钮文案）
    java.util.Set<String> downloadedFonts = new java.util.HashSet<>(); // 已下载的字体cssName
    java.util.Set<String> downloadingFonts = new java.util.HashSet<>(); // 正在下载的字体cssName
    Book currentBook;
    int currentChapterIndex = 0;
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
    private long lastChapterJumpTs = 0L;            // 防御：一次手势只会触发一次 onChapterEnd/onChapterStart；
                                                    //    仿真翻页下 document 级滑动与 turn.js boundary 曾各自触发一次导致连跳两章，
                                                    //    这里按时间窗去重（见 onChapterEnd/onChapterStart）
    /** 服务端进度比本地新时，允许越过 positionRestored 守卫重渲染一次（同一本书只保留最新那条记录） */
    private boolean forceRestorePosition = false;
    float currentFontSize = 22f;   // WebView CSS px；viewport width=device-width 下 1px == 1dp
    float headerFooterFontSize = 12f; // 初始字号改为12
    boolean showHeaderFooter = true;
    boolean showBatteryTime = false;
    boolean volumeKeyPageTurn = true;
    boolean swipePageTurn = true;
    private String pageTurnMode = "cover"; // none, cover, slide, updown, fade, simulation
    boolean isLocalBook = false;
    boolean isExternalBook = false;       // 外站书籍在线阅读模式
    private String[][] externalChapters;          // 外站章节列表 [[title, url], ...]
    private final android.util.LruCache<String, String> externalContentCache =
            new android.util.LruCache<>(5);        // 缓存最近5章正文
    /** 阅读器内「章节加载中」动画（与全站共用 {@link com.example.myapplication.widget.LoadingView}） */
    private View loadingChapter;
    /** 本站章节加载失败覆盖层（插画 + 文案 + 「再试一次」按钮，透明底适配各种阅读底色） */
    private android.widget.LinearLayout layoutLoadFail;
    private TextView tvLoadFailMsg;

    // ========== 电量时间 ==========
    final Handler timeUpdateHandler = new Handler(Looper.getMainLooper());
    final Runnable timeUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateBatteryAndTime();
            timeUpdateHandler.postDelayed(this, 60000); // 每分钟更新一次
        }
    };

    // ========== 章节数据 ==========
    final List<Chapter> chapterList = new ArrayList<>();
    /**
     * 本地书的分卷结构（来自导入时持久化的紧凑卷表）；空列表表示该书无分卷信息，
     * 目录浮窗按平铺章节列表显示。仅在 {@link #loadLocalBookChapters} 中填充。
     * 注意：服务器书（含后台导入 EPUB）的分卷不存这里，而是在打开目录时按最新
     */
    final List<LocalBookParser.VolumeInfo> localVolumes = new ArrayList<>();
    /**
     * 服务器书的分卷表（后台导入 EPUB 时写入 major_chapter）。仅在服务器书加载链路拉取；
     */
    final List<MajorChapter> serverMajorChapters = new ArrayList<>();

    // 本地书元信息（目录浮窗顶部信息头的兜底来源）：仅在 loadLocalBookChapters 里填充。
    // 从阅读记录/详情页进来时 currentBook 可能缺书名/作者/封面，这里用 local_books 记录补齐。
    String localBookName = "";
    String localBookAuthor = "";
    String localBookCover = "";
    String localBookCoverPath = "";
    final List<String> chapterContents = new ArrayList<>();
    /** 保留样式的 HTML（与 chapterContents 一一对应；为空表示用纯文本渲染） */
    private final List<String> chapterHtmlContents = new ArrayList<>();
    /**
     * 本地书章节正文的占位符：打开书时只建章节列表，正文真正翻到该章时才按需读取
     * （见 {@link #reloadLocalChapterContent(int)}）。
     * 必须含「加载中」以命中 {@link #isChapterContentPending(String)}，避免多余的全表扫描。
     */
    private static final String LOCAL_CHAPTER_PLACEHOLDER = "【章节加载中...】";
    /** 本地书已加载正文的保留半径：只留当前章前后各 N 章，避免长时间阅读把全书正文堆进内存 */
    private static final int LOCAL_CONTENT_KEEP_RADIUS = 5;
    /** 各类书籍统一的章节预取窗口：当前章前后各 N 章（由近及远），预取结果落盘供下次秒开 */
    static final int CHAPTER_PREFETCH_RADIUS = 5;

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
    static boolean isChapterContentPending(String content) {
        if (content == null) return true;
        String t = content.trim();
        if (t.isEmpty()) return true;
        // 【章节加载中...】/【正在加载...】等占位文本
        return t.contains("加载中") || t.contains("正在加载");
    }
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ========== 导航栏自动隐藏 ==========
    final Runnable hideNavRunnable = this::hideNavigation;
    private int statusBarHeight = 0;  // 状态栏高度
    private int bottomNavHeight = 0;  // 底部导航栏高度缓存（避免每次滑动都重新 measure）
    private int navBarInsetPx = 0;    // 导航条真实占位，由 inset 分发记录；0 = 手势导航或还没收到分发
    private boolean bottomNavInsetApplied = false;  // 导航条让位只加一次，重复加会逐次累加
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
    /** 目录/书签浮窗控制器（承载/切章/书签跳转），实现见 ReadTocPopupController */
    final ReadTocPopupController tocController = new ReadTocPopupController(this);
    /** 自动翻页调度器（定时器/倒计时/挂起恢复），实现见 ReadAutoPageController */
    final ReadAutoPageController autoPageController = new ReadAutoPageController(this);
    /** 当某个设置弹窗打开时挂起自动翻页，关闭后恢复（仅当开启时） */
    boolean autoPageSuspended = false;
    /** Activity 是否处于前台（onResume 后为 true，onPause 后为 false） */
    boolean activityResumed = false;

    // ========== 阅读时间 ==========
    private long readStartTime;

    // ========== JS 回调更新的当前进度 ==========
    private int currentPageInChapter = 1;
    private int totalPagesInChapter = 1;

    // 复用池修复：恢复阅读位置时记住「目标页码」，正文异步到达后重渲染时沿用，
    //    避免 fetchChapterContent.onResponse 用默认 page=1 重渲染把恢复位置冲掉（导致「恢复总落第 1 页」）。
    private int restoreTargetPage = 1;

    // 排版缓存：把「章节分页结果」按 layout signature 落盘，下次进入本书同章同排版直接复用，避免每次重排
    private ReadLayoutCache layoutCache;

    private boolean positionRestored = false;
    boolean isWebViewReady  = false;
    // 复用池：标记「WebView 就绪后的统一处理」是否已执行，避免首次加载与复用命中两条路径重复触发
    private boolean readerReadyHandled = false;
    // 复用池：等 WebView 布局就绪（拿到正确宽度）再恢复位置的监听。
    //    池化 WebView 跨 Activity 复用，该监听必须随 Activity 生命周期清理，否则 observer 随 detach 失效后崩溃。
    private android.view.ViewTreeObserver.OnGlobalLayoutListener layoutReadyListener;
    private boolean hasRestoredFromLocal = false;
    private boolean chapterRestoredFromCache = false;  // 新增：标记是否从缓存恢复了章节
    // 渲染代次：每次外部调用 renderChapterContent 都生成一个新代次。
    //    WebView 未就绪时会以 50ms 重试渲染，若期间已有更新的渲染请求（如服务器正文到达后渲染目标章），
    //    较早的延迟重试通过比对代次被判定为「过期」并直接丢弃，避免用旧 index（如占位章的 0）
    //    覆盖 currentChapterIndex，导致用户被拉回第一章。
    private int renderGeneration = 0;

    // ==================== Long/Book 安全工具（彻底消灭 Long->long 自动拆箱 NPE） ====================
    /** currentBook.getId() 统一安全入口：外站书为 null → 返回 0，绝不会自动拆箱 */
    long safeBookId() {
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
        return bid > 0 ? ("local|" + bid) : ("tmp_book|"
                + (currentBook.getBookName() == null ? "" : currentBook.getBookName()));
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

    /**
     * 阅读器不参与全站换肤：它自带一套纸张/夜间背景染色（ReadThemeController 逐色驱动），
     * 宣纸档的底色会绕过 mapBgColor 的白名单原样透传，页面染色和分页都会错乱。
     */
    @Override
    protected boolean followSkin() {
        return false;
    }

    /** 阅读器自己管沉浸：状态栏随工具栏一起滑入滑出，不交给统一的系统栏出血。 */
    @Override
    protected boolean edgeToEdge() {
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // super.onCreate 之前先替换当前线程未捕获异常处理器。
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

        // 阅读器自带一套纸张/夜间背景染色（由 ReadThemeController 逐色驱动），与系统日夜无关。
        // 这里把本页锁定为日间解析：否则 drawable-night 与 ios_* token 的夜间值会混进阅读器，
        // 使 mapBgColor 的白名单失效（未命中的色值原样透传），页面染色与分页都会错乱。
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);

        // 关键：让窗口始终占满物理屏幕，状态栏显隐时不改变窗口高度
        // 这样 WebView 的 window.innerHeight 始终恒定，不会触发重新分页
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            // 旧版本：LAYOUT_FULLSCREEN + LAYOUT_HIDE_NAVIGATION + LAYOUT_STABLE 确保窗口高度
            // 始终 = 屏幕高度，两根栏显隐都不改尺寸
            int decorFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
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
        // 系统导航条不在这里定色：它得和阅读器底栏同色，写死任何一个值都会在换纸时留下色带。
        // 统一由 applyChromeTheme() 在 effectiveBgBase 就位后染（见 ReadThemeController#applyChromeColorsWith）。

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
        currentFontSize = getIntent().getFloatExtra("fontSize", 22f);
        isNightMode = getIntent().getBooleanExtra("nightMode", false);
        isLocalBook = getIntent().getBooleanExtra("isLocal", false);
        isExternalBook = getIntent().getBooleanExtra("isExternal", false);
        externalChapters = (String[][]) getIntent().getSerializableExtra("chapters");

        // 关键兜底：即使 intent 没传对，也从 local_books 检测是否为本地书
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
        
        getStatusBarHeight();

        initView();
        // 进门即展示「章节加载中」遮罩：覆盖 WebView 就绪 + 仿真翻页首屏渲染（同步 toDataURL 多张大图）的空窗，
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
        // 应用阅读亮屏策略（跟随系统 / 定时 / 常亮）
        applyScreenKeepAlive();

        setupWebView();
        setupClickListeners();
        // 先挂上 inset 监听，再提隐藏请求：隐藏之后系统只会分发 0，真实占位只有这一帧拿得到
        watchNavBarInset();
        // 初始状态隐藏系统两根栏（状态栏 + 三键导航条），只显示阅读器自己的导航栏
        hideSystemBars();
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
     * 隐藏系统两根栏（状态栏 + 三键导航条），与阅读器自己的上下导航栏同步收起。
     *
     * <p>只改变可见性，不改变窗口高度：窗口常驻物理全屏（见 onCreate 的
     * {@code setDecorFitsSystemWindows(false)} / {@code LAYOUT_*} 三件套），
     * 避免状态栏或导航条显隐导致 WebView 高度变化触发重新分页。
     * {@code BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE} 让手势上滑只唤出半透明的临时条，不回填布局。
     */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // 旧版本按位改可见性，LAYOUT_* 三件套原样留着，窗口高度才不会被系统改判
            int flags = systemBarLayoutFlags()
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    /**
     * 显示系统两根栏（状态栏 + 三键导航条），与阅读器上下导航栏一起滑入。
     */
    @SuppressWarnings("deprecation")
    private void showSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
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
            // 只摘掉「隐藏」那三位；图标深浅由 ReadThemeController 逐帧维护，这里不碰，
            // 否则导航条刚显形会短暂配错深浅。
            int flags = systemBarLayoutFlags();
            if (isNightMode) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    /**
     * 旧版本（API 24–29）的「布局位基线」：抹掉全部可见性位、补齐 LAYOUT_* 三件套，
     * 让调用方只决定显或隐，窗口高度始终由系统按物理屏高给。
     */
    private int systemBarLayoutFlags() {
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        flags &= ~(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        return flags;
    }

    private void initView() {
        // 复用 WebView 池：把池化实例动态插入内容容器，避免每次开书都 inflate / 重建 WebView + 重载 reader.html。
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

        // 排版缓存：目录/写入池/容量淘汰由 ReadLayoutCache 托管（2026-10-02 拆出）
        layoutCache = new ReadLayoutCache(this);

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
        // 每次 attach 都重新绑定到当前 Activity：上一个 Activity 的 WebViewClient / JsBridge
        //    随替换而失效，避免复用池 WebView 时回调串台到已销毁的 Activity。
        // 复用池 WebView：必须使用同一个 JsBridge 实例注入 JS。
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

            /** 拦截 epubres:// 协议：从源 zip 实时读图，零落盘 */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,
                    android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("epubres://")) {
                    String entryPath = url.substring("epubres://".length());
                    // epubres:// 供图是 EPUB 独有（回源读 zip 条目）；TXT 懒解析仓储没有此能力，故加类型守卫
                    LazyStore ls = lazyStore();
                    EpubLazyStore store = ls instanceof EpubLazyStore ? (EpubLazyStore) ls : null;
                    if (store != null) {
                        byte[] data = store.getZipEntry(entryPath);
                        if (data != null && data.length > 0) {
                            String mime = guessImageMime(entryPath);
                            return new android.webkit.WebResourceResponse(mime, "UTF-8",
                                    new java.io.ByteArrayInputStream(data));
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            /** 根据条目名后缀猜 MIME；不认识就回退 image/png（浏览器能容错） */
            private String guessImageMime(String path) {
                if (path == null) return "image/png";
                String lower = path.toLowerCase();
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
                if (lower.endsWith(".gif")) return "image/gif";
                if (lower.endsWith(".webp")) return "image/webp";
                if (lower.endsWith(".svg")) return "image/svg+xml";
                return "image/png";
            }
        });

        // 复用池 WebView：reader.html 已加载则立即就绪（首开由上面的 onPageFinished 触发）。
        //    不再每次开书 loadDataWithBaseURL 重载 reader.html —— 这是冷启动优化的核心。
        if (ReaderWebViewPool.isLoaded()) {
            isWebViewReady = true;
            // 等 WebView 完成布局、拿到正确宽度后再恢复，否则 0 宽会导致分页 / 缓存 key 出错。
            // 复用的 WebView 跨 Activity：必须先把可能残留的旧监听清掉（避免叠加），
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
                            // 用「当前」observer 并在 alive 时移除，避免捕获的旧 observer 失效抛异常
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

        // 把原本注入到 reader.html 的初始化脚本改为 evaluate 执行（复用池后不再重载 HTML），
        //    保证字号 / 字体 / 背景 / 页眉页脚 / 纹理 / 仿真占位等全局变量与首帧样式正确。
        webView.evaluateJavascript(buildSettingsInitScript(), null);

        // 关键修复：WebView 一就绪就无条件补发一次页眉页脚 / 电量时间设置。
        // 退出阅读器重进时 onResume 先于 onPageFinished 执行，那时 updateBatteryAndTime()
        // 会因 isWebViewReady=false 被跳过；而下面恢复位置的分支只在「首次恢复」时应用设置，
        // 走本地缓存恢复（chapterRestoredFromCache=true）时完全不会走到，
        // 结果就是页脚只有页码、没有电量时间，要等 60 秒的定时刷新才出现。
        applyHeaderFooterSettings();

        // WebView 加载完成后，如果章节列表已加载但尚未恢复位置，则恢复
        mainHandler.post(this::restoreProgressOnceCatalogReady);
    }

    /**
     * WebView 就绪后的「恢复阅读位置」这一步，单独抽出来是因为本地书还有一处要调它：
     * 目录是后台构建出来的，索引就绪那一刻 WebView 早就 ready 了，不会再走
     * {@link #onReaderWebViewReady}（那里面有一次性标志），必须能原地补发一次。
     */
    private void restoreProgressOnceCatalogReady() {
        if (positionRestored || chapterRestoredFromCache || chapterList.isEmpty()) return;
        android.util.Log.d("ReadActivity", "WebView ready, restoring position from server...");
        // 先应用所有设置（字体、背景等），再恢复位置，避免默认设置闪烁
        webView.evaluateJavascript("beginSettingsBatch()", null);
        applySettingsToWebView_inner();
        webView.evaluateJavascript("finishSettingsBatch()", null);
        webView.evaluateJavascript("setPageTurnMode('" + pageTurnMode + "')", null);
        restoreReadingPosition(currentChapterIndex);
        updateChapterButtons();
        // 若此前开启过自动翻页，阅读器就绪后自动恢复
        if (autoPageEnabled) {
            autoPageSuspended = false;
            startAutoPage();
        }
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
                    activity.hideNavOnPageTurn();
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
            // 连击去重：同一手势的重复回调（仿真翻页下 document 级滑动分支与 turn.js boundary
            //    各可能触发一次，两者间隔 <50ms）直接丢弃。同手势去重主防线在 JS 侧
            //    （_boundaryPending/_xCrossFlipping 单一入口），此处只是兜底。
            // 窗口必须是 150ms 而非 700ms：无缝换章的回调在「动画 500ms 结束后」才发出，
            //    用户「翻过去立刻翻回来」时两次合法回调间隔仅 ~530-650ms——700ms 窗口会把
            //    第二次合法回调吞掉，JS 已切到目标章而 Java 索引滞留旧章，下一次边界翻页
            //    就会按旧索引用邻章，把用户抛回刚读过的章节（「换章翻页跳回未翻之前的页」）。
            //    合法两次翻页最小间隔 ≈ 500ms 动画 + 反应时间 ≫ 150ms；旧流程连击由
            //    pendingChapterIndex 同目标守卫拦截（与时间窗无关），不受此收紧影响。
            if (now - activity.lastChapterJumpTs < 150) return;
            activity.lastChapterJumpTs = now;
            activity.runOnUiThread(() -> {
                try {
                int nextIndex = activity.currentChapterIndex + 1;

                // 简化：直接检查是否还有下一章
                if (nextIndex >= activity.chapterList.size()) {
                    Hint.show(activity, "已经是最后一章，自动翻页已关闭");
                    // 到达书末：停止自动翻页并同步开关状态
                    activity.stopAutoPage();
                    activity.autoPageEnabled = false;
                    activity.autoPageSuspended = false;
                    activity.syncAutoPageSwitchUI();
                    activity.saveReadingPreferences();
                    return;
                }

                // 已有指向同一章的在途跳转 → 直接忽略，避免连跳多章
                if (activity.shouldIgnoreChapterJump(nextIndex)) {
                    return;
                }

                // 防御：chapterContents 越界
                if (nextIndex >= activity.chapterContents.size()) {
                    while (activity.chapterContents.size() < activity.chapterList.size()) activity.chapterContents.add("【正在加载...】");
                }
                String content = activity.chapterContents.get(nextIndex);

                if (activity.isChapterContentPending(content)) {
                    // 下一章正在加载或尚未加载，触发加载。
                    // 关键：这里【不要】推进 currentChapterIndex —— 推进了的话，
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
            // 连击去重：同 onChapterEnd，窗口 150ms（理由见彼处注释——700ms 会吞掉
            //    无缝换章「立刻折返」的第二次合法回调，造成 Java/JS 索引失步乱跳章）。
            if (now - activity.lastChapterJumpTs < 150) return;
            activity.lastChapterJumpTs = now;
            activity.runOnUiThread(() -> {
                try {
                int prevIndex = activity.currentChapterIndex - 1;

                if (prevIndex < 0) {
                    // 确实是第一章
                    Hint.show(activity, "已经是第一章");
                    return;
                }

                // 已有指向同一章的在途跳转 → 直接忽略，避免连跳多章
                if (activity.shouldIgnoreChapterJump(prevIndex)) return;

                // 防御：chapterContents 越界
                if (prevIndex >= activity.chapterContents.size()) {
                    activity.setPendingChapterJump(prevIndex, true);
                    activity.fetchChapterContent(prevIndex);
                    return;
                }
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

        /**
         * 跨章无缝翻页：JS 在章节边界翻页时同步查询邻章正文是否已就绪（预加载/缓存命中）。
         * 就绪返回 JSON {"index":N,"title":"...","content":"..."}，未就绪返回空串（走原有换章加载流程）。
         * 绝不发网络请求；服务器书只认内存 chapterContents —— SP 兜底会让 JS 先进入新章
         * 而 Java 侧 fetchChapterContent 仍走网络滞留旧章，两端索引窗口期错位。
         */
        @JavascriptInterface
        @SuppressWarnings("unused")
        public String getAdjacentChapterPayload(int dir) {
            ReadActivity activity = getActivity();
            if (activity == null) return "";
            try {
                return activity.resolveAdjacentChapterPayload(dir);
            } catch (Throwable t) {
                android.util.Log.e("ReadActivity", "getAdjacentChapterPayload 崩溃", t);
                return "";
            }
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void cacheChapterLayout(String key, String json) {
            ReadActivity activity = getActivity();
            if (activity == null) return;
            if (key == null || json == null || json.length() < 2) return;
            activity.writeLayoutCache(key, json);
        }

        /**
         * 跨章索引重同步：JS 在跨章过渡窗口内收到「过时的 loadContent」（用户已折返、
         * Java 还在处理上一次换章回调）时会丢弃它并调用本方法，把 Java 的章节索引对齐到
         * JS 的实际位置，并按正确落点页重发渲染 —— 否则下一次边界翻页会按 Java 的滞后索引
         * 取错邻章，把用户抛回刚读过的章节（「换章翻页跳回未翻之前的页」）。
         *
         * @param index      JS 实际所在章（0 基）
         * @param atLastPage JS 是否停在该章最后一页（向后跨章的落点）
         */
        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onChapterResync(int index, boolean atLastPage) {
            ReadActivity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                try {
                    if (index < 0 || index >= activity.chapterList.size()) return;
                    if (activity.currentChapterIndex == index) return;   // 已一致，无需同步
                    android.util.Log.d("ReadActivity", "onChapterResync: Java="
                            + activity.currentChapterIndex + " -> JS=" + index + ", atLast=" + atLastPage);
                    activity.currentChapterIndex = index;
                    activity.updateChapterButtons();
                    if (atLastPage) activity.loadChapterContentToLastPage(index);
                    else activity.loadChapterContent(index);
                } catch (Throwable t) {
                    android.util.Log.e("ReadActivity", "onChapterResync 崩溃", t);
                }
            });
        }
    }

    // ========== 排版缓存：实现见 ReadLayoutCache（2026-10-02 拆出，按 layout signature 落盘复用） ==========

    private String computeLayoutKey(int chIndex, String content) {
        return layoutCache.computeLayoutKey(chIndex, content);
    }

    private String readLayoutCache(String key) {
        return layoutCache.readLayoutCache(key);
    }

    private void writeLayoutCache(String key, String json) {
        layoutCache.writeLayoutCache(key, json);
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
        // 目录点击（显式跳章）优先于本地记录：点哪章进哪章
        int savedIdx = explicitChapterJump
                ? currentChapterIndex
                : pref.getInt(recordKey + "_chapterIndex", currentChapterIndex);
        currentChapterIndex = Math.min(savedIdx, chapterList.size() - 1);
        // 读取保存的页码，用于恢复到具体页（显式跳章从第 1 页开始，不沿用其他章节的页码）
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
            // 标记已恢复位置，阻止 onPageFinished → restoreReadingPosition 重复渲染（会导致闪回第1页）
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

    /**
     * 外站书章节列表兜底拉取（书城同款 fetchOnlineChapters：内存缓存 → 持久化缓存 → 网络）。
     * 内部含磁盘兜底读（缓存文件可达数 MB），必须在后台线程执行——本方法自带起线程。
     * 结果统一回 UI 线程处理：成功回填 externalChapters + 写缓存 + buildExternalChapterListAndLoad；
     * 失败弹 showExternalLoadErrorDialog；成功/失败都会撤下超时保护 timeoutTask。
     */
    private void fetchExternalChaptersAsync(Runnable timeoutTask) {
        new Thread(() -> BookDetailActivity.fetchOnlineChapters(this, currentBook,
                new BookDetailActivity.ChaptersCallback() {
            @Override
            public void onSuccess(java.util.List<String[]> chapters) {
                runOnUiThread(() -> {
                    mainHandler.removeCallbacks(timeoutTask);
                    if (isFinishing() || isDestroyed()) return;
                    if (externalChapters != null && externalChapters.length > 0) return;   // 已由其他途径就绪
                    if (chapters == null || chapters.isEmpty()) {
                        showExternalLoadErrorDialog("获取章节失败", true);
                        return;
                    }
                    externalChapters = chapters.toArray(new String[0][]);
                    BookDetailActivity.putExternalChapters(ReadActivity.this, currentBook, chapters);
                    buildExternalChapterListAndLoad();
                });
            }
            @Override
            public void onFail(String msg, boolean sourceMayDown) {
                runOnUiThread(() -> {
                    mainHandler.removeCallbacks(timeoutTask);
                    if (isFinishing() || isDestroyed()) return;
                    if (externalChapters != null && externalChapters.length > 0) return;
                    String show = sourceMayDown ? "书源暂不可用，请稍后重试"
                            : (msg == null || msg.isEmpty() ? "获取章节失败" : msg);
                    showExternalLoadErrorDialog(show, sourceMayDown);
                });
            }
        }), "ext-chapter-fetch").start();
    }

    private void loadChaptersFromServer() {
        positionRestored = false;

        // ===== 外站书籍：Intent extra 优先 → 内存缓存 → 占位+加载动画 → 后台(持久化缓存→网络) =====
        if (isExternalBook) {
            if (externalChapters == null || externalChapters.length == 0) {
                // 主线程只查内存缓存（无 IO，可安全调用）
                java.util.List<String[]> mem = BookDetailActivity.getExternalChaptersMemoryOnly(this, currentBook);
                if (mem != null && !mem.isEmpty()) {
                    externalChapters = mem.toArray(new String[0][]);
                }
            }
            if (externalChapters != null && externalChapters.length > 0) {
                buildExternalChapterListAndLoad();
                return;
            }
            // Intent 和内存都未命中：立即占位显示（整页「章节加载中」动画已由 onCreate 开启），
            // 持久化缓存→网络的完整拉取链放到后台线程——磁盘缓存文件可达数 MB，禁在主线程读。
            // 点击书架即刻进书，不再在书架页弹提示等待；列表到达后自动进入正文。
            if (currentBook.getSourceType() == null || currentBook.getSourceUrl() == null) {
                showExternalLoadErrorDialog("书源信息缺失", true);
                return;
            }
            currentChapterIndex = Math.max(0, currentChapterIndex);
            Chapter tempChapter = new Chapter();
            tempChapter.setIndex(0);
            tempChapter.setId(-1);
            // 标题不要塞「加载中...」：它会被当成真正的章节标题，**同时画在页眉和正文的大标题上**
            //    （用户看到的「正文里还显示加载中…」就是它）。标题未知时留空，
            //    「正在加载」统一由整页加载动画表达，不要再往正文里塞字。
            //    这个临时章节 id=-1，真实章节列表一到就会整体重建，所以留空不会残留。
            tempChapter.setTitle("");
            chapterList.add(tempChapter);
            chapterContents.add("【正在加载章节列表...】");
            // 修复：占位渲染必须用真实目标章 currentChapterIndex，不能用硬编码 0，
            //    否则 WebView 就绪前的延迟重试会把 currentChapterIndex 覆盖回 0，用户停在第一章
            renderChapterContent(currentChapterIndex, tempChapter.getTitle(), "【正在加载章节列表...】");
            tvToolbarTitle.setText(currentBook.getTitle());
            // 启动章节列表加载超时保护（覆盖后台磁盘读 + 网络拉取全程）
            final Runnable timeoutTask = () -> {
                if (externalChapters == null || externalChapters.length == 0) {
                    runOnUiThread(() -> {
                        // 后台拉取期间用户退出/换肤重建了本页：定时任务照样会到点触发，
                        // 在已销毁的 Activity 上 show() 对话框会抛 BadTokenException。
                        if (isFinishing() || isDestroyed()) return;
                        showExternalLoadErrorDialog("加载超时，请检查网络后重试", false);
                    });
                }
            };
            mainHandler.postDelayed(timeoutTask, 20000);
            fetchExternalChaptersAsync(timeoutTask);
            return;
        }

        long localBookId = safeBookId();
        int localCh = -1;
        SharedPreferences pref = getSharedPreferences("reading_records", MODE_PRIVATE);
        int cnt = pref.getInt("record_count", 0);
        // 外站书（bookId==0）按 sourceType+sourceUrl 组合查 external_reading_records；
        //    服务器书（bookId>0）继续走原有 reading_records。
        if (isExternalBook) {
            String recordKey = "ext_"
                    + (currentBook != null && currentBook.getSourceType() != null ? currentBook.getSourceType() : "")
                    + "|"







                            + (currentBook != null && currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "");
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
        // 目录点击（显式跳章）优先于阅读记录：点哪章进哪章，不再被"上次阅读位置"覆盖
        final int targetChapter = (!explicitChapterJump && localCh >= 0) ? localCh : currentChapterIndex;

        // 性能优化：调用并行加载策略
        initChapterListAndRestoreProgress(targetChapter);
    }

    /**
     * 章节列表加载入口：本地书/外站书/服务器书分流。
     * 服务器书先尝试本地缓存恢复（秒开），同时异步请求服务器列表做合并更新。
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
        // 外站书永远走 buildExternalChapterListAndLoad（章节来自 Intent/缓存/服务器 getExternalChapters API）
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
        
        // 步骤1：尝试从本地缓存恢复完整的章节列表
        boolean hasFullCache = restoreFullChapterListFromCache(bookId, targetChapter);
        
        if (hasFullCache) {
            android.util.Log.d("ReadActivity", "Restored full chapter list from cache: "
                    + chapterList.size() + " chapters");

            // 关键修复：先检查是否有阅读记录（包含页码信息）
            SharedPreferences pref = getSharedPreferences("reading_records", MODE_PRIVATE);
            int cnt = pref.getInt("record_count", 0);
            int savedPage = 1;  // 默认第1页
            boolean foundRecord = false;

            for (int i = 0; i < cnt; i++) {
                if (pref.getLong("record_bookId_" + i, 0) == bookId
                        && pref.getInt("record_chapterIndex_" + i, -1) == targetChapter) {
                    savedPage = pref.getInt("record_page_" + i, 1);
                    foundRecord = true;
                    android.util.Log.d("ReadActivity", "Found reading record: chapter="
                            + targetChapter + ", page=" + savedPage);
                    break;
                }
            }

            // 立即加载目标章节内容（如果有缓存）
            String cachedContent = getChapterContentCache(bookId, targetChapter);
            if (!isChapterContentPending(cachedContent)) {
                android.util.Log.d("ReadActivity", "Found cached content for chapter "
                        + targetChapter + ", length=" + cachedContent.length());
                currentChapterIndex = targetChapter;
                // 先应用设置，再渲染内容，避免默认设置闪烁
                applySettingsToWebView();
                // 关键修复：直接渲染到 savedPage，避免"先显示第1页再翻页"的动画
                renderChapterContent(targetChapter, chapterList.get(targetChapter).getTitle(),
                        cachedContent, savedPage);
                updateChapterButtons();
                // 恢复自缓存后触发预取：窗口内缺失的章由后台补齐并落盘（下次秒开）
                prefetchServerChaptersAround(targetChapter);
                hasRestoredFromLocal = true;
                chapterRestoredFromCache = true;
                positionRestored = true;
            } else {
                // 没有内容缓存，显示占位符并触发加载
                // 关键修复（复用池回归）：无本地内容缓存时也要记住「恢复目标页码」，
                //    否则 fetchChapterContent.onResponse 默认 page=1 会把恢复位置冲掉，
                //    导致「重新打开总落第 1 页」。与 loadChapterContentWithPage 保持一致。
                restoreTargetPage = savedPage;
                positionRestored = true;
                currentChapterIndex = targetChapter;
                renderChapterContent(targetChapter, chapterList.get(targetChapter).getTitle(), "【正在加载章节内容...】");
                fetchChapterContent(targetChapter);
                // 当前章走网络时同样预取窗口内其余缺失章（不与当前章加载抢主链路）
                prefetchServerChaptersAround(targetChapter);
            }

            long cacheLoadTime = System.currentTimeMillis() - startTime;
            android.util.Log.d("ReadActivity", "Cache displayed in " + cacheLoadTime
                    + "ms, now checking server for updates...");
        } else {
            android.util.Log.d("ReadActivity", "No full cache available, waiting for server response...");
            currentChapterIndex = targetChapter;
            Chapter tempChapter = new Chapter();
            tempChapter.setIndex(targetChapter);
            tempChapter.setId(-1);
            // 标题不要塞「加载中...」：它会被当成真正的章节标题，**同时画在页眉和正文的大标题上**
            //    （用户看到的「正文里还显示加载中…」就是它）。标题未知时留空，
            //    「正在加载」统一由整页加载动画表达，不要再往正文里塞字。
            //    这个临时章节 id=-1，真实章节列表一到就会整体重建，所以留空不会残留。
            tempChapter.setTitle("");
            chapterList.add(tempChapter);
            chapterContents.add("【正在加载章节列表...】");
            // 修复：占位渲染用真实目标章 targetChapter（而非硬编码 0），
            //    避免 WebView 就绪前的延迟重试覆盖 currentChapterIndex 导致首次进入落回第一章
            renderChapterContent(targetChapter, tempChapter.getTitle(), "【正在加载章节列表...】");
        }
        
        // 步骤2：异步请求服务器章节列表（后台更新）
        // 同时拉取分卷表（供阅读器目录浮窗分卷展示）；失败/无分卷时目录保持平铺
        loadServerMajorChapters(bookId);
        RetrofitClient.getApiService().getChapters(bookId).enqueue(new Callback<ApiResponse<List<ChapterDto>>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<List<ChapterDto>>> call,
                    @NonNull Response<ApiResponse<List<ChapterDto>>> response) {
                long serverResponseTime = System.currentTimeMillis() - startTime;
                
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    List<ChapterDto> list = response.body().getData();
                    if (list == null || list.isEmpty()) {
                        android.util.Log.e("ReadActivity", "Server returned empty chapter list");
                        return;
                    }
                    
                    android.util.Log.d("ReadActivity", "Server chapters loaded in " + serverResponseTime
                            + "ms: count=" + list.size());
                    
                    // 缓存章节列表元数据
                    cacheChapterListOnly(bookId, list);
                    
                    // 关键：用服务器数据替换当前章节列表
                    int savedChapterIndex = currentChapterIndex;
                    android.util.Log.d("ReadActivity", "Before merge: currentChapterIndex=" + savedChapterIndex);
                    
                    mergeServerData(list);
                    
                    // 恢复章节索引（确保不越界）
                    currentChapterIndex = Math.min(savedChapterIndex, chapterList.size() - 1);
                    android.util.Log.d("ReadActivity", "After merge: restored currentChapterIndex="
                            + currentChapterIndex);
                    
                    mainHandler.post(() -> {
                        android.util.Log.d("ReadActivity", "Server data arrived, refreshing UI...");

                        // 关键修复：如果已经通过本地缓存正确恢复了位置和内容，
                        // 不再调用 restoreReadingPosition（否则仿真模式下会重复 loadContent 导致闪屏）
                        if (!hasRestoredFromLocal) {
                            // 关键修复：使用 restoreReadingPosition 来恢复页码
                            restoreReadingPosition(currentChapterIndex);
                        } else {
                            // 已有缓存内容且已恢复页码，只需更新按钮状态
                            // 不要重新渲染章节内容，否则会清除页码状态导致跳回第一页
                            updateChapterButtons();
                            android.util.Log.d("ReadActivity",
                                    "Already restored from cache with page position, skip re-rendering");
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
                    android.util.Log.e("ReadActivity", "Server response failed: "
                            + (response.isSuccessful() ? "data error" : "code=" + response.code()));
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
     * 从缓存恢复完整的章节列表
     * @return true 如果成功恢复完整列表
     */
    private boolean restoreFullChapterListFromCache(long bookId, int targetChapter) {
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
                
                // 性能修复：不再逐章把全部正文读进内存。此前对最多数千章每章做一次
                //    getChapterContentCache（chapter_content_<bookId> SP 冷启动首次访问
                //    还是主线程同步整文件读 + 解析），进书卡顿数秒，表现为
                //    「书架点击书籍无反应，过一会才进阅读器」。
                //    现在只读「目标章 ±CHAPTER_PREFETCH_RADIUS」的正文保证即时渲染与顺滑翻页，
                //    其余章填空占位（命中 isChapterContentPending → 翻到时按需拉取/回填，
                //    与服务器刷新 mergeServerData 的重建行为一致）。
                //    SP 整文件只加载一次（书架点击已后台预热），窗口内逐章取只是内存哈希查找。
                String content = (i >= targetChapter - CHAPTER_PREFETCH_RADIUS && i <= targetChapter + CHAPTER_PREFETCH_RADIUS)
                        ? getChapterContentCache(bookId, i) : null;
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
        RetrofitClient.getApiService().getProgress(userId,
                bookId).enqueue(new Callback<ApiResponse<ReadingProgress>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<ReadingProgress>> call,
                    @NonNull Response<ApiResponse<ReadingProgress>> response) {
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
                // 复用池修复：同设备续读时以本地记录为上。服务端进度历史上一度长期存成第 1 页，
                //    即便本地 readTime 尚未被本次打开刷新、服务端时间戳更新，也应优先保留本地已恢复的位置，
                //    否则「打开书 → 本地恢复到第 N 页 → 服务端拉回第 1 页」会把用户弹回首页。
                //    仅当本地无任何阅读记录（换设备 / 首次打开）时才采纳服务端进度。
                if (localTime > 0) {
                    android.util.Log.d("ReadActivity",
                            "Local reading record exists, keep local position, skip server adopt");
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
        android.util.Log.d("ReadActivity", "restoreReadingPosition: chapterIndex=" + chapterIndex
                + ", chapterList.size()=" + chapterList.size());

        // 防止重复调用：如果已经正确恢复了位置，跳过
        if (positionRestored) {
            android.util.Log.d("ReadActivity", "positionRestored already true, skip restoreReadingPosition");
            return;
        }

        // 外站书：用 external_reading_records（按 sourceType+sourceUrl 组合键查）
        if (isExternalBook) {
            String recordKey = "ext_"
                    + (currentBook != null && currentBook.getSourceType() != null ? currentBook.getSourceType() : "")
                    + "|"







                            + (currentBook != null && currentBook.getSourceUrl() != null ? currentBook.getSourceUrl() : "");
            SharedPreferences pref = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
            int savedPage = pref.getInt(recordKey + "_page", 1);
            android.util.Log.d("ReadActivity", "外站书从本地记录恢复: page=" + savedPage);
            loadChapterContentWithPage(chapterIndex, savedPage);
            return;
        }

        long bookId = safeBookId();
        SharedPreferences pref = getSharedPreferences("reading_records", MODE_PRIVATE);
        int cnt = pref.getInt("record_count", 0);

        // bookId<=0 时不进循环按 bookId 精确匹配，回落到直接加载第一页
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

    void loadChapterContent(int chapterIndex) {
        android.util.Log.d("ReadActivity", "loadChapterContent: chapterIndex=" + chapterIndex
                + ", chapterList.size()=" + chapterList.size());
        // 非恢复渲染（跳章/翻章等）：目标页码回到首页，避免沿用上次恢复的目标页
        restoreTargetPage = 1;
        // 主动跳章（目录/进度条等）时取消在途的边界跨章跳转
        clearPendingChapterJump();

        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
            android.util.Log.e("ReadActivity",
                    "loadChapterContent: chapterIndex out of range! Will wait for full chapter list...");
            // 修正：如果索引超出范围，不直接返回，而是等待完整章节列表加载
            // 这种情况通常发生在章节列表尚未完全加载时
            return;
        }

        String title = chapterList.get(chapterIndex).getTitle();
        // 关键防御：chapterContents 与 chapterList 必须同步，否则用占位符避免 IndexOutOfBounds
        String content;
        if (chapterIndex < chapterContents.size()) {
            content = chapterContents.get(chapterIndex);
        } else {
            // 补齐 chapterContents 到 chapterList.size 防止后续 .set() 也越界
            while (chapterContents.size() < chapterList.size()) chapterContents.add("【正在加载...】");
            content = chapterContents.get(chapterIndex);
        }

        android.util.Log.d("ReadActivity", "loadChapterContent: title=" + title + ", content length="
                + (content != null ? content.length() : 0));

        boolean isPlaceholder = isChapterContentPending(content);

        if (isPlaceholder) {
            android.util.Log.d("ReadActivity", "Content is placeholder, fetching from server");
            // 修正：立即更新 currentChapterIndex，让用户看到章节切换
            currentChapterIndex = chapterIndex;
            updateChapterButtons();

            // 显示「章节加载中」动画（原生遮罩，不依赖 WebView 是否就绪，三种翻页模式都可见）。
            // 原实现调的是 reader.html 里的 showLoading() —— 那个函数根本不存在，一直是空操作，
            // 于是加载期间正文只把占位哨兵串「【章节加载中...】」当正文画了出来。
            setChapterLoading(true);
            // 同时把占位页渲染进 WebView：加载动画/失败层都是透明底，
            //    不清掉旧页的话，离线切章时「上一章正文」会一直衬在加载动画/失败层底下。
            renderChapterContent(chapterIndex, title, "【正在加载...】");
            fetchChapterContent(chapterIndex);
        } else {
            android.util.Log.d("ReadActivity", "Content valid, rendering directly");
            // 修正：先更新 currentChapterIndex，再渲染内容
            currentChapterIndex = chapterIndex;
            updateChapterButtons();
            renderChapterContent(chapterIndex, title, content);
            // 修正：成功渲染后，设置 positionRestored 标志
            if (!positionRestored) {
                positionRestored = true;
                android.util.Log.d("ReadActivity", "positionRestored set to true after successful render");
            }
        }

        // 当前章就绪后，后台由近及远预取前后各 5 章并落盘缓存（下次打开秒开）
        prefetchServerChaptersAround(chapterIndex);
    }

    //===== 本地书懒解析：书不物化，按导入时记下的索引（EPUB=zip 条目名 / TXT=字节区间）回源重建 =====

    private LazyStore lazyStore;
    /** 已经为哪本书尝试过打开：失败也只试一次，别每章都去开源文件、读三份键缓存 */
    private long lazyStoreBookId = -1L;

    /**
     * 当前本地书的懒解析仓储；返回 null 表示这本书读不出正文：
     * 没有回源索引、懒解析上线前导入的旧书、或源文件已被删除/移动。
     *
     * <p>索引条数必须与目录章节数一致才采信：对不上就说明这条记录和源文件已经不是同一本书，
     * 再按旧条目名去取，拿到的是别书的内容。
     *
     * <p>UI 线程与 WebView 桥线程都会进来（邻章兜底在桥线程），故整体加锁。
     */
    private synchronized LazyStore lazyStore() {
        long bookId = safeBookId();
        if (bookId <= 0) return null;
        if (lazyStoreBookId == bookId) return lazyStore;
        if (lazyStore != null) {
            lazyStore.close();
            lazyStore = null;
        }
        lazyStoreBookId = bookId;
        String src = localBookSourceUri(bookId);
        if (src.isEmpty()) {
            android.util.Log.w("ReadActivity", "懒解析失败：未找到源文件 Uri，bookId=" + bookId
                    + "。可能原因：1) 删除书籍时未正确搬运 book_source_uri_；2) 该书是懒解析上线前导入的旧书");
            return null;
        }
        LazyStore store = null;
        try {
            // 两个实现各自校验自己的索引文件（index.bin / txtindex.bin）是否存在，不存在即返回 null：
            // 先试 EPUB，不是 EPUB 懒解析书再试 TXT。都建不起来说明这本书没有回源索引，读不出正文。
            store = EpubLazyStore.open(this, bookId, Uri.parse(src));
            if (store == null) store = TxtLazyStore.open(this, bookId, Uri.parse(src));
        } catch (Throwable t) {
            android.util.Log.w("ReadActivity", "懒解析仓储打开失败: " + t.getMessage(), t);
        }
        if (store != null && store.chapterCount() != chapterList.size()) {
            android.util.Log.w("ReadActivity", "懒解析索引与目录章节数不符: index="
                    + store.chapterCount() + ", toc=" + chapterList.size()
                    + "。可能原因：源文件被替换为另一本，但 SP 中仍保留旧指纹");
            store.close();
            store = null;
        }
        lazyStore = store;
        if (store != null) {
            android.util.Log.d("ReadActivity", "懒解析已接管本书，回源章数=" + store.chapterCount());
        } else {
            android.util.Log.w("ReadActivity", "懒解析不可用，本书正文读不出: bookId=" + bookId
                    + ", src=" + src.substring(0, Math.min(50, src.length())));
        }
        return store;
    }

    /** local_books 中这本书记下的源文件 Uri；槽位下标会随删书重排，所以按 book_id_ 找 */
    private String localBookSourceUri(long bookId) {
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0L) == bookId) {
                String v = sp.getString("book_source_uri_" + i, "");
                return v == null ? "" : v;
            }
        }
        return "";
    }

    /** 本地书正文：一律回源重建；建不起仓储（无索引 / 源已删）返回空串，由开书守卫提示不可读 */
    private String localChapterText(long bookId, int chapterIndex, String title) {
        LazyStore store = lazyStore();
        if (store != null) {
            String s = store.text(chapterIndex, title);
            if (s != null) return s;
        }
        return "";
    }

    /** 本地书保留样式的 HTML：同上回源重建；TXT 懒解析无 HTML（store.html 返回 null）或仓储不可用时返回空串 */
    private String localChapterHtml(long bookId, int chapterIndex) {
        LazyStore store = lazyStore();
        if (store != null) {
            String s = store.html(chapterIndex);
            if (s != null) return s;
        }
        return "";
    }

    /**
     * 重新从本地缓存加载指定章节的内容（用于本地书）
     */
    private void reloadLocalChapterContent(int chapterIndex) {
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        
        // 添加调试日志
        android.util.Log.d("ReadActivity", "reloadLocalChapterContent: bookId=" + currentBook.getId()
                + ", chapterIndex=" + chapterIndex + ", totalBooks=" + count);

        // 内存控制：只保留当前章附近已加载的正文，其余重置为占位。
        //    否则用户一路读下去，翻过的每一章都会常驻 chapterContents/chapterHtmlContents（含 base64 图片），
        //    同样会把堆撑满（此前开书即 OOM 的另一半原因）。
        trimLocalContentCache(chapterIndex);

        for (int i = 0; i < count; i++) {
            long bookId = sp.getLong("book_id_" + i, 0);
            if (bookId == currentBook.getId()) {
                int chCount = sp.getInt("chapter_count_" + i, 0);
                android.util.Log.d("ReadActivity", "Found book at index " + i + ", chapterCount=" + chCount);
                
                if (chapterIndex >= 0 && chapterIndex < chCount) {
                    String title = sp.getString("chapter_title_" + i + "_" + chapterIndex, "第"
                            + (chapterIndex + 1) + "章");
                    // 正文优先读文件缓存；旧书回退读 SP
                    String content = localChapterText(bookId, chapterIndex, title);
                    if (content == null || content.isEmpty()) {
                        content = sp.getString("chapter_content_" + i + "_" + chapterIndex, "");
                    }
                    
                    android.util.Log.d("ReadActivity", "Read content length: " + content.length()
                            + ", isEmpty: " + content.isEmpty());
                    
                    if (!content.isEmpty()) {
                        chapterList.get(chapterIndex).setTitle(title);
                        // 修正：只存储纯内容，不添加标题，避免重复显示
                        setChapterContent(chapterIndex, content);
                        android.util.Log.d("ReadActivity", "Content loaded successfully");
                    } else {
                        android.util.Log.e("ReadActivity", "Content is empty! Key: chapter_content_"
                                + i + "_" + chapterIndex);
                    }

                    // 同步 HTML（本地书靠它保留书内样式）。此前漏写这一步，
                    //    导致按需加载后只能退化成纯文本渲染，书里的排版/字体样式全部丢失。
                    String html = localChapterHtml(bookId, chapterIndex);
                    setChapterHtml(chapterIndex, html != null ? html : "");
                } else {
                    android.util.Log.e("ReadActivity", "Chapter index out of range: " + chapterIndex
                            + ", chCount=" + chCount);
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
        // 关键防御：externalChapters 与 chapterList 长度必须一致，否则跳过
        // 避免异步加载途中共享缓存只写入一半时，目录点章触发 IndexOutOfBounds 崩溃
        if (externalChapters == null || chapterIndex >= externalChapters.length) {
            android.util.Log.w("ReadActivity", "loadExternalChapterContent: externalChapters missing for index="
                    + chapterIndex
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
        
        if (chapterUrl == null || chapterUrl.isEmpty()) {
            android.util.Log.w("ReadActivity", "loadExternalChapterContent: empty chapterUrl for index="
                    + chapterIndex);
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
            } else if (pendingChapterIndex == chapterIndex) {
                // 跨章在途跳转（外站书）：正文缓存已到位 → 走统一渲染推进索引
                //    （修复最后一页向后翻第一次没反应、需再翻一次才到下一章）
                boolean goLast = pendingGoLastPage;
                int pendingPage = pendingChapterPage;
                clearPendingChapterJump();
                runOnUiThread(() -> showPendingChapter(finalChapterIndex, finalCached, goLast, pendingPage));
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
                        final boolean wasPendingJump = pendingChapterIndex == chapterIndex;
                        if (wasPendingJump) clearPendingChapterJump();
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
                        } else if (wasPendingJump && ok) {
                            // 跨章在途跳转（外站书）：网络正文到位 → 走统一渲染推进索引
                            //    （修复最后一页向后翻第一次没反应、需再翻一次才到下一章）
                            boolean goLast = pendingGoLastPage;
                            int pendingPage = pendingChapterPage;
                            runOnUiThread(() -> {
                                showPendingChapter(chapterIndex, finalContent, goLast, pendingPage);
                                preloadNextExternalChapter(chapterIndex);
                            });
                        } else if (wasPendingJump) {
                            runOnUiThread(() -> Hint.show(ReadActivity.this, "下一章加载失败，请重试"));
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<String>> call, @NonNull Throwable t) {
                        // 失败标记仍写入 chapterContents（缓存守卫依赖），但不再把「【网络错误】…」画进正文
                        chapterContents.set(chapterIndex, "【网络错误】" + t.getMessage());
                        final boolean wasPendingJump = pendingChapterIndex == chapterIndex;
                        if (wasPendingJump) clearPendingChapterJump();
                        if (currentChapterIndex == chapterIndex) {
                            runOnUiThread(() -> showLoadFail("网络异常，请检查网络后重试"));
                        } else if (wasPendingJump) {
                            runOnUiThread(() -> Hint.show(ReadActivity.this, "下一章加载失败，请重试"));
                        }
                    }
                });
    }

    /**
     * 预加载下一章：仅在内存/磁盘都未命中时异步拉取，不渲染当前页（loadExternalChapterContent 内部
     * 通过 currentChapterIndex != chapterIndex 的判断自动跳过渲染）。
     */
    /** 外站预取去重（15 秒自动过期，防止网络慢时重复入队）。 */
    private final java.util.Set<Integer> externalPrefetchInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void preloadNextExternalChapter(int currentIndex) {
        // 由近及远预取当前章前后各 CHAPTER_PREFETCH_RADIUS 章（同距离先「下一章」）；
        //    内存/磁盘已命中的自动跳过，成功结果落盘（writeExternalContentToDisk），
        //    第二次打开本书或翻回已读章即点即看。
        for (int d = 1; d <= CHAPTER_PREFETCH_RADIUS; d++) {
            tryPreloadExternalChapter(currentIndex + d);
            tryPreloadExternalChapter(currentIndex - d);
        }
    }

    private void tryPreloadExternalChapter(int idx) {
        if (idx < 0 || idx >= chapterList.size() || idx == currentChapterIndex) return;
        if (externalChapters == null || idx >= externalChapters.length) return;
        if (idx == pendingChapterIndex) return;   // 在途跳转由正常加载链路负责
        String[] p = externalChapters[idx];
        if (p == null) return;
        String url = p.length > 1 ? p[1] : "";
        String key = idx + "|" + url;
        if (externalContentCache.get(key) != null) return;   // 内存已命中
        if (idx < chapterContents.size()) {
            String existing = chapterContents.get(idx);
            if (!isChapterContentPending(existing) && !isExternalFailureContent(existing)) return;   // 已有正文
        }
        if (readExternalContentFromDisk(key) != null) return;   // 磁盘已命中
        if (!externalPrefetchInFlight.add(idx)) return;         // 在途
        mainHandler.postDelayed(() -> externalPrefetchInFlight.remove(idx), 15_000L);
        // 异步预取（loadExternalChapterContent 内部对非当前章不渲染，成功即写内存+磁盘缓存）
        loadExternalChapterContent(idx);
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

    /**
     * 本地书的回源索引是否还在后台构建（刚导入、或索引缺失后触发自愈重建）。
     *
     * <p>这段时间里任何一章都注定读不出正文，属于「还没好」而不是「内容缺失」：
     * 加载动画继续转，索引发布那一刻由 {@link #onLocalCatalogReady} 重载目录并补发渲染。
     * 就绪判据与等待路径同源（{@link #localCatalogReady}），再叠一条「后台确实在跑」，
     * 这样真缺失（源文件没了、索引永远建不起来）仍会走原来的提示，不会被动画无限期吞掉。
     */
    private boolean localCatalogBuilding() {
        if (!isLocalBook) return false;
        long bookId = safeBookId();
        return bookId > 0 && !localCatalogReady(bookId) && LocalBookImport.isBuilding(bookId);
    }

    private void fetchChapterContent(int chapterIndex) {
        try {
        // ===== 外站书籍：走在线 API 获取章节正文 =====
        if (isExternalBook) {
            // 跨章在途跳转（pendingChapterIndex==chapterIndex，章节边界翻页触发）必须保留 pending：
            //    loadExternalChapterContent 在正文到位后按 pending 走 showPendingChapter 统一渲染。
            //    之前在这里就清掉 pending，而其渲染守卫是 currentChapterIndex==chapterIndex
            //    （此刻索引还是旧章，守卫永不满足）→ 内容到位只写缓存不渲染，
            //    表现为「最后一页向后翻第一次没反应，再翻一次才到下一章」。
            loadExternalChapterContent(chapterIndex);
            return;
        }
        if (isLocalBook) {
            // 本地书不应该走网络请求，直接尝试从本地缓存重新加载
            if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();   // 下面会同步渲染
            // 后台目录还在建：这一章读不出正文是必然的，不是「章节内容缺失」。
            //    加载动画继续转，索引发布那一刻 localIndexPoller 会重载精修目录并补发渲染。
            if (localCatalogBuilding()) {
                android.util.Log.d("ReadActivity", "后台目录构建中，本章暂不回源: chapterIndex=" + chapterIndex);
                setChapterLoading(true);
                return;
            }
            reloadLocalChapterContent(chapterIndex);
            String title = chapterList.get(chapterIndex).getTitle();
            String content = chapterContents.get(chapterIndex);
            // 有保留样式的 HTML 同样算「有内容」：极少数章节（题图页等）纯文本很短甚至为空，
            // 但 HTML 里有实体，走 HTML 渲染模式照样能显示出来。
            String html = (chapterIndex >= 0 && chapterIndex < chapterHtmlContents.size())
                    ? chapterHtmlContents.get(chapterIndex) : "";
            boolean hasHtml = html != null && !html.isEmpty();
            if (!isChapterContentPending(content) || hasHtml) {
                // 与服务器章节路径保持一致：渲染前先把「当前章」切过来，
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
        
        // 简化：直接检查索引范围
        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
            android.util.Log.e("ReadActivity", "fetchChapterContent: chapterIndex out of range! index="
                    + chapterIndex + ", size=" + chapterList.size());
            if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();
            setChapterLoading(false);   // 该分支不会触发渲染，别让动画一直转
            Hint.show(this, "章节加载中，请稍后...");
            return;
        }
        
        long chapterId = chapterList.get(chapterIndex).getId();
        android.util.Log.d("ReadActivity", "fetchChapterContent: chapterIndex=" + chapterIndex
                + ", chapterId=" + chapterId);
        
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
                    
                    android.util.Log.d("ReadActivity", "fetchChapterContent onResponse: chapterIndex="
                            + chapterIndex + ", currentChapterIndex=" + currentChapterIndex
                            + ", content length=" + realContent.length());
                    
                    if (pendingChapterIndex == chapterIndex) {
                        // 用户是在章节边界翻页触发的加载：正文到位后再切换过去（之前没推进过索引，
                        //    所以这里不会出现"加载完直接跳两章"）
                        boolean goLast = pendingGoLastPage;
                        int pendingPage = pendingChapterPage;
                        clearPendingChapterJump();
                        android.util.Log.d("ReadActivity", "Pending chapter jump done: chapterIndex="
                                + chapterIndex + ", goLastPage=" + goLast + ", page=" + pendingPage);
                        runOnUiThread(() -> showPendingChapter(chapterIndex, realContent, goLast, pendingPage));
                    } else if (currentChapterIndex == chapterIndex) {
                        // 复用池修复：正文异步到达时沿用「恢复目标页码」重渲染，
                        //    否则默认 page=1 会把恢复位置冲掉，导致「重新打开总落第 1 页」。
                        //    若 restoreTargetPage<=0（如未指定），则回退首页。
                        int _restorePage = (restoreTargetPage > 0) ? restoreTargetPage : 1;
                        android.util.Log.d("ReadActivity", "Re-rendering chapter " + chapterIndex
                                + " with fetched content, restoreTargetPage=" + _restorePage);
                        renderChapterContent(chapterIndex, chapterList.get(chapterIndex).getTitle(),
                                realContent, _restorePage);
                    } else {
                        android.util.Log.d("ReadActivity", "Not current chapter, skip re-render");
                    }
                } else {
                    if (pendingChapterIndex == chapterIndex) clearPendingChapterJump();
                    runOnUiThread(() -> showLoadFail("加载出错，请稍后再试"));
                }
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse<com.example.myapplication.bean.Chapter>> call,
                    @NonNull Throwable t) {
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
     * 支持传入目标页码，避免先显示第1页再动画跳转
     * @param startPage 从1开始的页码；传 <=0 表示不指定(=第1页)
     */
    private void renderChapterContent(int chapterIndex, String title, String content, int startPage) {
        // 每次外部渲染请求都生成新代次：使在途的旧延迟重试自动失效
        final int myGen = ++renderGeneration;
        renderChapterContentInternal(chapterIndex, title, content, startPage, myGen);
    }

    /**
     * renderChapterContent 的内部实现：携带本次渲染代次 myGen。
     * WebView 未就绪时的 50ms 延迟重试若发现代次已过期（期间有更新的渲染请求），
     * 则直接丢弃——不渲染、不写 currentChapterIndex。
     */
    private void renderChapterContentInternal(int chapterIndex, String title, String content,
            int startPage, int myGen) {
        try {
            // ★ 占位/未加载：正文用零宽字符顶替 —— 页面由「章节加载中」动画接管，
            //   不再把哨兵串「【章节加载中...】」当正文画出来。
            //   chapterContents 里的哨兵**原样保留**，isChapterContentPending 的判定不受影响。
            final boolean pendingContent = isChapterContentPending(content);
            // 只要渲染的是占位内容，就把加载动画顶上来 —— 阅读器有多个入口会直接渲染占位
            // （首屏恢复进度 / 章节列表还没到 / 外站翻章 / 本地书按需读取），在这里统一兜住，免得逐个入口漏加。
            if (pendingContent) setChapterLoading(true);
            else hideLoadFail();   // 渲染到真实正文 → 失败态自动退场（上次失败重试成功后也走这里）

            // 关键修复（复用池回归）：渲染必须等 WebView 真正完成布局、拿到有效宽度后再分页。
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
                    // 过期重试丢弃：已有更新的渲染请求发生（例如服务器正文已渲染目标章），本次作废
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
            // -1 表示跳到最后一页（loadChapterContentToLastPage 使用），必须原样传给 JS
            //    否则会被转成 null，JS loadContent 命中 else 分支回到第一页
            String pageArg = (startPage == -1) ? "-1" : (startPage > 0 ? String.valueOf(startPage) : "null");

            // 排版缓存：非占位内容时，按 layout signature 读取上次退出本章时的分页结果；
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

            // 本地 EPUB 且存在保留样式的 HTML → 走 HTML 渲染模式（展示样式而非纯文本）
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
                // 包装 loadContent 在 JS try/catch 中，捕获 JS 渲染异常并回传
                js = "(function(){" +
                        "try{" +
                        "var r=loadContent(" + chapterIndex + "," + escapedTitle + "," + escapedContent + "," +
                        pageArg + "," + isLocalBook + "," + bookProgress + "," + escapedCachedJson
                                + "," + escapedCacheKey + ");" +
                        "return 'ok';" +
                        "}catch(e){" +
                        "return 'error:'+e.message;" +
                        "}" +
                        "})()";
            }
            webView.evaluateJavascript(js, value -> {
                // 真实正文已渲染到位 → 收起「章节加载中」动画。
                // 放在 JS 回调里收（而不是发指令前），确保版面确实换成了正文再撤掉遮罩。
                // 本地书存在「纯文本是占位、但带保留样式的 HTML」（题图页等）：这种情况走 HTML 分支
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
                                "try{var r=loadContent(" + chapterIndex + "," + escapedTitle
                                        + "," + escapedContent + "," +
                                pageArg + "," + isLocalBook + "," + bookProgress + ","
                                        + escapedCachedJson + "," + escapedCacheKey + ");return 'ok';" +
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

            // 关键修复（覆盖全部主题模式）：从排版缓存恢复章节时，loadContent 直接把「上次分页时固化的文字色/
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
     * 应用页眉页脚设置到 WebView
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
     * 应用字体大小到 WebView
     */
    private void applyFontSizeToWebView() {
        if (isWebViewReady) {
            webView.evaluateJavascript("setFontSize(" + currentFontSize + ")", null);
        }
    }

    /**
     * 应用字体族到 WebView
     */
    void applyFontFamilyToWebView() {
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
        // 章节边界翻页到位后同样触发预取（前后各 5 章，后台落盘）
        prefetchServerChaptersAround(chapterIndex);
    }

    /**
     * 跨章无缝翻页（JS 桥）：解析邻章已就绪正文，供边界翻页复用换页动画。
     * 返回 JSON {"index":N,"title":"...","content":"..."}；未就绪 / 异常一律返回空串。
     * 运行在 WebView 桥线程：只做只读访问（ArrayList 按索引读不会 CME；SP / LruCache 线程安全）。
     */
    String resolveAdjacentChapterPayload(int dir) {
        int target = currentChapterIndex + (dir > 0 ? 1 : -1);
        if (target < 0 || target >= chapterList.size()) return "";
        String content = null;
        if (target < chapterContents.size()) {
            String c = chapterContents.get(target);
            if (!isChapterContentPending(c)) content = c;
        }
        if (content == null && isLocalBook) {
            // 本地书正文：一律回源重建（与 reloadLocalChapterContent 同源）
            try {
                String t = chapterList.get(target).getTitle();
                String c = localChapterText(safeBookId(), target, t);
                if (c != null && !c.trim().isEmpty()) content = c;
            } catch (Throwable ignored) { }
        }
        if (content == null && isExternalBook) {
            // 外站书：内存 Lru → 磁盘 SP（与 tryPreloadExternalChapter 同一套缓存与 key）
            try {
                if (externalChapters != null && target < externalChapters.length
                        && externalChapters[target] != null) {
                    String[] p = externalChapters[target];
                    String url = p.length > 1 ? p[1] : "";
                    if (!url.isEmpty()) {
                        String key = target + "|" + url;
                        String c = externalContentCache.get(key);
                        if (c == null || c.isEmpty() || isExternalFailureContent(c)) {
                            c = readExternalContentFromDisk(key);
                        }
                        if (c != null && !c.isEmpty() && !isExternalFailureContent(c)) content = c;
                    }
                }
            } catch (Throwable ignored) { }
        }
        // 服务器书：内存 chapterContents 未就绪不再兜底（fetchChapterContent 的 pending 路径走网络，
        // SP 兜底会让 JS 先进新章而 Java 滞留旧章）→ 返回空串走原有加载流程。
        if (content == null) return "";
        // 本地书邻章带保留样式 HTML：渲染会切到 HTML 模式（版式与纯文本分页完全不同），不做动画
        if (isLocalBook && target < chapterHtmlContents.size()
                && chapterHtmlContents.get(target) != null && !chapterHtmlContents.get(target).isEmpty()) {
            return "";
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("index", target);
            String t = chapterList.get(target).getTitle();
            o.put("title", t == null ? "" : t);
            o.put("content", content == null ? "" : content);
            return o.toString();
        } catch (Throwable t2) {
            return "";
        }
    }

    /**
     * 获取当前电量和时间，更新到 WebView
     */
    void updateBatteryAndTime() {
        if (!showBatteryTime || !showHeaderFooter) return;
        if (!isWebViewReady) {
            // WebView 还没就绪（典型场景：onResume 早于 onPageFinished）。
            // 不必在这里硬推，onPageFinished 会再调用一次 applyHeaderFooterSettings() 补发。
            return;
        }

        Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        String batteryText = "";
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra("level", -1);
            int scale = batteryStatus.getIntExtra("scale", 100);
            int pct = (level * 100) / scale;
            batteryText = pct + "%";
        }

        String timeText = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());

        String js = "updateBatteryTime(" + JSONObject.quote(batteryText) + "," + JSONObject.quote(timeText) + ")";
        webView.evaluateJavascript(js, null);
    }

    /** 加载章节并直接跳转到指定页（页码从1开始） */
    private void loadChapterContentWithPage(int chapterIndex, int page) {
        // 服务端进度比本地新时允许越过「已恢复位置」守卫重渲染一次（见 adoptServerPosition）
        boolean forced = forceRestorePosition;
        forceRestorePosition = false;
        // 复用池修复：记住本次恢复的目标页码，正文异步到达后重渲染沿用（见 fetchChapterContent.onResponse）
        restoreTargetPage = (page > 0) ? page : 1;
        try {
        android.util.Log.d("ReadActivity", "loadChapterContentWithPage: chapterIndex=" + chapterIndex
                + ", page=" + page + ", chapterList.size()=" + chapterList.size());

        // 防止重复渲染：如果已经正确恢复了位置，跳过（避免 fetchServerProgress 等后续调用导致闪屏）
        if (positionRestored && !forced) {
            android.util.Log.d("ReadActivity", "positionRestored already true, skip loadChapterContentWithPage");
            return;
        }

        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
            android.util.Log.e("ReadActivity",
                    "loadChapterContentWithPage: chapterIndex out of range! Will wait for full chapter list...");
            // 修正：如果索引超出范围，不直接返回，而是等待完整章节列表加载
            // 这种情况通常发生在章节列表尚未完全加载时
            return;
        }

        String title = chapterList.get(chapterIndex).getTitle();
        // 防御：chapterContents 越界时自动补齐
        if (chapterIndex >= chapterContents.size()) {
            while (chapterContents.size() < chapterList.size()) chapterContents.add("【正在加载...】");
        }
        String content = chapterContents.get(chapterIndex);

        android.util.Log.d("ReadActivity", "loadChapterContentWithPage: title=" + title
                + ", content length=" + (content != null ? content.length() : 0));

        // 检查内容是否有效，如果无效则重新从本地缓存加载
        boolean isPlaceholder = isChapterContentPending(content);
        if (isLocalBook && isPlaceholder) {
            // 后台目录还在建：这一章读不出正文是必然的，交给加载动画等索引发布，
            //    既不能把占位串当正文画出去，也不能弹「内容加载中」的提示。
            if (localCatalogBuilding()) {
                setChapterLoading(true);
                return;
            }
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

        // 应用设置到 WebView
        applyHeaderFooterSettings();
        applyFontSizeToWebView();
        applyFontFamilyToWebView();
        if (!isNightMode) applyBackgroundColorToWebView(currentBgColor);

        // 修正：成功调用 loadContent 后，设置 positionRestored 标志
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
        // 防御：chapterContents 越界自动补齐
        if (chapterIndex >= chapterContents.size()) {
            while (chapterContents.size() < chapterList.size()) chapterContents.add("【正在加载...】");
        }
        String content = chapterContents.get(chapterIndex);

        // 检查内容是否有效，如果无效则重新从本地缓存加载
        boolean isPlaceholder = isChapterContentPending(content);
        if (isLocalBook && isPlaceholder) {
            // 后台目录还在建：同 loadChapterContentWithPage，交给加载动画等索引发布
            if (localCatalogBuilding()) {
                setChapterLoading(true);
                return;
            }
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

        // 应用设置到 WebView
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

        // 如果是已下载的自定义字体，注入 FontFace 加载路径（在 loadContent 后加载并重绘）
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
                // 纹理背景：设置基础底色防止首帧白屏，然后启动纹理加载
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

        // 启动加载待加载的字体/纹理资源（在 HTML 加载完成后立即开始，比等 loadContent 调用更快）
        sb.append("if (typeof startPendingResources === 'function') { startPendingResources(); }");

        // 关键修复：如果是仿真模式，立即展示书本占位容器，而不是显示空 canvas
        // 容器会显示正确的背景色/纹理，与最终内容呈现风格一致，避免"空白米黄页"
        if ("simulation".equals(pageTurnMode)) {
            sb.append("if (typeof showSimulationPlaceholder === 'function') { showSimulationPlaceholder(); }");
        }

        return sb.toString();
    }

    private void applySettingsToWebView() {
        webView.evaluateJavascript("beginSettingsBatch()", null);
        applySettingsToWebView_inner();
        // 页眉页脚 / 电量时间也要一起应用：从本地缓存恢复章节时只走这个方法，
        // 之前漏掉它就导致页脚电量时间不显示
        applyHeaderFooterSettings();
        webView.evaluateJavascript("finishSettingsBatch()", null);
        webView.evaluateJavascript("setPageTurnMode('" + pageTurnMode + "')", null);
        webView.evaluateJavascript("setSwipeEnabled(" + swipePageTurn + ")", null);
    }

    /** 内部方法：应用字体、背景等设置（不含 batch 包装和翻页模式） */
    private void applySettingsToWebView_inner() {
        webView.evaluateJavascript("setFontSize(" + currentFontSize + ")", null);
        // 间距设置（行距/段距/边距）：与字号同批注入；padTB=-1 时 JS 侧走默认公式保持旧视觉
        webView.evaluateJavascript("setTypesetting(" + lineSpacingRatio + "," + paraGapRatio
                + "," + padLR + "," + padTB + ")", null);
        applyFontFamilyToWebView();
        if (isNightMode) {
            webView.evaluateJavascript("setNightMode(true)", null);
            effectiveBgBase = Color.parseColor("#1A1A1A");
        } else {
            applyBackgroundColorToWebView(currentBgColor);
        }
    }

    void applyBackgroundColorToWebView(int mode) {
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
    int resolveBaseForMode(int mode) {
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
                // 关键：MotionEvent 会先派发给最上层的左右热区 View（activity_read.xml 里
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
        // 设置浮窗的窗口下沿收缩到本行上沿后，本行已不被浮窗遮罩覆盖（保持可见、可点），
        // 正常点击根本到不了这里——由浮窗的窗口外触摸拦截转成按钮点击（见
        // forwardOutsideTouchToNavButton）。下面两处兜底只覆盖窗口尚未收缩到位那一帧的直达点击，
        // 语义与之一致：先收起当前浮窗，其 onDismiss 里再补发这次点击。
        btnCatalog.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (isSettingsPopupShowing()) {
                pendingNavActionAfterDismiss = v::performClick;
                dismissSettingsAnimated();
                return;
            }
            showChapterPopup();
        });
        btnNightMode.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (isSettingsPopupShowing()) {
                pendingNavActionAfterDismiss = v::performClick;
                dismissSettingsAnimated();
                return;
            }
            animateNightModeToggle();
        });
        btnSettings.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (isSettingsPopupShowing()) {
                pendingNavActionAfterDismiss = v::performClick;
                dismissSettingsAnimated();
                return;
            }
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
        hideSystemBars();

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
    private void ensureBottomNavHeight() {        if (bottomNavHeight <= 0) {
            // 先按导航条 inset 让位，再量高度：滑距用的是量出来的自身高度，
            // 顺序反了「目录/夜间/设置」那行会整条停在导航键底下。
            applyBottomNavInset();
            int w = getResources().getDisplayMetrics().widthPixels;
            int widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY);
            int heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(0,
                    android.view.View.MeasureSpec.UNSPECIFIED);
            layoutBottomNav.measure(widthSpec, heightSpec);
            bottomNavHeight = layoutBottomNav.getMeasuredHeight();
        }
    }

    /**
     * 三键导航机型上把底部工具行顶到导航键上方：面板表面照旧贴屏幕物理底边，只让内容上移。
     *
     * <p>本窗口用 {@code setDecorFitsSystemWindows(false)} 保持「高度恒等于物理屏高」，
     * 好让 WebView 的 innerHeight 不随系统栏显隐变化而重新分页，代价是导航条 inset 没人消费，
     * 底部面板得自己让位。手势导航机型上这个 inset 恒为 0，等于不做事。
     *
     * <p>取值只能信 {@link #watchNavBarInset()} 记下的历史最大值：导航条现在随工具栏一起隐藏，
     * 隐藏态下当场读 {@code getRootWindowInsets()} 会得到 0，照那个值让位等于没让。
     */
    private void applyBottomNavInset() {
        if (bottomNavInsetApplied || navBarInsetPx <= 0) {
            return;
        }
        bottomNavInsetApplied = true;
        layoutBottomNav.setPadding(layoutBottomNav.getPaddingLeft(),
                layoutBottomNav.getPaddingTop(),
                layoutBottomNav.getPaddingRight(), navBarInsetPx);
    }

    /**
     * 记下导航条的真实占位高度。
     *
     * <p>窗口首次布局时导航条还看得见，这次分发带的是实际值（三键机型 126px）；
     * 之后每次隐藏都分发 0，所以只认非零值。收到新值时把「已让位」标记清掉，
     * 下次滑入前重新按新占位量一次。
     */
    private void watchNavBarInset() {
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            if (bottom > navBarInsetPx) {
                navBarInsetPx = bottom;
                bottomNavInsetApplied = false;
                bottomNavHeight = 0;
                // 万一首次分发就已经是隐藏态（0），这里收到的就是「工具栏滑出后才到」的迟到值：
                // 工具栏正显示着，当场补一次让位，别等下一次滑入。
                if (layoutBottomNav != null && layoutBottomNav.getVisibility() == View.VISIBLE) {
                    applyBottomNavInset();
                }
            }
            return insets;
        });
    }

    private void showNavigation() {
        showSystemBars();  // 与滑入同步显示，避免动画结束后系统栏再突现

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
        hideSystemBars();  // 与滑出同步隐藏，避免动画结束后系统栏再突隐

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

    void resetAutoHideTimer() {
        mainHandler.removeCallbacks(hideNavRunnable);
        mainHandler.postDelayed(hideNavRunnable, 5000);
    }

    /**
     * 翻过一页即收起导航栏：底栏是按需唤出的浮层，用户回到翻页就说明不需要它了。
     *
     * <p>两种情况不收：自动翻页进行中（否则每 N 秒把用户刚唤出的底栏抽走）；
     * 任一浮窗开着（浮窗窗口下沿按底栏上沿算，底栏滑走会让浮窗悬空）。</p>
     */
    void hideNavOnPageTurn() {
        if (autoPageEnabled || isAnyReaderPopupShowing()) return;
        if (layoutTopNav.getVisibility() != View.VISIBLE
                && layoutBottomNav.getVisibility() != View.VISIBLE) return;
        mainHandler.removeCallbacks(hideNavRunnable);
        hideNavigation();
    }

    /** 阅读器当前是否有浮窗（目录/设置/二级抽屉/更多菜单）在显示 */
    private boolean isAnyReaderPopupShowing() {
        return (chapterPopupWindow != null && chapterPopupWindow.isShowing())
                || (settingsPopupWindow != null && settingsPopupWindow.isShowing())
                || (moreSettingsPopupWindow != null && moreSettingsPopupWindow.isShowing())
                || (bgColorsPopupWindow != null && bgColorsPopupWindow.isShowing())
                || (fontsPopupWindow != null && fontsPopupWindow.isShowing())
                || (moreMenuPopupWindow != null && moreMenuPopupWindow.isShowing())
                || (spacingPopup != null && spacingPopup.isShowing());
    }

    private void dismissPopups() {
        // 页面切换/销毁时的统一关闭：跳过各浮窗的退场动画，动画也没时间播
        SlideOutPopupWindow.dismissImmediate(chapterPopupWindow);
        SlideOutPopupWindow.dismissImmediate(moreMenuPopupWindow);
        SlideOutPopupWindow.dismissImmediate(settingsPopupWindow);
        SlideOutPopupWindow.dismissImmediate(moreSettingsPopupWindow);
        SlideOutPopupWindow.dismissImmediate(bgColorsPopupWindow);
        SlideOutPopupWindow.dismissImmediate(fontsPopupWindow);
    }

    // ==================== 目录 / 书签弹窗（实现见 ReadTocPopupController） ====================
    private void showChapterPopup() { tocController.showChapterPopup(); }

    /** 目录弹窗相关报错时统一弹对话框（实现见 ReadTocPopupController） */
    private void showPopupErrorDialog(String reason) { tocController.showPopupErrorDialog(reason); }

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

        editor.putLong("record_bookId_" + existIndex, bidForSave);
        editor.putString("record_bookName_" + existIndex, currentBook.getBookName());
        editor.putString("record_author_" + existIndex, currentBook.getAuthor() != null ? currentBook.getAuthor() : "");
        editor.putString("record_cover_" + existIndex, currentBook.getCover() != null ? currentBook.getCover() : "");

        editor.putInt("record_chapterIndex_" + existIndex, currentChapterIndex);
        String chapterTitle = (currentChapterIndex < chapterList.size()) ?
                chapterList.get(currentChapterIndex).getTitle() : "";
        editor.putString("record_chapterTitle_" + existIndex, chapterTitle);
        editor.putInt("record_totalChapters_" + existIndex, chapterList.size());

        editor.putInt("record_page_" + existIndex, currentPageInChapter);
        editor.putInt("record_totalPages_" + existIndex, totalPagesInChapter);
        // 使用实际章节标题来计算进度，避免特殊章节影响
        int bookProgress = (!chapterList.isEmpty()) ?
                (int)((currentChapterIndex + 1) * 100f / chapterList.size()) : 0;
        editor.putInt("record_progress_" + existIndex, bookProgress);

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
            @Override public void onResponse(@NonNull Call<ApiResponse<ReadingProgress>> call,
                    @NonNull Response<ApiResponse<ReadingProgress>> response) {}
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
                    @Override public void onResponse(@NonNull Call<ApiResponse<Void>> call,
                            Response<ApiResponse<Void>> r) {}
                    @Override public void onFailure(@NonNull Call<ApiResponse<Void>> call, @NonNull Throwable t) {}
                });
    }

    // ==================== 阅读偏好与设置 ====================

    // ========== 阅读亮屏控制（「更多设置 → 屏幕亮屏时间」） ==========
    /** 定时模式是否已处于「到期熄屏」状态（背光已压 0）。 */
    private boolean screenBlackoutApplied = false;

    /**
     * 定时亮屏模式到期：主动把本窗口背光压到 0（真正的物理熄屏，无需任何权限）。
     * 不能只 clearFlags(FLAG_KEEP_SCREEN_ON) 放行系统熄屏——系统熄屏时点从「最后一次
     * 触摸」起算（vivo 的该值很长且实测不执行短超时），结果只会进入系统压暗阶段迟迟不灭，
     * 用户感知为「到点不熄屏只压暗」。这里保留 KEEP_SCREEN_ON 让屏幕处于「亮着但背光为 0」
     * 的黑屏态：触摸任意位置由 onUserInteraction 恢复，按电源键则真熄屏。
     */
    private final Runnable screenOffRunnable = () -> setReaderBacklight(false);

    /** 恢复/压灭本窗口背光（on=true 正常亮度，off=黑屏）。 */
    private void setReaderBacklight(boolean on) {
        if (isFinishing()) return;
        android.view.Window w = getWindow();
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        float target = on
                ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        // BRIGHTNESS_OVERRIDE_NONE = -1 表示「不覆盖」，直接比较设置避免每帧写 attributes
        if (lp.screenBrightness != target) {
            lp.screenBrightness = target;
            w.setAttributes(lp);
        }
        screenBlackoutApplied = !on;
    }

    /** 按当前 screenOnTimeoutMs 应用亮屏策略（进书/切换设置/回到前台时调用）。 */
    void applyScreenKeepAlive() {
        mainHandler.removeCallbacks(screenOffRunnable);
        // 任何策略（重）应用时先恢复正常背光（清除可能残留的黑屏态）
        setReaderBacklight(true);
        if (screenOnTimeoutMs == 0) {
            // 跟随系统：交还系统熄屏节奏
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            // 定时 / 常亮：保持亮屏；定时模式到期后由 screenOffRunnable 主动压灭背光
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (screenOnTimeoutMs > 0) {
                mainHandler.postDelayed(screenOffRunnable, screenOnTimeoutMs);
            }
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        // 定时亮屏模式下：① 已黑屏则触摸立即恢复背光（模拟「点亮屏幕」）；
        // ② 任意触摸/按键都重新计时。常亮与跟随系统无需处理。
        if (screenOnTimeoutMs > 0 && !isFinishing()) {
            if (screenBlackoutApplied) {
                setReaderBacklight(true);
            }
            mainHandler.removeCallbacks(screenOffRunnable);
            mainHandler.postDelayed(screenOffRunnable, screenOnTimeoutMs);
        }
    }

    private void loadReadingPreferencesNoApply() {
        SharedPreferences sp = getSharedPreferences("read_settings", MODE_PRIVATE);
        currentFontSize = sp.getFloat("font_size", 22f);
        isNightMode = sp.getBoolean("night_mode", false);
        currentBgColor = sp.getInt("bg_color", 0);
        // 黑色背景(#1A1A1A)≡夜间模式，两者统一为一个状态：
        // 旧版本允许「日间 + 黑色背景」（bg_color=3），会导致日/夜切换按钮动画两端同色、看似无反应。
        // 加载时把该残留状态归一为夜间模式；退出夜间时由 animateNightModeTo(false) 兜底回退纯白。
        if (currentBgColor == 3) isNightMode = true;
        // 加载页眉页脚设置
        showHeaderFooter = sp.getBoolean("show_header_footer", true);
        headerFooterFontSize = sp.getFloat("header_footer_font_size", 12f); // 默认值改为12
        showBatteryTime = sp.getBoolean("show_battery_time", false);
        volumeKeyPageTurn = sp.getBoolean("volume_key_page_turn", true);
        swipePageTurn = sp.getBoolean("swipe_page_turn", true);
        pageTurnMode = sp.getString("page_turn_mode", "cover");
        // 加载字体设置
        currentFontFamily = sp.getString("font_family", "sans-serif");
        // 间距设置（缺省时保持旧行为：行距1.8 / 段距0.5行 / 边距走默认公式）
        lineSpacingRatio = sp.getFloat("line_spacing_ratio", 1.8f);
        paraGapRatio = sp.getFloat("para_gap_ratio", 0.5f);
        padLR = sp.getInt("padding_lr", 16);
        padTB = sp.getInt("padding_tb", -1);
        // 加载自动翻页设置（间隔与开关状态都持久化）
        autoPageEnabled = sp.getBoolean("auto_page", false);
        autoPageInterval = sp.getInt("auto_page_interval", 5000);
        // 加载阅读亮屏时间设置
        screenOnTimeoutMs = sp.getLong("screen_on_timeout", 0L);
        // 展示名也要持久化：否则重启后未打开字体弹窗时只能回退到 cssName
        currentFontDisplay = sp.getString("font_display", "默认字体");
        downloadedFonts = new java.util.HashSet<>(sp.getStringSet("downloaded_fonts", new java.util.HashSet<>()));
    }

    void saveReadingPreferences() {
        SharedPreferences sp = getSharedPreferences("read_settings", MODE_PRIVATE);
        sp.edit()
                .putFloat("font_size", currentFontSize)
                .putBoolean("night_mode", isNightMode)
                .putInt("brightness", currentBrightness)
                .putBoolean("follow_system_brightness", followSystemBrightness)
                .putInt("bg_color", currentBgColor)
                // 保存页眉页脚设置
                .putBoolean("show_header_footer", showHeaderFooter)
                .putFloat("header_footer_font_size", headerFooterFontSize)
                .putBoolean("show_battery_time", showBatteryTime)
                .putBoolean("volume_key_page_turn", volumeKeyPageTurn)
                .putBoolean("swipe_page_turn", swipePageTurn)
                .putString("page_turn_mode", pageTurnMode)
                // 保存字体设置
                .putString("font_family", currentFontFamily)
                // 保存间距设置
                .putFloat("line_spacing_ratio", lineSpacingRatio)
                .putFloat("para_gap_ratio", paraGapRatio)
                .putInt("padding_lr", padLR)
                .putInt("padding_tb", padTB)
                // 保存自动翻页设置
                .putBoolean("auto_page", autoPageEnabled)
                .putInt("auto_page_interval", autoPageInterval)
                .putString("font_display", currentFontDisplay)
                .putStringSet("downloaded_fonts", downloadedFonts)
                // 保存阅读亮屏时间设置
                .putLong("screen_on_timeout", screenOnTimeoutMs)
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

    // ==================== 配色与主题：实现见 ReadThemeController（2026-10-02 拆出） ====================

    private final ReadThemeController themeController = new ReadThemeController(this);

    /** 前台活跃实例维护（onResume/onPause 调用），供浮窗染色的宿主判定。 */
    static void noteActiveInstance(ReadActivity a) { ReadThemeController.noteActive(a); }
    static void clearActiveInstance(ReadActivity a) { ReadThemeController.clearActive(a); }

    // ---- 静态取色/染色接口（浮窗 Fragment / Adapter 使用），委托控制器 ----
    public static boolean isChromeDark() { return ReadThemeController.isChromeDark(); }
    public static int getAccentColor() { return ReadThemeController.getAccentColor(); }
    public static int getTextPrimaryColor() { return ReadThemeController.getTextPrimaryColor(); }
    public static int getTextSecondaryColor() { return ReadThemeController.getTextSecondaryColor(); }
    public static int getChromeBgColor() { return ReadThemeController.getChromeBgColor(); }
    public static int getChromeBgSecondaryColor() { return ReadThemeController.getChromeBgSecondaryColor(); }
    public static int getChromeLineColor() { return ReadThemeController.getChromeLineColor(); }
    public static int getNavTextColor() { return ReadThemeController.getNavTextColor(); }
    public static void themeViewTree(@Nullable View root) { ReadThemeController.themeViewTree(root); }
    /** 按 a 的比例把 c1 混向 c2（a=0 得 c1，a=1 得 c2）。 */
    static int mixColors(int c1, int c2, float a) { return ReadThemeController.mixColors(c1, c2, a); }

    void applyChromeTheme() { themeController.applyChromeTheme(); }
    private void animateNightModeToggle() { themeController.animateNightModeToggle(); }
    void animateNightModeTo(boolean toNight) { themeController.animateNightModeTo(toNight); }
    private int getNavBarHeightPx() { return themeController.getNavBarHeightPx(); }

    /**
     * 设置浮窗开启时：「设置」图标由描边齿轮切换为<b>实心齿轮</b>（不加圆底），
     * 齿轮颜色与平时保持一致（不随之变色）；底部「设置」文字颜色也不变。
     * 关闭时恢复为描边齿轮 + 主题次色文字。
     */
    void setSettingsNavActive(boolean active) {
        ImageView iv = findViewById(R.id.iv_settings_icon);
        TextView tv = findViewById(R.id.tv_settings_text);
        if (iv == null || tv == null) return;

        // 齿轮颜色不随选中态改变：日间跟随主文字色（近黑），夜间跟随其他图标用白色
        int gearColor = getTextPrimaryColor();

        iv.setBackground(null);       // 不加圆底
        iv.setPadding(0, 0, 0, 0);
        iv.clearColorFilter();
        iv.setImageResource(active ? R.drawable.ic_settings_filled
                : (isChromeDark() ? R.drawable.ic_settings_white : R.drawable.ic_settings_black));
        // 用 setImageTintList（与 XML 的 app:tint 同通道），setColorFilter 会被 AppCompat tint 覆盖
        iv.setImageTintList(ColorStateList.valueOf(gearColor));

        if (!active) {
            tv.setTextColor(getNavTextColor());
        }
        // active 时文字颜色保持不变（不改成蓝色）
    }

    /**
     * 目录抽屉开启时：「目录」图标由描边列表切换为<b>实心列表</b>（与设置图标同一套路，不加圆底）。
     * 实心版把外框填实、原线条与圆点挖空，于是挖空处露出底栏底色；图标颜色本身不变。
     */
    void setCatalogNavActive(boolean active) {
        ImageView iv = findViewById(R.id.iv_catalog_icon);
        if (iv == null) return;
        iv.setImageResource(active ? R.drawable.ic_list_filled
                : (isChromeDark() ? R.drawable.ic_list_white : R.drawable.ic_list_black));
        iv.setImageTintList(ColorStateList.valueOf(getTextPrimaryColor()));
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
        scrim.setOnClickListener(v -> {
            requestHideNavWithSheet();
            dismissSettingsAnimated();
        });

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
        root.setDismissAction(() -> SlideOutPopupWindow.dismissImmediate(settingsPopupWindow));

        SlideOutPopupWindow settingsPopup = new SlideOutPopupWindow(root,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true);
        settingsPopupWindow = settingsPopup;
        settingsPopup.setAnimationStyle(0); // 取消整窗位移动画
        settingsPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
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
                forwardOutsideTouchToNavButton(v, event, this::dismissSettingsAnimated);
                return true; // 吃掉事件，阻止 PopupWindow 自行 dismiss（那是不带动画的）
            }
            return false;
        });
        // 返回键：PopupWindow 的 DecorView 会自己消费 BACK 并 dismiss()，内容视图上的按键监听
        // 收不到，所以接管 dismiss() 本身，让它走与点面板外同一条收回动画。
        settingsPopup.setSlideOut(this::dismissSettingsAnimated);
        // 先按全屏显示（保证能拿到稳定坐标系测量），首帧 pre-draw 里再把高度收缩到该行上沿
        settingsPopupWindow.showAtLocation(layoutBottomNav, Gravity.TOP, 0, 0);

        setSettingsNavActive(true);
        suspendAutoPage();
        mainHandler.removeCallbacks(hideNavRunnable);
        settingsPopupWindow.setOnDismissListener(() -> {
            settingsPanelDismissing = false;
            finishSheetDismiss();
            resumeAutoPageIfSuspended();
            setSettingsNavActive(false);
            settingsPanelView = null;
            runPendingNavAction();
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
     * 设置浮窗与目录抽屉共用，保证两者出现/消失的位置完全一致。
     */
    int measureBottomRowTopInRoot(View root) {
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

    boolean isChapterPopupShowing() {
        return chapterPopupWindow != null && chapterPopupWindow.isShowing();
    }

    /**
     * 被浮窗窗口「吃掉」、等收起动画结束后要补发的底栏点击。
     * 一次手势里 DOWN/MOVE/UP 都会进拦截器，只认第一次，避免后到的事件把动作覆盖掉。
     */
    private Runnable pendingNavActionAfterDismiss;

    /**
     * 底栏「目录 / 夜间 / 设置」这一行落在浮窗窗口之外（窗口下沿被收缩到该行上沿），
     * 点它时事件只会以「点浮窗外部」的形式到达弹窗窗口，根本到不了 Activity 的按钮。
     * 这里把越界坐标换算回屏幕坐标、命中该行按钮：先播当前浮窗的收回动画，
     * 动画结束（onDismiss）后补发该按钮的点击，目录浮窗与设置浮窗因此能互相顶替。
     *
     * <p>命中的按钮若正是当前浮窗自己的入口（开着目录再点目录），只收起、不再打开，
     * 保住「再点一次即关闭」的开关语义。
     */
    void forwardOutsideTouchToNavButton(View popupRoot, MotionEvent ev, Runnable dismissAction) {
        if (pendingNavActionAfterDismiss == null) {
            View hit = navButtonAtWindowPoint(popupRoot, ev);
            if (hit != null) {
                boolean ownEntry = (hit == btnSettings && isSettingsPopupShowing())
                        || (hit == btnCatalog && isChapterPopupShowing());
                if (!ownEntry) pendingNavActionAfterDismiss = hit::performClick;
            }
        }
        dismissAction.run();
    }

    /** 收起动画跑完后补发被窗口吃掉的底栏点击（设置浮窗与目录抽屉的 onDismiss 共用）。 */
    void runPendingNavAction() {
        Runnable action = pendingNavActionAfterDismiss;
        pendingNavActionAfterDismiss = null;
        if (action != null) action.run();
    }

    /** 本次关闭是否由「点正文空白」发起 —— 是则浮窗收起后把上下导航栏一并滑走。 */
    private boolean hideNavWithSheet;

    /**
     * 「点正文/遮罩空白处关闭浮窗」登记：用户点的是正文，说明要回到阅读本身，
     * 于是浮窗收回动画结束后连上下导航栏一起收起，一帧回到纯净阅读态。
     * 底栏按钮的转发点击不调这里（那一类是换浮窗，导航栏必须留着）。
     */
    void requestHideNavWithSheet() {
        hideNavWithSheet = true;
    }

    /**
     * 浮窗（目录抽屉 / 设置面板）收起后的统一收尾：按需收起导航栏，否则维持原有的
     * 5 秒自动收起计时。仍要补发底栏点击时不收 —— 收了就等于把紧接着要打开的浮窗的入口抽走。
     */
    void finishSheetDismiss() {
        boolean hide = hideNavWithSheet;
        hideNavWithSheet = false;
        if (hide && pendingNavActionAfterDismiss == null) {
            hideNavigation();
        } else {
            resetAutoHideTimer();
        }
    }

    /**
     * 命中底栏按钮。ACTION_OUTSIDE 的 getX/getY 是相对弹窗窗口左上角的（框架自身的
     * 「是否在窗内」判定也用它），窗口左上角即该 decor 视图的屏幕位置，故直接相加还原。
     */
    private View navButtonAtWindowPoint(View popupRoot, MotionEvent ev) {
        int[] windowLoc = new int[2];
        popupRoot.getLocationOnScreen(windowLoc);
        int x = windowLoc[0] + (int) ev.getX();
        int y = windowLoc[1] + (int) ev.getY();
        int[] loc = new int[2];
        for (View button : new View[] { btnCatalog, btnNightMode, btnSettings }) {
            if (button == null) continue;
            button.getLocationOnScreen(loc);
            if (x >= loc[0] && x < loc[0] + button.getWidth()
                    && y >= loc[1] && y < loc[1] + button.getHeight()) return button;
        }
        return null;
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
            SlideOutPopupWindow.dismissImmediate(settingsPopupWindow);
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
                    SlideOutPopupWindow.dismissImmediate(settingsPopupWindow);
                })
                .start();
    }

    /** 二级弹窗（间距抽屉/更多阅读设置/选择字体/选择背景）滑出时联动：设置面板收回。
     *  窗口保持显示（scrim 不动，双层遮罩暗度与旧行为一致），仅面板平移出窗口下沿，
     *  与二级弹窗的入场滑出同步交叉（同 220ms Accelerate/Decelerate 对偶）。 */
    void retractSettingsPanelForSub() {
        if (settingsPopupWindow == null || !settingsPopupWindow.isShowing()) return;
        final View panel = settingsPanelView;
        if (panel == null) return;
        int h = panel.getHeight();
        if (h <= 0) return;
        panel.animate().cancel();
        panel.animate()
                .translationY(h)
                .setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .start();
    }

    /** 二级弹窗收起时联动：设置面板重新滑出（复用入场动画；整窗正在收回流程中则不抢） */
    void restoreSettingsPanelFromSub() {
        if (settingsPopupWindow == null || !settingsPopupWindow.isShowing()) return;
        if (settingsPanelDismissing) return;
        final View panel = settingsPanelView;
        if (panel == null) return;
        startSettingsPanelEnter(panel);
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
        
        // 修正：本地书籍使用本地存储，网络书籍使用服务器。
        // 外站书必须跟本地书同一条路：saveBookmark 里按 sourceType+sourceUrl 写 SP 再交给
        // ExternalSyncManager 同步。外站书 currentBook.getId() 是 null，走服务器那条
        // （addNetworkBookmark）会被后端以空 bookId 拒掉，表现为「添加失败」。
        if (isLocalBook || isExternalBook) {
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
        
        // 修正：从 WebView 获取当前页的内容作为预览
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
        
        // 修正：从 WebView 获取当前页的内容作为预览
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
            public void onResponse(@NonNull Call<ApiResponse<Bookmark>> call,
                    @NonNull Response<ApiResponse<Bookmark>> response) {
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
                    android.util.Log.e("ReadActivity", "saveNetworkBookmark failed: code="
                            + response.code() + ", msg=" + errMsg);
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
                if (idx == 3) {
                    // 黑色背景 ≡ 夜间模式（同为 #1A1A1A，统一为一个状态）：
                    // 点黑块即进入夜间（带渐变动画）；已处于夜间则仅刷新高亮。
                    // currentBgColor 不写 3，日间背景保持原选择，供退出夜间时恢复。
                    if (!isNightMode) animateNightModeTo(true);
                } else {
                    currentBgColor = idx;
                    // 关键修复：选中自定义背景即退出夜间模式。夜间模式与自定义背景互斥
                    // （夜间关时由 applyBackgroundColorToWebView(currentBgColor) 恢复背景），
                    // 若不置 false，夜间下选背景时 night_mode 仍存 true，重进阅读器会被 setNightMode(true)
                    // 覆盖回夜间、显示不出用户切换的背景。
                    isNightMode = false;
                    applyBackgroundColorToWebView(idx);
                    applyChromeTheme();   // 导航栏 / 浮窗底色跟随新背景
                    saveReadingPreferences();
                }
                updateBgColorHighlight(bgSwatches, bgRings);
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

        // 翻页动画模式选择（与间距抽屉同款分段导航：轨道 + 白色滑动指示器，260ms 减速滑动）
        TextView tvPageCover = view.findViewById(R.id.tv_page_cover);
        TextView tvPageSimulation = view.findViewById(R.id.tv_page_simulation);
        TextView tvPageTranslate = view.findViewById(R.id.tv_page_translate);
        TextView tvPageUpDown = view.findViewById(R.id.tv_page_updown);
        TextView tvPageFade = view.findViewById(R.id.tv_page_fade);

        TextView[] pageButtons = {tvPageCover, tvPageSimulation, tvPageTranslate, tvPageUpDown, tvPageFade};
        String[] pageModes = {"cover", "simulation", "slide", "updown", "fade"};

        View segTrackPage = view.findViewById(R.id.seg_track_page);
        final View segThumbPage = view.findViewById(R.id.seg_thumb_page);
        // 指示器显示真实颜色（日间白 / 夜间按 App 规则混白 88%），不参与跟随背景重着色
        segThumbPage.setTag(R.id.tag_keep_own_color, true);
        applySegThumbTheme(segThumbPage);

        final int[] pageSelIdx = {0};
        for (int i = 0; i < pageModes.length; i++) {
            if (pageModes[i].equals(pageTurnMode)) { pageSelIdx[0] = i; break; }
        }
        final int[] pageCell = {0};
        final Runnable[] placePageThumb = new Runnable[1];
        placePageThumb[0] = () -> {
            int w = segTrackPage.getWidth();
            if (w <= 0) return;   // 未布局：等 post 回调再定位
            int cell = Math.max(1, (int) ((w - dp(6)) / 5));
            pageCell[0] = cell;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) segThumbPage.getLayoutParams();
            int th = (int) (segTrackPage.getHeight() - dp(6));
            if (lp.width != cell || lp.height != th) {
                lp.width = cell;
                lp.height = th;
                segThumbPage.setLayoutParams(lp);
            }
            float target = dp(3) + pageSelIdx[0] * cell;
            if (segThumbPage.getWidth() > 0 && Math.abs(segThumbPage.getTranslationX() - target) > 0.5f) {
                segThumbPage.animate().translationX(target).setDuration(260)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else {
                segThumbPage.animate().cancel();
                segThumbPage.setTranslationX(target);
            }
        };
        Runnable updatePageSegUi = () -> {
            int pri = getTextPrimaryColor();
            for (int i = 0; i < pageButtons.length; i++) {
                boolean on = i == pageSelIdx[0];
                pageButtons[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                // 选中项落在混白 88% 的亮色指示器上：文字恒深色（夜间 textPrimary=白 不可读），
                // 并打 keep_own_color 防止夜间/背景切换时 themeViewTree 把恒深色重刷成主题白
                pageButtons[i].setTextColor(on ? 0xFF1D1D1F : pri);
                pageButtons[i].setTag(R.id.tag_keep_own_color, on ? Boolean.TRUE : null);
            }
            placePageThumb[0].run();
        };
        segTrackPage.post(placePageThumb[0]);
        updatePageSegUi.run();
        // 夜间/背景切换时 themeShowingPopups 会重刷已显示的设置面板：
        // 重设指示器底色与文字颜色，保证翻页分段在主题切换后依然正确
        settingsPageSegRefresher = () -> {
            applySegThumbTheme(segThumbPage);
            updatePageSegUi.run();
        };

        for (int i = 0; i < pageButtons.length; i++) {
            final int idx = i;
            final String mode = pageModes[i];
            pageButtons[i].setOnClickListener(v -> {
                pageTurnMode = mode;
                pageSelIdx[0] = idx;
                updatePageSegUi.run();
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

        // 间距行：行段间距 / 页面边距 两个胶囊入口 → 底部抽屉
        updateSpacingPillTexts(view);
        view.findViewById(R.id.btn_line_spacing).setOnClickListener(v -> showSpacingDrawer(true));
        view.findViewById(R.id.btn_page_margin).setOnClickListener(v -> showSpacingDrawer(false));
    }

    // ==================== 间距设置（行段间距 / 页面边距） ====================

    /** 行段间距档位：{行距倍数, 段距行数}，默认「适中」=(1.8, 0.5) */
    private static final float[][] LINE_PRESETS = {{1.5f, 0.25f}, {1.8f, 0.5f}, {2.0f, 0.75f}, {2.2f, 1.0f}};
    private static final String[] PRESET_NAMES = {"小", "适中", "较大", "大"};
    /** 页面边距档位：{左右 dp, 上下 dp}，默认「适中」=(16, 22) */
    private static final int[][] MARGIN_PRESETS = {{10, 14}, {16, 22}, {24, 32}, {32, 42}};
    /** 滑块值域：{偏移, 档数}（LiquidSlider 值为 1..total，实际值 = 偏移 + (值-1) × 步长） */
    private static final int SLIDER_LINE_OFF = 12,  SLIDER_LINE_TOTAL = 15;   // 1.2~2.6× step 0.1
    private static final int SLIDER_PARA_OFF = 0,   SLIDER_PARA_TOTAL = 31;   // 0~1.5 行 step 0.05
    private static final int SLIDER_LR_OFF = 8,     SLIDER_LR_TOTAL = 33;     // 8~40 dp step 1
    private static final int SLIDER_TB_OFF = 12,    SLIDER_TB_TOTAL = 45;     // 12~56 dp step 1

    /** 当前值命中的档位索引；未命中任何档位返回 4（=自定义） */
    private int linePresetIndex() {
        for (int i = 0; i < LINE_PRESETS.length; i++) {
            if (Math.abs(LINE_PRESETS[i][0] - lineSpacingRatio) < 0.001f
                    && Math.abs(LINE_PRESETS[i][1] - paraGapRatio) < 0.001f) return i;
        }
        return 4;
    }

    private int marginPresetIndex() {
        for (int i = 0; i < MARGIN_PRESETS.length; i++) {
            if (MARGIN_PRESETS[i][0] == padLR && MARGIN_PRESETS[i][1] == padTB) return i;
        }
        return 4;
    }

    /** 更新设置面板「间距」行两个胶囊上的当前档位名 */
    private void updateSpacingPillTexts(View panel) {
        if (panel == null) return;
        TextView tvLine = panel.findViewById(R.id.tv_line_spacing_cur);
        TextView tvMargin = panel.findViewById(R.id.tv_margin_cur);
        if (tvLine != null) tvLine.setText(linePresetIndex() < 4 ? PRESET_NAMES[linePresetIndex()] : "自定义");
        if (tvMargin != null) tvMargin.setText(marginPresetIndex() < 4 ? PRESET_NAMES[marginPresetIndex()] : "自定义");
    }

    /** 把当前间距参数应用到 WebView（运行中直调 → setTypesetting 立即重排当前章） */
    private void applySpacingToWebView() {
        if (isWebViewReady) {
            webView.evaluateJavascript("setTypesetting(" + lineSpacingRatio + "," + paraGapRatio
                    + "," + padLR + "," + padTB + ")", null);
        }
        updateSpacingPillTexts(settingsPanelView);
        saveReadingPreferences();
    }

    private PopupWindow spacingPopup;

    /**
     * 底部抽屉：行段间距（isLine=true）/ 页面边距（false）。
     * 复用同一布局，标题/描述/滑块标签按类型参数化；分段 5 档（小/适中/较大/大/自定义），
     * 自定义显现两个 LiquidSlider（拖动中仅更新数值文字，松手才重排，与导航栏滑块同策略）。
     * 配色：容器/文字走 themeViewTree 派生（黑白文字），指示器夜间按 App 规则混白 88%。
     */
    private void showSpacingDrawer(final boolean isLine) {
        if (spacingPopup != null && spacingPopup.isShowing()) return;
        ViewGroup root = (ViewGroup) LayoutInflater.from(this).inflate(R.layout.popup_spacing_drawer, null);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        TextView title = root.findViewById(R.id.tv_drawer_title);
        TextView desc = root.findViewById(R.id.tv_drawer_desc);
        TextView labelA = root.findViewById(R.id.tv_label_a);
        TextView labelB = root.findViewById(R.id.tv_label_b);
        TextView valA = root.findViewById(R.id.tv_val_a);
        TextView valB = root.findViewById(R.id.tv_val_b);
        LiquidSlider sliderA = root.findViewById(R.id.slider_a);
        LiquidSlider sliderB = root.findViewById(R.id.slider_b);
        View track = root.findViewById(R.id.seg_track);
        final View thumb = root.findViewById(R.id.seg_thumb);
        final View customZone = root.findViewById(R.id.custom_zone);
        final TextView[] segItems = {
                root.findViewById(R.id.seg_i_0), root.findViewById(R.id.seg_i_1),
                root.findViewById(R.id.seg_i_2), root.findViewById(R.id.seg_i_3),
                root.findViewById(R.id.seg_i_4)};

        title.setText(isLine ? "行段间距" : "页面边距");
        desc.setText(isLine ? "调整正文行距与段落间距，实时生效"
                            : "选择边距档位，正文留白即时预览；选「自定义」可分别微调");
        labelA.setText(isLine ? "行距" : "左右边距");
        labelB.setText(isLine ? "段距" : "上下边距");
        for (int i = 0; i < segItems.length; i++) {
            segItems[i].setText(i < 4 ? PRESET_NAMES[i] : "自定义");
        }

        // 主题派生：容器/文字由 themeViewTree 统一重着色（文字只黑/白）；指示器单独处理
        themeViewTree(root);
        thumb.setTag(R.id.tag_keep_own_color, true);
        applySegThumbTheme(thumb);
        sliderA.setChrome(getChromeBgColor(), getTextPrimaryColor(), isChromeDark());
        sliderB.setChrome(getChromeBgColor(), getTextPrimaryColor(), isChromeDark());

        // ===== 分段选择：滑动指示器 + 文字加粗（颜色统一主文字，层级靠字重） =====
        final int[] selIdx = {isLine ? linePresetIndex() : marginPresetIndex()};
        final int[] segCell = {0};
        final Runnable[] placeThumb = new Runnable[1];
        placeThumb[0] = () -> {
            int w = track.getWidth();
            if (w <= 0) return;   // 未布局：等 post 回调再定位
            int cell = Math.max(1, (int) ((w - dp(6)) / 5));
            segCell[0] = cell;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
            int th = (int) (track.getHeight() - dp(6));
            if (lp.width != cell || lp.height != th) {
                lp.width = cell;
                lp.height = th;
                thumb.setLayoutParams(lp);
            }
            float target = dp(3) + selIdx[0] * cell;
            if (thumb.getWidth() > 0 && Math.abs(thumb.getTranslationX() - target) > 0.5f) {
                thumb.animate().translationX(target).setDuration(260)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else {
                thumb.animate().cancel();
                thumb.setTranslationX(target);
            }
        };
        Runnable updateSegUi = () -> {
            int pri = getTextPrimaryColor();
            for (int i = 0; i < segItems.length; i++) {
                boolean on = i == selIdx[0];
                segItems[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                // 选中项落在混白 88% 的亮色指示器上，必须恒深色；夜间 textPrimary(白) 在白块上不可读
                segItems[i].setTextColor(on ? 0xFF1D1D1F : pri);
            }
            customZone.setVisibility(selIdx[0] == 4 ? View.VISIBLE : View.GONE);
            placeThumb[0].run();
        };
        track.post(placeThumb[0]);

        // ===== 滑块：拖动中仅更新数值文字，松手应用（避免逐帧重排卡顿） =====
        final int offA = isLine ? SLIDER_LINE_OFF : SLIDER_LR_OFF;
        final int offB = isLine ? SLIDER_PARA_OFF : SLIDER_TB_OFF;
        final int totalA = isLine ? SLIDER_LINE_TOTAL : SLIDER_LR_TOTAL;
        final int totalB = isLine ? SLIDER_PARA_TOTAL : SLIDER_TB_TOTAL;
        if (isLine) {
            sliderA.setTipFormatter(v -> fmtLineRatio((v - 1 + offA)));
            sliderB.setTipFormatter(v -> fmtParaGap((v - 1 + offB)));
        } else {
            sliderA.setTipFormatter(v -> String.valueOf(v - 1 + offA));
            sliderB.setTipFormatter(v -> String.valueOf(v - 1 + offB));
        }
        // 滑块初值必须按抽屉类型取各自的量：边距抽屉用 padLR/padTB，
        // 早先无脑用行段距两个值换算，边距抽屉每次重开滑块都跳回「看起来是默认」的位置。
        sliderA.setProgressInfo((isLine ? Math.round(lineSpacingRatio * 10) : padLR) - offA + 1, totalA);
        sliderB.setProgressInfo((isLine ? Math.round(paraGapRatio * 20) : (padTB < 0 ? 22 : padTB)) - offB + 1, totalB);

        Runnable syncVals = () -> {
            if (isLine) {
                valA.setText(fmtLineRatio(Math.round(lineSpacingRatio * 10)));
                valB.setText(fmtParaGap(Math.round(paraGapRatio * 20)));
            } else {
                valA.setText(String.valueOf(padLR));
                valB.setText(String.valueOf(padTB < 0 ? 22 : padTB));
            }
        };
        syncVals.run();

        sliderA.setListener(new LiquidSlider.Listener() {
            @Override public void onDragStart() { }
            @Override public void onDragPage(int page) {
                int v = page - 1 + offA;
                if (isLine) valA.setText(fmtLineRatio(v));
                else valA.setText(String.valueOf(v));
            }
            @Override public void onDragEnd(int page) {
                int v = page - 1 + offA;
                if (isLine) lineSpacingRatio = v / 10f;
                else padLR = v;
                selIdx[0] = 4;
                updateSegUi.run();
                syncVals.run();
                applySpacingToWebView();
            }
        });
        sliderB.setListener(new LiquidSlider.Listener() {
            @Override public void onDragStart() { }
            @Override public void onDragPage(int page) {
                int v = page - 1 + offB;
                if (isLine) valB.setText(fmtParaGap(v));
                else valB.setText(String.valueOf(v));
            }
            @Override public void onDragEnd(int page) {
                int v = page - 1 + offB;
                if (isLine) paraGapRatio = v / 20f;
                else padTB = v;
                selIdx[0] = 4;
                updateSegUi.run();
                syncVals.run();
                applySpacingToWebView();
            }
        });

        // 点击档位：预设档直接赋值生效；「自定义」展开滑块区
        for (int i = 0; i < segItems.length; i++) {
            final int idx = i;
            segItems[i].setOnClickListener(v -> {
                selIdx[0] = idx;
                updateSegUi.run();
                if (idx == 4) {
                    syncVals.run();
                    return;
                }
                if (isLine) {
                    lineSpacingRatio = LINE_PRESETS[idx][0];
                    paraGapRatio = LINE_PRESETS[idx][1];
                } else {
                    padLR = MARGIN_PRESETS[idx][0];
                    padTB = MARGIN_PRESETS[idx][1];
                }
                // 滑块同步到预设值，下次展开自定义即为当前值
                sliderA.setProgressInfo((isLine ? Math.round(lineSpacingRatio * 10) : padLR) - offA + 1, totalA);
                sliderB.setProgressInfo((isLine ? Math.round(paraGapRatio * 20) : (padTB < 0 ? 22 : padTB)) - offB + 1, totalB);
                syncVals.run();
                applySpacingToWebView();
            });
        }
        updateSegUi.run();

        // ===== 容器：SwipeDismissLayout 承载（跟手下拉关闭，与字体/背景弹窗同款）+ 全屏 scrim + 底部面板 =====
        SwipeDismissLayout host = new SwipeDismissLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(0x6B, 0, 0, 0));
        scrim.setOnClickListener(v -> dismissSpacingDrawer());
        host.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        host.setPanel(root);
        // 面板滑出（跟手超阈值 / 收起按钮）后：scrim 淡出再关窗；scrim 点击关闭走 dismissSpacingDrawer 自己的动画
        host.setDismissAction(() -> {
            scrim.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                if (spacingPopup != null) { SlideOutPopupWindow.dismissImmediate(spacingPopup); spacingPopup = null; }
            }).start();
        });

        // 收起按钮（左上）：走 SwipeDismissLayout 收回动画，与字体/背景弹窗同款
        root.findViewById(R.id.btn_drawer_close).setOnClickListener(v -> {
            SwipeDismissLayout h = SwipeDismissLayout.findHost(v);
            if (h != null) h.dismissAnimated();
            else dismissSpacingDrawer();
        });

        SlideOutPopupWindow spacing = new SlideOutPopupWindow(host,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        spacingPopup = spacing;
        spacing.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        spacing.setAnimationStyle(0);
        spacing.setOutsideTouchable(true);
        spacing.setTouchInterceptor((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_OUTSIDE) {
                dismissSpacingDrawer();
                return true;
            }
            return false;
        });
        // 返回键：PopupWindow 的 DecorView 会自己消费 BACK 并 dismiss()，这里接管 dismiss()，
        // 让它走与点 scrim 同一条滑回路径
        spacing.setSlideOut(this::dismissSpacingDrawer);
        spacingPopup.showAtLocation(layoutBottomNav, Gravity.NO_GRAVITY, 0, 0);
        // 联动：抽屉滑出时设置面板收回；抽屉收起（scrim/返回键/跟手下滑/按钮任一路径 dismiss）时面板重新滑出
        retractSettingsPanelForSub();
        spacingPopup.setOnDismissListener(this::restoreSettingsPanelFromSub);
        // 布局完成后重新定位指示器（首次定位时 track 宽度才就绪）
        root.post(placeThumb[0]);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(200).start();
        root.setTranslationY(dp(320));
        root.post(() -> root.animate().translationY(0f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f)).start());
    }

    private void dismissSpacingDrawer() {
        if (spacingPopup == null || !spacingPopup.isShowing()) return;
        PopupWindow pw = spacingPopup;
        spacingPopup = null;
        View host = pw.getContentView();
        View panel = ((ViewGroup) host).getChildAt(1);
        View scrim = ((ViewGroup) host).getChildAt(0);
        panel.animate().translationY(panel.getHeight() > 0 ? panel.getHeight() : dp(320))
                .setDuration(220).setInterpolator(new android.view.animation.AccelerateInterpolator())
                .start();
        scrim.animate().alpha(0f).setDuration(200).start();
        panel.postDelayed(() -> {
            try { SlideOutPopupWindow.dismissImmediate(pw); } catch (Exception ignore) { }
        }, 230);
    }

    /** 分段滑动指示器日/夜配色：日间白块+投影（书架同款 drawable），夜间按 App 规则混白 88% */
    private void applySegThumbTheme(View thumb) {
        if (isChromeDark()) {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(9));
            gd.setColor(mixColors(getChromeBgColor(), Color.WHITE, 0.88f));
            thumb.setBackground(gd);
        } else {
            thumb.setBackgroundResource(R.drawable.bg_shelf_seg_thumb);
        }
    }

    private String fmtLineRatio(int v10) {
        return String.format(java.util.Locale.US, "%.1f×", v10 / 10f);
    }

    private String fmtParaGap(int v20) {
        return String.format(java.util.Locale.US, "%.2f 行", v20 / 20f);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /**
     * 更新背景颜色选中高亮
     */
    private void updateBgColorHighlight(View[] swatches, View[] rings) {
        for (int i = 0; i < swatches.length; i++) {
            // 黑色背景(index 3) ≡ 夜间模式：夜间态下黑块显示选中
            boolean on = (i == currentBgColor) || (i == 3 && isNightMode);
            if (on) {
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

    // ========== 弹窗控制器（背景/字体/更多设置；2026-10-02 拆出，实现见同名 Controller 类） ==========
    private final ReadBackgroundPopupController bgPopupController = new ReadBackgroundPopupController(this);
    private final ReadFontPopupController fontPopupController = new ReadFontPopupController(this);
    private final ReadMoreSettingsPopupController moreSettingsPopupController = new ReadMoreSettingsPopupController(this);

    /** 显示更多背景弹窗（纯色 + 纹理） */
    private void showBgColorsDialog() { bgPopupController.show(); }

    /** 显示字体选择弹窗 */
    private void showFontsDialog() { fontPopupController.show(); }

    /** 显示更多设置弹窗（页眉页脚等） */
    private void showMoreSettingsDialog() { moreSettingsPopupController.show(); }

    /** 启动后后台预拉取字体列表（实现见 ReadFontPopupController） */
    private void preloadBackendFontsIfNeeded() { fontPopupController.preloadIfNeeded(); }

    /** 更新「切换字体」按钮文案与字体预览（实现见 ReadFontPopupController） */
    private void updateSwitchFontButton(TextView tv) { fontPopupController.updateSwitchFontButton(tv); }

    private void applyFontSizeChange(TextView display) {
        display.setText(String.valueOf((int) currentFontSize));
        webView.evaluateJavascript("setFontSize(" + currentFontSize + ")", null);
        saveReadingPreferences();
    }

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

    // ==================== 自动翻页（实现见 ReadAutoPageController） ====================
    void startAutoPage() { autoPageController.startAutoPage(); }

    void stopAutoPage() { autoPageController.stopAutoPage(); }

    /** 用跟手下滑容器包住底部面板（实现见 SwipeDismissLayout.showPopup） */
    PopupWindow showSwipeDismissPopup(View popupView, int panelHeight, String logTag) {
        return SwipeDismissLayout.showPopup(this, popupView, panelHeight, logTag);
    }

    void suspendAutoPage() { autoPageController.suspendAutoPage(); }

    void resumeAutoPageIfSuspended() { autoPageController.resumeAutoPageIfSuspended(); }

    void syncAutoPageSwitchUI() { autoPageController.syncAutoPageSwitchUI(); }

    void updateAutoSpeedUI(TextView[] views, int idx) { autoPageController.updateAutoSpeedUI(views, idx); }

    // ==================== 缓存与本地书 ====================
    private void cacheChapterListOnly(long bookId, List<ChapterDto> list) {
        SharedPreferences sp = getSharedPreferences("chapter_list_" + bookId, MODE_PRIVATE);
        sp.edit().putInt("count", list.size()).apply();
        // 修正：统一使用循环索引 i 缓存标题和ID，不再使用 sortOrder
        for (int i = 0; i < list.size(); i++) {
            ChapterDto dto = list.get(i);
            cacheChapterTitle(bookId, i, dto.getTitle());
            // 新增：缓存章节ID
            cacheChapterId(bookId, i, dto.getId());
            // 新增：缓存分卷键，供断网时仍能还原分卷结构（旧缓存无此键则退回平铺）
            cacheChapterSortKey(bookId, i, dto.getSortKey());
        }
    }
    
    // 新增：章节ID缓存方法
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
    void cacheChapterContent(long bookId, int index, String content) {
        getSharedPreferences("chapter_content_" + bookId, MODE_PRIVATE).edit().putString("content_"
                + index, content).apply();
    }
    private String getChapterContentCache(long bookId, int index) {
        return getSharedPreferences("chapter_content_" + bookId, MODE_PRIVATE).getString("content_" + index, null);
    }
    private String getChapterTitleCache(long bookId, int index) {
        return getSharedPreferences("chapter_meta_" + bookId, MODE_PRIVATE).getString("title_" + index, null);
    }
    // ========== 服务器书章节预取与列表合并：实现见 ReadChapterPrefetchController（2026-10-02 拆出） ==========

    private final ReadChapterPrefetchController chapterPrefetch = new ReadChapterPrefetchController(this);

    private void prefetchServerChaptersAround(int center) {
        chapterPrefetch.prefetchServerChaptersAround(center);
    }

    private void mergeServerData(List<ChapterDto> serverList) {
        chapterPrefetch.mergeServerData(serverList);
    }
    private void loadLocalBookChapters(long bookId, int targetChapter) {
        boolean found = readLocalCatalogFromSp(bookId);

        // 关键修复：用 targetChapter 更新 currentChapterIndex，保证 onPageFinished 渲染到正确章节
        if (!chapterList.isEmpty() && targetChapter >= 0 && targetChapter < chapterList.size()) {
            currentChapterIndex = targetChapter;
        }

        // 懒解析的书此刻可能还在后台建索引（导入页只等了卡片那几项），也可能源文件被删了。
        //    前者显示加载动画并等索引露面，后者才提示不可读——绝不能以空章节打开，
        //    用户只会看到一片空白、误以为书坏了。
        //    放在章节列表构建之后：lazyStore() 要用 chapterList.size() 校验索引条数，提前调会误判。
        if (found) awaitLocalCatalog(bookId, targetChapter);
    }

    /**
     * 从 {@code local_books} 读出这本书的目录：卡片信息、每章标题、分卷表。
     * 后台构建发布完会再读一次，所以这里必须每次都从零重建列表。
     *
     * @return 书架里是否有这本书
     */
    private boolean readLocalCatalogFromSp(long bookId) {
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);

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
                    // 只建章节列表，不在开书时全量读正文/HTML：
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
                (found ? "" : " [WARNING: book not found in local_books]"));
        return found;
    }

    /** 回源索引（EPUB 的 index.bin / TXT 的 txtindex.bin）是否已经发布到位 */
    private boolean localIndexPublished(long bookId) {
        return LocalBookParser.hasChapterIndex(this, bookId)
                || LocalBookParser.hasTxtIndex(this, bookId);
    }

    /**
     * 本地书「内容是否已就绪」：回源索引已发布 <b>且</b>  SharedPreferences 里有章数。
     *
     * <p>判据取自发布态而不是内存里的 chapterList：等待期间内存里还是（可能为空着的）粗版目录，
     * 而后台是先写目录、后让索引露面，所以「索引在 + SP 章数在」才等价于「目录与索引是同一版本」。
     * 反过来只看到目录没看到索引（进程在两件事之间被杀）则由自愈重建补上。
     */
    private boolean localCatalogReady(long bookId) {
        return localIndexPublished(bookId) && localSpChapterCount(bookId) > 0;
    }

    /** 书架记录里这本书当前发布的章数（等待期间它会被后台从粗版改成精修版） */
    private int localSpChapterCount(long bookId) {
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0L) == bookId) return sp.getInt("chapter_count_" + i, 0);
        }
        return 0;
    }

    /** 等待/发起本地书索引；就绪（或判定彻底读不出）前不渲染任何正文 */
    private void awaitLocalCatalog(long bookId, int targetChapter) {
        if (!foundBookHasSourcePointer(bookId)) {
            showLocalBookUnreadableAndFinish(bookId);
            return;
        }
        if (localCatalogReady(bookId)) return;
        // 没索引：要么后台还在跑，要么这本书从没建成过（进程被杀 / 老数据）——后者在这里补发一次
        LocalBookImport.enqueueByBookId(this, bookId);
        if (!LocalBookImport.isBuilding(bookId)) {
            // 入队没接上、后台也没在跑（源文件此刻打不开，重建注定失败）：直接按不可读处理
            showLocalBookUnreadableAndFinish(bookId);
            return;
        }
        android.util.Log.d("ReadActivity", "等待后台建目录 bookId=" + bookId);
        // 等待期间章数还是 0（TXT 前台不读文件），所以目标章先记着，就绪那一刻再套上去
        pendingLocalTargetChapter = targetChapter;
        setChapterLoading(true);
        localIndexDeadline = System.currentTimeMillis() + LOCAL_INDEX_WAIT_MS;
        mainHandler.removeCallbacks(localIndexPoller);
        mainHandler.postDelayed(localIndexPoller, LOCAL_INDEX_POLL_MS);
    }

    /** 轮询「索引是否已发布」：就绪就按精修目录重建章节列表并补发恢复位置，超时才提示不可读 */
    private final Runnable localIndexPoller = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            long bookId = safeBookId();
            if (localCatalogReady(bookId)) {
                mainHandler.removeCallbacks(this);
                onLocalCatalogReady(bookId);
                return;
            }
            // 队列里没有这本书了 = 后台已跑完却没产出索引（或压根没建成），再等也不会有
            if (!LocalBookImport.isBuilding(bookId)) {
                mainHandler.removeCallbacks(this);
                showLocalBookUnreadableAndFinish(bookId);
                return;
            }
            if (System.currentTimeMillis() > localIndexDeadline) {
                mainHandler.removeCallbacks(this);
                showLocalBookUnreadableAndFinish(bookId);
                return;
            }
            mainHandler.postDelayed(this, LOCAL_INDEX_POLL_MS);
        }
    };

    private static final long LOCAL_INDEX_POLL_MS = 400L;
    /** 上限按最坏情况给：千章 TXT 整本扫描 + 逐章判定，真机冷启动在秒级，留足余量不误判成不可读 */
    private static final long LOCAL_INDEX_WAIT_MS = 120000L;
    private long localIndexDeadline;
    /** 等待期间记下的目标章（那时章数可能还是 0，阅读记录没法当场套上） */
    private int pendingLocalTargetChapter = -1;

    /**
     * 索引到位：重新读一遍目录（章数与标题此刻都换成了精修版），再补发恢复位置。
     *
     * <p>{@code lazyStoreBookId} 是「只为这本书试过打开仓储」的缓存位，等待期间它记着 null 结果，
     * 必须清掉，否则索引已发布却永远不去开仓储。
     */
    private void onLocalCatalogReady(long bookId) {
        android.util.Log.d("ReadActivity", "后台目录已就绪，重载 bookId=" + bookId);
        lazyStoreBookId = -1L;
        lazyStore = null;
        int targetChapter = pendingLocalTargetChapter >= 0 ? pendingLocalTargetChapter : currentChapterIndex;
        pendingLocalTargetChapter = -1;
        // 不能再走 awaitLocalCatalog：这次索引已在，直接读目录并恢复
        boolean found = readLocalCatalogFromSp(bookId);
        setChapterLoading(false);
        if (!found || chapterList.isEmpty()) {
            showLocalBookUnreadableAndFinish(bookId);
            return;
        }
        if (targetChapter >= chapterList.size()) targetChapter = chapterList.size() - 1;
        currentChapterIndex = Math.max(0, targetChapter);
        updateChapterButtons();
        if (isWebViewReady) restoreProgressOnceCatalogReady();
    }

    /** 书架记录里这本书是否留有源文件指针；没有就无从重建，直接判不可读 */
    private boolean foundBookHasSourcePointer(long bookId) {
        return !localBookSourceUri(bookId).isEmpty();
    }

    /** 源文件/内容不可用时提示并退出阅读器，避免以空白页打开 */
    private void showLocalBookUnreadableAndFinish(long bookId) {
        boolean lazyIndexed = LocalBookParser.hasTxtIndex(this, bookId)
                || LocalBookParser.hasChapterIndex(this, bookId);
        String msg = lazyIndexed
                ? "本书源文件已删除或无法访问，无法打开"
                : "本书内容缺失，无法打开";
        android.util.Log.w("ReadActivity", msg + ": bookId=" + bookId);
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
        finish();
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
                @Override public void onResponse(@NonNull Call<ApiResponse<Void>> call,
                        @NonNull Response<ApiResponse<Void>> response) {}
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
     * 栈内复用入口：书籍详情页启动阅读器时带 FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP，
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
        // 离开前台时清空活跃实例：防止 paused 的 ReadActivity 仍被 themeViewTree
        // 误判为「前台活跃」，导致迟到的回调（比如某些 popup post、RecyclerView bind 等）把
        // 当前 Activity（比如 BookDetailActivity / 书城 / 书架）的 view 染色。
        clearActiveInstance(this);
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
        // 离开前台时撤销黑屏态与到期计时：用户按电源键灭屏再回来时，
        // 背光必须已恢复正常（黑屏态只属于「定时到期」这一种前台场景）
        mainHandler.removeCallbacks(screenOffRunnable);
        setReaderBacklight(true);
    }
    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        // 标记本实例为前台活跃，供 themeViewTree 宿主判定使用
        noteActiveInstance(this);
        readStartTime = System.currentTimeMillis();
        mainHandler.postDelayed(hideNavRunnable, 3000);
        // 回到前台重新应用亮屏策略（定时模式重新计时）
        applyScreenKeepAlive();
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
        // 清理亮屏计时回调（窗口销毁后触发无意义）
        mainHandler.removeCallbacks(screenOffRunnable);
        // 等后台建目录的轮询同理：本页都没了，继续轮询只会拖住 Activity
        mainHandler.removeCallbacks(localIndexPoller);
        pendingLocalTargetChapter = -1;
        // 懒解析仓储持有源 epub 的 zip 句柄，必须随本 Activity 释放
        if (lazyStore != null) {
            try { lazyStore.close(); } catch (Throwable ignored) {}
            lazyStore = null;
        }
        lazyStoreBookId = -1L;
        // 兜底：移除仍挂在复用 WebView 上的「布局就绪」监听（防止 observer 随 detach 失效后崩溃）
        if (layoutReadyListener != null) {
            try {
                android.view.ViewTreeObserver obs = webView.getViewTreeObserver();
                if (obs.isAlive()) obs.removeOnGlobalLayoutListener(layoutReadyListener);
            } catch (Throwable ignored) {}
            layoutReadyListener = null;
        }
        // 复用池：把 WebView 从本 Activity 视图树摘离并归还给池子（绝不 destroy），
        //    下次进书直接复用，跳过「重建 WebView + 重载 reader.html」的冷启动。
        if (webView != null && webView.getParent() != null) {
            try { ((ViewGroup) webView.getParent()).removeView(webView); } catch (Throwable ignored) {}
        }
        // 恢复原始未捕获异常处理器，避免污染其它 Activity
        try {
            if (savedUncaughtHandler != null) Thread.currentThread().setUncaughtExceptionHandler(savedUncaughtHandler);
        } catch (Throwable ignored) {}
        // 复用池：切断 JsBridge 对当前 Activity 的引用，避免已销毁 Activity 继续接收前端回调
        try { JsBridge.detach(this); } catch (Throwable ignored) {}
        super.onDestroy();
        stopAutoPage();
        mainHandler.removeCallbacks(hideNavRunnable);
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
        dismissPopups();
        // 排版缓存：退出时优雅关闭写入池（已提交任务会跑完）
        if (layoutCache != null) {
            layoutCache.shutdown();
        }
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (chapterPopupWindow != null && chapterPopupWindow.isShowing()) {
            // 走抽屉自己的滑回动画，与点遮罩/再点目录按钮同一收起路径（直接 dismiss 会瞬间消失）
            tocController.dismissChapterPopup();
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

    static class PopupPagerAdapter extends FragmentStateAdapter {
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