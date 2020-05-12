package com.ebook.me.provider;

import android.content.Context;

import androidx.fragment.app.Fragment;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.ebook.common.provider.IMeProvider;
import com.ebook.me.fragment.MainMeFragment;


@Route(path = "/me/main", name = "我的服务")
public class MeProvider implements IMeProvider {
    @Override
    public Fragment getMainMeFragment() {
        return MainMeFragment.newInstance();
    }

    @Override
    public void init(Context context) {

    }
}
