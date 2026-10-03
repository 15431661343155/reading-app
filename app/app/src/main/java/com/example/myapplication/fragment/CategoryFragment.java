package com.example.myapplication.fragment;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.activity.BaseActivity;
import com.example.myapplication.activity.CategoryBooksActivity;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.CategoryTree;
import com.example.myapplication.utils.ThemeAttrs;
import com.example.myapplication.utils.Hint;
import com.example.myapplication.widget.LoadingView;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 分类页（底部导航第三个 tab，原「讨论」页改版）。
 *
 * <p>布局：左侧竖排主分类（男生 / 女生 / 后台自定义），点击切换右侧该主分类下的子分类卡片；
 * 点子分类卡片进入 {@link CategoryBooksActivity} 查看该分类下的书籍。
 *
 * <p>数据源是后端 {@code GET /api/books/category-tree}，与后台「分类管理」页同源，
 * 后台增删改主/子分类后这里会自动跟着变（进入页面时按 60s 节流刷新）。
 */
public class CategoryFragment extends Fragment {

    /** 左侧主分类：条目高度 / 选中指示条宽度 / 右侧子分类网格列数 */
    private static final int MAIN_ITEM_HEIGHT_DP = 52;
    private static final int INDICATOR_WIDTH_DP = 3;
    private static final int GRID_SPAN_COUNT = 3;
    /** 分类树刷新节流：距上次加载超过这个时间、再次进入页面时重新拉取 */
    private static final long REFRESH_INTERVAL_MS = 60 * 1000L;

    private LinearLayout layoutMain;
    private RecyclerView rvSub;
    private TextView tvSubEmpty;
    /** 全站统一加载动画（首次拉取分类树期间显示） */
    private LoadingView loadingView;
    /** 全站统一加载失败层（插画 + 文案 + 「再试一次」） */
    private com.example.myapplication.widget.LoadFailView loadFailView;
    private SubAdapter subAdapter;

    /** 左侧每一行的视图引用，用于刷新选中态（避免反复 findViewById） */
    private final List<MainRow> mainRows = new ArrayList<>();
    private final List<CategoryTree.MainCategory> mains = new ArrayList<>();
    private final List<String> subs = new ArrayList<>();

    private int selectedIndex = -1;
    private boolean loaded = false;
    private boolean loading = false;
    private long lastLoadedAt = 0L;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_category, container, false);

        // 不再手动加状态栏padding：activity_main.xml 的 fitsSystemWindows 已让系统自动避让
        updateStatusBarColor();

        layoutMain = view.findViewById(R.id.layout_main);
        rvSub = view.findViewById(R.id.rv_sub);
        tvSubEmpty = view.findViewById(R.id.tv_sub_empty);
        loadingView = view.findViewById(R.id.loading_view);
        loadFailView = view.findViewById(R.id.load_fail_view);

        rvSub.setLayoutManager(new GridLayoutManager(getContext(), GRID_SPAN_COUNT));
        rvSub.setOverScrollMode(View.OVER_SCROLL_NEVER);
        subAdapter = new SubAdapter();
        rvSub.setAdapter(subAdapter);

        // 加载失败时点空态文案重试
        tvSubEmpty.setOnClickListener(v -> loadTree(false));

        // 加载失败层：点「再试一次」重新拉取
        loadFailView.setOnRetryClick(v -> {
            loadFailView.hide();
            loadTree(false);
        });

        refreshIfStale();
        return view;
    }

    /* ================= 数据 ================= */

    /** 首次进入直接加载；已加载过则超过节流时间才重新拉取（避免每次切 tab 都打接口） */
    private void refreshIfStale() {
        if (!loaded) {
            loadTree(false);
        } else if (System.currentTimeMillis() - lastLoadedAt > REFRESH_INTERVAL_MS) {
            loadTree(true);
        }
    }

    /**
     * 拉取分类树并渲染。
     *
     * @param keepSelection true = 刷新后尽量保持当前选中的主分类（按名字匹配）
     */
    private void loadTree(final boolean keepSelection) {
        if (loading) return;
        loading = true;
        // 一条分类都还没有时（首次进入 / 失败后重试）先显示加载动画；静默刷新不打扰
        if (mains.isEmpty()) {
            setLoading(true);
            loadFailView.hide();   // 重试开始 → 失败层退场（它在 LoadingView 之上，必须先收）
        }

        final String keepName = (keepSelection && selectedIndex >= 0 && selectedIndex < mains.size())
                ? mains.get(selectedIndex).getName() : "";

        RetrofitClient.getApiService().getCategoryTree()
                .enqueue(new Callback<ApiResponse<CategoryTree>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<CategoryTree>> c,
                                           Response<ApiResponse<CategoryTree>> r) {
                        loading = false;
                        loaded = true;
                        lastLoadedAt = System.currentTimeMillis();
                        mains.clear();
                        boolean ok = r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                && r.body().getData() != null;
                        if (ok) {
                            List<CategoryTree.MainCategory> list = r.body().getData().getMains();
                            if (list != null) {
                                for (CategoryTree.MainCategory m : list) {
                                    if (m.getName() != null && !m.getName().isEmpty()) {
                                        mains.add(m);
                                    }
                                }
                            }
                        }
                        renderMains(keepName);
                        if (!ok) {
                            loadFailView.show("分类加载失败，请检查网络后重试");
                        } else {
                            loadFailView.hide();
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<CategoryTree>> c, Throwable t) {
                        loading = false;
                        loaded = true;
                        lastLoadedAt = System.currentTimeMillis();
                        mains.clear();
                        renderMains("");
                        loadFailView.show("分类加载失败，请检查网络后重试");
                    }
                });
    }

    /* ================= 渲染 ================= */

    private void renderMains(String keepName) {
        // ★ 请求已经回来（成功、失败、空列表都必经这里）→ 先把加载动画收掉。
        //   踩过的坑：之前只在 showEmpty() 里收，于是「加载成功、有分类」这条路径没人收，
        //   动画就一直转（只在网络被挂住时验证过，没覆盖成功路径）。
        setLoading(false);
        layoutMain.removeAllViews();
        mainRows.clear();

        if (mains.isEmpty()) {
            selectedIndex = -1;
            subs.clear();
            subAdapter.notifyDataSetChanged();
            showEmpty("暂无分类");
            return;
        }

        for (int i = 0; i < mains.size(); i++) {
            View row = buildMainRow(mains.get(i).getName(), i);
            layoutMain.addView(row);
        }

        int target = 0;
        if (!keepName.isEmpty()) {
            for (int i = 0; i < mains.size(); i++) {
                if (keepName.equals(mains.get(i).getName())) {
                    target = i;
                    break;
                }
            }
        }
        selectMain(target);
    }

    /** 左侧单个主分类条目：左侧指示条 + 居中分类名 */
    private View buildMainRow(String name, final int index) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(MAIN_ITEM_HEIGHT_DP)));
        row.setClickable(true);
        row.setFocusable(true);

        View bar = new View(requireContext());
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                dp(INDICATOR_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT));
        row.addView(bar);

        TextView tv = new TextView(requireContext());
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(15);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setText(name);
        row.addView(tv);

        row.setOnClickListener(v -> selectMain(index));
        mainRows.add(new MainRow(row, bar, tv));
        return row;
    }

    /** 选中某个主分类：刷新左侧选中态 + 右侧子分类 */
    private void selectMain(int index) {
        if (index < 0 || index >= mains.size()) return;
        selectedIndex = index;

        for (int i = 0; i < mainRows.size(); i++) {
            applyMainRowStyle(mainRows.get(i), i == index);
        }

        subs.clear();
        subs.addAll(mains.get(index).getSubs());
        subAdapter.notifyDataSetChanged();
        if (subs.isEmpty()) {
            showEmpty("「" + mains.get(index).getName() + "」暂无子分类");
        } else {
            tvSubEmpty.setVisibility(View.GONE);
        }
    }

    /** 选中：白底 + 蓝色指示条 + 蓝色粗体；未选中：透明 + 主文字色 */
    private void applyMainRowStyle(MainRow row, boolean selected) {
        if (selected) {
            row.itemView.setBackgroundColor(resolve(R.attr.appSurface));
            row.bar.setBackgroundColor(resolve(R.attr.appAccent));
            row.name.setTextColor(resolve(R.attr.appAccent));
            row.name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else {
            row.itemView.setBackgroundColor(0x00000000);
            row.bar.setBackgroundColor(0x00000000);
            row.name.setTextColor(resolve(R.attr.appTextPrimary));
            row.name.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        }
    }

    /** 空态 / 失败提示（非加载态）：显示文字的同时收起加载动画。 */
    private void showEmpty(String text) {
        setLoading(false);
        tvSubEmpty.setText(text);
        tvSubEmpty.setVisibility(View.VISIBLE);
    }

    /** 首次拉取分类树期间显示加载动画（已有数据时的静默刷新不打扰）。 */
    private void setLoading(boolean loading) {
        if (loadingView != null) loadingView.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading && tvSubEmpty != null) tvSubEmpty.setVisibility(View.GONE);
    }

    /* ================= 子分类网格 ================= */

    private class SubAdapter extends RecyclerView.Adapter<SubAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_category_sub, parent, false);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final String name = subs.get(position);
            holder.tv.setText(name);
            holder.tv.setOnClickListener(v -> openCategoryBooks(name));
        }

        @Override
        public int getItemCount() {
            return subs.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView tv;

            Holder(TextView v) {
                super(v);
                tv = v;
            }
        }
    }

    /** 打开某个子分类下的书籍列表 */
    private void openCategoryBooks(String subName) {
        if (subName == null || subName.isEmpty()) return;
        String mainName = (selectedIndex >= 0 && selectedIndex < mains.size())
                ? mains.get(selectedIndex).getName() : "";
        Intent i = new Intent(getActivity(), CategoryBooksActivity.class);
        i.putExtra(CategoryBooksActivity.EXTRA_MAIN_CATEGORY, mainName);
        i.putExtra(CategoryBooksActivity.EXTRA_SUB_CATEGORY, subName);
        startActivity(i);
    }

    /* ================= 工具 / 生命周期 ================= */

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 取当前配色风格下的主题色：颜色不再只有唯一 @color 名字，需按 ?attr 解析。 */
    private int resolve(int attr) {
        return ThemeAttrs.color(requireContext(), attr, 0);
    }

    /** 左侧主分类一行的视图引用 */
    private static class MainRow {
        final View itemView;
        final View bar;
        final TextView name;

        MainRow(View itemView, View bar, TextView name) {
            this.itemView = itemView;
            this.bar = bar;
            this.name = name;
        }
    }

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
        // 从分类书单页返回 / 首次进入：数据过期时刷新
        if (layoutMain != null) refreshIfStale();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            updateStatusBarColor();
            if (layoutMain != null) refreshIfStale();
        }
    }
}
