package com.ebook.book;

import android.os.Bundle;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.api.entity.Comment;
import com.ebook.book.adapter.BookCommentsAdapter;
import com.ebook.book.databinding.ActivityBookCommentsBinding;
import com.ebook.book.mvvm.factory.BookViewModelFactory;
import com.ebook.book.mvvm.viewmodel.BookCommentsViewModel;
import com.ebook.common.event.KeyCode;
import com.ebook.common.util.SoftInputUtil;
import com.ebook.common.view.DeleteDialog;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.therouter.router.Route;
import com.xrn1997.common.mvvm.view.BaseMvvmRefreshActivity;
import com.xrn1997.common.util.ObservableListUtil;

@Route(path = KeyCode.Book.COMMENT_PATH)
public class BookCommentsActivity extends BaseMvvmRefreshActivity<ActivityBookCommentsBinding, BookCommentsViewModel> {
    private EditText editText;

    @NonNull
    @Override
    public RefreshLayout getRefreshLayout() {
        return getBinding().refviewBookCommentsList;
    }

    @NonNull
    @Override
    public Class<BookCommentsViewModel> onBindViewModel() {
        return BookCommentsViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return BookViewModelFactory.INSTANCE;
    }

    @Override
    public void initViewObservable() {
        mViewModel.getmVoidSingleLiveEvent().observe(this, aVoid -> {
            mViewModel.comments.set("");
            SoftInputUtil.hideSoftInput(BookCommentsActivity.this, editText);
        });
    }

    @NonNull
    @Override
    public String getToolBarTitle() {
        return "本章评论";
    }

    @Override
    public void initView() {
        editText = findViewById(R.id.text_input);
        BookCommentsAdapter mBookCommentsAdapter = new BookCommentsAdapter(this, mViewModel.mList);
        mViewModel.mList.addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mBookCommentsAdapter));
        getBinding().viewBookComments.setAdapter(mBookCommentsAdapter);
        mBookCommentsAdapter.setOnItemLongClickListener((comment, position) -> {
            String username = SPUtils.getInstance().getString(KeyCode.Login.SP_USERNAME);
            if (comment.getUser().getUsername().equals(username)) {
                DeleteDialog deleteDialog = DeleteDialog.newInstance();
                deleteDialog.setOnClickListener(() -> mViewModel.deleteComment(comment.getId()));
                deleteDialog.show(getSupportFragmentManager(), "deleteDialog");
            }
            return true;
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
