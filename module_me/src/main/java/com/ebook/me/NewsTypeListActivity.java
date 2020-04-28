package com.ebook.me;

import android.app.Activity;
import androidx.lifecycle.ViewModelProvider;
import android.content.Intent;
import androidx.annotation.Nullable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.ebook.common.event.RequestCode;
import com.ebook.common.mvvm.BaseMvvmRefreshActivity;
import com.ebook.common.util.ObservableListUtil;
import com.ebook.common.view.CommonDialogFragment;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.NewsTypeListViewModel;
import com.ebook.me.adapter.NewsTypeShowBindAdapter;
import com.ebook.me.databinding.ActivityNewsTypeListBinding;
import com.refresh.lib.DaisyRefreshLayout;


public class NewsTypeListActivity extends BaseMvvmRefreshActivity<ActivityNewsTypeListBinding, NewsTypeListViewModel> {
    private NewsTypeShowBindAdapter mNewsTypeShowAdapter;

    @Override
    public int onBindLayout() {
        return R.layout.activity_news_type_list;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar_add, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int i = item.getItemId();
        if (i == R.id.menu_add) {
            startActivityForResult(new Intent(this, NewsTypeAddActivity.class), RequestCode.Me.NEWS_TYPE_ADD);
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void initView() {
        mNewsTypeShowAdapter = new NewsTypeShowBindAdapter(this, mViewModel.getList());
        mViewModel.getList().addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mNewsTypeShowAdapter));
        mBinding.recview.setAdapter(mNewsTypeShowAdapter);
    }

    @Override
    public void initListener() {
        mNewsTypeShowAdapter.setDeleteClickLisenter(new NewsTypeShowBindAdapter.DeleteClickLisenter() {
            @Override
            public void onClickDeleteListener(final int id) {
                new CommonDialogFragment.Builder().setTitle("信息提示").setDescribe("确定删除吗？").setLeftbtn("取消").setRightbtn("确定").setOnDialogClickListener(new CommonDialogFragment.OnDialogClickListener() {
                    @Override
                    public void onLeftBtnClick(View view) {
                    }

                    @Override
                    public void onRightBtnClick(View view) {
                        mViewModel.deleteNewsTypeById(id);
                    }
                }).build().show(getSupportFragmentManager(), "dialog");
            }
        });
    }

    @Override
    public void initData() {
        mViewModel.refreshData();
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case RequestCode.Me.NEWS_TYPE_ADD:
                if (resultCode == Activity.RESULT_OK) {
                    autoLoadData();
                }
                break;
        }
    }

    @Override
    public Class<NewsTypeListViewModel> onBindViewModel() {
        return NewsTypeListViewModel.class;
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
    public DaisyRefreshLayout getRefreshLayout() {
        return mBinding.refviewNewsType;
    }
}
