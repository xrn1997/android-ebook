
package com.ebook.db.entity;

import android.os.Parcel;
import android.os.Parcelable;


import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Transient;

import java.util.ArrayList;
import java.util.List;

/**
 * 书本缓存内容
 */
@Entity
public class BookContent implements Parcelable {
    @Id
    private String durChapterUrl; //对应BookInfo noteUrl;

    private int durChapterIndex;   //当前章节  （包括番外）

    private String durCapterContent; //当前章节内容

    private String tag;   //来源  某个网站/本地

    @Transient
    private Boolean isRight = true;

    @Transient
    private List<String> lineContent = new ArrayList<>();

    @Transient
    private float lineSize;


    protected BookContent(Parcel in) {
        durChapterUrl = in.readString();
        durChapterIndex = in.readInt();
        durCapterContent = in.readString();
        tag = in.readString();
        byte tmpIsRight = in.readByte();
        isRight = tmpIsRight == 0 ? null : tmpIsRight == 1;
        lineContent = in.createStringArrayList();
        lineSize = in.readFloat();
    }

    @Generated(hash = 1780852126)
    public BookContent(String durChapterUrl, int durChapterIndex,
                       String durCapterContent, String tag) {
        this.durChapterUrl = durChapterUrl;
        this.durChapterIndex = durChapterIndex;
        this.durCapterContent = durCapterContent;
        this.tag = tag;
    }

    @Generated(hash = 1559836836)
    public BookContent() {
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(durChapterUrl);
        dest.writeInt(durChapterIndex);
        dest.writeString(durCapterContent);
        dest.writeString(tag);
        dest.writeByte((byte) (isRight == null ? 0 : isRight ? 1 : 2));
        dest.writeStringList(lineContent);
        dest.writeFloat(lineSize);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public String getDurChapterUrl() {
        return this.durChapterUrl;
    }

    public void setDurChapterUrl(String durChapterUrl) {
        this.durChapterUrl = durChapterUrl;
    }

    public int getDurChapterIndex() {
        return this.durChapterIndex;
    }

    public void setDurChapterIndex(int durChapterIndex) {
        this.durChapterIndex = durChapterIndex;
    }

    public String getDurCapterContent() {
        return this.durCapterContent;
    }

    public void setDurCapterContent(String durCapterContent) {
        this.durCapterContent = durCapterContent;
    }

    public Boolean getRight() {
        return isRight;
    }

    public void setRight(Boolean right) {
        isRight = right;
    }

    public List<String> getLineContent() {
        return lineContent;
    }

    public void setLineContent(List<String> lineContent) {
        this.lineContent = lineContent;
    }

    public float getLineSize() {
        return lineSize;
    }

    public void setLineSize(float lineSize) {
        this.lineSize = lineSize;
    }

    public String getTag() {
        return this.tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public static final Creator<BookContent> CREATOR = new Creator<BookContent>() {
        @Override
        public BookContent createFromParcel(Parcel in) {
            return new BookContent(in);
        }

        @Override
        public BookContent[] newArray(int size) {
            return new BookContent[size];
        }
    };
}
