package com.ebook;

import com.ebook.api.RetrofitManager;
import com.ebook.common.BaseApplication;

import com.ebook.db.GreenDaoManager;


public class MyApplication extends BaseApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        RetrofitManager.init(this);
        GreenDaoManager.init(this);
    }

}
