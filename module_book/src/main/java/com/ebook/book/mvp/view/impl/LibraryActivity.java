
package com.ebook.book.mvp.view.impl;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import com.ebook.book.R;
import com.ebook.book.mvp.view.libraryview.LibraryKindBookListView;
import com.ebook.book.mvp.view.libraryview.LibraryNewBooksView;
import com.ebook.common.mvp.base.activity.BaseActivity;
import com.ebook.common.util.DisplayUtil;
import com.ebook.common.view.refreshview.BaseRefreshListener;
import com.ebook.common.view.refreshview.RefreshProgressBar;
import com.ebook.common.view.refreshview.RefreshScrollView;
import com.ebook.db.entity.Library;
import com.ebook.db.entity.LibraryNewBook;
import com.ebook.db.entity.SearchBook;
import com.ebook.book.mvp.presenter.ILibraryPresenter;
import com.ebook.book.mvp.presenter.impl.BookDetailPresenterImpl;
import com.ebook.book.mvp.presenter.impl.LibraryPresenterImpl;
import com.ebook.book.mvp.view.ILibraryView;


import java.util.Iterator;
import java.util.Map;

public class LibraryActivity extends BaseActivity<ILibraryPresenter> implements ILibraryView {
    private RefreshScrollView rscvContent;
    private RefreshProgressBar rpbProgress;

    private LinearLayout llContent;
    private ImageButton ibReturn;
    private FrameLayout flSearch;

    private Animation animIn;
    private Animation animOut;

    private LinearLayout kindLl;

    private LibraryNewBooksView lavHotauthor;
    private LibraryKindBookListView lkbvKindbooklist;

    @Override
    protected ILibraryPresenter initInjector() {
        return new LibraryPresenterImpl();
    }

    @Override
    protected void onCreateActivity() {
        setContentView(R.layout.activity_library);
    }

    @Override
    protected void firstRequest() {
        llContent.startAnimation(animIn);
    }

    @Override
    protected void initData() {
        animIn = AnimationUtils.loadAnimation(this, R.anim.anim_act_importbook_in);
        animOut = AnimationUtils.loadAnimation(this, R.anim.anim_act_importbook_out);
    }

    @Override
    protected void bindView() {
        rscvContent = (RefreshScrollView) findViewById(R.id.rscv_content);
        rpbProgress = (RefreshProgressBar) findViewById(R.id.rpb_progress);
        rscvContent.setRpb(rpbProgress);

        llContent = (LinearLayout) findViewById(R.id.ll_content);
        ibReturn = (ImageButton) findViewById(R.id.ib_return);
        flSearch = (FrameLayout) findViewById(R.id.fl_search);

        kindLl = (LinearLayout) findViewById(R.id.kind_ll);
        initKind();

        lavHotauthor = (LibraryNewBooksView) findViewById(R.id.lav_hotauthor);
        lkbvKindbooklist = (LibraryKindBookListView) findViewById(R.id.lkbv_kindbooklist);
    }

    private void initKind() {
        int columnCout = 4;
        Iterator iterator = mPresenter.getKinds().entrySet().iterator();
        int temp = 0;
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout linearLayout = null;
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.weight = 1;
        while (iterator.hasNext()) {
            final Map.Entry<String, String> resultTemp = (Map.Entry<String, String>) iterator.next();
            if (temp % columnCout == 0) {
                linearLayout = new LinearLayout(this);
                linearLayout.setLayoutParams(l);
                linearLayout.setOrientation(LinearLayout.HORIZONTAL);
                kindLl.addView(linearLayout);
            }
            TextView textView = new TextView(this);
            textView.setLayoutParams(tvLp);
            textView.setText(resultTemp.getKey());
            textView.setGravity(Gravity.CENTER);
            textView.setTextSize(14);
            textView.setPadding(0, DisplayUtil.dip2px( 5), 0, DisplayUtil.dip2px(  5));
            textView.setLines(1);
            textView.setTextColor(getResources().getColorStateList(R.color.selector_kind_tv_color));
            textView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ChoiceBookActivity.startChoiceBookActivity(LibraryActivity.this, resultTemp.getKey(),resultTemp.getValue());
                }
            });
            linearLayout.addView(textView);
            temp++;
        }
        int viewCount = mPresenter.getKinds().size() % columnCout == 0?0:(columnCout-mPresenter.getKinds().size() % columnCout);
        for(int i=0;i<viewCount;i++){
            View v = new View(this);
            v.setLayoutParams(tvLp);
            linearLayout.addView(v);
        }
    }

    @Override
    protected void bindEvent() {
        animIn.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                rscvContent.startRefresh();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        animOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                LibraryActivity.super.finish();
                overridePendingTransition(0, 0);
                isExiting = false;
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        ibReturn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
               // finish();
                onBackPressed();
            }
        });

        flSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //点击搜索
                startActivityByAnim(new Intent(LibraryActivity.this, SearchActivity.class), flSearch, "to_search", android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });

        rscvContent.setBaseRefreshListener(new BaseRefreshListener() {
            @Override
            public void startRefresh() {
                mPresenter.getLibraryData();
            }
        });
    }

    private Boolean isExiting = false;

    @Override
    public void finish() {
        if (!isExiting) {
            isExiting = true;
            llContent.startAnimation(animOut);
        }
    }

    @Override
    public void updateUI(final Library library) {
        //获取数据后刷新UI
        lavHotauthor.updateData(library.getLibraryNewBooks(), new LibraryNewBooksView.OnClickAuthorListener() {
            @Override
            public void clickNewBook(LibraryNewBook libraryNewBook) {
                SearchBook searchBook = new SearchBook();
                searchBook.setName(libraryNewBook.getName());
                searchBook.setNoteUrl(libraryNewBook.getUrl());
                searchBook.setTag(libraryNewBook.getTag());
                searchBook.setOrigin(libraryNewBook.getOrgin());
                Intent intent = new Intent(LibraryActivity.this, BookDetailActivity.class);
                intent.putExtra("from", BookDetailPresenterImpl.FROM_SEARCH);
                intent.putExtra("data", searchBook);
                startActivityByAnim(intent, android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });
        lkbvKindbooklist.updateData(library.getKindBooks(), new LibraryKindBookListView.OnItemListener() {
            @Override
            public void onClickMore(String title, String url) {
                ChoiceBookActivity.startChoiceBookActivity(LibraryActivity.this,title,url);
            }

            @Override
            public void onClickBook(ImageView animView, SearchBook searchBook) {
                Intent intent = new Intent(LibraryActivity.this, BookDetailActivity.class);
                intent.putExtra("from", BookDetailPresenterImpl.FROM_SEARCH);
                intent.putExtra("data", searchBook);
                startActivityByAnim(intent, animView, "img_cover", android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });
    }

    @Override
    public void finishRefresh() {
        rscvContent.finishRefresh();
    }
}