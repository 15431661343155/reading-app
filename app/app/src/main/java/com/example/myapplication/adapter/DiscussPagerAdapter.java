package com.example.myapplication.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.example.myapplication.fragment.DiscussStoryFragment;
import com.example.myapplication.fragment.DiscussSuggestFragment;

import java.util.List;

public class DiscussPagerAdapter extends FragmentStateAdapter {

    public DiscussPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if(position == 0){
            return new DiscussStoryFragment();
        }else{
            return new DiscussSuggestFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}