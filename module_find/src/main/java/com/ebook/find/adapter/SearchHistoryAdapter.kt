package com.ebook.find.adapter

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.ebook.common.view.flowlayout.FlowLayout
import com.ebook.common.view.flowlayout.TagAdapter
import com.ebook.db.entity.SearchHistory
import com.ebook.find.R

class SearchHistoryAdapter : TagAdapter<SearchHistory>(ArrayList()) {
    private var listener: ((searchHistory: SearchHistory) -> Unit)? = null

    fun setOnItemClickListener(listener: (searchHistory: SearchHistory) -> Unit) {
        this.listener = listener
    }

    override fun getView(parent: FlowLayout, position: Int, item: SearchHistory): View {
        val tv = LayoutInflater.from(parent.context).inflate(
            R.layout.adapter_searchhistory_item,
            parent, false
        ) as TextView
        tv.text = item.content
        tv.setOnClickListener {
            listener?.invoke(item)
        }
        return tv
    }

    fun getItemData(position: Int): SearchHistory {
        return mTagData[position]
    }

    val dataSize: Int
        get() = mTagData.size

}
