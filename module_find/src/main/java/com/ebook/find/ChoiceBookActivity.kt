package com.ebook.find

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.SearchBook
import com.ebook.find.adapter.SearchBookAdapter
import com.ebook.find.databinding.ActivityBookchoiceBinding
import com.ebook.find.mvvm.viewmodel.ChoiceBookViewModel
import com.hwangjr.rxbus.RxBus
import com.hwangjr.rxbus.annotation.Subscribe
import com.hwangjr.rxbus.annotation.Tag
import com.hwangjr.rxbus.thread.EventThread
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.view.BaseMvvmRefreshActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ChoiceBookActivity :
    BaseMvvmRefreshActivity<ActivityBookchoiceBinding, ChoiceBookViewModel>() {
    override val mViewModel: ChoiceBookViewModel by viewModels()
    private lateinit var tvTitle: TextView
    private lateinit var rfRvSearchBooks: RecyclerView
    private lateinit var searchBookAdapter: SearchBookAdapter

    override fun getRefreshLayout(): RefreshLayout {
        return binding.rfRvSmartRefreshLayout
    }

    override fun initView() {
        tvTitle = binding.tvTitle
        searchBookAdapter = SearchBookAdapter(this)
        rfRvSearchBooks = binding.rfRvSearchBooks
        rfRvSearchBooks.layoutManager = LinearLayoutManager(this)
        rfRvSearchBooks.adapter = searchBookAdapter
        binding.ivReturn.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        searchBookAdapter.setItemClickListener(object : SearchBookAdapter.OnItemClickListener {
            override fun clickAddShelf(clickView: View, position: Int, searchBook: SearchBook) {
                mViewModel.addBookToShelf(searchBook)
            }

            override fun clickItem(animView: View, position: Int, searchBook: SearchBook) {
                TheRouter.build(KeyCode.Book.DETAIL_PATH)
                    .withInt("from", FROM_SEARCH)
                    .withObject("data", searchBook)
                    .navigation(this@ChoiceBookActivity)
            }
        })
    }


    override fun initData() {
        val bundle = this.intent.extras
        if (bundle != null) {
            tvTitle.text = bundle.getString("title")
            mViewModel.url = bundle.getString("url", "")
        }
        mViewModel.mList.observe(this) {
            searchBookAdapter.submitList(it)
        }
    }

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityBookchoiceBinding {
        return ActivityBookchoiceBinding.inflate(inflater, parent, attachToParent)
    }

    override fun enableLoadMore(): Boolean {
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
    }

    // 处理添加书籍事件
    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.HAD_ADD_BOOK)])
    fun hadAddBook(bookShelf: BookShelf) {
        mViewModel.bookShelves.add(bookShelf)
        handleBookUpdate(bookShelf, true)  // true 表示添加书籍
    }

    // 处理移除书籍事件
    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.HAD_REMOVE_BOOK)])
    fun hadRemoveBook(bookShelf: BookShelf) {
        mViewModel.bookShelves.remove(bookShelf)
        handleBookUpdate(bookShelf, false)  // false 表示移除书籍
    }

    // 公共处理逻辑
    private fun handleBookUpdate(bookShelf: BookShelf, isAdd: Boolean) {
        val currentList = mViewModel.mList.value?.toMutableList() ?: mutableListOf()

        val index = currentList.indexOfFirst { it.noteUrl == bookShelf.noteUrl }
        if (index != -1) {
            // 根据 isAdd 判断是添加还是移除书籍
            val updatedBook = currentList[index].copy(add = isAdd)
            currentList[index] = updatedBook
        }
        searchBookAdapter.submitList(currentList)
    }
}