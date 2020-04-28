package com.ebook.me.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;
import android.widget.TextView;

import com.ebook.api.newstype.entity.NewsType;
import com.ebook.common.adapter.BaseAdapter;
import com.ebook.me.R;


public class NewsTypeSelectAdapter extends BaseAdapter<NewsType, NewsTypeSelectAdapter.MyViewHolder> {
    public NewsTypeSelectAdapter(Context context) {
        super(context);
    }

    @Override
    protected int onBindLayout() {
        return R.layout.item_news_type_select;
    }

    @Override
    protected MyViewHolder onCreateHolder(View view) {
        return new MyViewHolder(view);
    }

    @Override
    protected void onBindData(MyViewHolder holder, NewsType newsType, int position) {
        holder.mTxtNewTypeTitle.setText(newsType.getTypename());
    }

    class MyViewHolder extends RecyclerView.ViewHolder {
        TextView mTxtNewTypeTitle;

        public MyViewHolder(@NonNull View itemView) {
            super(itemView);
            mTxtNewTypeTitle = itemView.findViewById(R.id.txt_me_news_type_title);

        }
    }
}
