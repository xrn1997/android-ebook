package com.ebook.common.util;

import androidx.databinding.ObservableList;
import androidx.recyclerview.widget.RecyclerView;


public class ObservableListUtil {
    public static ObservableList.OnListChangedCallback getListChangedCallback(final RecyclerView.Adapter adapter) {
        return new ObservableList.OnListChangedCallback() {
            @Override
            public void onChanged(ObservableList observableList) {
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onItemRangeChanged(ObservableList observableList, int i, int i1) {
                adapter.notifyItemRangeChanged(i, i1);
            }

            @Override
            public void onItemRangeInserted(ObservableList observableList, int i, int i1) {
                adapter.notifyItemRangeInserted(i, i1);
            }

            @Override
            public void onItemRangeMoved(ObservableList observableList, int i, int i1, int i2) {
                if (i2 == 1) {
                    adapter.notifyItemMoved(i, i1);
                } else {
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onItemRangeRemoved(ObservableList observableList, int i, int i1) {
                adapter.notifyItemRangeRemoved(i, i1);
            }
        };
    }
}
