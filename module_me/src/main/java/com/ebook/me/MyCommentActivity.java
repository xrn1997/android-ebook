package com.ebook.me;


import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

import com.ebook.common.event.KeyCode;
import com.ebook.common.view.DeleteDialog;
import com.ebook.me.adapter.CommentListAdapter;
import com.ebook.me.databinding.ActivityCommentBinding;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.CommentViewModel;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.therouter.TheRouter;
import com.therouter.router.Route;
import com.xrn1997.common.mvvm.view.BaseMvvmRefreshActivity;
import com.xrn1997.common.util.ObservableListUtil;

@Route(path = KeyCode.Me.COMMENT_PATH, params = {"needLogin", "true"})
public class MyCommentActivity extends BaseMvvmRefreshActivity<ActivityCommentBinding, CommentViewModel> {

    @Override
    public int onBindLayout() {
        return R.layout.activity_comment;
    }

    @NonNull
    @Override
    public Class<CommentViewModel> onBindViewModel() {
        return CommentViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return MeViewModelFactory.INSTANCE;
    }

    @Override
    public void initViewObservable() {

    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

    @Override
    public void initView() {
        CommentListAdapter mCommentListAdapter = new CommentListAdapter(this, mViewModel.mList);
        mViewModel.mList.addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mCommentListAdapter));
        getBinding().viewMyCommentList.setAdapter(mCommentListAdapter);
        mCommentListAdapter.setOnItemClickListener((comment, position) -> {
            Bundle bundle = new Bundle();
            bundle.putString("chapterUrl", comment.getChapterUrl());
            bundle.putString("chapterName", comment.getChapterName());
            bundle.putString("bookName", comment.getBookName());
            TheRouter.build(KeyCode.Book.COMMENT_PATH)
                    .with(bundle)
                    .navigation(MyCommentActivity.this);
        });
        mCommentListAdapter.setOnItemLongClickListener((comment, position) -> {
            DeleteDialog deleteDialog = DeleteDialog.newInstance();
            deleteDialog.setOnClickListener(() -> mViewModel.deleteComment(comment.getId()));
            deleteDialog.show(getSupportFragmentManager(), "deleteDialog");
            return true;
        });
    }

    @Override
    public boolean enableToolbar() {
        return true;
    }

    @Override
    public void initData() {
        mViewModel.refreshData();
    }

    @NonNull
    @Override
    public RefreshLayout getRefreshLayout() {
        return getBinding().refreshCommentList;
    }
}
