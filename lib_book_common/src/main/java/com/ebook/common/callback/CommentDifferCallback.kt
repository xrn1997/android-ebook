package com.ebook.common.callback

import androidx.recyclerview.widget.DiffUtil
import com.ebook.api.entity.Comment

class CommentDifferCallback : DiffUtil.ItemCallback<Comment>() {
    override fun areItemsTheSame(oldItem: Comment, newItem: Comment): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Comment, newItem: Comment): Boolean {
        return oldItem == newItem
    }
}