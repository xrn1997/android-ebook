package com.ebook.db;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.ebook.db.entity.DaoMaster;
import com.ebook.db.entity.DaoSession;

@SuppressLint("StaticFieldLeak")
public class GreenDaoManager {
    private volatile static GreenDaoManager greenDaoManager;
    public static Context mContext;
    private DaoMaster.DevOpenHelper mHelper;
    private SQLiteDatabase db;
    private DaoMaster mDaoMaster;
    private DaoSession mDaoSession;

    private GreenDaoManager() {
        mHelper = new DaoMaster.DevOpenHelper(mContext, "ebook.db", null);
        db = mHelper.getWritableDatabase();
        // 注意：该数据库连接属于 DaoMaster，所以多个 Session 指的是相同的数据库连接。
        mDaoMaster = new DaoMaster(db);
        mDaoSession = mDaoMaster.newSession();
    }

    public static void init(Application application) {
        mContext = application;
    }

    public static GreenDaoManager getInstance() {
        if (greenDaoManager == null) {
            synchronized (GreenDaoManager.class) {
                if (greenDaoManager == null) {
                    greenDaoManager = new GreenDaoManager();
                }
            }
        }
        return greenDaoManager;
    }

    public DaoSession getmDaoSession() {
        return mDaoSession;
    }

    public SQLiteDatabase getDb() {
        return db;
    }
}
