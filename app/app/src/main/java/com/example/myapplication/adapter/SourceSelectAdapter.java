package com.example.myapplication.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.utils.ThemeAttrs;
import com.example.myapplication.R;
import com.example.myapplication.bean.SourceInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 外站书源选择弹窗中的 书源 单选项列表适配器。
 */
public class SourceSelectAdapter extends RecyclerView.Adapter<SourceSelectAdapter.VH> {

    public interface OnSourceSelectedListener {
        void onSelected(SourceInfo source);
    }

    private final List<SourceInfo> list = new ArrayList<>();
    private String selectedType;
    /** 书城当前正在使用的书源 type：行尾打「当前」徽标，与本次待确认的 selectedType 区分开 */
    private String appliedType = "";
    private OnSourceSelectedListener listener;

    public void setListener(OnSourceSelectedListener listener) {
        this.listener = listener;
    }

    public void setAppliedType(String appliedType) {
        this.appliedType = appliedType == null ? "" : appliedType;
        notifyDataSetChanged();
    }

    public void setData(List<SourceInfo> sources, String selectedType) {
        this.list.clear();
        if (sources != null) {
            this.list.addAll(sources);
        }
        this.selectedType = selectedType;
        notifyDataSetChanged();
    }

    public String getSelectedType() {
        return selectedType;
    }

    public void setSelectedType(String selectedType) {
        this.selectedType = selectedType;
        notifyDataSetChanged();
    }

    /** 暴露当前已加载的书源列表，便于 Dialog 通过选中 type 反查书源 */
    public List<SourceInfo> getItemList() {
        return list;
    }

    /** 根据选中的 type 反查完整书源 */
    public SourceInfo findSelected() {
        if (selectedType == null || selectedType.isEmpty()) return null;
        for (SourceInfo s : list) {
            if (selectedType.equals(s.getType())) return s;
        }
        return null;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_source_select, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        SourceInfo item = list.get(position);
        if (item == null) return;

        h.tvName.setText(item.getName() == null ? "未命名书源" : item.getName());
        h.tvDesc.setText(item.getDescription() == null ? "" : item.getDescription());
        h.tvType.setText(item.getType() == null ? "" : item.getType());

        boolean isSelected = item.getType() != null && item.getType().equals(selectedType);
        h.ivRadio.setImageResource(isSelected
                ? R.drawable.ic_checkbox_checked_orange
                : R.drawable.ic_checkbox_unchecked);
        // 选中行的主色薄底走 itemView 的 state_selected（bg_source_row），文字同时转主色
        h.itemView.setSelected(isSelected);
        h.tvName.setTextColor(ThemeAttrs.color(h.itemView.getContext(),
                isSelected ? R.attr.appAccent : R.attr.appTextPrimary, 0));

        boolean isApplied = item.getType() != null && item.getType().equals(appliedType);
        h.tvCurrent.setVisibility(isApplied ? View.VISIBLE : View.GONE);
        // 「当前」已经点明这行是谁，type 标签就让位给它，避免行尾挤两个胶囊
        boolean showType = !isApplied && item.getType() != null && !item.getType().isEmpty();
        h.tvType.setVisibility(showType ? View.VISIBLE : View.GONE);
        h.vDivider.setVisibility(position == getItemCount() - 1 ? View.GONE : View.VISIBLE);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) {
                selectedType = item.getType();
                notifyDataSetChanged();
                listener.onSelected(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivRadio;
        final TextView tvName;
        final TextView tvDesc;
        final TextView tvType;
        final TextView tvCurrent;
        final View vDivider;

        VH(View v) {
            super(v);
            ivRadio = v.findViewById(R.id.iv_radio);
            tvName = v.findViewById(R.id.tv_source_name);
            tvDesc = v.findViewById(R.id.tv_source_desc);
            tvType = v.findViewById(R.id.tv_source_type);
            tvCurrent = v.findViewById(R.id.tv_source_current);
            vDivider = v.findViewById(R.id.v_row_divider);
        }
    }
}
