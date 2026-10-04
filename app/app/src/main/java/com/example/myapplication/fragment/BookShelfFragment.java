package com.example.myapplication.fragment;

import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.myapplication.R;
import com.example.myapplication.activity.BaseActivity;
import com.example.myapplication.activity.BookDetailActivity;
import com.example.myapplication.activity.MainActivity;
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.activity.ReaderWebViewPool;
import com.example.myapplication.activity.UploadBookActivity;
import com.example.myapplication.adapter.BookAdapter;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.Bookshelf;
import com.example.myapplication.bean.ReadingProgress;
import com.example.myapplication.utils.ThemeAttrs;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.ExternalPrefs;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.Hint;
import com.example.myapplication.utils.ReadTimeText;
import com.example.myapplication.utils.ShelfPinStore;
import com.example.myapplication.utils.ShelfPrefetch;
import com.example.myapplication.utils.SystemBarInsets;
import com.example.myapplication.widget.LoadingView;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@SuppressLint({"UnknownNullness", "NewApi"})
public class BookShelfFragment extends Fragment {

    private FastScrollRecyclerView rvShelf;
    private SwipeRefreshLayout swipeRefresh;
    private BookAdapter adapter;
    private final List<Book> shelfBookList = new ArrayList<>();
    private TextView tvReadTime;
    /** 空状态容器（人物插画 + 文案 + CTA），替代原先的纯文字提示 */
    private View layoutEmpty;
    /** 全站统一加载动画（首屏向服务器请求书架期间显示） */
    private LoadingView loadingView;
    private android.widget.PopupWindow shelfMenuPopup;

    private android.app.Dialog topDialog;
    private android.app.Dialog bottomDialog;
    /** 编辑态系统返回键拦截：编辑中按返回=退出编辑（不离开书架页），非编辑态不启用 */
    private OnBackPressedCallback editBackCallback;
    // 编辑态顶栏控件引用：计数随勾选实时刷新、全选文案随选中数切换
    private android.widget.TextView tvSelectedCount;
    private android.widget.TextView tvSelectAllRef;
    private boolean isGridView = false;
    /** 统一 GridLayoutManager：列表 span=1 / 宫格 span=3，切换时只改 spanCount（不销毁重建节点） */
    private GridLayoutManager shelfLM;

    // ========== 列表⇄宫格形变切换（FLIP + 弧线 + 错帧） ==========
    private boolean morphAnimating = false;
    private final List<ValueAnimator> morphAnimators = new ArrayList<>();
    private static final long MORPH_DURATION_MS = 520L;
    private static final long MORPH_STAGGER_MS = 50L;   // 相邻项错开 3 帧（60fps ≈ 16.7ms/帧）
    private RecyclerView.OnScrollListener morphCancelOnScroll;

    private final Map<Long, Integer> progressMap = new HashMap<>();
    private final Map<Long, String> chapterTitleMap = new HashMap<>();

    // 网络书籍缓存（用于后台刷新时不丢失显示）
    private static final String PREF_SHELF_CACHE = "shelf_cache";
    private static final String KEY_CACHE_COUNT = "cache_count";
    private boolean firstLoadDone = false;

    // ========== 分组 ==========
    private static final String PREF_SHELF_GROUPS = "shelf_groups";
    private static final String KEY_CUSTOM_GROUPS = "custom_groups"; // 逗号分隔的自定义分组名
    private static final String PREF_GROUP_ASSIGN = "shelf_group_assign"; // bookKey -> groupName
    private static final String GROUP_ALL = "全部";
    private static final String GROUP_LOCAL = "本地";
    private static final String GROUP_UNGROUPED = "未分组";
    private static final String GROUP_EXTERNAL = "外站";
    private final java.util.List<String> customGroups = new ArrayList<>();
    private String currentGroup = GROUP_ALL;
    private final java.util.List<Book> allBooks = new ArrayList<>();   // 全量(本地+网络+外站)
    private android.widget.LinearLayout layoutGroupTabs;

    // 分组 Tab 滑动指示器
    private View segThumbShelf;
    private ValueAnimator thumbAnim, chipColorAnim;
    /** 切换分段前各 chip 的文字颜色（作为颜色渐变的真实起点，防止闪现蓝色） */
    private java.util.Map<TextView, Integer> pendingChipStartColors;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_shelf, container, false);

        applySystemBars(view);

        rvShelf = view.findViewById(R.id.rv_shelf);
        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        tvReadTime = view.findViewById(R.id.tv_read_time);
        layoutEmpty = view.findViewById(R.id.layout_empty);
        loadingView = view.findViewById(R.id.loading_view);

        // 空状态 CTA：切到底部导航的「书城」tab
        View btnGoStore = view.findViewById(R.id.btn_go_store);
        if (btnGoStore != null) {
            btnGoStore.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).switchToTab(R.id.nav_bookstore);
                }
            });
        }
        layoutGroupTabs = view.findViewById(R.id.layout_group_tabs);
        segThumbShelf = view.findViewById(R.id.seg_thumb_shelf);
        if (segThumbShelf != null) {
            // 白块 + 淡投影（对齐效果稿），圆角 9dp
            segThumbShelf.setBackgroundResource(R.drawable.bg_shelf_seg_thumb);
        }
        loadCustomGroups();
        buildGroupTabs();

        // 后台清扫孤儿 HTML 缓存目录（local_book_html/<bookId> 中 bookId 已不在 local_books）
        // 兜住"重导入同一本书但未先删除旧书"产生的残留；失败不影响主流程
        new Thread(() -> {
            try {
                if (getActivity() != null) {
                    LocalBookParser.cleanupOrphanHtmlCache(getActivity().getApplicationContext());
                }
            } catch (Throwable ignored) {}
        }, "html-cache-sweep").start();

        swipeRefresh.setColorSchemeColors(ThemeAttrs.color(requireActivity(), R.attr.appAccent, 0));

        // 编辑态返回键拦截：进入编辑态时启用，按返回退出编辑而不是退出页面
        editBackCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                hideEditMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), editBackCallback);

        // 下拉刷新监听
        swipeRefresh.setOnRefreshListener(() -> {
            loadBookshelf();
            loadReadTime();
        });

        adapter = new BookAdapter(getActivity(), shelfBookList, progressMap, chapterTitleMap);
        rvShelf.setAdapter(adapter);

        // 从 SharedPreferences 恢复布局模式（默认列表）
        // 统一用 GridLayoutManager：列表=span1 / 宫格=span3，切换只改 spanCount，
        // 同一批 ViewHolder 复用（不销毁重建、不重新绑定、Glide 不重载）
        SharedPreferences spPref = getActivity().getSharedPreferences("shelf_pref", Context.MODE_PRIVATE);
        isGridView = spPref.getBoolean("is_grid_mode", false);
        shelfLM = new GridLayoutManager(getActivity(), isGridView ? 3 : 1);
        rvShelf.setLayoutManager(shelfLM);
        // 形变动画期间禁用默认 ItemAnimator（避免移动动画与手动 FLIP 叠加）
        rvShelf.setItemAnimator(null);
        // 形变飞行中允许越出 item 边界（与效果稿一致的重叠观感）
        rvShelf.setClipChildren(false);
        // 形变过程中用户滚动 → 取消动画并复位
        morphCancelOnScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (morphAnimating) cancelMorph();
            }
        };
        rvShelf.addOnScrollListener(morphCancelOnScroll);
        adapter.setGridMode(isGridView);

        adapter.setOnItemClickListener(book -> {
            try {
                if (book == null) return;
                Intent intent = new Intent(getActivity(), ReadActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra("book", book);

                // 外站书籍（status==-2）：直接进入在线阅读模式，不再先跳详情页
                Integer stObj = book.getStatus();
                int st = stObj == null ? 0 : stObj;
                if (st == -2) {
                    openExternalBook(book);
                    return;
                }

                // 本地 / 服务器书籍：原有逻辑 + Long/Integer 自动拆箱 NPE 兜底
                Long idObj = book.getId();
                long bookId = idObj == null ? 0L : idObj;
                // 进书前后台预热章节缓存 SP：SharedPreferences 首次访问会在「当前线程」
                //    同步整文件读 + XML 解析，章节多的书正文缓存（chapter_content_<bookId>）
                //    可达数 MB，冷启动后首次点书曾在主线程卡住数秒，表现为
                //    「点击书籍无反应，过一会才进阅读器」。SP 是进程级单例，
                //    在后台线程触发加载后，主线程再访问即命中内存缓存。
                if (st != -1) {
                    prewarmReaderCaches(bookId);
                }
                if (progressMap.containsKey(bookId)) {
                    intent.putExtra("chapterIndex", progressMap.get(bookId));
                }
                if (st == -1) {
                    intent.putExtra("isLocal", true);
                }
                startActivity(intent);
            } catch (Throwable t) {
                android.util.Log.e("BookShelf", "书架点击书籍失败", t);
                try { Hint.show(getContext(), "打开失败：" + t.getMessage()); } catch (Throwable ignored) {}
            }
        });

        adapter.setOnLongPressListener(pos -> {
            showEditMode();
            adapter.toggleSelect(pos);
        });

        ImageView ivMore = view.findViewById(R.id.iv_more);
        ivMore.setOnClickListener(this::showShelfMenu);

        loadReadTime();
        return view;
    }

    /**
     * 后台预热阅读器章节缓存 SP（chapter_list / chapter_meta / chapter_content_<bookId>）。
     * 只触发加载即可（任意一次 get* 调用会阻塞该后台线程直至整文件加载完成），
     * 之后 ReadActivity.onCreate 在主线程访问同名字 SP 时直接命中进程级单例的内存缓存。
     */
    private void prewarmReaderCaches(long bookId) {
        if (getActivity() == null || bookId <= 0) return;
        final android.content.Context appCtx = getActivity().getApplicationContext();
        final long bid = bookId;
        new Thread(() -> {
            try {
                appCtx.getSharedPreferences("chapter_list_" + bid, Context.MODE_PRIVATE).getInt("count", 0);
                appCtx.getSharedPreferences("chapter_meta_" + bid, Context.MODE_PRIVATE).getInt("count", 0);
                appCtx.getSharedPreferences("chapter_content_" + bid,
                        Context.MODE_PRIVATE).getString("content_0", null);
            } catch (Throwable ignored) {}
        }, "reader-sp-prewarm").start();
    }

    /**
     * 外站书籍：立即进入阅读器。章节列表由阅读器内部异步加载
     * （内存缓存 → 持久化缓存 → 书城同款 fetchOnlineChapters 网络拉取），
     * 加载期间阅读器显示整页「章节加载中」动画，加载完成自动进入正文。
     * 此前版本在书架页后台等缓存就绪再跳转，会先弹「加载章节列表...」并停顿一会才进入，体验割裂。
     */
    private void openExternalBook(Book book) {
        if (getActivity() == null) return;
        launchExternalRead(book, null);
    }

    /** 带章节列表进入阅读器（小书走 Intent，大书走共享缓存，与书城一致）；chapters 为 null 时由阅读器自行加载。 */
    private void launchExternalRead(Book book, List<String[]> chapters) {
        Intent intent = new Intent(getActivity(), ReadActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("book", book);
        intent.putExtra("isExternal", true);
        if (chapters != null && !chapters.isEmpty()) {
            BookDetailActivity.putChaptersExtra(getActivity(), intent, book, chapters);
        }
        startActivity(intent);
    }

    /**
     * 系统栏出血：页底铺到屏幕顶（paddingTop 叠加状态栏 inset，背景不被 padding 裁剪），
     * 状态栏图标深浅按页底实际亮度判定。切页时顶部颜色随页面一起滑动，不再有独立色带。
     */
    private void applySystemBars(View view) {
        if (getActivity() == null || view == null) return;
        SystemBarInsets.bleedTop(view);
        int pageBg = ThemeAttrs.color(requireActivity(), R.attr.appPageBg, 0);
        ((BaseActivity) getActivity()).applyBarIcons(pageBg, pageBg);
    }

    /** 外站数据拉取完成后刷新书架（登录后 pullAll 是异步的，此前的加载会读不到数据）。 */
    private final ExternalSyncManager.PullListener pullListener = () -> {
        if (isAdded()) loadBookshelf();
    };

    @Override
    public void onResume() {
        super.onResume();
        // 预热阅读器 WebView（复用池）：在书架页就把 reader.html 异步加载好，
        //    这样进书时通常已命中缓存，跳过「重建 WebView + 重载 reader.html」的冷启动。
        if (getContext() != null) {
            ReaderWebViewPool.preload(getContext().getApplicationContext());
        }
        applySystemBars(getView());
        loadBookshelf();
        loadReadTime();
        if (getContext() != null) {
            ExternalSyncManager.getInstance(getContext()).addPullListener(pullListener);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getContext() != null) {
            ExternalSyncManager.getInstance(getContext()).removePullListener(pullListener);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            applySystemBars(getView());
        }
    }

    private void showEditMode() {
        adapter.setEditMode(true);
        if (editBackCallback != null) editBackCallback.setEnabled(true); // 返回键=退出编辑
        // 选中数变化统一走监听器：刷新「已选择 N 本」计数 + 「全选/取消全选」文案
        adapter.setOnEditStateListener(n -> {
            updateSelectedCountText(n);
            syncSelectAllText(n);
        });
        showEditTopDialog();
        showEditBottomDialog();
    }

    private void updateSelectedCountText(int n) {
        if (tvSelectedCount != null) tvSelectedCount.setText("已选择 " + n + " 本");
    }

    private void syncSelectAllText(int n) {
        if (tvSelectAllRef != null) {
            tvSelectAllRef.setText(n > 0 && n >= shelfBookList.size() ? "取消全选" : "全选");
        }
    }

    @SuppressLint("InflateParams")
    private void showEditTopDialog() {
        View topView = LayoutInflater.from(getActivity()).inflate(R.layout.popup_edit_top, null);
        topView.findViewById(R.id.tv_done).setOnClickListener(v -> hideEditMode());

        tvSelectedCount = topView.findViewById(R.id.tv_selected_count);
        TextView tvSelectAll = topView.findViewById(R.id.tv_select_all);
        tvSelectAllRef = tvSelectAll;
        tvSelectAll.setText("全选");
        tvSelectAll.setOnClickListener(v -> {
            if (adapter.getSelectedCount() == shelfBookList.size()) {
                adapter.clearSelection();
            } else {
                adapter.selectAll();
            }
            // 文案由 setOnEditStateListener 回调统一刷新，不再在此处手写
        });
        updateSelectedCountText(adapter.getSelectedCount());

        topDialog = new android.app.Dialog(requireActivity(), R.style.TopDialogStyle);
        topDialog.setContentView(topView);
        // cancelable=true：编辑栏是独立窗口，系统返回键进的是 Dialog 窗口（到不了 Activity 的
        // 返回分发器），只能靠 Dialog 自身的取消机制响应返回。取消（返回键）统一走 hideEditMode；
        // dismiss() 不触发 onCancel，退出编辑时不会递归。触摸外部仍不可关。
        topDialog.setCancelable(true);
        topDialog.setCanceledOnTouchOutside(false);
        topDialog.setOnCancelListener(d -> hideEditMode());
        if (topDialog.getWindow() != null) {
            topDialog.getWindow().setGravity(Gravity.TOP);
            topDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            topDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            topDialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        topDialog.show();
    }

    @SuppressLint("InflateParams")
    private void showEditBottomDialog() {
        View bottomView = LayoutInflater.from(getActivity()).inflate(R.layout.popup_edit_bottom, null);
        bottomView.findViewById(R.id.btn_edit_delete).setOnClickListener(v -> deleteSelectedBooks());
        bottomView.findViewById(R.id.btn_edit_move_group).setOnClickListener(v -> showMoveGroupPicker());
        bottomView.findViewById(R.id.btn_edit_pin).setOnClickListener(v -> togglePinSelectedBooks());

        // ③ 底栏加高：按钮行(64dp) + 白色垫高区，总高 ≥ 导航卡顶(含 8dp 阴影)到窗口底的距离，
        // 完整盖住底部导航——窗口保持 FLAG_NOT_TOUCH_MODAL（列表仍可点选），
        // 但导航区域已被底栏窗口覆盖 → 点击落在底栏上，导航不可点，杜绝编辑中误切页。
        View filler = bottomView.findViewById(R.id.v_edit_bottom_filler);
        if (filler != null && getActivity() != null) {
            float d = getResources().getDisplayMetrics().density;
            int rowH = Math.round(64 * d);
            View navCard = getActivity().findViewById(R.id.nav_card);
            View rootLayout = getActivity().findViewById(R.id.root_layout);
            if (navCard != null && rootLayout != null && navCard.getHeight() > 0) {
                int[] nl = new int[2];
                navCard.getLocationOnScreen(nl);
                int[] rl = new int[2];
                rootLayout.getLocationOnScreen(rl);
                int winBottom = rl[1] + rootLayout.getHeight();
                int navTop = nl[1] - Math.round(8 * d); // 再抬 8dp 盖住导航卡阴影
                int coverH = Math.max(0, winBottom - navTop);
                filler.getLayoutParams().height = Math.max(0, coverH - rowH);
            } else {
                // 导航卡未布局兜底：按 60dp 行高 + 11dp 底边距 + 8dp 阴影估算
                filler.getLayoutParams().height = Math.round(79 * d);
            }
            filler.setLayoutParams(filler.getLayoutParams());
        }

        bottomDialog = new android.app.Dialog(requireActivity(), R.style.BottomDialogStyle);
        bottomDialog.setContentView(bottomView);
        bottomDialog.setCancelable(true); // 返回键可关（见 topDialog 处注释）
        bottomDialog.setCanceledOnTouchOutside(false);
        bottomDialog.setOnCancelListener(d -> hideEditMode());
        if (bottomDialog.getWindow() != null) {
            bottomDialog.getWindow().setGravity(Gravity.BOTTOM);
            bottomDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            bottomDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            bottomDialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        bottomDialog.show();
    }

    /** 底栏「置顶」：全部已选已置顶 → 取消置顶；否则置顶。置顶后按「置顶优先」重排并刷新 */
    private void togglePinSelectedBooks() {
        List<Book> selected = adapter.getSelectedBooks();
        if (selected.isEmpty()) { Hint.show(getActivity(), "请选择要置顶的书籍"); return; }
        long userId = getUserId();
        boolean allPinned = true;
        for (Book b : selected) {
            if (!ShelfPinStore.isPinned(getActivity(), userId, ShelfPinStore.keyOf(b))) { allPinned = false; break; }
        }
        if (allPinned) {
            for (Book b : selected) ShelfPinStore.unpin(getActivity(), userId, ShelfPinStore.keyOf(b));
            Hint.show(getActivity(), "已取消置顶 " + selected.size() + " 本");
        } else {
            for (Book b : selected) ShelfPinStore.pin(getActivity(), userId, ShelfPinStore.keyOf(b));
            Hint.show(getActivity(), "已置顶 " + selected.size() + " 本");
        }
        adapter.clearSelection(); // 触发监听器把计数归零
        applySortAndNotify();     // 置顶优先重排 + 刷新 UI
    }

    private void hideEditMode() {
        adapter.setEditMode(false);
        if (editBackCallback != null) editBackCallback.setEnabled(false); // 恢复默认返回行为
        if (topDialog != null && topDialog.isShowing()) topDialog.dismiss();
        if (bottomDialog != null && bottomDialog.isShowing()) bottomDialog.dismiss();
        tvSelectedCount = null;
        tvSelectAllRef = null;
    }

    private void deleteSelectedBooks() {
        List<Book> selected = adapter.getSelectedBooks();
        if (selected.isEmpty()) { Hint.show(getActivity(), "请选择要删除的书籍"); return; }
        long userId = getUserId();
        for (Book book : selected) {
            if (book.getStatus() == -2) {
                // 外站书籍：从本地 external_bookshelf 移除
                removeExternalBook(book);
            } else if (userId > 0 && book.getStatus() != -1) {
                RetrofitClient.getApiService().removeFromBookshelf(userId, book.getId())
                        .enqueue(new Callback<ApiResponse<Void>>() {
                            @Override public void onResponse(@NonNull Call<ApiResponse<Void>> call,
                                    @NonNull Response<ApiResponse<Void>> response) {}
                            @Override public void onFailure(@NonNull Call<ApiResponse<Void>> call,
                                    @NonNull Throwable t) {}
                        });
            }
            if (book.getStatus() == -1) {
                removeLocalBook(book.getId());
            }
            allBooks.remove(book);
            shelfBookList.remove(book);
        }
        // 同步清理被删书籍的置顶键，避免残留（书重新加入书架也不应继承旧置顶）
        for (Book book : selected) {
            ShelfPinStore.unpin(getActivity(), userId, ShelfPinStore.keyOf(book));
        }
        cancelMorph();
        adapter.refreshList(shelfBookList);
        updateEmptyView(shelfBookList.isEmpty());
        hideEditMode();
        buildGroupTabs();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelMorph();
        if (shelfMenuPopup != null && shelfMenuPopup.isShowing()) shelfMenuPopup.dismiss();
        // 编辑栏是两扇独立窗口，宿主视图没了必须一起收掉，否则换肤/日夜重建时漏窗口（WindowLeaked）。
        // 编辑态本来就不跨重建（没有写进 onSaveInstanceState），这里只管关窗、不必走 hideEditMode。
        if (topDialog != null && topDialog.isShowing()) topDialog.dismiss();
        if (bottomDialog != null && bottomDialog.isShowing()) bottomDialog.dismiss();
    }

    // ==================================================================
    // 列表⇄宫格形变切换：FLIP 矩形记录 → 位移/缩放反向补偿 → 二次贝塞尔弧线
    // 缓动 → 相邻项错开 3 帧 → 封面 Matrix 等比裁切。全程同一批 ViewHolder，
    // 不重绑、不重新加载图片（对齐效果稿 preview_shelf_morph.html）。
    // ==================================================================
    private void switchShelfLayoutAnimated() {
        if (!isAdded() || rvShelf == null || adapter == null || shelfLM == null) return;
        cancelMorph();

        // 1) 记录每项切换前矩形（卡片 + 封面，相对 RecyclerView）
        Map<Integer, RectF> oldCards = new HashMap<>();
        Map<Integer, RectF> oldCovers = new HashMap<>();
        for (int i = 0; i < rvShelf.getChildCount(); i++) {
            View child = rvShelf.getChildAt(i);
            RecyclerView.ViewHolder vh = rvShelf.getChildViewHolder(child);
            if (!(vh instanceof BookAdapter.BookHolder)) continue;
            BookAdapter.BookHolder h = (BookAdapter.BookHolder) vh;
            int pos = rvShelf.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION) continue;
            oldCards.put(pos, rectInRv(h.cardBg));
            oldCovers.put(pos, rectInRv(h.coverBox));
        }

        // 2) 切模式：只改 spanCount + 就地布置可见 holder（同一批节点）
        isGridView = !isGridView;
        adapter.setGridModeSilently(isGridView);
        shelfLM.setSpanCount(isGridView ? 3 : 1);
        for (int i = 0; i < rvShelf.getChildCount(); i++) {
            View child = rvShelf.getChildAt(i);
            int pos = rvShelf.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION) continue;
            adapter.applyMode(rvShelf.getChildViewHolder(child), pos);
        }
        // 持久化布局模式
        if (isAdded()) {
            SharedPreferences.Editor editor = requireActivity()
                    .getSharedPreferences("shelf_pref", Context.MODE_PRIVATE).edit();
            editor.putBoolean("is_grid_mode", isGridView);
            editor.apply();
        }

        // 3) 新布局测量完成后：反向补偿 + 弧线动画（PreDraw 时机 = 布局后、绘制前，无闪帧）
        final Map<Integer, RectF> fOldCards = oldCards;
        final Map<Integer, RectF> fOldCovers = oldCovers;
        final ViewTreeObserver vto = rvShelf.getViewTreeObserver();
        if (vto == null || !vto.isAlive()) return;
        vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (rvShelf != null) {
                    ViewTreeObserver cur = rvShelf.getViewTreeObserver();
                    if (cur != null && cur.isAlive()) cur.removeOnPreDrawListener(this);
                }
                if (!isAdded() || rvShelf == null) return true;
                startMorph(fOldCards, fOldCovers);
                return true;
            }
        });
    }

    /** 子视图相对 RecyclerView 的矩形（祖父 = item root，均无变换残留下调用） */
    private RectF rectInRv(View v) {
        View p = (View) v.getParent();
        float x = v.getX() + p.getX();
        float y = v.getY() + p.getY();
        return new RectF(x, y, x + v.getWidth(), y + v.getHeight());
    }

    /** 单个形变项的预计算数据（主时钟动画器每帧驱动） */
    private static class MorphItem {
        View child;
        BookAdapter.BookHolder h;
        int index;
        boolean hasFrom;          // false = 新进入可视区（淡入路径）
        RectF r0, r1;             // 卡片旧/新矩形（RV 相对）
        float cx, cy;             // 贝塞尔控制点
        float sx0, sy0;           // 卡片初始缩放补偿
        float csx0, csy0;         // 封面初始缩放补偿
        float ctx0, cty0;         // 封面初始位移补偿
        View outBox, inBox;       // 旧/新文字盒
        boolean outIsList, inIsList;          // 旧/新文字盒是否为列表盒（决定内边距/几何）
        int outLeftPad, outRightPad;         // 旧文字盒左右内边距(px)
        int inLeftPad, inRightPad;           // 新文字盒左右内边距(px)
        int outTopMargin, inTopMargin;       // 旧/新文字盒 top margin(px)
        int outH, inH;                        // 旧/新文字盒名义高(px)
        int cardMarginTop;                    // 新模式下卡片 top margin(px)
        int cardMarginLeft;                   // 新模式下卡片 left margin(px)（宫格海报卡 10dp，根平移扣减用）
    }

    private final android.view.animation.Interpolator morphInterp = new AccelerateDecelerateInterpolator();

    private void startMorph(Map<Integer, RectF> oldCards, Map<Integer, RectF> oldCovers) {
        cancelMorph();
        morphAnimating = true;
        float d = getResources().getDisplayMetrics().density;
        final float inShiftY = 8 * d;

        List<MorphItem> items = new ArrayList<>();
        for (int i = 0; i < rvShelf.getChildCount(); i++) {
            View child = rvShelf.getChildAt(i);
            RecyclerView.ViewHolder vh = rvShelf.getChildViewHolder(child);
            if (!(vh instanceof BookAdapter.BookHolder)) continue;
            BookAdapter.BookHolder h = (BookAdapter.BookHolder) vh;
            int pos = rvShelf.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION) continue;

            MorphItem it = new MorphItem();
            it.child = child;
            it.h = h;
            it.index = items.size();
            it.r1 = rectInRv(h.cardBg);
            RectF c1 = rectInRv(h.coverBox);
            RectF r0 = oldCards.get(pos);
            RectF c0 = oldCovers.get(pos);
            it.outBox = isGridView ? h.listTextBox : h.gridTextBox;
            it.inBox = isGridView ? h.gridTextBox : h.listTextBox;
            it.hasFrom = r0 != null && c0 != null && it.r1.width() > 0 && it.r1.height() > 0;

            // 预计算文字盒内边距/几何（px），供 applyMorphState 每帧把宽度钳在卡片当前渲染宽内。
            // 列表盒：左内边距恒 dp100（编辑态不再 100→134，封面已不让位）、
            //         右内边距 dp14（编辑态再让 34dp 给右侧选择框）/top dp15/高 96；
            // 宫格盒（海报卡）：左右各 dp18/top dp146/高 34。
            it.outIsList = (it.outBox == h.listTextBox);
            it.inIsList = (it.inBox == h.listTextBox);
            boolean editAdj = adapter.isEditMode();
            int dp2b = Math.round(2 * d), dp4b = Math.round(4 * d), dp10b = Math.round(10 * d),
                dp14b = Math.round(14 * d), dp15b = Math.round(15 * d), dp18b = Math.round(18 * d),
                dp34b = Math.round(34 * d), dp96b = Math.round(96 * d), dp146b = Math.round(146 * d);
            int dp100b = Math.round(100f * d);
            it.outLeftPad = it.outIsList ? dp100b : dp18b;
            it.outRightPad = it.outIsList ? (editAdj ? dp14b + dp34b : dp14b) : dp18b;
            it.outTopMargin = it.outIsList ? dp15b : dp146b;
            it.outH = it.outIsList ? dp96b : dp34b;
            it.inLeftPad = it.inIsList ? dp100b : dp18b;
            it.inRightPad = it.inIsList ? (editAdj ? dp14b + dp34b : dp14b) : dp18b;
            it.inTopMargin = it.inIsList ? dp15b : dp146b;
            it.inH = it.inIsList ? dp96b : dp34b;
            it.cardMarginTop = isGridView ? dp4b : dp2b;
            it.cardMarginLeft = isGridView ? dp10b : 0;

            if (it.hasFrom) {
                it.r0 = r0;
                float dx = it.r1.left - r0.left, dy = it.r1.top - r0.top;
                float dist = (float) Math.max(1f, Math.sqrt(dx * dx + dy * dy));
                float sgn = (dx >= 0 ? 1f : -1f) * (it.index % 2 == 0 ? 1f : -1f);
                float k = 0.2f * dist * sgn;
                it.cx = (r0.left + it.r1.left) / 2f + (-dy / dist) * k;
                it.cy = (r0.top + it.r1.top) / 2f + (dx / dist) * k;
                it.sx0 = r0.width() / it.r1.width();
                it.sy0 = r0.height() / it.r1.height();
                it.csx0 = c1.width() > 0 ? c0.width() / c1.width() : 1f;
                it.csy0 = c1.height() > 0 ? c0.height() / c1.height() : 1f;
                // 封面位移补偿 = 「新布局偏移 → 旧偏移」的纯平移差（各自相对卡片矩形）。
                // 旧写法 cl0 - cs*cl1 混入了封面缩放系数：缩放只改尺寸、不动左上角锚点，
                // 位移补偿与缩放无关。旧宫格封面有大白边遮住误差；海报卡封面贴边后
                // 宫→列飞行中封面顶出卡面上缘、左移出卡外（2026-09-30 过程帧取证）。
                float cl0x = c0.left - r0.left, cl0y = c0.top - r0.top;
                float cl1x = c1.left - it.r1.left, cl1y = c1.top - it.r1.top;
                it.ctx0 = cl0x - cl1x;
                it.cty0 = cl0y - cl1y;
                // 同步施加初始状态（e=0），杜绝 PreDraw 帧与动画首帧之间「闪跳到新位置」
                applyMorphState(it, 0f, inShiftY);
            } else {
                // 新进入可视区：先补一次 applyMode（防复用/重绑残留旧模式几何），随错帧淡入
                adapter.applyMode(vh, pos);
                child.setAlpha(0f);
            }
            items.add(it);
        }
        if (items.isEmpty()) { morphAnimating = false; return; }

        final List<MorphItem> fItems = items;
        final float fInShiftY = inShiftY;
        final long total = MORPH_DURATION_MS + (long) (items.size() - 1) * MORPH_STAGGER_MS;

        // 主时钟动画器：单一 ValueAnimator 每帧驱动全部项。
        // 未起步/已起步的项每帧重申各自状态 —— 自愈动画期间任何 rebind/二次布局
        // 触发的 applyMode 变换复位（此前「封面已放大但卡片还是列表宽、文字全空」即此因：
        // 初始补偿态被 onAnimationEnd/重绑路径清掉，而该项的错帧还没走到首个更新帧）。
        ValueAnimator master = ValueAnimator.ofFloat(0f, 1f);
        master.setDuration(total);
        master.addUpdateListener(anim -> {
            long now = (long) ((Float) anim.getAnimatedValue() * total);
            for (MorphItem it : fItems) {
                float local = (now - it.index * (float) MORPH_STAGGER_MS) / MORPH_DURATION_MS;
                if (!it.hasFrom) {
                    // 淡入项：几何已是终态，只做 alpha（每帧重申，自愈复位）
                    float a = Math.max(0f, Math.min(1f, local / 0.4f));
                    it.child.setAlpha(a);
                    continue;
                }
                float e;
                if (local <= 0f) {
                    e = 0f;
                } else if (local >= 1f) {
                    e = 1f;
                } else {
                    e = morphInterp.getInterpolation(local);
                }
                applyMorphState(it, e, fInShiftY);
            }
        });
        master.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                morphAnimators.remove(master);
                if (morphAnimators.isEmpty()) {
                    morphAnimating = false;
                    // 全部结束：复位到静止态（清变换/透明度/可见性/封面 Matrix）
                    if (isAdded() && rvShelf != null && adapter != null) {
                        for (int j = 0; j < rvShelf.getChildCount(); j++) {
                            View ch = rvShelf.getChildAt(j);
                            RecyclerView.ViewHolder vh2 = rvShelf.getChildViewHolder(ch);
                            if (vh2 instanceof BookAdapter.BookHolder) {
                                adapter.applyMode(vh2, rvShelf.getChildAdapterPosition(ch));
                            } else {
                                ch.setAlpha(1f);
                                ch.setTranslationX(0f);
                                ch.setTranslationY(0f);
                            }
                        }
                    }
                }
            }
        });
        master.start();
        morphAnimators.add(master);
    }

    /** 施加某项在进度 e 下的渲染状态（e=0 = 旧位置旧尺寸，e=1 = 新布局终态） */
    private void applyMorphState(MorphItem it, float e, float inShiftY) {
        BookAdapter.BookHolder h = it.h;
        View child = it.child;
        // 根：贝塞尔弧线位移（顶角沿二次贝塞尔）。贝塞尔对位目标是「卡片矩形」，须再扣掉
        // 新模式卡片自身 margin，否则 list→宫格 收尾时整卡停在 margin 偏移上、结束瞬间回跳
        // （宫格左右 margin 10dp = 30px 跳变、上 margin 4dp = 12px，2026-09-30 海报卡后暴露）。
        float u = 1 - e;
        float bx = u * u * it.r0.left + 2 * u * e * it.cx + e * e * it.r1.left;
        float by = u * u * it.r0.top + 2 * u * e * it.cy + e * e * it.r1.top;
        float rtx = bx - child.getLeft() - it.cardMarginLeft;
        float rty = by - child.getTop() - it.cardMarginTop;
        child.setTranslationX(rtx);
        child.setTranslationY(rty);
        // 文字盒/多选框只随 child（item 根）平移即可，不再叠加 rtx/rty；
        // 此前多叠一份 rtx → 文字盒相对卡片被推出「卡片自身位移量」、随 rtx→0 归位，
        // 表现为「书籍信息从卡片外飞入」。去掉额外位移后，信息始终锁定在卡片内浮现。
        // 仅交叉淡化、不缩放 → 字形永不变形。可见性每帧重申（rebind 的 applyMode 会把旧盒设回 INVISIBLE）
        it.outBox.setVisibility(View.VISIBLE);
        it.inBox.setVisibility(View.VISIBLE);
        // 文字盒宽度逐帧跟随「卡片当前渲染宽」：左/右内边距按各自模式（编辑态列表右侧多让 34dp 给选择框），
        // 不再钉死在 rvW 全宽 → 列表→宫格卡片收窄时文字盒右缘恒等于卡右缘减内边距，从右向左被裁掉；
        // 下缘上吸保证文字盒底不越过卡片渲染下缘（终帧自动归 0，无跳变）。
        float cardW = it.r0.width() + (it.r1.width() - it.r0.width()) * e;
        float cardH = it.r0.height() + (it.r1.height() - it.r0.height()) * e;
        ViewGroup.MarginLayoutParams olp = (ViewGroup.MarginLayoutParams) it.outBox.getLayoutParams();
        olp.width = Math.max(6, Math.round(cardW - it.outLeftPad - it.outRightPad));
        it.outBox.setLayoutParams(olp);
        float outOver = (it.outTopMargin - it.cardMarginTop) + it.outH - cardH;
        it.outBox.setTranslationY(outOver > 0f ? -outOver : 0f);
        ViewGroup.MarginLayoutParams ilp = (ViewGroup.MarginLayoutParams) it.inBox.getLayoutParams();
        ilp.width = Math.max(6, Math.round(cardW - it.inLeftPad - it.inRightPad));
        it.inBox.setLayoutParams(ilp);
        float inOver = (it.inTopMargin - it.cardMarginTop) + it.inH - cardH;
        // 入场下滑量不得把文字底推出卡片底缘：先叠加再整体钳位（旧写法先钳位后加滑入量，
        // 幽灵文字悬在卡片下缘之外）
        float inDown = (1f - e) * inShiftY;
        float inOverAll = inOver + inDown;
        it.inBox.setTranslationY(inDown - Math.max(0f, inOverAll));
        // 卡片：缩放归一（pivot 左上角）
        h.cardBg.setPivotX(0f);
        h.cardBg.setPivotY(0f);
        h.cardBg.setScaleX(it.sx0 + (1 - it.sx0) * e);
        h.cardBg.setScaleY(it.sy0 + (1 - it.sy0) * e);
        // 封面：独立 FLIP + 逐帧 Matrix 等比裁切（位图不挤压）
        h.coverBox.setPivotX(0f);
        h.coverBox.setPivotY(0f);
        float csx = it.csx0 + (1 - it.csx0) * e;
        float csy = it.csy0 + (1 - it.csy0) * e;
        h.coverBox.setScaleX(csx);
        h.coverBox.setScaleY(csy);
        h.coverBox.setTranslationX(it.ctx0 * (1 - e));
        h.coverBox.setTranslationY(it.cty0 * (1 - e));
        adapter.applyCoverMatrix(h, csx, csy);
        // 封面圆角随形变插值：列表 8dp 全角 ⇄ 宫格海报顶 12dp/底 0（消除切换首尾的圆角突 snap）
        float outTopR = it.outIsList ? 8f : 12f, outBotR = it.outIsList ? 8f : 0f;
        float inTopR = it.inIsList ? 8f : 12f, inBotR = it.inIsList ? 8f : 0f;
        h.applyCoverCorners(outTopR + (inTopR - outTopR) * e, outBotR + (inBotR - outBotR) * e);
        // 文字连续交叉淡化：旧字随缓动进度 1→0、新字 0→1（宽度已跟随卡片，信息随卡片收拢/展开，无空白空档）
        it.outBox.setAlpha(1f - e);
        it.inBox.setAlpha(e);
    }

    private void cancelMorph() {
        if (!morphAnimators.isEmpty()) {
            for (ValueAnimator an : new ArrayList<>(morphAnimators)) {
                an.removeAllUpdateListeners();
                an.cancel();
            }
            morphAnimators.clear();
        }
        morphAnimating = false;
    }

    @SuppressLint("InflateParams")
    private void showShelfMenu(View anchor) {
        View popupView = LayoutInflater.from(getActivity()).inflate(R.layout.popup_shelf_menu, null);
        ImageView ivLayoutIcon = popupView.findViewById(R.id.iv_layout_icon);
        TextView tvLayoutText = popupView.findViewById(R.id.tv_layout_text);

        // 根据当前状态设置图标和文字
        if (isGridView) {
            ivLayoutIcon.setImageResource(R.drawable.ic_list);
            tvLayoutText.setText("列表布局");
        } else {
            ivLayoutIcon.setImageResource(R.drawable.ic_grid);
            tvLayoutText.setText("宫格布局");
        }

        // 布局切换（形变动画：记录矩形 → 位移/缩放反向补偿 → 弧线缓动 → 错帧）
        popupView.findViewById(R.id.item_layout_switch).setOnClickListener(v -> {
            dismissMenu();
            switchShelfLayoutAnimated();
        });
        // 导入书籍
        popupView.findViewById(R.id.item_import_book).setOnClickListener(v -> {
            dismissMenu();
            startActivity(new Intent(getActivity(), UploadBookActivity.class));
        });
        // 分组管理
        popupView.findViewById(R.id.item_group_manage).setOnClickListener(v -> {
            dismissMenu();
            showGroupManageDialog();
        });
        shelfMenuPopup = new android.widget.PopupWindow(popupView,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT, true);
        shelfMenuPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        shelfMenuPopup.setOutsideTouchable(true);
        shelfMenuPopup.showAsDropDown(anchor, 0, 10);
    }

    private void dismissMenu() {
        if (shelfMenuPopup != null && shelfMenuPopup.isShowing()) shelfMenuPopup.dismiss();
    }

    private void loadReadTime() {
        if (tvReadTime == null || getActivity() == null) return;
        // 与「我的」页同源同格式：只认服务端 user_reading_stat 的累计值（秒），
        // 文案统一走 ReadTimeText，保证两页显示的数字永远一致。
        // 不再回退本地 SP——旧逻辑的本地累计不分用户、含未上报时长，
        // 服务端返回 0 或请求失败时会与服务端值对不上（两页显示不一致）。
        long userId = getUserId();
        if (userId <= 0) {
            tvReadTime.setText("阅读时长 0 分钟");
            return;
        }
        RetrofitClient.getApiService().getTotalReadTime(userId)
                .enqueue(new Callback<ApiResponse<Long>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<Long>> call,
                            @NonNull Response<ApiResponse<Long>> response) {
                        long seconds = 0;
                        if (response.isSuccessful() && response.body() != null && response.body().isSuccess()
                                && response.body().getData() != null) {
                            seconds = Math.max(0, response.body().getData());
                        }
                        // 失败/空数据分支与「我的」页一致：归零，保证两页永远显示同一个数
                        if (tvReadTime != null) {
                            tvReadTime.setText("阅读时长 " + ReadTimeText.format(seconds));
                        }
                    }
                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<Long>> call, @NonNull Throwable t) {
                        if (tvReadTime != null) tvReadTime.setText("阅读时长 0 分钟");
                    }
                });
    }

    /**
     * 从 SharedPreferences 读取本地导入的书籍
     */
    private List<Book> loadLocalBooksFromPref() {
        List<Book> localBooks = new ArrayList<>();
        if (getActivity() == null) return localBooks;
        SharedPreferences spLocal = getActivity().getSharedPreferences("local_books", Context.MODE_PRIVATE);
        int localCount = spLocal.getInt("count", 0);
        for (int i = 0; i < localCount; i++) {
            long bookId = spLocal.getLong("book_id_" + i, 0);
            String name = spLocal.getString("book_name_" + i, "未命名");
            String author = spLocal.getString("book_author_" + i, "未知作者");
            String cover = spLocal.getString("book_cover_" + i, "");
            String coverPath = spLocal.getString("book_cover_path_" + i, "");

            Book book = new Book();
            book.setId(bookId);
            book.setBookName(name);
            book.setTitle(name);
            book.setAuthor(author);

            // 优先使用coverPath（如果文件存在）
            if (coverPath != null && !coverPath.isEmpty()) {
                java.io.File coverFile = new java.io.File(coverPath);
                if (coverFile.exists()) {
                    book.setCover(coverPath);
                } else {
                    book.setCover(cover);
                }
            } else {
                book.setCover(cover);
            }

            book.setChapterCount(spLocal.getInt("chapter_count_" + i, 0));
            book.setStatus(-1);  // 标记为本地书
            localBooks.add(book);
        }
        return localBooks;
    }

    /**
     * 从 SharedPreferences 读取网络书籍缓存
     */
    private List<Book> loadCachedNetworkBooks() {
        List<Book> cached = new ArrayList<>();
        if (getActivity() == null) return cached;
        SharedPreferences sp = getActivity().getSharedPreferences(PREF_SHELF_CACHE, Context.MODE_PRIVATE);
        int count = sp.getInt(KEY_CACHE_COUNT, 0);
        for (int i = 0; i < count; i++) {
            long bookId = sp.getLong("book_id_" + i, 0);
            if (bookId == 0) continue;
            Book book = new Book();
            book.setId(bookId);
            String name = sp.getString("book_name_" + i, "");
            book.setBookName(name);
            book.setTitle(name);
            book.setAuthor(sp.getString("book_author_" + i, ""));
            book.setCover(sp.getString("book_cover_" + i, ""));
            book.setStatus(sp.getInt("book_status_" + i, 0));
            cached.add(book);
        }
        return cached;
    }

    /**
     * 将网络书籍缓存到 SharedPreferences
     */
    private void saveNetworkBooksCache(List<Book> networkBooks) {
        if (getActivity() == null) return;
        SharedPreferences sp = getActivity().getSharedPreferences(PREF_SHELF_CACHE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        editor.putInt(KEY_CACHE_COUNT, networkBooks.size());
        for (int i = 0; i < networkBooks.size(); i++) {
            Book b = networkBooks.get(i);
            editor.putLong("book_id_" + i, b.getId() == null ? 0 : b.getId());
            editor.putString("book_name_" + i, b.getBookName());
            editor.putString("book_author_" + i, b.getAuthor());
            editor.putString("book_cover_" + i, b.getCover());
            editor.putInt("book_status_" + i, b.getStatus());
        }
        editor.apply();
    }

    /**
     * 合并本地书与网络书（按 bookId 去重，本地书在前）
     */
    private List<Book> mergeBooks(List<Book> localBooks, List<Book> networkBooks) {
        List<Book> merged = new ArrayList<>(localBooks);
        if (networkBooks != null) {
            for (Book nb : networkBooks) {
                if (nb == null || nb.getId() == null) continue;
                boolean exists = false;
                for (Book mb : merged) {
                    if (mb.getId() != null && mb.getId().equals(nb.getId())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) merged.add(nb);
            }
        }
        return merged;
    }

    /**
     * 按最后阅读时间排序（置顶书优先、其余保持时间序），并刷新UI
     */
    private void applySortAndNotify() {
        if (getActivity() == null) return;
        SharedPreferences sp = getActivity().getSharedPreferences("reading_records", Context.MODE_PRIVATE);
        SharedPreferences spExt = getActivity().getSharedPreferences(ExternalPrefs.recordsName(getActivity()),
                Context.MODE_PRIVATE);
        shelfBookList.sort((a, b) -> {
            long timeA = getLastReadTimeFor(sp, spExt, a);
            long timeB = getLastReadTimeFor(sp, spExt, b);
            return Long.compare(timeB, timeA);
        });
        // 置顶优先：先按时间排好，再对置顶集合做稳定分区（List.sort 稳定 → 其余相对顺序不变）
        Set<String> pins = ShelfPinStore.load(getActivity(), getUserId());
        if (!pins.isEmpty()) {
            shelfBookList.sort((a, b) -> Boolean.compare(
                    !ShelfPinStore.contains(pins, ShelfPinStore.keyOf(a)),
                    !ShelfPinStore.contains(pins, ShelfPinStore.keyOf(b))));
        }
        // 重绑前先终止进行中的形变动画：主时钟若还在跑，会在重绑后的 holder 上
        // 继续盖中途 FLIP 态（封面放大/文字消失），且每帧重申会顶掉 applyMode 复位
        cancelMorph();
        adapter.notifyDataSetChanged();
        updateEmptyView(shelfBookList.isEmpty());
    }

    /**
     * 兼容外站书籍（id 为 null）的最后阅读时间获取。
     */
    private long getLastReadTimeFor(SharedPreferences sp, SharedPreferences spExt, Book book) {
        if (book.getStatus() == -2 && book.getSourceType() != null && book.getSourceUrl() != null) {
            String recordKey = "ext_" + book.getSourceType() + "|" + book.getSourceUrl();
            return spExt.getLong(recordKey + "_readTime", 0);
        }
        Long id = book.getId();
        if (id == null) return 0;
        return getLastReadTime(sp, id);
    }

    /**
     * 加载书架：
     * - 首次 / 下拉刷新：用本地书 + 缓存网络书 + 外站书先显示，同时后台请求服务器刷新
     * - 从阅读页返回（非首次）：保留当前显示数据，后台静默刷新，不主动清空
     * 全量数据存入 allBooks，由 applyGroupFilter() 按当前分组过滤后显示。
     */
    private void loadBookshelf() {
        long userId = getUserId();
        // 旧版全局外站数据迁移到当前登录用户命名空间（幂等，未登录则跳过）
        ExternalPrefs.migrateIfNeeded(getActivity());
        final List<Book> localBooks = loadLocalBooksFromPref();
        final List<Book> externalBooks = loadExternalBooks();

        if (userId == 0) {
            // 游客模式：外站书按用户隔离，游客命名空间恒空，故只展示本地导入书，不请求服务器书架
            allBooks.clear();
            allBooks.addAll(mergeAllBooks(localBooks, new ArrayList<>(), externalBooks));
            loadReadingProgresses();
            applyGroupFilter();
            stopRefreshing();
            return;
        }

        final List<Book> cachedNetworkBooks = loadCachedNetworkBooks();
        final List<Book> baseline = mergeAllBooks(localBooks, cachedNetworkBooks, externalBooks);

        boolean needShowBaseline = !firstLoadDone || shelfBookList.isEmpty();
        if (needShowBaseline) {
            allBooks.clear();
            allBooks.addAll(baseline);
            applyGroupFilter();
        }

        // 只有「首屏且本地一无所有」（此刻列表注定是空的）才用整页加载动画。
        // 本方法被三个入口共用：onResume（每次从阅读页返回）、下拉刷新、外站同步完成回调 ——
        //    那几种情况列表里已经有书、也各有自己的反馈（下拉转圈），再无条件盖一层整页动画
        //    会把已有列表整块刷掉（踩过）。
        if (!firstLoadDone && baseline.isEmpty()) setShelfLoading(true);

        // 开屏预取命中：书架请求已在开屏窗口内完成，直接同步上屏（省 ~670ms 网络等待）
        ApiResponse<List<Bookshelf>> prefetched = ShelfPrefetch.consume(userId);
        if (prefetched != null) {
            applyServerBooks(prefetched.getData(), localBooks, externalBooks);
            firstLoadDone = true;
            stopRefreshing();
            return;
        }

        // 必须走 SafeCallback：换肤 / 切夜间会重建 Activity，旧实例的在途响应回调落到已 detach 的
        // Fragment 上时 getActivity() 为 null，applyServerBooks 里读阅读进度就直接 NPE 退出。
        RetrofitClient.getApiService().getBookshelf(userId).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        applyServerBooks(response.body().getData(), localBooks, externalBooks);
                        firstLoadDone = true;
                    }
                    stopRefreshing();
                },
                (call, t) -> {
                    firstLoadDone = true;
                    stopRefreshing();
                }));
    }

    /**
     * 网络书架数据处理：缓存网络书 + 合并本地/网络/外站 + 刷新进度与分组过滤。
     * 供普通网络回调与开屏预取命中两条路径共用。
     */
    private void applyServerBooks(List<Bookshelf> list, List<Book> localBooks, List<Book> externalBooks) {
        List<Book> serverBooks = new ArrayList<>();
        if (list != null) {
            for (Bookshelf bs : list) {
                if (bs != null && bs.getBook() != null) {
                    serverBooks.add(bs.getBook());
                }
            }
        }
        // 缓存网络书籍
        saveNetworkBooksCache(serverBooks);

        // 合并本地 + 网络 + 外站，全量存入 allBooks
        allBooks.clear();
        allBooks.addAll(mergeAllBooks(localBooks, serverBooks, externalBooks));
        loadReadingProgresses();
        applyGroupFilter();
    }

    /**
     * 合并本地书、网络书、外站书（去重）。
     */
    private List<Book> mergeAllBooks(List<Book> localBooks, List<Book> networkBooks, List<Book> externalBooks) {
        List<Book> merged = mergeBooks(localBooks, networkBooks);
        // 追加外站书（外站书无 id，按 sourceType|sourceUrl 去重）
        if (externalBooks != null) {
            for (Book eb : externalBooks) {
                if (eb == null) continue;
                boolean exists = false;
                for (Book mb : merged) {
                    if (mb.getStatus() == -2
                            && equalsStr(mb.getSourceType(), eb.getSourceType())
                            && equalsStr(mb.getSourceUrl(), eb.getSourceUrl())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) merged.add(eb);
            }
        }
        return merged;
    }

    private static boolean equalsStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 从 SharedPreferences 读取外站书籍（加入书架的外站书）。
     * 格式：key=sourceType|sourceBookId，value=title|||author|||cover|||category|||chapterCount
     */
    private List<Book> loadExternalBooks() {
        List<Book> external = new ArrayList<>();
        if (getActivity() == null) return external;
        SharedPreferences sp = getActivity().getSharedPreferences(ExternalPrefs.shelfName(getActivity()),
                Context.MODE_PRIVATE);
        java.util.Map<String, ?> all = sp.getAll();
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            Object v = entry.getValue();
            if (!(v instanceof String)) continue;
            String val = (String) v;
            if (val.isEmpty()) continue;
            String[] parts = val.split("\\|\\|\\|", -1);
            Book book = new Book();
            book.setTitle(parts.length > 0 ? parts[0] : "未命名");
            book.setBookName(parts.length > 0 ? parts[0] : "未命名");
            book.setAuthor(parts.length > 1 ? parts[1] : "");
            book.setCover(parts.length > 2 ? parts[2] : "");
            book.setCategory(parts.length > 3 ? parts[3] : "");
            if (parts.length > 4) {
                try { book.setChapterCount(Integer.parseInt(parts[4])); } catch (Exception ignore) {}
            }
            int sep = key.indexOf('|');
            if (sep > 0) {
                book.setSourceType(key.substring(0, sep));
                book.setSourceUrl(key.substring(sep + 1));
            }
            // 生成稳定的合成ID（负数，避免与真实bookId冲突），供 progressMap 查找阅读进度
            String compositeKey = book.getSourceType() + "|" + book.getSourceUrl();
            book.setId(-(long) Math.abs(compositeKey.hashCode()));
            book.setStatus(-2); // 标记为外站书籍
            external.add(book);
        }
        return external;
    }

    /**
     * 从本地 external_bookshelf 移除外站书籍。
     */
    private void removeExternalBook(Book book) {
        if (book.getSourceType() == null || book.getSourceUrl() == null) return;
        String key = book.getSourceType() + "|" + book.getSourceUrl();
        SharedPreferences sp = getActivity().getSharedPreferences(ExternalPrefs.shelfName(getActivity()),
                Context.MODE_PRIVATE);
        sp.edit().remove(key).apply();
        // 同步移除阅读记录
        String recordKey = "ext_" + book.getSourceType() + "|" + book.getSourceUrl();
        getActivity().getSharedPreferences(ExternalPrefs.recordsName(getActivity()), Context.MODE_PRIVATE)
                .edit()                .remove(recordKey + "_chapterIndex")
                .remove(recordKey + "_chapterTitle")
                .remove(recordKey + "_page")
                .remove(recordKey + "_readTime")
                .apply();

        // 同步删除服务器侧外站书架 + 阅读记录（离散主动操作，立即同步；否则下次 pullAll 会把记录拉回）
        ExternalSyncManager.getInstance(getActivity()).removeShelfRemote(book.getSourceType(), book.getSourceUrl());
        ExternalSyncManager.getInstance(getActivity()).deleteReadingRecordRemote(book.getSourceType(),
                book.getSourceUrl());

        getActivity().getSharedPreferences(PREF_GROUP_ASSIGN, Context.MODE_PRIVATE)
                .edit().remove(getBookGroupKey(book)).apply();
    }

    private long getLastReadTime(SharedPreferences sp, long bookId) {
        int count = sp.getInt("record_count", 0);
        for (int i = 0; i < count; i++) {
            long bid = sp.getLong("record_bookId_" + i, 0);
            if (bid == bookId) {
                return sp.getLong("record_readTime_" + i, 0);
            }
        }
        return 0;
    }

    /**
     * 从本地缓存中删除指定 ID 的本地书
     */
    private void removeLocalBook(long bookId) {
        SharedPreferences sp = getActivity().getSharedPreferences("local_books", Context.MODE_PRIVATE);
        int count = sp.getInt("count", 0);

        // 临时存储未删除的书籍数据
        List<Long> bookIds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> authors = new ArrayList<>();
        List<String> covers = new ArrayList<>();
        List<String> coverPaths = new ArrayList<>();
        List<String> intros = new ArrayList<>();
        List<Integer> chapterCounts = new ArrayList<>();
        List<List<String>> allTitles = new ArrayList<>();
        // 分卷结构：删书重排会重写整个 local_books，必须一并搬运，
        // 否则删掉任意一本书后，其余 TXT 的分卷折叠目录会全部丢失（退化成平铺）。
        List<List<LocalBookParser.VolumeInfo>> allVolumes = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            long id = sp.getLong("book_id_" + i, 0);
            if (id == bookId) continue; // 跳过要删除的书籍

            bookIds.add(id);
            names.add(sp.getString("book_name_" + i, ""));
            authors.add(sp.getString("book_author_" + i, ""));
            covers.add(sp.getString("book_cover_" + i, ""));
            coverPaths.add(sp.getString("book_cover_path_" + i, ""));
            intros.add(sp.getString("book_intro_" + i, ""));
            int chCount = sp.getInt("chapter_count_" + i, 0);
            chapterCounts.add(chCount);

            // 正文已改为文件缓存，这里只需搬运章节标题
            List<String> titles = new ArrayList<>();
            for (int j = 0; j < chCount; j++) {
                titles.add(sp.getString("chapter_title_" + i + "_" + j, ""));
            }
            allTitles.add(titles);

            // 搬运分卷结构（无分卷的书长度为 0）
            int volCount = sp.getInt("book_volume_count_" + i, 0);
            List<LocalBookParser.VolumeInfo> vols = new ArrayList<>();
            for (int v = 0; v < volCount; v++) {
                LocalBookParser.VolumeInfo vi = new LocalBookParser.VolumeInfo();
                vi.index = v + 1;
                vi.title = sp.getString("book_volume_title_" + i + "_" + v, "");
                vi.start = sp.getInt("book_volume_start_" + i + "_" + v, 0);
                vi.childStart = sp.getInt("book_volume_child_" + i + "_" + v, vi.start);
                vi.end = sp.getInt("book_volume_end_" + i + "_" + v, vi.start);
                vols.add(vi);
            }
            allVolumes.add(vols);
        }

        // 重新写入 SharedPreferences
        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        int newCount = bookIds.size();
        editor.putInt("count", newCount);
        for (int i = 0; i < newCount; i++) {
            editor.putLong("book_id_" + i, bookIds.get(i));
            editor.putString("book_name_" + i, names.get(i));
            editor.putString("book_author_" + i, authors.get(i));
            editor.putString("book_cover_" + i, covers.get(i));
            editor.putString("book_cover_path_" + i, coverPaths.get(i));
            editor.putString("book_intro_" + i, intros.get(i));
            editor.putInt("chapter_count_" + i, chapterCounts.get(i));
            List<String> titles = allTitles.get(i);
            for (int j = 0; j < titles.size(); j++) {
                editor.putString("chapter_title_" + i + "_" + j, titles.get(j));
            }
            // 回写分卷结构
            List<LocalBookParser.VolumeInfo> vols = allVolumes.get(i);
            editor.putInt("book_volume_count_" + i, vols.size());
            for (int v = 0; v < vols.size(); v++) {
                LocalBookParser.VolumeInfo vi = vols.get(v);
                editor.putString("book_volume_title_" + i + "_" + v, vi.title != null ? vi.title : "");
                editor.putInt("book_volume_start_" + i + "_" + v, vi.start);
                editor.putInt("book_volume_child_" + i + "_" + v, vi.childStart);
                editor.putInt("book_volume_end_" + i + "_" + v, vi.end);
            }
        }
        editor.apply();

        // 同步清理本地书关联数据：阅读记录、书签、分页缓存、分组归属
        // 避免删除书籍后残留垃圾数据导致阅读记录页/书签页显示已删除书籍

        // 1) 阅读记录 reading_records：按 bookId 定位，与最后一条交换后删除全部字段
        SharedPreferences recSp = getActivity().getSharedPreferences("reading_records", Context.MODE_PRIVATE);
        int recCount = recSp.getInt("record_count", 0);
        for (int i = 0; i < recCount; i++) {
            if (recSp.getLong("record_bookId_" + i, 0) == bookId) {
                int lastIndex = recCount - 1;
                if (i != lastIndex) {
                    recSp.edit()
                            .putLong("record_bookId_" + i, recSp.getLong("record_bookId_" + lastIndex, 0))
                            .putString("record_bookName_" + i, recSp.getString("record_bookName_" + lastIndex, ""))
                            .putString("record_author_" + i, recSp.getString("record_author_" + lastIndex, ""))
                            .putString("record_cover_" + i, recSp.getString("record_cover_" + lastIndex, ""))
                            .putInt("record_chapterIndex_" + i, recSp.getInt("record_chapterIndex_" + lastIndex, 0))
                            .putString("record_chapterTitle_" + i, recSp.getString("record_chapterTitle_"
                                    + lastIndex, ""))
                            .putInt("record_totalChapters_" + i, recSp.getInt("record_totalChapters_" + lastIndex, 0))
                            .putInt("record_page_" + i, recSp.getInt("record_page_" + lastIndex, 1))
                            .putInt("record_totalPages_" + i, recSp.getInt("record_totalPages_" + lastIndex, 0))
                            .putInt("record_progress_" + i, recSp.getInt("record_progress_" + lastIndex, 0))
                            .putLong("record_readTime_" + i, recSp.getLong("record_readTime_" + lastIndex, 0))
                            .apply();
                }
                recSp.edit()
                        .remove("record_bookId_" + lastIndex)
                        .remove("record_bookName_" + lastIndex)
                        .remove("record_author_" + lastIndex)
                        .remove("record_cover_" + lastIndex)
                        .remove("record_chapterIndex_" + lastIndex)
                        .remove("record_chapterTitle_" + lastIndex)
                        .remove("record_totalChapters_" + lastIndex)
                        .remove("record_page_" + lastIndex)
                        .remove("record_totalPages_" + lastIndex)
                        .remove("record_progress_" + lastIndex)
                        .remove("record_readTime_" + lastIndex)
                        .putInt("record_count", recCount - 1)
                        .apply();
                break;
            }
        }

        // 2) 书签 local_bookmarks_{bookId}：整文件清空
        getActivity().getSharedPreferences("local_bookmarks_" + bookId, Context.MODE_PRIVATE)
                .edit().clear().apply();

        // 3) 分组归属 shelf_group_assign：移除 id_{bookId}
        getActivity().getSharedPreferences(PREF_GROUP_ASSIGN, Context.MODE_PRIVATE)
                .edit().remove("id_" + bookId).apply();

        // 4) EPUB「保留样式」HTML 文件缓存：删除 filesDir/local_book_html/<bookId> 整目录
        //    否则重导入同书会生成新 bookId 目录，旧目录残留成为孤儿文件
        LocalBookParser.deleteHtmlCache(getActivity(), bookId);
    }

    /**
     * 加载所有书架书籍的阅读进度
     */
    private void loadReadingProgresses() {
        progressMap.clear();
        chapterTitleMap.clear();

        // 先从本地缓存读取
        SharedPreferences sp = getActivity().getSharedPreferences("reading_records", Context.MODE_PRIVATE);
        int count = sp.getInt("record_count", 0);
        for (int i = 0; i < count; i++) {
            long bookId = sp.getLong("record_bookId_" + i, 0);
            int chapterIndex = sp.getInt("record_chapterIndex_" + i, 0);
            String chapterTitle = sp.getString("record_chapterTitle_" + i, "");
            if (bookId > 0) {
                progressMap.put(bookId, chapterIndex);
                if (!chapterTitle.isEmpty()) {
                    chapterTitleMap.put(bookId, chapterTitle);
                }
            }
        }

        // 外站书籍：从 external_reading_records 读取阅读进度
        SharedPreferences extSp = getActivity().getSharedPreferences(ExternalPrefs.recordsName(getActivity()),
                Context.MODE_PRIVATE);
        for (Book book : allBooks) {
            Integer stObj = book.getStatus();
            int st = stObj == null ? 0 : stObj;
            if (st != -2) continue;  // 只处理外站书
            String sourceType = book.getSourceType();
            String sourceUrl = book.getSourceUrl();
            if (sourceType == null || sourceUrl == null) continue;
            String prefix = "ext_" + sourceType + "|" + sourceUrl;
            int extChapterIndex = extSp.getInt(prefix + "_chapterIndex", -1);
            if (extChapterIndex >= 0) {
                Long idBox = book.getId();
                long syntheticId = idBox == null ? -1L : idBox;
                progressMap.put(syntheticId, extChapterIndex);
                String extChapterTitle = extSp.getString(prefix + "_chapterTitle", "");
                if (!extChapterTitle.isEmpty()) {
                    chapterTitleMap.put(syntheticId, extChapterTitle);
                }
            }
        }

        adapter.notifyDataSetChanged();

        // 异步从服务器获取最新进度（仅网络书，外站/本地书无服务器进度）
        long userId = getUserId();
        for (Book book : allBooks) {
            Long idBox = book.getId();
            if (idBox == null || idBox <= 0) continue;          // 外站书/无id 跳过
            if (book.getStatus() == -1) continue;               // 本地书不查服务器
            final long bookId = idBox;
            if (progressMap.containsKey(bookId)) continue;
            RetrofitClient.getApiService().getProgress(userId, bookId)
                    .enqueue(SafeCallback.from(this,
                            (call, response) -> {
                                if (response.isSuccessful() && response.body() != null
                                        && response.body().isSuccess() && response.body().getData() != null) {
                                    ReadingProgress p = response.body().getData();
                                    progressMap.put(bookId, p.getChapterIndex());
                                    adapter.notifyDataSetChanged();
                                }
                            },
                            (call, t) -> { }));
        }
    }

    /**
     * 统一刷新「书籍列表 / 空状态」的可见性。
     * 加载动画还在转时（loadingView 可见）绝不显示「书架空空如也」——否则首屏会先闪一下空状态。
     */
    private void updateEmptyView(boolean isEmpty) {
        boolean loading = loadingView != null && loadingView.getVisibility() == View.VISIBLE;
        boolean showEmpty = isEmpty && !loading;
        if (layoutEmpty != null) layoutEmpty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        rvShelf.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
    }

    /** 首屏加载动画：转起来时同时收起列表与空状态。 */
    private void setShelfLoading(boolean loading) {
        if (loadingView == null) return;
        loadingView.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
            rvShelf.setVisibility(View.GONE);
        }
    }

    private long getUserId() {
        String uid = getActivity().getSharedPreferences("user_info", 0).getString("userId", "");
        return uid.isEmpty() ? 0 : Long.parseLong(uid);
    }

    private void stopRefreshing() {
        if (swipeRefresh != null && swipeRefresh.isRefreshing()) {
            swipeRefresh.setRefreshing(false);
        }
        // 首屏加载结束：收起加载动画，再由真实数据决定显示列表还是空状态
        setShelfLoading(false);
        updateEmptyView(shelfBookList.isEmpty());
    }

    // ======================== 分组 ========================

    /**
     * 按当前分组过滤 allBooks，写入 shelfBookList 并刷新。
     */
    private void applyGroupFilter() {
        shelfBookList.clear();
        for (Book b : allBooks) {
            if (b == null) continue;
            if (currentGroup.equals(GROUP_ALL)) {
                shelfBookList.add(b);
            } else if (currentGroup.equals(GROUP_LOCAL) && b.getStatus() == -1) {
                shelfBookList.add(b);
            } else if (currentGroup.equals(GROUP_EXTERNAL) && b.getStatus() == -2) {
                shelfBookList.add(b);
            } else if (currentGroup.equals(GROUP_UNGROUPED)) {
                // 网络书且未分配自定义分组
                if (b.getStatus() != -1 && b.getStatus() != -2
                        && getBookGroupName(b).equals(GROUP_UNGROUPED)) {
                    shelfBookList.add(b);
                }
            } else {
                // 自定义分组
                if (getBookGroupName(b).equals(currentGroup)) {
                    shelfBookList.add(b);
                }
            }
        }
        applySortAndNotify();
    }

    /**
     * 构建分组 Tab（默认 + 自定义）。
     * 等宽分段：所有分组 weight 均分，新增分组时其余分段压缩让位（FLIP 位移动画），新组淡入。
     */
    private void buildGroupTabs() {
        if (layoutGroupTabs == null || getActivity() == null) return;
        // 记录重建前各 chip 的横向位置，重建后做"让位"位移动画
        java.util.Map<String, Integer> oldLefts = new java.util.HashMap<>();
        for (int i = 0; i < layoutGroupTabs.getChildCount(); i++) {
            View c = layoutGroupTabs.getChildAt(i);
            if (c instanceof TextView) {
                oldLefts.put(((TextView) c).getText().toString(), c.getLeft());
            }
        }
        boolean rebuild = !oldLefts.isEmpty();

        layoutGroupTabs.removeAllViews();
        java.util.List<String> groups = new ArrayList<>();
        groups.add(GROUP_ALL);
        groups.add(GROUP_LOCAL);
        groups.add(GROUP_UNGROUPED);
        groups.add(GROUP_EXTERNAL);
        groups.addAll(customGroups);
        // 若当前分组已被删除，回退到"全部"
        if (!groups.contains(currentGroup)) currentGroup = GROUP_ALL;

        // 分组多时字号自适应缩小（等分后宽度变小）
        int n = groups.size();
        float textSize = n >= 8 ? 12f : (n >= 6 ? 13f : 14f);
        for (String g : groups) {
            TextView chip = new TextView(getActivity());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            chip.setLayoutParams(lp);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setText(g);
            chip.setTextSize(textSize);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); // 效果稿 font-weight:600
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setClickable(true);
            chip.setFocusable(true);
            applyChipStyle(chip, g.equals(currentGroup));
            chip.setOnClickListener(v -> {
                if (g.equals(currentGroup)) return;
                currentGroup = g;
                // 先记录切换前各 chip 的真实颜色作为渐变起点（必须在样式刷新之前）
                java.util.Map<TextView, Integer> startColors = new java.util.HashMap<>();
                for (int i = 0; i < layoutGroupTabs.getChildCount(); i++) {
                    View c = layoutGroupTabs.getChildAt(i);
                    if (c instanceof TextView) startColors.put((TextView) c, ((TextView) c).getCurrentTextColor());
                }
                pendingChipStartColors = startColors;
                for (int i = 0; i < layoutGroupTabs.getChildCount(); i++) {
                    View c = layoutGroupTabs.getChildAt(i);
                    if (c instanceof TextView) {
                        applyChipStyle((TextView) c, ((TextView) c).getText().toString().equals(currentGroup));
                    }
                }
                animateShelfThumbTo(chip);
                animateGroupContent();
            });
            layoutGroupTabs.addView(chip);
        }
        // 布局完成后：让位动画（重建且有位置变化时）+ 按当前分组定位指示器
        layoutGroupTabs.post(() -> {
            if (layoutGroupTabs == null || !isAdded()) return;
            boolean moved = false;
            for (int i = 0; i < layoutGroupTabs.getChildCount(); i++) {
                View c = layoutGroupTabs.getChildAt(i);
                if (!(c instanceof TextView)) continue;
                Integer old = oldLefts.get(((TextView) c).getText().toString());
                if (old == null) {
                    // 新增的分组：淡入
                    c.setAlpha(0f);
                    c.animate().alpha(1f).setDuration(220L)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else if (old != c.getLeft()) {
                    // 已有分组位置被挤动：从旧位置滑到新位置（让位）
                    c.setTranslationX(old - c.getLeft());
                    c.animate().translationX(0f).setDuration(260L)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    moved = true;
                }
            }
            positionShelfThumb(rebuild && moved);
        });
    }

    /** 分段文字色走 token：夜间选中态落在深灰指示器上，不能沿用日间那层 iOS 蓝 */
    private int shelfChipColor(boolean selected) {
        return ThemeAttrs.color(requireActivity(), selected ? R.attr.appSegText
                : R.attr.appTextSecondary, 0);
    }

    private void applyChipStyle(TextView chip, boolean selected) {
        if (selected) {
            chip.setTextColor(shelfChipColor(true));
            if (segThumbShelf != null) {
                chip.setBackground(null); // 白底由滑动指示器承担
            } else {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(ThemeAttrs.color(requireActivity(), R.attr.appSegThumb, 0));
                bg.setCornerRadius(getResources().getDisplayMetrics().density * 6);
                chip.setBackground(bg);
            }
        } else {
            chip.setTextColor(shelfChipColor(false));
            chip.setBackground(null); // 未选中：透明，让外层容器露出来
        }
    }

    /** 在 chip 行里找指定分组名的 chip */
    private View findGroupChip(String name) {
        if (layoutGroupTabs == null) return null;
        for (int i = 0; i < layoutGroupTabs.getChildCount(); i++) {
            View c = layoutGroupTabs.getChildAt(i);
            if (c instanceof TextView && ((TextView) c).getText().toString().equals(name)) return c;
        }
        return null;
    }

    /** 无动画归位指示器（重建 Tab 时用） */
    private void positionShelfThumb(boolean animate) {
        if (segThumbShelf == null || layoutGroupTabs == null) return;
        View target = findGroupChip(currentGroup);
        if (target == null) {
            segThumbShelf.setVisibility(View.INVISIBLE);
            return;
        }
        if (target.getWidth() == 0) {
            // onCreateView 里 post 的回调会在 attach 时、首次 layout 之前就跑掉，此刻宽度还是 0。
            // 早退会让指示器整页都不显示（只剩文字变色），所以挂一次性布局监听等它量出来再定位。
            target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    v.removeOnLayoutChangeListener(this);
                    if (isAdded()) positionShelfThumb(false);
                }
            });
            return;
        }
        segThumbShelf.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = segThumbShelf.getLayoutParams();
        if (lp.width != target.getWidth()) {
            lp.width = target.getWidth();
            segThumbShelf.setLayoutParams(lp);
        }
        float x = target.getLeft();
        if (animate) {
            animateShelfThumb(x, target.getWidth());
        } else {
            if (thumbAnim != null) thumbAnim.cancel();
            segThumbShelf.setTranslationX(x);
        }
    }

    /** 指示器滑到指定 chip（等宽分段下即滑动到目标分段） */
    private void animateShelfThumbTo(View chip) {
        if (segThumbShelf == null || layoutGroupTabs == null) return;
        segThumbShelf.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = segThumbShelf.getLayoutParams();
        if (lp.width != chip.getWidth()) {
            lp.width = chip.getWidth();
            segThumbShelf.setLayoutParams(lp);
        }
        animateShelfThumb(chip.getLeft(), chip.getWidth());
    }

    private void animateShelfThumb(float endX, int width) {
        if (thumbAnim != null) thumbAnim.cancel();
        thumbAnim = ValueAnimator.ofFloat(segThumbShelf.getTranslationX(), endX);
        thumbAnim.setDuration(260L);
        thumbAnim.setInterpolator(new DecelerateInterpolator());
        thumbAnim.addUpdateListener(a -> segThumbShelf.setTranslationX((float) a.getAnimatedValue()));
        thumbAnim.start();
        animateChipColors();
    }

    /** chip 文字颜色渐变到新的选中态（起点取切换前的真实颜色，避免其他选项闪现蓝色） */
    private void animateChipColors() {
        if (chipColorAnim != null) chipColorAnim.cancel();
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < layoutGroupTabs.getChildCount(); i++) {
            View c = layoutGroupTabs.getChildAt(i);
            if (c instanceof TextView) chips.add((TextView) c);
        }
        java.util.Map<TextView, Integer> starts = pendingChipStartColors;
        pendingChipStartColors = null;
        ArgbEvaluator ev = new ArgbEvaluator();
        chipColorAnim = ValueAnimator.ofFloat(0f, 1f);
        chipColorAnim.setDuration(220L);
        chipColorAnim.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            for (TextView c : chips) {
                boolean sel = c.getText().toString().equals(currentGroup);
                int from = (starts != null && starts.containsKey(c)) ? starts.get(c) : c.getCurrentTextColor();
                int to = shelfChipColor(sel);
                c.setTextColor((int) ev.evaluate(t, from, to));
            }
        });
        chipColorAnim.start();
    }

    /** 分组切换内容过渡：列表先快速淡出，换数据后淡入 */
    private void animateGroupContent() {
        if (rvShelf == null || !isAdded()) {
            applyGroupFilter();
            return;
        }
        rvShelf.animate().cancel();
        rvShelf.animate()
                .alpha(0.4f)
                .setDuration(110L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (!isAdded()) return;
                    applyGroupFilter();
                    rvShelf.animate()
                            .alpha(1f)
                            .setDuration(200L)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                })
                .start();
    }

    /**
     * 读取自定义分组列表。
     */
    private void loadCustomGroups() {
        customGroups.clear();
        if (getActivity() == null) return;
        String csv = getActivity().getSharedPreferences(PREF_SHELF_GROUPS, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_GROUPS, "");
        if (csv.isEmpty()) return;
        String[] names = csv.split(",");
        for (String n : names) {
            if (!n.isEmpty() && !customGroups.contains(n)) customGroups.add(n);
        }
    }

    private void saveCustomGroups() {
        if (getActivity() == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < customGroups.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(customGroups.get(i));
        }
        getActivity().getSharedPreferences(PREF_SHELF_GROUPS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CUSTOM_GROUPS, sb.toString()).apply();
    }

    /**
     * 书籍分组 key：网络/本地用 bookId，外站用 sourceType|sourceUrl。
     */
    private String getBookGroupKey(Book book) {
        if (book.getId() != null && book.getId() > 0) return "id_" + book.getId();
        return "ext_" + (book.getSourceType() != null ? book.getSourceType() : "")
                + "_" + (book.getSourceUrl() != null ? book.getSourceUrl() : "");
    }

    /**
     * 获取书籍所属分组名（网络书未分配则返回"未分组"）。
     */
    private String getBookGroupName(Book book) {
        if (book.getStatus() == -1) return GROUP_LOCAL;
        if (book.getStatus() == -2) return GROUP_EXTERNAL;
        if (getActivity() == null) return GROUP_UNGROUPED;
        String g = getActivity().getSharedPreferences(PREF_GROUP_ASSIGN, Context.MODE_PRIVATE)
                .getString(getBookGroupKey(book), null);
        return g == null ? GROUP_UNGROUPED : g;
    }

    private void setBookGroup(Book book, String groupName) {
        if (getActivity() == null) return;
        SharedPreferences.Editor ed = getActivity().getSharedPreferences(PREF_GROUP_ASSIGN,
                Context.MODE_PRIVATE).edit();
        if (groupName == null || groupName.isEmpty() || groupName.equals(GROUP_UNGROUPED)) {
            ed.remove(getBookGroupKey(book));
        } else {
            ed.putString(getBookGroupKey(book), groupName);
        }
        ed.apply();
    }

    /**
     * 分组管理弹窗：添加/删除自定义分组。
     */
    @SuppressLint("InflateParams")
    private void showGroupManageDialog() {
        View view = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_group_manage, null);
        android.widget.ListView lv = view.findViewById(R.id.lv_custom_groups);
        final android.widget.ArrayAdapter<String> adapterGrp = new android.widget.ArrayAdapter<String>(
                getActivity(), android.R.layout.simple_list_item_1, customGroups) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setText(getItem(position) + "    (长按删除)");
                tv.setTextSize(14);
                tv.setTextColor(ThemeAttrs.color(requireActivity(), R.attr.appTextPrimary, 0));
                return tv;
            }
        };
        lv.setAdapter(adapterGrp);
        lv.setOnItemLongClickListener((parent, v, position, id) -> {
            String name = adapterGrp.getItem(position);
            new android.app.AlertDialog.Builder(getActivity())
                    .setTitle("删除分组")
                    .setMessage("删除\"" + name + "\"？组内书籍将回到\"未分组\"")
                    .setPositiveButton("删除", (d, w) -> {
                        customGroups.remove(name);
                        saveCustomGroups();
                        // 清除该分组的归属
                        SharedPreferences sp = getActivity().getSharedPreferences(PREF_GROUP_ASSIGN,
                                Context.MODE_PRIVATE);
                        SharedPreferences.Editor ed = sp.edit();
                        for (String k : sp.getAll().keySet()) {
                            if (name.equals(sp.getString(k, ""))) ed.remove(k);
                        }
                        ed.apply();
                        adapterGrp.notifyDataSetChanged();
                        if (currentGroup.equals(name)) currentGroup = GROUP_ALL;
                        buildGroupTabs();
                        applyGroupFilter();
                    })
                    .setNegativeButton("取消", null).show();
            return true;
        });

        android.widget.EditText etName = view.findViewById(R.id.et_group_name);
        view.findViewById(R.id.btn_add_group).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) { Hint.show(getActivity(), "请输入分组名"); return; }
            if (name.equals(GROUP_ALL) || name.equals(GROUP_LOCAL)
                    || name.equals(GROUP_UNGROUPED) || name.equals(GROUP_EXTERNAL)) {
                Hint.show(getActivity(), "与默认分组重名");
                return;
            }
            if (customGroups.contains(name)) { Hint.show(getActivity(), "分组已存在"); return; }
            customGroups.add(name);
            saveCustomGroups();
            adapterGrp.notifyDataSetChanged();
            etName.setText("");
            buildGroupTabs();
        });

        android.app.Dialog dialog = new android.app.Dialog(getActivity());
        dialog.setContentView(view);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        view.findViewById(R.id.btn_done).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * 编辑模式：将选中书籍移动到某分组。
     */
    private void showMoveGroupPicker() {
        List<Book> selected = adapter.getSelectedBooks();
        if (selected.isEmpty()) {
            Hint.show(getActivity(), "请先选择书籍");
            return;
        }
        java.util.List<String> options = new ArrayList<>();
        options.add(GROUP_UNGROUPED);
        options.addAll(customGroups);
        if (customGroups.isEmpty()) {
            Hint.showLong(getActivity(), "请先在分组管理中创建自定义分组");
            return;
        }
        String[] arr = options.toArray(new String[0]);
        new android.app.AlertDialog.Builder(getActivity())
                .setTitle("移动到分组")
                .setItems(arr, (d, which) -> {
                    String group = arr[which];
                    for (Book b : selected) {
                        // 本地书/外站书不参与自定义分组（它们有固定默认分组）
                        if (b.getStatus() == -1 || b.getStatus() == -2) continue;
                        setBookGroup(b, group);
                    }
                    hideEditMode();
                    applyGroupFilter();
                    Hint.show(getActivity(), "已移动到 " + group);
                })
                .setNegativeButton("取消", null).show();
    }
}