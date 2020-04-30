package com.ebook.common.binding.viewadapter.recycleview;

import androidx.databinding.BindingAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class ViewAdapter {
    @BindingAdapter({"linearLayoutManager"})
    public static void setLinearLayoutManager(RecyclerView recyclerView,boolean b) {
        LinearLayoutManager layoutManager = new LinearLayoutManager(recyclerView.getContext());
        layoutManager.setOrientation(b?LinearLayoutManager.HORIZONTAL:LinearLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);
    }
}
