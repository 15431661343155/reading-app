package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Book;
import com.example.myapplication.bean.Bookshelf;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.bean.MajorChapter;
import com.example.myapplication.bean.ReadingProgress;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.LoginHelper;
import com.example.myapplication.utils.ExternalPrefs;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.ThemeManager;
import com.example.myapplication.utils.TocOrder;
import com.example.myapplication.utils.VolumeDeriver;
import com.example.myapplication.utils.Hint;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

//书籍详情页
public class BookDetailActivity extends BaseActivity{

    /** 状态行最多展示的子分类个数（超出的用「…」收尾） */
    private static final int MAX_STATUS_SUBS = 3;
    private ImageView ivCover;
    private TextView tvBookName, tvAuthor, tvIntro, tvStatus, tvLastRead, tvAddShelf, tvLatestChapter;
    private ImageView ivShelfIcon;
    private Button btnRead;
    private LinearLayout btnChapterList, btnAddShelf;
    private boolean isInShelf = false;
    private int savedChapterIndex = 0;
    private String savedChapterTitle = "";
    private boolean hasRead = false;
    /** 外站书是否存在外站阅读记录（决定外站详情页按钮显示"继续阅读"还是"在线阅读"） */
    private boolean extHasRead = false;
    /** 上次已同步展示的本地外站记录章节索引；用于判断"本地记录是否又变过" */
    private int extLocalSyncedIdx = -1;
    /** 是否已发起过服务器阅读进度查询（避免 onResume 反复请求同一本书的进度） */
    private boolean serverProgressQueried = false;
    /** 当前章行背景（淡蓝底 + 左侧竖条），多行复用同一实例 */
    private Drawable currentChapterBgDrawable;

    // 简介折叠相关
    private static final int INTRO_MAX_LINES = 3;
    private static final int INTRO_ANIM_DURATION = 260;
    private TextView tvIntroToggle;
    private String introFullText = "";
    private boolean introExpanded = false;
    private ValueAnimator introAnim = null;

    private Book currentBook;

    // 外站章节列表内存缓存（sourceType|sourceBookId -> 章节列表），避免重复请求
    private static final java.util.Map<String, List<String[]>> ONLINE_CHAPTER_CACHE = new java.util.HashMap<>();

    // ========== 详情页「目录」底部弹窗（替代跳转章节列表页） ==========
    private PopupWindow chapterSheetPopup;
    private final TocOrder sheetOrder = new TocOrder();
    private RecyclerView sheetRv;
    private TextView sheetSortBtn, sheetInfo;
    private SheetAdapter sheetAdapter;
    /** 弹窗面板根视图（收起时做下滑动画 / 跟手拖拽的位移目标） */
    private View sheetPanelView;
    /** 防止收起动画期间重复触发 */
    private boolean sheetDismissing;
    /** 章节标题（外站为 [title, url]），下标 = 真实章节 index */
    private List<String[]> sheetChapters = new ArrayList<>();
    /** 本站分卷（VolumeDeriver 推导；空 → 平铺） */
    private final List<LocalBookParser.VolumeInfo> sheetVolumes = new ArrayList<>();
    /** 渲染行：String=卷头 / int[]{realIndex}=章节 */
    private final List<Object> sheetRows = new ArrayList<>();
    private int sheetCurrentIndex = -1;

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
            Hint.show(this, "书籍信息获取失败");
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
            // 阅读量埋点：本站书籍进入详情页 +1（书城「男生/女生」子页按点击量排热门用）
            trackBookView();
        }
    }

    /**
     * 阅读量埋点：进详情页 POST /api/books/{id}/view。
     * 只统计本站书籍（外站书没有本地 id，也无需参与本站热门榜）；
     * 成败都静默处理 —— 计数失败绝不该影响用户看书。
     */
    private void trackBookView() {
        if (currentBook == null || currentBook.getId() == null || currentBook.getId() <= 0) return;
        RetrofitClient.getApiService().incrementBookView(currentBook.getId())
                .enqueue(new Callback<ApiResponse<Void>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                        // 无需处理
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                        // 静默：埋点失败不影响阅读
                    }
                });
    }

    /**
     * 返回详情页（resume）时刷新阅读记录，使"读完返回"后按钮文案与"上次读到"立即正确。
     * 外站书走 refreshExternalProgressOnResume()（只读本地）；本站书走 syncLocalReadingProgress()。
     * 服务器进度不在此每次都查：本地未命中且尚未成功查过服务器时才查一次，避免频繁请求。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (currentBook == null) return;
        if (isExternalBook()) {
            // 外站书：返回详情页后刷新本地外站记录（只读本地，不打接口）
            refreshExternalProgressOnResume();
            return;
        }
        if (currentBook.getId() == null) return;
        boolean localHit = syncLocalReadingProgress();
        if (!localHit && !serverProgressQueried) {
            queryServerReadingProgress();
        }
    }

    /**
     * 返回详情页（resume）时刷新外站本地阅读记录：只读本地，不回退已显示的更靠后进度。
     * 阅读时本地记录已写入，足够让"返回即刷新"正确，故不发网络请求。
     */
    private void refreshExternalProgressOnResume() {
        if (currentBook == null) return;
        String sourceType = currentBook.getSourceType();
        String sourceBookId = currentBook.getSourceUrl();
        if (sourceType == null || sourceBookId == null || sourceBookId.isEmpty()) return;
        String recordKey = "ext_" + sourceType + "|" + sourceBookId;
        SharedPreferences sp = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
        int localIdx = sp.getInt(recordKey + "_chapterIndex", -1);
        String localTitle = sp.getString(recordKey + "_chapterTitle", "");
        // 本地记录"变了"（用户又读了新章，或往回跳读了旧章）→ 立即刷新；
        // 本地记录没变 → 保持已显示的进度（可能是服务器更靠后的值），不回退
        if (localIdx >= 0 && (localIdx != extLocalSyncedIdx || !extHasRead)) {
            hasRead = true;
            savedChapterIndex = localIdx;
            savedChapterTitle = localTitle;
            extLocalSyncedIdx = localIdx;
            showExternalContinueReading(localTitle);   // 内部会置 extHasRead=true 并更新按钮文案与"上次读到"
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
        tvIntroToggle = findViewById(R.id.tv_intro_toggle);

        // 简介：点击正文区域或"展开/收起"按钮均可切换折叠状态
        View.OnClickListener introToggleListener = v -> toggleIntro();
        tvIntro.setOnClickListener(introToggleListener);
        tvIntroToggle.setOnClickListener(introToggleListener);
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
            introFullText = "";
            tvIntro.setText("暂无简介");
            tvIntroToggle.setVisibility(View.GONE);
        } else {
            introFullText = intro;
            tvIntro.setText(intro);
            // 等布局完成后再判断是否超 3 行，决定是否显示折叠按钮
            tvIntro.post(this::refreshIntroToggle);
        }

        // 显示书籍状态（详细分类·状态·字数）
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
        if (isExternalBook()) {
            // 外站书：按是否存在外站阅读记录决定文案（避免异步回调 displayBookInfo 覆盖"继续阅读"）
            btnRead.setText(extHasRead ? "继续阅读" : "在线阅读");
        } else {
            btnRead.setText(hasRead ? "继续阅读" : "开始阅读");
        }
        btnRead.setAlpha(1.0f);
    }

    /**
     * 根据简介真实行数决定是否显示"展开/收起"按钮，并套用当前折叠状态。
     * 用 StaticLayout 测量（不触发真实重布局，避免先全显再折叠的闪烁）。
     */
    private void refreshIntroToggle() {
        if (introFullText.isEmpty()) {
            tvIntroToggle.setVisibility(View.GONE);
            return;
        }
        if (introNeedsToggle(introFullText)) {
            tvIntroToggle.setVisibility(View.VISIBLE);
            applyIntroCollapsed(!introExpanded);
        } else {
            // 不足 3 行，直接全显，不显示按钮
            tvIntroToggle.setVisibility(View.GONE);
            tvIntro.setMaxLines(Integer.MAX_VALUE);
            tvIntro.setEllipsize(null);
        }
    }

    /**
     * 用与 tvIntro 完全相同的排版参数测量真实行数，判断是否超过 INTRO_MAX_LINES。
     */
    private boolean introNeedsToggle(String text) {
        int width = tvIntro.getWidth();
        if (width <= 0) return false;
        int avail = width - tvIntro.getPaddingLeft() - tvIntro.getPaddingRight();
        if (avail <= 0) return false;
        TextPaint paint = tvIntro.getPaint();
        StaticLayout sl = StaticLayout.Builder.obtain(
                        text, 0, text.length(), paint, avail)
                .setLineSpacing(tvIntro.getLineSpacingExtra(), tvIntro.getLineSpacingMultiplier())
                .setIncludePad(tvIntro.getIncludeFontPadding())
                .build();
        return sl.getLineCount() > INTRO_MAX_LINES;
    }

    /** 点击简介正文或按钮时切换折叠/展开（带高度过渡动画） */
    private void toggleIntro() {
        if (tvIntroToggle.getVisibility() != View.VISIBLE) return;
        animateIntroCollapsed(introExpanded);
    }

    /** collapsed=true 折叠（最多 3 行 + 省略号，按钮显示"展开"）；false 展开（全显，按钮显示"收起"）。
     *  用于初始/异步重渲染时的瞬时设置，不做动画。 */
    private void applyIntroCollapsed(boolean collapsed) {
        introExpanded = !collapsed;
        if (collapsed) {
            tvIntro.setMaxLines(INTRO_MAX_LINES);
            tvIntro.setEllipsize(TextUtils.TruncateAt.END);
            tvIntroToggle.setText("展开");
        } else {
            tvIntro.setMaxLines(Integer.MAX_VALUE);
            tvIntro.setEllipsize(null);
            tvIntroToggle.setText("收起");
        }
    }

    /** 用户手动切换：在折叠/展开之间做高度过渡动画 */
    private void animateIntroCollapsed(boolean collapsed) {
        // 先取消可能存在的旧动画，避免叠加
        if (introAnim != null && introAnim.isRunning()) {
            introAnim.cancel();
        }
        tvIntroToggle.setText(collapsed ? "展开" : "收起");
        introExpanded = !collapsed;

        int width = tvIntro.getWidth();
        if (width <= 0) {
            applyIntroCollapsed(collapsed);
            return;
        }
        int specW = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int specH = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

        // 完整高度
        tvIntro.setMaxLines(Integer.MAX_VALUE);
        tvIntro.setEllipsize(null);
        tvIntro.measure(specW, specH);
        int fullHeight = tvIntro.getMeasuredHeight();

        // 折叠高度（最多 3 行）
        tvIntro.setMaxLines(INTRO_MAX_LINES);
        tvIntro.setEllipsize(TextUtils.TruncateAt.END);
        tvIntro.measure(specW, specH);
        int collapsedHeight = tvIntro.getMeasuredHeight();

        // 动画期间始终按完整高度排版，仅靠改变 TextView 高度裁切来实现视觉折叠
        tvIntro.setMaxLines(Integer.MAX_VALUE);
        tvIntro.setEllipsize(null);

        int startHeight = tvIntro.getHeight();
        int endHeight = collapsed ? collapsedHeight : fullHeight;

        final ViewGroup.LayoutParams lp = tvIntro.getLayoutParams();
        introAnim = ValueAnimator.ofInt(startHeight, endHeight);
        introAnim.setDuration(INTRO_ANIM_DURATION);
        introAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        introAnim.addUpdateListener(animation -> {
            lp.height = (int) animation.getAnimatedValue();
            tvIntro.setLayoutParams(lp);
        });
        introAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 动画结束后恢复为 WRAP_CONTENT，避免固定高度影响后续布局
                if (collapsed) {
                    tvIntro.setMaxLines(INTRO_MAX_LINES);
                    tvIntro.setEllipsize(TextUtils.TruncateAt.END);
                } else {
                    tvIntro.setMaxLines(Integer.MAX_VALUE);
                    tvIntro.setEllipsize(null);
                }
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                tvIntro.setLayoutParams(lp);
            }
        });
        introAnim.start();
    }

    /**
     * 更新状态栏显示（详细分类 · 状态 · 字数）
     *
     * <p>分类一栏显示的是<b>子分类</b>（后台可多选），不再显示主分类；没有子分类时该栏整体省略。
     */
    private void updateStatusDisplay() {
        String category = joinSubCategories(currentBook.getSubCategories());

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

        StringBuilder sb = new StringBuilder();
        if (!category.isEmpty()) {
            sb.append(category).append(" · ");
        }
        sb.append(statusText).append(" · ").append(wordCountText);
        tvStatus.setText(sb.toString());
        tvStatus.setTextColor(0xFF8E8E93); // iOS 次文字
    }

    /**
     * 子分类（后端存的是 JSON 数组串，如 {@code ["玄幻","都市"]}）→ 展示文本 {@code 玄幻 · 都市}。
     *
     * <p>最多展示 {@value #MAX_STATUS_SUBS} 个，多的用「…」收尾，避免状态行被撑成多行；
     * 空/解析失败都返回空串（调用方据此省略分类这一栏）。
     */
    private String joinSubCategories(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                String n = arr.optString(i, "").trim();
                if (!n.isEmpty() && !names.contains(n)) {
                    names.add(n);
                }
            }
        } catch (Exception ignored) {
            // 兼容非标准 JSON 的老数据：去掉括号引号后按逗号切
            for (String n : s.replaceAll("[\\[\\]\"]", "").split("[,，]")) {
                String v = n.trim();
                if (!v.isEmpty() && !names.contains(v)) {
                    names.add(v);
                }
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(names.size(), MAX_STATUS_SUBS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(" · ");
            }
            sb.append(names.get(i));
        }
        if (names.size() > shown) {
            sb.append(" …");
        }
        return sb.toString();
    }

    /**
     * 检查阅读进度：先同步本地记录（所有用户，含未登录游客）；
     * 本地未命中且已登录时，再从服务器查询（游客仅用本地记录）。
     */
    private void checkReadingProgress() {
        if (currentBook == null || currentBook.getId() == null) return;
        boolean localHit = syncLocalReadingProgress();
        if (localHit) return;
        queryServerReadingProgress();
    }

    /**
     * 扫描本地 reading_records，命中当前书籍则刷新 hasRead / savedChapterIndex / savedChapterTitle
     * 并更新 UI（"继续阅读" + "上次读到：xxx"），返回 true；未命中返回 false。
     * 未命中时**不清空**已有状态，避免把从服务器查到的进度抹掉。
     * 仅在主线程调用（内部 runOnUiThread 更新 UI 安全）。
     */
    private boolean syncLocalReadingProgress() {
        if (currentBook == null || currentBook.getId() == null) return false;
        long bookId = currentBook.getId();
        SharedPreferences sp = getSharedPreferences("reading_records", MODE_PRIVATE);
        int count = sp.getInt("record_count", 0);
        for (int i = 0; i < count; i++) {
            long recBookId = sp.getLong("record_bookId_" + i, 0);
            if (recBookId == bookId) {
                hasRead = true;
                savedChapterIndex = sp.getInt("record_chapterIndex_" + i, 0);
                savedChapterTitle = sp.getString("record_chapterTitle_" + i, "");
                if (savedChapterTitle == null) savedChapterTitle = "";
                runOnUiThread(() -> {
                    btnRead.setText("继续阅读");
                    if (savedChapterTitle != null && !savedChapterTitle.isEmpty()) {
                        tvLastRead.setText("上次读到：" + savedChapterTitle);
                        tvLastRead.setVisibility(View.VISIBLE);
                    }
                });
                return true;
            }
        }
        return false;
    }

    /**
     * 已登录用户从服务器查询阅读进度并更新状态/UI；游客（userId==0）直接返回。
     * 成功发起请求后置 serverProgressQueried=true，避免 onResume 反复请求。
     */
    private void queryServerReadingProgress() {
        if (currentBook == null || currentBook.getId() == null) return;
        String userIdStr = getSharedPreferences("user_info", MODE_PRIVATE).getString("userId", "");
        long userId = userIdStr.isEmpty() ? 0 : Long.parseLong(userIdStr);
        if (userId == 0) return;
        serverProgressQueried = true;
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
                        // 更新 currentBook 的所有字段（包含 wordCount、category、subCategories 等）
                        if (updated.getWordCount() != null) {
                            currentBook.setWordCount(updated.getWordCount());
                        }
                        if (updated.getCategory() != null) {
                            currentBook.setCategory(updated.getCategory());
                        }
                        if (updated.getSubCategories() != null) {
                            currentBook.setSubCategories(updated.getSubCategories());
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

        // 章节目录：底部弹窗（从底部滑出），不再跳转章节列表页
        btnChapterList.setOnClickListener(v -> {
            if (isExternalBook()) {
                // 外站书籍：直接从后端 API 获取章节列表，不导入
                loadOnlineChapters();
            } else {
                showServerChapterSheet();
            }
        });

        // 加入书架
        btnAddShelf.setOnClickListener(v -> {
            if (isExternalBook()) {
                // 外站书籍：与网络书一致，未登录不可导入/移出书架，弹登录提醒
                if (!LoginHelper.isLoggedIn(BookDetailActivity.this)) {
                    LoginHelper.requireLogin(BookDetailActivity.this,
                            "加入书架需要登录后操作", null);
                    return;
                }
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
                // 未登录：弹登录提醒，用户同意则跳转登录页（不直接加入书架）
                LoginHelper.requireLogin(BookDetailActivity.this,
                        "加入书架需要登录后操作", null);
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
                                        Hint.show(BookDetailActivity.this, "已移出书架");
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
                                        Hint.show(BookDetailActivity.this, "已加入书架");
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
                                        Hint.show(BookDetailActivity.this, errorMsg);
                                    });
                                }
                            }
                            @Override
                            public void onFailure(@NonNull Call<ApiResponse<Bookshelf>> call, @NonNull Throwable t) {
                                runOnUiThread(() -> {
                                    btnAddShelf.setEnabled(true);
                                    tvAddShelf.setText("加入书架");
                                    ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_add);
                                    Hint.show(BookDetailActivity.this, "网络连接失败，请检查网络");
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
            Hint.show(this, "书源信息缺失");
            return;
        }

        // 旧版全局外站数据迁移到当前登录用户命名空间（幂等）
        ExternalPrefs.migrateIfNeeded(this);

        SharedPreferences sp = getSharedPreferences(ExternalPrefs.shelfName(this), MODE_PRIVATE);
        String key = sourceType + "|" + sourceBookId;

        // 检查是否已在本地书架
        if (sp.contains(key)) {
            Hint.show(this, "已在书架");
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
        Hint.show(this, "已加入书架");

        // 立即同步外站书架到服务器（用户主动操作，期望即时入库）
        ExternalSyncManager.getInstance(this).flushShelfOnly();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /**
     * 外站书籍是否已导入本地书架（按 sourceType|sourceUrl 唯一键查 external_bookshelf）。
     */
    private boolean isExternalBookInShelf() {
        String sourceType = currentBook.getSourceType();
        String sourceUrl = currentBook.getSourceUrl();
        if (sourceType == null || sourceUrl == null) return false;
        // 旧版全局外站数据迁移到当前登录用户命名空间（幂等）
        ExternalPrefs.migrateIfNeeded(this);
        String key = sourceType + "|" + sourceUrl;
        return getSharedPreferences(ExternalPrefs.shelfName(this), MODE_PRIVATE).contains(key);
    }

    /**
     * 外站书籍移出本地书架，并同步清理阅读记录与分组归属（与 BookShelfFragment 删除逻辑一致）。
     */
    private void removeExternalBookFromLocal() {
        String sourceType = currentBook.getSourceType();
        String sourceUrl = currentBook.getSourceUrl();
        if (sourceType == null || sourceUrl == null) return;
        String key = sourceType + "|" + sourceUrl;

        getSharedPreferences(ExternalPrefs.shelfName(this), MODE_PRIVATE)
                .edit().remove(key).apply();

        // 同步移除外站阅读记录（键格式与 BookShelfFragment/ReadActivity 一致）
        String recordKey = "ext_" + sourceType + "|" + sourceUrl;
        getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE)
                .edit()
                .remove(recordKey + "_chapterIndex")
                .remove(recordKey + "_chapterTitle")
                .remove(recordKey + "_page")
                .remove(recordKey + "_readTime")
                .apply();

        // 移除分组归属（键格式与 BookShelfFragment.getBookGroupKey 一致）
        getSharedPreferences("shelf_group_assign", MODE_PRIVATE)
                .edit().remove("ext_" + sourceType + "_" + sourceUrl).apply();

        // 同步删除服务器侧外站书架 + 阅读记录（离散主动操作，立即同步，避免服务器残留/被 pullAll 拉回）
        ExternalSyncManager.getInstance(this).removeShelfRemote(sourceType, sourceUrl);
        ExternalSyncManager.getInstance(this).deleteReadingRecordRemote(sourceType, sourceUrl);

        isInShelf = false;
        tvAddShelf.setText("导入书架");
        ivShelfIcon.setImageResource(R.drawable.icon_bookshelf_add);
        Hint.show(this, "已移出书架");
    }

    private String externalCacheKey() {
        return safe(currentBook.getSourceType()) + "|" + safe(currentBook.getSourceUrl());
    }

    /**
     * 外站章节列表共享缓存：供 ReadActivity 读取。
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
        extHasRead = false; // 重新检查前先复位，避免陈旧的"继续阅读"状态残留
        String sourceType = currentBook.getSourceType();
        String sourceBookId = currentBook.getSourceUrl();
        if (sourceType == null || sourceBookId == null || sourceBookId.isEmpty()) return;

        String recordKey = "ext_" + sourceType + "|" + sourceBookId;
        SharedPreferences sp = getSharedPreferences(ExternalPrefs.recordsName(this), MODE_PRIVATE);
        int localIdx = sp.getInt(recordKey + "_chapterIndex", -1);
        String localTitle = sp.getString(recordKey + "_chapterTitle", "");
        extLocalSyncedIdx = localIdx;   // 记录本次已同步的本地值
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
        extHasRead = true; // 标记存在外站阅读记录，避免被后续 displayBookInfo() 覆盖回"在线阅读"
        btnRead.setText("继续阅读");
        if (chapterTitle != null && !chapterTitle.isEmpty()) {
            tvLastRead.setText("上次读到：" + chapterTitle);
            tvLastRead.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 外站书籍：加载章节列表并以底部弹窗（BottomSheet）展示。
     * 使用统一的 fetchOnlineChapters（内存 → 持久化 → 网络），失败时给出书源失效兜底。
     */
    private void loadOnlineChapters() {
        if (currentBook.getSourceType() == null || currentBook.getSourceUrl() == null) {
            Hint.show(this, "书源信息缺失");
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
                showExternalChapterSheet(chapters);
            }
            @Override
            public void onFail(String msg, boolean sourceMayDown) {
                btnChapterList.setEnabled(true);
                tvLatestChapter.setText("外站书籍 · 在线阅读");
                if (sourceMayDown) {
                    showSourceUnavailableDialog(msg);
                } else {
                    Hint.show(BookDetailActivity.this, msg);
                }
            }
        });
    }

    // ==================== 详情页「目录」底部弹窗 ====================

    /** 本站/本地书目录弹窗：先弹出面板显示加载中，再异步拉章节与分卷 */
    private void showServerChapterSheet() {
        if (currentBook == null) return;
        showChapterSheetFrame();
        sheetInfo.setVisibility(View.VISIBLE);
        sheetInfo.setText("加载中...");
        sheetChapters = new ArrayList<>();
        sheetVolumes.clear();
        // 必须同步重建渲染行：第二次打开时 sheetRows 还留着上次的旧行，
        // 而 sheetChapters 已清空 → RecyclerView 立即布局绑定 → sheetChapters.get(realIndex) 越界崩溃
        rebuildSheetRows();
        if (sheetAdapter != null) sheetAdapter.notifyDataSetChanged();

        long bookId = currentBook.getId();
        // 本地导入书：章节仅存在本地 SP
        if (isLocalImportedBook(bookId)) {
            loadLocalChaptersIntoSheet(bookId);
            return;
        }

        final List<ChapterDto> dtoHolder = new ArrayList<>();
        final List<MajorChapter> majorHolder = new ArrayList<>();
        final boolean[] chapterReady = {false};
        final boolean[] majorReady = {false};

        Runnable render = () -> runOnUiThread(() -> {
            if (!chapterReady[0] || !isSheetShowing()) return;
            sheetChapters = new ArrayList<>();
            List<String> sortKeys = new ArrayList<>();
            for (ChapterDto dto : dtoHolder) {
                String t = dto.getTitle() == null ? "" : dto.getTitle();
                sheetChapters.add(new String[]{t});
                sortKeys.add(dto.getSortKey());
            }
            sheetVolumes.clear();
            sheetVolumes.addAll(majorReady[0]
                    ? VolumeDeriver.derive(sortKeys, majorHolder)
                    : new ArrayList<>());
            sheetInfo.setText(sheetChapters.isEmpty() ? "暂无章节" : "共 " + sheetChapters.size() + " 章");
            rebuildSheetRows();
            if (sheetAdapter != null) sheetAdapter.notifyDataSetChanged();
            TocOrder.scrollToFirstRow(sheetRv);
            scrollToSheetCurrent();
        });

        RetrofitClient.getApiService().getChapters(bookId)
                .enqueue(new Callback<ApiResponse<List<ChapterDto>>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<List<ChapterDto>>> call,
                                           @NonNull Response<ApiResponse<List<ChapterDto>>> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().isSuccess() && response.body().getData() != null) {
                            dtoHolder.addAll(response.body().getData());
                        }
                        chapterReady[0] = true;
                        render.run();
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<List<ChapterDto>>> call, @NonNull Throwable t) {
                        chapterReady[0] = true;
                        render.run();
                    }
                });

        RetrofitClient.getApiService().getMajorChapters(bookId)
                .enqueue(new Callback<ApiResponse<List<MajorChapter>>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<List<MajorChapter>>> call,
                                           @NonNull Response<ApiResponse<List<MajorChapter>>> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().isSuccess() && response.body().getData() != null) {
                            majorHolder.addAll(response.body().getData());
                        }
                        majorReady[0] = true;
                        render.run();
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<List<MajorChapter>>> call, @NonNull Throwable t) {
                        majorReady[0] = true;
                        render.run();
                    }
                });
    }

    /** 外站书目录弹窗：数据已就绪（fetchOnlineChapters 回调），直接展示 */
    private void showExternalChapterSheet(List<String[]> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            Hint.show(this, "章节列表为空");
            return;
        }
        showChapterSheetFrame();
        sheetChapters = new ArrayList<>(chapters);
        sheetVolumes.clear();
        sheetInfo.setVisibility(View.VISIBLE);
        // 信息行对齐截图：复用详情页的连载信息（如「连载至1160章 连续更新280天」）；
        // 已被「共X章」覆盖时显示章节总数
        String cur = tvLatestChapter.getText().toString();
        sheetInfo.setText(cur.startsWith("共") ? "共 " + sheetChapters.size() + " 章" : cur);
        rebuildSheetRows();
        if (sheetAdapter != null) sheetAdapter.notifyDataSetChanged();
        TocOrder.scrollToFirstRow(sheetRv);
        scrollToSheetCurrent();
    }

    /** 本地导入书判定：id 在 local_books SP 有记录 */
    private boolean isLocalImportedBook(long bookId) {
        if (bookId <= 0 || currentBook == null) return false;
        if (currentBook.getSourceType() != null || currentBook.getSourceUrl() != null) return false;
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0) == bookId) return true;
        }
        return false;
    }

    /** 本地导入书：从 local_books SP 读章节标题 */
    private void loadLocalChaptersIntoSheet(long bookId) {
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        List<String[]> titles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (sp.getLong("book_id_" + i, 0) == bookId) {
                int chCount = sp.getInt("chapter_count_" + i, 0);
                for (int j = 0; j < chCount; j++) {
                    titles.add(new String[]{sp.getString("chapter_title_" + i + "_" + j, "第" + (j + 1) + "章")});
                }
                break;
            }
        }
        if (!isSheetShowing()) return;
        sheetChapters = titles;
        sheetInfo.setText(sheetChapters.isEmpty() ? "暂无章节" : "共 " + sheetChapters.size() + " 章");
        rebuildSheetRows();
        if (sheetAdapter != null) sheetAdapter.notifyDataSetChanged();
        TocOrder.scrollToFirstRow(sheetRv);
        scrollToSheetCurrent();
    }

    /** 构建并展示弹窗骨架（面板贴底、高约 82% 屏高、从底部滑入；点外部/收起箭头关闭） */
    private void showChapterSheetFrame() {
        if (isSheetShowing()) return;
        View content = LayoutInflater.from(this).inflate(R.layout.popup_chapter_sheet, null);
        sheetSortBtn = content.findViewById(R.id.btn_sheet_sort);
        sheetInfo = content.findViewById(R.id.tv_sheet_info);
        sheetRv = content.findViewById(R.id.rv_sheet_chapters);
        sheetSortBtn.setText(TocOrder.label(sheetOrder.isDescending()));
        sheetSortBtn.setOnClickListener(v -> toggleSheetSort());
        content.findViewById(R.id.iv_sheet_close).setOnClickListener(v -> dismissChapterSheet());
        sheetRv.setLayoutManager(new LinearLayoutManager(this));
        sheetAdapter = new SheetAdapter();
        sheetRv.setAdapter(sheetAdapter);

        // 列表跟手：目录滚到顶部（无法再上滚）后继续下拉，弹窗跟随手指；
        // 松手下拉超面板高 30% → 收起，否则弹回。列表未到顶时正常滚动不受影响。
        sheetRv.setOnTouchListener(new View.OnTouchListener() {
            float downRawY;
            boolean tracking;
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = ev.getRawY();
                        tracking = false;
                        return false;   // 先放行给列表，保持其滚动能力
                    case MotionEvent.ACTION_MOVE: {
                        float dy = ev.getRawY() - downRawY;
                        if (!tracking) {
                            int slop = android.view.ViewConfiguration
                                    .get(BookDetailActivity.this).getScaledTouchSlop();
                            if (dy > slop && !sheetRv.canScrollVertically(-1)) {
                                tracking = true;
                                downRawY = ev.getRawY();   // 以接管点为基准，避免跳变
                                if (content.animate() != null) content.animate().cancel();
                            }
                            return tracking;   // 接管后拦下事件，列表停止响应
                        }
                        if (dy > 0) content.setTranslationY(dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!tracking) return false;
                        tracking = false;
                        float released = Math.max(0f, ev.getRawY() - downRawY);
                        float h = content.getHeight() > 0 ? content.getHeight() : 1f;
                        if (released > h * 0.30f) {
                            dismissChapterSheet();   // 从当前位置继续滑出
                        } else {
                            content.animate().translationY(0f).setDuration(200)
                                    .setInterpolator(new AccelerateDecelerateInterpolator()).start();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        sheetPanelView = content;

        // 顶栏跟手拖拽：按住标题栏下拉，松手超过阈值（面板高 30%）即收起，否则弹回
        View titleBar = content.findViewById(R.id.popup_title_bar);
        titleBar.setOnTouchListener(new View.OnTouchListener() {
            float downRawY;
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = ev.getRawY();
                        if (content.animate() != null) content.animate().cancel();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = ev.getRawY() - downRawY;
                        if (dy > 0) content.setTranslationY(dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float released = Math.max(0f, ev.getRawY() - downRawY);
                        float h = content.getHeight() > 0 ? content.getHeight() : 1f;
                        if (released > h * 0.30f) {
                            dismissChapterSheet();  // 从当前位置继续滑出
                        } else {
                            content.animate().translationY(0f).setDuration(200)
                                    .setInterpolator(new AccelerateDecelerateInterpolator()).start();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        int panelH = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
        android.widget.FrameLayout host = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelH, Gravity.BOTTOM);
        content.setLayoutParams(lp);
        host.addView(content);

        chapterSheetPopup = new PopupWindow(host,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        chapterSheetPopup.setAnimationStyle(0);
        chapterSheetPopup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        // 点面板外：拦截 ACTION_OUTSIDE，走带滑出动画的关闭（否则焦点弹窗会瞬时 dismiss）
        chapterSheetPopup.setTouchInterceptor((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_OUTSIDE) {
                dismissChapterSheet();
                return true;
            }
            return false;
        });
        chapterSheetPopup.setOnDismissListener(() -> {
            chapterSheetPopup = null;
            sheetPanelView = null;
            sheetDismissing = false;
        });
        chapterSheetPopup.showAtLocation(getWindow().getDecorView(), Gravity.BOTTOM, 0, 0);

        // 入场：首帧绘制前把面板移到屏幕下沿之外，再滑入（避免首帧闪现）
        content.setVisibility(View.INVISIBLE);
        host.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        float h = content.getHeight() > 0 ? content.getHeight() : panelH;
                        if (h <= 0) return true;
                        host.getViewTreeObserver().removeOnPreDrawListener(this);
                        content.setVisibility(View.VISIBLE);
                        content.setTranslationY(h);
                        content.animate().translationY(0f).setDuration(240)
                                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
                        return true;
                    }
                });

        // 打开目录前先同步本地记录，确保用的是最新进度（从阅读器返回详情页后状态可能陈旧）
        if (!isExternalBook()) {
            syncLocalReadingProgress();
        }
        sheetCurrentIndex = hasRead ? savedChapterIndex : -1;
    }

    private boolean isSheetShowing() {
        return chapterSheetPopup != null && chapterSheetPopup.isShowing();
    }

    /** 收起弹窗：面板从当前位置向下滑出屏幕后再 dismiss（与入场滑入对称） */
    private void dismissChapterSheet() {
        if (chapterSheetPopup == null || !chapterSheetPopup.isShowing()) return;
        if (sheetDismissing) return;
        View panel = sheetPanelView;
        if (panel == null) {
            chapterSheetPopup.dismiss();
            return;
        }
        sheetDismissing = true;
        float h = panel.getHeight() > 0 ? panel.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        panel.animate().translationY(h).setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (chapterSheetPopup != null) chapterSheetPopup.dismiss();
                })
                .start();
    }

    /** 正序/倒序切换：正序有卷头分组（覆盖完整时），倒序整表反转（卷头仅在正序展示） */
    private void toggleSheetSort() {
        sheetOrder.toggle();
        sheetSortBtn.setText(TocOrder.label(sheetOrder.isDescending()));
        rebuildSheetRows();
        if (sheetAdapter != null) sheetAdapter.notifyDataSetChanged();
        TocOrder.scrollToFirstRow(sheetRv);
    }

    /** 按当前排序与分卷构建渲染行；分卷不能完整覆盖全部章节时退回平铺，绝不丢章 */
    private void rebuildSheetRows() {
        sheetRows.clear();
        boolean desc = sheetOrder.isDescending();
        int n = sheetChapters.size();
        if (!desc && !sheetVolumes.isEmpty() && n > 0) {
            int covered = 0;
            for (LocalBookParser.VolumeInfo v : sheetVolumes) {
                if (v.end >= v.childStart) covered += v.end - v.childStart + 1;
            }
            if (covered == n) {
                for (LocalBookParser.VolumeInfo v : sheetVolumes) {
                    if (v.title != null && !v.title.isEmpty()) sheetRows.add(v.title);
                    for (int i = v.childStart; i <= v.end && i < n; i++) sheetRows.add(new int[]{i});
                }
                return;
            }
        }
        for (int p = 0; p < n; p++) {
            sheetRows.add(new int[]{desc ? (n - 1 - p) : p});
        }
    }

    /** 当前阅读章节在渲染行中的位置（用于打开弹窗后自动定位） */
    private void scrollToSheetCurrent() {
        if (sheetCurrentIndex < 0 || sheetRv == null) return;
        sheetRv.post(() -> {
            if (!isSheetShowing()) return;
            int pos = -1;
            for (int i = 0; i < sheetRows.size(); i++) {
                Object row = sheetRows.get(i);
                if (row instanceof int[] && ((int[]) row)[0] == sheetCurrentIndex) {
                    pos = i;
                    break;
                }
            }
            if (pos >= 0 && sheetRv.getLayoutManager() instanceof LinearLayoutManager) {
                ((LinearLayoutManager) sheetRv.getLayoutManager())
                        .scrollToPositionWithOffset(Math.max(0, pos - 2), 0);
            }
        });
    }

    /** 点击章节：跳转阅读器并关闭弹窗（详情页保留，返回时可回此页）。
     *  chapterExplicit=true：显式跳章，ReadActivity 直接进入点击章节，不被阅读进度覆盖 */
    private void openSheetChapter(int realIndex) {
        dismissChapterSheet();
        Intent intent = new Intent(this, ReadActivity.class);
        intent.putExtra("book", currentBook);
        intent.putExtra("chapterExplicit", true);
        if (isExternalBook()) {
            intent.putExtra("isExternal", true);
            putChaptersExtra(this, intent, currentBook, sheetChapters);
            intent.putExtra("chapterIndex", realIndex);
            try { startActivity(intent); }
            catch (RuntimeException te) {
                Intent fallback = new Intent(this, ReadActivity.class);
                fallback.putExtra("book", currentBook);
                fallback.putExtra("isExternal", true);
                fallback.putExtra("chaptersViaCache", true);
                fallback.putExtra("chapterExplicit", true);
                fallback.putExtra("chapterIndex", realIndex);
                startActivity(fallback);
            }
        } else {
            intent.putExtra("chapterIndex", realIndex);
            startActivity(intent);
        }
    }

    /** 当前章行背景：淡蓝底 + 左侧 3dp 蓝色竖条（绘制与视图宽度无关，无需测量） */
    private static class CurrentChapterBg extends Drawable {
        private final Paint paint = new Paint();
        private final int baseColor;
        private final int barColor;
        private final int barWidth;

        CurrentChapterBg(int baseColor, int barColor, int barWidth) {
            this.baseColor = baseColor;
            this.barColor = barColor;
            this.barWidth = barWidth;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            paint.setColor(baseColor);
            canvas.drawRect(b, paint);
            paint.setColor(barColor);
            canvas.drawRect(b.left, b.top, b.left + barWidth, b.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** 行尾"当前"标记：淡蓝圆角底 + 小号蓝字（ReplacementSpan 自行绘制，字号约 0.75 倍） */
    private static class CurrentTagSpan extends ReplacementSpan {
        private final int bgColor;
        private final int textColor;
        private final float textSizePx;
        private final float padH;
        private final float radius;

        CurrentTagSpan(int bgColor, int textColor, float textSizePx, float padH, float radius) {
            this.bgColor = bgColor;
            this.textColor = textColor;
            this.textSizePx = textSizePx;
            this.padH = padH;
            this.radius = radius;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            float oldSize = paint.getTextSize();
            paint.setTextSize(textSizePx);
            float w = paint.measureText(text, start, end);
            paint.setTextSize(oldSize);
            return (int) (w + padH * 2);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            float oldSize = paint.getTextSize();
            int oldColor = paint.getColor();
            paint.setTextSize(textSizePx);

            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            float textW = paint.measureText(text, start, end);
            float bgLeft = x;
            float bgRight = x + textW + padH * 2;
            float bgTop = y + fm.ascent;
            float bgBottom = y + fm.descent;

            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(bgColor);
            canvas.drawRoundRect(new RectF(bgLeft, bgTop, bgRight, bgBottom), radius, radius, bg);

            paint.setColor(textColor);
            canvas.drawText(text, start, end, x + padH, y, paint);

            paint.setTextSize(oldSize);
            paint.setColor(oldColor);
        }
    }

    /** 当前章行背景单例（复用同一实例，避免每次绑定都新建） */
    private Drawable getCurrentChapterBg() {
        if (currentChapterBgDrawable == null) {
            float d = getResources().getDisplayMetrics().density;
            int barW = Math.max(1, Math.round(3 * d));
            currentChapterBgDrawable = new CurrentChapterBg(0x14007AFF, 0xFF007AFF, barW);
        }
        return currentChapterBgDrawable;
    }

    /**
     * 构造当前章标题文案：章节标题 + 行尾小号"当前"标记（淡蓝圆角底 + 蓝字，字号约 0.75 倍）。
     * 仅当前章行调用；非当前章行用纯文本 setText 即可清除所有 span。
     */
    private CharSequence buildCurrentChapterTitle(TextView tv, String title) {
        String tag = "当前";
        String full = title + "  " + tag;
        SpannableString ss = new SpannableString(full);
        float d = getResources().getDisplayMetrics().density;
        float smallSize = tv.getTextSize() * 0.75f;
        float padH = 5 * d;
        float radius = 3 * d;
        int tagStart = full.length() - tag.length();
        ss.setSpan(new CurrentTagSpan(0x1F007AFF, 0xFF007AFF, smallSize, padH, radius),
                tagStart, full.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** 目录弹窗列表适配器：卷头（灰字）+ 章节行（当前章高亮） */
    private class SheetAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_CHAPTER = 1;

        @Override
        public int getItemViewType(int position) {
            return sheetRows.get(position) instanceof String ? TYPE_HEADER : TYPE_CHAPTER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(BookDetailActivity.this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            float d = getResources().getDisplayMetrics().density;
            if (viewType == TYPE_HEADER) {
                tv.setPadding((int) (20 * d), (int) (12 * d), (int) (20 * d), (int) (6 * d));
                tv.setTextSize(13);
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else {
                tv.setPadding((int) (20 * d), (int) (13 * d), (int) (20 * d), (int) (13 * d));
                tv.setTextSize(15);
                tv.setClickable(true);
                tv.setFocusable(true);
                tv.setBackgroundResource(android.R.drawable.list_selector_background);
            }
            return new RecyclerView.ViewHolder(tv) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TextView tv = (TextView) holder.itemView;
            Object row = sheetRows.get(position);
            if (row instanceof String) {
                tv.setText((String) row);
                tv.setTextColor(0xFF8E8E93);
                return;
            }
            int realIndex = ((int[]) row)[0];
            // 防御：数据重置与渲染行短暂不一致时不崩溃，兜底显示章节号
            String title = "第" + (realIndex + 1) + "章";
            if (realIndex >= 0 && realIndex < sheetChapters.size()) {
                String[] ch = sheetChapters.get(realIndex);
                if (ch != null && ch.length > 0 && ch[0] != null && !ch[0].isEmpty()) {
                    title = ch[0];
                }
            }
            if (realIndex == sheetCurrentIndex) {
                // 当前章：淡蓝底 + 左侧蓝色竖条 + 蓝字粗体 + 行尾小号"当前"标记
                tv.setBackground(getCurrentChapterBg());
                tv.setTextColor(0xFF007AFF);
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                tv.setText(buildCurrentChapterTitle(tv, title));
            } else {
                // 非当前章：彻底重置（纯文本 setText 清除所有 span；背景/字色/字重恢复原样），
                // 避免 RecyclerView 复用把"当前"标记或竖条串到别的行上
                tv.setTypeface(android.graphics.Typeface.DEFAULT);
                tv.setTextColor(0xFF1D1D1F);
                tv.setBackgroundColor(0x00000000);
                tv.setText(title);
            }
            tv.setOnClickListener(v -> openSheetChapter(realIndex));
        }

        @Override
        public int getItemCount() {
            return sheetRows.size();
        }
    }

    /**
     * 外站书籍：直接在线阅读。
     * 优先使用内存/持久化缓存的章节列表；若有已保存进度，传入 chapterIndex 让 ReadActivity 直接跳到该章。
     * 失败时给出书源失效兜底。
     */
    private void startOnlineReading() {
        if (currentBook.getSourceType() == null || currentBook.getSourceUrl() == null) {
            Hint.show(this, "书源信息缺失");
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
                    Hint.show(BookDetailActivity.this, "加载超时，请检查网络后重试");
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
                        Hint.show(BookDetailActivity.this, msg);
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
