
package com.ebook.basebook.mvp.model.impl;


import com.ebook.api.RetrofitManager;
import com.ebook.basebook.mvp.model.ImportBookModel;
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


public class ImportBookModelImpl extends MBaseModelImpl implements ImportBookModel {
    private volatile static ImportBookModelImpl importBookModel;

    public static ImportBookModelImpl getInstance() {
        if (importBookModel == null) {
            synchronized (ImportBookModelImpl.class) {
                if (importBookModel == null) {
                    importBookModel = new ImportBookModelImpl();
                }
            }
        }
        return importBookModel;
    }
    @Override
    public Observable<LocBookShelf> importBook(final File book) {
        return Observable.create(e -> {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream in = new FileInputStream(book);
            byte[] buffer = new byte[2048];
            int len;
            while ((len = in.read(buffer, 0, 2048)) != -1) {
                md.update(buffer, 0, len);
            }
            in.close();

            String md5 = new BigInteger(1, md.digest()).toString(16);
            BookShelf bookShelf;
            List<BookShelf> temp = GreenDaoManager.getInstance().getmDaoSession().getBookShelfDao().queryBuilder().where(BookShelfDao.Properties.NoteUrl.eq(md5)).build().list();
            boolean isNew = true;
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
        });
    }

    @SuppressWarnings("unused")
    private Boolean isAdded(BookShelf temp, List<BookShelf> shelf) {
        if (shelf == null || shelf.size() == 0) {
            return false;
        } else {
            int a = 0;
            for (int i = 0; i < shelf.size(); i++) {
                if (temp.getNoteUrl().equals(shelf.get(i).getNoteUrl())) {
                    break;
                } else {
                    a++;
                }
            }
            return a != shelf.size();
        }
    }

    private void saveChapter(File book, String md5) throws IOException {
        String regex = "第.{1,7}章.*";

        String encoding;

        FileInputStream fis = new FileInputStream(book);
        byte[] buf = new byte[4096];
        UniversalDetector detector = new UniversalDetector(null);
        int nRead;
        while ((nRead = fis.read(buf)) > 0 && !detector.isDone()) {
            detector.handleData(buf, 0, nRead);
        }
        detector.dataEnd();
        encoding = detector.getDetectedCharset();
        if (encoding == null || encoding.length() == 0)
            encoding = "utf-8";
        fis.close();

        int chapterPageIndex = 0;
        String title = null;
        StringBuilder contentBuilder = new StringBuilder();
        fis = new FileInputStream(book);
        InputStreamReader inputReader = new InputStreamReader(fis, encoding);
        BufferedReader buffReader = new BufferedReader(inputReader);
        String line;
        while ((line = buffReader.readLine()) != null) {
            Pattern p = Pattern.compile(regex);
            Matcher m = p.matcher(line);
            if (m.find()) {
                String temp = line.trim().substring(0, line.trim().indexOf("第"));
                if (temp.trim().length() > 0) {
                    contentBuilder.append(temp);
                }
                if (contentBuilder.toString().length() > 0) {
                    if (contentBuilder.toString().replaceAll(" ", "").replaceAll("\\s*", "").trim().length() > 0) {
                        saveDurChapterContent(md5, chapterPageIndex, title, contentBuilder.toString());
                        chapterPageIndex++;
                    }
                    contentBuilder.delete(0, contentBuilder.length());
                }
                title = line.trim().substring(line.trim().indexOf("第"));
            } else {
                String temp = line.trim().replaceAll(" ", "").replaceAll(" ", "").replaceAll("\\s*", "");
                if (temp.length() == 0) {
                    if (contentBuilder.length() > 0) {
                        contentBuilder.append("\r\n\u3000\u3000");
                    } else {
                        contentBuilder.append("\r\u3000\u3000");
                    }
                } else {
                    contentBuilder.append(temp);
                    if (title == null) {
                        title = line.trim();
                    }
                }
            }
        }
        if (contentBuilder.length() > 0) {
            saveDurChapterContent(md5, chapterPageIndex, title, contentBuilder.toString());
            contentBuilder.delete(0, contentBuilder.length());
        }
        buffReader.close();
        inputReader.close();
        fis.close();
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
