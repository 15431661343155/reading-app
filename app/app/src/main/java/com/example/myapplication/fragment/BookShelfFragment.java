package com.example.myapplication.fragment;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.myapplication.R;
import com.example.myapplication.activity.BookDetailActivity;
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.activity.UploadBookActivity;
import com.example.myapplication.adapter.BookAdapter;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.Bookshelf;
import com.example.myapplication.bean.ReadingProgress;
import com.example.myapplication.utils.LocalBookParser;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@SuppressLint({"UnknownNullness", "NewApi"})
public class BookShelfFragment extends Fragment {

    private FastScrollRecyclerView rvShelf;
    private SwipeRefreshLayout swipeRefresh;
    private BookAdapter adapter;
    private final List<Book> shelfBookList = new ArrayList<>();
    private TextView tvEmptyHint, tvReadTime;
    private android.widget.PopupWindow shelfMenuPopup;

    private android.app.Dialog topDialog;
    private android.app.Dialog bottomDialog;
    private boolean isGridView = false;

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

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_shelf, container, false);

        // 不再手动加状态栏padding：activity_main.xml 的 fitsSystemWindows + setDecorFits(true)
        // 已经让系统自动避让状态栏和导航栏，双重叠加会导致顶部大空白

        // 设置状态栏图标颜色为深色（因为书架页面背景是浅色）
        updateStatusBarColor();

        rvShelf = view.findViewById(R.id.rv_shelf);
        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        tvEmptyHint = view.findViewById(R.id.tv_empty_hint);
        tvReadTime = view.findViewById(R.id.tv_read_time);
        layoutGroupTabs = view.findViewById(R.id.layout_group_tabs);
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

        // 设置下拉刷新颜色
        swipeRefresh.setColorSchemeResources(R.color.ios_blue, R.color.ios_blue);

        // 下拉刷新监听
        swipeRefresh.setOnRefreshListener(() -> {
            loadBookshelf();
            loadReadTime();
        });

        adapter = new BookAdapter(getActivity(), shelfBookList, progressMap, chapterTitleMap);
        rvShelf.setAdapter(adapter);

        // 从 SharedPreferences 恢复布局模式（默认列表）
        SharedPreferences spPref = getActivity().getSharedPreferences("shelf_pref", Context.MODE_PRIVATE);
        isGridView = spPref.getBoolean("is_grid_mode", false);
        if (isGridView) {
            rvShelf.setLayoutManager(new GridLayoutManager(getActivity(), 3));
        } else {
            rvShelf.setLayoutManager(new LinearLayoutManager(getActivity()));
        }
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
                if (progressMap.containsKey(bookId)) {
                    intent.putExtra("chapterIndex", progressMap.get(bookId));
                }
                if (st == -1) {
                    intent.putExtra("isLocal", true);
                }
                startActivity(intent);
            } catch (Throwable t) {
                android.util.Log.e("BookShelf", "书架点击书籍失败", t);
                try { android.widget.Toast.makeText(getContext(), "打开失败：" + t.getMessage(),
                        android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
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
     * 外站书籍：复用书城可靠的"章节列表获取"逻辑（内存缓存 → 持久化缓存 → 网络），
     * 拿到章节列表后再带进阅读器。这样阅读器不再需要在内部异步拉取章节列表（该路径偶发卡在"正在加载"），
     * 与书城"在线阅读"表现一致：缓存命中即时进入，未命中先拉取再进入。
     */
    private void openExternalBook(Book book) {
        if (getActivity() == null) return;
        List<String[]> cached = BookDetailActivity.getExternalChapters(getActivity(), book);
        if (cached != null && !cached.isEmpty()) {
            launchExternalRead(book, cached);
            return;
        }
        // 缓存未命中：先可靠拉取章节列表（与书城在线阅读同一套逻辑），成功后再进入阅读器
        android.widget.Toast.makeText(getActivity(), "加载章节列表...", android.widget.Toast.LENGTH_SHORT).show();
        BookDetailActivity.fetchOnlineChapters(getActivity(), book, new BookDetailActivity.ChaptersCallback() {
            @Override
            public void onSuccess(List<String[]> chapters) {
                if (getActivity() != null) launchExternalRead(book, chapters);
            }
            @Override
            public void onFail(String msg, boolean sourceMayDown) {
                if (getActivity() != null) {
                    android.widget.Toast.makeText(getActivity(),
                            sourceMayDown ? "书源暂不可用，请稍后重试" : msg,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** 带章节列表进入阅读器（小书走 Intent，大书走共享缓存，与书城一致）。 */
    private void launchExternalRead(Book book, List<String[]> chapters) {
        Intent intent = new Intent(getActivity(), ReadActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("book", book);
        intent.putExtra("isExternal", true);
        BookDetailActivity.putChaptersExtra(getActivity(), intent, book, chapters);
        startActivity(intent);
    }

    /**
     * 更新状态栏图标颜色（浅色背景用深色图标）
     */
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
        loadBookshelf();
        loadReadTime();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            updateStatusBarColor();
        }
    }

    private void showEditMode() {
        adapter.setEditMode(true);
        showEditTopDialog();
        showEditBottomDialog();
    }

    @SuppressLint("InflateParams")
    private void showEditTopDialog() {
        View topView = LayoutInflater.from(getActivity()).inflate(R.layout.popup_edit_top, null);
        topView.findViewById(R.id.tv_done).setOnClickListener(v -> hideEditMode());

        TextView tvSelectAll = topView.findViewById(R.id.tv_select_all);
        tvSelectAll.setText("全选");
        tvSelectAll.setOnClickListener(v -> {
            if (adapter.getSelectedCount() == shelfBookList.size()) {
                adapter.clearSelection();
                tvSelectAll.setText("全选");
            } else {
                adapter.selectAll();
                tvSelectAll.setText("取消全选");
            }
        });

        topDialog = new android.app.Dialog(requireActivity(), R.style.TopDialogStyle);
        topDialog.setContentView(topView);
        topDialog.setCancelable(false);
        topDialog.setCanceledOnTouchOutside(false);
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

        bottomDialog = new android.app.Dialog(requireActivity(), R.style.BottomDialogStyle);
        bottomDialog.setContentView(bottomView);
        bottomDialog.setCancelable(false);
        bottomDialog.setCanceledOnTouchOutside(false);
        if (bottomDialog.getWindow() != null) {
            bottomDialog.getWindow().setGravity(Gravity.BOTTOM);
            bottomDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bottomDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            bottomDialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        bottomDialog.show();
    }

    private void hideEditMode() {
        adapter.setEditMode(false);
        if (topDialog != null && topDialog.isShowing()) topDialog.dismiss();
        if (bottomDialog != null && bottomDialog.isShowing()) bottomDialog.dismiss();
    }

    private void deleteSelectedBooks() {
        List<Book> selected = adapter.getSelectedBooks();
        if (selected.isEmpty()) { Toast.makeText(getActivity(), "请选择要删除的书籍", Toast.LENGTH_SHORT).show(); return; }
        long userId = getUserId();
        for (Book book : selected) {
            if (book.getStatus() == -2) {
                // 外站书籍：从本地 external_bookshelf 移除
                removeExternalBook(book);
            } else if (userId > 0 && book.getStatus() != -1) {
                RetrofitClient.getApiService().removeFromBookshelf(userId, book.getId())
                        .enqueue(new Callback<ApiResponse<Void>>() {
                            @Override public void onResponse(@NonNull Call<ApiResponse<Void>> call, @NonNull Response<ApiResponse<Void>> response) {}
                            @Override public void onFailure(@NonNull Call<ApiResponse<Void>> call, @NonNull Throwable t) {}
                        });
            }
            if (book.getStatus() == -1) {
                removeLocalBook(book.getId());
            }
            allBooks.remove(book);
            shelfBookList.remove(book);
        }
        adapter.refreshList(shelfBookList);
        updateEmptyView(shelfBookList.isEmpty());
        hideEditMode();
        buildGroupTabs();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (shelfMenuPopup != null && shelfMenuPopup.isShowing()) shelfMenuPopup.dismiss();
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

        // 布局切换
        popupView.findViewById(R.id.item_layout_switch).setOnClickListener(v -> {
            dismissMenu();
            isGridView = !isGridView;
            adapter.setGridMode(isGridView);
            if (isGridView) {
                rvShelf.setLayoutManager(new GridLayoutManager(getActivity(), 3));
            } else {
                rvShelf.setLayoutManager(new LinearLayoutManager(getActivity()));
            }
            // 保存布局模式到 SharedPreferences
            SharedPreferences.Editor editor = getActivity().getSharedPreferences("shelf_pref", Context.MODE_PRIVATE).edit();
            editor.putBoolean("is_grid_mode", isGridView);
            editor.apply();
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
        SharedPreferences sp = getActivity().getSharedPreferences("read_time", Context.MODE_PRIVATE);
        long totalMinutes = sp.getLong("total_read_time", 0);
        displayReadTime(totalMinutes);
        long userId = getUserId();
        if (userId > 0) {
            RetrofitClient.getApiService().getTotalReadTime(userId)
                    .enqueue(new Callback<ApiResponse<Long>>() {
                        @Override
                        public void onResponse(@NonNull Call<ApiResponse<Long>> call, @NonNull Response<ApiResponse<Long>> response) {
                            if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                                Long totalSeconds = response.body().getData();
                                if (totalSeconds != null && totalSeconds > 0) {
                                    long minutes = totalSeconds / 60;
                                    displayReadTime(minutes);
                                }
                            }
                        }
                        @Override
                        public void onFailure(@NonNull Call<ApiResponse<Long>> call, @NonNull Throwable t) {}
                    });
        }
    }

    @SuppressLint("SetTextI18n")
    private void displayReadTime(long totalMinutes) {
        if (totalMinutes < 60) {
            tvReadTime.setText("阅读时长 " + totalMinutes + " 分钟");
        } else {
            long hours = totalMinutes / 60;
            long mins = totalMinutes % 60;
            tvReadTime.setText("阅读时长 " + hours + " 小时 " + mins + " 分钟");
        }
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
     * 按最后阅读时间排序，并刷新UI
     */
    private void applySortAndNotify() {
        if (getActivity() == null) return;
        SharedPreferences sp = getActivity().getSharedPreferences("reading_records", Context.MODE_PRIVATE);
        SharedPreferences spExt = getActivity().getSharedPreferences("external_reading_records", Context.MODE_PRIVATE);
        shelfBookList.sort((a, b) -> {
            long timeA = getLastReadTimeFor(sp, spExt, a);
            long timeB = getLastReadTimeFor(sp, spExt, b);
            return Long.compare(timeB, timeA);
        });
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
        if (userId == 0) {
            allBooks.clear();
            shelfBookList.clear();
            adapter.notifyDataSetChanged();
            updateEmptyView(true);
            stopRefreshing();
            return;
        }

        final List<Book> localBooks = loadLocalBooksFromPref();
        final List<Book> cachedNetworkBooks = loadCachedNetworkBooks();
        final List<Book> externalBooks = loadExternalBooks();
        final List<Book> baseline = mergeAllBooks(localBooks, cachedNetworkBooks, externalBooks);

        boolean needShowBaseline = !firstLoadDone || shelfBookList.isEmpty();
        if (needShowBaseline) {
            allBooks.clear();
            allBooks.addAll(baseline);
            applyGroupFilter();
        }

        // 后台请求服务器刷新
        RetrofitClient.getApiService().getBookshelf(userId).enqueue(new Callback<ApiResponse<List<Bookshelf>>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<List<Bookshelf>>> call, @NonNull Response<ApiResponse<List<Bookshelf>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    List<Bookshelf> list = response.body().getData();
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
                    firstLoadDone = true;
                }
                stopRefreshing();
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse<List<Bookshelf>>> call, @NonNull Throwable t) {
                firstLoadDone = true;
                stopRefreshing();
            }
        });
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
        SharedPreferences sp = getActivity().getSharedPreferences("external_bookshelf", Context.MODE_PRIVATE);
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
            // ✅ 生成稳定的合成ID（负数，避免与真实bookId冲突），供 progressMap 查找阅读进度
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
        SharedPreferences sp = getActivity().getSharedPreferences("external_bookshelf", Context.MODE_PRIVATE);
        sp.edit().remove(key).apply();
        // 同步移除阅读记录
        String recordKey = "ext_" + book.getSourceType() + "|" + book.getSourceUrl();
        getActivity().getSharedPreferences("external_reading_records", Context.MODE_PRIVATE)
                .edit().remove(recordKey + "_chapterIndex")
                .remove(recordKey + "_chapterTitle")
                .remove(recordKey + "_page")
                .remove(recordKey + "_readTime")
                .apply();
        // 移除分组归属
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
        }
        editor.apply();

        // ✅ 同步清理本地书关联数据：阅读记录、书签、分页缓存、分组归属
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
                            .putString("record_chapterTitle_" + i, recSp.getString("record_chapterTitle_" + lastIndex, ""))
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

        // 3) 分页缓存 page_cache：删除所有 page_info_{bookId}_* 条目
        SharedPreferences pageSp = getActivity().getSharedPreferences("page_cache", Context.MODE_PRIVATE);
        String pagePrefix = "page_info_" + bookId + "_";
        SharedPreferences.Editor pageEditor = pageSp.edit();
        for (String k : pageSp.getAll().keySet()) {
            if (k != null && k.startsWith(pagePrefix)) pageEditor.remove(k);
        }
        pageEditor.apply();

        // 4) 分组归属 shelf_group_assign：移除 id_{bookId}
        getActivity().getSharedPreferences(PREF_GROUP_ASSIGN, Context.MODE_PRIVATE)
                .edit().remove("id_" + bookId).apply();

        // 5) EPUB「保留样式」HTML 文件缓存：删除 filesDir/local_book_html/<bookId> 整目录
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

        // ✅ 外站书籍：从 external_reading_records 读取阅读进度
        SharedPreferences extSp = getActivity().getSharedPreferences("external_reading_records", Context.MODE_PRIVATE);
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
                    .enqueue(new Callback<ApiResponse<ReadingProgress>>() {
                        @Override
                        public void onResponse(@NonNull Call<ApiResponse<ReadingProgress>> call,
                                               @NonNull Response<ApiResponse<ReadingProgress>> response) {
                            if (response.isSuccessful() && response.body() != null
                                    && response.body().isSuccess() && response.body().getData() != null) {
                                ReadingProgress p = response.body().getData();
                                progressMap.put(bookId, p.getChapterIndex());
                                adapter.notifyDataSetChanged();
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Call<ApiResponse<ReadingProgress>> call, @NonNull Throwable t) {}
                    });
        }
    }

    private void updateEmptyView(boolean isEmpty) {
        if (tvEmptyHint != null) tvEmptyHint.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvShelf.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private long getUserId() {
        String uid = getActivity().getSharedPreferences("user_info", 0).getString("userId", "");
        return uid.isEmpty() ? 0 : Long.parseLong(uid);
    }

    private void stopRefreshing() {
        if (swipeRefresh != null && swipeRefresh.isRefreshing()) {
            swipeRefresh.setRefreshing(false);
        }
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
     */
    private void buildGroupTabs() {
        if (layoutGroupTabs == null || getActivity() == null) return;
        layoutGroupTabs.removeAllViews();
        java.util.List<String> groups = new ArrayList<>();
        groups.add(GROUP_ALL);
        groups.add(GROUP_LOCAL);
        groups.add(GROUP_UNGROUPED);
        groups.add(GROUP_EXTERNAL);
        groups.addAll(customGroups);
        // 若当前分组已被删除，回退到"全部"
        if (!groups.contains(currentGroup)) currentGroup = GROUP_ALL;

        float density = getResources().getDisplayMetrics().density;
        for (String g : groups) {
            TextView chip = new TextView(getActivity());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMargins((int) (4 * density), 0, (int) (4 * density), 0);
            chip.setLayoutParams(lp);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding((int) (14 * density), 0, (int) (14 * density), 0);
            chip.setText(g);
            chip.setTextSize(14);
            chip.setClickable(true);
            chip.setFocusable(true);
            applyChipStyle(chip, g.equals(currentGroup));
            chip.setOnClickListener(v -> {
                currentGroup = g;
                // 刷新所有 chip 样式
                for (int i = 0; i < layoutGroupTabs.getChildCount(); i++) {
                    View c = layoutGroupTabs.getChildAt(i);
                    if (c instanceof TextView) {
                        applyChipStyle((TextView) c, ((TextView) c).getText().toString().equals(currentGroup));
                    }
                }
                applyGroupFilter();
            });
            layoutGroupTabs.addView(chip);
        }
    }

    private void applyChipStyle(TextView chip, boolean selected) {
        if (selected) {
            chip.setTextColor(0xFF007AFF); // iOS 蓝
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFFFFFFFF); // 选中：白底
            bg.setCornerRadius(getResources().getDisplayMetrics().density * 6);
            chip.setBackground(bg);
        } else {
            chip.setTextColor(0xFF8E8E93); // iOS 次文字
            chip.setBackground(null); // 未选中：透明，让外层容器露出来
        }
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
        SharedPreferences.Editor ed = getActivity().getSharedPreferences(PREF_GROUP_ASSIGN, Context.MODE_PRIVATE).edit();
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
                tv.setTextColor(0xFF1D1D1F); // iOS 主文字
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
                        SharedPreferences sp = getActivity().getSharedPreferences(PREF_GROUP_ASSIGN, Context.MODE_PRIVATE);
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
            if (name.isEmpty()) { Toast.makeText(getActivity(), "请输入分组名", Toast.LENGTH_SHORT).show(); return; }
            if (name.equals(GROUP_ALL) || name.equals(GROUP_LOCAL)
                    || name.equals(GROUP_UNGROUPED) || name.equals(GROUP_EXTERNAL)) {
                Toast.makeText(getActivity(), "与默认分组重名", Toast.LENGTH_SHORT).show();
                return;
            }
            if (customGroups.contains(name)) { Toast.makeText(getActivity(), "分组已存在", Toast.LENGTH_SHORT).show(); return; }
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
            Toast.makeText(getActivity(), "请先选择书籍", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> options = new ArrayList<>();
        options.add(GROUP_UNGROUPED);
        options.addAll(customGroups);
        if (customGroups.isEmpty()) {
            Toast.makeText(getActivity(), "请先在分组管理中创建自定义分组", Toast.LENGTH_LONG).show();
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
                    Toast.makeText(getActivity(), "已移动到 " + group, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null).show();
    }
}