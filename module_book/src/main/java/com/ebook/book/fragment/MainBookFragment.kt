package com.ebook.book.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.ebook.book.ImportBookActivity
import com.ebook.book.ReadBookActivity
import com.ebook.book.adapter.BookListAdapter
import com.ebook.book.databinding.FragmentBookMainBinding
import com.ebook.book.mvvm.viewmodel.BookListViewModel
import com.ebook.book.mvvm.viewmodel.BookReadViewModel.Companion.OPEN_FROM_APP
import com.ebook.book.service.DownloadService
import com.ebook.common.event.FROM_BOOKSHELF
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.manager.BitIntentDataManager
import com.ebook.common.view.popupwindow.DownloadListPop
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.DownloadChapterList
import com.hwangjr.rxbus.RxBus
import com.hwangjr.rxbus.annotation.Subscribe
import com.hwangjr.rxbus.annotation.Tag
import com.hwangjr.rxbus.thread.EventThread
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.view.BaseMvvmRefreshFragment
import dagger.hilt.android.AndroidEntryPoint
import java.util.Timer
import java.util.TimerTask

@Suppress("unused")
@AndroidEntryPoint
class MainBookFragment :
    BaseMvvmRefreshFragment<FragmentBookMainBinding, BookListViewModel>() {
    override val mViewModel: BookListViewModel by viewModels()
    private lateinit var ibDownload: ImageButton
    private lateinit var downloadListPop: DownloadListPop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    override fun initView() {
        downloadListPop = DownloadListPop(mActivity)
        ibDownload = binding.ibDownload
        val mBookListAdapter = BookListAdapter(mActivity)
        mViewModel.mList.observe(this) {
            mBookListAdapter.submitList(it)
        }
        binding.bookRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.bookRecyclerView.adapter = mBookListAdapter
        ibDownload.setOnClickListener { downloadListPop.showAsDropDown(ibDownload) }

        binding.ibAdd.setOnClickListener {
            startActivity(Intent(mActivity, ImportBookActivity::class.java))
        }
        mBookListAdapter.setOnItemClickListener { bookShelf: BookShelf, _: Int ->
            val intent = Intent(mActivity, ReadBookActivity::class.java)
            intent.putExtra("from", OPEN_FROM_APP)
            val key = System.currentTimeMillis().toString()
            intent.putExtra("data_key", key)
            BitIntentDataManager.putData(key, bookShelf.clone())
            startActivity(intent)
        }
        mBookListAdapter.setOnItemLongClickListener { bookShelf: BookShelf, _: Int ->
            val key = System.currentTimeMillis().toString()
            BitIntentDataManager.putData(key, bookShelf)
            TheRouter.build(KeyCode.Book.DETAIL_PATH)
                .withInt("from", FROM_BOOKSHELF)
                .withString("data_key", key)
                .navigation()
            true
        }
    }

    override fun initData() {
        mRefreshLayout.autoRefresh()
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): FragmentBookMainBinding {
        return FragmentBookMainBinding.inflate(inflater, parent, attachToParent)
    }

    override fun enableLoadMore(): Boolean {
        return false
    }

    override fun getRefreshLayout(): RefreshLayout {
        return binding.smartRefreshBookList
    }

    override fun onDestroy() {
        super.onDestroy()
        RxBus.get().unregister(this)
        downloadListPop.onDestroy()
    }

    @Subscribe(
        thread = EventThread.MAIN_THREAD,
        tags = [Tag(RxBusTag.HAD_ADD_BOOK), Tag(RxBusTag.HAD_REMOVE_BOOK), Tag(RxBusTag.UPDATE_BOOK_PROGRESS)]
    )
    fun hadAddOrRemoveBook(bookShelf: BookShelf) {
        mViewModel.refreshData()
        //autoLoadData();
    }

    @Subscribe(thread = EventThread.NEW_THREAD, tags = [Tag(RxBusTag.START_DOWNLOAD_SERVICE)])
    fun startDownloadService(result: DownloadChapterList) {
        mActivity.startService(Intent(mActivity, DownloadService::class.java))
        val task: TimerTask = object : TimerTask() {
            override fun run() {
                // 逻辑处理
                RxBus.get().post(RxBusTag.ADD_DOWNLOAD_TASK, result)
            }
        }
        val timer = Timer()
        timer.schedule(task, 500) // 延迟0.5秒，执行一次task
    }

    companion object {
        @JvmStatic
        fun newInstance(): MainBookFragment {
            return MainBookFragment()
        }
    }
}
