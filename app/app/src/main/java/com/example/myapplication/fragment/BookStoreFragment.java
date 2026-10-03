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

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import com.example.myapplication.R;
import com.example.myapplication.activity.BaseActivity;
import com.example.myapplication.activity.BookDetailActivity;
import com.example.myapplication.activity.SearchActivity;
import com.example.myapplication.adapter.BookAdapter;
import com.example.myapplication.adapter.LocalStorePageAdapter;
import com.example.myapplication.adapter.SourceSelectAdapter;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.CategoryTree;
import com.example.myapplication.bean.PageResponse;
import com.example.myapplication.bean.SourceInfo;
import com.example.myapplication.utils.ThemeAttrs;
import com.example.myapplication.utils.Hint;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 书城主页 Fragment。
 *
 * <p>两个模式共用同一份书籍列表（顶部只有搜索框一行 + 右侧分类图标）：
 * <ul>
 *   <li><b>本站藏书</b>（默认）：本站数据库内的书籍，按阅读量/热度排序。
 *       分类浏览已交给独立的「分类」页（底部导航第三个 tab），
 *       所以这里不再显示模式标题行，也没有一级分类 Tab。</li>
 *   <li><b>外站书城</b>：某个具体书源的「分类 + 榜单/推荐」书籍，
 *       顶部显示模式标题行 + 横滚二级分类。</li>
 * </ul>
 *
 * <p>点击搜索框进 {@link SearchActivity}；点击搜索框右侧分类图标弹出模式/书源面板。
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
    /** 外站书城模式的整页「加载失败」层（本站子页面各有自己的失败层，见 LocalStorePageAdapter） */
    private com.example.myapplication.widget.LoadFailView loadFailView;

    private LinearLayout layoutSearchBar;
    private LinearLayout layoutCategoryIcon;
    private TextView tvModeTitle;
    private TextView tvModeSubtitle;

    private HorizontalScrollView hsvSubCategory;
    private LinearLayout layoutSubCategory;
    /** 当前书城模式标题行（仅外站模式显示） */
    private LinearLayout layoutModeTitle;

    /* ===== 本站藏书：主分类子页面（新书 / 男生 / 女生 / 后台新增主分类），支持点 Tab 与左右滑动 ===== */
    private TabLayout tabLocalMain;
    private ViewPager2 vpLocalStore;
    private LocalStorePageAdapter localPageAdapter;
    private TabLayoutMediator localTabMediator;
    private boolean localPagesReady = false;

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
        setupLocalPages();
        setupClickListeners(view);

        // 恢复上次书城模式（本站/外站），首次启动默认 本站藏书-小说
        restoreStateAndInit();
        return view;
    }

    /* ================= 绑定/初始化 ================= */

    private void bindViews(View v) {
        layoutSearchBar = v.findViewById(R.id.layout_search_bar);
        layoutCategoryIcon = v.findViewById(R.id.layout_category_icon);
        layoutModeTitle = v.findViewById(R.id.layout_mode_title);
        tvModeTitle = v.findViewById(R.id.tv_mode_title);
        tvModeSubtitle = v.findViewById(R.id.tv_mode_subtitle);
        hsvSubCategory = v.findViewById(R.id.hsv_sub_category);
        layoutSubCategory = v.findViewById(R.id.layout_sub_category);
        tabLocalMain = v.findViewById(R.id.tab_local_main);
        vpLocalStore = v.findViewById(R.id.vp_local_store);
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

        // 外站书城模式的整页失败层
        loadFailView = v.findViewById(R.id.load_fail_view);
        loadFailView.setOnRetryClick(v2 -> {
            loadFailView.hide();
            reloadCurrentMode();
        });
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

    /** 面板只构建一次并复用：书源弹窗取消后要 hide→show 回到这里，而不是重建 */
    private AlertDialog modePanelDialog;
    private ImageView ivRadioLocal, ivRadioExternal;
    private View optExternalCard;
    private LinearLayout llSourceHint;
    private TextView tvSourceState, tvSourceStateType, tvSelectSource;

    private void showBookStoreModePanel() {
        ensureModePanel();
        refreshModePanel();
        modePanelDialog.show();
    }

    private void ensureModePanel() {
        if (modePanelDialog != null) return;

        View panel = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_bookstore_mode_panel, null, false);
        ivRadioLocal = panel.findViewById(R.id.iv_radio_local);
        ivRadioExternal = panel.findViewById(R.id.iv_radio_external);
        optExternalCard = panel.findViewById(R.id.opt_external);
        llSourceHint = panel.findViewById(R.id.ll_source_hint);
        tvSourceState = panel.findViewById(R.id.tv_source_state);
        tvSourceStateType = panel.findViewById(R.id.tv_source_state_type);
        tvSelectSource = panel.findViewById(R.id.tv_select_source);

        panel.findViewById(R.id.opt_local).setOnClickListener(x -> {
            modePanelDialog.dismiss();
            switchToLocalBooks();
        });

        // 已选过书源 → 点整卡直接切换；未选 → 弹窗保持打开，警示条就地显示
        optExternalCard.setOnClickListener(x -> {
            if (hasExternalSource()) {
                modePanelDialog.dismiss();
                switchToExternal(externalSourceType, externalSourceName);
            } else {
                showSourceHint();
            }
        });

        // 「选择书源 / 更换书源」：面板隐藏（不是关闭），书源弹窗取消后再回到面板
        tvSelectSource.setOnClickListener(x -> {
            modePanelDialog.hide();
            showExternalSourceDialog();
        });

        panel.findViewById(R.id.iv_close).setOnClickListener(x -> modePanelDialog.dismiss());

        modePanelDialog = new AlertDialog.Builder(getContext())
                .setView(panel)
                .create();
        if (modePanelDialog.getWindow() != null) {
            modePanelDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    /** 每次打开面板时同步：单选圈、当前书源回显、按钮文案，并收起上一次的警示条 */
    private void refreshModePanel() {
        boolean has = hasExternalSource();
        applyRadioState(ivRadioLocal, ivRadioExternal);
        tvSourceState.setText(has ? "当前书源：" + externalSourceName : "尚未选择书源");
        tvSourceStateType.setText(has ? externalSourceType : "");
        tvSourceStateType.setVisibility(has ? View.VISIBLE : View.GONE);
        tvSelectSource.setText(has ? "更换书源 →" : "选择书源 →");
        llSourceHint.setVisibility(View.GONE);
        optExternalCard.setBackgroundResource(R.drawable.bg_option_card);
    }

    private void showSourceHint() {
        optExternalCard.setBackgroundResource(R.drawable.bg_option_card_warn);
        llSourceHint.setAlpha(0f);
        llSourceHint.setVisibility(View.VISIBLE);
        llSourceHint.animate().alpha(1f).setDuration(180).start();
    }

    private boolean hasExternalSource() {
        return externalSourceType != null && !externalSourceType.isEmpty();
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
            g.setColor(ThemeAttrs.color(requireContext(), R.attr.appAccent, 0));
            tv.setTextColor(Color.WHITE);
        } else {
            g.setColor(ThemeAttrs.color(requireContext(), R.attr.appCardSoft, 0));
            tv.setTextColor(ThemeAttrs.color(requireContext(), R.attr.appTextPrimary, 0));
        }
        tv.setBackground(g);
    }

    /* ================= 外站书城 —— 书源选择弹窗 ================= */

    /** 书源弹窗单独留引用，Fragment 视图销毁时一并关掉，避免窗口泄漏 */
    private AlertDialog sourceDialog;

    private void showExternalSourceDialog() {
        View layout = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_source_select, null, false);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setView(layout).create();
        sourceDialog = dialog;

        ImageView ivClose = layout.findViewById(R.id.iv_dialog_close);
        RecyclerView rvList = layout.findViewById(R.id.rv_source_list);
        rvList.setLayoutManager(new LinearLayoutManager(getContext()));
        MaterialCardView cardList = layout.findViewById(R.id.card_source_list);
        LinearLayout llLoading = layout.findViewById(R.id.ll_source_loading);
        LinearLayout llError = layout.findViewById(R.id.ll_source_error);
        TextView tvSelected = layout.findViewById(R.id.tv_source_selected);
        TextView tvConfirm = layout.findViewById(R.id.tv_source_confirm);
        TextView tvCancel = layout.findViewById(R.id.tv_source_cancel);
        TextView tvError = layout.findViewById(R.id.tv_source_error);
        TextView tvRetry = layout.findViewById(R.id.tv_source_retry);

        SourceSelectAdapter sourceAdapter = new SourceSelectAdapter();
        // 已应用的 type 打「当前」徽标，同时作为本次待确认的高亮项
        String currentType = mainMode == MODE_EXTERNAL ? externalSourceType : "";
        sourceAdapter.setAppliedType(currentType);
        sourceAdapter.setData(null, currentType);
        rvList.setAdapter(sourceAdapter);

        // 最后点击的 source，用户需点击「确认」才会真正切换
        final SourceInfo[] pending = new SourceInfo[1];
        final boolean[] applied = {false};

        Runnable syncFooter = () -> {
            SourceInfo s = pending[0] != null ? pending[0] : sourceAdapter.findSelected();
            tvConfirm.setEnabled(s != null);
            tvConfirm.setAlpha(s == null ? 0.4f : 1f);
            tvSelected.setText(s == null ? "请选择一个书源" : "已选：" + s.getName());
        };
        sourceAdapter.setListener(source -> {
            pending[0] = source;
            sourceAdapter.setSelectedType(source == null ? "" : source.getType());
            syncFooter.run();
        });

        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            llLoading.setVisibility(View.VISIBLE);
            llError.setVisibility(View.GONE);
            cardList.setVisibility(View.GONE);
            tvSelected.setText("正在获取书源…");
            tvConfirm.setEnabled(false);
            tvConfirm.setAlpha(0.4f);
            RetrofitClient.getApiService().getOnlineSources()
                    .enqueue(new Callback<ApiResponse<List<SourceInfo>>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<List<SourceInfo>>> c,
                                               Response<ApiResponse<List<SourceInfo>>> r) {
                            if (!dialog.isShowing()) return;
                            llLoading.setVisibility(View.GONE);
                            if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                    && r.body().getData() != null) {
                                List<SourceInfo> sources = r.body().getData();
                                sourceAdapter.setData(sources, currentType);
                                cardList.setVisibility(View.VISIBLE);
                                llError.setVisibility(View.GONE);
                                capSourceListHeight(rvList, sources.size());
                                syncFooter.run();
                            } else {
                                showSourceError(llError, tvError, "获取书源列表失败");
                                syncFooter.run();
                            }
                        }

                        @Override
                        public void onFailure(Call<ApiResponse<List<SourceInfo>>> c, Throwable t) {
                            if (!dialog.isShowing()) return;
                            llLoading.setVisibility(View.GONE);
                            showSourceError(llError, tvError, "网络异常，请检查网络后重试");
                            syncFooter.run();
                        }
                    });
        };
        tvRetry.setOnClickListener(x -> load[0].run());

        ivClose.setOnClickListener(x -> dialog.dismiss());
        tvCancel.setOnClickListener(x -> dialog.dismiss());
        tvConfirm.setOnClickListener(x -> {
            SourceInfo s = pending[0] != null ? pending[0] : sourceAdapter.findSelected();
            if (s == null) return;
            applied[0] = true;
            dialog.dismiss();
            if (modePanelDialog != null) modePanelDialog.dismiss();
            switchToExternal(s.getType(), s.getName());
        });

        dialog.setOnDismissListener(d -> {
            boolean userClosed = sourceDialog == d;
            sourceDialog = null;
            // 取消 / ✕ / 返回键 → 回到切换书城面板；点确认切换或视图正在销毁时不重开
            if (!applied[0] && userClosed && isAdded()) showBookStoreModePanel();
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
        load[0].run();
    }

    private void showSourceError(LinearLayout llError, TextView tvError, String msg) {
        llError.setVisibility(View.VISIBLE);
        tvError.setText(msg);
    }

    /** 书源过多时给列表封顶（行高约 62dp，留 5 行后内部滚动），避免底部按钮被顶出屏幕 */
    private void capSourceListHeight(RecyclerView rv, int count) {
        if (count <= 5) return;
        ViewGroup.LayoutParams lp = rv.getLayoutParams();
        lp.height = (int) (62 * 5 * getResources().getDisplayMetrics().density + 0.5f);
        rv.setLayoutParams(lp);
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

            // 正确策略：主线程 apply()（保证点击不被 FS 延迟卡顿→不会 ANR），
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 两个弹窗都跨开关复用，视图销毁时必须关掉避免窗口泄漏；
        // 先摘掉引用再 dismiss，书源弹窗的 DismissListener 才不会把面板重新弹出来。
        AlertDialog source = sourceDialog;
        sourceDialog = null;
        if (source != null) source.dismiss();
        AlertDialog panel = modePanelDialog;
        modePanelDialog = null;
        if (panel != null) panel.dismiss();
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
        tvModeSubtitle.setText("本地书城 · 按热度排序");
        applyModeViews();
        if (loadFailView != null) loadFailView.hide();   // 切回本站 → 外站整页失败层退场
        loadLocalPages();
    }

    /**
     * 本站模式与外站模式的视图切换：
     *   本站 → 主分类 Tab（新书 / 男生 / 女生 / 后台新增主分类）+ ViewPager2 子页面，
     *          点 Tab 或左右滑动切换；无模式标题行
     *   外站 → 模式标题行 + 二级分类横滚 + 单个书籍列表
     */
    private void applyModeViews() {
        boolean local = (mainMode == MODE_LOCAL);
        layoutModeTitle.setVisibility(local ? View.GONE : View.VISIBLE);
        tabLocalMain.setVisibility(local ? View.VISIBLE : View.GONE);
        vpLocalStore.setVisibility(local ? View.VISIBLE : View.GONE);
        hsvSubCategory.setVisibility(local ? View.GONE : View.VISIBLE);
        swipeRefresh.setVisibility(local ? View.GONE : View.VISIBLE);
    }

    /* ================= 模式 A：本站藏书（主分类子页面） ================= */

    /** 初始化本站藏书的 ViewPager2 + TabLayout（只做一次；页面数据在 loadLocalPages 里灌）。 */
    private void setupLocalPages() {
        localPageAdapter = new LocalStorePageAdapter();
        localPageAdapter.setOnBookClickListener(book -> {
            Intent i = new Intent(getActivity(), BookDetailActivity.class);
            i.putExtra("book", book);
            startActivity(i);
        });
        vpLocalStore.setAdapter(localPageAdapter);
        // 只预加载相邻一页，避免一次并发太多请求
        vpLocalStore.setOffscreenPageLimit(1);

        localTabMediator = new TabLayoutMediator(tabLocalMain, vpLocalStore,
                (tab, position) -> tab.setText(localPageAdapter.getPageTitle(position)));
        localTabMediator.attach();
    }

    /**
     * 拉取分类树并重建本站藏书的子页面：固定第一个「新书」页（按导入时间倒序），
     * 其余按后端主分类顺序（后台新增主分类后，书城会自动多出一页）。
     */
    private void loadLocalPages() {
        RetrofitClient.getApiService().getCategoryTree()
                .enqueue(new Callback<ApiResponse<CategoryTree>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<CategoryTree>> c,
                                           Response<ApiResponse<CategoryTree>> r) {
                        List<LocalStorePageAdapter.PageSpec> specs = new ArrayList<>();
                        specs.add(new LocalStorePageAdapter.PageSpec("新书", null, null));
                        if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                && r.body().getData() != null) {
                            for (CategoryTree.MainCategory m : r.body().getData().getMains()) {
                                if (m == null || m.getName() == null || m.getName().isEmpty()) continue;
                                specs.add(new LocalStorePageAdapter.PageSpec(
                                        m.getName(), m.getName(), m.getSubs()));
                            }
                        }
                        applyLocalPages(specs);
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<CategoryTree>> c, Throwable t) {
                        // 分类树拿不到时，至少保证「新书」页可用
                        List<LocalStorePageAdapter.PageSpec> specs = new ArrayList<>();
                        specs.add(new LocalStorePageAdapter.PageSpec("新书", null, null));
                        applyLocalPages(specs);
                        Hint.show(getContext(), "分类加载失败，已按新书显示");
                    }
                });
    }

    private void applyLocalPages(List<LocalStorePageAdapter.PageSpec> specs) {
        localPageAdapter.setPages(specs);
        if (vpLocalStore.getCurrentItem() >= specs.size()) {
            vpLocalStore.setCurrentItem(0, false);
        }
        if (localPagesReady) {
            // 已初始化过：分类可能有变动，强制刷新当前页
            localPageAdapter.requestReload(vpLocalStore.getCurrentItem());
        }
        localPagesReady = true;
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
        applyModeViews();
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
                            // 书源自带分类：直接以真实分类名构建 Tab，不再强行插入 synthetic "全部"。
                            // 仅当书源自身含名为"全部"的分类时才显示"全部"；否则默认选中第一个真实分类，
                            // 避免之前"全部"被映射成 null → 后端取首个分类 → 与首分类内容重复的问题。
                            String defaultCat = null;
                            for (String[] cat : externalCategories) {
                                if (cat[0] != null && !cat[0].isEmpty()) {
                                    subCategoryList.add(cat[0]);
                                    if (defaultCat == null) defaultCat = cat[0];
                                    if ("全部".equals(cat[0].trim())) {
                                        // 书源自带"全部"分类：默认选中它（命中其真实 URL，而非首分类）
                                        defaultCat = cat[0];
                                    }
                                }
                            }
                            currentSubCategory = (defaultCat == null) ? "" : defaultCat;
                            hsvSubCategory.setVisibility(
                                    subCategoryList.isEmpty() ? View.GONE : View.VISIBLE);
                        } else {
                            // 书源无分类信息：隐藏分类 Tab，直接加载 explore 数据
                            hsvSubCategory.setVisibility(View.GONE);
                            currentSubCategory = "";
                        }
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

    /* ================= 外站：横滚二级分类 ================= */

    private void refreshSubCategoryViews() {
        layoutSubCategory.removeAllViews();
        for (int i = 0; i < subCategoryList.size(); i++) {
            String category = subCategoryList.get(i);
            TextView tv = new TextView(getContext());
            tv.setText(category);
            tv.setTextSize(13);
            tv.setPadding(18, 8, 18, 8);

            boolean isSelected = category.equals(currentSubCategory);

            if (isSelected) {
                tv.setTextColor(ThemeAttrs.color(requireContext(), R.attr.appSegText, 0));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(999);
                bg.setColor(ThemeAttrs.color(requireContext(), R.attr.appSeparator, 0));
                tv.setBackground(bg);
            } else {
                tv.setTextColor(ThemeAttrs.color(requireContext(), R.attr.appTextSecondary, 0));
                tv.setBackground(null); // 未选中：透明
            }

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) p.leftMargin = 12;
            tv.setLayoutParams(p);

            final int index = i;
            tv.setOnClickListener(v -> {
                // 每个 Tab 都是书源的真实分类名，直接作为查询分类（无 synthetic "全部" → 空串）
                currentSubCategory = category;
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

    /**
     * 双击底部「书城」tab 的回调：刷新当前模式数据 + 列表滚动回顶部。
     * 由 {@link MainActivity} 在检测到书城 tab 双击时调用。
     */
    public void onDoubleTapStore() {
        if (mainMode == MODE_LOCAL) {
            // 本地模式：只刷新「当前主分类子页」并回顶。
            // 注意：不要走 reloadCurrentMode() -> loadLocalPages() 整批重建分页。
            // 适配器未设稳定 item ID，整批 notifyDataSetChanged() 会让 ViewPager2 在重新布局瞬间
            // 先回弹到首屏「新书」(第 0 页) 再弹回当前页，造成“先显示新书再跳对应界面”的闪烁。
            int pos = vpLocalStore.getCurrentItem();
            localPageAdapter.scrollToTop(pos);
            localPageAdapter.requestReload(pos);
        } else {
            // 外站模式：单列表，直接回顶 + 重新拉榜单
            rvBookstore.scrollToPosition(0);
            reloadCurrentMode();
        }
    }

    private void loadDataForCurrentMode() {
        if (mainMode == MODE_LOCAL) {
            loadFailView.hide();   // 切回本站模式 → 外站整页失败层退场（本站子页面有自己的失败层）
            loadLocalPages();
        } else {
            loadExternalBooks();
        }
    }

    /* ================= 模式 B：外站书城（具体书源 explore 接口） ================= */

    private void loadExternalBooks() {
        if (externalSourceType == null || externalSourceType.isEmpty()) return;
        swipeRefresh.setRefreshing(true);
        loadFailView.hide();   // 重试/刷新开始 → 失败层退场
        // currentSubCategory 现在恒为书源真实分类名；为空表示书源无分类（后端走默认 exploreUrl）。
        // 若书源自带"全部"分类，则"全部"会作为真实分类名传入并命中其真实 URL，不再与首分类重复。
        String category = (currentSubCategory == null || currentSubCategory.isEmpty())
                ? null : currentSubCategory;
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
                            loadFailView.hide();
                        } else {
                            loadFailView.show("加载榜单失败，请确认书源可用");
                        }
                        adapter.notifyDataSetChanged();
                    }
                    @Override
                    public void onFailure(Call<ApiResponse<List<Book>>> c, Throwable t) {
                        swipeRefresh.setRefreshing(false);
                        loadFailView.show("网络异常，请检查网络后重试");
                    }
                });
    }

    /* ================= 生命周期 ================= */

    private void updateStatusBarColor() {
        if (getActivity() == null) return;
        // 状态栏背景与页面顶部颜色统一，消除割裂
        getActivity().getWindow().setStatusBarColor(
            ThemeAttrs.color(requireActivity(), R.attr.appPageBg, 0));
        // 图标明暗交由宿主按当前日夜配置复位（夜间深色底要用浅色图标）
        ((BaseActivity) getActivity()).applyStatusBarIcons();
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
