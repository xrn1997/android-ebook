package com.ebook.book.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.ebook.basebook.base.manager.BitIntentDataManager;
import com.ebook.basebook.mvp.presenter.impl.BookDetailPresenterImpl;
import com.ebook.basebook.mvp.presenter.impl.ReadBookPresenterImpl;
import com.ebook.basebook.mvp.view.impl.BookDetailActivity;
import com.ebook.basebook.mvp.view.impl.ImportBookActivity;
import com.ebook.basebook.mvp.view.impl.ReadBookActivity;
import com.ebook.basebook.view.popupwindow.DownloadListPop;
import com.ebook.book.BR;
import com.ebook.book.R;
import com.ebook.book.adapter.BookListAdapter;
import com.ebook.book.databinding.FragmentBookMainBinding;
import com.ebook.book.mvvm.factory.BookViewModelFactory;
import com.ebook.book.mvvm.viewmodel.BookListViewModel;
import com.ebook.book.service.DownloadService;
import com.ebook.common.event.RxBusTag;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.DownloadChapterList;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.xrn1997.common.mvvm.view.BaseMvvmRefreshFragment;
import com.xrn1997.common.util.ObservableListUtil;

import java.util.Timer;
import java.util.TimerTask;

@SuppressWarnings("unused")
public class MainBookFragment extends BaseMvvmRefreshFragment<FragmentBookMainBinding, BookListViewModel> {
    public static final String TAG = "MainBookFragment";
    private ImageButton ibDownload;
    private DownloadListPop downloadListPop;

    public static MainBookFragment newInstance() {
        return new MainBookFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RxBus.get().register(this);
    }

    @NonNull
    @Override
    public Class<BookListViewModel> onBindViewModel() {
        return BookListViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return BookViewModelFactory.INSTANCE;
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
    public void initView() {
        downloadListPop = new DownloadListPop(mActivity);
        ImageButton ibAdd = getBinding().ibAdd;
        ibDownload = getBinding().ibDownload;
        BookListAdapter mBookListAdapter = new BookListAdapter(mActivity, mViewModel.mList);
        mViewModel.mList.addOnListChangedCallback(ObservableListUtil.getListChangedCallback(mBookListAdapter));
        getBinding().recview.setAdapter(mBookListAdapter);
        ibDownload.setOnClickListener(v -> downloadListPop.showAsDropDown(ibDownload));

        ibAdd.setOnClickListener(v -> {
            //点击更多
            startActivity(new Intent(mActivity, ImportBookActivity.class));
        });
        mBookListAdapter.setOnItemClickListener((bookShelf, position) -> {
            Intent intent = new Intent(mActivity, ReadBookActivity.class);
            intent.putExtra("from", ReadBookPresenterImpl.OPEN_FROM_APP);
            String key = String.valueOf(System.currentTimeMillis());
            intent.putExtra("data_key", key);
            try {
                BitIntentDataManager.getInstance().putData(key, bookShelf.clone());
            } catch (CloneNotSupportedException e) {
                BitIntentDataManager.getInstance().putData(key, bookShelf);
                Log.e(TAG, "initView: ", e);
            }
            startActivity(intent);
        });
        mBookListAdapter.setOnItemLongClickListener((bookShelf, position) -> {
            Intent intent = new Intent(mActivity, BookDetailActivity.class);
            intent.putExtra("from", BookDetailPresenterImpl.FROM_BOOKSHELF);
            String key = String.valueOf(System.currentTimeMillis());
            intent.putExtra("data_key", key);
            BitIntentDataManager.getInstance().putData(key, bookShelf);
            startActivity(intent);
            return true;
        });
    }

    @Override
    public void initData() {
        mViewModel.refreshData();

    }

    @NonNull
    @Override
    public RefreshLayout getRefreshLayout() {
        return getBinding().refviewBookList;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RxBus.get().unregister(this);
        downloadListPop.onDestroy();
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.HAD_ADD_BOOK),
                    @Tag(RxBusTag.HAD_REMOVE_BOOK),
                    @Tag(RxBusTag.UPDATE_BOOK_PROGRESS)
            }
    )
    public void hadAddOrRemoveBook(BookShelf bookShelf) {
        Log.e(TAG, "hadAddOrRemoveBook: " + bookShelf.getBookInfo().getName());
        mViewModel.refreshData();
        //autoLoadData();
    }

    @Subscribe(thread = EventThread.NEW_THREAD,
            tags = {
                    @Tag(RxBusTag.START_DOWNLOAD_SERVICE)
            }
    )
    public void startDownloadService(DownloadChapterList result) {
        Log.e(TAG, "startDownloadService: 开启下载服务");
        mActivity.startService(new Intent(mActivity, DownloadService.class));
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                // 逻辑处理
                RxBus.get().post(RxBusTag.ADD_DOWNLOAD_TASK, result);
            }
        };
        Timer timer = new Timer();
        timer.schedule(task, 500); // 延迟0.5秒，执行一次task
    }

}
