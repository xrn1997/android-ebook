package com.ebook.me.adapter;

import android.content.Context;
import androidx.databinding.ObservableArrayList;
import android.view.View;

import com.ebook.api.newstype.entity.NewsType;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.me.R;
import com.ebook.me.databinding.AdapterNewsTypeShowBindingItemBinding;

public class NewsTypeShowBindAdapter extends BaseBindAdapter<NewsType, AdapterNewsTypeShowBindingItemBinding> {
    private NewsTypeShowBindAdapter.DeleteClickLisenter mDeleteClickLisenter;

    public interface DeleteClickLisenter {
        void onClickDeleteListener(int id);
    }

    public void setDeleteClickLisenter(NewsTypeShowBindAdapter.DeleteClickLisenter deleteClickLisenter) {
        mDeleteClickLisenter = deleteClickLisenter;
    }
    public NewsTypeShowBindAdapter(Context context, ObservableArrayList<NewsType> items) {
        super(context, items);
    }
    @Override
    protected int getLayoutItemId(int viewType) {
        return R.layout.adapter_news_type_show_binding_item;
    }

    @Override
    protected void onBindItem(AdapterNewsTypeShowBindingItemBinding binding, final NewsType item, int position) {
        binding.setNewsType(item);
        binding.btnMeNewsTypeDelete.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                if(mDeleteClickLisenter != null){
                    mDeleteClickLisenter.onClickDeleteListener(item.getId());
                }
            }
        });
    }
}
