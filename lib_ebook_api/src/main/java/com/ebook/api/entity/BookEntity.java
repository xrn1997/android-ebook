package com.ebook.api.entity;


import androidx.annotation.NonNull;

public class BookEntity {

    private String Id;
    private String Name;
    private String Author;
    private String Img;
    private String Desc;
    private String BookStatus;
    private String LastChapterId;
    private String LastChapter;
    private String CName;
    private String UpdateTime;

    public String getId() {
        return Id;
    }

    public void setId(String Id) {
        this.Id = Id;
    }

    public String getName() {
        return Name;
    }

    public void setName(String Name) {
        this.Name = Name;
    }

    public String getAuthor() {
        return Author;
    }

    public void setAuthor(String Author) {
        this.Author = Author;
    }

    public String getImg() {
        return Img;
    }

    public void setImg(String Img) {
        this.Img = Img;
    }

    public String getDesc() {
        return Desc;
    }

    public void setDesc(String Desc) {
        this.Desc = Desc;
    }

    public String getBookStatus() {
        return BookStatus;
    }

    public void setBookStatus(String BookStatus) {
        this.BookStatus = BookStatus;
    }

    public String getLastChapterId() {
        return LastChapterId;
    }

    public void setLastChapterId(String LastChapterId) {
        this.LastChapterId = LastChapterId;
    }

    public String getLastChapter() {
        return LastChapter;
    }

    public void setLastChapter(String LastChapter) {
        this.LastChapter = LastChapter;
    }

    public String getCName() {
        return CName;
    }

    public void setCName(String CName) {
        this.CName = CName;
    }

    public String getUpdateTime() {
        return UpdateTime;
    }

    public void setUpdateTime(String UpdateTime) {
        this.UpdateTime = UpdateTime;
    }

    @NonNull
    @Override
    public String toString() {
        return "BookEntity{" +
                "Id='" + Id + '\'' +
                ", Name='" + Name + '\'' +
                ", Author='" + Author + '\'' +
                ", Img='" + Img + '\'' +
                ", Desc='" + Desc + '\'' +
                ", BookStatus='" + BookStatus + '\'' +
                ", LastChapterId='" + LastChapterId + '\'' +
                ", LastChapter='" + LastChapter + '\'' +
                ", CName='" + CName + '\'' +
                ", UpdateTime='" + UpdateTime + '\'' +
                '}';
    }
}
