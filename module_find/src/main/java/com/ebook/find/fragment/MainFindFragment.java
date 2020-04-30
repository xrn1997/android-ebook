package com.ebook.find.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.common.mvvm.BaseMvvmRefreshFragment;
import com.ebook.find.BR;
import com.ebook.find.R;
import com.ebook.find.databinding.FragmentFindMainBinding;
import com.ebook.find.entity.BookType;
import com.ebook.find.adapter.BookTypeShowAdapter;
import com.ebook.find.mvp.view.impl.ChoiceBookActivity;
import com.ebook.find.mvp.view.impl.SearchActivity;
import com.ebook.find.mvp.view.libraryview.LibraryKindBookListView;
import com.ebook.find.mvp.view.libraryview.LibraryNewBooksView;
import com.ebook.find.mvvm.factory.FindViewModelFactory;
import com.ebook.find.mvvm.viewmodel.LibraryViewModel;
import com.refresh.lib.DaisyRefreshLayout;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;


public class MainFindFragment extends BaseMvvmRefreshFragment<FragmentFindMainBinding,LibraryViewModel> {
    private FrameLayout flSearch;
    private BookTypeShowAdapter mBookTypeShowAdapter;
    public static final String TAG = MainFindFragment.class.getSimpleName();

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
        flSearch = (FrameLayout) view.findViewById(R.id.fl_search);
        mBookTypeShowAdapter=new BookTypeShowAdapter(mActivity,mViewModel.getBookTypeList());
        mBinding.kindLl.setAdapter(mBookTypeShowAdapter);
    }

    @Override
    public void initListener() {
        super.initListener();
        mBookTypeShowAdapter.setItemClickListener(new BaseBindAdapter.OnItemClickListener<BookType>() {
            @Override
            public void onItemClick(BookType bookType, int position) {
                ChoiceBookActivity.startChoiceBookActivity(getActivity(), bookType.getBookType(),bookType.getUrl());
            }
        });
        flSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //点击搜索
                startActivity(new Intent(getActivity(), SearchActivity.class));
            }
        });
    }

    @Override
    public boolean enableToolbar() {
        return true;
    }

    @Override
    public void initData() {
    }

    @Override
    public String getToolbarTitle() {
        return "书城";
    }


}
