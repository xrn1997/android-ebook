package com.ebook.me;


import android.os.Bundle;

import androidx.lifecycle.ViewModelProvider;

import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseMvvmRefreshActivity;
import com.ebook.common.util.ObservableListUtil;
import com.ebook.common.view.DeleteDialog;
import com.ebook.me.adapter.CommentListAdapter;
import com.ebook.me.databinding.ActivityCommentBinding;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.CommentViewModel;
import com.refresh.lib.DaisyRefreshLayout;
import com.therouter.TheRouter;
import com.therouter.router.Route;

@Route(path = KeyCode.Me.COMMENT_PATH, params = {"needLogin", "true"})
public class MyCommentActivity extends BaseMvvmRefreshActivity<ActivityCommentBinding, CommentViewModel> {
    private CommentListAdapter mCommentListAdapter;

    @Override
    public int onBindLayout() {
        return R.layout.activity_comment;
    }

    @Override
    public Class<CommentViewModel> onBindViewModel() {
        return CommentViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return MeViewModelFactory.getInstance(getApplication());
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
        mCommentListAdapter = new CommentListAdapter(this, mViewModel.getList());
        mViewModel.getList().addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mCommentListAdapter));
        mBinding.viewMyCommentList.setAdapter(mCommentListAdapter);
    }

    @Override
    public boolean enableToolbar() {
        return true;
    }

    @Override
    public void initData() {
        mViewModel.refreshData();
    }

    @Override
    public void initListener() {
        super.initListener();
        mCommentListAdapter.setItemClickListener((comment, position) -> {
            Bundle bundle = new Bundle();
            bundle.putString("chapterUrl", comment.getChapterUrl());
            bundle.putString("chapterName", comment.getChapterName());
            bundle.putString("bookName", comment.getBookName());
            TheRouter.build(KeyCode.Book.COMMENT_PATH)
                    .with(bundle)
                    .navigation(MyCommentActivity.this);
        });
        mCommentListAdapter.setOnItemLongClickListener((comment, postion) -> {
            DeleteDialog deleteDialog = DeleteDialog.newInstance();
            deleteDialog.setOnClickListener(() -> mViewModel.deleteComent(comment.getId()));
            deleteDialog.show(getSupportFragmentManager(), "deleteDialog");
            return true;
        });
    }

    @Override
    public DaisyRefreshLayout getRefreshLayout() {
        return mBinding.refviewCommentList;
    }
}
