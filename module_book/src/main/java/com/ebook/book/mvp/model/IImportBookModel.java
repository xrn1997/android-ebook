
package com.ebook.book.mvp.model;

import com.ebook.db.entity.LocBookShelf;

import java.io.File;

import io.reactivex.Observable;

public interface IImportBookModel {

    Observable<LocBookShelf> importBook(File book);
}
