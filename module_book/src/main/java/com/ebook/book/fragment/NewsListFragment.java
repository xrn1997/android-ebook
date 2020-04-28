package com.ebook.book.fragment;



import android.content.Context;
import android.os.Bundle;
import android.view.View;


import com.ebook.api.news.entity.NewsDetail;
import com.ebook.api.newstype.entity.NewsType;
import com.ebook.book.BR;
import com.ebook.book.NewsDetailActivity;
import com.ebook.book.R;
import com.ebook.book.adapter.NewsListAdatper;
import com.ebook.book.databinding.FragmentNewsListBinding;
import com.ebook.book.mvvm.factory.NewsViewModelFactory;
import com.ebook.book.mvvm.viewmodel.NewsListViewModel;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.me.NewsDetailCurdEvent;
import com.ebook.common.mvvm.BaseMvvmRefreshFragment;
import com.ebook.common.util.ObservableListUtil;
import com.ebook.common.util.log.KLog;
import com.refresh.lib.DaisyRefreshLayout;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import androidx.lifecycle.ViewModelProvider;


public class NewsListFragment extends BaseMvvmRefreshFragment<NewsDetail, FragmentNewsListBinding, NewsListViewModel> {
    private NewsType mNewsType;
    private NewsListAdatper mNewsListAdatper;

    public static NewsListFragment newInstance(NewsType newsType) {
        NewsListFragment newsListFragment = new NewsListFragment();
        Bundle args = new Bundle();
        args.putParcelable(KeyCode.Book.NEWS_TYPE, newsType);
        newsListFragment.setArguments(args);
        return newsListFragment;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mNewsType = getArguments().getParcelable(KeyCode.Book.NEWS_TYPE);
    }

    @Override
    public int onBindLayout() {
        return R.layout.fragment_news_list;
    }

    @Override
    public boolean enableLazyData() {
        return true;
    }

    @Override
    public void initView(View view) {
        KLog.v("MYTAG", "initView start:" + mNewsType.getTypename());

        mNewsListAdatper = new NewsListAdatper(mActivity, mViewModel.getList());
        mNewsListAdatper.setItemClickListener(new BaseBindAdapter.OnItemClickListener<NewsDetail>() {
            @Override
            public void onItemClick(NewsDetail newsDetail, int position) {
                NewsDetailActivity.startNewsDetailActivity(mActivity,newsDetail.getId());
            }
        });
        mViewModel.getList().addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mNewsListAdatper));
        mBinding.recview.setAdapter(mNewsListAdatper);
    }

    @Override
    public void initData() {
        mViewModel.setNewsType(mNewsType.getId());
        KLog.v("MYTAG", "initData start:" + mNewsType.getTypename());
        autoLoadData();
    }

    @Override
    public String getToolbarTitle() {
        return null;
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(NewsDetailCurdEvent curdEvent) {
        if (curdEvent.getCode() == mNewsType.getId()) {
            autoLoadData();
        }
    }

    @Override
    public Class<NewsListViewModel> onBindViewModel() {
        return NewsListViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return NewsViewModelFactory.getInstance(mActivity.getApplication());
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
