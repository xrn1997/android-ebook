package com.ebook.common.view.popupwindow;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.common.R;
import com.ebook.common.event.RxBusTag;
import com.ebook.db.ObjectBoxManager;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.BookShelf_;
import com.ebook.db.entity.DownloadChapter;
import com.ebook.db.entity.DownloadChapter_;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.xrn1997.common.event.SimpleObserver;

import java.util.List;
import java.util.Objects;

import io.objectbox.query.QueryBuilder;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableOnSubscribe;
import io.reactivex.rxjava3.schedulers.Schedulers;


public class DownloadListPop extends PopupWindow {
    public static final String TAG = "DownloadListPop";
    private final Context mContext;
    private final View view;

    private TextView tvNone;
    private LinearLayout llDownload;

    private ImageView ivCover;
    private TextView tvName;
    private TextView tvChapterName;
    private TextView tvCancel;
    private TextView tvDownload;

    @SuppressLint("InflateParams")
    public DownloadListPop(Context context) {
        super(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mContext = context;
        view = LayoutInflater.from(mContext).inflate(R.layout.view_pop_downloadlist, null);
        this.setContentView(view);
        bindView();
        bindEvent();
        initWait();
        setBackgroundDrawable(ContextCompat.getDrawable(mContext, R.drawable.shape_pop_checkaddshelf_bg));
        setFocusable(true);
        setTouchable(true);
        setAnimationStyle(R.style.anim_pop_checkaddshelf);
        RxBus.get().register(DownloadListPop.this);
    }

    private void bindEvent() {
        tvCancel.setOnClickListener(v -> {
            RxBus.get().post(RxBusTag.CANCEL_DOWNLOAD, new Object());
            tvNone.setVisibility(View.VISIBLE);
        });
        tvDownload.setOnClickListener(v -> {
            if (tvDownload.getText().equals("开始下载")) {
                //todo 下一半暂停，然后重启应用，没有开启download服务，故这里无效。
                RxBus.get().post(RxBusTag.START_DOWNLOAD, new Object());
            } else {
                RxBus.get().post(RxBusTag.PAUSE_DOWNLOAD, new Object());
            }
        });
    }

    private void bindView() {
        tvNone = view.findViewById(R.id.tv_none);
        llDownload = view.findViewById(R.id.ll_download);
        ivCover = view.findViewById(R.id.iv_cover);
        tvName = view.findViewById(R.id.tv_name);
        tvChapterName = view.findViewById(R.id.tv_chapter_name);
        tvCancel = view.findViewById(R.id.tv_cancel);
        tvDownload = view.findViewById(R.id.tv_download);
    }

    private void initWait() {
        Observable.create((ObservableOnSubscribe<DownloadChapter>) e -> {
                    try (var query = ObjectBoxManager.INSTANCE.getBookShelfBox().query().orderDesc(BookShelf_.finalDate).build()) {
                        List<BookShelf> bookShelfList = query.find();
                        if (!bookShelfList.isEmpty()) {
                            for (BookShelf bookItem : bookShelfList) {
                                if (!Objects.equals(bookItem.getTag(), BookShelf.LOCAL_TAG)) {
                                    try (var downloadChapterQuery = ObjectBoxManager.INSTANCE.getDownloadChapterBox().query(DownloadChapter_.noteUrl.equal(bookItem.noteUrl)).order(DownloadChapter_.durChapterIndex, QueryBuilder.DESCENDING).build()) {
                                        List<DownloadChapter> downloadChapterList = downloadChapterQuery.find(0, 1);
                                        if (!downloadChapterList.isEmpty()) {
                                            //下载数据库表不为空即意味着当前存在下载中断、暂停。
                                            e.onNext(downloadChapterList.get(0));
                                            e.onComplete();
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                        ObjectBoxManager.INSTANCE.getDownloadChapterBox().removeAll();
                        e.onNext(new DownloadChapter());
                        e.onComplete();
                    } catch (Exception ex) {
                        e.onError(ex);
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(DownloadChapter value) {
                        if (!value.noteUrl.isEmpty()) {
                            llDownload.setVisibility(View.GONE);
                            tvNone.setVisibility(View.GONE);
                            tvDownload.setText("开始下载");
                        } else {
                            tvNone.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "onError: ", e);
                        tvNone.setVisibility(View.VISIBLE);
                    }
                });
    }

    public void onDestroy() {
        RxBus.get().unregister(DownloadListPop.this);
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.PAUSE_DOWNLOAD_LISTENER)
            }
    )
    public void pauseTask(Object o) {
        tvNone.setVisibility(View.GONE);
        llDownload.setVisibility(View.GONE);
        tvDownload.setText("开始下载");
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.FINISH_DOWNLOAD_LISTENER)
            }
    )
    public void finishTask(Object o) {
        tvNone.setVisibility(View.VISIBLE);
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.PROGRESS_DOWNLOAD_LISTENER)
            }
    )
    public void progressTask(DownloadChapter downloadChapter) {
        tvNone.setVisibility(View.GONE);
        llDownload.setVisibility(View.VISIBLE);
        tvDownload.setText("暂停下载");
        Glide.with(mContext)
                .load(downloadChapter.coverUrl)
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .centerCrop()
                .placeholder(R.drawable.img_cover_default)
                .into(ivCover);
        tvName.setText(downloadChapter.bookName);
        tvChapterName.setText(downloadChapter.durChapterName);
    }

}
