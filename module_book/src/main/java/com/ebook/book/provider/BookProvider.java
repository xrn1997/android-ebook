package com.ebook.book.provider;

import android.content.Context;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.book.fragment.MainBooksFragment;
import com.ebook.common.provider.IBookProvider;

import androidx.fragment.app.Fragment;

@Route(path = "/bookshelf/main", name = "书架_1")
public class BookProvider implements IBookProvider {
    @Override
    public Fragment getMainBookFragment() {
        return MainBooksFragment.newInstance();
    }

    @Override
    public void init(Context context) {

    }


}
