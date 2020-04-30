package com.ebook.find.mvvm.viewmodel;

import android.app.Application;
import android.view.View;

import com.ebook.common.mvvm.viewmodel.BaseRefreshViewModel;
import com.ebook.db.entity.Library;
import com.ebook.find.entity.BookType;
import com.ebook.find.mvvm.model.LibraryModel;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableArrayList;

public class LibraryViewModel extends BaseRefreshViewModel<Library, LibraryModel> {
    private ObservableArrayList<BookType> bookTypes=new ObservableArrayList<>();
    public LibraryViewModel(@NonNull Application application, LibraryModel model) {
        super(application, model);
    }

    @Override
    public void refreshData() {

    }

    @Override
    public void loadMore() {

    }

    public ObservableArrayList<BookType> getBookTypeList(){
        bookTypes.addAll(mModel.getBookTypeList());
        return bookTypes;
    }
}
