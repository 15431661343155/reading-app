package com.example.myapplication.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.myapplication.R;
import com.example.myapplication.adapter.BookAdapter;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.PageResponse;
import com.example.myapplication.utils.Hint;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 子分类书单页：展示某个子分类下的书籍（按阅读量 + 点赞的热度倒序）。
 *
 * <p>入口是「分类」页右侧的子分类卡片；主分类也会一起带上（后端按主/子分类联合筛选，
 * 同时兼容「只填了子分类、没填主分类」的历史数据）。
 */
public class CategoryBooksActivity extends BaseActivity {

    public static final String EXTRA_MAIN_CATEGORY = "extra_main_category";
    public static final String EXTRA_SUB_CATEGORY = "extra_sub_category";

    /** 一页拉取的书籍数（与书城列表保持一致） */
    private static final int PAGE_SIZE = 50;

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView rvBooks;
    private TextView tvEmpty;
    private BookAdapter adapter;

    private final List<Book> books = new ArrayList<>();

    private String mainCategory = "";
    private String subCategory = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category_books);

        mainCategory = safe(getIntent().getStringExtra(EXTRA_MAIN_CATEGORY));
        subCategory = safe(getIntent().getStringExtra(EXTRA_SUB_CATEGORY));
        // 只传了主分类也要能查（此时按主分类列全部）
        if (subCategory.isEmpty() && mainCategory.isEmpty()) {
            finish();
            return;
        }

        bindViews();
        loadBooks();
    }

    private void bindViews() {
        TextView tvTitle = findViewById(R.id.tv_category_title);
        tvTitle.setText(subCategory.isEmpty() ? mainCategory : subCategory);

        ImageView ivBack = findViewById(R.id.iv_back);
        ivBack.setOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tv_books_empty);

        rvBooks = findViewById(R.id.rv_category_books);
        rvBooks.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BookAdapter(this, books);
        adapter.setOnItemClickListener(book -> {
            Intent i = new Intent(this, BookDetailActivity.class);
            i.putExtra("book", book);
            startActivity(i);
        });
        rvBooks.setAdapter(adapter);

        swipeRefresh = findViewById(R.id.swipe_refresh_books);
        swipeRefresh.setColorSchemeResources(R.color.purple_500, R.color.cyan_400, R.color.teal_200);
        swipeRefresh.setOnRefreshListener(this::loadBooks);
    }

    /** 拉取该分类下的书籍 */
    private void loadBooks() {
        swipeRefresh.setRefreshing(true);
        RetrofitClient.getApiService().getFictionBooks(
                        0, PAGE_SIZE, null,
                        mainCategory.isEmpty() ? null : mainCategory,
                        subCategory.isEmpty() ? null : subCategory,
                        "hot")
                .enqueue(new Callback<ApiResponse<PageResponse<Book>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<PageResponse<Book>>> c,
                                           Response<ApiResponse<PageResponse<Book>>> r) {
                        swipeRefresh.setRefreshing(false);
                        books.clear();
                        if (r.isSuccessful() && r.body() != null && r.body().isSuccess()
                                && r.body().getData() != null && r.body().getData().getContent() != null) {
                            books.addAll(r.body().getData().getContent());
                        } else {
                            Hint.show(CategoryBooksActivity.this, "加载失败，请稍后重试");
                        }
                        adapter.notifyDataSetChanged();
                        tvEmpty.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<PageResponse<Book>>> c, Throwable t) {
                        swipeRefresh.setRefreshing(false);
                        books.clear();
                        adapter.notifyDataSetChanged();
                        tvEmpty.setVisibility(View.VISIBLE);
                        Hint.show(CategoryBooksActivity.this, "网络错误: " + t.getMessage());
                    }
                });
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
