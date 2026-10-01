package com.example.myapplication.utils;

import android.content.Context;
import android.view.ViewGroup;

import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

public class ViewUtils {

    /**
     * 创建带竖向快速滑动条的 RecyclerView
     * @param context 上下文
     * @param thumbColor 滑块颜色，如 "#0099CC"
     * @param trackColor 滑道颜色，如 "#E0E0E0"
     */
    public static FastScrollRecyclerView createFastScrollRecyclerView(Context context, String thumbColor,
            String trackColor) {
        FastScrollRecyclerView recyclerView = new FastScrollRecyclerView(context);
        recyclerView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        recyclerView.setThumbColor(android.graphics.Color.parseColor(thumbColor));
        recyclerView.setTrackColor(android.graphics.Color.parseColor(trackColor));
        return recyclerView;
    }
}