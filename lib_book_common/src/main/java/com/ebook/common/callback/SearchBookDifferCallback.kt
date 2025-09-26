package com.ebook.common.callback

import androidx.recyclerview.widget.DiffUtil
import com.ebook.db.entity.SearchBook

class SearchBookDifferCallback : DiffUtil.ItemCallback<SearchBook>() {
    override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
        return oldItem.noteUrl == newItem.noteUrl
    }

    override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
        return oldItem == newItem
    }

}