package com.example.myapplication.activity;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.example.myapplication.R;
import com.example.myapplication.bean.ApkPush;
import com.example.myapplication.fragment.BookStoreFragment;
import com.example.myapplication.fragment.CategoryFragment;
import com.example.myapplication.fragment.MineFragment;
import com.example.myapplication.fragment.BookShelfFragment;
import com.example.myapplication.manager.UpdateManager;
import com.example.myapplication.utils.ExternalPrefs;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.ShelfPrefetch;
import com.example.myapplication.utils.SystemBarInsets;
import com.example.myapplication.widget.YieldBottomNav;

import android.view.View;

public class MainActivity extends BaseActivity {

    private BookShelfFragment bookShelfFragment;
    private BookStoreFragment storeFragment;
    private CategoryFragment categoryFragment;
    private MineFragment mineFragment;

    /** tab 顺序：书架 → 书城 → 分类 → 我的，仅用于判断滑动方向 */
    private static final int TAB_BOOKSHELF = 0;
    private static final int TAB_BOOKSTORE = 1;
    private static final int TAB_CATEGORY = 2;
    private static final int TAB_MINE = 3;

    /** 书城 tab 双击判定阈值（毫秒）：两次点击间隔小于此值视为双击 */
    private static final long BOOKSTORE_DOUBLE_TAP_MS = 350;
    /** 上次点击（已选中态下）书城 tab 的时间戳，用于双击检测 */
    private long lastBookstoreTapTime = 0;

    private Fragment currentFragment;
    private YieldBottomNav bottomNavigationView;
    private int currentTabIndex = TAB_BOOKSHELF;

    public static MainActivity instance;
    private int lastSelectedItemId = R.id.nav_bookshelf;

    // ═══ 单窗口开屏（替代原独立 SplashActivity）═══
    /** 开屏浮层最短展示时长（系统启动屏退出动画被砍掉省下 ~400ms，加到这里，总时长不变） */
    private static final long SPLASH_MIN_MS = 2900;
    /** 等书架数据的兜底上限（网络过慢也不无限等） */
    private static final long SPLASH_MAX_WAIT_MS = 4500;
    /** 就绪轮询间隔 */
    private static final long SPLASH_POLL_MS = 100;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private View splashOverlay;
    private long mSplashStartAt;
    /** 开屏浮层挡屏期间挂起的页面系统栏配色，浮层退场时补发一次。 */
    private int[] mDeferredBarColors;

    /**
     * 用「主页专用变体」（窗口背景=开屏底 + 系统启动屏同色）：
     * 图标点击 → 系统启动屏（开屏底+图标）→ 主页内开屏浮层（开屏图）→ 书架，
     * 全程单窗口、零 Activity 转场，无系统 starting window 伪影。
     * 具体样式由 BaseActivity 按配色风格解析，日间/夜间由 values-night 同名色值解析。
     */
    @Override
    protected boolean usesMainTheme() {
        return true;
    }

    /**
     * 主页自己接管 inset 落在哪一层：开屏图与页底铺满整屏，顶部由各标签页决定
     * （书架/书城/分类落在根布局 padding，「我的」落在英雄区上），所以不走 BaseActivity 的统一接管。
     */
    @Override
    protected boolean autoBleedSystemBars() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        instance = this;

        // 主题（含配色风格）由 BaseActivity 在 super.onCreate 之前统一落到窗口上
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 底部避让落在根布局的 paddingBottom 上：一次布局同时抬升内容容器与悬浮磨砂卡，
        // 与迁移前 root_layout 的 fitsSystemWindows 等价。
        // 顶部不在这里处理——书架/书城/分类把状态栏 inset 落在自己根布局的 padding 上，「我的」落在英雄区上。
        SystemBarInsets.bleedBottom(findViewById(R.id.root_layout));

        splashOverlay = findViewById(R.id.splash_overlay);

        if (savedInstanceState == null) {
            // ─── 冷启动：主页自身充当启动页 ───
            splashOverlay.setVisibility(View.VISIBLE);
            // 开屏插画只有日间那一版奶油底，没有夜间变体，且它铺满整屏（含两根系统栏底下）。
            // 所以浮层期间图标一律深色，页面自己按底色判定的结果挂起到浮层退场后再补。
            SystemBarInsets.setLightStatusIcons(getWindow(), true);
            SystemBarInsets.setLightNavigationIcons(getWindow(), true);

            // 接管系统启动屏（Android 12+）的退出动画：默认播放 ~300-400ms 的图标放大淡出，
            // 这里改成 60ms 即刻淡出移除 → 系统启动屏在首帧一绘完就结束。
            // 省下的时长已加到 SPLASH_MIN_MS（由开屏浮层展示）；系统启动屏与浮层同为奶油底，视觉无跳变。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSplashScreen().setOnExitAnimationListener(splashView ->
                        splashView.animate().alpha(0f).setDuration(60)
                                .withEndAction(splashView::remove).start());
            }

            // 应用级初始化在开屏展示期间完成（网络拉取/周期同步为异步；迁移放后台线程）
            ExternalSyncManager.getInstance(this).pullAll();
            ExternalSyncManager.getInstance(this).startPeriodic();
            final Context appCtx = getApplicationContext();
            new Thread(() -> ExternalPrefs.migrateIfNeeded(appCtx), "splash-init").start();

            // 书架数据预取：开屏期间把首屏书架接口请求发出去，揭示浮层时书架已就绪
            ShelfPrefetch.prefetch(this);

            mSplashStartAt = SystemClock.elapsedRealtime();
            mUiHandler.postDelayed(this::maybeHideSplash, SPLASH_POLL_MS);
        } else {
            // 配置变更重建等非冷启动场景：直接显示主页内容，不再放开屏
            splashOverlay.setVisibility(View.GONE);
        }

        bottomNavigationView = findViewById(R.id.bottom_navigation);

        //重建时从FragmentManager获取
        if (savedInstanceState != null) {
            lastSelectedItemId = savedInstanceState.getInt("last_tab", R.id.nav_bookshelf);
            bookShelfFragment = (BookShelfFragment) getSupportFragmentManager().findFragmentByTag("BOOKSHELF");
            storeFragment = (BookStoreFragment) getSupportFragmentManager().findFragmentByTag("STORE");
            categoryFragment = (CategoryFragment) getSupportFragmentManager().findFragmentByTag("CATEGORY");
            mineFragment = (MineFragment) getSupportFragmentManager().findFragmentByTag("MINE");

            // 重建后恢复"当前页面"引用，避免再次 show 触发一次多余的进场动画
            currentTabIndex = tabIndex(lastSelectedItemId);
            currentFragment = fragmentForIndex(currentTabIndex);
        }

        //切换Fragment（控件回调同步发出，首帧即带上正确页面）
        bottomNavigationView.setOnTabSelectedListener(new YieldBottomNav.OnTabSelectedListener() {
            @Override
            public void onTabSelected(int itemId) {
                lastSelectedItemId = itemId;
                if (itemId == R.id.nav_bookshelf) {
                    switchFragment(getBookShelfFragment(), "BOOKSHELF", TAB_BOOKSHELF);
                } else if (itemId == R.id.nav_bookstore) {
                    switchFragment(getStoreFragment(), "STORE", TAB_BOOKSTORE);
                } else if (itemId == R.id.nav_category) {
                    switchFragment(getCategoryFragment(), "CATEGORY", TAB_CATEGORY);
                } else if (itemId == R.id.nav_mine) {
                    switchFragment(getMineFragment(), "MINE", TAB_MINE);
                }
            }

            // 书城 tab 双击：刷新界面 + 列表回到顶部（仅在已选中书城页、再次点击时触发）
            @Override
            public void onTabReselected(int itemId) {
                if (itemId != R.id.nav_bookstore) return;
                long now = System.currentTimeMillis();
                if (now - lastBookstoreTapTime <= BOOKSTORE_DOUBLE_TAP_MS) {
                    // 双击命中：刷新并回到顶部
                    BookStoreFragment f = getStoreFragment();
                    if (f != null) {
                        f.onDoubleTapStore();
                    }
                    lastBookstoreTapTime = 0; // 消费后重置，避免三击误触发
                } else {
                    lastBookstoreTapTime = now;
                }
            }
        });

        // 恢复最后停留的 tab：控件首次落选中态不播让位动画，直接同步回调切页面
        bottomNavigationView.setSelectedItemId(lastSelectedItemId);
        getSupportFragmentManager().executePendingTransactions();

        // 进入APP后后台检查更新（延迟2秒，不影响其他功能）。
        // 只在冷启动排队：换肤/日夜会在后台重建主页，每次重建都排一遍就会凭空多弹一次更新框；
        // 走 mUiHandler 而不是临时 new，销毁时才能一起掐掉。
        if (savedInstanceState == null) {
            mUiHandler.postDelayed(() -> {
                UpdateManager.checkUpdate(MainActivity.this, new UpdateManager.UpdateCheckCallback() {
                    @Override
                    public void onUpdateAvailable(ApkPush apkPush) {
                        // 有新版本，弹出更新选择弹窗
                        UpdateManager.showUpdateDialog(MainActivity.this, apkPush);
                    }

                    @Override
                    public void onNoUpdate() {
                        // 已是最新版本，静默不提示（避免打扰）
                    }

                    @Override
                    public void onError(String message) {
                        // 网络错误静默处理，不影响用户使用
                    }
                });
            }, 2000);
        }
    }

    private BookShelfFragment getBookShelfFragment() {
        if (bookShelfFragment == null) bookShelfFragment = new BookShelfFragment();
        return bookShelfFragment;
    }

    private BookStoreFragment getStoreFragment() {
        if (storeFragment == null) storeFragment = new BookStoreFragment();
        return storeFragment;
    }

    private CategoryFragment getCategoryFragment() {
        if (categoryFragment == null) categoryFragment = new CategoryFragment();
        return categoryFragment;
    }

    private MineFragment getMineFragment() {
        if (mineFragment == null) mineFragment = new MineFragment();
        return mineFragment;
    }

    // 安全切换Fragment，带TAG + 过渡动画
    /**
     * 开屏浮层隐藏条件：最短展示时间到 + 书架数据就绪（或兜底超时）。
     * 淡出揭示书架——书架内容在浮层期间已加载完成，揭示即满内容。
     */
    private void maybeHideSplash() {
        if (splashOverlay == null || splashOverlay.getVisibility() != View.VISIBLE) return;
        long elapsed = SystemClock.elapsedRealtime() - mSplashStartAt;
        boolean dataReady = ShelfPrefetch.isDone() || elapsed >= SPLASH_MAX_WAIT_MS;
        if (elapsed < SPLASH_MIN_MS || !dataReady) {
            mUiHandler.postDelayed(this::maybeHideSplash, SPLASH_POLL_MS);
            return;
        }
        splashOverlay.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> {
                    splashOverlay.setVisibility(View.GONE);
                    // 浮层彻底没了才交还图标深浅：淡出过程中奶油底还在，提前翻会糊在一起
                    if (mDeferredBarColors != null) {
                        int[] c = mDeferredBarColors;
                        mDeferredBarColors = null;
                        super.applyBarIcons(c[0], c[1]);
                    }
                })
                .start();
    }

    @Override
    public void applyBarIcons(int topColor, int bottomColor) {
        if (splashOverlay != null && splashOverlay.getVisibility() == View.VISIBLE) {
            mDeferredBarColors = new int[]{topColor, bottomColor};
            return;
        }
        super.applyBarIcons(topColor, bottomColor);
    }

    private void switchFragment(Fragment fragment, String tag, int tabIndex) {
        if (fragment == currentFragment) return;

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        // 首次进入（还没有任何页面时不放动画，避免启动就滑一下）
        if (currentFragment != null) {
            applySlideTransition(transaction, tabIndex);
            transaction.hide(currentFragment);
        }

        if (!fragment.isAdded()) {
            transaction.add(R.id.fragment_container, fragment, tag); // 加tag，防止重建重复
        } else {
            transaction.show(fragment);
        }

        transaction.commitAllowingStateLoss();
        currentFragment = fragment;
        currentTabIndex = tabIndex;
    }

    /**
     * 左右滑动过渡（260ms）：
     * 目标 tab 在当前右边 → 新页面从右侧滑入、旧页面向左滑出；往左回退则方向相反。
     */
    private void applySlideTransition(FragmentTransaction transaction, int tabIndex) {
        boolean toRight = tabIndex > currentTabIndex;
        if (toRight) {
            transaction.setCustomAnimations(
                    R.anim.fragment_slide_in_right, R.anim.fragment_slide_out_left);
        } else {
            transaction.setCustomAnimations(
                    R.anim.fragment_slide_in_left, R.anim.fragment_slide_out_right);
        }
    }

    /** menu id → tab 序号 */
    private int tabIndex(int itemId) {
        if (itemId == R.id.nav_bookstore) return TAB_BOOKSTORE;
        if (itemId == R.id.nav_category) return TAB_CATEGORY;
        if (itemId == R.id.nav_mine) return TAB_MINE;
        return TAB_BOOKSHELF;
    }

    /** tab 序号 → Fragment 实例（未创建时返回 null） */
    private Fragment fragmentForIndex(int index) {
        switch (index) {
            case TAB_BOOKSTORE: return storeFragment;
            case TAB_CATEGORY: return categoryFragment;
            case TAB_MINE: return mineFragment;
            default: return bookShelfFragment;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("last_tab", lastSelectedItemId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 主页现在会被换肤/日夜在后台重建：挂着的开屏轮询和延迟检查更新必须掐掉，
        // 否则回调打在已销毁实例上（漏窗口、凭空弹窗）。
        mUiHandler.removeCallbacksAndMessages(null);
        instance = null;
    }

    /**
     * 切到底部导航的指定 tab（供其它页面 / 空状态跳转用）。
     * lastSelectedItemId 由选中回调统一记录，这里不重复维护。
     */
    public void switchToTab(int itemId) {
        bottomNavigationView.setSelectedItemId(itemId);
    }

    //修复 switchToMine
    public void switchToMine() {
        switchToTab(R.id.nav_mine);
    }
}
