package com.ebook.me;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.annotation.Nullable;
import com.ebook.api.newstype.entity.NewsType;
import com.ebook.common.adapter.BaseAdapter;
import com.ebook.common.mvvm.BaseMvvmActivity;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.NewsDetailAddViewModel;
import com.ebook.me.view.NewsTypeBottomSelectDialog;
import com.ebook.me.databinding.ActivityNewsDetailAddBinding;

import java.util.List;


public class NewsDetailAddActivity extends BaseMvvmActivity<ActivityNewsDetailAddBinding, NewsDetailAddViewModel> {
    @Override
    public int onBindLayout() { return R.layout.activity_news_detail_add; }

    @Override
    public Class<NewsDetailAddViewModel> onBindViewModel() { return NewsDetailAddViewModel.class; }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() { return MeViewModelFactory.getInstance(getApplication()); }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

    @Override
    public void initData() {
        //初始化数据的地方，比如findViewById 什么的。
    }

    public void showNewsType(List<NewsType> typeList) {
        //显示新闻类型的方法，typeList里面存储着要显示的数据
        NewsTypeBottomSelectDialog newsTypeBottomSelectDialog = NewsTypeBottomSelectDialog.newInstance(typeList);
        newsTypeBottomSelectDialog.setItemClickListener(new BaseAdapter.OnItemClickListener<NewsType>() {
            @Override
            public void onItemClick(NewsType newsType, int position) {
                //监听item点击事件
                mViewModel.setNewsType(newsType);
                mBinding.viewMeSetNewsType.setContent(newsType.getTypename());
            }
        });
        newsTypeBottomSelectDialog.show(getSupportFragmentManager(), "dialog");
    }

    @Override
    public void initViewObservable() {
        mViewModel.getSingleNewsTypeLiveEvent().observe(this, new Observer<List<NewsType>>() {
            @Override
            public void onChanged(@Nullable List<NewsType> newsTypes) {
                showNewsType(newsTypes);
            }
        });
    }
}
