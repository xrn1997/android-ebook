package com.ebook.book.provider;

import android.content.Context;


import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.book.fragment.MainNewsFragment;
import com.ebook.common.provider.INewsProvider;

import androidx.fragment.app.Fragment;

@Route(path = "/news/main",name = "新闻")
public class NewProvider implements INewsProvider {
    @Override
    public Fragment getMainNewsFragment() {
        return MainNewsFragment.newInstance();
    }

    @Override
    public void init(Context context) {

    }
}
