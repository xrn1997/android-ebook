package com.ebook.me.adapter;


import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.databinding.BindingAdapter;
import androidx.databinding.ObservableArrayList;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.api.config.API;
import com.ebook.api.entity.Comment;
import com.ebook.common.view.profilePhoto.CircleImageView;
import com.ebook.me.R;
import com.ebook.me.databinding.AdapterCommentListItemBinding;
import com.xrn1997.common.adapter.BaseBindAdapter;

public class CommentListAdapter extends BaseBindAdapter<Comment, AdapterCommentListItemBinding> {
    public CommentListAdapter(Context context, ObservableArrayList<Comment> items) {
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
        return R.layout.adapter_comment_list_item;
    }

    @Override
    protected void onBindItem(AdapterCommentListItemBinding binding, Comment item, int position) {
        binding.setComment(item);
        binding.layoutCommentItem.setOnClickListener(v -> {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(item, position);
            }
        });
        binding.layoutCommentItem.setOnLongClickListener(v -> {
            if (mOnItemLongClickListener != null) {
                mOnItemLongClickListener.onItemLongClick(item, position);
            }
            return true;
        });
    }
}
