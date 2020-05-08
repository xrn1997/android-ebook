package com.ebook.book.fragment;

import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;

import com.ebook.book.BR;
import com.ebook.book.R;
import com.ebook.book.adapter.BookListAdapter;
import com.ebook.book.databinding.FragmentBookMainBinding;
import com.ebook.basebook.mvp.presenter.impl.BookDetailPresenterImpl;
import com.ebook.basebook.mvp.presenter.impl.ReadBookPresenterImpl;
import com.ebook.basebook.mvp.view.impl.BookDetailActivity;
import com.ebook.basebook.mvp.view.impl.ImportBookActivity;
import com.ebook.basebook.mvp.view.impl.ReadBookActivity;
import com.ebook.book.mvvm.factory.BookViewModelFactory;
import com.ebook.book.mvvm.viewmodel.BookListViewModel;
import com.ebook.book.service.DownloadService;
import com.ebook.common.adapter.BaseBindAdapter;
import com.ebook.common.event.RxBusTag;
import com.ebook.basebook.base.manager.BitIntentDataManager;
import com.ebook.common.mvvm.BaseMvvmRefreshFragment;
import com.ebook.common.util.ObservableListUtil;
import com.ebook.basebook.view.popupwindow.DownloadListPop;
import com.ebook.db.entity.BookShelf;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.refresh.lib.DaisyRefreshLayout;

import androidx.lifecycle.ViewModelProvider;

public class MainBookFragment extends BaseMvvmRefreshFragment<FragmentBookMainBinding, BookListViewModel> {
    private BookListAdapter mBookListAdatper;
    private ImageButton ibSettings;
    private ImageButton ibAdd;
    private ImageButton ibDownload;
    private DownloadListPop downloadListPop;

    public static MainBookFragment newInstance() {
        return new MainBookFragment();
    }

    @Override
    public Class<BookListViewModel> onBindViewModel() {
        return BookListViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return BookViewModelFactory.getInstance(mActivity.getApplication());
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
        return R.layout.fragment_book_main;
    }

    @Override
    public void initView(View view) {
        downloadListPop = new DownloadListPop(mActivity);
        ibSettings = view.findViewById(R.id.ib_settings);
        ibAdd = (ImageButton) view.findViewById(R.id.ib_add);
        ibDownload = (ImageButton)view. findViewById(R.id.ib_download);
        mBookListAdatper = new BookListAdapter(mActivity,mViewModel.getList());
        mViewModel.getList().addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mBookListAdatper));
        mBinding.recview.setAdapter(mBookListAdatper);
    }

    @Override
    public void initData() {
        mViewModel.refreshData();

    }

    @Override
    public void initListener() {
        super.initListener();
        ibSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
        ibDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadListPop.showAsDropDown(ibDownload);
            }
        });

        ibAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //点击更多
                startActivity(new Intent(mActivity, ImportBookActivity.class));
            }
        });
        mBookListAdatper.setItemClickListener(new BaseBindAdapter.OnItemClickListener<BookShelf>() {

            @Override
            public void onItemClick(BookShelf bookShelf, int position) {
                Intent intent = new Intent(mActivity, ReadBookActivity.class);
                intent.putExtra("from", ReadBookPresenterImpl.OPEN_FROM_APP);
                String key = String.valueOf(System.currentTimeMillis());
                intent.putExtra("data_key", key);
                try {
                    BitIntentDataManager.getInstance().putData(key, bookShelf.clone());
                } catch (CloneNotSupportedException e) {
                    BitIntentDataManager.getInstance().putData(key, bookShelf);
                    e.printStackTrace();
                }
                startActivity(intent);
            }
        });
        mBookListAdatper.setOnItemLongClickListener(new BaseBindAdapter.OnItemLongClickListener<BookShelf>() {
            @Override
            public boolean onItemLongClick(BookShelf bookShelf, int postion) {
                Intent intent = new Intent(mActivity, BookDetailActivity.class);
                intent.putExtra("from", BookDetailPresenterImpl.FROM_BOOKSHELF);
                String key = String.valueOf(System.currentTimeMillis());
                intent.putExtra("data_key", key);
                BitIntentDataManager.getInstance().putData(key, bookShelf);
                startActivity(intent);
                return true;
            }
        });

    }

    @Override
    public String getToolbarTitle() {
        return "我的书架";
    }

    @Override
    public DaisyRefreshLayout getRefreshLayout() {
        return mBinding.refviewBookList;
    }



    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
            @Tag(RxBusTag.HAD_ADD_BOOK),
            @Tag(RxBusTag.HAD_REMOVE_BOOK),
            @Tag(RxBusTag.UPDATE_BOOK_PROGRESS)
    }
    )
    public void hadAddOrRemoveBook(BookShelf bookShelf) {
       mViewModel.refreshData();
        //autoLoadData();
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.START_DOWNLOAD_SERVICE)
            }
    )
    public void startDownloadService(Object o) {
        Log.e(TAG, "startDownloadService: 开启下载服务" );
            mActivity.startService(new Intent(mActivity, DownloadService.class));
    }

}
