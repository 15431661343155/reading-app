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

    private Fragment currentFragment;
    private BottomNavigationView bottomNavigationView;

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
        }

        //切换Fragment
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            lastSelectedItemId = id;

            if (id == R.id.nav_bookshelf) {
                switchFragment(getBookShelfFragment(), "BOOKSHELF");
                return true;
            } else if (id == R.id.nav_bookstore) {
                switchFragment(getStoreFragment(), "STORE");
                return true;
            } else if (id == R.id.nav_discussion) {
                switchFragment(getDiscussionFragment(), "DISCUSSION");
                return true;
            } else if (id == R.id.nav_mine) {
                switchFragment(getMineFragment(), "MINE");
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

    // 安全切换Fragment，带TAG
    private void switchFragment(Fragment fragment, String tag) {
        if (fragment == currentFragment) return;

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        if (currentFragment != null) transaction.hide(currentFragment);

        if (!fragment.isAdded()) {
            transaction.add(R.id.fragment_container, fragment, tag); // 加tag，防止重建重复
        } else {
            transaction.show(fragment);
        }

        transaction.commitAllowingStateLoss();
        currentFragment = fragment;
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
