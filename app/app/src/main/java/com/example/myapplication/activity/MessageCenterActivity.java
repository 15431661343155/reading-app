package com.example.myapplication.activity;

import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.viewpager2.widget.ViewPager2;

import com.example.myapplication.R;
import com.example.myapplication.adapter.MessagePagerAdapter;
import com.example.myapplication.api.ApiService;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.UnreadCountResponse;
import com.example.myapplication.fragment.MessageListFragment;
import com.example.myapplication.utils.ThemeManager;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * 消息中心页
 * TabLayout + ViewPager2 四分类导航：
 * 评论和@ | 赞和收藏 | 关注消息 | 系统通知
 */
public class MessageCenterActivity extends BaseActivity {

    private static final String[] TAB_TITLES_KEYS = {
            "message_tab_comment",
            "message_tab_like",
            "message_tab_follow",
            "message_tab_system"
    };

    private Toolbar toolbar;
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private TextView tvMarkAllRead;
    private int currentTheme;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        currentTheme = ThemeManager.getCurrentTheme(this);
        setTheme(ThemeManager.getThemeRes(currentTheme));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_center);

        initView();
        loadUnreadBadges();
    }

    private void initView() {
        toolbar      = findViewById(R.id.toolbar_back);
        tabLayout    = findViewById(R.id.tab_layout);
        viewPager    = findViewById(R.id.view_pager);
        tvMarkAllRead = findViewById(R.id.tv_mark_all_read);

        // Toolbar 延伸到状态栏
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing

        // 主题适配：海滨主题使用深色文字和图标
        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF000000);
            tvMarkAllRead.setTextColor(0xFF000000);
            tabLayout.setTabTextColors(0x99000000, 0xFF000000);
            tabLayout.setSelectedTabIndicatorColor(0xFF000000);
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF1D1D1F);
            tvMarkAllRead.setTextColor(0xFF1D1D1F);
            tabLayout.setTabTextColors(0xFF8E8E93, 0xFF1D1D1F);
            tabLayout.setSelectedTabIndicatorColor(0xFF007AFF);
        }
        // 海滨主题工具栏为浅色，状态栏图标用深色
        setLightStatusBar(currentTheme == ThemeManager.THEME_SEASIDE);

        toolbar.setNavigationOnClickListener(v -> finish());

        // ViewPager2 适配器
        MessagePagerAdapter pagerAdapter = new MessagePagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        // 保持全部 4 个 Tab 在内存中，避免远端 Tab 视图未布局导致数据不显示
        viewPager.setOffscreenPageLimit(3);

        // TabLayout 与 ViewPager2 联动，使用自定义Tab视图（带红点角标）
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> {
                    int resId = getResources().getIdentifier(
                            TAB_TITLES_KEYS[position], "string", getPackageName());
                    String title = getString(resId);

                    // 使用自定义布局，支持未读红点
                    View customView = LayoutInflater.from(this)
                            .inflate(R.layout.layout_tab_with_badge, null);
                    TextView tvTitle = customView.findViewById(R.id.tv_tab_title);
                    tvTitle.setText(title);

                    if (currentTheme == ThemeManager.THEME_SEASIDE) {
                        tvTitle.setTextColor(position == viewPager.getCurrentItem()
                                ? 0xFF000000 : 0x99000000);
                    } else {
                        tvTitle.setTextColor(position == viewPager.getCurrentItem()
                                ? 0xFF1D1D1F : 0xFF8E8E93);
                    }

                    tab.setCustomView(customView);
                }
        ).attach();

        // Tab切换时更新文字颜色（选中/未选中状态）
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                updateTabTextColor(tab, true);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                updateTabTextColor(tab, false);
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        // 全部已读 — 操作当前 Tab 对应 Fragment 的消息
        tvMarkAllRead.setOnClickListener(v -> markAllReadCurrentTab());
    }

    /**
     * 更新Tab文字颜色（选中/未选中）
     */
    private void updateTabTextColor(TabLayout.Tab tab, boolean selected) {
        if (tab.getCustomView() == null) return;
        TextView tvTitle = tab.getCustomView().findViewById(R.id.tv_tab_title);
        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            tvTitle.setTextColor(selected ? 0xFF000000 : 0x99000000);
        } else {
            tvTitle.setTextColor(selected ? 0xFF1D1D1F : 0xFF8E8E93);
        }
    }

    /**
     * 获取未读消息数量，更新各Tab红点角标
     */
    private void loadUnreadBadges() {
        SharedPreferences sp = getSharedPreferences("user_info", MODE_PRIVATE);
        String userIdStr = sp.getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        if (userId == 0) return;

        ApiService api = RetrofitClient.getApiService();
        api.getUnreadCount(userId).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (response.isSuccessful() && response.body() != null
                            && response.body().isSuccess() && response.body().getData() != null) {
                        UnreadCountResponse data = response.body().getData();
                        long[] typeCounts = {
                                data.getComment(),
                                data.getLike(),
                                data.getFollow(),
                                data.getSystem()
                        };
                        for (int i = 0; i < tabLayout.getTabCount(); i++) {
                            setTabBadge(i, typeCounts[i] > 0);
                        }
                    }
                },
                (call, t) -> { /* 静默失败 */ }
        ));
    }

    /**
     * 设置指定Tab的红点角标显示/隐藏
     */
    private void setTabBadge(int position, boolean hasUnread) {
        if (position >= tabLayout.getTabCount()) return;
        TabLayout.Tab tab = tabLayout.getTabAt(position);
        if (tab == null || tab.getCustomView() == null) return;
        View badgeDot = tab.getCustomView().findViewById(R.id.view_badge_dot);
        badgeDot.setVisibility(hasUnread ? View.VISIBLE : View.GONE);
    }

    /**
     * 将当前 Tab 的所有消息标记为已读
     */
    private void markAllReadCurrentTab() {
        int position = viewPager.getCurrentItem();
        String tag = "f" + position;
        MessageListFragment fragment =
                (MessageListFragment) getSupportFragmentManager().findFragmentByTag(tag);
        if (fragment != null) {
            fragment.markAllRead();
            // 标记已读后隐藏当前Tab红点
            setTabBadge(position, false);
        }
    }
}
