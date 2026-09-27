package com.example.myapplication.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.widget.ImageView;

import com.example.myapplication.R;
import com.example.myapplication.adapter.ReadingRecordAdapter;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.activity.BookDetailActivity;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.ReadingProgress;
import com.example.myapplication.bean.ReadingRecord;
import com.example.myapplication.utils.ExternalPrefs;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.Hint;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 阅读记录页面
 */
public class ReadingRecordActivity extends BaseActivity {

    private FastScrollRecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private TextView tvEmpty, tvEdit, tvCancel, tvSelectAll;
    private ImageView ivDelete;
    private View layoutTitle, layoutEditTitle, layoutBottomDelete;
    private ReadingRecordAdapter adapter;
    private final List<ReadingRecord> records = new ArrayList<>();
    private boolean isEditMode = false;
    private boolean isAllSelected = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reading_record);

        // 不再手动加状态栏padding：setDecorFits(true) 已让系统自动避让
        View headerLayout = findViewById(R.id.layout_header);
        if (headerLayout != null) {
            headerLayout.setBackgroundColor(getResources().getColor(R.color.ios_bg_grouped, null));
        }

        // 状态栏颜色与页面顶部统一
        getWindow().setStatusBarColor(getResources().getColor(R.color.ios_bg_grouped, null));
        setLightStatusBar(true);

        recyclerView = findViewById(R.id.recycler_view);
        tvEmpty = findViewById(R.id.tv_empty);
        tvEdit = findViewById(R.id.tv_edit);
        tvCancel = findViewById(R.id.tv_cancel);
        tvSelectAll = findViewById(R.id.tv_select_all);
        ivDelete = findViewById(R.id.iv_delete);
        layoutTitle = findViewById(R.id.layout_title);
        layoutEditTitle = findViewById(R.id.layout_edit_title);
        layoutBottomDelete = findViewById(R.id.layout_bottom_delete);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ReadingRecordAdapter(this, records);
        adapter.setOnItemClickListener(new ReadingRecordAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(ReadingRecord record) {
                if (isEditMode) {
                    adapter.toggleSelect(record);
                    updateSelectAllText();
                } else {
                    openBook(record);
                }
            }

            @Override
            public void onItemLongClick(ReadingRecord record) {
                if (!isEditMode) {
                    enterEditMode();
                    adapter.toggleSelect(record);
                    updateSelectAllText();
                }
            }
        });

        recyclerView.setAdapter(adapter);

        // 下拉刷新
        swipeRefresh = findViewById(R.id.swipe_refresh_reading);
        swipeRefresh.setColorSchemeResources(
                R.color.purple_500, R.color.cyan_400, R.color.teal_200);
        swipeRefresh.setOnRefreshListener(() -> {
            loadRecords();
            swipeRefresh.setRefreshing(false);
        });

        // 返回按钮
        findViewById(R.id.iv_back).setOnClickListener(v -> {
            if (isEditMode) exitEditMode();
            else finish();
        });

        tvEdit.setOnClickListener(v -> enterEditMode());
        tvCancel.setOnClickListener(v -> exitEditMode());

        tvSelectAll.setOnClickListener(v -> {
            isAllSelected = !isAllSelected;
            if (isAllSelected) {
                adapter.selectAll();
                tvSelectAll.setText("取消全选");
            } else {
                adapter.clearSelection();
                tvSelectAll.setText("全选");
            }
        });

        ivDelete.setOnClickListener(v -> {
            List<ReadingRecord> selected = adapter.getSelectedItems();
            if (selected.isEmpty()) {
                Hint.show(this, "请选择要删除的记录");
                return;
            }
            // 同步删除后台记录
            String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
            long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
            for (ReadingRecord r : selected) {
                if (r.isExternal()) {
                    // 外站书籍：从 external_reading_records 删除
                    clearExternalRecord(r);
                } else {
                    // 服务器/本地书籍：从 reading_records 删除
                    clearLocalRecord(r.getBookId());
                }

                // 删除服务器阅读进度
                if (userId > 0 && r.getBookId() > 0 && !r.isExternal()) {
                    RetrofitClient.getApiService().deleteProgress(userId, r.getBookId())
                            .enqueue(new Callback<ApiResponse<Void>>() {
                                @Override
                                public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {}
                                @Override
                                public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {}
                            });
                }
            }
            records.removeAll(selected);
            adapter.clearSelection();
            saveAllRecords();
            adapter.notifyDataSetChanged();
            updateEmptyView();
            exitEditMode();
            Hint.show(this, "已删除");
        });

        loadRecords();
    }

    /** 外站数据拉取完成后刷新列表（登录后 pullAll 是异步的，此前的加载会读不到数据）。 */
    private final ExternalSyncManager.PullListener pullListener = () -> {
        if (!isFinishing() && !isDestroyed()) loadRecords();
    };

    @Override
    protected void onResume() {
        super.onResume();
        loadRecords();
        ExternalSyncManager.getInstance(this).addPullListener(pullListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ExternalSyncManager.getInstance(this).removePullListener(pullListener);
    }

    private void clearLocalRecord(long bookId) {
        SharedPreferences sp = getSharedPreferences("reading_records", MODE_PRIVATE);
        int count = sp.getInt("record_count", 0);
        SharedPreferences.Editor editor = sp.edit();

        // 重建记录列表，跳过要删除的
        List<ReadingRecord> tempRecords = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long bid = sp.getLong("record_bookId_" + i, 0);
            if (bid != bookId) {
                ReadingRecord r = new ReadingRecord();
                r.setBookId(bid);
                r.setBookName(sp.getString("record_bookName_" + i, ""));
                r.setAuthor(sp.getString("record_author_" + i, ""));
                r.setCover(sp.getString("record_cover_" + i, ""));
                r.setChapterIndex(sp.getInt("record_chapterIndex_" + i, 0));
                r.setChapterTitle(sp.getString("record_chapterTitle_" + i, ""));
                r.setTotalChapters(sp.getInt("record_totalChapters_" + i, 0));
                r.setReadTime(sp.getLong("record_readTime_" + i, 0));
                r.setProgress(sp.getInt("record_progress_" + i, 0));
                r.setChapterProgress(sp.getInt("record_chapterProgress_" + i, 0));
                tempRecords.add(r);
            }
        }

        // 重新写入
        editor.clear();
        editor.putInt("record_count", tempRecords.size());
        for (int i = 0; i < tempRecords.size(); i++) {
            ReadingRecord r = tempRecords.get(i);
            editor.putLong("record_bookId_" + i, r.getBookId());
            editor.putString("record_bookName_" + i, r.getBookName());
            editor.putString("record_author_" + i, r.getAuthor());
            editor.putString("record_cover_" + i, r.getCover());
            editor.putInt("record_chapterIndex_" + i, r.getChapterIndex());
            editor.putString("record_chapterTitle_" + i, r.getChapterTitle());
            editor.putLong("record_readTime_" + i, r.getReadTime());
            editor.putInt("record_progress_" + i, r.getProgress());
            editor.putInt("record_chapterProgress_" + i, r.getChapterProgress());
        }
        editor.apply();
    }

    /**
     * 清除外站书籍的本地阅读记录
     */
    private void clearExternalRecord(ReadingRecord record) {
        if (!record.isExternal() || record.getSourceType() == null || record.getSourceUrl() == null) return;
        String prefix = "ext_" + record.getSourceType() + "|" + record.getSourceUrl();
        SharedPreferences extSp = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
        SharedPreferences.Editor editor = extSp.edit();
        // 删除该外站书的所有相关 key
        java.util.Map<String, ?> all = extSp.getAll();
        if (all != null) {
            for (String key : all.keySet()) {
                if (key.startsWith(prefix + "_")) {
                    editor.remove(key);
                }
            }
        }
        editor.apply();

        // ✅ 清理旧版本遗留：reading_records 中可能被错误写入的同名外站书脏条目（bookId=0 + 相同书名作者）
        purgeDirtyExternalFromReadingRecords(record.getBookName(), record.getAuthor());

        // ✅ 同步删除服务器侧阅读记录：否则下次启动 pullAll() 会把它重新拉回本地（"删了又出现"）
        ExternalSyncManager.getInstance(this)
                .deleteReadingRecordRemote(record.getSourceType(), record.getSourceUrl());
    }

    /**
     * 从 reading_records SP 中清除被错误写入的外站书脏条目
     * （老版本 saveAllRecords() 未过滤外站书，导致 external 记录被写进 reading_records，bookId=0）
     */
    private void purgeDirtyExternalFromReadingRecords(String bookName, String author) {
        if (bookName == null) return;
        SharedPreferences sp = getSharedPreferences("reading_records", MODE_PRIVATE);
        int count = sp.getInt("record_count", 0);
        List<ReadingRecord> remain = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < count; i++) {
            long bid = sp.getLong("record_bookId_" + i, 0);
            String bn = sp.getString("record_bookName_" + i, "");
            String au = sp.getString("record_author_" + i, "");
            // bookId<=0 且 书名+作者匹配 → 是外站书脏条目，丢弃
            if (bid <= 0 && bookName.equals(bn)
                    && ((author == null && (au == null || au.isEmpty()))
                        || (author != null && author.equals(au)))) {
                changed = true;
                continue;
            }
            ReadingRecord r = new ReadingRecord();
            r.setBookId(bid);
            r.setBookName(bn);
            r.setAuthor(au);
            r.setCover(sp.getString("record_cover_" + i, ""));
            r.setChapterIndex(sp.getInt("record_chapterIndex_" + i, 0));
            r.setChapterTitle(sp.getString("record_chapterTitle_" + i, ""));
            r.setTotalChapters(sp.getInt("record_totalChapters_" + i, 0));
            r.setReadTime(sp.getLong("record_readTime_" + i, 0));
            r.setProgress(sp.getInt("record_progress_" + i, 0));
            r.setChapterProgress(sp.getInt("record_chapterProgress_" + i, 0));
            remain.add(r);
        }
        if (changed) {
            SharedPreferences.Editor ed = sp.edit();
            ed.clear();
            ed.putInt("record_count", remain.size());
            for (int i = 0; i < remain.size(); i++) {
                ReadingRecord r = remain.get(i);
                ed.putLong("record_bookId_" + i, r.getBookId());
                ed.putString("record_bookName_" + i, r.getBookName());
                ed.putString("record_author_" + i, r.getAuthor());
                ed.putString("record_cover_" + i, r.getCover());
                ed.putInt("record_chapterIndex_" + i, r.getChapterIndex());
                ed.putString("record_chapterTitle_" + i, r.getChapterTitle());
                ed.putInt("record_totalChapters_" + i, r.getTotalChapters());
                ed.putLong("record_readTime_" + i, r.getReadTime());
                ed.putInt("record_progress_" + i, r.getProgress());
                ed.putInt("record_chapterProgress_" + i, r.getChapterProgress());
            }
            ed.apply();
        }
    }

    private void enterEditMode() {
        isEditMode = true;

        // 先测量视图尺寸（可见前测量会返回0）
        layoutEditTitle.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float topHeight = layoutEditTitle.getMeasuredHeight();
        layoutBottomDelete.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float bottomHeight = layoutBottomDelete.getMeasuredHeight();

        // 上标题框：从上方滑出
        layoutEditTitle.setTranslationY(-topHeight);
        layoutEditTitle.setVisibility(View.VISIBLE);
        layoutEditTitle.animate()
                .translationY(0)
                .setDuration(200)
                .start();

        // 下标题框：从下方滑入
        layoutBottomDelete.setTranslationY(bottomHeight);
        layoutBottomDelete.setVisibility(View.VISIBLE);
        layoutBottomDelete.animate()
                .translationY(0)
                .setDuration(200)
                .start();

        tvSelectAll.setText("全选");
        isAllSelected = false;
        adapter.setEditMode(true);
    }

    private void exitEditMode() {
        isEditMode = false;

        layoutEditTitle.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float topHeight = layoutEditTitle.getMeasuredHeight();
        layoutBottomDelete.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float bottomHeight = layoutBottomDelete.getMeasuredHeight();

        layoutEditTitle.animate()
                .translationY(-topHeight)
                .setDuration(200)
                .withEndAction(() -> layoutEditTitle.setVisibility(View.GONE))
                .start();

        // 下标题框：向下方滑出
        layoutBottomDelete.animate()
                .translationY(bottomHeight)
                .setDuration(200)
                .withEndAction(() -> layoutBottomDelete.setVisibility(View.GONE))
                .start();

        adapter.setEditMode(false);
        adapter.clearSelection();
    }

    private void updateSelectAllText() {
        if (adapter.getSelectedCount() == records.size() && records.size() > 0) {
            tvSelectAll.setText("取消全选");
            isAllSelected = true;
        } else {
            tvSelectAll.setText("全选");
            isAllSelected = false;
        }
    }

    /**
     * 加载阅读记录
     */
    private void loadRecords() {
        // 旧版全局外站数据迁移到当前登录用户命名空间（幂等，未登录则跳过）
        ExternalPrefs.migrateIfNeeded(this);
        // 阅读记录本地保存，游客模式也应展示（后端无独立历史接口，以本地为准）
        loadLocalRecords();
    }

    /**
     * 从本地加载阅读记录
     */
    private void loadLocalRecords() {
        records.clear();

        // ===== 1. 读取服务器/本地书籍的阅读记录 =====
        SharedPreferences sp = getSharedPreferences("reading_records", MODE_PRIVATE);
        int count = sp.getInt("record_count", 0);
        List<ReadingRecord> validRecords = new ArrayList<>();  // 仅存有效记录
        boolean hasDirty = false;
        for (int i = 0; i < count; i++) {
            ReadingRecord record = new ReadingRecord();
            long bid = sp.getLong("record_bookId_" + i, 0);
            record.setBookId(bid);
            record.setBookName(sp.getString("record_bookName_" + i, ""));
            record.setAuthor(sp.getString("record_author_" + i, ""));
            record.setCover(sp.getString("record_cover_" + i, ""));
            record.setChapterIndex(sp.getInt("record_chapterIndex_" + i, 0));
            record.setChapterTitle(sp.getString("record_chapterTitle_" + i, ""));
            record.setTotalChapters(sp.getInt("record_totalChapters_" + i, 0));
            record.setReadTime(sp.getLong("record_readTime_" + i, 0));
            record.setProgress(sp.getInt("record_progress_" + i, 0));
            record.setChapterProgress(sp.getInt("record_chapterProgress_" + i, 0));
            // ✅ 过滤外站书籍脏数据（bookId==0 说明是之前被错误写入 reading_records 的外站书）
            if (bid <= 0) {
                hasDirty = true;
                continue;
            }
            validRecords.add(record);
        }
        // 有脏条目 → 立即清理 SP 中的残留（防止下次继续污染）
        if (hasDirty) {
            SharedPreferences.Editor ed = sp.edit();
            ed.clear();
            ed.putInt("record_count", validRecords.size());
            for (int i = 0; i < validRecords.size(); i++) {
                ReadingRecord r = validRecords.get(i);
                ed.putLong("record_bookId_" + i, r.getBookId());
                ed.putString("record_bookName_" + i, r.getBookName());
                ed.putString("record_author_" + i, r.getAuthor());
                ed.putString("record_cover_" + i, r.getCover());
                ed.putInt("record_chapterIndex_" + i, r.getChapterIndex());
                ed.putString("record_chapterTitle_" + i, r.getChapterTitle());
                ed.putInt("record_totalChapters_" + i, r.getTotalChapters());
                ed.putLong("record_readTime_" + i, r.getReadTime());
                ed.putInt("record_progress_" + i, r.getProgress());
                ed.putInt("record_chapterProgress_" + i, r.getChapterProgress());
            }
            ed.apply();
        }
        records.addAll(validRecords);

        // ===== 2. 额外读取外站书籍的阅读记录 =====
        loadExternalRecords();

        // 按时间倒序
        records.sort((a, b) -> Long.compare(b.getReadTime(), a.getReadTime()));

        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    /**
     * 从 external_reading_records 读取外站书籍的阅读记录并合并到列表
     */
    private void loadExternalRecords() {
        SharedPreferences extSp = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
        java.util.Map<String, ?> all = extSp.getAll();
        if (all == null || all.isEmpty()) return;

        // 收集所有唯一的外站书 key 前缀
        java.util.Set<String> seenPrefixes = new java.util.HashSet<>();
        for (String key : all.keySet()) {
            // 找 chapterIndex 作为唯一键（每个书只有一个 chapterIndex）
            if (key.startsWith("ext_") && key.endsWith("_chapterIndex")) {
                String prefix = key.substring(0, key.length() - "_chapterIndex".length());
                seenPrefixes.add(prefix);
            }
        }

        // 用 prefix 重建每条记录
        for (String prefix : seenPrefixes) {
            ReadingRecord record = new ReadingRecord();
            record.setExternal(true);
            record.setSourceType(extSp.getString(prefix + "_sourceType", ""));
            record.setSourceUrl(extSp.getString(prefix + "_sourceUrl", ""));
            record.setBookName(extSp.getString(prefix + "_bookName", ""));
            record.setAuthor(extSp.getString(prefix + "_author", ""));
            record.setCover(extSp.getString(prefix + "_cover", ""));
            record.setChapterIndex(extSp.getInt(prefix + "_chapterIndex", 0));
            record.setChapterTitle(extSp.getString(prefix + "_chapterTitle", ""));
            record.setChapterProgress(extSp.getInt(prefix + "_page", 0));
            record.setReadTime(extSp.getLong(prefix + "_readTime", 0));
            // 外站书没有数据库 bookId，用 0 标记
            record.setBookId(0);
            records.add(record);
        }
    }

    /**
     * 打开书籍继续阅读
     */
    private void openBook(ReadingRecord record) {
        // ===== 外站书籍走外站分支 =====
        if (record.isExternal()) {
            Book book = new Book();
            book.setBookName(record.getBookName());
            book.setAuthor(record.getAuthor());
            book.setCover(record.getCover());
            book.setSourceType(record.getSourceType());
            book.setSourceUrl(record.getSourceUrl());

            // 复用书城可靠取章节列表逻辑，带列表进阅读器，避免阅读器内异步拉取偶发卡"正在加载"
            final int idx = record.getChapterIndex();
            List<String[]> cached = BookDetailActivity.getExternalChapters(this, book);
            if (cached != null && !cached.isEmpty()) {
                launchExternalRead(book, cached, idx);
            } else {
                BookDetailActivity.fetchOnlineChapters(this, book, new BookDetailActivity.ChaptersCallback() {
                    @Override
                    public void onSuccess(List<String[]> chapters) {
                        launchExternalRead(book, chapters, idx);
                    }
                    @Override
                    public void onFail(String msg, boolean sourceMayDown) {
                        Hint.show(ReadingRecordActivity.this, sourceMayDown ? "书源暂不可用，请稍后重试" : msg);
                    }
                });
            }
            return;
        }

        // ===== 本地/服务器书籍走原有逻辑 =====
        Book book = new Book();
        book.setId((long) record.getBookId());
        book.setBookName(record.getBookName());
        book.setAuthor(record.getAuthor());
        book.setCover(record.getCover());

        // ✅ 关键修复：检测是否为本地书（通过 local_books SharedPreferences）
        SharedPreferences spLocal = getSharedPreferences("local_books", MODE_PRIVATE);
        int localBookCount = spLocal.getInt("count", 0);
        boolean isLocalBook = false;
        for (int i = 0; i < localBookCount; i++) {
            if (spLocal.getLong("book_id_" + i, 0) == record.getBookId()) {
                isLocalBook = true;
                book.setStatus(-1);  // 标记为本地书
                break;
            }
        }

        Intent intent = new Intent(this, ReadActivity.class);
        intent.putExtra("book", book);
        intent.putExtra("chapterIndex", record.getChapterIndex());
        intent.putExtra("chapterProgress", record.getChapterProgress());
        intent.putExtra("isLocal", isLocalBook);
        startActivity(intent);
    }

    /** 带章节列表进入外站阅读器（小书走 Intent，大书走共享缓存，与书城一致）。 */
    private void launchExternalRead(Book book, List<String[]> chapters, int chapterIndex) {
        Intent intent = new Intent(this, ReadActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("book", book);
        intent.putExtra("isExternal", true);
        if (chapterIndex >= 0) intent.putExtra("chapterIndex", chapterIndex);
        BookDetailActivity.putChaptersExtra(this, intent, book, chapters);
        startActivity(intent);
    }

    /**
     * 保存所有记录到本地（仅保存非外站书籍，外站书由 external_reading_records 独立管理）
     */
    private void saveAllRecords() {
        SharedPreferences sp = getSharedPreferences("reading_records", MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        int saveIndex = 0;
        for (int i = 0; i < records.size(); i++) {
            ReadingRecord r = records.get(i);
            // ✅ 关键：外站书籍不写入 reading_records（否则删除时无法清理，造成重复条目）
            if (r.isExternal()) continue;
            editor.putLong("record_bookId_" + saveIndex, r.getBookId());
            editor.putString("record_bookName_" + saveIndex, r.getBookName());
            editor.putString("record_author_" + saveIndex, r.getAuthor());
            editor.putString("record_cover_" + saveIndex, r.getCover());
            editor.putInt("record_chapterIndex_" + saveIndex, r.getChapterIndex());
            editor.putString("record_chapterTitle_" + saveIndex, r.getChapterTitle());
            editor.putInt("record_totalChapters_" + saveIndex, r.getTotalChapters());
            editor.putLong("record_readTime_" + saveIndex, r.getReadTime());
            editor.putInt("record_progress_" + saveIndex, r.getProgress());
            editor.putInt("record_chapterProgress_" + saveIndex, r.getChapterProgress());
            saveIndex++;
        }
        editor.putInt("record_count", saveIndex);
        editor.apply();
    }

    private void updateEmptyView() {
        if (records.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }
    }
}
