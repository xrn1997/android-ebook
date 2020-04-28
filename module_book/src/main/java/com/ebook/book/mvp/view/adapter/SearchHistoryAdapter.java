
package com.ebook.book.mvp.view.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.ebook.book.R;
import com.ebook.common.view.flowlayout.FlowLayout;
import com.ebook.common.view.flowlayout.TagAdapter;
import com.ebook.db.entity.SearchHistory;


import java.util.ArrayList;

public class SearchHistoryAdapter extends TagAdapter<SearchHistory> {
    public SearchHistoryAdapter() {
        super(new ArrayList<SearchHistory>());
    }

    public interface OnItemClickListener{
        void itemClick(SearchHistory searchHistory);
    }
    private SearchHistoryAdapter.OnItemClickListener onItemClickListener;

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
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(null != onItemClickListener){
                    onItemClickListener.itemClick(searchHistory);
                }
            }
        });
        return tv;
    }

    public SearchHistory getItemData(int position){
        return mTagDatas.get(position);
    }

    public int getDataSize(){
        return mTagDatas.size();
    }
}
