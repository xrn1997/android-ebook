
package com.ebook.basebook.mvp.view;

import android.content.Intent;
import android.graphics.Paint;

import androidx.activity.result.ActivityResultLauncher;

import com.ebook.basebook.base.IView;

public interface IBookReadView extends IView {

    /**
     * 获取当前阅读界面UI画笔
     */
    Paint getPaint();

    /**
     * 获取当前小说内容可绘制宽度
     */
    int getContentWidth();

    /**
     * 小说数据初始化成功
     */
    void initContentSuccess(int durChapterIndex, int chapterAll, int durPageIndex);

    /**
     * 开始加载
     */
    void startLoadingBook();

    void setHpbReadProgressMax(int count);

    void initPop();

    void showLoadBook();

    void dimissLoadBook();

    void loadLocationBookError();

    void showDownloadMenu();

     ActivityResultLauncher<Intent> getRequestPermission();
}
