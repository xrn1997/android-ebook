package com.ebook.common.callback

import androidx.recyclerview.widget.DiffUtil
import com.ebook.db.entity.BookShelf

class BookShelfDifferCallback : DiffUtil.ItemCallback<BookShelf>() {
    override fun areItemsTheSame(oldItem: BookShelf, newItem: BookShelf): Boolean {
        return oldItem.noteUrl == newItem.noteUrl
    }

    override fun areContentsTheSame(oldItem: BookShelf, newItem: BookShelf): Boolean {
        return oldItem == newItem
    }
}