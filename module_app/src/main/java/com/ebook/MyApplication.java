package com.ebook;

import android.content.Intent;
import android.os.Build;

import com.ebook.api.RetrofitManager;
import com.ebook.common.BaseApplication;
import com.ebook.db.GreenDaoManager;
import com.ebook.book.service.DownloadService;
public class MyApplication extends BaseApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        RetrofitManager.init(this);
        GreenDaoManager.init(this);
        startService(new Intent(this, DownloadService.class));

    }
}
