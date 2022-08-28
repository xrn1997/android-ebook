package com.ebook.book;

import android.os.Bundle;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.entity.Comment;
import com.ebook.book.adapter.BookCommentsAdpater;
import com.ebook.book.databinding.ActivityBookCommentsBinding;
import com.ebook.book.mvvm.factory.BookViewModelFactory;
import com.ebook.book.mvvm.viewmodel.BookCommentsViewModel;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseMvvmRefreshActivity;
import com.ebook.common.util.ObservableListUtil;
import com.ebook.common.util.SoftInputUtil;
import com.ebook.common.view.DeleteDialog;
import com.refresh.lib.DaisyRefreshLayout;

@Route(path = KeyCode.Book.Comment_PATH)
public class BookCommentsActivity extends BaseMvvmRefreshActivity<ActivityBookCommentsBinding, BookCommentsViewModel> {
    private EditText editText;
    private BookCommentsAdpater mBookCommentsAdapter;

    @Override
    public DaisyRefreshLayout getRefreshLayout() {
        return mBinding.refviewBookCommentsList;
    }

    @Override
    public Class<BookCommentsViewModel> onBindViewModel() {
        return BookCommentsViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return BookViewModelFactory.getInstance(getApplication());
    }

    @Override
    public void initViewObservable() {
        mViewModel.getmVoidSingleLiveEvent().observe(this, new Observer<Void>() {
            @Override
            public void onChanged(@Nullable Void aVoid) {
                mViewModel.comments.set("");
                SoftInputUtil.hideSoftInput(BookCommentsActivity.this, editText);
            }
        });
    }

    @Override
    public boolean enableToolbar() {
        return true;
    }

    @Override
    public String getTootBarTitle() {
        return "本章评论";
    }

    @Override
    public void initView() {
        super.initView();
        editText = findViewById(R.id.text_input);
        mBookCommentsAdapter = new BookCommentsAdpater(this, mViewModel.getList());
        mViewModel.getList().addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mBookCommentsAdapter));
        mBinding.viewBookComments.setAdapter(mBookCommentsAdapter);
    }

    @Override
    public void initListener() {
        super.initListener();
        mBookCommentsAdapter.setOnItemLongClickListener(new BaseBindAdapter.OnItemLongClickListener<Comment>() {
            @Override
            public boolean onItemLongClick(Comment comment, int postion) {
                String username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME);
                if (comment.getUser().getUsername().equals(username)) {
                    DeleteDialog deleteDialog = DeleteDialog.newInstance();
                    deleteDialog.setOnClickListener(new DeleteDialog.OnDeleteClickListener() {
                        @Override
                        public void onItemClick() {
                            mViewModel.deleteComment(comment.getId());
                        }
                    });
                    deleteDialog.show(getSupportFragmentManager(), "deleteDialog");
                }
                return true;
            }
        });
    }

    @Override
    public void initData() {
        Bundle bundle = this.getIntent().getExtras();
        if (bundle != null && !bundle.isEmpty()) {
            Comment comment = new Comment();
            comment.setBookName(bundle.getString("bookName"));
            comment.setChapterName(bundle.getString("chapterName"));
            comment.setChapterUrl(bundle.getString("chapterUrl"));
            mViewModel.comment = comment;
            mViewModel.refreshData();
        }
    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

    @Override
    public int onBindLayout() {
        return R.layout.activity_book_comments;
    }
}
