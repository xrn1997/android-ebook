package com.ebook.book.fragment;


import android.view.View;

import com.ebook.api.newstype.entity.NewsType;
import com.ebook.book.R;
import com.ebook.book.mvvm.factory.NewsViewModelFactory;
import com.ebook.book.mvvm.viewmodel.NewsTypeViewModel;
import com.ebook.common.event.me.NewsTypeCrudEvent;
import com.ebook.common.mvvm.BaseMvvmFragment;
import com.ebook.common.util.log.KLog;
import com.google.android.material.tabs.TabLayout;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager.widget.ViewPager;


public class MainNewsFragment extends BaseMvvmFragment<ViewDataBinding, NewsTypeViewModel> {
    private List<String> titles = new ArrayList<>();
    private List<NewsListFragment> mListFragments = new ArrayList<>();
    private TabLayout mTabLayout;
    private ViewPager mViewPager;
    private NewsFragmentAdapter mNewsFragmentAdapter;
    private boolean mIsfresh;

    public static MainNewsFragment newInstance() {
        return new MainNewsFragment();
    }

    @Override
    public int onBindLayout() {
        return R.layout.fragment_news_main;
    }

    @Override
    public void initView(View view) {
        mViewPager = view.findViewById(R.id.pager_tour);
        mTabLayout = view.findViewById(R.id.layout_tour);
    }

    @Override
    public void initData() {
        mViewModel.getListNewsType();
    }

    @Override
    public void initListener() {
        //mViewPager.setOffscreenPageLimit(mListFragments.size());
    }

    public void initTabLayout() {
        mNewsFragmentAdapter = new NewsFragmentAdapter(getChildFragmentManager());
        mViewPager.setAdapter(mNewsFragmentAdapter);
        mNewsFragmentAdapter.refreshViewPager(mListFragments);

        mTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                //mListFragments.get(tab.getPosition()).autoLoadData();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });
        mTabLayout.setupWithViewPager(mViewPager);
    }


    public void showListNewsType(List<com.ebook.api.newstype.entity.NewsType> listNewsType) {
        KLog.v("MYTAG", "initNewsListFragment start..." + listNewsType.toString());
        mListFragments.clear();
        titles.clear();
        if (listNewsType != null && listNewsType.size() > 0) {
            for (int i = 0; i < listNewsType.size(); i++) {
                NewsType newsType = listNewsType.get(i);
                mListFragments.add(NewsListFragment.newInstance(newsType));
                titles.add(newsType.getTypename());
            }
        }
    }

    @Override
    public Class<NewsTypeViewModel> onBindViewModel() {
        return NewsTypeViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return NewsViewModelFactory.getInstance(mActivity.getApplication());
    }

    @Override
    public void initViewObservable() {
        mViewModel.getListSingleLiveEvent().observe(this, new Observer<List<NewsType>>() {
            @Override
            public void onChanged(@Nullable List<NewsType> newsTypes) {
                showListNewsType(newsTypes);
                initTabLayout();
            }
        });
    }

    @Override
    public int onBindVariableId() {
        return 0;
    }

    class NewsFragmentAdapter extends FragmentPagerAdapter {
        public FragmentManager mFragmentManager;
        List<NewsListFragment> pages;

        public NewsFragmentAdapter(FragmentManager fm) {
            super(fm);
            mFragmentManager = fm;
        }


        @Override
        public Fragment getItem(int position) {
            if (pages != null) {
                return pages.get(position);
            }
            return null;
        }


        @Override
        public int getCount() {
            return pages != null ? pages.size() : 0;
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            return titles.get(position);
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return POSITION_NONE;
        }

        public void refreshViewPager(List<NewsListFragment> listFragments) {
            if (pages != null && pages.size() > 0) {
                FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                for (NewsListFragment fragment : pages) {
                    fragmentTransaction.remove(fragment);
                }
                fragmentTransaction.commit();
                mFragmentManager.executePendingTransactions();
            }
            pages = listFragments;
            notifyDataSetChanged();

            mViewPager.setCurrentItem(0);
        }
    }

    @Override
    public String getToolbarTitle() {
        return null;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(NewsTypeCrudEvent event) {
        mIsfresh = true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        KLog.v("MYTAG", "onHiddenChanged start..." + hidden);
        if (mIsfresh && !hidden) {
            KLog.v("MYTAG", "ViewPager refresh start...");
            mIsfresh = false;
            mViewPager.setCurrentItem(mListFragments.size() - 1);
            initData();
            mNewsFragmentAdapter.refreshViewPager(mListFragments);
        }
    }
}
