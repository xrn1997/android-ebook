package com.ebook.db.entity;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载章节列表
 */
public class DownloadChapterList implements Parcelable {
    public static final Creator<DownloadChapterList> CREATOR = new Creator<DownloadChapterList>() {
        @Override
        public DownloadChapterList createFromParcel(Parcel in) {
            return new DownloadChapterList(in);
        }

        @Override
        public DownloadChapterList[] newArray(int size) {
            return new DownloadChapterList[size];
        }
    };
    private List<DownloadChapter> data;

    public DownloadChapterList(List<DownloadChapter> result) {
        this.data = result;
    }

    protected DownloadChapterList(Parcel in) {
        if (data == null)
            data = new ArrayList<>();
        in.readTypedList(data, DownloadChapter.CREATOR);
    }

    public List<DownloadChapter> getData() {
        return data;
    }

    public void setData(List<DownloadChapter> data) {
        this.data = data;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(data);
    }
}
