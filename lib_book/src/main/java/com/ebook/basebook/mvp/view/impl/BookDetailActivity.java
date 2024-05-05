package com.ebook.basebook.mvp.view.impl;

import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.basebook.R;
import com.ebook.basebook.base.activity.BaseActivity;
import com.ebook.basebook.base.manager.BitIntentDataManager;
import com.ebook.basebook.mvp.presenter.IBookDetailPresenter;
import com.ebook.basebook.mvp.presenter.impl.BookDetailPresenterImpl;
import com.ebook.basebook.mvp.presenter.impl.ReadBookPresenterImpl;
import com.ebook.basebook.mvp.view.IBookDetailView;
import com.ebook.db.entity.BookShelf;

import jp.wasabeef.glide.transformations.BlurTransformation;

public class BookDetailActivity extends BaseActivity<IBookDetailPresenter> implements IBookDetailView {
    public static final String TAG = "BookDetailActivity";
    private FrameLayout iflContent;
    private ImageView ivBlurCover;
    private ImageView ivCover;
    private TextView tvName;
    private TextView tvAuthor;
    private TextView tvOrigin;
    private TextView tvChapter;
    private TextView tvIntro;
    private TextView tvShelf;
    private TextView tvRead;
    private TextView tvLoading;

    private Animation animHideLoading;
    private Animation animShowInfo;

    @Override
    protected IBookDetailPresenter initInjector() {
        return new BookDetailPresenterImpl(getIntent());
    }

    @Override
    protected void onCreateActivity() {
        setContentView(R.layout.activity_detail);
    }

    @Override
    protected void initData() {
        animShowInfo = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
        animHideLoading = AnimationUtils.loadAnimation(this, android.R.anim.fade_out);
        animHideLoading.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                tvLoading.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
    }


    @Override
    protected void bindView() {
        iflContent = findViewById(R.id.ifl_content);
        ivBlurCover = findViewById(R.id.iv_blur_cover);
        ivCover = findViewById(R.id.iv_cover);
        tvName = findViewById(R.id.tv_name);
        tvAuthor = findViewById(R.id.tv_author);
        tvOrigin = findViewById(R.id.tv_origin);
        tvChapter = findViewById(R.id.tv_chapter);
        tvIntro = findViewById(R.id.tv_intro);
        tvShelf = findViewById(R.id.tv_shelf);
        tvRead = findViewById(R.id.tv_read);
        tvLoading = findViewById(R.id.tv_loading);

        tvIntro.setMovementMethod(ScrollingMovementMethod.getInstance());
        initView();

        updateView();
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void updateView() {
        BookShelf bookShelf = mPresenter.getBookShelf();
        if (null != bookShelf) {
            Glide.with(this)
                    .load(bookShelf.getBookInfo().getCoverUrl())
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .placeholder(R.drawable.img_cover_default)
                    .into(ivCover);
            Glide.with(this)
                    .load(bookShelf.getBookInfo().getCoverUrl())
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .apply(bitmapTransform(new BlurTransformation(6)))
                    .into(ivBlurCover);
            if (mPresenter.getInBookShelf()) {
                if (!bookShelf.getBookInfo().getChapterlist().isEmpty())
                    tvChapter.setText(String.format(getString(R.string.tv_read_durprogress), bookShelf.getBookInfo().getChapterlist().get(bookShelf.getDurChapter()).getDurChapterName()));
                else
                    tvChapter.setText("无章节");
                tvShelf.setText("移出书架");
                tvRead.setText("继续阅读");
                tvShelf.setOnClickListener(v -> {
                    //从书架移出
                    mPresenter.removeFromBookShelf();
                });
            } else {
                if (bookShelf.getBookInfo().getChapterlist().isEmpty()) {
                    tvChapter.setText("无章节");
                } else {
                    tvChapter.setText(String.format(getString(R.string.tv_searchbook_lastest), bookShelf.getBookInfo().getChapterlist().get(bookShelf.getBookInfo().getChapterlist().size() - 1).getDurChapterName()));
                }
                tvShelf.setText("放入书架");
                tvRead.setText("开始阅读");
                tvShelf.setOnClickListener(v -> {
                    //放入书架
                    mPresenter.addToBookShelf();
                });
            }
            if (tvIntro.getText().toString().trim().isEmpty()) {
                tvIntro.setText(bookShelf.getBookInfo().getIntroduce());
            }
            if (tvIntro.getVisibility() != View.VISIBLE) {
                tvIntro.setVisibility(View.VISIBLE);
                tvIntro.startAnimation(animShowInfo);
                tvLoading.startAnimation(animHideLoading);
            }
            if (bookShelf.getBookInfo().getOrigin() != null && !bookShelf.getBookInfo().getOrigin().isEmpty()) {
                tvOrigin.setVisibility(View.VISIBLE);
                tvOrigin.setText("来源:" + bookShelf.getBookInfo().getOrigin());
            } else {
                tvOrigin.setVisibility(View.GONE);
            }
        } else {
            tvChapter.setText(String.format(getString(R.string.tv_searchbook_lastest), mPresenter.getSearchBook().getLastChapter()));
            tvIntro.setVisibility(View.INVISIBLE);
            tvLoading.setVisibility(View.VISIBLE);
            tvLoading.setText("加载中...");
        }
        tvLoading.setOnClickListener(null);
    }

    @Override
    public void getBookShelfError() {
        tvLoading.setVisibility(View.VISIBLE);
        tvLoading.setText("加载失败,点击重试");
        tvLoading.setOnClickListener(v -> {
            tvLoading.setText("加载中...");
            tvLoading.setOnClickListener(null);
            mPresenter.getBookShelfInfo();
        });
    }

    @Override
    protected void firstRequest() {
        super.firstRequest();
        if (mPresenter.getOpenFrom() == BookDetailPresenterImpl.FROM_SEARCH && mPresenter.getBookShelf() == null) {
            //网络请求
            mPresenter.getBookShelfInfo();
        }
    }

    @SuppressLint("SetTextI18n")
    private void initView() {
        String coverUrl;
        String name;
        String author;
        if (mPresenter.getOpenFrom() == BookDetailPresenterImpl.FROM_BOOKSHELF) {
            coverUrl = mPresenter.getBookShelf().getBookInfo().getCoverUrl();
            name = mPresenter.getBookShelf().getBookInfo().getName();
            author = mPresenter.getBookShelf().getBookInfo().getAuthor();
            if (mPresenter.getBookShelf().getBookInfo().getOrigin() != null && !mPresenter.getBookShelf().getBookInfo().getOrigin().isEmpty()) {
                tvOrigin.setVisibility(View.VISIBLE);
                tvOrigin.setText("来源:" + mPresenter.getBookShelf().getBookInfo().getOrigin());
            } else {
                tvOrigin.setVisibility(View.GONE);
            }
        } else {
            coverUrl = mPresenter.getSearchBook().getCoverUrl();
            name = mPresenter.getSearchBook().getName();
            author = mPresenter.getSearchBook().getAuthor();
            if (mPresenter.getSearchBook().getOrigin() != null && !mPresenter.getSearchBook().getOrigin().isEmpty()) {
                tvOrigin.setVisibility(View.VISIBLE);
                tvOrigin.setText("来源:" + mPresenter.getSearchBook().getOrigin());
            } else {
                tvOrigin.setVisibility(View.GONE);
            }
        }

        Glide.with(this)
                .load(coverUrl)
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(R.drawable.img_cover_default)
                .into(ivCover);
        Glide.with(this)
                .load(coverUrl)
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .apply(bitmapTransform(new BlurTransformation(6)))
                .into(ivBlurCover);
        tvName.setText(name);
        tvAuthor.setText(author);
    }

    @Override
    protected void bindEvent() {
        iflContent.setOnClickListener(v -> {
            if (getStart_share_ele()) {
                finishAfterTransition();
            } else {
                finish();
                overridePendingTransition(0, android.R.anim.fade_out);
            }
        });

        tvRead.setOnClickListener(v -> {
            //进入阅读
            Intent intent = new Intent(BookDetailActivity.this, ReadBookActivity.class);
            intent.putExtra("from", ReadBookPresenterImpl.OPEN_FROM_APP);
            String key = String.valueOf(System.currentTimeMillis());
            intent.putExtra("data_key", key);
            try {
                BitIntentDataManager.getInstance().putData(key, mPresenter.getBookShelf().clone());
            } catch (CloneNotSupportedException e) {
                BitIntentDataManager.getInstance().putData(key, mPresenter.getBookShelf());
                Log.e(TAG, "bindEvent: ", e);
            }
            startActivityByAnim(intent, android.R.anim.fade_in, android.R.anim.fade_out);

            if (getStart_share_ele()) {
                finishAfterTransition();
            } else {
                finish();
                overridePendingTransition(0, android.R.anim.fade_out);
            }
        });
    }
}
