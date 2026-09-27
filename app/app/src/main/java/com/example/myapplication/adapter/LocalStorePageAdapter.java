package com.example.myapplication.adapter;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.PageResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 「本站藏书」顶部主分类子页面适配器（供 {@link androidx.viewpager2.widget.ViewPager2} 使用）。
 *
 * <p>页序：前端固定加的「新书」页 + 后端的每个主分类（男生 / 女生 / 名著 …，后台新增会自动多出一页）。
 * 每页内部自带二级分类筛选（该主分类下的 subs）与书单，可独立下拉刷新；
 * 切换 Tab 与左右滑动由外层 ViewPager2 + TabLayoutMediator 负责，各页的子分类选择互不干扰。
 *
 * <p>「新书」页不带主分类筛选，按导入时间（createdAt）倒序；其余页按热度（viewCount → likeCount）倒序。
 */
public class LocalStorePageAdapter extends RecyclerView.Adapter<LocalStorePageAdapter.PageHolder> {

    /** 一个子页面的描述。 */
    public static class PageSpec {
        public final String title;
        /** 主分类名；「新书」页为 null（不带分类筛选，按导入时间倒序） */
        public final String mainCategory;
        /** 该主分类下的子分类名，用作页内二级筛选 chips */
        public final List<String> subs;

        public PageSpec(String title, String mainCategory, List<String> subs) {
            this.title = title;
            this.mainCategory = mainCategory;
            this.subs = subs == null ? new ArrayList<>() : subs;
        }

        public boolean isNewPage() {
            return mainCategory == null || mainCategory.isEmpty();
        }
    }

    public interface OnBookClickListener {
        void onBookClick(Book book);
    }

    private final List<PageSpec> pages = new ArrayList<>();
    /** 每页当前选中的子分类（与 pages 同下标）—— 来回切页后仍保留各自的选择 */
    private final List<String> subSelection = new ArrayList<>();
    /** 被显式要求强制刷新的页下标 */
    private final Set<Integer> forceReload = new HashSet<>();
    private OnBookClickListener listener;
    /** ViewPager2 内部承载各子页的 RecyclerView，用于按 adapterPosition 取当前页 ViewHolder 滚到顶部 */
    private RecyclerView attachedRecyclerView;

    public void setOnBookClickListener(OnBookClickListener l) {
        this.listener = l;
    }

    public List<PageSpec> getPages() {
        return pages;
    }

    public String getPageTitle(int position) {
        if (position < 0 || position >= pages.size()) return "";
        return pages.get(position).title;
    }

    /** 整批替换页列表（分类树刷新后调用），子分类选择按页标题对齐保留。 */
    public void setPages(List<PageSpec> list) {
        List<String> oldTitles = new ArrayList<>();
        for (PageSpec p : pages) oldTitles.add(p.title);
        List<String> oldSubs = new ArrayList<>(subSelection);

        pages.clear();
        if (list != null) pages.addAll(list);

        subSelection.clear();
        for (PageSpec p : pages) {
            int idx = oldTitles.indexOf(p.title);
            subSelection.add(idx >= 0 && idx < oldSubs.size() ? oldSubs.get(idx) : "");
        }
        notifyDataSetChanged();
    }

    /** 请求某一页重新加载（下拉刷新之外的强制刷新入口）。 */
    public void requestReload(int position) {
        if (position < 0 || position >= pages.size()) return;
        forceReload.add(position);
        notifyItemChanged(position);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        if (attachedRecyclerView == recyclerView) attachedRecyclerView = null;
        super.onDetachedFromRecyclerView(recyclerView);
    }

    /** 将指定主分类子页面（adapterPosition）的书单滚回顶部，供书城 tab 双击调用。 */
    public void scrollToTop(int position) {
        if (attachedRecyclerView == null) return;
        RecyclerView.ViewHolder vh = attachedRecyclerView.findViewHolderForAdapterPosition(position);
        if (vh instanceof PageHolder) {
            ((PageHolder) vh).scrollToTop();
        }
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bookstore_local_page, parent, false);
        return new PageHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        holder.bind(pages.get(position), position, subSelection.get(position));
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    class PageHolder extends RecyclerView.ViewHolder {
        private final HorizontalScrollView hsvSub;
        private final LinearLayout layoutSub;
        private final SwipeRefreshLayout swipe;
        private final RecyclerView rv;
        private final com.example.myapplication.widget.LoadFailView loadFailPage;
        private final BookAdapter adapter;
        private final List<Book> data = new ArrayList<>();

        private PageSpec spec;
        private int boundPosition = -1;
        private String currentSub = "";
        private boolean loaded = false;
        private boolean loading = false;

        PageHolder(@NonNull View itemView) {
            super(itemView);
            hsvSub = itemView.findViewById(R.id.hsv_sub);
            layoutSub = itemView.findViewById(R.id.layout_sub);
            swipe = itemView.findViewById(R.id.swipe_page);
            rv = itemView.findViewById(R.id.rv_page);
            loadFailPage = itemView.findViewById(R.id.load_fail_page);

            rv.setLayoutManager(new LinearLayoutManager(itemView.getContext()));
            adapter = new BookAdapter(itemView.getContext(), data);
            rv.setAdapter(adapter);
            adapter.setOnItemClickListener(book -> {
                if (listener != null) listener.onBookClick(book);
            });

            swipe.setOnRefreshListener(() -> load(true));

            // 本页书单加载失败 → 点「再试一次」重拉
            loadFailPage.setOnRetryClick(v -> load(true));
        }

        void bind(PageSpec newSpec, int position, String sub) {
            boolean slotChanged = (this.spec != newSpec) || (this.boundPosition != position);
            this.spec = newSpec;
            this.boundPosition = position;
            this.currentSub = sub == null ? "" : sub;

            renderSubChips();

            boolean forced = forceReload.remove(position);
            if (slotChanged || forced) loaded = false;

            if (!loaded) {
                load(forced);
            } else {
                adapter.notifyDataSetChanged();
            }
        }

        void forceReload() {
            load(true);
        }

        /** 将本页书单滚动回顶部（供书城 tab 双击调用）。 */
        void scrollToTop() {
            rv.scrollToPosition(0);
        }

        /** 渲染二级分类 chips：「全部」+ 该主分类的 subs；「新书」页或无子分类时整行隐藏。 */
        private void renderSubChips() {
            layoutSub.removeAllViews();
            if (spec == null || spec.isNewPage() || spec.subs.isEmpty()) {
                hsvSub.setVisibility(View.GONE);
                return;
            }
            hsvSub.setVisibility(View.VISIBLE);

            List<String> chips = new ArrayList<>();
            chips.add("全部");
            chips.addAll(spec.subs);

            for (int i = 0; i < chips.size(); i++) {
                final int idx = i;
                final String name = chips.get(i);
                boolean selected = (i == 0 && currentSub.isEmpty()) || name.equals(currentSub);

                TextView tv = new TextView(itemView.getContext());
                tv.setText(name);
                tv.setTextSize(13);
                tv.setPadding(18, 8, 18, 8);
                if (selected) {
                    tv.setTextColor(Color.parseColor("#007AFF")); // iOS 蓝
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(999);
                    bg.setColor(Color.parseColor("#E8E8ED")); // 选中：浅灰底
                    tv.setBackground(bg);
                } else {
                    tv.setTextColor(Color.parseColor("#8E8E93")); // iOS 次文字
                    tv.setBackground(null);
                }

                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) p.leftMargin = 12;
                tv.setLayoutParams(p);

                tv.setOnClickListener(v -> {
                    String newSub = (idx == 0) ? "" : name;
                    if (newSub.equals(currentSub)) return;
                    currentSub = newSub;
                    if (boundPosition >= 0 && boundPosition < subSelection.size()) {
                        subSelection.set(boundPosition, currentSub);
                    }
                    renderSubChips();
                    load(true);
                });

                layoutSub.addView(tv);
            }
        }

        private void load(boolean force) {
            if (spec == null || loading) return;
            if (loaded && !force) return;

            loading = true;
            swipe.setRefreshing(true);
            loadFailPage.hide();   // 重试/刷新开始 → 本页失败层退场

            String sub = (currentSub == null || currentSub.isEmpty()) ? null : currentSub;
            String sort = spec.isNewPage() ? "createdAt" : "hot";

            RetrofitClient.getApiService()
                    .getFictionBooks(0, 50, null, spec.mainCategory, sub, sort)
                    .enqueue(new Callback<ApiResponse<PageResponse<Book>>>() {
                        @Override
                        public void onResponse(@NonNull Call<ApiResponse<PageResponse<Book>>> c,
                                               @NonNull Response<ApiResponse<PageResponse<Book>>> r) {
                            loading = false;
                            swipe.setRefreshing(false);
                            data.clear();
                            if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                    && r.body().getData() != null
                                    && r.body().getData().getContent() != null) {
                                data.addAll(r.body().getData().getContent());
                                loaded = true;
                                loadFailPage.hide();
                            } else {
                                // 服务端返回异常：同样按失败处理
                                loadFailPage.show("加载出错，请稍后再试");
                            }
                            adapter.notifyDataSetChanged();
                        }

                        @Override
                        public void onFailure(@NonNull Call<ApiResponse<PageResponse<Book>>> c,
                                              @NonNull Throwable t) {
                            loading = false;
                            swipe.setRefreshing(false);
                            data.clear();
                            adapter.notifyDataSetChanged();
                            loadFailPage.show("网络异常，请检查网络后重试");
                        }
                    });
        }
    }
}
