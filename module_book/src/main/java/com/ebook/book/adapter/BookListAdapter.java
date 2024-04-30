package com.ebook.book.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.databinding.BindingAdapter;
import androidx.databinding.ObservableArrayList;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.book.R;
import com.ebook.book.databinding.AdapterBookListItemBinding;
import com.ebook.db.entity.BookShelf;
import com.xrn1997.common.adapter.BaseBindAdapter;

public class BookListAdapter extends BaseBindAdapter<BookShelf, AdapterBookListItemBinding> {

    public BookListAdapter(Context context, ObservableArrayList<BookShelf> items) {
        super(context, items);
    }

    @BindingAdapter(value = {"imageUrl", "placeHolder"}, requireAll = false)
    public static void loadImage(ImageView imageView, String url, Drawable holderDrawable) {
        // Log.d("glide_cover", "loadImage url: "+url);
        Glide.with(imageView.getContext())
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .dontAnimate()
                .placeholder(holderDrawable)
                .into(imageView);
    }

    @Override
    protected int getLayoutItemId(int viewType) {
        return R.layout.adapter_book_list_item;
    }

    @Override
    protected void onBindItem(AdapterBookListItemBinding binding, BookShelf item, int position) {
        binding.setBookshelf(item);
        binding.viewBookDetail.setOnClickListener(view -> {
            if (mItemClickListener != null) {
                mItemClickListener.onItemClick(item, position);
            }
        });
        binding.viewBookDetail.setOnLongClickListener(v -> {
            if (mOnItemLongClickListener != null) {
                mOnItemLongClickListener.onItemLongClick(item, position);
            }
            return true;
        });
    }

}
