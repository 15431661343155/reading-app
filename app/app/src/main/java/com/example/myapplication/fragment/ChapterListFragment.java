package com.example.myapplication.fragment;

import android.content.Intent;
import android.content.SharedPreferences;
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
import com.example.myapplication.utils.TocOrder;

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
    private TextView btnTocSort;
    private final TocOrder order = new TocOrder();
    private boolean isLocalBook = false;
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
        // 本地导入的书：chapter 信息只存在于本地 SP，服务器无记录，必须本地加载
        isLocalBook = detectLocalBook();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chapter_list, container, false);

        initView(view);
        displayBookInfo();
        setupRecyclerView();
        if (isLocalBook) {
            loadLocalChapters();
        } else {
            loadChaptersFromServer();
        }

        return view;
    }

    private void initView(View view) {
        ivCover = view.findViewById(R.id.iv_book_cover);
        tvBookName = view.findViewById(R.id.tv_header_book_name);
        tvAuthor = view.findViewById(R.id.tv_header_author);
        tvChapterTotal = view.findViewById(R.id.tv_chapter_total);
        rvChapters = view.findViewById(R.id.rv_chapters);

        // 排序切换按钮（与阅读器目录一致：文字按钮，标签随顺序变化）
        btnTocSort = view.findViewById(R.id.btn_toc_sort);
        btnTocSort.setText(TocOrder.label(order.isDescending()));
        btnTocSort.setOnClickListener(v -> toggleSortOrder());
    }

    private void toggleSortOrder() {
        order.toggle();
        // 反转列表展示顺序；章节对象携带真实 index，点击仍回传真实 chapterIndex，不受影响
        Collections.reverse(chapterList);
        adapter.notifyDataSetChanged();

        // 更新按钮文案（正序/倒序）
        btnTocSort.setText(TocOrder.label(order.isDescending()));

        // 切换到新排序后焦点回到第一行（正序=第一章，倒序=最后一章）
        TocOrder.scrollToFirstRow(rvChapters);
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

    /**
     * 判断当前书是否为「本地导入」书：其 id 在 local_books SP 中有对应记录，
     * 且不是外站书（外站书 id 为 null，不会命中）。命中则章节须从本地 SP 取，不能查服务器。
     */
    private boolean detectLocalBook() {
        if (currentBook == null || currentBook.getId() == null || currentBook.getId() <= 0) return false;
        // 外站书（sourceType/sourceUrl 非空）即使 id 异常也不能误判为本地书
        if (currentBook.getSourceType() != null || currentBook.getSourceUrl() != null) return false;
        long id = currentBook.getId();
        SharedPreferences sp = requireContext().getSharedPreferences("local_books", android.content.Context.MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0) == id) return true;
        }
        return false;
    }

    /**
     * 本地导入书：从 local_books SP 读取章节标题（键 chapter_title_<localIndex>_<chapterIndex>）。
     * 与 ReadActivity.loadLocalBookChapters 用同一套存储，保证详情页目录与阅读器目录一致。
     */
    private void loadLocalChapters() {
        long id = currentBook.getId();
        SharedPreferences sp = requireContext().getSharedPreferences("local_books", android.content.Context.MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        chapterList.clear();
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0) == id) {
                int chCount = sp.getInt("chapter_count_" + i, 0);
                for (int j = 0; j < chCount; j++) {
                    ReadActivity.Chapter chapter = new ReadActivity.Chapter();
                    chapter.setIndex(j);
                    chapter.setTitle(sp.getString("chapter_title_" + i + "_" + j, "第" + (j + 1) + "章"));
                    chapterList.add(chapter);
                }
                break;
            }
        }
        tvChapterTotal.setText("共 " + chapterList.size() + " 章");
        adapter.notifyDataSetChanged();
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