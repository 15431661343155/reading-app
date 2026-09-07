package com.example.myapplication.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.bean.Book;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 外站书籍章节目录页。
 * 从 BookDetailActivity 传入 Book + chapters (String[][]，每项 [title, url])。
 * 点击章节跳转 ReadActivity（外站模式）。
 * 高亮当前阅读章节并自动滚动定位。
 */
public class OnlineChapterListActivity extends AppCompatActivity {

    private RecyclerView rvChapters;
    private TextView tvChapterCount;
    private List<String[]> chapters = new ArrayList<>();
    private Book book;
    private int currentChapterIndex = -1;
    private LinearLayoutManager layoutManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online_chapter_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvChapterCount = findViewById(R.id.tv_chapter_count);
        rvChapters = findViewById(R.id.rv_chapters);

        book = (Book) getIntent().getSerializableExtra("book");
        if (book == null) {
            android.widget.Toast.makeText(this, "书籍信息缺失", android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Intent extra 优先 → 共享缓存兜底 → 网络异步拉取
        String[][] arr = (String[][]) getIntent().getSerializableExtra("chapters");
        if (arr != null && arr.length > 0) {
            chapters = new ArrayList<>(Arrays.asList(arr));
        } else {
            List<String[]> cached = BookDetailActivity.getExternalChapters(this, book);
            if (cached != null && !cached.isEmpty()) {
                chapters = new ArrayList<>(cached);
            }
        }

        if (chapters.isEmpty()
                && book.getSourceType() != null && book.getSourceUrl() != null) {
            // 缓存也没命中：后台拉取，等 API 返回再刷新 UI
            tvChapterCount.setText("加载中...");
            final String st = book.getSourceType();
            final String sb = book.getSourceUrl();
            com.example.myapplication.api.RetrofitClient.getApiService()
                    .getOnlineChapterList(st, sb)
                    .enqueue(new retrofit2.Callback<com.example.myapplication.bean.ApiResponse<java.util.List<String[]>>>() {
                        @Override
                        public void onResponse(@NonNull retrofit2.Call<com.example.myapplication.bean.ApiResponse<java.util.List<String[]>>> call,
                                               @NonNull retrofit2.Response<com.example.myapplication.bean.ApiResponse<java.util.List<String[]>>> response) {
                            boolean ok = response.isSuccessful() && response.body() != null
                                    && response.body().isSuccess()
                                    && response.body().getData() != null
                                    && !response.body().getData().isEmpty();
                            if (ok) {
                                List<String[]> list = response.body().getData();
                                BookDetailActivity.putExternalChapters(OnlineChapterListActivity.this, book, list);
                                runOnUiThread(() -> {
                                    chapters = new ArrayList<>(list);
                                    setupAfterChaptersReady();
                                });
                            } else {
                                String msg = response.body() != null ? response.body().getMessage() : "获取章节失败";
                                if (msg == null || msg.isEmpty()) msg = "获取章节失败";
                                final String fmsg = msg;
                                runOnUiThread(() -> showErrorDialog(fmsg));
                            }
                        }
                        @Override
                        public void onFailure(@NonNull retrofit2.Call<com.example.myapplication.bean.ApiResponse<java.util.List<String[]>>> call,
                                              @NonNull Throwable t) {
                            runOnUiThread(() -> showErrorDialog("网络错误：" + t.getMessage()));
                        }
                    });
        }

        // 读取已保存的当前章节索引
        String sourceType = book.getSourceType();
        String sourceBookId = book.getSourceUrl();
        if (sourceType != null && sourceBookId != null) {
            String recordKey = "ext_" + sourceType + "|" + sourceBookId;
            SharedPreferences sp = getSharedPreferences("external_reading_records", MODE_PRIVATE);
            currentChapterIndex = sp.getInt(recordKey + "_chapterIndex", -1);
        }

        if (getSupportActionBar() != null && book.getTitle() != null) {
            getSupportActionBar().setTitle(book.getTitle());
        }

        layoutManager = new LinearLayoutManager(this);
        rvChapters.setLayoutManager(layoutManager);
        rvChapters.setAdapter(new ChapterAdapter());

        setupAfterChaptersReady();
    }

    private void setupAfterChaptersReady() {
        tvChapterCount.setText("共" + chapters.size() + "章");
        ChapterAdapter adapter = (ChapterAdapter) rvChapters.getAdapter();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (currentChapterIndex >= 0 && currentChapterIndex < chapters.size()) {
            final int target = Math.max(0, currentChapterIndex - 2);
            rvChapters.post(() -> layoutManager.scrollToPositionWithOffset(target, 0));
        }
    }

    private void showErrorDialog(String reason) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("加载失败")
                .setMessage("原因：" + reason + "\n请稍后重试。")
                .setCancelable(false)
                .setPositiveButton("返回", (d, w) -> finish())
                .setNegativeButton("重试", (d, w) -> {
                    if (book != null && book.getSourceType() != null && book.getSourceUrl() != null) {
                        tvChapterCount.setText("加载中...");
                        chapters.clear();
                        ChapterAdapter adapter = (ChapterAdapter) rvChapters.getAdapter();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        com.example.myapplication.api.RetrofitClient.getApiService()
                                .getOnlineChapterList(book.getSourceType(), book.getSourceUrl())
                                .enqueue(new retrofit2.Callback<com.example.myapplication.bean.ApiResponse<java.util.List<String[]>>>() {
                                    @Override
                                    public void onResponse(@NonNull retrofit2.Call<com.example.myapplication.bean.ApiResponse<java.util.List<String[]>>> call,
                                                           @NonNull retrofit2.Response<com.example.myapplication.bean.ApiResponse<java.util.List<String[]>>> response) {
                                        boolean ok = response.isSuccessful() && response.body() != null
                                                && response.body().isSuccess() && response.body().getData() != null
                                                && !response.body().getData().isEmpty();
                                        if (ok) {
                                            List<String[]> list = response.body().getData();
                                            BookDetailActivity.putExternalChapters(OnlineChapterListActivity.this, book, list);
                                            runOnUiThread(() -> {
                                                chapters = new ArrayList<>(list);
                                                setupAfterChaptersReady();
                                            });
                                        } else {
                                            String m = response.body() != null ? response.body().getMessage() : "获取章节失败";
                                            if (m == null || m.isEmpty()) m = "获取章节失败";
                                            final String fm = m;
                                            runOnUiThread(() -> showErrorDialog(fm));
                                        }
                                    }
                                    @Override
                                    public void onFailure(@NonNull retrofit2.Call<com.example.myapplication.bean.ApiResponse<java.util.List<String[]>>> call,
                                                          @NonNull Throwable t) {
                                        runOnUiThread(() -> showErrorDialog("网络错误：" + t.getMessage()));
                                    }
                                });
                    } else {
                        finish();
                    }
                })
                .show();
    }

    private class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(OnlineChapterListActivity.this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            int pad = (int) (getResources().getDisplayMetrics().density * 14);
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextSize(15);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String[] ch = chapters.get(position);
            String title = ch.length > 0 ? ch[0] : "第" + (position + 1) + "章";
            boolean isCurrent = (position == currentChapterIndex);
            ((TextView) holder.itemView).setText((position + 1) + ". " + title);

            if (isCurrent) {
                // 当前阅读章节：iOS 蓝高亮
                holder.itemView.setBackgroundColor(0x14007AFF);
                ((TextView) holder.itemView).setTextColor(0xFF007AFF);
                ((TextView) holder.itemView).setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else {
                holder.itemView.setBackgroundColor(0x00000000);
                ((TextView) holder.itemView).setTextColor(0xFF1D1D1F);
                ((TextView) holder.itemView).setTypeface(android.graphics.Typeface.DEFAULT);
            }

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(OnlineChapterListActivity.this, ReadActivity.class);
                intent.putExtra("book", book);
                intent.putExtra("isExternal", true);
                // 小书走 Intent，大书走共享缓存（始终写缓存，失败兜底）
                BookDetailActivity.putChaptersExtra(OnlineChapterListActivity.this, intent, book, chapters);
                intent.putExtra("chapterIndex", position);
                try { startActivity(intent); }
                catch (RuntimeException te) {
                    Intent fallback = new Intent(OnlineChapterListActivity.this, ReadActivity.class);
                    fallback.putExtra("book", book);
                    fallback.putExtra("isExternal", true);
                    fallback.putExtra("chaptersViaCache", true);
                    fallback.putExtra("chapterIndex", position);
                    startActivity(fallback);
                }
            });
        }

        @Override
        public int getItemCount() {
            return chapters.size();
        }

        class VH extends RecyclerView.ViewHolder {
            VH(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
