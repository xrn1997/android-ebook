package com.ebook.find.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.SizeReadyCallback;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;
import com.ebook.find.R;
import com.ebook.find.databinding.AdapterLibraryKindbookBinding;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.BindingAdapter;
import androidx.databinding.ObservableArrayList;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class LibraryBookAdapter extends BaseBindAdapter<SearchBook, AdapterLibraryKindbookBinding> {
    public LibraryBookAdapter(Context context, ObservableArrayList<SearchBook> items) {
        super(context, items);
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
}
