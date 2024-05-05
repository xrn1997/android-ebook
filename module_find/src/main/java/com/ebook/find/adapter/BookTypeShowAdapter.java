package com.ebook.find.adapter;

import android.content.Context;

import androidx.databinding.ObservableArrayList;

import com.ebook.find.R;
import com.ebook.find.databinding.AdpaterBookTypeItemBinding;
import com.ebook.find.entity.BookType;
import com.xrn1997.common.adapter.BaseBindAdapter;

public class BookTypeShowAdapter extends BaseBindAdapter<BookType, AdpaterBookTypeItemBinding> {

    public BookTypeShowAdapter(Context context, ObservableArrayList<BookType> items) {
        super(context, items);
    }

    @Override
    protected int getLayoutItemId(int viewType) {
        return R.layout.adpater_book_type_item;
    }

    @Override
    protected void onBindItem(AdpaterBookTypeItemBinding binding, BookType item, int position) {
        binding.setBooktype(item);
        binding.viewBooktype.setOnClickListener(v -> {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(item, position);
            }
        });
    }
}
