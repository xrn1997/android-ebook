package com.ebook.common.provider;

import androidx.fragment.app.Fragment;

import com.alibaba.android.arouter.facade.template.IProvider;


public interface IMeProvider extends IProvider {
    Fragment getMainMeFragment();
}
