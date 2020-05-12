package com.ebook.book.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.book.R;
import com.ebook.book.fragment.MainBookFragment;
import com.ebook.common.event.RxBusTag;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.db.GreenDaoManager;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;

import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookContentDao;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.BookShelfDao;
import com.ebook.db.entity.ChapterList;
import com.ebook.db.entity.DownloadChapter;
import com.ebook.db.entity.DownloadChapterDao;
import com.ebook.db.entity.DownloadChapterList;

import java.util.List;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;


public class DownloadService extends Service {
    private NotificationManager notifyManager;
    private int notifiId = 19931118;
    private Boolean isStartDownload = false;
    private Boolean isInit = false;

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel notificationChannel =
                        new NotificationChannel("40", "App Service", NotificationManager.IMPORTANCE_HIGH);
                notifyManager.createNotificationChannel(notificationChannel);
            }
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
        Observable.create(new ObservableOnSubscribe<Boolean>() {
            @Override
            public void subscribe(ObservableEmitter<Boolean> e) throws Exception {
                GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().insertOrReplaceInTx(newData);
                e.onNext(true);
                e.onComplete();
            }
        })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(new SimpleObserver<Boolean>() {
                    @Override
                    public void onNext(Boolean value) {
                        if (!isDownloading) {
                            toDownload();
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    private Boolean isDownloading = false;
    public static final int reTryTimes = 1;

    private void toDownload() {
        isDownloading = true;
        if (isStartDownload) {
            Observable.create(new ObservableOnSubscribe<DownloadChapter>() {
                @Override
                public void subscribe(ObservableEmitter<DownloadChapter> e) throws Exception {
                    List<BookShelf> bookShelfList = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().orderDesc(BookShelfDao.Properties.FinalDate).list();
                    if (bookShelfList != null && bookShelfList.size() > 0) {
                        for (BookShelf bookItem : bookShelfList) {
                            if (!bookItem.getTag().equals(BookShelf.LOCAL_TAG)) {
                                List<DownloadChapter> downloadChapterList = GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().queryBuilder().where(DownloadChapterDao.Properties.NoteUrl.eq(bookItem.getNoteUrl())).orderAsc(DownloadChapterDao.Properties.DurChapterIndex).limit(1).list();
                                if (downloadChapterList != null && downloadChapterList.size() > 0) {
                                    e.onNext(downloadChapterList.get(0));
                                    e.onComplete();
                                    return;
                                }
                            }
                        }
                        GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                        e.onNext(new DownloadChapter());
                    } else {
                        GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                        e.onNext(new DownloadChapter());
                    }
                    e.onComplete();
                }
            })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new SimpleObserver<DownloadChapter>() {
                        @Override
                        public void onNext(DownloadChapter value) {
                            if (value.getNoteUrl() != null && value.getNoteUrl().length() > 0) {
                                downloading(value, 0);
                            } else {
                                Observable.create(new ObservableOnSubscribe<Object>() {
                                    @Override
                                    public void subscribe(ObservableEmitter<Object> e) throws Exception {
                                        GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                                        e.onNext(new Object());
                                        e.onComplete();
                                    }
                                })
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(new SimpleObserver<Object>() {
                                            @Override
                                            public void onNext(Object value) {
                                                isDownloading = false;
                                                finishDownload();
                                            }

                                            @Override
                                            public void onError(Throwable e) {
                                                e.printStackTrace();
                                                isDownloading = false;
                                            }
                                        });
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
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
            Observable.create(new ObservableOnSubscribe<BookContent>() {
                @Override
                public void subscribe(ObservableEmitter<BookContent> e) throws Exception {
                    List<BookContent> result = GreenDaoManager.getInstance().getmDaoSession().getBookContentDao().queryBuilder().where(BookContentDao.Properties.DurChapterUrl.eq(data.getDurChapterUrl())).list();
                    if (result != null && result.size() > 0) {
                        e.onNext(result.get(0));
                    } else {
                        e.onNext(new BookContent());
                    }
                    e.onComplete();
                }
            }).flatMap(new Function<BookContent, ObservableSource<BookContent>>() {
                @Override
                public ObservableSource<BookContent> apply(final BookContent bookContent) throws Exception {
                    if (bookContent.getDurChapterUrl() == null || bookContent.getDurChapterUrl().length() <= 0) {
                        return WebBookModelImpl.getInstance().getBookContent(data.getDurChapterUrl(), data.getDurChapterIndex(), data.getTag()).map(new Function<BookContent, BookContent>() {
                            @Override
                            public BookContent apply(BookContent bookContent) throws Exception {
                                GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().delete(data);
                                if (bookContent.getRight()) {
                                    GreenDaoManager.getInstance().getmDaoSession().getBookContentDao().insertOrReplace(bookContent);
                                    GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().update(new ChapterList(data.getNoteUrl(), data.getDurChapterIndex(), data.getDurChapterUrl(), data.getDurChapterName(), data.getTag(), true));
                                }
                                return bookContent;
                            }
                        });
                    } else {
                        return Observable.create(new ObservableOnSubscribe<BookContent>() {
                            @Override
                            public void subscribe(ObservableEmitter<BookContent> e) throws Exception {
                                GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().delete(data);
                                e.onNext(bookContent);
                                e.onComplete();
                            }
                        });
                    }
                }
            })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribeOn(Schedulers.io())
                    .subscribe(new SimpleObserver<BookContent>() {
                        @Override
                        public void onNext(BookContent value) {
                            if (isStartDownload) {
                                new Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isStartDownload) {
                                            toDownload();
                                        } else {
                                            isPause();
                                        }
                                    }
                                }, 800);
                            } else {
                                isPause();
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
                            int time = durTime + 1;
                            downloading(data, time);
                        }
                    });
        } else {
            if (isStartDownload) {
                Observable.create(new ObservableOnSubscribe<Boolean>() {
                    @Override
                    public void subscribe(ObservableEmitter<Boolean> e) throws Exception {
                        GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().delete(data);
                        e.onNext(true);
                        e.onComplete();
                    }
                })
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeOn(Schedulers.io())
                        .subscribe(new SimpleObserver<Boolean>() {
                            @Override
                            public void onNext(Boolean value) {
                                if (isStartDownload) {
                                    new Handler().postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (isStartDownload) {
                                                toDownload();
                                            } else {
                                                isPause();
                                            }
                                        }
                                    }, 800);
                                } else {
                                    isPause();
                                }
                            }

                            @Override
                            public void onError(Throwable e) {
                                e.printStackTrace();
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
        Observable.create(new ObservableOnSubscribe<Object>() {
            @Override
            public void subscribe(ObservableEmitter<Object> e) throws Exception {
                GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                e.onNext(new Object());
                e.onComplete();
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Object>() {
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
        Observable.create(new ObservableOnSubscribe<DownloadChapter>() {
            @Override
            public void subscribe(ObservableEmitter<DownloadChapter> e) throws Exception {
                List<BookShelf> bookShelfList = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().orderDesc(BookShelfDao.Properties.FinalDate).list();
                if (bookShelfList != null && bookShelfList.size() > 0) {
                    for (BookShelf bookItem : bookShelfList) {
                        if (!bookItem.getTag().equals(BookShelf.LOCAL_TAG)) {
                            List<DownloadChapter> downloadChapterList = GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().queryBuilder().where(DownloadChapterDao.Properties.NoteUrl.eq(bookItem.getNoteUrl())).orderAsc(DownloadChapterDao.Properties.DurChapterIndex).limit(1).list();
                            if (downloadChapterList != null && downloadChapterList.size() > 0) {
                                e.onNext(downloadChapterList.get(0));
                                e.onComplete();
                                return;
                            }
                        }
                    }
                    GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                    e.onNext(new DownloadChapter());
                } else {
                    GreenDaoManager.getInstance().getmDaoSession().getDownloadChapterDao().deleteAll();
                    e.onNext(new DownloadChapter());
                }
                e.onComplete();
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<DownloadChapter>() {
                    @Override
                    public void onNext(DownloadChapter value) {
                        if (value.getNoteUrl() != null && value.getNoteUrl().length() > 0) {
                            RxBus.get().post(RxBusTag.PAUSE_DOWNLOAD_LISTENER, new Object());
                        } else {
                            RxBus.get().post(RxBusTag.FINISH_DOWNLOAD_LISTENER, new Object());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    private void isProgress(DownloadChapter downloadChapter) {
        RxBus.get().post(RxBusTag.PROGRESS_DOWNLOAD_LISTENER, downloadChapter);
        Intent mainIntent = new Intent(this, MainBookFragment.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        //创建 Notification.Builder 对象
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "40")
                .setSmallIcon(R.mipmap.ic_launcher)
                //点击通知后自动清除
                .setAutoCancel(true)
                .setContentTitle("正在下载：" + downloadChapter.getBookName())
                .setContentText(downloadChapter.getDurChapterName() == null ? "  " : downloadChapter.getDurChapterName())
                .setContentIntent(mainPendingIntent);
        //发送通知
        notifyManager.notify(notifiId, builder.build());
    }

    private void finishDownload() {
        RxBus.get().post(RxBusTag.FINISH_DOWNLOAD_LISTENER, new Object());
        notifyManager.cancelAll();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "全部离线章节下载完成", Toast.LENGTH_SHORT).show();
                stopService(new Intent(getApplication(), DownloadService.class));
            }
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