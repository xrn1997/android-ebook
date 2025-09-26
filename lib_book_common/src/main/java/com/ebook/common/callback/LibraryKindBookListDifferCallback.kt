package com.ebook.common.callback

import androidx.recyclerview.widget.DiffUtil
import com.ebook.db.entity.LibraryKindBookList

class LibraryKindBookListDifferCallback : DiffUtil.ItemCallback<LibraryKindBookList>() {
    override fun areItemsTheSame(
        oldItem: LibraryKindBookList,
        newItem: LibraryKindBookList
    ): Boolean {
        return oldItem.kindUrl == newItem.kindUrl
    }

    override fun areContentsTheSame(
        oldItem: LibraryKindBookList,
        newItem: LibraryKindBookList
    ): Boolean {
        return oldItem == newItem
    }
}