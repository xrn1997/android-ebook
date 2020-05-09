package com.ebook.me;


import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.api.entity.Comment;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseMvvmRefreshActivity;
import com.ebook.common.util.ObservableListUtil;
import com.ebook.me.adapter.CommentListAdapter;
import com.ebook.me.databinding.ActivityCommentBinding;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.CommentViewModel;
import com.ebook.me.view.DeleteDialog;
import com.refresh.lib.DaisyRefreshLayout;


import androidx.lifecycle.ViewModelProvider;

@Route(path= KeyCode.Me.Comment_PATH)
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
        return  MeViewModelFactory.getInstance(getApplication());
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
        mCommentListAdapter=new CommentListAdapter(this,mViewModel.getList());
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
        mCommentListAdapter.setItemClickListener(new BaseBindAdapter.OnItemClickListener<Comment>() {
            @Override
            public void onItemClick(Comment comment, int position) {
                //TODO 处理打开评论事件

            }
        });
        mCommentListAdapter.setOnItemLongClickListener(new BaseBindAdapter.OnItemLongClickListener<Comment>() {
            @Override
            public boolean onItemLongClick(Comment comment, int postion) {
                DeleteDialog deleteDialog = DeleteDialog.newInstance();
                deleteDialog.setOnClickLisener(new DeleteDialog.OnDeleteClickLisener() {
                    @Override
                    public void onItemClick() {
                        mViewModel.deleteComent(comment.getId());
                    }
                });
                deleteDialog.show(getSupportFragmentManager(),"deleteDialog");
                return true;
            }
        });
    }

    @Override
    public DaisyRefreshLayout getRefreshLayout() {
        return mBinding.refviewCommentList;
    }
}
