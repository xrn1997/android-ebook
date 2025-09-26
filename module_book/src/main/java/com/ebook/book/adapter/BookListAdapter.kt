package com.ebook.book.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.ebook.book.R
import com.ebook.book.databinding.AdapterBookListItemBinding
import com.ebook.common.callback.BookShelfDifferCallback
import com.ebook.db.entity.BookShelf
import com.xrn1997.common.adapter.BaseBindAdapter

class BookListAdapter(context: Context) :
    BaseBindAdapter<BookShelf, AdapterBookListItemBinding>(context, BookShelfDifferCallback()) {

    override fun onBindItem(binding: AdapterBookListItemBinding, item: BookShelf, position: Int) {
        Glide.with(context)
            .load(item.bookInfo.target.coverUrl)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .placeholder(com.ebook.common.R.drawable.img_cover_default)
            .error(com.ebook.common.R.drawable.img_cover_default)
            .fallback(com.ebook.common.R.drawable.img_cover_default)
            .fitCenter()
            .into(binding.ivCover)
        binding.txtBookName.text = item.bookInfo.target.name
        binding.txtBookAuthor.text = item.bookInfo.target.author
        binding.txtBookStatus.text = String.format(
            context.resources.getString(R.string.read_to) +
                    item.bookInfo.target.chapterList[item.durChapter].durChapterName
        )
    }
    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        attachToParent: Boolean,
        viewType: Int
    ): AdapterBookListItemBinding {
        return AdapterBookListItemBinding.inflate(inflater, parent, attachToParent)
    }
}
