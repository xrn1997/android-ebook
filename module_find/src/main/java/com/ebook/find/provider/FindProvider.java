package com.ebook.find.provider;

import com.ebook.common.provider.IFindProvider;
import com.ebook.find.fragment.MainFindFragment;
import com.therouter.inject.ServiceProvider;


public class FindProvider {
    @ServiceProvider
    public static IFindProvider getMainFindFragment() {
        return MainFindFragment::newInstance;
    }

}
