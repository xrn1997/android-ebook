package com.ebook.find.provider;

import android.content.Context;

import androidx.fragment.app.Fragment;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.common.provider.IFindProvider;
import com.ebook.find.fragment.MainFindFragment;


@Route(path = "/find/main", name = "书库")
public class FindProvider implements IFindProvider {
    @Override
    public Fragment getMainFindFragment() {
        return MainFindFragment.newInstance();
    }

    @Override
    public void init(Context context) {

    }
}
