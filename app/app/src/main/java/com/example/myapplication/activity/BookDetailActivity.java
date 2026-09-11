package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.Bookshelf;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.bean.ReadingProgress;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.ThemeManager;

import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

//书籍详情页
public class BookDetailActivity extends BaseActivity{
    private ImageView ivCover;
    private TextView tvBookName, tvAuthor, tvIntro, tvStatus, tvLastRead, tvAddShelf, tvLatestChapter;
    private ImageView ivShelfIcon;
    private Button btnRead;
    private LinearLayout btnChapterList, btnAddShelf;
    private boolean isInShelf = false;
    private int savedChapterIndex = 0;
    private String savedChapterTitle = "";
    private boolean hasRead = false;

    private Book currentBook;

    // 外站章节列表内存缓存（sourceType|sourceBookId -> 章节列表），避免重复请求
    private static final java.util.Map<String, List<String[]>> ONLINE_CHAPTER_CACHE = new java.util.HashMap<>();

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        int currentTheme = ThemeManager.getCurrentTheme(this);
        setTheme(ThemeManager.getThemeRes(currentTheme));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_detail);

        // 获取Toolbar并延伸到状态栏区域
        Toolbar toolbar = findViewById(R.id.toolbar_back);
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing

        // 获取传递的Book对象
        currentBook = (Book) getIntent().getSerializableExtra("book");
        if (currentBook == null) {
            Toast.makeText(this, "书籍信息获取失败", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initView();
        setupToolbar(currentTheme);
        displayBookInfo();
        setupClickListeners();

        // 外站书籍（来自书源爬取，未导入服务器数据库）没有本地 id，
        // 跳过依赖 book.getId() 的接口调用，避免 NPE 和无意义的请求
        if (isExternalBook()) {
            // 外站书籍：按钮状态由 displayBookInfo 中本地 external_bookshelf 检查结果决定
            // （已在书架显示"已在书架"，否则"导入书架"）
            btnRead.setEnabled(true);
            btnRead.setText("在线阅读");
            tvLatestChapter.setText("外站书籍 · 在线阅读");
            // 检查已保存的外站阅读记录，显示"继续阅读"和"上次读到:xxx"
            checkExternalReadingProgress();
            // 静默预加载章节列表：进入详情页即后台拉取，用户点目录/在线阅读时直接展示
            preloadOnlineChapters();
            // 异步获取完整书籍详情（含格式化简介、最新章节等），更新UI显示
            fetchExternalBookDetail();
        } else {
            checkReadingProgress();
            loadLatestChapter();
            loadBookDetailForShelfStatus();
        }
    }

    /**
     * 判断是否为外站书籍（来自书源爬取，未导入服务器，id 为 null）
     */
    private boolean isExternalBook() {
        return currentBook.getId() == null
                && (currentBook.getSourceType() != null || currentBook.getSourceUrl() != null);
    }

    private void initView() {
        ivCover = findViewById(R.id.iv_detail_cover);
        tvBookName = findViewById(R.id.tv_detail_book_name);
        tvAuthor = findViewById(R.id.tv_detail_author);
        tvIntro = findViewById(R.id.tv_detail_intro);
        tvStatus = findViewById(R.id.tv_detail_status);
        tvLastRead = findViewById(R.id.tv_last_read);
        btnRead = findViewById(R.id.btn_start_read);
        btnChapterList = findViewById(R.id.btn_chapter_list);
        btnAddShelf = findViewById(R.id.btn_add_shelf);
        tvAddShelf = findViewById(R.id.tv_add_shelf);
        ivShelfIcon = findViewById(R.id.iv_shelf_icon);
        tvLatestChapter = findViewById(R.id.tv_latest_chapter);
    }

    private void setupToolbar(int currentTheme) {
        Toolbar toolbar = findViewById(R.id.toolbar_back);
        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF000000);
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF1D1D1F);
        }
        // 海滨主题工具栏为浅色，状态栏图标用深色
        setLightStatusBar(currentTheme == ThemeManager.THEME_SEASIDE);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    @SuppressLint("SetTextI18n")
    private void displayBookInfo() {
        // 本地书：intro 可能没随 Book 一起传进来（书架 → 阅读器 → 更多 → 书籍详情；
        // 或阅读记录 → 阅读器 → 更多），这里统一按 bookId 从 local_books 回查一次。
        // 查不到就保持原值，下面照常显示"暂无简介"。
        if ((currentBook.getIntro() == null || currentBook.getIntro().isEmpty())
                && currentBook.getId() != null && !isExternalBook()) {
            String localIntro = LocalBookParser.findLocalBookIntro(this, currentBook.getId());
            if (!localIntro.isEmpty()) {
                currentBook.setIntro(localIntro);
            }
        }

        // 加载封面图片
        String coverUrl = currentBook.getCover();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            // 将相对路径转换为完整URL
            String fullCoverUrl = RetrofitClient.getFullImageUrl(coverUrl);
            Glide.with(this)
                    .load(fullCoverUrl)
                    .into(ivCover);
        } else {
            ivCover.setImageResource(R.drawable.default_book_cover);
        }

        tvBookName.setText(currentBook.getTitle());
        tvAuthor.setText("作者：" + currentBook.getAuthor());
        String intro = currentBook.getIntro();
        if (intro == null || intro.isEmpty()) {
            tvIntro.setText("暂无简介");
        } else {
            tvIntro.setText(intro);
        }

        // 显示书籍状态（分类·状态·字数）
        updateStatusDisplay();

        // 直接用接口返回的 isInShelf 字段初始化书架按钮状态
        Boolean shelfFlag = currentBook.getIsInShelf();
        isInShelf = shelfFlag != null && shelfFlag;
        // 外站书籍没有服务端 isInShelf 字段，改查本地 external_bookshelf 是否已导入
        if (!isInShelf && isExternalBook()) {
            isInShelf = isExternalBookInShelf();
        }
        if (isInShelf) {
            tvAddShelf.setText("已在书架");
            ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_selected);
        } else {
            tvAddShelf.setText(isExternalBook() ? "导入书架" : "加入书架");
            ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_add);
        }
        btnAddShelf.setEnabled(true);

        btnRead.setEnabled(true);
        btnRead.setText(isExternalBook() ? "在线阅读" : "开始阅读");
        btnRead.setAlpha(1.0f);
    }

    /**
     * 更新状态栏显示（分类·状态·字数）
     */
    private void updateStatusDisplay() {
        String category = currentBook.getCategory();
        if (category == null || category.isEmpty()) {
            category = "未知";
        }

        String statusText;
        int auditStatus = currentBook.getAuditStatus();
        if (auditStatus == 2) {
            statusText = "已下架";
        } else {
            switch (currentBook.getStatus()) {
                case 0:
                    statusText = "连载中";
                    break;
                case 1:
                    statusText = "已完结";
                    break;
                default:
                    statusText = "连载中";
                    break;
            }
        }

        Integer wordCount = currentBook.getWordCount();
        String wordCountText;
        if (wordCount != null && wordCount > 0) {
            if (wordCount >= 10000) {
                wordCountText = String.format("%.1f万字", wordCount / 10000.0);
            } else {
                wordCountText = wordCount + "字";
            }
        } else {
            wordCountText = "暂无字数";
        }

        tvStatus.setText(category + " · " + statusText + " · " + wordCountText);
        tvStatus.setTextColor(0xFF8E8E93); // iOS 次文字
    }

    /**
     * 检查是否有阅读记录
     */
    private void checkReadingProgress() {
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        if (userId == 0) return;

        // 先从本地缓存检查
        SharedPreferences sp = getSharedPreferences("reading_records", MODE_PRIVATE);
        int count = sp.getInt("record_count", 0);
        for (int i = 0; i < count; i++) {
            long bookId = sp.getLong("record_bookId_" + i, 0);
            if (bookId == currentBook.getId()) {
                hasRead = true;
                savedChapterIndex = sp.getInt("record_chapterIndex_" + i, 0);
                savedChapterTitle = sp.getString("record_chapterTitle_" + i, "");

                runOnUiThread(() -> {
                    btnRead.setText("继续阅读");
                    if (!savedChapterTitle.isEmpty()) {
                        tvLastRead.setText("上次读到：" + savedChapterTitle);
                        tvLastRead.setVisibility(View.VISIBLE);
                    }
                });
                return;
            }
        }

        // 本地没有，从服务器查
        RetrofitClient.getApiService().getProgress(userId, currentBook.getId())
                .enqueue(new Callback<ApiResponse<ReadingProgress>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<ReadingProgress>> call,
                                           @NonNull Response<ApiResponse<ReadingProgress>> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().isSuccess() && response.body().getData() != null) {
                            ReadingProgress p = response.body().getData();
                            hasRead = true;
                            savedChapterIndex = p.getChapterIndex();
                            String chTitle = p.getChapterTitle();

                            runOnUiThread(() -> {
                                btnRead.setText("继续阅读");
                                if (chTitle != null && !chTitle.isEmpty()) {
                                    tvLastRead.setText("上次读到：" + chTitle);
                                    tvLastRead.setVisibility(View.VISIBLE);
                                }
                            });
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<ReadingProgress>> call, @NonNull Throwable t) {}
                });
    }

    private void loadLatestChapter() {
        long bookId = currentBook.getId();
        // 使用轻量接口 /api/books/{id}/latest-chapter，只返回最后一章，避免加载全量章节
        RetrofitClient.getApiService().getLatestChapter(bookId).enqueue(new Callback<ApiResponse<ChapterDto>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<ChapterDto>> call, @NonNull Response<ApiResponse<ChapterDto>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    ChapterDto latest = response.body().getData();
                    if (latest != null) {
                        runOnUiThread(() -> tvLatestChapter.setText("连载至 " + latest.getTitle()));
                    }
                }
            }
            @Override
            public void onFailure(@NonNull Call<ApiResponse<ChapterDto>> call, @NonNull Throwable t) {}
        });
    }

    private void loadBookDetailForShelfStatus() {
        long bookId = currentBook.getId();
        // 传入登录 userId 作为 token 失效时的回退，确保书架状态可靠刷新
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        Long userId = null;
        try { if (!userIdStr.isEmpty()) userId = Long.valueOf(userIdStr); } catch (NumberFormatException ignored) {}
        RetrofitClient.getApiService().getBookDetail(bookId, userId).enqueue(new Callback<ApiResponse<Book>>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse<Book>> call, @NonNull Response<ApiResponse<Book>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    Book updated = response.body().getData();
                    if (updated != null) {
                        // 更新 currentBook 的所有字段（包含 wordCount、category 等）
                        if (updated.getWordCount() != null) {
                            currentBook.setWordCount(updated.getWordCount());
                        }
                        if (updated.getCategory() != null) {
                            currentBook.setCategory(updated.getCategory());
                        }
                        currentBook.setStatus(updated.getStatus());

                        // 更新书架按钮状态
                        if (updated.getIsInShelf() != null) {
                            isInShelf = updated.getIsInShelf();
                        }
                        runOnUiThread(() -> {
                            if (isInShelf) {
                                tvAddShelf.setText("已在书架");
                                ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_selected);
                            } else {
                                tvAddShelf.setText("加入书架");
                                ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_add);
                            }
                            btnAddShelf.setEnabled(true);
                            // 刷新状态栏显示（字数、分类等）
                            updateStatusDisplay();
                        });
                    }
                }
            }
            @Override
            public void onFailure(@NonNull Call<ApiResponse<Book>> call, @NonNull Throwable t) {}
        });
    }

    private void setupClickListeners() {
        // 开始阅读
        btnRead.setOnClickListener(v -> {
            if (isExternalBook()) {
                // 外站书籍：直接在线阅读，不导入
                startOnlineReading();
            } else {
                Intent intent = new Intent(this, ReadActivity.class);
                intent.putExtra("book", currentBook);
                if (hasRead) {
                    intent.putExtra("chapterIndex", savedChapterIndex);
                }
                startActivity(intent);
            }
        });

        // 章节目录
        btnChapterList.setOnClickListener(v -> {
            if (isExternalBook()) {
                // 外站书籍：直接从后端 API 获取章节列表，不导入
                loadOnlineChapters();
            } else {
                Intent intent = new Intent(this, ChapterListActivity.class);
                intent.putExtra("book", currentBook);
                startActivity(intent);
            }
        });

        // 加入书架
        btnAddShelf.setOnClickListener(v -> {
            if (isExternalBook()) {
                // 外站书籍：仅本地保存/移除，不调服务器
                if (isInShelf) {
                    removeExternalBookFromLocal();
                } else {
                    saveExternalBookToLocal();
                }
                return;
            }
            String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
            long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
            if (userId == 0) {
                Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
                return;
            }

            if (isInShelf) {
                // ========== 移出书架 ==========
                btnAddShelf.setEnabled(false);
                tvAddShelf.setText("移除中...");

                RetrofitClient.getApiService().removeFromBookshelf(userId, currentBook.getId())
                        .enqueue(new Callback<ApiResponse<Void>>() {
                            @Override
                            public void onResponse(@NonNull Call<ApiResponse<Void>> call, @NonNull Response<ApiResponse<Void>> response) {
                                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                                    isInShelf = false;
                                    runOnUiThread(() -> {
                                        tvAddShelf.setText("加入书架");
                                        ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_add);
                                        btnAddShelf.setEnabled(true);
                                        Toast.makeText(BookDetailActivity.this, "已移出书架", Toast.LENGTH_SHORT).show();
                                    });
                                } else {
                                    runOnUiThread(() -> {
                                        btnAddShelf.setEnabled(true);
                                        tvAddShelf.setText("已在书架");
                                        ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_selected);
                                    });
                                }
                            }
                            @Override
                            public void onFailure(@NonNull Call<ApiResponse<Void>> call, @NonNull Throwable t) {
                                runOnUiThread(() -> {
                                    btnAddShelf.setEnabled(true);
                                    tvAddShelf.setText("已在书架");
                                    ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_selected);
                                });
                            }
                        });
            } else {
                // ========== 加入书架 ==========
                btnAddShelf.setEnabled(false);
                tvAddShelf.setText("添加中...");

                RetrofitClient.getApiService().addToBookshelf(userId, currentBook.getId())
                        .enqueue(new Callback<ApiResponse<Bookshelf>>() {
                            @Override
                            public void onResponse(@NonNull Call<ApiResponse<Bookshelf>> call, @NonNull Response<ApiResponse<Bookshelf>> response) {
                                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                                    isInShelf = true;
                                    runOnUiThread(() -> {
                                        tvAddShelf.setText("已在书架");
                                        ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_selected);
                                        btnAddShelf.setEnabled(true);
                                        Toast.makeText(BookDetailActivity.this, "已加入书架", Toast.LENGTH_SHORT).show();
                                    });
                                } else {
                                    String errorMsg;
                                    if (response.body() != null && response.body().getMessage() != null) {
                                        errorMsg = response.body().getMessage();
                                    } else {
                                        errorMsg = getAddShelfErrorMessage(response.code());
                                    }
                                    runOnUiThread(() -> {
                                        btnAddShelf.setEnabled(true);
                                        tvAddShelf.setText("加入书架");
                                        ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_add);
                                        Toast.makeText(BookDetailActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
                                    });
                                }
                            }
                            @Override
                            public void onFailure(@NonNull Call<ApiResponse<Bookshelf>> call, @NonNull Throwable t) {
                                runOnUiThread(() -> {
                                    btnAddShelf.setEnabled(true);
                                    tvAddShelf.setText("加入书架");
                                    ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_add);
                                    Toast.makeText(BookDetailActivity.this, "网络连接失败，请检查网络", Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
            }
        });
    }

    private String getAddShelfErrorMessage(int code) {
        if (code == 500) {
            return "服务器错误(500)，请稍后重试";
        } else if (code == 409) {
            return "书籍已在书架中";
        } else {
            return "添加失败，请重试(" + code + ")";
        }
    }

    // ======================== 外站书籍：在线阅读相关 ========================

    /**
     * 外站书籍加入书架：仅本地 SharedPreferences 保存，不调服务器。
     * 保存内容：书名、作者、封面、sourceType、sourceBookId(tocUrl)、分类。
     */
    private void saveExternalBookToLocal() {
        String sourceType = currentBook.getSourceType();
        String sourceBookId = currentBook.getSourceUrl();
        if (sourceType == null || sourceBookId == null || sourceBookId.isEmpty()) {
            Toast.makeText(this, "书源信息缺失", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences sp = getSharedPreferences("external_bookshelf", MODE_PRIVATE);
        String key = sourceType + "|" + sourceBookId;

        // 检查是否已在本地书架
        if (sp.contains(key)) {
            Toast.makeText(this, "已在书架", Toast.LENGTH_SHORT).show();
            return;
        }

        // 拼接格式：title|||author|||cover|||category|||chapterCount
        String val = safe(currentBook.getTitle()) + "|||"
                + safe(currentBook.getAuthor()) + "|||"
                + safe(currentBook.getCover()) + "|||"
                + safe(currentBook.getCategory()) + "|||"
                + currentBook.getChapterCount();
        sp.edit().putString(key, val).apply();

        isInShelf = true;
        tvAddShelf.setText("已在书架");
        ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_selected);
        Toast.makeText(this, "已加入书架", Toast.LENGTH_SHORT).show();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /**
     * 外站书籍是否已导入本地书架（按 sourceType|sourceUrl 唯一键查 external_bookshelf）。
     */
    private boolean isExternalBookInShelf() {
        String sourceType = currentBook.getSourceType();
        String sourceUrl = currentBook.getSourceUrl();
        if (sourceType == null || sourceUrl == null) return false;
        String key = sourceType + "|" + sourceUrl;
        return getSharedPreferences("external_bookshelf", MODE_PRIVATE).contains(key);
    }

    /**
     * 外站书籍移出本地书架，并同步清理阅读记录与分组归属（与 BookShelfFragment 删除逻辑一致）。
     */
    private void removeExternalBookFromLocal() {
        String sourceType = currentBook.getSourceType();
        String sourceUrl = currentBook.getSourceUrl();
        if (sourceType == null || sourceUrl == null) return;
        String key = sourceType + "|" + sourceUrl;

        getSharedPreferences("external_bookshelf", MODE_PRIVATE)
                .edit().remove(key).apply();

        // 同步移除外站阅读记录（键格式与 BookShelfFragment/ReadActivity 一致）
        String recordKey = "ext_" + sourceType + "|" + sourceUrl;
        getSharedPreferences("external_reading_records", MODE_PRIVATE)
                .edit()
                .remove(recordKey + "_chapterIndex")
                .remove(recordKey + "_chapterTitle")
                .remove(recordKey + "_page")
                .remove(recordKey + "_readTime")
                .apply();

        // 移除分组归属（键格式与 BookShelfFragment.getBookGroupKey 一致）
        getSharedPreferences("shelf_group_assign", MODE_PRIVATE)
                .edit().remove("ext_" + sourceType + "_" + sourceUrl).apply();

        isInShelf = false;
        tvAddShelf.setText("导入书架");
        ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_add);
        Toast.makeText(this, "已移出书架", Toast.LENGTH_SHORT).show();
    }

    private String externalCacheKey() {
        return safe(currentBook.getSourceType()) + "|" + safe(currentBook.getSourceUrl());
    }

    /**
     * 外站章节列表共享缓存：供 ReadActivity / OnlineChapterListActivity 读取。
     * 背景：大书（如斗破苍穹 1663 章 × ~1.7KB/章 ≈ 2.9MB）经 Intent 传递会超过
     * Binder 1MB 限制，导致接收方 getSerializableExtra("chapters") 返回 null，
     * 阅读器回退到本地书路径一直"加载中"。改为不传 chapters，由目标页从此缓存读取
     * （preloadOnlineChapters / fetchOnlineChapters 已写入静态内存 + 磁盘）。
     */
    public static String externalCacheKeyFor(Book book) {
        return safe(book.getSourceType()) + "|" + safe(book.getSourceUrl());
    }

    /** 读取外站章节列表：先静态内存，后磁盘持久化缓存。命中返回非空 List，未命中返回 null。 */
    public static List<String[]> getExternalChapters(android.content.Context ctx, Book book) {
        if (book == null) return null;
        String key = externalCacheKeyFor(book);
        List<String[]> cached = ONLINE_CHAPTER_CACHE.get(key);
        if (cached != null && !cached.isEmpty()) return cached;
        try {
            SharedPreferences sp = ctx.getSharedPreferences("external_chapter_cache", MODE_PRIVATE);
            String json = sp.getString(key, "");
            if (json == null || json.isEmpty()) return null;
            org.json.JSONArray arr = new org.json.JSONArray(json);
            List<String[]> list = new java.util.ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONArray item = arr.getJSONArray(i);
                String[] pair = new String[item.length()];
                for (int j = 0; j < item.length(); j++) pair[j] = item.optString(j, "");
                list.add(pair);
            }
            if (!list.isEmpty()) {
                ONLINE_CHAPTER_CACHE.put(key, list);
                return list;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 写入外站章节列表到静态内存 + 磁盘（供异步拉取到章节后回填缓存）。 */
    public static void putExternalChapters(android.content.Context ctx, Book book, List<String[]> chapters) {
        if (book == null || chapters == null || chapters.isEmpty()) return;
        String key = externalCacheKeyFor(book);
        ONLINE_CHAPTER_CACHE.put(key, chapters);
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String[] pair : chapters) {
                org.json.JSONArray item = new org.json.JSONArray();
                if (pair != null) {
                    for (String s : pair) item.put(s == null ? "" : s);
                }
                arr.put(item);
            }
            SharedPreferences sp = ctx.getSharedPreferences("external_chapter_cache", MODE_PRIVATE);
            sp.edit().putString(key, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Binder 1MB 事务的安全阈值：超过则走共享缓存，不走 Intent extra。 */
    private static final int INTENT_CHAPTERS_MAX_BYTES = 300 * 1024;

    /**
     * 粗略估算章节列表序列化字节数（保守 UTF-8 估算 + 对象开销），
     * 保证小于 INTENT_CHAPTERS_MAX_BYTES 时才走 Intent，避免 TransactionTooLargeException。
     */
    private static int estimateBytes(List<String[]> chapters) {
        int total = 0;
        for (String[] pair : chapters) {
            if (pair == null) continue;
            for (String s : pair) {
                if (s == null) continue;
                total += s.length() * 2 + 32;
            }
        }
        return total;
    }

    /**
     * 把章节列表附加到 Intent：≤ 阈值直接附加；> 阈值只写共享缓存。
     * 始终会先调用 putExternalChapters() 写入共享缓存（失败兜底）。
     * @return true=经 Intent 传递；false=仅经共享缓存传递
     */
    public static boolean putChaptersExtra(android.content.Context ctx, Intent intent, Book book, List<String[]> chapters) {
        putExternalChapters(ctx, book, chapters); // 始终写缓存（失败兜底）
        if (estimateBytes(chapters) <= INTENT_CHAPTERS_MAX_BYTES) {
            intent.putExtra("chapters", chapters.toArray(new String[0][]));
            return true;
        }
        intent.putExtra("chaptersViaCache", true);
        return false;
    }

    /**
     * 外站书籍：检查已保存的阅读记录（先查本地，再查服务器），显示"继续阅读"。
     * 满足"只保存阅读信息用于下次继续阅读"的需求。
     * 进度合并策略：取读到更靠后的一方（max(本地, 服务器)），避免弱网下本地进度被服务器旧值覆盖。
     */
    private void checkExternalReadingProgress() {
        String sourceType = currentBook.getSourceType();
        String sourceBookId = currentBook.getSourceUrl();
        if (sourceType == null || sourceBookId == null || sourceBookId.isEmpty()) return;

        String recordKey = "ext_" + sourceType + "|" + sourceBookId;
        SharedPreferences sp = getSharedPreferences("external_reading_records", MODE_PRIVATE);
        int localIdx = sp.getInt(recordKey + "_chapterIndex", -1);
        String localTitle = sp.getString(recordKey + "_chapterTitle", "");
        if (localIdx >= 0) {
            hasRead = true;
            savedChapterIndex = localIdx;
            savedChapterTitle = localTitle;
            showExternalContinueReading(localTitle);
        }

        // 再查服务器记录，采用合并策略：仅当服务器读到更靠后时才覆盖本地显示，
        // 避免弱网/同步延迟下本地进度被服务器旧值回滚覆盖。
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        if (userId == 0) return;
        RetrofitClient.getApiService().getExternalProgress(userId, sourceType, sourceBookId)
                .enqueue(new Callback<ApiResponse<java.util.Map<String, Object>>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<java.util.Map<String, Object>>> call,
                                           @NonNull Response<ApiResponse<java.util.Map<String, Object>>> response) {
                        runOnUiThread(() -> {
                            if (response.isSuccessful() && response.body() != null
                                    && response.body().isSuccess() && response.body().getData() != null) {
                                java.util.Map<String, Object> data = response.body().getData();
                                Object idxObj = data.get("chapterIndex");
                                Object titleObj = data.get("chapterTitle");
                                if (idxObj != null) {
                                    int serverIdx = ((Number) idxObj).intValue();
                                    // 合并策略：服务器读到更靠后才采用（取较大值）
                                    if (serverIdx >= 0 && serverIdx > localIdx) {
                                        hasRead = true;
                                        savedChapterIndex = serverIdx;
                                        savedChapterTitle = titleObj != null ? titleObj.toString() : "";
                                        showExternalContinueReading(savedChapterTitle);
                                    }
                                }
                            }
                        });
                    }
                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<java.util.Map<String, Object>>> call,
                                          @NonNull Throwable t) {}
                });
    }

    private void showExternalContinueReading(String chapterTitle) {
        btnRead.setText("继续阅读");
        if (chapterTitle != null && !chapterTitle.isEmpty()) {
            tvLastRead.setText("上次读到：" + chapterTitle);
            tvLastRead.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 外站书籍：加载章节列表并跳转 OnlineChapterListActivity 展示。
     * 使用统一的 fetchOnlineChapters（内存 → 持久化 → 网络），失败时给出书源失效兜底。
     */
    private void loadOnlineChapters() {
        if (currentBook.getSourceType() == null || currentBook.getSourceUrl() == null) {
            Toast.makeText(this, "书源信息缺失", Toast.LENGTH_SHORT).show();
            return;
        }
        btnChapterList.setEnabled(false);
        // 仅在没有缓存命中时显示"加载中"，避免命中缓存时把"共X章"刷掉
        String cur = tvLatestChapter.getText().toString();
        if (!cur.startsWith("共")) tvLatestChapter.setText("加载中...");

        fetchOnlineChapters(new ChaptersCallback() {
            @Override
            public void onSuccess(List<String[]> chapters) {
                btnChapterList.setEnabled(true);
                Intent intent = new Intent(BookDetailActivity.this, OnlineChapterListActivity.class);
                intent.putExtra("book", currentBook);
                putChaptersExtra(BookDetailActivity.this, intent, currentBook, chapters);
                try { startActivity(intent); }
                catch (RuntimeException te) {
                    Intent fallback = new Intent(BookDetailActivity.this, OnlineChapterListActivity.class);
                    fallback.putExtra("book", currentBook);
                    fallback.putExtra("chaptersViaCache", true);
                    startActivity(fallback);
                }
            }
            @Override
            public void onFail(String msg, boolean sourceMayDown) {
                btnChapterList.setEnabled(true);
                tvLatestChapter.setText("外站书籍 · 在线阅读");
                if (sourceMayDown) {
                    showSourceUnavailableDialog(msg);
                } else {
                    Toast.makeText(BookDetailActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /**
     * 外站书籍：直接在线阅读。
     * 优先使用内存/持久化缓存的章节列表；若有已保存进度，传入 chapterIndex 让 ReadActivity 直接跳到该章。
     * 失败时给出书源失效兜底。
     */
    private void startOnlineReading() {
        if (currentBook.getSourceType() == null || currentBook.getSourceUrl() == null) {
            Toast.makeText(this, "书源信息缺失", Toast.LENGTH_SHORT).show();
            return;
        }

        // 先合并内存与持久化缓存
        List<String[]> cached = ONLINE_CHAPTER_CACHE.get(externalCacheKey());
        if (cached == null || cached.isEmpty()) {
            cached = readChapterCacheFromDisk(this, currentBook);
            if (cached != null && !cached.isEmpty()) {
                ONLINE_CHAPTER_CACHE.put(externalCacheKey(), cached);
            }
        }
        if (cached != null && !cached.isEmpty()) {
            currentBook.setChapterCount(cached.size());
            Intent intent = new Intent(BookDetailActivity.this, ReadActivity.class);
            intent.putExtra("book", currentBook);
            intent.putExtra("isExternal", true);
            putChaptersExtra(BookDetailActivity.this, intent, currentBook, cached); // 小书走Intent，大书走缓存
            if (hasRead) intent.putExtra("chapterIndex", savedChapterIndex);
            try { startActivity(intent); }
            catch (RuntimeException te) {
                // Binder 事务仍失败时，降级为仅缓存模式重试（几乎不会触发，estimateBytes 已保守）
                Intent fallback = new Intent(BookDetailActivity.this, ReadActivity.class);
                fallback.putExtra("book", currentBook);
                fallback.putExtra("isExternal", true);
                fallback.putExtra("chaptersViaCache", true);
                if (hasRead) fallback.putExtra("chapterIndex", savedChapterIndex);
                startActivity(fallback);
            }
            return;
        }

        btnRead.setEnabled(false);
        btnRead.setText("加载中...");
        // 启动超时保护：20秒后若仍在加载，恢复按钮状态并提示
        final boolean[] completed = {false};
        final Runnable timeoutTask = () -> {
            if (!completed[0]) {
                runOnUiThread(() -> {
                    btnRead.setEnabled(true);
                    btnRead.setText(hasRead ? "继续阅读" : "在线阅读");
                    Toast.makeText(BookDetailActivity.this, "加载超时，请检查网络后重试", Toast.LENGTH_SHORT).show();
                });
            }
        };
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(timeoutTask, 20000);
        
        fetchOnlineChapters(new ChaptersCallback() {
            @Override
            public void onSuccess(List<String[]> chapters) {
                completed[0] = true;
                runOnUiThread(() -> {
                    btnRead.setEnabled(true);
                    btnRead.setText(hasRead ? "继续阅读" : "在线阅读");
                    Intent intent = new Intent(BookDetailActivity.this, ReadActivity.class);
                    intent.putExtra("book", currentBook);
                    intent.putExtra("isExternal", true);
                    putChaptersExtra(BookDetailActivity.this, intent, currentBook, chapters);
                    if (hasRead) intent.putExtra("chapterIndex", savedChapterIndex);
                    try { startActivity(intent); }
                    catch (RuntimeException te) {
                        Intent fallback = new Intent(BookDetailActivity.this, ReadActivity.class);
                        fallback.putExtra("book", currentBook);
                        fallback.putExtra("isExternal", true);
                        fallback.putExtra("chaptersViaCache", true);
                        if (hasRead) fallback.putExtra("chapterIndex", savedChapterIndex);
                        startActivity(fallback);
                    }
                });
            }
            @Override
            public void onFail(String msg, boolean sourceMayDown) {
                completed[0] = true;
                runOnUiThread(() -> {
                    btnRead.setEnabled(true);
                    btnRead.setText(hasRead ? "继续阅读" : "在线阅读");
                    if (sourceMayDown) {
                        showSourceUnavailableDialog(msg);
                    } else {
                        Toast.makeText(BookDetailActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    // ========== 外站章节列表：统一获取（内存 → 持久化 → 网络） ==========

    public interface ChaptersCallback {
        void onSuccess(List<String[]> chapters);
        void onFail(String msg, boolean sourceMayDown);
    }

    /**
     * 外站书籍：异步获取完整书籍详情（含格式化简介、最新章节等），更新UI显示。
     * 搜索结果只有简短摘要，详情API返回完整格式化简介（书名/作者/热度/章节数/简介等）。
     */
    private void fetchExternalBookDetail() {
        String sourceType = currentBook.getSourceType();
        String sourceBookId = currentBook.getSourceUrl();
        if (sourceType == null || sourceBookId == null || sourceBookId.isEmpty()) return;

        RetrofitClient.getApiService().getOnlineBookDetail(sourceType, sourceBookId)
                .enqueue(new Callback<ApiResponse<Book>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<Book>> call,
                                           @NonNull Response<ApiResponse<Book>> response) {
                        runOnUiThread(() -> {
                            if (response.isSuccessful() && response.body() != null
                                    && response.body().isSuccess()
                                    && response.body().getData() != null) {
                                Book detail = response.body().getData();
                                // 用详情数据更新 currentBook（仅更新非空字段，避免覆盖搜索结果的有效数据）
                                if (detail.getIntro() != null && !detail.getIntro().isEmpty()) {
                                    currentBook.setIntro(detail.getIntro());
                                }
                                if (detail.getCover() != null && !detail.getCover().isEmpty()) {
                                    currentBook.setCover(detail.getCover());
                                }
                                if (detail.getWordCount() != null && detail.getWordCount() > 0) {
                                    currentBook.setWordCount(detail.getWordCount());
                                }
                                if (detail.getCategory() != null && !detail.getCategory().isEmpty()) {
                                    currentBook.setCategory(detail.getCategory());
                                }
                                // 重新渲染详情页UI（简介、封面、状态等）
                                displayBookInfo();
                                // 更新最新章节显示
                                String lastCh = detail.getLastChapter();
                                if (lastCh != null && !lastCh.isEmpty()) {
                                    tvLatestChapter.setText(lastCh);
                                }
                            }
                            // 获取详情失败时不影响页面正常使用，静默忽略
                        });
                    }
                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<Book>> call, @NonNull Throwable t) {
                        // 网络错误时不影响页面正常使用，静默忽略
                    }
                });
    }

    /**
     * 静默预加载章节列表：进入详情页即后台拉取，用户点目录/在线阅读时直接展示。
     * 成功/失败都不弹 Toast，仅更新 tvLatestChapter 文案。
     */
    private void preloadOnlineChapters() {
        fetchOnlineChapters(new ChaptersCallback() {
            @Override
            public void onSuccess(List<String[]> chapters) {
                // tvLatestChapter 已在 fetchOnlineChapters 内更新为"共X章"
            }
            @Override
            public void onFail(String msg, boolean sourceMayDown) {
                if (sourceMayDown) {
                    tvLatestChapter.setText("书源暂不可用 · 在线阅读");
                }
            }
        });
    }

    /**
     * 统一获取外站章节列表：内存缓存 → 持久化缓存 → 网络。
     * 实例版供详情页使用（会顺带刷新 tvLatestChapter）；静态版供书架等外部入口复用，避免重复实现。
     */
    private void fetchOnlineChapters(ChaptersCallback callback) {
        fetchExternalChaptersCore(this, currentBook, new ChaptersCallback() {
            @Override
            public void onSuccess(List<String[]> chapters) {
                currentBook.setChapterCount(chapters.size());
                tvLatestChapter.setText("共" + chapters.size() + "章");
                callback.onSuccess(chapters);
            }
            @Override
            public void onFail(String msg, boolean sourceMayDown) {
                callback.onFail(msg, sourceMayDown);
            }
        });
    }

    /** 静态版：供书架等外部入口复用，逻辑与详情页一致（缓存 → 网络），不带详情页 UI。 */
    public static void fetchOnlineChapters(android.content.Context ctx, Book book, ChaptersCallback callback) {
        fetchExternalChaptersCore(ctx, book, callback);
    }

    /**
     * 外站章节列表核心获取逻辑：内存缓存 → 持久化缓存 → 网络。
     * 成功时写回内存+磁盘缓存，失败时透传原因（sourceMayDown=true 表示可能书源失效）。
     * 与书城"在线阅读"完全同一套逻辑，书架入口复用它即可避免阅读器内异步拉取偶发卡在"正在加载"。
     */
    private static void fetchExternalChaptersCore(android.content.Context ctx, Book book, ChaptersCallback callback) {
        if (book == null) { callback.onFail("书籍信息缺失", false); return; }
        String sourceType = book.getSourceType();
        String sourceBookId = book.getSourceUrl();
        if (sourceType == null || sourceBookId == null || sourceBookId.isEmpty()) {
            callback.onFail("书源信息缺失", false);
            return;
        }

        // 1. 内存缓存
        List<String[]> cached = ONLINE_CHAPTER_CACHE.get(externalCacheKeyFor(book));
        if (cached != null && !cached.isEmpty()) {
            callback.onSuccess(cached);
            return;
        }

        // 2. 持久化缓存（SharedPreferences，App 重启后仍可用）
        List<String[]> persisted = readChapterCacheFromDisk(ctx, book);
        if (persisted != null && !persisted.isEmpty()) {
            ONLINE_CHAPTER_CACHE.put(externalCacheKeyFor(book), persisted);
            callback.onSuccess(persisted);
            return;
        }

        // 3. 网络
        RetrofitClient.getApiService().getOnlineChapterList(sourceType, sourceBookId)
                .enqueue(new Callback<ApiResponse<List<String[]>>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<List<String[]>>> call,
                                           @NonNull Response<ApiResponse<List<String[]>>> response) {
                        if (ctx instanceof android.app.Activity) {
                            ((android.app.Activity) ctx).runOnUiThread(() -> onChaptersResult(ctx, response, book, callback));
                        } else {
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> onChaptersResult(ctx, response, book, callback));
                        }
                    }
                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<List<String[]>>> call, @NonNull Throwable t) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onFail("网络错误: " + t.getMessage(), true));
                    }
                });
    }

    private static void onChaptersResult(android.content.Context ctx, @NonNull Response<ApiResponse<List<String[]>>> response, Book book, ChaptersCallback callback) {
        if (response.isSuccessful() && response.body() != null
                && response.body().isSuccess()
                && response.body().getData() != null
                && !response.body().getData().isEmpty()) {
            List<String[]> chapters = response.body().getData();
            ONLINE_CHAPTER_CACHE.put(externalCacheKeyFor(book), chapters);
            writeChapterCacheToDisk(ctx, book, chapters);
            callback.onSuccess(chapters);
        } else {
            String msg = response.body() != null ? response.body().getMessage() : "获取章节失败";
            if (msg == null || msg.isEmpty()) msg = "获取章节失败";
            callback.onFail(msg, true);
        }
    }

    // ========== 章节列表持久化（SharedPreferences，JSON 序列化） ==========

    private static List<String[]> readChapterCacheFromDisk(android.content.Context ctx, Book book) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("external_chapter_cache", android.content.Context.MODE_PRIVATE);
            String json = sp.getString(externalCacheKeyFor(book), "");
            if (json == null || json.isEmpty()) return null;
            org.json.JSONArray arr = new org.json.JSONArray(json);
            List<String[]> list = new java.util.ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONArray item = arr.getJSONArray(i);
                String[] pair = new String[item.length()];
                for (int j = 0; j < item.length(); j++) {
                    pair[j] = item.optString(j, "");
                }
                list.add(pair);
            }
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeChapterCacheToDisk(android.content.Context ctx, Book book, List<String[]> chapters) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String[] pair : chapters) {
                org.json.JSONArray item = new org.json.JSONArray();
                if (pair != null) {
                    for (String s : pair) item.put(s == null ? "" : s);
                }
                arr.put(item);
            }
            SharedPreferences sp = ctx.getSharedPreferences("external_chapter_cache", android.content.Context.MODE_PRIVATE);
            sp.edit().putString(externalCacheKeyFor(book), arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ========== 书源失效兜底 ==========

    /**
     * 书源失效兜底：友好提示用户切换书源，避免裸露的网络/4xx 错误。
     * 用户可选"返回"（finish 回到书城切换书源）或"留在此页"。
     */
    private void showSourceUnavailableDialog(String reason) {
        String msg = (reason == null || reason.isEmpty() ? "" : "原因：" + reason + "\n")
                + "本书源可能已失效或被封禁，请返回书城切换其他书源后重试。";
        new android.app.AlertDialog.Builder(this)
                .setTitle("书源暂时不可用")
                .setMessage(msg)
                .setPositiveButton("返回", (d, w) -> finish())
                .setNegativeButton("留在此页", null)
                .show();
    }

}
