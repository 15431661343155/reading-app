package com.example.myapplication.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.myapplication.R;
import com.example.myapplication.utils.ThemeAttrs;
import com.example.myapplication.utils.ThemeManager;
import com.example.myapplication.adapter.BookAdapter;
import com.example.myapplication.utils.ActivityTransition;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.PageResponse;
import com.example.myapplication.utils.Hint;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 独立搜索页：点击书城中的搜索框进入。
 *
 * <p>功能：
 * <ul>
 *   <li>显示搜索历史（本地 SharedPreferences 存储，最多 20 条）</li>
 *   <li>显示热门搜索推荐词</li>
 *   <li>输入关键词后点击搜索，切换为搜索结果视图（Tab 过滤 + 书籍列表）</li>
 *   <li>支持软键盘 Enter 搜索</li>
 * </ul>
 */
public class SearchActivity extends AppCompatActivity {

    private static final String PREF_NAME = "search_history_pref";
    private static final String KEY_HISTORY = "search_history";
    private static final int MAX_HISTORY = 20;

    // —— 热门搜索词（内置固定 + 可后续拓展为接口返回） ——
    private static final List<String> HOT_KEYWORDS = Arrays.asList(
            "斗破苍穹", "万相之王", "斗罗大陆", "凡人修仙传",
            "西游记", "红楼梦", "水浒传", "三国演义",
            "诡秘之主", "庆余年", "遮天", "金庸"
    );

    // —— UI 控件 ——
    private ImageView ivBack;
    private EditText etSearch;
    private ImageView ivClearInput;
    private TextView tvSearchBtn;
    private ImageView ivClearHistory;

    private ScrollView layoutHistory;
    private LinearLayout layoutHistoryTags;
    private LinearLayout layoutHotTags;

    private LinearLayout layoutResults;
    private TabLayout tabFilter;
    private RecyclerView rvResult;
    private SwipeRefreshLayout swipeRefresh;

    // —— 数据 ——
    private BookAdapter adapter;
    private final List<Book> allResultBooks = new ArrayList<>();
    private final List<Book> filteredBooks = new ArrayList<>();
    private int currentFilter = 0; // 0全部 1小说 2文学 3外站

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 本页不继承 BaseActivity，配色风格要自己落到主题上（必须在 super.onCreate 之前，主题一参与布局解析就改不了）
        setTheme(ThemeManager.themeResId(this, false));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        // 状态栏与搜索页顶部同色；图标明暗跟随日夜配置（本页不继承 BaseActivity，故就地处理）
        getWindow().setStatusBarColor(ThemeAttrs.color(this, R.attr.appSurface, 0));
        int barFlags = getWindow().getDecorView().getSystemUiVisibility();
        if (ThemeManager.isNight(this)) {
            barFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            barFlags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(barFlags);

        bindViews();
        setupListeners();
        setupRecyclerView();

        renderHistory();
        renderHotKeywords();

        // 支持通过 Intent extra 注入关键词（便于 ADB 测试中文搜索）
        String extraKeyword = getIntent().getStringExtra("keyword");
        if (extraKeyword != null && !extraKeyword.isEmpty()) {
            etSearch.setText(extraKeyword);
            // 直接触发搜索
            doSearch(extraKeyword);
        }

        // 入场转场：从右侧滑入（与首页 tab 切换的「左右滑动」观感一致）
        ActivityTransition.applyEnter(this);
    }

    /* ================= 绑定视图 ================= */

    private void bindViews() {
        ivBack = findViewById(R.id.iv_back);
        etSearch = findViewById(R.id.et_search_input);
        ivClearInput = findViewById(R.id.iv_clear_input);
        tvSearchBtn = findViewById(R.id.tv_search_btn);
        ivClearHistory = findViewById(R.id.iv_clear_history);

        layoutHistory = findViewById(R.id.layout_history);
        layoutHistoryTags = findViewById(R.id.layout_history_tags);
        layoutHotTags = findViewById(R.id.layout_hot_tags);

        layoutResults = findViewById(R.id.layout_results);
        tabFilter = findViewById(R.id.tab_result_filter);
        rvResult = findViewById(R.id.rv_search_result);
        swipeRefresh = findViewById(R.id.swipe_refresh_search);

        swipeRefresh.setColorSchemeResources(
                R.color.purple_500, R.color.cyan_400, R.color.teal_200);
    }

    private void setupListeners() {
        ivBack.setOnClickListener(v -> finish());

        // 搜索按钮
        tvSearchBtn.setOnClickListener(v -> doSearch(etSearch.getText().toString().trim()));

        ivClearInput.setOnClickListener(v -> {
            etSearch.setText("");
            ivClearInput.setVisibility(View.GONE);
            showHistoryView();
        });

        // 输入框：监听文字变化显示清空按钮；监听 Enter 键搜索
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ivClearInput.setVisibility((s != null && s.length() > 0) ? View.VISIBLE : View.GONE);
                if (s == null || s.length() == 0) {
                    showHistoryView();
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                doSearch(etSearch.getText().toString().trim());
                hideKeyboard();
                return true;
            }
            return false;
        });

        // 清除历史（长按）
        ivClearHistory.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("确定清空所有搜索历史吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空", (d, w) -> {
                        clearHistory();
                        renderHistory();
                    }).show();
        });

        // 下拉刷新（仅在结果视图时有用）
        swipeRefresh.setOnRefreshListener(() -> {
            if (layoutResults.getVisibility() == View.VISIBLE) {
                doSearch(etSearch.getText().toString().trim());
            } else {
                swipeRefresh.setRefreshing(false);
            }
        });

        // 结果过滤 Tab
        tabFilter.addTab(tabFilter.newTab().setText("全部"));
        tabFilter.addTab(tabFilter.newTab().setText("小说"));
        tabFilter.addTab(tabFilter.newTab().setText("文学"));
        tabFilter.addTab(tabFilter.newTab().setText("外站"));
        tabFilter.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentFilter = tab.getPosition();
                applyFilter();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setupRecyclerView() {
        rvResult.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BookAdapter(this, filteredBooks);
        rvResult.setAdapter(adapter);
        adapter.setOnItemClickListener(book -> {
            Intent i = new Intent(this, BookDetailActivity.class);
            i.putExtra("book", book);
            startActivity(i);
        });
    }

    /* ================= 历史/热门 渲染 ================= */

    private void renderHistory() {
        List<String> history = getHistory();
        layoutHistoryTags.removeAllViews();
        if (history.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("暂无搜索历史");
            tv.setTextSize(13);
            tv.setTextColor(ThemeAttrs.color(this, R.attr.appTextTertiary, 0));
            layoutHistoryTags.addView(tv);
            return;
        }
        for (String kw : history) {
            layoutHistoryTags.addView(makeTag(kw, true));
        }
    }

    private void renderHotKeywords() {
        layoutHotTags.removeAllViews();
        for (String kw : HOT_KEYWORDS) {
            layoutHotTags.addView(makeTag(kw, false));
        }
    }

    private View makeTag(final String keyword, boolean isHistory) {
        TextView tv = new TextView(this);
        tv.setText(keyword);
        tv.setTextSize(13);
        tv.setPadding(24, 10, 24, 10);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999); // iOS 胶囊全圆
        if (isHistory) {
            bg.setColor(ThemeAttrs.color(this, R.attr.appCardSoft, 0)); // 二级底
            tv.setTextColor(ThemeAttrs.color(this, R.attr.appTextPrimary, 0));
        } else {
            bg.setColor(ThemeAttrs.color(this, R.attr.appSeparator, 0));
            tv.setTextColor(ThemeAttrs.color(this, R.attr.appAccent, 0));
        }
        tv.setBackground(bg);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = 14;
        p.bottomMargin = 10;
        tv.setLayoutParams(p);

        tv.setOnClickListener(v -> {
            etSearch.setText(keyword);
            etSearch.setSelection(keyword.length());
            doSearch(keyword);
        });
        return tv;
    }

    /* ================= 视图切换 ================= */

    private void showHistoryView() {
        layoutHistory.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);
        renderHistory();
    }

    private void showResultView() {
        layoutHistory.setVisibility(View.GONE);
        layoutResults.setVisibility(View.VISIBLE);
    }

    /* ================= 搜索核心逻辑 ================= */

    private void doSearch(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            Hint.show(this, "请输入搜索关键词");
            return;
        }
        hideKeyboard();
        saveHistory(keyword);
        showResultView();
        swipeRefresh.setRefreshing(true);
        allResultBooks.clear();
        adapter.notifyDataSetChanged();

        // 并行搜索：本地数据库 + 所有外站书源
        final AtomicInteger pending = new AtomicInteger(2);
        final Set<String> seen = Collections.synchronizedSet(new HashSet<>());

        // 1) 本地搜索
        RetrofitClient.getApiService().searchBooks(keyword, 0, 50)
                .enqueue(new Callback<ApiResponse<PageResponse<Book>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<PageResponse<Book>>> c,
                                           Response<ApiResponse<PageResponse<Book>>> r) {
                        if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                && r.body().getData() != null) {
                            List<Book> books = r.body().getData().getContent();
                            addBooksUnique(books, seen);
                        }
                        applyFilter();
                        if (pending.decrementAndGet() == 0) {
                            swipeRefresh.setRefreshing(false);
                            if (allResultBooks.isEmpty()) {
                                Hint.show(SearchActivity.this, "未找到相关书籍");
                            }
                        }
                    }
                    @Override
                    public void onFailure(Call<ApiResponse<PageResponse<Book>>> c, Throwable t) {
                        if (pending.decrementAndGet() == 0) swipeRefresh.setRefreshing(false);
                    }
                });

        // 2) 外站书源搜索
        RetrofitClient.getApiService().searchOnlineBooks(keyword, "all", 0, 50)
                .enqueue(new Callback<ApiResponse<List<Book>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<Book>>> c,
                                           Response<ApiResponse<List<Book>>> r) {
                        if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                && r.body().getData() != null) {
                            List<Book> books = r.body().getData();
                            // 关键词再过滤
                            List<Book> filtered = filterByKeyword(books, keyword);
                            addBooksUnique(filtered, seen);
                        }
                        applyFilter();
                        if (pending.decrementAndGet() == 0) {
                            swipeRefresh.setRefreshing(false);
                            if (allResultBooks.isEmpty()) {
                                Hint.show(SearchActivity.this, "未找到相关书籍");
                            }
                        }
                    }
                    @Override
                    public void onFailure(Call<ApiResponse<List<Book>>> c, Throwable t) {
                        if (pending.decrementAndGet() == 0) swipeRefresh.setRefreshing(false);
                    }
                });
    }

    /** 去重添加到 allResultBooks */
    private void addBooksUnique(List<Book> books, Set<String> seen) {
        if (books == null) return;
        for (Book b : books) {
            String key = (b.getTitle() == null ? "" : b.getTitle())
                    + "|" + (b.getAuthor() == null ? "" : b.getAuthor());
            if (seen.add(key)) {
                allResultBooks.add(b);
            }
        }
    }

    /** Tab 过滤应用到 filteredBooks */
    private void applyFilter() {
        filteredBooks.clear();
        for (Book b : allResultBooks) {
            String st = b.getSourceType() == null ? "" : b.getSourceType();
            String cat = b.getCategory() == null ? "" : b.getCategory();
            Boolean pd = b.getPublicDomain();
            if (currentFilter == 0) {
                filteredBooks.add(b);
            } else if (currentFilter == 1) {
                // 小说：本地且非公版
                boolean isFiction = (pd == null || !pd) && !st.startsWith("CUSTOM_");
                if (isFiction) filteredBooks.add(b);
            } else if (currentFilter == 2) {
                // 文学：本地公版（文学/经典）
                boolean isLit = Boolean.TRUE.equals(pd) || "文学".equals(cat);
                if (isLit) filteredBooks.add(b);
            } else if (currentFilter == 3) {
                // 外站：sourceType 为 CUSTOM_xxx
                if (st.startsWith("CUSTOM_")) filteredBooks.add(b);
            }
        }
        adapter.notifyDataSetChanged();
    }

    /* ================= 历史 本地存储 ================= */

    private List<String> getHistory() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> set = sp.getStringSet(KEY_HISTORY, null);
        if (set == null || set.isEmpty()) return new ArrayList<>();
        // Set 无序，需要按追加顺序；我们使用逗号字符串 + 切分的方式保序
        String raw = sp.getString(KEY_HISTORY + "_ordered", "");
        if (raw != null && !raw.isEmpty()) {
            String[] arr = raw.split("\\|\\|");
            List<String> list = new ArrayList<>();
            for (String s : arr) {
                if (s != null && !s.isEmpty()) list.add(s);
            }
            return list;
        }
        return new ArrayList<>(set);
    }

    private void saveHistory(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        List<String> history = new ArrayList<>(getHistory());
        // 先移除重复的，再插入到头部，保证最新在最前
        history.remove(keyword);
        history.add(0, keyword);
        if (history.size() > MAX_HISTORY) {
            history = history.subList(0, MAX_HISTORY);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : history) {
            if (sb.length() > 0) sb.append("||");
            sb.append(s);
        }
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KEY_HISTORY + "_ordered", sb.toString())
                .putStringSet(KEY_HISTORY, new LinkedHashSet<>(history))
                .apply();
    }

    private void clearHistory() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }

    /* ================= 工具 ================= */

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                View v = getCurrentFocus();
                if (v == null) v = new View(this);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } catch (Exception ignore) {
        }
    }

    /** 外站结果关键词过滤（与 BookStoreFragment 保持一致） */
    private static List<Book> filterByKeyword(List<Book> books, String keyword) {
        List<Book> out = new ArrayList<>();
        if (books == null || books.isEmpty()) return out;
        if (keyword == null || keyword.trim().isEmpty()) { out.addAll(books); return out; }
        String nkw = normalizeMatch(keyword);
        if (nkw.isEmpty()) { out.addAll(books); return out; }
        boolean shortNumeric = nkw.matches("\\d+") && nkw.length() <= 2;
        for (Book b : books) {
            boolean match = normalizeMatch(b.getTitle()).contains(nkw)
                    || normalizeMatch(b.getAuthor()).contains(nkw)
                    || normalizeMatch(b.getIntro()).contains(nkw)
                    || (!shortNumeric && normalizeMatch(b.getCategory()).contains(nkw));
            if (match) out.add(b);
        }
        return out;
    }

    private static String normalizeMatch(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[\\p{Punct}\\p{P}\\p{S}]", "")
                .trim();
    }

    /** 返回转场：上一页从左滑回，当前页向右滑出（与入场动画反向对称） */
    @Override
    public void finish() {
        super.finish();
        ActivityTransition.applyExit(this);
    }
}
