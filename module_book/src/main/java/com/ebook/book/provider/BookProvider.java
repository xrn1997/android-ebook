package com.ebook.book.provider;

import com.ebook.book.fragment.MainBookFragment;
import com.ebook.common.provider.IBookProvider;
import com.therouter.inject.ServiceProvider;


public class BookProvider {
    @ServiceProvider
    public static IBookProvider getMainFindFragment() {
        return MainBookFragment::newInstance;
    }

}
