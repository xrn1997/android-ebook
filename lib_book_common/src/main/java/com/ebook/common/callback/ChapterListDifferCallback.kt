package com.ebook.common.callback

import androidx.recyclerview.widget.DiffUtil
import com.ebook.db.entity.ChapterList

class ChapterListDifferCallback : DiffUtil.ItemCallback<ChapterList>() {
    override fun areItemsTheSame(oldItem: ChapterList, newItem: ChapterList): Boolean {
        return oldItem.durChapterUrl == newItem.durChapterUrl
    }

    override fun areContentsTheSame(oldItem: ChapterList, newItem: ChapterList): Boolean {
        return oldItem == newItem
    }
}