package com.ebook.common.provider;

import com.alibaba.android.arouter.facade.template.IProvider;

import androidx.fragment.app.Fragment;

public interface IBookProvider extends IProvider {
    Fragment getMainBookFragment();
}
