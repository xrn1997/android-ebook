package com.ebook.basebook.mvp.presenter.impl;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.appcompat.app.AlertDialog;

import com.ebook.basebook.base.activity.BaseActivity;
import com.ebook.basebook.base.impl.BasePresenterImpl;
import com.ebook.basebook.base.manager.BitIntentDataManager;
import com.ebook.basebook.mvp.model.impl.ImportBookModelImpl;
import com.ebook.basebook.mvp.model.impl.WebBookModelImpl;
import com.ebook.basebook.mvp.presenter.IBookReadPresenter;
import com.ebook.basebook.mvp.view.IBookReadView;
import com.ebook.basebook.observer.SimpleObserver;
import com.ebook.basebook.view.BookContentView;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.util.ToastUtil;
import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookContent;
import com.ebook.db.entity.BookContentDao;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.BookShelfDao;
import com.ebook.db.entity.LocBookShelf;
import com.ebook.db.entity.ReadBookContent;
import com.ebook.db.event.DBCode;
import com.hwangjr.rxbus.RxBus;
import com.trello.rxlifecycle3.android.ActivityEvent;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class ReadBookPresenterImpl extends BasePresenterImpl<IBookReadView> implements IBookReadPresenter {

    public final static int OPEN_FROM_OTHER = 0;
    public final static int OPEN_FROM_APP = 1;

    private Boolean isAdd = false; //判断是否已经添加进书架
    private int open_from;
    private BookShelf bookShelf;

    private int pageLineCount = 5;   //假设5行一页

    public ReadBookPresenterImpl() {

    }

    /**
     * 所有需要的权限
     */
    public static List<String> allNeedPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        return permissions;
    }

    @Override
    public void initData(Activity activity) {
        Intent intent = activity.getIntent();
        open_from = intent.getIntExtra("from", OPEN_FROM_OTHER);
        if (open_from == OPEN_FROM_APP) {
            String key = intent.getStringExtra("data_key");
            bookShelf = (BookShelf) BitIntentDataManager.getInstance().getData(key);
            if (!bookShelf.getTag().equals(BookShelf.LOCAL_TAG)) {
                mView.showDownloadMenu();
            }
            BitIntentDataManager.getInstance().cleanData(key);
            checkInShelf();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !Environment.isExternalStorageManager()) {
                AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                        .setMessage("在Android11及以上的版本中，本程序还需要您同意允许访问所有文件权限，不然无法打开和扫描本地文件")
                        .setPositiveButton("确定", (dialog, which) -> {
                            mView.getRequestPermission().launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        })
                        .setNegativeButton("取消", ((dialog, which) -> {
                            activity.onBackPressed();
                        }));
                AlertDialog dialog = builder.create();
                //点击dialog之外的空白处，dialog不能消失
                dialog.setCanceledOnTouchOutside(false);
                dialog.show();
            }
        }
    }

    @Override
    public void openBookFromOther(Activity activity) {
        //APP外部打开
        Uri uri = activity.getIntent().getData();
        mView.showLoadBook();
        getRealFilePath(activity, uri)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(new SimpleObserver<String>() {
                    @Override
                    public void onNext(@NotNull String value) {
                        ImportBookModelImpl.getInstance().importBook(new File(value))
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribeOn(Schedulers.io())
                                .subscribe(new SimpleObserver<LocBookShelf>() {
                                    @Override
                                    public void onNext(@NotNull LocBookShelf value) {
                                        if (value.getNew())
                                            RxBus.get().post(RxBusTag.HAD_ADD_BOOK, value);
                                        bookShelf = value.getBookShelf();
                                        mView.dimissLoadBook();
                                        checkInShelf();
                                    }

                                    @Override
                                    public void onError(Throwable e) {
                                        e.printStackTrace();
                                        mView.dimissLoadBook();
                                        mView.loadLocationBookError();
                                        ToastUtil.showToast("文本打开失败！");
                                    }
                                });
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        mView.dimissLoadBook();
                        mView.loadLocationBookError();
                        ToastUtil.showToast("文本打开失败！");
                    }
                });
    }

    @Override
    public void detachView() {
    }

    @Override
    public int getOpen_from() {
        return open_from;
    }

    @Override
    public BookShelf getBookShelf() {
        return bookShelf;
    }

    @Override
    public void initContent() {
        mView.initContentSuccess(bookShelf.getDurChapter(), bookShelf.getBookInfo().getChapterlist().size(), bookShelf.getDurChapterPage());
    }

    @Override
    public void loadContent(final BookContentView bookContentView, final long bookTag, final int chapterIndex, int pageIndex) {
        if (null != bookShelf && bookShelf.getBookInfo().getChapterlist().size() > 0) {
            if (null != bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent() && null != bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getDurCapterContent()) {
                if (bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getLineSize() == mView.getPaint().getTextSize() && bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getLineContent().size() > 0) {
                    //已有数据
                    int tempCount = (int) Math.ceil(bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getLineContent().size() * 1.0 / pageLineCount) - 1;

                    if (pageIndex == DBCode.BookContentView.DURPAGEINDEXBEGIN) {
                        pageIndex = 0;
                    } else if (pageIndex == DBCode.BookContentView.DURPAGEINDEXEND) {
                        pageIndex = tempCount;
                    } else {
                        if (pageIndex >= tempCount) {
                            pageIndex = tempCount;
                        }
                    }

                    int start = pageIndex * pageLineCount;
                    int end = pageIndex == tempCount ? bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getLineContent().size() : start + pageLineCount;
                    if (bookContentView != null && bookTag == bookContentView.getqTag()) {
                        bookContentView.updateData(bookTag, bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getDurChapterName()
                                , bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getLineContent().subList(start, end)
                                , chapterIndex
                                , bookShelf.getBookInfo().getChapterlist().size()
                                , pageIndex
                                , tempCount + 1);
                    }
                } else {
                    //有元数据  重新分行
                    bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().setLineSize(mView.getPaint().getTextSize());
                    final int finalPageIndex = pageIndex;
                    SeparateParagraphtoLines(bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getDurCapterContent())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribeOn(Schedulers.io())
                            .compose(((BaseActivity) mView.getContext()).<List<String>>bindUntilEvent(ActivityEvent.DESTROY))
                            .subscribe(new SimpleObserver<List<String>>() {
                                @Override
                                public void onNext(List<String> value) {
                                    bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getLineContent().clear();
                                    bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getBookContent().getLineContent().addAll(value);
                                    loadContent(bookContentView, bookTag, chapterIndex, finalPageIndex);
                                }

                                @Override
                                public void onError(Throwable e) {
                                    if (bookContentView != null && bookTag == bookContentView.getqTag())
                                        bookContentView.loadError();
                                }
                            });
                }
            } else {
                final int finalPageIndex1 = pageIndex;
                Observable.create((ObservableOnSubscribe<ReadBookContent>) e -> {
                            List<BookContent> tempList = GreenDaoManager.getInstance().getmDaoSession().getBookContentDao().queryBuilder().where(BookContentDao.Properties.DurChapterUrl.eq(bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getDurChapterUrl())).build().list();
                            e.onNext(new ReadBookContent(tempList == null ? new ArrayList<>() : tempList, finalPageIndex1));
                            e.onComplete();
                        }).observeOn(AndroidSchedulers.mainThread())
                        .subscribeOn(Schedulers.io())
                        .compose(((BaseActivity) mView.getContext()).<ReadBookContent>bindUntilEvent(ActivityEvent.DESTROY))
                        .subscribe(new SimpleObserver<>() {
                            @Override
                            public void onNext(ReadBookContent tempList) {
                                if (tempList.getBookContentList() != null && tempList.getBookContentList().size() > 0 && tempList.getBookContentList().get(0).getDurCapterContent() != null) {
                                    bookShelf.getBookInfo().getChapterlist().get(chapterIndex).setBookContent(tempList.getBookContentList().get(0));
                                    loadContent(bookContentView, bookTag, chapterIndex, tempList.getPageIndex());
                                } else {
                                    final int finalPageIndex1 = tempList.getPageIndex();
                                    WebBookModelImpl.getInstance().getBookContent(bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getDurChapterUrl(), chapterIndex).map(bookContent -> {
                                                if (bookContent.getRight()) {
                                                    GreenDaoManager.getInstance().getmDaoSession().getBookContentDao().insertOrReplace(bookContent);
                                                    bookShelf.getBookInfo().getChapterlist().get(chapterIndex).setHasCache(true);
                                                    GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().update(bookShelf.getBookInfo().getChapterlist().get(chapterIndex));
                                                }
                                                return bookContent;
                                            })
                                            .observeOn(AndroidSchedulers.mainThread())
                                            .subscribeOn(Schedulers.io())
                                            .compose(((BaseActivity) mView.getContext()).<BookContent>bindUntilEvent(ActivityEvent.DESTROY))
                                            .subscribe(new SimpleObserver<BookContent>() {
                                                @Override
                                                public void onNext(BookContent value) {
                                                    if (value.getDurChapterUrl() != null && value.getDurChapterUrl().length() > 0) {
                                                        bookShelf.getBookInfo().getChapterlist().get(chapterIndex).setBookContent(value);
                                                        if (bookTag == bookContentView.getqTag())
                                                            loadContent(bookContentView, bookTag, chapterIndex, finalPageIndex1);
                                                    } else {
                                                        if (bookContentView != null && bookTag == bookContentView.getqTag())
                                                            bookContentView.loadError();
                                                    }
                                                }

                                                @Override
                                                public void onError(Throwable e) {
                                                    e.printStackTrace();
                                                    if (bookContentView != null && bookTag == bookContentView.getqTag())
                                                        bookContentView.loadError();
                                                }
                                            });
                                }
                            }

                            @Override
                            public void onError(Throwable e) {

                            }
                        });
            }
        } else {
            if (bookContentView != null && bookTag == bookContentView.getqTag())
                bookContentView.loadError();
        }
    }

    @Override
    public void updateProgress(int chapterIndex, int pageIndex) {
        bookShelf.setDurChapter(chapterIndex);
        bookShelf.setDurChapterPage(pageIndex);
    }

    @Override
    public void saveProgress() {
        if (bookShelf != null) {
            Observable.create((ObservableOnSubscribe<BookShelf>) e -> {
                        bookShelf.setFinalDate(System.currentTimeMillis());
                        GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().insertOrReplace(bookShelf);
                        e.onNext(bookShelf);
                        e.onComplete();
                    }).subscribeOn(Schedulers.io())
                    .subscribe(new SimpleObserver<>() {
                        @Override
                        public void onNext(BookShelf value) {
                            RxBus.get().post(RxBusTag.UPDATE_BOOK_PROGRESS, bookShelf);
                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
                        }
                    });
        }
    }

    @Override
    public String getChapterTitle(int chapterIndex) {
        if (bookShelf.getBookInfo().getChapterlist().size() == 0) {
            return "无章节";
        } else
            return bookShelf.getBookInfo().getChapterlist().get(chapterIndex).getDurChapterName();
    }

    public Observable<List<String>> SeparateParagraphtoLines(final String paragraphstr) {
        return Observable.create(e -> {
            TextPaint mPaint = (TextPaint) mView.getPaint();
            mPaint.setSubpixelText(true);
            Layout tempLayout = new StaticLayout(paragraphstr, mPaint, mView.getContentWidth(), Layout.Alignment.ALIGN_NORMAL, 0, 0, false);
            List<String> linesdata = new ArrayList<>();
            for (int i = 0; i < tempLayout.getLineCount(); i++) {
                linesdata.add(paragraphstr.substring(tempLayout.getLineStart(i), tempLayout.getLineEnd(i)));
            }
            e.onNext(linesdata);
            e.onComplete();
        });
    }

    @Override
    public void setPageLineCount(int pageLineCount) {
        this.pageLineCount = pageLineCount;
    }

    private void checkInShelf() {
        Observable.create((ObservableOnSubscribe<Boolean>) e -> {
                    List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().where(BookShelfDao.Properties.NoteUrl.eq(bookShelf.getNoteUrl())).build().list();
                    if (temp == null || temp.size() == 0) {
                        isAdd = false;
                    } else
                        isAdd = true;
                    e.onNext(isAdd);
                    e.onComplete();
                }).subscribeOn(Schedulers.io())
                .compose(((BaseActivity) mView.getContext()).<Boolean>bindUntilEvent(ActivityEvent.DESTROY))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<Boolean>() {
                    @Override
                    public void onNext(Boolean value) {
                        mView.initPop();
                        mView.setHpbReadProgressMax(bookShelf.getBookInfo().getChapterlist().size());
                        mView.startLoadingBook();
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    @Override
    public void addToShelf(final OnAddListner addListner) {
        if (bookShelf != null) {
            Observable.create(new ObservableOnSubscribe<Boolean>() {
                        @Override
                        public void subscribe(ObservableEmitter<Boolean> e) throws Exception {
                            GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().insertOrReplaceInTx(bookShelf.getBookInfo().getChapterlist());
                            GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().insertOrReplace(bookShelf.getBookInfo());
                            //网络数据获取成功  存入BookShelf表数据库
                            GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().insertOrReplace(bookShelf);
                            RxBus.get().post(RxBusTag.HAD_ADD_BOOK, bookShelf);
                            isAdd = true;
                            e.onNext(true);
                            e.onComplete();
                        }
                    }).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new SimpleObserver<Object>() {
                        @Override
                        public void onNext(Object value) {
                            if (addListner != null)
                                addListner.addSuccess();
                        }

                        @Override
                        public void onError(Throwable e) {

                        }
                    });
        }
    }

    public Boolean getAdd() {
        return isAdd;
    }

    public Observable<String> getRealFilePath(final Context context, final Uri uri) {
        return Observable.create(e -> {
            String data = "";
            if (null != uri) {
                final String scheme = uri.getScheme();
                if (scheme == null)
                    data = uri.getPath();
                else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
                    data = uri.getPath();
                } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
                    Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.Images.ImageColumns.DATA}, null, null, null);
                    if (null != cursor) {
                        if (cursor.moveToFirst()) {
                            int index = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
                            if (index > -1) {
                                data = cursor.getString(index);
                            }
                        }
                        cursor.close();
                    }

                    if ((data == null || data.length() <= 0) && uri.getPath() != null && uri.getPath().contains("/storage/emulated/")) {
                        data = uri.getPath().substring(uri.getPath().indexOf("/storage/emulated/"));
                    }
                }
            }
            e.onNext(data == null ? "" : data);
            e.onComplete();
        });
    }

    public interface OnAddListner {
        public void addSuccess();
    }
}
