package com.ebook.find.mvp.view.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.ebook.basebook.view.flowlayout.FlowLayout;
import com.ebook.basebook.view.flowlayout.TagAdapter;
import com.ebook.db.entity.SearchHistory;
import com.ebook.find.R;

import java.util.ArrayList;

public class SearchHistoryAdapter extends TagAdapter<SearchHistory> {
    private SearchHistoryAdapter.OnItemClickListener onItemClickListener;

    public SearchHistoryAdapter() {
        super(new ArrayList<>());
    }

    public OnItemClickListener getListener() {
        return onItemClickListener;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    @Override
    public View getView(FlowLayout parent, int position, final SearchHistory searchHistory) {
        TextView tv = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_searchhistory_item,
                parent, false);
        tv.setText(searchHistory.getContent());
        tv.setOnClickListener(v -> {
            if (null != onItemClickListener) {
                onItemClickListener.itemClick(searchHistory);
            }
        });
        return tv;
    }

    public SearchHistory getItemData(int position) {
        return mTagDatas.get(position);
    }

    public int getDataSize() {
        return mTagDatas.size();
    }

    public interface OnItemClickListener {
        void itemClick(SearchHistory searchHistory);
    }
}
