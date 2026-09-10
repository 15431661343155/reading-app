package com.example.myapplication.activity;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.example.myapplication.R;
import com.example.myapplication.bean.ApkPush;
import com.example.myapplication.fragment.BookStoreFragment;
import com.example.myapplication.fragment.DiscussionFragment;
import com.example.myapplication.fragment.MineFragment;
import com.example.myapplication.fragment.BookShelfFragment;
import com.example.myapplication.manager.UpdateManager;
import com.example.myapplication.utils.ThemeManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends BaseActivity {

    private BookShelfFragment bookShelfFragment;
    private BookStoreFragment storeFragment;
    private DiscussionFragment discussionFragment;
    private MineFragment mineFragment;

    /** tab 顺序：书架 → 书城 → 讨论 → 我的，仅用于判断滑动方向 */
    private static final int TAB_BOOKSHELF = 0;
    private static final int TAB_BOOKSTORE = 1;
    private static final int TAB_DISCUSSION = 2;
    private static final int TAB_MINE = 3;

    private Fragment currentFragment;
    private BottomNavigationView bottomNavigationView;
    private int currentTabIndex = TAB_BOOKSHELF;

    public static MainActivity instance;
    private int lastSelectedItemId = R.id.nav_bookshelf;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        instance = this;

        // 主题必须在 super.onCreate 之前设置
        int currentTheme = ThemeManager.getCurrentTheme(this);
        setTheme(ThemeManager.getThemeRes(currentTheme));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNavigationView = findViewById(R.id.bottom_navigation);

        //重建时从FragmentManager获取
        if (savedInstanceState != null) {
            lastSelectedItemId = savedInstanceState.getInt("last_tab", R.id.nav_bookshelf);
            bookShelfFragment = (BookShelfFragment) getSupportFragmentManager().findFragmentByTag("BOOKSHELF");
            storeFragment = (BookStoreFragment) getSupportFragmentManager().findFragmentByTag("STORE");
            discussionFragment = (DiscussionFragment) getSupportFragmentManager().findFragmentByTag("DISCUSSION");
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
            } else if (id == R.id.nav_discussion) {
                switchFragment(getDiscussionFragment(), "DISCUSSION", TAB_DISCUSSION);
                return true;
            } else if (id == R.id.nav_mine) {
                switchFragment(getMineFragment(), "MINE", TAB_MINE);
                return true;
            }
            return false;
        });

        // 恢复最后选中的tab
        bottomNavigationView.setSelectedItemId(lastSelectedItemId);

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

    private DiscussionFragment getDiscussionFragment() {
        if (discussionFragment == null) discussionFragment = new DiscussionFragment();
        return discussionFragment;
    }

    private MineFragment getMineFragment() {
        if (mineFragment == null) mineFragment = new MineFragment();
        return mineFragment;
    }

    // 安全切换Fragment，带TAG + 过渡动画
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
        if (itemId == R.id.nav_discussion) return TAB_DISCUSSION;
        if (itemId == R.id.nav_mine) return TAB_MINE;
        return TAB_BOOKSHELF;
    }

    /** tab 序号 → Fragment 实例（未创建时返回 null） */
    private Fragment fragmentForIndex(int index) {
        switch (index) {
            case TAB_BOOKSTORE: return storeFragment;
            case TAB_DISCUSSION: return discussionFragment;
            case TAB_MINE: return mineFragment;
            default: return bookShelfFragment;
        }
    }

    //保存状态
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

    //修复 switchToMine
    public void switchToMine() {
        lastSelectedItemId = R.id.nav_mine;
        bottomNavigationView.post(() -> {
            bottomNavigationView.setSelectedItemId(R.id.nav_mine);
        });
    }
}
