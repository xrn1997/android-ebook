package com.ebook.db.entity;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;

@Entity
public class SearchHistory {
    @Id(autoincrement = true)
    private Long id = null;
    private int type;
    private String content;
    private long date;

    @Generated(hash = 1127448272)
    public SearchHistory(Long id, int type, String content, long date) {
        this.id = id;
        this.type = type;
        this.content = content;
        this.date = date;
    }

    @Generated(hash = 1905904755)
    public SearchHistory() {
    }

    public SearchHistory(int type, String content, long date) {
        this.type = type;
        this.content = content;
        this.date = date;
    }

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getType() {
        return this.type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getContent() {
        return this.content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getDate() {
        return this.date;
    }

    public void setDate(long date) {
        this.date = date;
    }
}
