package com.ebook.book.adapter;

import android.content.Context;

import android.view.View;

import com.ebook.api.news.entity.NewsDetail;
import com.ebook.book.R;

import com.ebook.book.databinding.AdpaterNewsListItemBinding;
import com.ebook.common.adapter.BaseBindAdapter;

import androidx.databinding.ObservableArrayList;


public class NewsListAdatper extends BaseBindAdapter<NewsDetail, AdpaterNewsListItemBinding> {


    public NewsListAdatper(Context context, ObservableArrayList<NewsDetail> items) {
        super(context, items);
    }

    @Override
    protected int getLayoutItemId(int viewType) {
        return R.layout.adpater_news_list_item;
    }

    @Override
    protected void onBindItem(AdpaterNewsListItemBinding binding, final NewsDetail item, final int position) {
        binding.setNewsDetail(item);
        binding.viewNewsDetal.setOnClickListener(new View.OnClickListener(){

            @Override
            public void onClick(View view) {
                if(mItemClickListener != null){
                    mItemClickListener.onItemClick(item,position);
                }
            }
        });
    }
}
