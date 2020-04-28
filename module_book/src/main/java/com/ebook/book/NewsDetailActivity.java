package com.ebook.book;

import android.content.Context;
import android.content.Intent;

import com.ebook.book.databinding.ActivityNewsDetailBinding;
import com.ebook.book.mvvm.factory.NewsViewModelFactory;
import com.ebook.book.mvvm.viewmodel.NewsDetailViewModel;
import com.ebook.common.event.KeyCode;
import com.ebook.common.mvvm.BaseMvvmActivity;

import androidx.lifecycle.ViewModelProvider;


public class NewsDetailActivity extends BaseMvvmActivity<ActivityNewsDetailBinding, NewsDetailViewModel> {
    public static void startNewsDetailActivity(Context context,int id){
        Intent intent = new Intent(context, NewsDetailActivity.class);
        intent.putExtra(KeyCode.Book.NEWS_ID,id);
        context.startActivity(intent);
    }

    @Override
    public int onBindLayout() {
        return R.layout.activity_news_detail;
    }


    @Override
    public void initData() {
        int newsid = getIntent().getIntExtra(KeyCode.Book.NEWS_ID,-1);
        mViewModel.getNewsDetailById(newsid);
    }

    @Override
    public Class<NewsDetailViewModel> onBindViewModel() {
        return NewsDetailViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return NewsViewModelFactory.getInstance(getApplication());
    }

    @Override
    public void initViewObservable() {

    }

    @Override
    public int onBindVariableId() {
        return 0;
    }
}
