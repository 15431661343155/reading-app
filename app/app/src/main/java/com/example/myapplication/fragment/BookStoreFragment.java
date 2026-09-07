package com.example.myapplication.fragment;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.myapplication.R;
import com.example.myapplication.activity.BookDetailActivity;
import com.example.myapplication.activity.SearchActivity;
import com.example.myapplication.adapter.BookAdapter;
import com.example.myapplication.adapter.SourceSelectAdapter;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.PageResponse;
import com.example.myapplication.bean.SourceInfo;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 书城主页 Fragment（功能改版）。
 *
 * <p>新功能：
 * <ol>
 *   <li>搜索框为"纯展示+可点击"，点击进入独立搜索页 {@link SearchActivity}</li>
 *   <li>搜索框右侧添加「分类图标框」，点击弹出面板含两个大选项：
 *     <ul>
 *       <li>📚 本站藏书（默认）：加载本站数据库内书籍（分类 Tab：小说 / 文学）</li>
 *       <li>🌐 外站书城：点击后弹窗显示全部可用书源，选中某书源后切换到该网站
 *         「分类 + 榜单/推荐」书籍</li>
 *     </ul>
 *   </li>
 *   <li>标题行显示当前模式（📚 本站藏书 / 🌐 七猫小说网 等）与副标题说明</li>
 *   <li>二级分类 Tab 针对不同模式显示对应分类（本站：本地接口返回 / 外站：静态预设分类）</li>
 * </ol>
 */
public class BookStoreFragment extends Fragment {

    /* ================= 常量 —— 书城模式 ================= */
    /** 模式：本站藏书（本地数据库） */
    private static final int MODE_LOCAL = 0;
    /** 模式：外站书城（某一具体书源 sourceType） */
    private static final int MODE_EXTERNAL = 2;

    /* ================= 视图 ================= */
    private RecyclerView rvBookstore;
    private SwipeRefreshLayout swipeRefresh;
    private BookAdapter adapter;
    private final List<Book> storeBookList = new ArrayList<>();

    private LinearLayout layoutSearchBar;
    private LinearLayout layoutCategoryIcon;
    private TextView tvModeTitle;
    private TextView tvModeSubtitle;

    private HorizontalScrollView hsvSubCategory;
    private LinearLayout layoutSubCategory;

    /* ================= 状态 ================= */
    private int mainMode = MODE_LOCAL;           // MODE_LOCAL or MODE_EXTERNAL
    private String externalSourceType = "";       // 外站模式下：具体书源 type
    private String externalSourceName = "";       // 外站模式下：具体书源 name，用于标题

    private String currentSubCategory = "";
    private final List<String> subCategoryList = new ArrayList<>();
    /** 外站书源分类列表：从后端 explore-categories API 获取的 {分类名, URL} */
    private final List<String[]> externalCategories = new ArrayList<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_bookstore, container, false);

        // 不再手动加状态栏padding：activity_main.xml 的 fitsSystemWindows 已让系统自动避让
        updateStatusBarColor();

        bindViews(view);
        setupRecyclerView(view);
        setupSwipeRefresh(view);
        setupClickListeners(view);

        // 恢复上次书城模式（本站/外站），首次启动默认 本站藏书-小说
        restoreStateAndInit();
        return view;
    }

    /* ================= 绑定/初始化 ================= */

    private void bindViews(View v) {
        layoutSearchBar = v.findViewById(R.id.layout_search_bar);
        layoutCategoryIcon = v.findViewById(R.id.layout_category_icon);
        tvModeTitle = v.findViewById(R.id.tv_mode_title);
        tvModeSubtitle = v.findViewById(R.id.tv_mode_subtitle);
        hsvSubCategory = v.findViewById(R.id.hsv_sub_category);
        layoutSubCategory = v.findViewById(R.id.layout_sub_category);
    }

    private void setupRecyclerView(View v) {
        rvBookstore = v.findViewById(R.id.rv_bookstore);
        rvBookstore.setLayoutManager(new LinearLayoutManager(getActivity()));
        adapter = new BookAdapter(getContext(), storeBookList);
        rvBookstore.setAdapter(adapter);
        adapter.setOnItemClickListener(book -> {
            Intent i = new Intent(getActivity(), BookDetailActivity.class);
            i.putExtra("book", book);
            startActivity(i);
        });
    }

    private void setupSwipeRefresh(View v) {
        swipeRefresh = v.findViewById(R.id.swipe_refresh_bookstore);
        swipeRefresh.setColorSchemeResources(
                R.color.purple_500, R.color.cyan_400, R.color.teal_200);
        swipeRefresh.setOnRefreshListener(this::reloadCurrentMode);
    }

    private void setupClickListeners(View v) {
        // 1) 点击搜索框 → 跳转独立搜索页
        layoutSearchBar.setOnClickListener(x -> {
            Intent i = new Intent(getActivity(), SearchActivity.class);
            startActivity(i);
        });

        // 2) 点击分类图标框 → 弹出书城模式选择面板
        layoutCategoryIcon.setOnClickListener(x -> showBookStoreModePanel());
    }

    /* ================= 书城模式面板（本站藏书 / 外站书城） ================= */

    private void showBookStoreModePanel() {
        // 使用 AlertDialog.Builder 构建自定义面板（两个大选项）
        View panel = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_bookstore_mode_panel, null, false);
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(panel)
                .create();

        // 选项 A：本站藏书
        View optLocal = panel.findViewById(R.id.opt_local);
        View optExt = panel.findViewById(R.id.opt_external);
        ImageView ivClose = panel.findViewById(R.id.iv_close);
        ImageView ivRadioLocal = panel.findViewById(R.id.iv_radio_local);
        ImageView ivRadioExternal = panel.findViewById(R.id.iv_radio_external);
        TextView tvSelectSource = panel.findViewById(R.id.tv_select_source);

        // 根据当前模式同步两个 radio 的选中状态
        applyRadioState(ivRadioLocal, ivRadioExternal);

        optLocal.setOnClickListener(x -> {
            dialog.dismiss();
            switchToLocalBooks();
        });

        optExt.setOnClickListener(x -> {
            dialog.dismiss();
            // 使用上次保存的书源切换到外站书城；不再自动弹出书源选择弹窗。
            if (externalSourceType != null && !externalSourceType.isEmpty()) {
                switchToExternal(externalSourceType, externalSourceName);
            } else {
                Toast.makeText(getContext(),
                        "请先点击「选择书源」选一个外站书源", Toast.LENGTH_SHORT).show();
            }
        });

        // 选项 B 内「选择书源 →」按钮：单独打开书源选择弹窗（重选书源）
        tvSelectSource.setOnClickListener(x -> {
            dialog.dismiss();
            showExternalSourceDialog();
        });

        ivClose.setOnClickListener(x -> dialog.dismiss());

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    /**
     * 根据当前 mainMode 同步切换书城面板的两个单选圆框：
     *  本地选中：蓝实心 + 白勾 / 外站未选：空心灰圆
     *  外站选中：蓝实心 + 白勾 / 本地未选：空心灰圆
     */
    private void applyRadioState(ImageView ivLocal, ImageView ivExt) {
        boolean isExternal = (mainMode == MODE_EXTERNAL);
        ivLocal.setImageResource(isExternal
                ? R.drawable.ic_checkbox_unchecked
                : R.drawable.ic_checkbox_checked_orange);
        ivExt.setImageResource(isExternal
                ? R.drawable.ic_checkbox_checked_orange
                : R.drawable.ic_checkbox_unchecked);
    }

    private void setTagSelected(TextView tv, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999); // iOS 胶囊全圆
        if (selected) {
            g.setColor(Color.parseColor("#007AFF")); // iOS 蓝
            tv.setTextColor(Color.WHITE);
        } else {
            g.setColor(Color.parseColor("#F2F2F7")); // iOS 分组底
            tv.setTextColor(Color.parseColor("#1D1D1F")); // iOS 主文字
        }
        tv.setBackground(g);
    }

    /* ================= 外站书城 —— 书源选择弹窗 ================= */

    private void showExternalSourceDialog() {
        View layout = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_source_select, null, false);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setView(layout).create();

        ImageView ivClose = layout.findViewById(R.id.iv_dialog_close);
        RecyclerView rvList = layout.findViewById(R.id.rv_source_list);
        rvList.setLayoutManager(new LinearLayoutManager(getContext()));

        SourceSelectAdapter adapter = new SourceSelectAdapter();
        // 已有选中 sourceType 则高亮
        adapter.setData(null, mainMode == MODE_EXTERNAL ? externalSourceType : "");
        rvList.setAdapter(adapter);

        // 最后点击的 source，用户需点击「确认」来切换
        final SourceInfo[] pending = new SourceInfo[1];
        adapter.setListener(source -> {
            pending[0] = source;
            adapter.setSelectedType(source == null ? "" : source.getType());
        });

        ivClose.setOnClickListener(x -> dialog.dismiss());

        // 加载书源列表
        swipeRefresh.setRefreshing(true);
        RetrofitClient.getApiService().getOnlineSources()
                .enqueue(new Callback<ApiResponse<List<SourceInfo>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<SourceInfo>>> c,
                                           Response<ApiResponse<List<SourceInfo>>> r) {
                        swipeRefresh.setRefreshing(false);
                        if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                && r.body().getData() != null) {
                            adapter.setData(r.body().getData(),
                                    mainMode == MODE_EXTERNAL ? externalSourceType : "");
                        } else {
                            Toast.makeText(getContext(), "获取书源列表失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                    @Override
                    public void onFailure(Call<ApiResponse<List<SourceInfo>>> c, Throwable t) {
                        swipeRefresh.setRefreshing(false);
                        Toast.makeText(getContext(), "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });

        // 注册「确认」按钮：必须在 dialog.show() 之前 setButton，否则 AlertDialog 不会渲染按钮区
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "确认", (d, w) -> {
            SourceInfo s = pending[0];
            if (s == null) {
                // 用户未显式点击条目但有高亮项（例如默认选中），直接使用高亮项
                s = adapter.findSelected();
            }
            if (s != null) {
                switchToExternal(s.getType(), s.getName());
            } else {
                Toast.makeText(getContext(), "请选择一个书源", Toast.LENGTH_SHORT).show();
            }
        });
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "取消", (d, w) -> dialog.dismiss());

        dialog.setOnDismissListener(d -> {
            // 仅作为兜底：若用户未点确认但已选中 source（例如按系统返回键），不自动应用以避免误操作
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    /* ================= 书城模式持久化（退出 App 后仍记忆上次选择） ================= */
    private static final String PREF_NAME = "bookstore_state";
    private static final String KEY_MAIN_MODE = "main_mode";      // 0=本地, 2=外站
    private static final String KEY_EXT_TYPE  = "ext_source_type";
    private static final String KEY_EXT_NAME  = "ext_source_name";

    private SharedPreferences prefState() {
        return requireContext().getApplicationContext()
                .getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
    }

    private void saveState() {
        try {
            SharedPreferences.Editor e = prefState().edit();
            e.putInt(KEY_MAIN_MODE, mainMode);
            e.putString(KEY_EXT_TYPE, externalSourceType == null ? "" : externalSourceType);
            e.putString(KEY_EXT_NAME, externalSourceName == null ? "" : externalSourceName);

            // ✅ 正确策略：主线程 apply()（保证点击不被 FS 延迟卡顿→不会 ANR），
            // 然后立刻在后台线程 commit() 一次强制刷盘，保证崩/划走/重启前已经落盘。
            e.apply();
            try {
                new Thread("BookStore-flush-state") {
                    @Override public void run() {
                        try {
                            // 后台再用 commit() 把 apply 留下的 pending write 强制落盘，
                            // 不占用主线程，但很快（通常<100ms）就完成持久化，
                            // 这样即使用户在 1 秒内立刻触发崩溃或从最近任务划走，也不会丢。
                            SharedPreferences.Editor force = prefState().edit();
                            force.putLong("__flush_token", System.currentTimeMillis());
                            force.commit();
                        } catch (Throwable ignored) {}
                    }
                }.start();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            android.util.Log.w("BookStore", "saveState 异常", t);
        }
    }

    @Override public void onPause() {
        super.onPause();
        // 用户离开 Fragment（切 Activity / Home / 最近任务），立刻同步刷盘一次，
        // 保证后续系统杀进程或崩溃不会重置模式
        try {
            new Thread("BookStore-commit-onPause") {
                @Override public void run() {
                    try {
                        SharedPreferences sp = prefState();
                        sp.edit().putLong("__last_leave", System.currentTimeMillis()).commit();
                    } catch (Throwable ignored) {}
                }
            }.start();
        } catch (Throwable ignored) {}
    }

    private void restoreStateAndInit() {
        SharedPreferences sp = prefState();
        int savedMain = sp.getInt(KEY_MAIN_MODE, MODE_LOCAL);
        String savedExtType = sp.getString(KEY_EXT_TYPE, "");
        String savedExtName = sp.getString(KEY_EXT_NAME, "");
        if (savedMain == MODE_EXTERNAL && savedExtType != null && !savedExtType.isEmpty()) {
            // 使用已保存的 sourceType/name，不重新请求书源列表
            externalSourceType = savedExtType;
            externalSourceName = (savedExtName == null || savedExtName.isEmpty()) ? "外站书城" : savedExtName;
            mainMode = MODE_EXTERNAL;
            currentSubCategory = "";
            saveState();
            tvModeTitle.setText("外站书城：" + externalSourceName);
            tvModeSubtitle.setText("外站书源 · 分类/榜单");
            // loadExternalCategories 会决定 Tab 是否显示
            loadExternalCategories();
        } else {
            switchToLocalBooks();
        }
    }

    /* ================= 模式切换入口 ================= */

    private void switchToLocalBooks() {
        mainMode = MODE_LOCAL;
        // 切换到本地藏书时，保留已选的外站书源记忆（externalSourceType/Name 不清空），
        // 这样之后再次切回外站书城时能直接复用上次选择，无需重新弹书源列表。
        currentSubCategory = "";
        saveState();
        tvModeTitle.setText("本站藏书");
        tvModeSubtitle.setText("本地书城");
        hsvSubCategory.setVisibility(View.VISIBLE);
        loadSubCategoriesAndData();
    }

    private void switchToExternal(String sourceType, String sourceName) {
        if (sourceType == null || sourceType.isEmpty()) return;
        mainMode = MODE_EXTERNAL;
        externalSourceType = sourceType;
        externalSourceName = sourceName == null ? "外站书城" : sourceName;
        currentSubCategory = "";
        saveState();
        tvModeTitle.setText("外站书城：" + externalSourceName);
        tvModeSubtitle.setText("外站书源 · 分类/榜单");
        // 外站模式：从后端获取书源实际分类（loadExternalCategories 会决定 Tab 是否显示）
        loadExternalCategories();
    }

    /* ================= 外站动态分类加载 ================= */

    private void loadExternalCategories() {
        externalCategories.clear();
        swipeRefresh.setRefreshing(true);
        RetrofitClient.getApiService().getExploreCategories(externalSourceType)
                .enqueue(new Callback<ApiResponse<List<String[]>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<String[]>>> c,
                                           Response<ApiResponse<List<String[]>>> r) {
                        swipeRefresh.setRefreshing(false);
                        subCategoryList.clear();
                        if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                && r.body().getData() != null && !r.body().getData().isEmpty()) {
                            externalCategories.addAll(r.body().getData());
                            // 有分类：显示分类 Tab
                            subCategoryList.add("全部");
                            for (String[] cat : externalCategories) {
                                if (cat[0] != null && !cat[0].isEmpty()) {
                                    subCategoryList.add(cat[0]);
                                }
                            }
                            hsvSubCategory.setVisibility(View.VISIBLE);
                        } else {
                            // 书源无分类信息：隐藏分类 Tab，直接加载 explore 数据
                            hsvSubCategory.setVisibility(View.GONE);
                        }
                        currentSubCategory = "";
                        refreshSubCategoryViews();
                        loadDataForCurrentMode();
                    }
                    @Override
                    public void onFailure(Call<ApiResponse<List<String[]>>> c, Throwable t) {
                        swipeRefresh.setRefreshing(false);
                        // 网络失败：隐藏分类 Tab，直接加载 explore 数据
                        subCategoryList.clear();
                        hsvSubCategory.setVisibility(View.GONE);
                        currentSubCategory = "";
                        refreshSubCategoryViews();
                        loadDataForCurrentMode();
                    }
                });
    }

    /* ================= 本地子分类加载 & 渲染 ================= */

    private void loadSubCategoriesAndData() {
        Call<ApiResponse<List<String>>> call = RetrofitClient.getApiService().getFictionCategories();

        call.enqueue(new Callback<ApiResponse<List<String>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<String>>> c, Response<ApiResponse<List<String>>> r) {
                subCategoryList.clear();
                subCategoryList.add("全部");
                if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                        && r.body().getData() != null) {
                    subCategoryList.addAll(r.body().getData());
                }
                refreshSubCategoryViews();
                loadDataForCurrentMode();
            }
            @Override
            public void onFailure(Call<ApiResponse<List<String>>> c, Throwable t) {
                refreshSubCategoryViews();
                loadDataForCurrentMode();
            }
        });
    }

    private void refreshSubCategoryViews() {
        layoutSubCategory.removeAllViews();
        for (int i = 0; i < subCategoryList.size(); i++) {
            String category = subCategoryList.get(i);
            TextView tv = new TextView(getContext());
            tv.setText(category);
            tv.setTextSize(13);
            tv.setPadding(18, 8, 18, 8);

            boolean isSelected = (i == 0 && currentSubCategory.isEmpty())
                    || category.equals(currentSubCategory);

            if (isSelected) {
                tv.setTextColor(Color.parseColor("#007AFF")); // iOS 蓝
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(999);
                bg.setColor(Color.parseColor("#E8E8ED")); // 选中：浅灰底
                tv.setBackground(bg);
            } else {
                tv.setTextColor(Color.parseColor("#8E8E93")); // iOS 次文字
                tv.setBackground(null); // 未选中：透明
            }

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) p.leftMargin = 12;
            tv.setLayoutParams(p);

            final int index = i;
            tv.setOnClickListener(v -> {
                currentSubCategory = (index == 0) ? "" : category;
                refreshSubCategoryViews();
                loadDataForCurrentMode();
            });
            layoutSubCategory.addView(tv);
        }
    }

    /* ================= 数据加载入口（按模式分发） ================= */

    private void reloadCurrentMode() {
        loadDataForCurrentMode();
    }

    private void loadDataForCurrentMode() {
        if (mainMode == MODE_LOCAL) {
            loadLocalBooks();
        } else {
            loadExternalBooks();
        }
    }

    /* ================= 模式 A：本站藏书（本地数据库） ================= */

    private void loadLocalBooks() {
        String category = (currentSubCategory == null || currentSubCategory.isEmpty())
                ? null : currentSubCategory;
        Call<ApiResponse<PageResponse<Book>>> call = RetrofitClient.getApiService().getFictionBooks(0, 50, category);

        swipeRefresh.setRefreshing(true);
        call.enqueue(new Callback<ApiResponse<PageResponse<Book>>>() {
            @Override
            public void onResponse(Call<ApiResponse<PageResponse<Book>>> c,
                                   Response<ApiResponse<PageResponse<Book>>> r) {
                swipeRefresh.setRefreshing(false);
                storeBookList.clear();
                if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                        && r.body().getData() != null) {
                    storeBookList.addAll(r.body().getData().getContent());
                } else {
                    Toast.makeText(getContext(), "加载失败", Toast.LENGTH_SHORT).show();
                }
                adapter.notifyDataSetChanged();
            }
            @Override
            public void onFailure(Call<ApiResponse<PageResponse<Book>>> c, Throwable t) {
                swipeRefresh.setRefreshing(false);
                Toast.makeText(getContext(), "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /* ================= 模式 B：外站书城（具体书源 explore 接口） ================= */

    private void loadExternalBooks() {
        if (externalSourceType == null || externalSourceType.isEmpty()) return;
        swipeRefresh.setRefreshing(true);
        // "全部"分类传 null（后端走第一个分类）；其他分类传分类名
        String category = (currentSubCategory == null || currentSubCategory.isEmpty()
                || currentSubCategory.equals("全部")) ? null : currentSubCategory;
        RetrofitClient.getApiService().exploreOnlineBooks(externalSourceType, 0, 50, category)
                .enqueue(new Callback<ApiResponse<List<Book>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<Book>>> c,
                                           Response<ApiResponse<List<Book>>> r) {
                        swipeRefresh.setRefreshing(false);
                        storeBookList.clear();
                        if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                && r.body().getData() != null) {
                            storeBookList.addAll(r.body().getData());
                        } else {
                            Toast.makeText(getContext(), "加载榜单失败，请确认书源可用", Toast.LENGTH_SHORT).show();
                        }
                        adapter.notifyDataSetChanged();
                    }
                    @Override
                    public void onFailure(Call<ApiResponse<List<Book>>> c, Throwable t) {
                        swipeRefresh.setRefreshing(false);
                        Toast.makeText(getContext(), "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /* ================= 生命周期 ================= */

    private void updateStatusBarColor() {
        if (getActivity() == null) return;
        // 状态栏背景与页面顶部颜色统一，消除割裂
        getActivity().getWindow().setStatusBarColor(
            getActivity().getResources().getColor(R.color.ios_bg_grouped, null));
        int flags = getActivity().getWindow().getDecorView().getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getActivity().getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatusBarColor();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) updateStatusBarColor();
    }
}
