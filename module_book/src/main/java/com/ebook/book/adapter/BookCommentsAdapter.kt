package com.ebook.book.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.ebook.api.entity.Comment
import com.ebook.book.R
import com.ebook.book.databinding.AdpaterBookCommentsItemBinding
import com.ebook.common.callback.CommentDifferCallback
import com.ebook.common.util.DateUtil
import com.xrn1997.common.adapter.BaseBindAdapter

class BookCommentsAdapter(context: Context) :
    BaseBindAdapter<Comment, AdpaterBookCommentsItemBinding>(context, CommentDifferCallback()) {


    override fun onBindItem(binding: AdpaterBookCommentsItemBinding, item: Comment, position: Int) {
        Glide.with(context)
            .load(item.user.image)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .fitCenter()
            .dontAnimate()
            .placeholder(R.drawable.image_default)
            .into(binding.viewProfilePhoto)
        binding.txtMeNewsTypeTitle.text = item.user.nickname
        binding.tvComment.text = item.comment
        binding.tvAddTime.text = DateUtil.formatDate(item.addTime, DateUtil.FormatType.yyyyMMddHHmm)
    }


    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        attachToParent: Boolean,
        viewType: Int
    ): AdpaterBookCommentsItemBinding {
        return AdpaterBookCommentsItemBinding.inflate(inflater, parent, attachToParent)
    }
}
