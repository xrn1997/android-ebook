package com.ebook.book.service;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.book.R;
import com.ebook.book.fragment.MainBookFragment;
import com.ebook.common.event.RxBusTag;
import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookContentDao;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.BookShelfDao;
import com.ebook.db.entity.ChapterList;
import com.ebook.db.entity.DownloadChapter;
import com.ebook.db.entity.DownloadChapterDao;
import com.ebook.db.entity.DownloadChapterList;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;

import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

@SuppressWarnings("unused")
public class DownloadService extends Service {
    public static final String TAG = "DownloadService";
    public static final int reTryTimes = 1;
    private NotificationManager notifyManager;
    private Boolean isStartDownload = false;
    private Boolean isInit = false;
    private Boolean isDownloading = false;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RxBus.get().unregister(this);
        isInit = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isInit) {
            isInit = true;
            notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel notificationChannel =
                    new NotificationChannel("40", "App Service", NotificationManager.IMPORTANCE_HIGH);
            notifyManager.createNotificationChannel(notificationChannel);
            RxBus.get().register(this);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addNewTask(final List<DownloadChapter> newData) {
        isStartDownload = true;
        Observable.create((ObservableOnSubscribe<Boolean>) e -> {
                    GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().insertOrReplaceInTx(newData);
                    e.onNext(true);
                    e.onComplete();
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(Boolean value) {
                        if (!isDownloading) {
                            toDownload();
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "onError: ", e);
                    }
                });
    }

    private void toDownload() {
        isDownloading = true;
        if (isStartDownload) {
            Observable.create((ObservableOnSubscribe<DownloadChapter>) e -> {
                        List<BookShelf> bookShelfList = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().orderDesc(BookShelfDao.Properties.FinalDate).list();
                        if (bookShelfList != null && !bookShelfList.isEmpty()) {
                            for (BookShelf bookItem : bookShelfList) {
                                if (!bookItem.getTag().equals(BookShelf.LOCAL_TAG)) {
                                    List<DownloadChapter> downloadChapterList = GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().queryBuilder().where(DownloadChapterDao.Properties.NoteUrl.eq(bookItem.getNoteUrl())).orderAsc(DownloadChapterDao.Properties.DurChapterIndex).limit(1).list();
                                    if (downloadChapterList != null && !downloadChapterList.isEmpty()) {
                                        e.onNext(downloadChapterList.get(0));
                                        e.onComplete();
                                        return;
                                    }
                                }
                            }
                        }
                        GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                        e.onNext(new DownloadChapter());
                        e.onComplete();
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new SimpleObserver<>() {
                        @Override
                        public void onNext(DownloadChapter value) {
                            if (value.getNoteUrl() != null && !value.getNoteUrl().isEmpty()) {
                                downloading(value, 0);
                            } else {
                                Observable.create(e -> {
                                            GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                                            e.onNext(new Object());
                                            e.onComplete();
                                        })
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(new SimpleObserver<>() {
                                            @Override
                                            public void onNext(Object value) {
                                                isDownloading = false;
                                                finishDownload();
                                            }

                                            @Override
                                            public void onError(Throwable e) {
                                                Log.e(TAG, "onError: ", e);
                                                isDownloading = false;
                                            }
                                        });
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            Log.e(TAG, "onError: ", e);
                            isDownloading = false;
                        }
                    });
        } else {
            isPause();
        }
    }

    private void downloading(final DownloadChapter data, final int durTime) {
        if (durTime < reTryTimes && isStartDownload) {
            isProgress(data);
            Observable.create((ObservableOnSubscribe<BookContent>) e -> {
                        List<BookContent> result = GreenDaoManager.getInstance().getmDaoSession().getBookContentDao().queryBuilder().where(BookContentDao.Properties.DurChapterUrl.eq(data.getDurChapterUrl())).list();
                        if (result != null && !result.isEmpty()) {
                            e.onNext(result.get(0));
                        } else {
                            e.onNext(new BookContent());
                        }
                        e.onComplete();
                    }).flatMap((Function<BookContent, ObservableSource<BookContent>>) bookContent -> {
                        if (bookContent.getDurChapterUrl() == null || bookContent.getDurChapterUrl().isEmpty()) {
                            return WebBookModelImpl.getInstance().getBookContent(data.getDurChapterUrl(), data.getDurChapterIndex()).map(bookContent1 -> {
                                GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().delete(data);
                                if (bookContent1.getRight()) {
                                    GreenDaoManager.getInstance().getmDaoSession().getBookContentDao().insertOrReplace(bookContent1);
                                    GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().update(new ChapterList(data.getNoteUrl(), data.getDurChapterIndex(), data.getDurChapterUrl(), data.getDurChapterName(), data.getTag(), true));
                                }
                                return bookContent1;
                            });
                        } else {
                            return Observable.create((ObservableOnSubscribe<BookContent>) e -> {
                                GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().delete(data);
                                e.onNext(bookContent);
                                e.onComplete();
                            });
                        }
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribeOn(Schedulers.io())
                    .subscribe(new SimpleObserver<>() {
                        @Override
                        public void onNext(BookContent value) {
                            if (isStartDownload) {
                                new Handler().postDelayed(() -> {
                                    if (isStartDownload) {
                                        toDownload();
                                    } else {
                                        isPause();
                                    }
                                }, 800);
                            } else {
                                isPause();
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            Log.e(TAG, "onError: ", e);
                            int time = durTime + 1;
                            downloading(data, time);
                        }
                    });
        } else {
            if (isStartDownload) {
                Observable.create((ObservableOnSubscribe<Boolean>) e -> {
                            GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().delete(data);
                            e.onNext(true);
                            e.onComplete();
                        })
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeOn(Schedulers.io())
                        .subscribe(new SimpleObserver<>() {
                            @Override
                            public void onNext(Boolean value) {
                                if (isStartDownload) {
                                    new Handler().postDelayed(() -> {
                                        if (isStartDownload) {
                                            toDownload();
                                        } else {
                                            isPause();
                                        }
                                    }, 800);
                                } else {
                                    isPause();
                                }
                            }

                            @Override
                            public void onError(Throwable e) {
                                Log.e(TAG, "onError: ", e);
                                if (!isStartDownload)
                                    isPause();
                            }
                        });
            } else
                isPause();
        }
    }

    public void startDownload() {
        isStartDownload = true;
        toDownload();
    }

    public void pauseDownload() {
        isStartDownload = false;
        notifyManager.cancelAll();
    }

    public void cancelDownload() {
        Observable.create(e -> {
                    GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                    e.onNext(new Object());
                    e.onComplete();
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(Object value) {
                        isStartDownload = false;
                        notifyManager.cancelAll();
                        stopService(new Intent(getApplication(), DownloadService.class));
                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private void isPause() {
        isDownloading = false;
        Observable.create((ObservableOnSubscribe<DownloadChapter>) e -> {
                    List<BookShelf> bookShelfList = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().orderDesc(BookShelfDao.Properties.FinalDate).list();
                    if (bookShelfList != null && !bookShelfList.isEmpty()) {
                        for (BookShelf bookItem : bookShelfList) {
                            if (!bookItem.getTag().equals(BookShelf.LOCAL_TAG)) {
                                List<DownloadChapter> downloadChapterList = GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().queryBuilder().where(DownloadChapterDao.Properties.NoteUrl.eq(bookItem.getNoteUrl())).orderAsc(DownloadChapterDao.Properties.DurChapterIndex).limit(1).list();
                                if (downloadChapterList != null && !downloadChapterList.isEmpty()) {
                                    e.onNext(downloadChapterList.get(0));
                                    e.onComplete();
                                    return;
                                }
                            }
                        }
                    }
                    GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                    e.onNext(new DownloadChapter());
                    e.onComplete();
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(DownloadChapter value) {
                        if (value.getNoteUrl() != null && !value.getNoteUrl().isEmpty()) {
                            RxBus.get().post(RxBusTag.PAUSE_DOWNLOAD_LISTENER, new Object());
                        } else {
                            RxBus.get().post(RxBusTag.FINISH_DOWNLOAD_LISTENER, new Object());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "onError: ", e);
                    }
                });
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private void isProgress(DownloadChapter downloadChapter) {
        RxBus.get().post(RxBusTag.PROGRESS_DOWNLOAD_LISTENER, downloadChapter);
        Intent mainIntent = new Intent(this, MainBookFragment.class);
        PendingIntent mainPendingIntent;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);
        } else {
            mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        //创建 Notification.Builder 对象
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "40")
                .setSmallIcon(R.mipmap.ic_launcher)
                //点击通知后自动清除
                .setAutoCancel(true)
                .setContentTitle("正在下载：" + downloadChapter.getBookName())
                .setContentText(downloadChapter.getDurChapterName() == null ? "  " : downloadChapter.getDurChapterName())
                .setContentIntent(mainPendingIntent);
        //发送通知
        int notifyId = 19931118;
        notifyManager.notify(notifyId, builder.build());
    }

    private void finishDownload() {
        RxBus.get().post(RxBusTag.FINISH_DOWNLOAD_LISTENER, new Object());
        notifyManager.cancelAll();
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(getApplicationContext(), "全部离线章节下载完成", Toast.LENGTH_SHORT).show();
            stopService(new Intent(getApplication(), DownloadService.class));
        });
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.PAUSE_DOWNLOAD)
            }
    )
    public void pauseTask(Object o) {
        pauseDownload();
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.START_DOWNLOAD)
            }
    )
    public void startTask(Object o) {
        startDownload();
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.CANCEL_DOWNLOAD)
            }
    )
    public void cancelTask(Object o) {
        cancelDownload();
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.ADD_DOWNLOAD_TASK)
            }
    )
    public void addTask(DownloadChapterList newData) {
        addNewTask(newData.getData());
    }
}