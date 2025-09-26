package com.ebook.me.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.ebook.api.entity.Comment
import com.ebook.common.callback.CommentDifferCallback
import com.ebook.common.util.DateUtil
import com.ebook.me.R
import com.ebook.me.databinding.AdapterCommentListItemBinding
import com.xrn1997.common.adapter.BaseBindAdapter


@Suppress("unused")
class CommentListAdapter(context: Context) :
    BaseBindAdapter<Comment, AdapterCommentListItemBinding>(context, CommentDifferCallback()) {

    override fun onBindItem(binding: AdapterCommentListItemBinding, item: Comment, position: Int) {
        Glide.with(context)
            .load(item.user.image)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .fitCenter()
            .dontAnimate()
            .placeholder(R.drawable.image_default)
            .into(binding.viewProfilePhoto)
        binding.txtMeNewsTypeTitle.text = item.user.nickname
        binding.tvComment.text = item.comment
        binding.viewCommentAddress.text =
            String.format(
                context.resources.getString(R.string.comment) +
                        item.bookName +
                        context.resources.getString(R.string.shu_ming_hao) +
                        item.chapterName
            )
        binding.tvAddTime.text = DateUtil.formatDate(item.addTime, DateUtil.FormatType.yyyyMMddHHmm)
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        attachToParent: Boolean,
        viewType: Int
    ): AdapterCommentListItemBinding {
        return AdapterCommentListItemBinding.inflate(inflater, parent, attachToParent)
    }
}
