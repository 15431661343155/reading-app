package com.example.myapplication.activity;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentTransaction;
import com.example.myapplication.fragment.ChapterListFragment;

import com.example.myapplication.R;
import com.example.myapplication.bean.Book;
import com.example.myapplication.utils.ThemeManager;

public class ChapterListActivity extends BaseActivity {

    private Book currentBook;
    private int currentChapterIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int currentTheme = ThemeManager.getCurrentTheme(this);
        setTheme(ThemeManager.getThemeRes(currentTheme));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chapter_list_simple);  // 使用简化布局

        currentBook = (Book) getIntent().getSerializableExtra("book");
        currentChapterIndex = getIntent().getIntExtra("currentChapter", 0);

        if (currentBook == null) {
            finish();
            return;
        }

        setupToolbar(currentTheme);

        // 直接加载目录Fragment，不需要TabLayout和ViewPager
        ChapterListFragment fragment = ChapterListFragment.newInstance(currentBook, currentChapterIndex);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
    }

    private void setupToolbar(int currentTheme) {
        Toolbar toolbar = findViewById(R.id.toolbar_back);
        if (toolbar == null) return;
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing

        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF000000);
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF1D1D1F);
        }
        // 海滨主题工具栏为浅色，状态栏图标用深色
        setLightStatusBar(currentTheme == ThemeManager.THEME_SEASIDE);
        toolbar.setTitle(currentBook.getBookName());
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.fade_out);
    }
}
