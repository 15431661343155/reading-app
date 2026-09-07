package com.example.myapplication.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;
import com.example.myapplication.adapter.DiscussPagerAdapter;
import com.example.myapplication.R;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
//讨论界面
public class DiscussionFragment extends Fragment {

    private TabLayout tabLayout;
    private ViewPager2 viewPager;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_discussion, container, false);

        // 不再手动加状态栏padding：activity_main.xml 的 fitsSystemWindows 已让系统自动避让
        updateStatusBarColor();

        tabLayout = view.findViewById(R.id.tab_layout);
        viewPager = view.findViewById(R.id.view_pager);

        // 设置适配器
        DiscussPagerAdapter adapter = new DiscussPagerAdapter(requireActivity());
        viewPager.setAdapter(adapter);

        // 绑定Tab和ViewPager
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            if(position == 0){
                tab.setText("书籍剧情");
            }else {
                tab.setText("软件建议");
            }
        }).attach();

        return view;
    }

    /**
     * 更新状态栏图标颜色（浅色背景用深色图标）
     */
    private void updateStatusBarColor() {
        if (getActivity() == null) return;
        // 状态栏背景与页面顶部颜色统一，消除割裂
        getActivity().getWindow().setStatusBarColor(
            getActivity().getResources().getColor(R.color.ios_bg_grouped, null));
        int flags = getActivity().getWindow().getDecorView().getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getActivity().getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatusBarColor();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            updateStatusBarColor();
        }
    }
}