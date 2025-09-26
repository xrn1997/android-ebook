package com.ebook.book

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.SPUtils
import com.ebook.api.entity.Comment
import com.ebook.book.adapter.BookCommentsAdapter
import com.ebook.book.databinding.ActivityBookCommentsBinding
import com.ebook.book.mvvm.viewmodel.BookCommentsViewModel
import com.ebook.common.event.KeyCode
import com.ebook.common.util.SoftInputUtil.hideSoftInput
import com.ebook.common.view.DeleteDialog.Companion.newInstance
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.therouter.router.Route
import com.xrn1997.common.mvvm.view.BaseMvvmRefreshActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
@Route(path = KeyCode.Book.COMMENT_PATH, params = ["needLogin", "true"])
class BookCommentsActivity :
    BaseMvvmRefreshActivity<ActivityBookCommentsBinding, BookCommentsViewModel>() {
    override val mViewModel: BookCommentsViewModel by viewModels()
    private lateinit var editText: EditText

    override fun getRefreshLayout(): RefreshLayout {
        return binding.refviewBookCommentsList
    }

    override fun initView() {
        editText = binding.textInput
        val mBookCommentsAdapter = BookCommentsAdapter(this)
        mViewModel.mList.observe(this) {
            mBookCommentsAdapter.submitList(it)
        }
        binding.viewBookComments.layoutManager = LinearLayoutManager(this)
        binding.viewBookComments.adapter = mBookCommentsAdapter
        mViewModel.mVoidSingleLiveEvent.observe(this) {
            hideSoftInput(this@BookCommentsActivity, editText)
        }
        mBookCommentsAdapter.setOnItemLongClickListener { comment: Comment, _: Int ->
            val username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME)
            if (comment.user.username == username) {
                val deleteDialog = newInstance()
                deleteDialog.setOnClickListener {
                    mViewModel.deleteComment(comment.id)
                }
                deleteDialog.show(supportFragmentManager, "deleteDialog")
            }
            true
        }
        binding.btnAddComment.setOnClickListener {
            val comments = binding.textInput.text.toString()
            mViewModel.addComment(comments)
        }
    }


    override fun initData() {
        val bundle = this.intent.extras
        if (bundle != null && !bundle.isEmpty) {
            val comment = Comment()
            comment.bookName = bundle.getString("bookName")
            comment.chapterName = bundle.getString("chapterName")
            comment.chapterUrl = bundle.getString("chapterUrl")
            mViewModel.comment = comment
            mRefreshLayout.autoRefresh()
        }
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityBookCommentsBinding {
        return ActivityBookCommentsBinding.inflate(inflater, parent, attachToParent)
    }

    override fun enableLoadMore(): Boolean {
        return false
    }
}
