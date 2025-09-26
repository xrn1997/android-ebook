package com.ebook.common.callback

import androidx.recyclerview.widget.DiffUtil
import java.io.File

class FileDifferCallback : DiffUtil.ItemCallback<File>() {
    override fun areItemsTheSame(oldItem: File, newItem: File): Boolean {
        return oldItem.absolutePath == newItem.absolutePath
    }

    override fun areContentsTheSame(oldItem: File, newItem: File): Boolean {
        return oldItem.length() == newItem.length() && oldItem.lastModified() == newItem.lastModified()
    }
}

