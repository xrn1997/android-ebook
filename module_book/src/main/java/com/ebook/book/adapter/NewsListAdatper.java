package com.ebook.book.adapter;

import android.content.Context;

import android.view.View;

import com.ebook.api.news.entity.NewsDetail;
import com.ebook.book.R;
import com.ebook.book.databinding.ItemNewsListBinding;
import com.ebook.common.adapter.BaseBindAdapter;

import androidx.databinding.ObservableArrayList;


public class NewsListAdatper extends BaseBindAdapter<NewsDetail, ItemNewsListBinding> {


    public NewsListAdatper(Context context, ObservableArrayList<NewsDetail> items) {
        super(context, items);
    }

    @Override
    protected int getLayoutItemId(int viewType) {
        return R.layout.item_news_list;
    }

    @Override
    protected void onBindItem(ItemNewsListBinding binding, final NewsDetail item, final int position) {
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
