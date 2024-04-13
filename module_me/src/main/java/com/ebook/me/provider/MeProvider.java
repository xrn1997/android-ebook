package com.ebook.me.provider;

import com.ebook.common.provider.IMeProvider;
import com.ebook.me.fragment.MainMeFragment;
import com.therouter.inject.ServiceProvider;


public class MeProvider {
    @ServiceProvider
    public static IMeProvider getMainMeFragment() {
        return MainMeFragment::newInstance;
    }

}
