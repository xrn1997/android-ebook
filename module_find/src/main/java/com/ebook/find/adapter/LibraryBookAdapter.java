package com.ebook.find.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;

import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.basebook.base.activity.BaseActivity;
import com.ebook.basebook.mvp.model.impl.GxwztvBookModelImpl;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.common.mvvm.BaseFragment;
import com.ebook.common.mvvm.BaseMvvmFragment;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.SearchBook;
import com.ebook.find.R;
import com.ebook.find.databinding.AdapterLibraryKindbookBinding;
import com.trello.rxlifecycle3.android.ActivityEvent;

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
        GxwztvBookModelImpl.getInstance().getBookInfo(bookShelf)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<BookShelf>() {
                    @Override
                    public void onNext(BookShelf bookShelf) {
                        loadImage(binding.ivCover, bookShelf.getBookInfo().getCoverUrl(), context.getDrawable(R.drawable.img_cover_default));
                    }

                    @Override
                    public void onError(Throwable e) {

                    }
                });
        binding.ibContent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mItemClickListener != null) {
                    mItemClickListener.onItemClick(item, position);
                }
            }
        });
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
}
