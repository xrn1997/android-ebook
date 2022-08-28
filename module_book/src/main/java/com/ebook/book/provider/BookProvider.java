package com.ebook.book.provider;

import android.content.Context;

import androidx.fragment.app.Fragment;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.book.fragment.MainBookFragment;
import com.ebook.common.provider.IBookProvider;

@Route(path = "/book/main", name = "书架")
public class BookProvider implements IBookProvider {
    @Override
    public Fragment getMainBookFragment() {
        return MainBookFragment.newInstance();
    }

    @Override
    public void init(Context context) {

    }


}
