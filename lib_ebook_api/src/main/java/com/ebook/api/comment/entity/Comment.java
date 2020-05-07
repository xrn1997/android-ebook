package com.ebook.api.comment.entity;

import android.os.Parcel;
import android.os.Parcelable;

import com.ebook.api.user.entity.User;

public class Comment implements Parcelable {
    private Long id;
    private User user;
    private String chapterUrl; //对应BookInfo noteUrl;
    private String chapterName;//当前章节名称
    private String bookname;
    private String comment;

    public Comment() {
    }

    protected Comment(Parcel in) {
        if (in.readByte() == 0) {
            id = null;
        } else {
            id = in.readLong();
        }
        chapterUrl = in.readString();
        chapterName = in.readString();
        bookname = in.readString();
        comment = in.readString();
        user = in.readParcelable(Thread.currentThread().getContextClassLoader());
    }

    public static final Creator<Comment> CREATOR = new Creator<Comment>() {
        @Override
        public Comment createFromParcel(Parcel in) {
            return new Comment(in);
        }

        @Override
        public Comment[] newArray(int size) {
            return new Comment[size];
        }
    };

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getChapterUrl() {
        return chapterUrl;
    }

    public void setChapterUrl(String chapterUrl) {
        this.chapterUrl = chapterUrl;
    }

    public String getChapterName() {
        return chapterName;
    }

    public void setChapterName(String chapterName) {
        this.chapterName = chapterName;
    }

    public String getBookname() {
        return bookname;
    }

    public void setBookname(String bookname) {
        this.bookname = bookname;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (id == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(id);
        }
        dest.writeString(chapterUrl);
        dest.writeString(chapterName);
        dest.writeString(bookname);
        dest.writeParcelable(user, 0);
        dest.writeString(comment);
    }
}
