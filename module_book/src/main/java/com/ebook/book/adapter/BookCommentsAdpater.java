package com.ebook.book.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.databinding.BindingAdapter;
import androidx.databinding.ObservableArrayList;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.api.config.API;
import com.ebook.api.entity.Comment;
import com.ebook.book.R;
import com.ebook.book.databinding.AdpaterBookCommentsItemBinding;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.common.view.profilePhoto.CircleImageView;

public class BookCommentsAdpater extends BaseBindAdapter<Comment, AdpaterBookCommentsItemBinding> {
    public BookCommentsAdpater(Context context, ObservableArrayList<Comment> items) {
        super(context, items);
    }

    @BindingAdapter(value = {"imageUrl", "placeHolder"}, requireAll = false)
    public static void loadImage(CircleImageView imageView, String url, Drawable holderDrawable) {
        // Log.d("glide_cover", "loadImage url: "+url);
        Glide.with(imageView.getContext())
                .load(API.URL_HOST_USER + "user/image/" + url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .dontAnimate()
                .placeholder(holderDrawable)
                .into(imageView);
    }

    @Override
    protected int getLayoutItemId(int viewType) {
        return R.layout.adpater_book_comments_item;
    }

    @Override
    protected void onBindItem(AdpaterBookCommentsItemBinding binding, Comment item, int position) {
        binding.setComment(item);
        binding.layoutCommentItem.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mOnItemLongClickListener != null) {
                    mOnItemLongClickListener.onItemLongClick(item, position);
                }
                return true;
            }
        });
    }
}
