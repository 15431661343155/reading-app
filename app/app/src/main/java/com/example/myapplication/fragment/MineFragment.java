package com.example.myapplication.fragment;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.fragment.app.Fragment;
import com.example.myapplication.activity.ReadingRecordActivity;
import com.example.myapplication.activity.ThemeSettingActivity;
import com.example.myapplication.activity.FeedbackActivity;
import com.example.myapplication.activity.MessageCenterActivity;
import com.example.myapplication.activity.SettingsActivity;
import com.example.myapplication.R;
import com.example.myapplication.api.ApiService;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.UnreadCountResponse;

import java.io.File;
//个人中心页
public class MineFragment extends Fragment {

    private ImageView ivAvatar;
    private TextView tvNickname;
    private TextView tvUid;
    private ImageView btnSettings;
    private TextView tvUnreadBadge;
    private SharedPreferences sp;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mine, container, false);

        // 不再手动加状态栏padding：activity_main.xml 的 fitsSystemWindows 已让系统自动避让
        updateStatusBarColor();

        initView(view);
        sp = getActivity().getSharedPreferences("user_info", getActivity().MODE_PRIVATE);
        loadUserProfile();

        // 设置按钮：跳转到设置页面
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(getActivity(), SettingsActivity.class));
        });

        //阅读记录点击
        View btnReadingRecord = view.findViewById(R.id.btn_reading_record);
        btnReadingRecord.setOnClickListener(v -> {
            startActivity(new Intent(getActivity(), ReadingRecordActivity.class));
        });

        //意见反馈点击
        View btnFeedback = view.findViewById(R.id.btn_feedback);
        btnFeedback.setOnClickListener(v -> {
            startActivity(new Intent(getActivity(), FeedbackActivity.class));
        });

        //消息中心点击
        View btnMessageCenter = view.findViewById(R.id.btn_message_center);
        btnMessageCenter.setOnClickListener(v -> {
            startActivity(new Intent(getActivity(), MessageCenterActivity.class));
        });

        return view;
    }

    private void initView(View view) {
        ivAvatar = view.findViewById(R.id.iv_avatar);
        tvNickname = view.findViewById(R.id.tv_nickname);
        tvUid = view.findViewById(R.id.tv_uid);
        btnSettings = view.findViewById(R.id.btn_settings);
        tvUnreadBadge = view.findViewById(R.id.tv_unread_badge);
    }

    /**
     * 更新状态栏图标颜色（深色背景用浅色/白色图标）
     */
    private void updateStatusBarColor() {
        if (getActivity() == null) return;
        // 状态栏背景与页面顶部蓝色头像区统一，消除割裂
        getActivity().getWindow().setStatusBarColor(
            getActivity().getResources().getColor(R.color.ios_blue, null));
        int flags = getActivity().getWindow().getDecorView().getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getActivity().getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatusBarColor();
        loadUserProfile();
        loadUnreadCount();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            updateStatusBarColor();
        }
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
     * 加载并显示用户资料（头像、昵称、UID）
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
            Drawable defaultDrawable = getResources().getDrawable(R.drawable.ic_detective, null);
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
    }
}