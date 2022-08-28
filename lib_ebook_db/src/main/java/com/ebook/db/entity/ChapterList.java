package com.ebook.db.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Transient;

/**
 * 章节列表
 */
@Entity
public class ChapterList implements Parcelable, Cloneable {

    @Transient
    public static final Creator<ChapterList> CREATOR = new Creator<ChapterList>() {
        @Override
        public ChapterList createFromParcel(Parcel in) {
            return new ChapterList(in);
        }

        @Override
        public ChapterList[] newArray(int size) {
            return new ChapterList[size];
        }
    };
    private String noteUrl; //对应BookInfo noteUrl;
    private int durChapterIndex;  //当前章节数
    @Id
    private String durChapterUrl;  //当前章节对应的文章地址
    private String durChapterName;  //当前章节名称
    private String tag;
    private Boolean hasCache = false;
    @Transient
    private BookContent bookContent = new BookContent();

    protected ChapterList(Parcel in) {
        noteUrl = in.readString();
        durChapterIndex = in.readInt();
        durChapterUrl = in.readString();
        durChapterName = in.readString();
        tag = in.readString();
        bookContent = in.readParcelable(BookContent.class.getClassLoader());
        hasCache = in.readByte() != 0;
    }

    @Generated(hash = 1219340674)
    public ChapterList(String noteUrl, int durChapterIndex, String durChapterUrl,
                       String durChapterName, String tag, Boolean hasCache) {
        this.noteUrl = noteUrl;
        this.durChapterIndex = durChapterIndex;
        this.durChapterUrl = durChapterUrl;
        this.durChapterName = durChapterName;
        this.tag = tag;
        this.hasCache = hasCache;
    }

    @Generated(hash = 1539606642)
    public ChapterList() {
    }

    public static Creator<ChapterList> getCREATOR() {
        return CREATOR;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(noteUrl);
        dest.writeInt(durChapterIndex);
        dest.writeString(durChapterUrl);
        dest.writeString(durChapterName);
        dest.writeString(tag);
        dest.writeByte((byte) (hasCache == null ? 0 : hasCache ? 1 : 2));
        dest.writeParcelable(bookContent, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public String getNoteUrl() {
        return this.noteUrl;
    }

    public void setNoteUrl(String noteUrl) {
        this.noteUrl = noteUrl;
    }

    public int getDurChapterIndex() {
        return this.durChapterIndex;
    }

    public void setDurChapterIndex(int durChapterIndex) {
        this.durChapterIndex = durChapterIndex;
    }

    public String getDurChapterUrl() {
        return this.durChapterUrl;
    }

    public void setDurChapterUrl(String durChapterUrl) {
        this.durChapterUrl = durChapterUrl;
    }

    public String getDurChapterName() {
        return this.durChapterName;
    }

    public void setDurChapterName(String durChapterName) {
        this.durChapterName = durChapterName;
    }

    public String getTag() {
        return this.tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public Boolean getHasCache() {
        return this.hasCache;
    }

    public void setHasCache(Boolean hasCache) {
        this.hasCache = hasCache;
    }

    public BookContent getBookContent() {
        return bookContent;
    }

    public void setBookContent(BookContent bookContent) {
        this.bookContent = bookContent;
    }

    @NonNull
    @Override
    protected Object clone() throws CloneNotSupportedException {
        ChapterList chapterList = (ChapterList) super.clone();
        chapterList.noteUrl = noteUrl;
        chapterList.durChapterUrl = durChapterUrl;
        chapterList.durChapterName = durChapterName;
        chapterList.tag = tag;
        chapterList.hasCache = hasCache;
        chapterList.bookContent = new BookContent();
        return chapterList;
    }

    @Override
    public String toString() {
        return "ChapterList{" +
                "noteUrl='" + noteUrl + '\'' +
                ", durChapterIndex=" + durChapterIndex +
                ", durChapterUrl='" + durChapterUrl + '\'' +
                ", durChapterName='" + durChapterName + '\'' +
                ", tag='" + tag + '\'' +
                ", hasCache=" + hasCache +
                ", bookContent=" + bookContent +
                '}';
    }
}
