
package com.ebook.basebook.mvp.model.impl;


import com.ebook.basebook.mvp.model.IImportBookModel;
import com.ebook.basebook.base.impl.MBaseModelImpl;
import com.ebook.db.GreenDaoManager;
import com.ebook.db.entity.BookInfoDao;
import com.ebook.db.entity.BookShelf;
import com.ebook.db.entity.BookShelfDao;
import com.ebook.db.entity.ChapterList;
import com.ebook.db.entity.ChapterListDao;
import com.ebook.db.entity.LocBookShelf;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;

public class ImportBookModelImpl extends MBaseModelImpl implements IImportBookModel {

    public static ImportBookModelImpl getInstance() {
        return new ImportBookModelImpl();
    }

    @Override
    public Observable<LocBookShelf> importBook(final File book) {
        return Observable.create(new ObservableOnSubscribe<LocBookShelf>() {
            @Override
            public void subscribe(ObservableEmitter<LocBookShelf> e) throws Exception {
                MessageDigest md = MessageDigest.getInstance("MD5");
                FileInputStream in = new FileInputStream(book);
                byte[] buffer = new byte[2048];
                int len;
                while ((len = in.read(buffer, 0, 2048)) != -1) {
                    md.update(buffer, 0, len);
                }
                in.close();
                in = null;

                String md5 = new BigInteger(1, md.digest()).toString(16);
                BookShelf bookShelf = null;
                List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().where(BookShelfDao.Properties.NoteUrl.eq(md5)).build().list();
                Boolean isNew = true;
                if (temp != null && temp.size() > 0) {
                    isNew = false;
                    bookShelf = temp.get(0);
                    bookShelf.setBookInfo(GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().queryBuilder().where(BookInfoDao.Properties.NoteUrl.eq(bookShelf.getNoteUrl())).build().list().get(0));
                } else {
                    bookShelf = new BookShelf();
                    bookShelf.setFinalDate(System.currentTimeMillis());
                    bookShelf.setDurChapter(0);
                    bookShelf.setDurChapterPage(0);
                    bookShelf.setTag(BookShelf.LOCAL_TAG);
                    bookShelf.setNoteUrl(md5);

                    bookShelf.getBookInfo().setAuthor("佚名");
                    bookShelf.getBookInfo().setName(book.getName().replace(".txt", "").replace(".TXT", ""));
                    bookShelf.getBookInfo().setFinalRefreshData(System.currentTimeMillis());
                    bookShelf.getBookInfo().setCoverUrl("");
                    bookShelf.getBookInfo().setNoteUrl(md5);
                    bookShelf.getBookInfo().setTag(BookShelf.LOCAL_TAG);

                    saveChapter(book, md5);
                    GreenDaoManager.getInstance().getmDaoSession().getBookInfoDao().insertOrReplace(bookShelf.getBookInfo());
                    GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().insertOrReplace(bookShelf);
                }
                bookShelf.getBookInfo().setChapterlist(GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().queryBuilder().where(ChapterListDao.Properties.NoteUrl.eq(bookShelf.getNoteUrl())).orderAsc(ChapterListDao.Properties.DurChapterIndex).build().list());
                e.onNext(new LocBookShelf(isNew, bookShelf));
                e.onComplete();
            }
        });
    }

    private Boolean isAdded(BookShelf temp, List<BookShelf> shelfs) {
        if (shelfs == null || shelfs.size() == 0) {
            return false;
        } else {
            int a = 0;
            for (int i = 0; i < shelfs.size(); i++) {
                if (temp.getNoteUrl().equals(shelfs.get(i).getNoteUrl())) {
                    break;
                } else {
                    a++;
                }
            }
            if (a == shelfs.size()) {
                return false;
            } else
                return true;
        }
    }

    private void saveChapter(File book, String md5) throws IOException {
        String regex = "第.{1,7}章.{0,}";

        String encoding;

        FileInputStream fis = new FileInputStream(book);
        byte[] buf = new byte[4096];
        UniversalDetector detector = new UniversalDetector(null);
        int nread;
        while ((nread = fis.read(buf)) > 0 && !detector.isDone()) {
            detector.handleData(buf, 0, nread);
        }
        detector.dataEnd();
        encoding = detector.getDetectedCharset();
        if (encoding == null || encoding.length() == 0)
            encoding = "utf-8";
        fis.close();
        fis = null;

        int chapterPageIndex = 0;
        String title = null;
        StringBuilder contentBuilder = new StringBuilder();
        fis = new FileInputStream(book);
        InputStreamReader inputreader = new InputStreamReader(fis, encoding);
        BufferedReader buffreader = new BufferedReader(inputreader);
        String line;
        while ((line = buffreader.readLine()) != null) {
            Pattern p = Pattern.compile(regex);
            Matcher m = p.matcher(line);
            if (m.find()) {
                String temp = line.trim().substring(0, line.trim().indexOf("第"));
                if (temp != null && temp.trim().length() > 0) {
                    contentBuilder.append(temp);
                }
                if (contentBuilder.toString().length() > 0) {
                    if (contentBuilder.toString().replaceAll("　", "").trim().length() > 0) {
                        saveDurChapterContent(md5, chapterPageIndex, title, contentBuilder.toString());
                        chapterPageIndex++;
                    }
                    contentBuilder.delete(0, contentBuilder.length());
                }
                title = line.trim().substring(line.trim().indexOf("第"));
            } else {
                if (line.trim().length() == 0) {
                    if (contentBuilder.length() > 0) {
                        contentBuilder.append("\r\n\u3000\u3000");
                    } else {
                        contentBuilder.append("\r\u3000\u3000");
                    }
                } else {
                    contentBuilder.append(line);
                    if (title == null) {
                        title = line.trim();
                    }
                }
            }
        }
        if (contentBuilder.length() > 0) {
            saveDurChapterContent(md5, chapterPageIndex, title, contentBuilder.toString());
            contentBuilder.delete(0, contentBuilder.length());
            title = null;
        }
        buffreader.close();
        inputreader.close();
        fis.close();
        fis = null;
    }

    private void saveDurChapterContent(String md5, int chapterPageIndex, String name, String content) {
        ChapterList chapterList = new ChapterList();
        chapterList.setNoteUrl(md5);
        chapterList.setDurChapterIndex(chapterPageIndex);
        chapterList.setTag(BookShelf.LOCAL_TAG);
        chapterList.setDurChapterUrl(md5 + "_" + chapterPageIndex);
        chapterList.setDurChapterName(name);
        chapterList.getBookContent().setDurChapterUrl(chapterList.getDurChapterUrl());
        chapterList.getBookContent().setTag(BookShelf.LOCAL_TAG);
        chapterList.getBookContent().setDurChapterIndex(chapterList.getDurChapterIndex());
        chapterList.getBookContent().setDurCapterContent(content);

        GreenDaoManager.getInstance().getmDaoSession().getBookContentDao().insertOrReplace(chapterList.getBookContent());
        GreenDaoManager.getInstance().getmDaoSession().getChapterListDao().insertOrReplace(chapterList);
    }
}
