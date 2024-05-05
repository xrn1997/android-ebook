package com.ebook.find.fragment;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.ebook.find.BR;
import com.ebook.find.R;
import com.ebook.find.adapter.BookTypeShowAdapter;
import com.ebook.find.adapter.LibraryBookListAdapter;
import com.ebook.find.databinding.FragmentFindMainBinding;
import com.ebook.find.mvp.view.impl.ChoiceBookActivity;
import com.ebook.find.mvp.view.impl.SearchActivity;
import com.ebook.find.mvvm.factory.FindViewModelFactory;
import com.ebook.find.mvvm.viewmodel.LibraryViewModel;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.xrn1997.common.mvvm.view.BaseMvvmRefreshFragment;
import com.xrn1997.common.util.ObservableListUtil;


public class MainFindFragment extends BaseMvvmRefreshFragment<FragmentFindMainBinding, LibraryViewModel> {
    public static final String TAG = MainFindFragment.class.getSimpleName();

    public static MainFindFragment newInstance() {
        return new MainFindFragment();
    }

    @NonNull
    @Override
    public RefreshLayout getRefreshLayout() {
        return getBinding().refviewLibrary;
    }

    @NonNull
    @Override
    public Class<LibraryViewModel> onBindViewModel() {
        return LibraryViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return FindViewModelFactory.INSTANCE;
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
    public void initView() {
        BookTypeShowAdapter mBookTypeShowAdapter = new BookTypeShowAdapter(mActivity, mViewModel.getBookTypeList());
        LibraryBookListAdapter mLibraryKindBookAdapter = new LibraryBookListAdapter(mActivity, mViewModel.getLibraryKindBookLists());
        mViewModel.getLibraryKindBookLists().addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mLibraryKindBookAdapter));
        MyRecyclerviewManager myRecyclerviewManager = new MyRecyclerviewManager(mActivity);
        myRecyclerviewManager.setScrollEnabled(false);
        getBinding().lkbvKindbooklist.setLayoutManager(myRecyclerviewManager);
        getBinding().lkbvKindbooklist.setAdapter(mLibraryKindBookAdapter);
        getBinding().kindLl.setAdapter(mBookTypeShowAdapter);
        mBookTypeShowAdapter.setOnItemClickListener((bookType, position) -> ChoiceBookActivity.startChoiceBookActivity(getActivity(), bookType.getBookType(), bookType.getUrl()));
        getBinding().flSearch.setOnClickListener(v -> {
            //点击搜索
            startActivity(new Intent(getActivity(), SearchActivity.class));
        });
    }

    @Override
    public void initData() {
        mViewModel.refreshData();
    }

    @NonNull
    @Override
    public String getToolBarTitle() {
        return "书城";
    }

    @Override
    public boolean enableToolbar() {
        return true;
    }

    //自定义的manager，用于禁用滚动条
    public static class MyRecyclerviewManager extends LinearLayoutManager {

        private boolean isScrollEnabled = true;

        public MyRecyclerviewManager(Context context) {
            super(context);
            this.setOrientation(VERTICAL);
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
