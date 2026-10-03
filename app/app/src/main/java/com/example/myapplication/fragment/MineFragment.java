package com.example.myapplication.fragment;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.fragment.app.Fragment;

import com.example.myapplication.R;
import com.example.myapplication.activity.AboutActivity;
import com.example.myapplication.activity.FeedbackActivity;
import com.example.myapplication.activity.MessageCenterActivity;
import com.example.myapplication.activity.LoginActivity;
import com.example.myapplication.activity.ProfileActivity;
import com.example.myapplication.activity.ReadingRecordActivity;
import com.example.myapplication.activity.SettingsActivity;
import com.example.myapplication.api.ApiService;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.utils.CacheManager;
import com.example.myapplication.utils.LoginHelper;
import com.example.myapplication.utils.ProfileSync;
import com.example.myapplication.utils.ReadTimeText;
import com.example.myapplication.utils.ThemeAttrs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 个人中心页 */
public class MineFragment extends Fragment {

    private ImageView ivAvatar;
    private TextView tvNickname;
    private TextView tvUid;
    private TextView tvUnreadBadge;
    private TextView tvStatBooks;
    private TextView tvStatTime;
    private TextView tvCacheSize;
    private View layoutStreak;
    private TextView tvStreak;
    private TextView btnEditProfile;

    private SharedPreferences sp;

    /** 抽屉里逐项勾选状态：分项 → 是否清理。LinkedHashMap 保住展示顺序。 */
    private final Map<CacheManager.Item, Boolean> cacheChecked = new LinkedHashMap<>();
    /** 当前打开的清理抽屉；为 null 表示没开。 */
    private Dialog cacheSheet;
    /** 抽屉根视图（含遮罩），用于跟手拖拽时整块下移。 */
    private View cacheSheetRoot;
    /** 独立遮罩 View，跟手拖拽时变淡的对象。 */
    private View cacheSheetDim;
    /** 抽屉面板本体（圆角白卡），缩放目标与拖拽对象。 */
    private View cacheSheetPanel;
    /** 抽屉面板的屏幕高度（布局完成后才有值）。 */
    private int cacheSheetPanelH;
    /** 遮罩当前淡出的进度：0 = 满遮罩（#73000000 满值），1 = 完全透明。 */
    private float sheetDimP;
    /** 抽屉打开期间缓存的各分项体积，供勾选时重算大字。 */
    private Map<CacheManager.Item, Long> cachedSizes;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mine, container, false);

        // 不再手动加状态栏padding：activity_main.xml 的 fitsSystemWindows 已让系统自动避让
        updateStatusBarColor();

        initView(view);
        sp = getActivity().getSharedPreferences("user_info", getActivity().MODE_PRIVATE);
        loadUserProfile();

        // 通用设置：跳转到设置页面
        view.findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(getActivity(), SettingsActivity.class)));

        // 阅读记录（本地记录，游客也可查看）
        view.findViewById(R.id.btn_reading_record).setOnClickListener(v ->
                startActivity(new Intent(getActivity(), ReadingRecordActivity.class)));

        // 编辑资料 / 立即登录：已登录进个人资料；未登录直接跳登录页（按钮文案随登录态切换）
        view.findViewById(R.id.btn_edit_profile).setOnClickListener(v -> {
            if (LoginHelper.isLoggedIn(getActivity())) {
                startActivity(new Intent(getActivity(), ProfileActivity.class));
            } else {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                intent.putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true);
                startActivity(intent);
            }
        });

        // 意见反馈（需登录）
        view.findViewById(R.id.btn_feedback).setOnClickListener(v -> LoginHelper.requireLogin(getActivity(),
                () -> startActivity(new Intent(getActivity(), FeedbackActivity.class))));

        // 消息中心（需登录）
        view.findViewById(R.id.btn_message_center).setOnClickListener(v -> LoginHelper.requireLogin(getActivity(),
                () -> startActivity(new Intent(getActivity(), MessageCenterActivity.class))));

        // 关于我们：复用设置页的「关于书阁」
        view.findViewById(R.id.btn_about_us).setOnClickListener(v ->
                startActivity(new Intent(getActivity(), AboutActivity.class)));

        // 清理缓存
        view.findViewById(R.id.btn_clear_cache).setOnClickListener(v -> confirmClearCache());

        return view;
    }

    private void initView(View view) {
        ivAvatar = view.findViewById(R.id.iv_avatar);
        tvNickname = view.findViewById(R.id.tv_nickname);
        tvUid = view.findViewById(R.id.tv_uid);
        tvUnreadBadge = view.findViewById(R.id.tv_unread_badge);
        tvStatBooks = view.findViewById(R.id.tv_stat_books);
        tvStatTime = view.findViewById(R.id.tv_stat_time);
        tvCacheSize = view.findViewById(R.id.tv_cache_size);
        layoutStreak = view.findViewById(R.id.layout_streak);
        tvStreak = view.findViewById(R.id.tv_streak);
        btnEditProfile = view.findViewById(R.id.btn_edit_profile);
    }

    /**
     * 状态栏与英雄区色带统一。
     *
     * <p>activity_main.xml 的根布局带 fitsSystemWindows=true，fragment 从状态栏下方才开始绘制，
     * 英雄区色带铺不到状态栏后面；这里把状态栏染成渐变顶部的同色（?attr/appBandTop，
     * 素白档是 #2E8FFF、宣纸档是檀褐），让两者连成一片、看不出断缝。
     */
    private void updateStatusBarColor() {
        if (getActivity() == null) return;
        getActivity().getWindow().setStatusBarColor(
                ThemeAttrs.color(getActivity(), R.attr.appBandTop, 0));
        int flags = getActivity().getWindow().getDecorView().getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getActivity().getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatusBarColor();
        loadUserProfile();   // 内部会拉起 loadReadingStats（含连续天数徽章）
        loadUnreadCount();
        loadCacheSize();
        // 云端资料对齐：Web 端改了头像/昵称等，切回「我的」页即刷新，无需重登
        ProfileSync.sync(getActivity(), changed -> {
            if (changed && isAdded()) loadUserProfile();
        });
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            updateStatusBarColor();
        }
    }

    /**
     * 缓存体积：异步读取，避免在主线程递归扫目录。
     *
     * <p>展示两个数字：可清缓存 + 本地书数据（只统计不清理，让用户知道空间去哪了）。
     */
    private void loadCacheSize() {
        if (tvCacheSize == null || !isAdded()) return;
        final Context ctx = getActivity();
        if (ctx == null) return;
        new Thread(() -> {
            final long cache = CacheManager.getCacheBytes(ctx);
            final long local = CacheManager.getLocalBookBytes(ctx);
            if (!isAdded() || tvCacheSize == null) return;
            tvCacheSize.post(() -> {
                if (!isAdded() || tvCacheSize == null) return;
                tvCacheSize.setText(formatCacheSummary(cache, local));
            });
        }).start();
    }

    /** 缓存行右侧文案：只显示缓存；有本地书时追加，让用户知道空间去哪了。 */
    private String formatCacheSummary(long cache, long local) {
        String base = CacheManager.formatSize(cache);
        if (local > 0) {
            return getString(R.string.mine_cache_with_local, base, CacheManager.formatSize(local));
        }
        return base;
    }

    /**
     * 清缓存：弹出底部抽屉，逐项列出可清理内容，用户勾选后再清。
     *
     * <p>清理范围是「可清缓存」——临时文件、章节/分页缓存、在线字体、更新包、头像历史残留，
     * 全部可再生，不影响已导入的本地书与阅读进度。本地书数据只展示、不清理。
     */
    private void confirmClearCache() {
        final Context ctx = getActivity();
        if (ctx == null || cacheSheet != null) return;

        new Thread(() -> {
            // 逐项取体积：用来渲染明细行右侧的数字与顶部大字
            final Map<CacheManager.Item, Long> sizes = new LinkedHashMap<>();
            for (CacheManager.Item item : CacheManager.Item.values()) {
                sizes.put(item, CacheManager.getItemBytes(ctx, item));
            }
            final long local = CacheManager.getLocalBookBytes(ctx);
            if (!isAdded()) return;
            View anchor = tvCacheSize != null ? tvCacheSize : getView();
            if (anchor == null) return;
            anchor.post(() -> {
                if (!isAdded() || getActivity() == null) return;
                showClearCacheSheet(sizes, local);
            });
        }).start();
    }

    /**
     * 展示清理抽屉。
     *
     * @param sizes 各分项的当前体积
     * @param local 本地书数据体积（只展示、不参与清理）
     */
    private void showClearCacheSheet(Map<CacheManager.Item, Long> sizes, long local) {
        final Context ctx = getActivity();
        if (ctx == null || getView() == null) return;

        cacheChecked.clear();
        // 默认全选：用户点「清理缓存」的意图就是清理，逐项取消才是少数操作
        for (CacheManager.Item item : CacheManager.Item.values()) {
            long sz = sizes.get(item) == null ? 0L : sizes.get(item);
            cacheChecked.put(item, sz > 0);
        }
        cachedSizes = sizes;

        final Dialog sheet = new Dialog(ctx, R.style.CacheSheetStyle);
        cacheSheet = sheet;
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_clear_cache_sheet, null);
        sheet.setContentView(root);
        Window w = sheet.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        sheet.setCanceledOnTouchOutside(true);

        cacheSheetRoot = root;
        cacheSheetDim = root.findViewById(R.id.v_cache_dim);
        cacheSheetPanel = root.findViewById(R.id.sheet_panel_clear);

        // 顶部圆角裁切：面板自带 bg_sheet_top_rounded，但子视图会溢出圆角，需要 clipToOutline
        if (cacheSheetPanel != null) {
            cacheSheetPanel.setClipToOutline(true);
            cacheSheetPanel.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    float r = getResources().getDisplayMetrics().density * 18f;
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight() + (int) r, r);
                }
            });
        }

        // 明细行
        LinearLayout itemBox = root.findViewById(R.id.box_cache_items);
        TextView tvLocal = root.findViewById(R.id.tv_cache_local_note);
        TextView tvSelectAll = root.findViewById(R.id.tv_select_all);
        ImageView ivSelectAll = root.findViewById(R.id.iv_select_all);
        View btnOk = root.findViewById(R.id.btn_cache_confirm);

        if (itemBox != null) {
            itemBox.removeAllViews();
            itemBox.setOrientation(LinearLayout.VERTICAL);
            for (CacheManager.Item item : CacheManager.Item.values()) {
                itemBox.addView(buildCacheItemRow(ctx, item, sizes.get(item) == null ? 0L : sizes.get(item)));
            }
        }

        if (tvLocal != null) {
            if (local > 0) {
                tvLocal.setVisibility(View.VISIBLE);
                tvLocal.setText(getString(R.string.mine_cache_local_note,
                        CacheManager.formatSize(local)));
            } else {
                // 没有本地书时整块说明去掉，避免抽屉里出现一句无关的话
                tvLocal.setVisibility(View.GONE);
                View noteBox = root.findViewById(R.id.box_cache_note);
                if (noteBox != null) noteBox.setVisibility(View.GONE);
            }
        }

        View.OnClickListener toggleAll = v -> {
            boolean target = false;
            for (CacheManager.Item item : CacheManager.Item.values()) {
                Long sz = sizes.get(item);
                if (sz != null && sz > 0 && !Boolean.TRUE.equals(cacheChecked.get(item))) {
                    target = true;   // 还有没选的 → 本次全选
                    break;
                }
            }
            for (CacheManager.Item item : CacheManager.Item.values()) {
                Long sz = sizes.get(item);
                cacheChecked.put(item, target && sz != null && sz > 0);
            }
            syncItemRows(itemBox);
            refreshSheetHeader();
        };
        if (ivSelectAll != null) ivSelectAll.setOnClickListener(toggleAll);
        if (tvSelectAll != null) tvSelectAll.setOnClickListener(toggleAll);

        View btnCancel = root.findViewById(R.id.btn_cache_cancel);
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dismissClearCacheSheet());

        btnOk.setOnClickListener(v -> {
            List<CacheManager.Item> picked = new ArrayList<>();
            for (CacheManager.Item item : CacheManager.Item.values()) {
                if (Boolean.TRUE.equals(cacheChecked.get(item))) picked.add(item);
            }
            dismissClearCacheSheet();
            if (!picked.isEmpty()) doClearCache(picked);
        });

        // 遮罩点击关闭：setCanceledOnTouchOutside 只认窗口外的触摸，
        // 而遮罩是窗口内铺满的 View（touch 事件不会冒泡给父级），必须直接挂在它身上
        if (cacheSheetDim != null) {
            cacheSheetDim.setOnClickListener(v -> dismissClearCacheSheet());
        }

        refreshSheetHeader();
        setupSheetDrag();

        sheet.setOnDismissListener(d -> {
            cacheSheet = null;
            cacheSheetRoot = null;
            cacheSheetDim = null;
            cacheSheetPanel = null;
            cacheSheetPanelH = 0;
            sheetDimP = 0f;
            cachedSizes = null;
        });

        // 入场：先在首帧前把面板推到屏幕外，再滑上来
        if (cacheSheetPanel != null) {
            cacheSheetPanel.setVisibility(View.INVISIBLE);
            final View panel = cacheSheetPanel;
            panel.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    panel.getViewTreeObserver().removeOnPreDrawListener(this);
                    cacheSheetPanelH = panel.getHeight();
                    panel.setTranslationY(cacheSheetPanelH);
                    panel.setVisibility(View.VISIBLE);
                    panel.animate()
                            .translationY(0f)
                            .setDuration(240)
                            .setInterpolator(new AccelerateDecelerateInterpolator())
                            .start();
                    return true;
                }
            });
        }

        sheet.show();
    }

    /** 构造一行可勾选的缓存明细。 */
    private View buildCacheItemRow(Context ctx, final CacheManager.Item item, long size) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        row.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        row.setTag(item);
        // 设置可点背景，点按有反馈
        row.setBackgroundResource(R.drawable.bg_cache_item_row);
        row.setClickable(true);

        ImageView cb = new ImageView(ctx);
        int cbSize = (int) (20 * d);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(cbSize, cbSize);
        cbLp.setMarginEnd((int) (12 * d));
        cb.setLayoutParams(cbLp);
        cb.setImageResource(R.drawable.sel_cache_checkbox);
        cb.setTag("cb");
        row.addView(cb);

        TextView name = new TextView(ctx);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameLp);
        name.setTextSize(13.5f);
        name.setTextColor(ThemeAttrs.color(ctx, R.attr.appTextPrimary, 0));
        name.setText(itemTitle(item));
        name.setTag("name");
        row.addView(name);

        TextView sz = new TextView(ctx);
        sz.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sz.setTextSize(13f);
        sz.setTextColor(ThemeAttrs.color(ctx, R.attr.appTextTertiary, 0));
        sz.setText(CacheManager.formatSize(size));
        sz.setTag("size");
        row.addView(sz);

        // 体积为 0 的项：整行置灰不可点，避免用户勾了却清不出东西
        if (size > 0) {
            row.setOnClickListener(v -> {
                boolean now = !Boolean.TRUE.equals(cacheChecked.get(item));
                cacheChecked.put(item, now);
                syncItemRow(row);
                refreshSheetHeader();
            });
        } else {
            row.setEnabled(false);
            row.setClickable(false);
        }
        syncItemRow(row);
        return row;
    }

    /** 把一行的勾选态同步到控件上：勾选框切图 + 文字整体降灰。 */
    private void syncItemRow(View row) {
        if (row == null) return;
        Object tag = row.getTag();
        if (!(tag instanceof CacheManager.Item)) return;
        boolean on = Boolean.TRUE.equals(cacheChecked.get((CacheManager.Item) tag));
        View cb = row.findViewWithTag("cb");
        if (cb != null) cb.setSelected(on);
        View name = row.findViewWithTag("name");
        if (name instanceof TextView) {
            ((TextView) name).setTextColor(ThemeAttrs.color(row.getContext(),
                    on ? R.attr.appTextPrimary : R.attr.appTextSecondary, 0));
        }
        View szView = row.findViewWithTag("size");
        if (szView instanceof TextView) {
            szView.setAlpha(on ? 1f : 0.45f);
        }
    }

    /** 全选/全不选后，把所有行刷一遍。 */
    private void syncItemRows(LinearLayout box) {
        if (box == null) return;
        for (int i = 0; i < box.getChildCount(); i++) {
            syncItemRow(box.getChildAt(i));
        }
    }

    /** 勾选变化后只重算顶部大字与按钮文案，不重建行。 */
    private void refreshSheetHeader() {
        if (cacheSheetRoot == null) return;
        // 复用 showClearCacheSheet 里那套计算：重新构建一次数字
        TextView tvTotal = cacheSheetRoot.findViewById(R.id.tv_cache_total);
        TextView tvTotalUnit = cacheSheetRoot.findViewById(R.id.tv_cache_total_unit);
        TextView tvCount = cacheSheetRoot.findViewById(R.id.tv_cache_count);
        TextView btnOk = cacheSheetRoot.findViewById(R.id.btn_cache_confirm);
        ImageView ivSelectAll = cacheSheetRoot.findViewById(R.id.iv_select_all);
        if (btnOk == null) return;

        long selected = 0;
        int n = 0;
        int total = 0;
        for (CacheManager.Item item : CacheManager.Item.values()) {
            Long sz = cachedSizes == null ? null : cachedSizes.get(item);
            boolean has = sz != null && sz > 0;
            if (has) total++;
            if (has && Boolean.TRUE.equals(cacheChecked.get(item))) {
                selected += sz;
                n++;
            }
        }
        String[] parts = splitSize(selected);
        if (tvTotal != null) tvTotal.setText(parts[0]);
        if (tvTotalUnit != null) {
            // 单位跟随实际量级（KB / MB / GB），后面统一缀「可清理」
            tvTotalUnit.setText(parts[1].isEmpty()
                    ? getString(R.string.mine_cache_unit_idle)
                    : getString(R.string.mine_cache_unit, parts[1]));
        }
        if (tvCount != null) tvCount.setText(getString(R.string.mine_cache_selected_count, n, total));
        boolean all = total > 0 && n == total;
        btnOk.setText(all ? R.string.mine_cache_clear_all : R.string.mine_cache_clear);
        btnOk.setEnabled(n > 0);
        if (ivSelectAll != null) ivSelectAll.setSelected(all);
    }

    /** 把 "128.4 MB" 拆成 ["128.4", "MB"]，便于大小字号混排。 */
    private String[] splitSize(long bytes) {
        String s = CacheManager.formatSize(bytes);
        int i = s.indexOf(' ');
        if (i <= 0) return new String[]{s, ""};
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    private String itemTitle(CacheManager.Item item) {
        switch (item) {
            case CHAPTER_CACHE: return getString(R.string.mine_cache_item_chapter);
            case READING_CACHE: return getString(R.string.mine_cache_item_reading);
            case FONT:          return getString(R.string.mine_cache_item_font);
            case UPDATE_TMP:    return getString(R.string.mine_cache_item_update);
            case AVATAR:        return getString(R.string.mine_cache_item_avatar);
            case OTHER:         return getString(R.string.mine_cache_item_other);
            default:            return "";
        }
    }

    /**
     * 跟手拖拽：在面板任意空白处下拉即整体跟手，松手超过面板高度 30% 则关闭，否则弹回。
     *
     * <p>监听挂<b>面板本身</b>而非只挂抓手条，这样标题区、体积大字框、说明区、按钮之间的
     * 空隙、面板内边距都能起手拖拽（明细行有各自的点击业务，会先消费掉触摸，不参与拖拽）。
     *
     * <p>该监听同时承担第二个职责：<b>消费落在面板上的触摸且不进入按下态</b>。
     * 事件走到这里说明没有任何子 View 消费它，此时：
     * <ul>
     *   <li>不消费 → 穿透到底层兄弟 {@code v_cache_dim}（遮罩挂着"点击关闭"）→ 点面板空白误关抽屉；</li>
     *   <li>改用 {@code android:clickable="true"} 消费 → 会走 {@code onTouchEvent} 并
     *       {@code setPressed(true)}，再经 {@code ViewGroup.dispatchSetPressed} 把按下态
     *       <b>传播给所有不可点击的子节点</b>（{@code btn_select_all}、体积为 0 的明细行），
     *       于是点空白会把它们一起点亮成灰色。</li>
     * </ul>
     * 走 OnTouchListener 并返回 true，则 {@code View.dispatchTouchEvent} 直接置 result=true
     * 而<b>不再调用 onTouchEvent</b> → 既不穿透、也无按下态、无传播。因此本方法对所有
     * action 都必须返回 true。
     */
    private void setupSheetDrag() {
        if (cacheSheetPanel == null) return;
        final View panel = cacheSheetPanel;

        final float[] downY = new float[1];
        final boolean[] dragging = new boolean[1];
        final int slop = ViewConfiguration.get(getActivity()).getScaledTouchSlop();

        panel.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = e.getRawY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - downY[0];
                    if (!dragging[0]) {
                        if (dy > slop) {
                            dragging[0] = true;
                            panel.animate().cancel();
                        } else {
                            return true;
                        }
                    }
                    panel.setTranslationY(Math.max(0f, dy));
                    // 遮罩随下拉一起变淡，保证「面板走到哪、阴影淡到哪」
                    if (cacheSheetPanelH > 0) {
                        applySheetDim(panel.getTranslationY() / cacheSheetPanelH);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging[0]) {
                        // 没拖动 → 面板空白处的一次点击，无业务，吞掉即可
                        return true;
                    }
                    float dy = panel.getTranslationY();
                    if (dy > cacheSheetPanelH * 0.30f) {
                        dismissClearCacheSheet();
                    } else {
                        // 弹回：面板上滑的同时把遮罩同步加回满值（两个动画同时长同插值，
                        // 不能只靠 withEndAction 在结束时一把恢复 —— 那会让阴影先淡后"啪"地变黑）
                        panel.animate().translationY(0f).setDuration(200)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        android.animation.ValueAnimator dimAnim =
                                android.animation.ValueAnimator.ofFloat(sheetDimP, 0f);
                        dimAnim.setDuration(200);
                        dimAnim.setInterpolator(new DecelerateInterpolator());
                        dimAnim.addUpdateListener(a -> applySheetDim((float) a.getAnimatedValue()));
                        dimAnim.start();
                    }
                    dragging[0] = false;
                    return true;
                }
                default:
                    // 多指等其余 action 一律吞掉：一旦不消费，dispatchTouchEvent 就会回落到
                    // onTouchEvent，按下态与穿透问题会重新出现（见方法注释）
                    return true;
            }
        });
    }

    /**
     * 按下拉进度设置遮罩透明度：p=0 满遮罩，p=1 完全透明。
     *
     * <p>遮罩是布局里独立的 {@code v_cache_dim}（不是根 FrameLayout 的 background），
     * 直接 setAlpha 更可靠；同时 Window 层 dim 已在 {@code CacheSheetStyle} 里关掉
     * —— Window 层的遮罩不在 App 的 View 树里，代码拿不到引用，会导致
     * 「面板跟手走、阴影钉在原地」。
     */
    private void applySheetDim(float p) {
        if (cacheSheetDim == null) return;
        sheetDimP = Math.min(1f, Math.max(0f, p));
        cacheSheetDim.setAlpha(1f - sheetDimP);
    }

    /** 关闭抽屉：先滑出（遮罩同步淡出），动画结束再真正 dismiss，避免"啪"地消失。 */
    private void dismissClearCacheSheet() {
        final Dialog sheet = cacheSheet;
        if (sheet == null) return;
        View panel = cacheSheetPanel;
        if (panel == null || cacheSheetPanelH <= 0) {
            sheet.dismiss();
            return;
        }
        // 面板下滑的同时把遮罩淡掉，否则会看到阴影孤零零留到最后
        sheet.setCancelable(false);
        panel.animate()
                .translationY(cacheSheetPanelH)
                .setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(sheet::dismiss)
                .start();
        View rootView = cacheSheetRoot;
        if (rootView != null) {
            android.animation.ValueAnimator dimAnim =
                    android.animation.ValueAnimator.ofFloat(sheetDimP, 1f);
            dimAnim.setDuration(220);
            dimAnim.setInterpolator(new AccelerateDecelerateInterpolator());
            dimAnim.addUpdateListener(a -> applySheetDim((float) a.getAnimatedValue()));
            dimAnim.start();
        }
    }

    /** 执行清理 → 刷新显示 → 弹出居中完成卡片。 */
    private void doClearCache(List<CacheManager.Item> picked) {
        final Context ctx = getActivity();
        if (ctx == null) return;
        new Thread(() -> {
            final long freed = CacheManager.clearItems(ctx, picked);
            final long cache = CacheManager.getCacheBytes(ctx);
            final long local = CacheManager.getLocalBookBytes(ctx);
            if (!isAdded()) return;
            View anchor = tvCacheSize != null ? tvCacheSize : getView();
            if (anchor == null) return;
            anchor.post(() -> {
                if (!isAdded() || getActivity() == null) return;
                if (tvCacheSize != null) tvCacheSize.setText(formatCacheSummary(cache, local));
                showClearResultCard(freed);
            });
        }).start();
    }

    /** 清理结果：屏幕居中的轻量卡片，1.6 秒后自动淡出。 */
    private void showClearResultCard(long freed) {
        Context ctx = getActivity();
        if (ctx == null || getView() == null) return;
        final Dialog d = new Dialog(ctx, R.style.ClearResultCardStyle);
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_clear_cache_result, null);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setGravity(Gravity.CENTER);
            w.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        d.setCanceledOnTouchOutside(true);
        TextView tvSub = root.findViewById(R.id.tv_result_sub);
        if (tvSub != null) {
            tvSub.setText(freed > 0
                    ? getString(R.string.mine_cache_freed, CacheManager.formatSize(freed))
                    : getString(R.string.mine_cache_nothing));
        }
        d.show();
        // 1.6s 后自动收，用户也可以点一下提前关
        root.postDelayed(() -> {
            if (d.isShowing()) d.dismiss();
        }, 1600);
        root.setOnClickListener(v -> {
            if (d.isShowing()) d.dismiss();
        });
    }

    /**
     * 获取未读消息数量并更新角标显示
     */
    private void loadUnreadCount() {
        String userIdStr = sp.getString("userId", "");
        if (userIdStr.isEmpty()) {
            tvUnreadBadge.setVisibility(View.GONE);
            return;
        }
        long userId = Long.parseLong(userIdStr);

        ApiService api = RetrofitClient.getApiService();
        api.getUnreadCount(userId).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (!isAdded()) return;
                    if (response.isSuccessful() && response.body() != null
                            && response.body().isSuccess() && response.body().getData() != null) {
                        long total = response.body().getData().getTotal();
                        if (total > 0) {
                            tvUnreadBadge.setVisibility(View.VISIBLE);
                            tvUnreadBadge.setText(total > 99 ? "99+" : String.valueOf(total));
                        } else {
                            tvUnreadBadge.setVisibility(View.GONE);
                        }
                    } else {
                        tvUnreadBadge.setVisibility(View.GONE);
                    }
                },
                (call, t) -> {
                    if (isAdded()) {
                        tvUnreadBadge.setVisibility(View.GONE);
                    }
                }
        ));
    }

    /**
     * 加载并显示用户资料（头像、昵称、UID），同时拉取统计数字。
     */
    private void loadUserProfile() {
        if (sp == null || !isAdded()) return;

        // 昵称：优先显示昵称，为空则显示用户名
        String nickname = sp.getString("nickname", "");
        String username = sp.getString("username", "未登录");
        String displayName = nickname.isEmpty() ? username : nickname;
        tvNickname.setText(displayName);

        // UID：显示6位随机数
        String userId = sp.getString("userId", "");
        if (!userId.isEmpty()) {
            tvUid.setText("UID: " + userId);
            tvUid.setVisibility(View.VISIBLE);
        } else {
            tvUid.setVisibility(View.GONE);
        }

        // 头像：从本地文件加载，为空则使用默认图标，统一裁剪为圆形
        String avatarPath = sp.getString("avatar", "");
        Bitmap avatarBitmap = null;
        if (!avatarPath.isEmpty()) {
            File avatarFile = new File(avatarPath);
            if (avatarFile.exists()) {
                avatarBitmap = BitmapFactory.decodeFile(avatarPath);
            }
        }
        if (avatarBitmap == null) {
            // 解码默认图标
            Drawable defaultDrawable = getResources().getDrawable(R.drawable.default_avatar, null);
            if (defaultDrawable != null) {
                avatarBitmap = Bitmap.createBitmap(
                        defaultDrawable.getIntrinsicWidth() > 0 ? defaultDrawable.getIntrinsicWidth() : 200,
                        defaultDrawable.getIntrinsicHeight() > 0 ? defaultDrawable.getIntrinsicHeight() : 200,
                        Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(avatarBitmap);
                defaultDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                defaultDrawable.draw(canvas);
            }
        }
        if (avatarBitmap != null) {
            RoundedBitmapDrawable rounded = RoundedBitmapDrawableFactory.create(getResources(), avatarBitmap);
            rounded.setCircular(true);
            ivAvatar.setImageDrawable(rounded);
        }

        // 编辑资料按钮文案随登录态切换：已登录=编辑资料；未登录=立即登录
        if (btnEditProfile != null) {
            btnEditProfile.setText(LoginHelper.isLoggedIn(getActivity())
                    ? R.string.mine_edit_profile : R.string.mine_login_now);
        }

        loadReadingStats(userId);
    }

    /**
     * 统计卡：藏书数 + 阅读时长。
     * 未登录时两项都归零（本地数据按用户隔离，游客没有可比对的账）。
     *
     * <p>时长走 {@code /api/user/reading-stat/{userId}} 的累计值（后端一次查询），
     * 与「连续阅读天数」同源，因此这里顺带把徽章一起刷新，少打一个接口。
     */
    private void loadReadingStats(String userId) {
        if (tvStatBooks == null || tvStatTime == null) return;
        if (userId.isEmpty()) {
            tvStatBooks.setText("0");
            tvStatTime.setText("0");
            return;
        }
        final long uid = Long.parseLong(userId);
        ApiService api = RetrofitClient.getApiService();

        // 藏书数 = 书架条目数
        api.getBookshelf(uid).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (!isAdded() || tvStatBooks == null) return;
                    if (response.isSuccessful() && response.body() != null
                            && response.body().isSuccess() && response.body().getData() != null) {
                        tvStatBooks.setText(String.valueOf(response.body().getData().size()));
                    } else {
                        tvStatBooks.setText("0");
                    }
                },
                (call, t) -> {
                    if (isAdded() && tvStatBooks != null) tvStatBooks.setText("0");
                }
        ));

        // 阅读时长：后端存秒；文案与书架页统一走 ReadTimeText（精确到分，不四舍五入）
        api.getReadingStat(uid).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (!isAdded() || tvStatTime == null) return;
                    if (response.isSuccessful() && response.body() != null
                            && response.body().isSuccess() && response.body().getData() != null) {
                        com.example.myapplication.bean.UserReadingStat stat = response.body().getData();
                        tvStatTime.setText(ReadTimeText.format(stat.getTotalReadSeconds()));
                        if (layoutStreak != null) {
                            int days = stat.getStreakDays();
                            if (days > 0) {
                                layoutStreak.setVisibility(View.VISIBLE);
                                tvStreak.setText(getString(R.string.mine_streak_days, days));
                            } else {
                                layoutStreak.setVisibility(View.GONE);
                            }
                        }
                    } else {
                        tvStatTime.setText("0");
                    }
                },
                (call, t) -> {
                    if (isAdded() && tvStatTime != null) tvStatTime.setText("0");
                }
        ));
    }

    /**
     * 阅读时长文案已统一收敛到 {@link com.example.myapplication.utils.ReadTimeText}——
     * 与书架页共用同一实现，保证两页数字一致（原 %.1f 时 四舍五入会与书架页对不上）。
     */
}
