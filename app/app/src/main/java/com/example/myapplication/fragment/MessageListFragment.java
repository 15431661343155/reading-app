package com.example.myapplication.fragment;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.myapplication.R;
import com.example.myapplication.adapter.MessageAdapter;
import com.example.myapplication.api.ApiService;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Message;
import com.example.myapplication.bean.PageResponse;
import com.example.myapplication.utils.Hint;
import com.example.myapplication.widget.LoadingView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 消息分类列表Fragment
 * 通过 typeFilters 数组指定要展示的消息类型：
 * - 单类型（comment / like / follow）：使用后端 type 参数过滤
 * - 多类型（system + reply）：分别请求各类型后合并结果
 */
public class MessageListFragment extends Fragment {

    private static final String ARG_TYPE_FILTERS = "type_filters";

    private RecyclerView rvMessages;
    private SwipeRefreshLayout swipeRefresh;
    private View layoutEmpty;
    private ImageView ivEmptyIcon;
    private TextView tvEmptyText;
    private LoadingView loadingView;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private String[] typeFilters;
    private long userId;

    /**
     * 创建实例
     * @param typeFilters 要展示的消息类型数组
     *                    如 {"comment"} 或 {"system", "reply"}
     */
    public static MessageListFragment newInstance(String[] typeFilters) {
        MessageListFragment fragment = new MessageListFragment();
        Bundle args = new Bundle();
        args.putStringArray(ARG_TYPE_FILTERS, typeFilters);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            typeFilters = getArguments().getStringArray(ARG_TYPE_FILTERS);
        }
        if (typeFilters == null || typeFilters.length == 0) {
            typeFilters = new String[]{"system"};
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_message_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SharedPreferences sp = requireActivity().getSharedPreferences("user_info", 0);
        String uid = sp.getString("userId", "");
        userId = uid.isEmpty() ? 0 : Long.parseLong(uid);

        rvMessages  = view.findViewById(R.id.rv_messages);
        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        layoutEmpty = view.findViewById(R.id.layout_empty);
        ivEmptyIcon = view.findViewById(R.id.iv_empty_icon);
        tvEmptyText = view.findViewById(R.id.tv_empty_text);
        loadingView = view.findViewById(R.id.loading_view);

        // 设置空状态图标和文案
        setupEmptyState();

        // 下拉刷新颜色
        swipeRefresh.setColorSchemeResources(
                R.color.purple_500, R.color.cyan_400, R.color.teal_200);
        swipeRefresh.setOnRefreshListener(this::refreshMessages);

        // RecyclerView
        rvMessages.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvMessages.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        adapter = new MessageAdapter(requireContext(), messages);
        adapter.setOnMessageClickListener(msg -> {
            if (!msg.isRead()) {
                markAsRead(msg);
            }
        });
        rvMessages.setAdapter(adapter);

        // 首次加载
        loadMessages();
    }

    /**
     * 根据首类型设置空状态视图
     */
    private void setupEmptyState() {
        String primaryType = typeFilters[0];
        switch (primaryType) {
            case "comment":
                ivEmptyIcon.setImageResource(R.drawable.ic_comment);
                tvEmptyText.setText(R.string.message_empty_comment);
                break;
            case "like":
                ivEmptyIcon.setImageResource(R.drawable.ic_like);
                tvEmptyText.setText(R.string.message_empty_like);
                break;
            case "follow":
                ivEmptyIcon.setImageResource(R.drawable.ic_follow);
                tvEmptyText.setText(R.string.message_empty_follow);
                break;
            case "system":
                ivEmptyIcon.setImageResource(R.drawable.ic_notification);
                tvEmptyText.setText(R.string.message_empty_system);
                break;
        }
    }

    private boolean useServerFilter() {
        return typeFilters.length == 1;
    }

    /**
     * 首次加载（显示 ProgressBar）
     */
    private void loadMessages() {
        if (userId == 0) {
            showLoading(false);
            updateEmptyState();
            return;
        }
        showLoading(true);

        if (useServerFilter()) {
            ApiService api = RetrofitClient.getApiService();
            api.getMessagesByType(userId, typeFilters[0]).enqueue(SafeCallback.from(this,
                    (call, response) -> {
                        showLoading(false);
                        handleResponse(response.body());
                    },
                    (call, t) -> {
                        showLoading(false);
                        if (isAdded()) {
                            Hint.show(requireContext(), R.string.message_load_fail);
                        }
                    }
            ));
        } else {
            loadMultiTypeMessages(true);
        }
    }

    /**
     * 下拉刷新
     */
    private void refreshMessages() {
        if (userId == 0) {
            swipeRefresh.setRefreshing(false);
            return;
        }

        if (useServerFilter()) {
            ApiService api = RetrofitClient.getApiService();
            api.getMessagesByType(userId, typeFilters[0]).enqueue(SafeCallback.from(this,
                    (call, response) -> {
                        swipeRefresh.setRefreshing(false);
                        handleResponse(response.body());
                    },
                    (call, t) -> {
                        swipeRefresh.setRefreshing(false);
                        if (isAdded()) {
                            Hint.show(requireContext(), R.string.message_load_fail);
                        }
                    }
            ));
        } else {
            loadMultiTypeMessages(false);
        }
    }

    /**
     * 多类型消息加载：分别请求每种类型，合并结果（按 id 去重）
     * 用于系统通知 Tab（system + reply 意见反馈回复）
     */
    private void loadMultiTypeMessages(boolean initialLoad) {
        ApiService api = RetrofitClient.getApiService();
        final List<Message> merged = new ArrayList<>();
        final java.util.Set<Long> seenIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        final AtomicInteger remaining = new AtomicInteger(typeFilters.length);
        final boolean[] hasError = {false};

        for (String type : typeFilters) {
            api.getMessagesByType(userId, type).enqueue(new Callback<ApiResponse<PageResponse<Message>>>() {
                @Override
                public void onResponse(Call<ApiResponse<PageResponse<Message>>> call,
                                       Response<ApiResponse<PageResponse<Message>>> response) {
                    if (response.isSuccessful() && response.body() != null
                            && response.body().isSuccess() && response.body().getData() != null) {
                        List<Message> content = response.body().getData().getContent();
                        if (content != null && !content.isEmpty()) {
                            synchronized (merged) {
                                for (Message msg : content) {
                                    if (seenIds.add(msg.getId())) {
                                        merged.add(msg);
                                    }
                                }
                            }
                        }
                    } else if (!response.isSuccessful() || (response.body() != null && !response.body().isSuccess())) {
                        hasError[0] = true;
                    }
                    checkComplete();
                }

                @Override
                public void onFailure(Call<ApiResponse<PageResponse<Message>>> call, Throwable t) {
                    hasError[0] = true;
                    checkComplete();
                }

                private void checkComplete() {
                    if (remaining.decrementAndGet() > 0) return;
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (initialLoad) showLoading(false);
                        else swipeRefresh.setRefreshing(false);

                        if (!merged.isEmpty() || !hasError[0]) {
                            merged.sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()));
                            messages.clear();
                            messages.addAll(merged);
                            adapter.notifyDataSetChanged();
                            rvMessages.requestLayout();
                            updateEmptyState();
                        } else {
                            Hint.show(requireContext(), R.string.message_load_fail);
                        }
                    });
                }
            });
        }
    }

    /**
     * 处理后端已过滤的响应（单类型）
     */
    private void handleResponse(ApiResponse<PageResponse<Message>> body) {
        if (!isAdded()) return;

        if (body != null && body.isSuccess() && body.getData() != null) {
            List<Message> content = body.getData().getContent();
            if (content == null) content = new ArrayList<>();
            messages.clear();
            messages.addAll(content);
            adapter.notifyDataSetChanged();
            updateEmptyState();
        } else {
            Hint.show(requireContext(), R.string.message_load_fail);
        }
    }

    private void markAsRead(Message msg) {
        msg.setRead(true);
        adapter.notifyDataSetChanged();

        ApiService api = RetrofitClient.getApiService();
        api.markMessageRead(msg.getId()).enqueue(SafeCallback.from(this,
                (call, response) -> { /* 静默成功 */ },
                (call, t) -> { /* 静默失败 */ }
        ));
    }

    /**
     * 获取当前Tab未读消息数量
     */
    public int getUnreadCount() {
        int count = 0;
        for (Message msg : messages) {
            if (!msg.isRead()) count++;
        }
        return count;
    }

    /**
     * 全部标记已读（由 Activity 调用）
     */
    public void markAllRead() {
        boolean hasUnread = false;
        for (Message msg : messages) {
            if (!msg.isRead()) {
                msg.setRead(true);
                hasUnread = true;
            }
        }
        if (!hasUnread) return;

        adapter.notifyDataSetChanged();

        if (userId == 0 || !isAdded()) return;

        ApiService api = RetrofitClient.getApiService();
        api.markAllMessagesRead(userId).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (isAdded()) {
                        Hint.show(requireContext(), "已全部标记为已读");
                    }
                },
                (call, t) -> {
                    if (isAdded()) {
                        Hint.show(requireContext(), "操作失败");
                    }
                }
        ));
    }

    private void showLoading(boolean loading) {
        if (loadingView == null) return;
        loadingView.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            layoutEmpty.setVisibility(View.GONE);
        }
    }

    private void updateEmptyState() {
        if (messages.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            rvMessages.setVisibility(View.GONE);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            rvMessages.setVisibility(View.VISIBLE);
        }
    }
}
