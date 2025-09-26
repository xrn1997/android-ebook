package com.ebook.find.adapter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.ebook.common.callback.LibraryKindBookListDifferCallback
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.db.entity.LibraryKindBookList
import com.ebook.db.entity.SearchBook
import com.ebook.find.ChoiceBookActivity
import com.ebook.find.databinding.ViewLibraryKindbookBinding
import com.therouter.TheRouter
import com.xrn1997.common.adapter.BaseBindAdapter

class LibraryBookListAdapter(context: Context) :
    BaseBindAdapter<LibraryKindBookList, ViewLibraryKindbookBinding>(
        context, LibraryKindBookListDifferCallback()
    ) {
    override fun onBindItem(
        binding: ViewLibraryKindbookBinding,
        item: LibraryKindBookList,
        position: Int
    ) {
        binding.tvKindname.text = item.kindName
        val libraryBookAdapter = LibraryBookAdapter(context)
        libraryBookAdapter.submitList(item.books)
        if (item.kindUrl.isEmpty()) {
            binding.tvMore.visibility = View.GONE
            binding.tvMore.setOnClickListener(null)
        } else {
            binding.tvMore.visibility = View.VISIBLE
            binding.tvMore.setOnClickListener {
                val intent = Intent(context, ChoiceBookActivity::class.java)
                val bundle = Bundle()
                bundle.putString("url", item.kindUrl)
                bundle.putString("title", item.kindName)
                intent.putExtras(bundle)
                context.startActivity(intent)
            }
        }
        libraryBookAdapter.setOnItemClickListener { searchBook: SearchBook, _: Int ->
            TheRouter.build(KeyCode.Book.DETAIL_PATH)
                .withInt("from", FROM_SEARCH)
                .withObject("data", searchBook)
                .navigation()
        }
        binding.rvBooklist.adapter = libraryBookAdapter
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        attachToParent: Boolean,
        viewType: Int
    ): ViewLibraryKindbookBinding {
        return ViewLibraryKindbookBinding.inflate(inflater, parent, attachToParent)
    }

}
