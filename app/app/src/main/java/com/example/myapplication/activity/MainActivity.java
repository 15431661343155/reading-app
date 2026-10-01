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
import com.example.myapplication.utils.ThemeManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.lang.reflect.Field;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

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
    private BottomNavigationView bottomNavigationView;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        instance = this;

        // 主题必须在 super.onCreate 之前设置。
        // 运行时用「主页专用变体」（窗口背景=奶油色 + 系统启动屏同色）：
        // 图标点击 → 系统启动屏（奶油底+图标）→ 主页内开屏浮层（开屏图）→ 书架，
        // 全程单窗口、零 Activity 转场，无系统 starting window 伪影。
        int currentTheme = ThemeManager.getCurrentTheme(this);
        setTheme(currentTheme == ThemeManager.THEME_SEASIDE
                ? R.style.Theme_MyApp_Seaside_Main : R.style.Theme_MyApp_Main);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        splashOverlay = findViewById(R.id.splash_overlay);

        if (savedInstanceState == null) {
            // ─── 冷启动：主页自身充当启动页 ───
            splashOverlay.setVisibility(View.VISIBLE);

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

        //切换Fragment
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            lastSelectedItemId = id;

            if (id == R.id.nav_bookshelf) {
                switchFragment(getBookShelfFragment(), "BOOKSHELF", TAB_BOOKSHELF);
                return true;
            } else if (id == R.id.nav_bookstore) {
                switchFragment(getStoreFragment(), "STORE", TAB_BOOKSTORE);
                return true;
            } else if (id == R.id.nav_category) {
                switchFragment(getCategoryFragment(), "CATEGORY", TAB_CATEGORY);
                return true;
            } else if (id == R.id.nav_mine) {
                switchFragment(getMineFragment(), "MINE", TAB_MINE);
                return true;
            }
            return false;
        });

        // 书城 tab 双击：刷新界面 + 列表回到顶部（仅在已选中书城页、再次点击时触发）
        bottomNavigationView.setOnNavigationItemReselectedListener(item -> {
            if (item.getItemId() != R.id.nav_bookstore) return;
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
        });

        // 恢复最后选中的tab（仅同步导航栏的选中态，未必会触发 listener）
        bottomNavigationView.setSelectedItemId(lastSelectedItemId);

        // 兜底：部分机型 setSelectedItemId 的选中回调是异步 post 的，
        // 若 onCreate 结束时片段尚未提交，这里直接同步提交书架片段，
        // 保证冷启动首帧即包含完整书架视图（数据已由开屏预取就绪），
        // 否则首帧会露出空容器（窗口底色）再闪成书架
        if (currentFragment == null) {
            switchFragment(getBookShelfFragment(), "BOOKSHELF", TAB_BOOKSHELF);
        }
        getSupportFragmentManager().executePendingTransactions();

        // 导航 item 点击反馈：按下缩到 0.92、松手弹性回弹（无波纹，波纹已在布局中置 @null）
        setupNavItemPressFeedback();

        // 消除选中/未选中文字的“标签缩放过渡”：强制每个 item 的 scale 因子为 1，
        // 配合 active/inactive 同一份 NavTextAppearance，使切换一步到位、不再“先变色后变粗”。
        bottomNavigationView.post(this::normalizeNavLabelScale);

        // 兜底：确保首屏 fragment 一定被加载。
        // 部分 Material 版本下 setSelectedItemId 对"已选中项"不会触发 OnNavigationItemSelectedListener，
        // 导致 fragment_container 一直为空——首屏只剩底部导航栏、磨砂栏也因背后无内容显得异常；
        // 切到别的 tab 选中态变化后才正常。这里在 currentFragment 为空时手动加载一次首屏。
        if (currentFragment == null) {
            int initIndex = tabIndex(lastSelectedItemId);
            Fragment initFrag;
            String initTag;
            switch (initIndex) {
                case TAB_BOOKSTORE: initFrag = getStoreFragment(); initTag = "STORE"; break;
                case TAB_CATEGORY: initFrag = getCategoryFragment(); initTag = "CATEGORY"; break;
                case TAB_MINE: initFrag = getMineFragment(); initTag = "MINE"; break;
                default: initFrag = getBookShelfFragment(); initTag = "BOOKSHELF";
            }
            switchFragment(initFrag, initTag, initIndex);
        }

        // 进入APP后后台检查更新（延迟2秒，不影响其他功能）
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
                .withEndAction(() -> splashOverlay.setVisibility(View.GONE))
                .start();
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
        instance = null;
    }

    /**
     * 切到底部导航的指定 tab（供其它页面 / 空状态跳转用）。
     * 同时记住 lastSelectedItemId，避免重建后回到旧 tab。
     */
    public void switchToTab(int itemId) {
        lastSelectedItemId = itemId;
        bottomNavigationView.post(() -> bottomNavigationView.setSelectedItemId(itemId));
    }

    //修复 switchToMine
    public void switchToMine() {
        switchToTab(R.id.nav_mine);
    }

    /**
     * 给每个底部导航 item 加“按下回弹”点击反馈：
     *  - 手指按下：item 整体缩到 0.92（轻微下压手感）
     *  - 手指松开/移出：用 OvershootInterpolator 弹性回弹到 1.0（带轻微过冲）
     * 波纹已在布局 itemRippleColor=@null 中去掉；此处 TouchListener 返回 false，不消费事件，
     * 因此 BottomNavigationView 的选中逻辑（含书城双击检测）不受影响。
     */
    private void setupNavItemPressFeedback() {
        if (bottomNavigationView.getChildCount() == 0) return;
        View menuView = bottomNavigationView.getChildAt(0); // BottomNavigationMenuView
        if (!(menuView instanceof ViewGroup)) return;
        ViewGroup menu = (ViewGroup) menuView;
        for (int i = 0; i < menu.getChildCount(); i++) {
            View itemView = menu.getChildAt(i);
            itemView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            v.animate().cancel();
                            v.animate().scaleX(0.92f).scaleY(0.92f)
                                    .setDuration(60).setInterpolator(new AccelerateInterpolator()).start();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.animate().cancel();
                            v.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(260).setInterpolator(new OvershootInterpolator(2.2f)).start();
                            break;
                        default:
                            break;
                    }
                    return false; // 不消费，保证选中/双击逻辑正常
                }
            });
        }
    }

    /**
     * 强制底部导航每个 item 的标签缩放因子为 1，消除切换选中时的“标签缩放过渡”动画。
     * Material 1.11.0 的 NavigationBarItemView 内部用 smallLabel/largeLabel 两个独立 TextView，
     * 并以 scaleUpFactor/scaleDownFactor（= 选中/未选中字号之比）做缩放过渡。若 active 与 inactive
     * 的字号/字重不一致（Material 默认选中态加粗），切换时会先变色、再“变粗/变细”，产生分步卡顿感。
     * 这里把缩放因子锁死为 1，配合 styles.xml 中 active/inactive 同一份 NavTextAppearance，
     * 使选中切换“一步到位”。纯反射实现，失败不影响主流程。
     */
    private void normalizeNavLabelScale() {
        try {
            View menuView = bottomNavigationView.getChildAt(0);
            if (!(menuView instanceof ViewGroup)) return;
            Class<?> itemClass = Class.forName("com.google.android.material.navigation.NavigationBarItemView");
            Field scaleUp = itemClass.getDeclaredField("scaleUpFactor");
            Field scaleDown = itemClass.getDeclaredField("scaleDownFactor");
            scaleUp.setAccessible(true);
            scaleDown.setAccessible(true);
            ViewGroup menu = (ViewGroup) menuView;
            for (int i = 0; i < menu.getChildCount(); i++) {
                View child = menu.getChildAt(i);
                if (itemClass.isInstance(child)) {
                    scaleUp.setFloat(child, 1.0f);
                    scaleDown.setFloat(child, 1.0f);
                }
            }
        } catch (Exception ignored) {
            // 反射失败不阻断：恒定字重已通过样式保证，最坏只是保留原本的轻微缩放
        }
    }
}
