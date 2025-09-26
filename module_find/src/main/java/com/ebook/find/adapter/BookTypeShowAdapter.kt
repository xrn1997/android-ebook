package com.ebook.find.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import com.ebook.find.databinding.AdpaterBookTypeItemBinding
import com.ebook.find.entity.BookType
import com.xrn1997.common.adapter.BaseBindAdapter

class BookTypeShowAdapter(context: Context) :
    BaseBindAdapter<BookType, AdpaterBookTypeItemBinding>(context, BookTypeDifferCallback()) {

    override fun onBindItem(binding: AdpaterBookTypeItemBinding, item: BookType, position: Int) {
        binding.viewBooktype.text = item.bookType
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        attachToParent: Boolean,
        viewType: Int
    ): AdpaterBookTypeItemBinding {
        return AdpaterBookTypeItemBinding.inflate(inflater, parent, attachToParent)
    }

    class BookTypeDifferCallback : DiffUtil.ItemCallback<BookType>() {
        override fun areItemsTheSame(oldItem: BookType, newItem: BookType): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: BookType, newItem: BookType): Boolean {
            return oldItem == newItem
        }

    }
}
