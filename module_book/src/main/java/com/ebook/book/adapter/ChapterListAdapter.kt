package com.ebook.book.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.ebook.book.databinding.ViewAdapterChapterlistBinding
import com.ebook.common.R

import com.ebook.common.callback.ChapterListDifferCallback
import com.ebook.db.entity.ChapterList

import com.xrn1997.common.adapter.BaseBindAdapter

class ChapterListAdapter(
    context: Context
) : BaseBindAdapter<ChapterList, ViewAdapterChapterlistBinding>(
    context,
    ChapterListDifferCallback()
) {
    private var index = 0

    override fun onBindItem(
        binding: ViewAdapterChapterlistBinding,
        item: ChapterList,
        position: Int
    ) {
        if (position == itemCount - 1) {
            binding.vLine.visibility = View.INVISIBLE
        } else binding.vLine.visibility = View.VISIBLE
        binding.tvName.text = item.durChapterName
        if (position == index) {
            binding.flContent.setBackgroundColor(Color.parseColor("#cfcfcf"))
            binding.flContent.isClickable = false
        } else {
            binding.flContent.setBackgroundResource(R.drawable.bg_ib_pre2)
            binding.flContent.isClickable = true
        }
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        attachToParent: Boolean,
        viewType: Int
    ): ViewAdapterChapterlistBinding {
        return ViewAdapterChapterlistBinding.inflate(inflater, parent, attachToParent)
    }

    fun setIndex(index: Int) {
        notifyItemChanged(this.index)
        this.index = index
        notifyItemChanged(this.index)
    }
}
