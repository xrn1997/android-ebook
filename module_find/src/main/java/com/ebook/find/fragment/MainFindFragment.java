package com.ebook.find.fragment;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ebook.common.mvvm.BaseMvvmRefreshFragment;
import com.ebook.common.util.ObservableListUtil;
import com.ebook.find.BR;
import com.ebook.find.R;
import com.ebook.find.adapter.BookTypeShowAdapter;
import com.ebook.find.adapter.LibraryBookListAdapter;
import com.ebook.find.databinding.FragmentFindMainBinding;
import com.ebook.find.mvp.view.impl.ChoiceBookActivity;
import com.ebook.find.mvp.view.impl.SearchActivity;
import com.ebook.find.mvvm.factory.FindViewModelFactory;
import com.ebook.find.mvvm.viewmodel.LibraryViewModel;
import com.refresh.lib.DaisyRefreshLayout;


public class MainFindFragment extends BaseMvvmRefreshFragment<FragmentFindMainBinding, LibraryViewModel> {
    public static final String TAG = MainFindFragment.class.getSimpleName();
    private FrameLayout flSearch;
    private BookTypeShowAdapter mBookTypeShowAdapter;
    private LibraryBookListAdapter mLibraryKindBookAdapter;

    public static MainFindFragment newInstance() {
        return new MainFindFragment();
    }

    @Override
    public DaisyRefreshLayout getRefreshLayout() {
        return mBinding.refviewLibrary;
    }

    @Override
    public Class<LibraryViewModel> onBindViewModel() {
        return LibraryViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return FindViewModelFactory.getInstance(mActivity.getApplication());
    }

    @Override
    public void initViewObservable() {

    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }


    @Override
    public int onBindLayout() {
        return R.layout.fragment_find_main;
    }

    @Override
    public void initView(View view) {
        flSearch = view.findViewById(R.id.fl_search);
        mBookTypeShowAdapter = new BookTypeShowAdapter(mActivity, mViewModel.getBookTypeList());
        mLibraryKindBookAdapter = new LibraryBookListAdapter(mActivity, mViewModel.getLibraryKindBookLists());
        mViewModel.getLibraryKindBookLists().addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mLibraryKindBookAdapter));
        MyRecycleviewManager myRecycleviewManager = new MyRecycleviewManager(mActivity);
        myRecycleviewManager.setScrollEnabled(false);
        myRecycleviewManager.setOrientation(RecyclerView.VERTICAL);
        mBinding.lkbvKindbooklist.setLayoutManager(myRecycleviewManager);
        mBinding.lkbvKindbooklist.setAdapter(mLibraryKindBookAdapter);
        mBinding.kindLl.setAdapter(mBookTypeShowAdapter);
    }

    ////////////////////////////////
    @Override
    public void initListener() {
        super.initListener();
        mBookTypeShowAdapter.setItemClickListener((bookType, position) -> ChoiceBookActivity.startChoiceBookActivity(getActivity(), bookType.getBookType(), bookType.getUrl()));
        flSearch.setOnClickListener(v -> {
            //点击搜索
            startActivity(new Intent(getActivity(), SearchActivity.class));
        });
    }

    @Override
    public void initData() {
        mViewModel.refreshData();
    }

    @Override
    public String getToolbarTitle() {
        return "书城";
    }

    //自定义的manager，用于禁用滚动条
    public static class MyRecycleviewManager extends LinearLayoutManager {

        private boolean isScrollEnabled = true;

        public MyRecycleviewManager(Context context) {
            super(context);
        }

        public void setScrollEnabled(boolean flag) {
            this.isScrollEnabled = flag;
        }

        @Override
        public boolean canScrollVertically() {
            return isScrollEnabled && super.canScrollVertically();
        }

    }


}
