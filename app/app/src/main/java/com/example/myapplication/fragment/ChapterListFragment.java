package com.example.myapplication.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.adapter.ChapterAdapter;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.utils.ThemeManager;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ChapterListFragment extends Fragment {

    private static final String ARG_BOOK = "book";
    private static final String ARG_CURRENT_CHAPTER = "current_chapter";

    private Book currentBook;
    private int currentChapterIndex;
    private final List<ReadActivity.Chapter> chapterList = new ArrayList<>();

    private ImageView ivCover;
    private TextView tvBookName, tvAuthor, tvChapterTotal;
    private ImageView ivSortOrder;
    private boolean isAscending = true;
    private RecyclerView rvChapters;
    private ChapterAdapter adapter;

    public static ChapterListFragment newInstance(Book book, int currentChapter) {
        ChapterListFragment fragment = new ChapterListFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_BOOK, book);
        args.putInt(ARG_CURRENT_CHAPTER, currentChapter);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            currentBook = (Book) getArguments().getSerializable(ARG_BOOK);
            currentChapterIndex = getArguments().getInt(ARG_CURRENT_CHAPTER, 0);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chapter_list, container, false);

        initView(view);
        displayBookInfo();
        setupRecyclerView();
        loadChaptersFromServer();

        return view;
    }

    private void initView(View view) {
        ivCover = view.findViewById(R.id.iv_book_cover);
        tvBookName = view.findViewById(R.id.tv_header_book_name);
        tvAuthor = view.findViewById(R.id.tv_header_author);
        tvChapterTotal = view.findViewById(R.id.tv_chapter_total);
        rvChapters = view.findViewById(R.id.rv_chapters);

        // 排序切换按钮
        ivSortOrder = view.findViewById(R.id.iv_sort_order);
        // 根据主题设置图标颜色
        int currentTheme = ThemeManager.getCurrentTheme(requireContext());
        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            ivSortOrder.setImageResource(R.drawable.ic_sort_asc);
        } else {
            ivSortOrder.setImageResource(R.drawable.ic_sort_asc_white);
        }
        ivSortOrder.setOnClickListener(v -> toggleSortOrder());
    }

    private void toggleSortOrder() {
        isAscending = !isAscending;
        Collections.reverse(chapterList);
        adapter.notifyDataSetChanged();

        // 更新图标
        int currentTheme = ThemeManager.getCurrentTheme(requireContext());
        boolean isLightTheme = (currentTheme == ThemeManager.THEME_SEASIDE);
        if (isAscending) {
            ivSortOrder.setImageResource(isLightTheme ? R.drawable.ic_sort_asc : R.drawable.ic_sort_asc_white);
        } else {
            ivSortOrder.setImageResource(isLightTheme ? R.drawable.ic_sort_desc : R.drawable.ic_sort_desc_white);
        }

        // 切换到新排序后滚动到列表顶部
        rvChapters.scrollToPosition(0);
    }

    private void displayBookInfo() {
        if (currentBook != null) {
            tvBookName.setText(currentBook.getTitle());
            tvAuthor.setText("作者：" + currentBook.getAuthor());

            // 加载书籍封面
            String coverUrl = currentBook.getCover();
            if (coverUrl != null && !coverUrl.isEmpty()) {
                String fullCoverUrl = RetrofitClient.getFullImageUrl(coverUrl);
                Glide.with(this)
                        .load(fullCoverUrl)
                        .placeholder(R.drawable.default_book_cover)
                        .error(R.drawable.default_book_cover)
                        .into(ivCover);
            } else {
                ivCover.setImageResource(R.drawable.default_book_cover);
            }
        }
    }

    private void loadChaptersFromServer() {
        long bookId = currentBook.getId();
        RetrofitClient.getApiService().getChapters(bookId).enqueue(new Callback<ApiResponse<List<ChapterDto>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<ChapterDto>>> call, Response<ApiResponse<List<ChapterDto>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    List<ChapterDto> list = response.body().getData();
                    chapterList.clear();
                    if (list != null) {
                        for (ChapterDto dto : list) {
                            ReadActivity.Chapter chapter = new ReadActivity.Chapter();
                            chapter.setIndex(dto.getSortOrder() - 1);
                            chapter.setTitle(dto.getTitle());
                            chapterList.add(chapter);
                        }
                    }
                    tvChapterTotal.setText("共 " + chapterList.size() + " 章");
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<List<ChapterDto>>> call, Throwable t) {
                Toast.makeText(getContext(), "加载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupRecyclerView() {
        rvChapters.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ChapterAdapter(chapterList, currentChapterIndex);
        rvChapters.setAdapter(adapter);

        adapter.setOnChapterClickListener(chapter -> {
            Intent intent = new Intent(getActivity(), ReadActivity.class);
            intent.putExtra("book", currentBook);
            intent.putExtra("chapterIndex", chapter.getIndex());
            startActivity(intent);
            if (getActivity() != null) {
                getActivity().finish();
            }
        });
    }
}