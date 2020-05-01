package com.ebook.find.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.db.entity.SearchBook;
import com.ebook.find.R;
import com.ebook.find.databinding.AdapterLibraryKindbookBinding;

import androidx.databinding.BindingAdapter;
import androidx.databinding.ObservableArrayList;
import jp.wasabeef.glide.transformations.RoundedCornersTransformation;

import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

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
        binding.ibContent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mItemClickListener != null) {
                    mItemClickListener.onItemClick(item, position);
                }
            }
        });
    }
    @BindingAdapter(value = {"imageUrl", "placeHolder"},requireAll = false)
    public static void loadImage(ImageView imageView, String url, Drawable holderDrawable) {
        Log.d("glide_cover", "loadImage url: "+url);
        Glide.with(imageView.getContext())
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .dontAnimate()
                .placeholder(holderDrawable)
                .into(imageView);
    }
}
