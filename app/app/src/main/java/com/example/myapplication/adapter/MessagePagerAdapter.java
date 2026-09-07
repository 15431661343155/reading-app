package com.example.myapplication.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.example.myapplication.fragment.MessageListFragment;

/**
 * 消息中心 ViewPager2 适配器
 * 4 个 Tab：评论和@、赞和收藏、关注消息、系统通知
 */
public class MessagePagerAdapter extends FragmentStateAdapter {

    /**
     * 各 Tab 对应的消息类型过滤条件
     * 前三个为单类型，使用后端 type 参数过滤
     * 系统通知 Tab 同时展示 system 和 reply（意见反馈回复），需客户端过滤
     */
    private static final String[][] TAB_TYPES = {
            {"comment"},
            {"like"},
            {"follow"},
            {"system", "reply"}
    };

    public MessagePagerAdapter(@NonNull Fragment fragment) {
        super(fragment);
    }

    public MessagePagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return MessageListFragment.newInstance(TAB_TYPES[position]);
    }

    @Override
    public int getItemCount() {
        return TAB_TYPES.length;
    }
}
