package com.ebook.db.entity;

import android.os.Parcel;
import android.os.Parcelable;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;

import androidx.annotation.NonNull;

import org.greenrobot.greendao.annotation.Transient;

import java.util.ArrayList;
import java.util.List;

/**
 * 书本信息
 */
@Entity
public class BookInfo implements Parcelable, Cloneable {

    @Transient
    public static final long REFRESH_DUR = 10 * 60 * 1000;

    private String name; //小说名

    private String tag;

    @Id
    private String noteUrl;  //如果是来源网站   则小说根地址 /如果是本地  则是小说本地MD5

    private String chapterUrl;  //章节目录地址

    @Transient
    private List<ChapterList> chapterlist = new ArrayList<>();    //章节列表

    private long finalRefreshData;  //章节最后更新时间

    private String coverUrl; //小说封面

    private String author;//作者

    private String introduce; //简介

    private String origin; //来源

    private String status;//状态，连载or完结

    protected BookInfo(Parcel in) {
        name = in.readString();
        tag = in.readString();
        noteUrl = in.readString();
        chapterUrl = in.readString();
        chapterlist = in.createTypedArrayList(ChapterList.CREATOR);
        finalRefreshData = in.readLong();
        coverUrl = in.readString();
        author = in.readString();
        introduce = in.readString();
        origin = in.readString();
        status=in.readString();
    }

    @Generated(hash = 882977097)
    public BookInfo(String name, String tag, String noteUrl, String chapterUrl,
            long finalRefreshData, String coverUrl, String author, String introduce,
            String origin, String status) {
        this.name = name;
        this.tag = tag;
        this.noteUrl = noteUrl;
        this.chapterUrl = chapterUrl;
        this.finalRefreshData = finalRefreshData;
        this.coverUrl = coverUrl;
        this.author = author;
        this.introduce = introduce;
        this.origin = origin;
        this.status = status;
    }

    @Generated(hash = 1952025412)
    public BookInfo() {
    }




    @Transient
    public static final Creator<BookInfo> CREATOR = new Creator<BookInfo>() {
        @Override
        public BookInfo createFromParcel(Parcel in) {
            return new BookInfo(in);
        }

        @Override
        public BookInfo[] newArray(int size) {
            return new BookInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(tag);
        dest.writeString(noteUrl);
        dest.writeString(chapterUrl);
        dest.writeTypedList(chapterlist);
        dest.writeLong(finalRefreshData);
        dest.writeString(coverUrl);
        dest.writeString(author);
        dest.writeString(introduce);
        dest.writeString(origin);
        dest.writeString(status);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTag() {
        return this.tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getNoteUrl() {
        return this.noteUrl;
    }

    public void setNoteUrl(String noteUrl) {
        this.noteUrl = noteUrl;
    }

    public String getChapterUrl() {
        return this.chapterUrl;
    }

    public void setChapterUrl(String chapterUrl) {
        this.chapterUrl = chapterUrl;
    }

    public long getFinalRefreshData() {
        return this.finalRefreshData;
    }

    public void setFinalRefreshData(long finalRefreshData) {
        this.finalRefreshData = finalRefreshData;
    }

    public static long getRefreshDur() {
        return REFRESH_DUR;
    }

    public List<ChapterList> getChapterlist() {
        return chapterlist;
    }

    public void setChapterlist(List<ChapterList> chapterlist) {
        this.chapterlist = chapterlist;
    }

    public static Creator<BookInfo> getCREATOR() {
        return CREATOR;
    }

    public String getCoverUrl() {
        return this.coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getAuthor() {
        return this.author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getIntroduce() {
        return this.introduce;
    }

    public void setIntroduce(String introduce) {
        this.introduce = introduce;
    }

    public String getOrigin() {
        return this.origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    @NonNull
    @Override
    protected Object clone() throws CloneNotSupportedException {
        BookInfo bookInfo = (BookInfo) super.clone();
        bookInfo.name = name;
        bookInfo.tag = tag;
        bookInfo.noteUrl = noteUrl;
        bookInfo.chapterUrl = chapterUrl;
        bookInfo.coverUrl = coverUrl;
        bookInfo.author = author;
        bookInfo.introduce = introduce;
        bookInfo.origin = origin;
        bookInfo.status=status;
        if (chapterlist != null) {
            List<ChapterList> newList = new ArrayList<>();
            for (ChapterList chapterList : chapterlist) {
                newList.add((ChapterList) chapterList.clone());
            }
            bookInfo.setChapterlist(newList);
        }
        return bookInfo;
    }
}