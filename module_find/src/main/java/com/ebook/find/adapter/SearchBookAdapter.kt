package com.ebook.find.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.ebook.common.R
import com.ebook.common.callback.SearchBookDifferCallback
import com.ebook.db.entity.SearchBook
import com.ebook.find.databinding.AdapterSearchbookItemBinding
import com.xrn1997.common.adapter.BaseBindAdapter
import java.text.DecimalFormat

class SearchBookAdapter(
    context: Context
) : BaseBindAdapter<SearchBook, AdapterSearchbookItemBinding>(context, SearchBookDifferCallback()) {
    private var itemClickListener: OnItemClickListener? = null

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        attachToParent: Boolean,
        viewType: Int
    ): AdapterSearchbookItemBinding {
        return AdapterSearchbookItemBinding.inflate(inflater, parent, attachToParent)
    }

    override fun onBindItem(
        binding: AdapterSearchbookItemBinding,
        item: SearchBook,
        position: Int
    ) {
            Glide.with(context)
                .load(item.coverUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.drawable.img_cover_default)
                .into(binding.ivCover)
            binding.tvName.text = item.name
            binding.tvAuthor.text = item.author
            val state = item.state
            if (state.isEmpty()) {
                binding.tvState.visibility = View.GONE
            } else {
                binding.tvState.visibility = View.VISIBLE
                binding.tvState.text = state
            }
            val words = item.words
            if (words <= 0) {
                binding.tvWords.visibility = View.GONE
            } else {
                var wordsS = words.toString() + "字"
                if (words > 10000) {
                    val df = DecimalFormat("#.#")
                    wordsS = df.format((words * 1.0f / 10000f).toDouble()) + "万字"
                }
                binding.tvWords.visibility = View.VISIBLE
                binding.tvWords.text = wordsS
            }
            val kind = item.kind
            if (kind.isEmpty()) {
                binding.tvKind.visibility = View.GONE
            } else {
                binding.tvKind.visibility = View.VISIBLE
                binding.tvKind.text = kind
            }
            if (item.lastChapter.isNotEmpty()) binding.tvLastest.text =
                item.lastChapter
            else if (item.desc.isNotEmpty()) {
                binding.tvLastest.text = item.desc
            } else binding.tvLastest.text = ""
            if (item.origin.isNotEmpty()) {
                binding.tvOrigin.visibility = View.VISIBLE
                binding.tvOrigin.text = String.format("来源:" + item.origin)
            } else {
                binding.tvOrigin.visibility = View.GONE
            }
            if (item.add) {
                binding.tvAddshelf.text = "已添加"
                binding.tvAddshelf.isEnabled = false
            } else {
                binding.tvAddshelf.text = "+添加"
                binding.tvAddshelf.isEnabled = true
            }

            binding.flContent.setOnClickListener {
                if (itemClickListener != null) itemClickListener!!.clickItem(
                    binding.ivCover, position, item
                )
            }
            binding.tvAddshelf.setOnClickListener {
                if (itemClickListener != null) itemClickListener!!.clickAddShelf(
                    binding.tvAddshelf,
                    position,
                    item
                )
            }
    }

    fun setItemClickListener(itemClickListener: OnItemClickListener?) {
        this.itemClickListener = itemClickListener
    }

    interface OnItemClickListener {
        fun clickAddShelf(clickView: View, position: Int, searchBook: SearchBook)

        fun clickItem(animView: View, position: Int, searchBook: SearchBook)
    }
}