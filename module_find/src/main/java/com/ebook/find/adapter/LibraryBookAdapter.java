package com.ebook.find.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.databinding.BindingAdapter;
import androidx.databinding.ObservableArrayList;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;
import com.ebook.find.R;
import com.ebook.find.databinding.AdapterLibraryKindbookBinding;

public class LibraryBookAdapter extends BaseBindAdapter<SearchBook, AdapterLibraryKindbookBinding> {
    public LibraryBookAdapter(Context context, ObservableArrayList<SearchBook> items) {
        super(context, items);
    }

    @BindingAdapter(value = {"imageUrl", "placeHolder"}, requireAll = false)
    public static void loadImage(ImageView imageView, String url, Drawable holderDrawable) {
        // Log.d("glide_cover", "loadImage url: "+url);
        Glide.with(imageView.getContext())
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontAnimate()
                .placeholder(holderDrawable)
                .error(holderDrawable)
                .fallback(holderDrawable)
                .fitCenter()
                .into(imageView);

    }

    @Override
    protected int getLayoutItemId(int viewType) {
        return R.layout.adapter_library_kindbook;
    }

    @Override
    protected void onBindItem(AdapterLibraryKindbookBinding binding, SearchBook item, int position) {
        binding.setSearchbook(item);
        BookShelf bookShelf = new BookShelf();
        bookShelf.setNoteUrl(item.getNoteUrl());
        binding.ibContent.setOnClickListener(v -> {
            if (mItemClickListener != null) {
                mItemClickListener.onItemClick(item, position);
            }
        });
    }
}
