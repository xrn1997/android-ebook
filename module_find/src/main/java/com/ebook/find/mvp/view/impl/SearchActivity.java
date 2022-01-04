
package com.ebook.find.mvp.view.impl;

import android.animation.Animator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.daimajia.androidanimations.library.Techniques;
import com.daimajia.androidanimations.library.YoYo;

import com.ebook.basebook.base.activity.BaseActivity;
import com.ebook.basebook.mvp.presenter.impl.BookDetailPresenterImpl;
import com.ebook.basebook.mvp.view.impl.BookDetailActivity;
import com.ebook.common.util.StatusBarUtils;
import com.ebook.basebook.utils.NetworkUtil;
import com.ebook.basebook.view.flowlayout.TagFlowLayout;
import com.ebook.basebook.view.refreshview.OnLoadMoreListener;
import com.ebook.basebook.view.refreshview.RefreshRecyclerView;
import com.ebook.db.entity.SearchBook;
import com.ebook.db.entity.SearchHistory;
import com.ebook.find.R;
import com.ebook.find.mvp.presenter.ISearchPresenter;
import com.ebook.find.mvp.presenter.impl.SearchPresenterImpl;
import com.ebook.find.mvp.view.ISearchView;
import com.ebook.find.mvp.view.adapter.SearchBookAdapter;
import com.ebook.find.mvp.view.adapter.SearchHistoryAdapter;

import java.util.List;
import java.util.Objects;

import androidx.recyclerview.widget.LinearLayoutManager;

import tyrantgit.explosionfield.ExplosionField;

public class SearchActivity extends BaseActivity<ISearchPresenter> implements ISearchView {
    private FrameLayout flSearchContent;
    private EditText edtContent;
    private TextView tvToSearch;
    private LinearLayout llSearchHistory;
    private TextView tvSearchHistoryClean;
    private TagFlowLayout tflSearchHistory;
    private SearchHistoryAdapter searchHistoryAdapter;
    private Animation animHistory;
    private Animator animHistory5;
    private ExplosionField explosionField;
    private RefreshRecyclerView rfRvSearchBooks;
    private SearchBookAdapter searchBookAdapter;

    @Override
    protected ISearchPresenter initInjector() {
        return new SearchPresenterImpl();
    }

    @Override
    protected void onCreateActivity() {
        setContentView(R.layout.activity_search);
    }

    @Override
    protected void initData() {
        explosionField = ExplosionField.attach2Window(this);
        searchHistoryAdapter = new SearchHistoryAdapter();
        searchBookAdapter = new SearchBookAdapter();
    }

    @Override
    protected void bindView() {
        flSearchContent = findViewById(R.id.fl_search_content);
        edtContent = findViewById(R.id.edt_content);
        tvToSearch = findViewById(R.id.tv_to_search);
        llSearchHistory = findViewById(R.id.ll_search_history);
        tvSearchHistoryClean = findViewById(R.id.tv_search_history_clean);
        tflSearchHistory = findViewById(R.id.tfl_search_history);
        tflSearchHistory.setAdapter(searchHistoryAdapter);
        rfRvSearchBooks = findViewById(R.id.rfRv_search_books);
        rfRvSearchBooks.setRefreshRecyclerViewAdapter(searchBookAdapter, new LinearLayoutManager(this));
        View viewRefreshError = LayoutInflater.from(this).inflate(R.layout.view_searchbook_refresherror, null);
        viewRefreshError.findViewById(R.id.tv_refresh_again).setOnClickListener(v -> {
            //刷新失败 ，重试
            mPresenter.initPage();
            mPresenter.toSearchBooks(null, true);
            rfRvSearchBooks.startRefresh();
        });
        rfRvSearchBooks.setNoDataAndRefreshErrorView(LayoutInflater.from(this).inflate(R.layout.view_searchbook_nodata, null),
                viewRefreshError);
        searchBookAdapter.setItemClickListener(new SearchBookAdapter.OnItemClickListener() {
            @Override
            public void clickAddShelf(View clickView, int position, SearchBook searchBook) {
                mPresenter.addBookToShelf(searchBook);
            }

            @Override
            public void clickItem(View animView, int position, SearchBook searchBook) {
                Intent intent = new Intent(SearchActivity.this, BookDetailActivity.class);
                intent.putExtra("from", BookDetailPresenterImpl.FROM_SEARCH);
                intent.putExtra("data", searchBook);
                startActivityByAnim(intent, animView, "img_cover", android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });
    }

    @Override
    protected void bindEvent() {
        tvSearchHistoryClean.setOnClickListener(v -> {
            for (int i = 0; i < tflSearchHistory.getChildCount(); i++) {
                explosionField.explode(tflSearchHistory.getChildAt(i));
            }
            mPresenter.cleanSearchHistory();
        });
        edtContent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                edtContent.setSelection(edtContent.length());
                checkTvToSearch();
                mPresenter.querySearchHistory();
            }
        });
        edtContent.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() ==
                    KeyEvent.KEYCODE_ENTER)) {
                toSearch();
                return true;
            } else
                return false;
        });
        tvToSearch.setOnClickListener(v -> {
            if (!mPresenter.getInput()) {
                finishAfterTransition();
            } else {
                //搜索
                toSearch();
            }
        });
        searchHistoryAdapter.setOnItemClickListener(searchHistory -> {
            edtContent.setText(searchHistory.getContent());
            toSearch();
        });
        bindKeyBoardEvent();
        rfRvSearchBooks.setLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void startLoadMore() {
                mPresenter.toSearchBooks(null, false);
            }

            @Override
            public void loadMoreErrorTryAgain() {
                mPresenter.toSearchBooks(null, true);
            }
        });
    }

    @Override
    protected void firstRequest() {
        super.firstRequest();
        mPresenter.querySearchHistory();
    }

    //开始搜索
    private void toSearch() {
        if (edtContent.getText().toString().trim().length() > 0) {
            final String key = edtContent.getText().toString().trim();
            mPresenter.setHasSearch(true);
            mPresenter.insertSearchHistory();
            closeKeyBoard();
            //执⾏搜索请求
            new Handler().postDelayed(() -> {
                mPresenter.initPage();
                mPresenter.toSearchBooks(key, false);
                rfRvSearchBooks.startRefresh();
            }, 300);
        } else {
            YoYo.with(Techniques.Shake).playOn(flSearchContent);
        }
    }

    private void bindKeyBoardEvent() {
        llSearchHistory.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            llSearchHistory.getWindowVisibleDisplayFrame(r);
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) llSearchHistory.getLayoutParams();
            int height = llSearchHistory.getContext().getResources().getDisplayMetrics().heightPixels;
            if (height < r.bottom) { //⼩⽶8-Android9.0 刘海屏问题，可⻅区域⾼度会⼤于屏幕⾼度
                r.bottom = height;
            }
            int diff = height - r.bottom;
            if (diff != 0 && Math.abs(diff) != StatusBarUtils.getStatus_height()) {
                if (layoutParams.bottomMargin != diff) {
                    //华为可隐藏导航栏，在⼿动隐藏或显示导航栏 屏幕⾼度获取数值不会改变。
                    if (Math.abs(layoutParams.bottomMargin - Math.abs(diff)) != StatusBarUtils.getStatus_height()) {
                        layoutParams.setMargins(0, 0, 0, Math.abs(diff));
                        llSearchHistory.setLayoutParams(layoutParams);
                    }
                    //打开输⼊
                    if (llSearchHistory.getVisibility() != View.VISIBLE)
                        openOrCloseHistory(true);
                }
            } else {
                if (layoutParams.bottomMargin != 0) {
                    if (!mPresenter.getHasSearch()) {
                        finishAfterTransition();
                    } else {
                        layoutParams.setMargins(0, 0, 0, 0);
                        llSearchHistory.setLayoutParams(layoutParams);
                        //关闭输⼊
                        if (llSearchHistory.getVisibility() == View.VISIBLE)
                            openOrCloseHistory(false);
                    }
                }
            }
        });
        getWindow()
                .getDecorView()
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        new Handler().postDelayed(() -> openKeyBoard(), 100);
                        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                });
    }

    private void checkTvToSearch() {
        if (llSearchHistory.getVisibility() == View.VISIBLE) {
            tvToSearch.setText("搜索");
            mPresenter.setInput(true);
        } else {
            tvToSearch.setText("返回");
            mPresenter.setInput(false);
        }
    }

    private void openOrCloseHistory(Boolean open) {
        if (null != animHistory5) {
            animHistory5.cancel();
        }
        if (open) {
            animHistory5 = ViewAnimationUtils.createCircularReveal(
                    llSearchHistory,
                    0, 0, 0,
                    (float) Math.hypot(llSearchHistory.getWidth(), llSearchHistory.getHeight()));
            animHistory5.setInterpolator(new AccelerateDecelerateInterpolator());
            animHistory5.setDuration(700);
            animHistory5.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    llSearchHistory.setVisibility(View.VISIBLE);
                    edtContent.setCursorVisible(true);
                    checkTvToSearch();
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (rfRvSearchBooks.getVisibility() != View.VISIBLE)
                        rfRvSearchBooks.setVisibility(View.VISIBLE);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }
            });
        } else {
            animHistory5 = ViewAnimationUtils.createCircularReveal(
                    llSearchHistory,
                    0, 0, (float) Math.hypot(llSearchHistory.getHeight(), llSearchHistory.getHeight()),
                    0);
            animHistory5.setInterpolator(new AccelerateDecelerateInterpolator());
            animHistory5.setDuration(300);
            animHistory5.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    llSearchHistory.setVisibility(View.GONE);
                    edtContent.setCursorVisible(false);
                    checkTvToSearch();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }
            });
        }
        animHistory5.start();
    }

    private void closeKeyBoard() {
        InputMethodManager imm = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(edtContent.getWindowToken(), 0);
        /*
        由于关闭软键盘会触发监听事件导致搜索历史关闭，所以这里为了兼容没有软键盘的例外情况，再次核验了一次是否已经关闭搜索历史。
         */
        if (llSearchHistory.getVisibility() == View.VISIBLE)
            openOrCloseHistory(false);
    }

    private void openKeyBoard() {

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        edtContent.requestFocus();
         imm.showSoftInput(edtContent, InputMethodManager.RESULT_UNCHANGED_SHOWN);
            /*
            由于思路是通过软键盘改变“屏幕大小”来控制是否显示搜索历史的，
            因此即便是在打开软键盘失败的情况下，也依旧应该兼容显示搜索历史，
            如PC端的Android模拟器就有可能不会提供软键盘。
             */
            if (llSearchHistory.getVisibility() != View.VISIBLE)
                openOrCloseHistory(true);
    }

    @Override
    public void insertSearchHistorySuccess(SearchHistory searchHistory) {
        //搜索历史插⼊或者修改成功
        mPresenter.querySearchHistory();
    }

    @Override
    public void querySearchHistorySuccess(List<SearchHistory> datas) {
        searchHistoryAdapter.replaceAll(datas);
        if (searchHistoryAdapter.getDataSize() > 0) {
            tvSearchHistoryClean.setVisibility(View.VISIBLE);
        } else {
            tvSearchHistoryClean.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void refreshSearchBook(List<SearchBook> books) {
        searchBookAdapter.replaceAll(books);
    }

    @Override
    public void refreshFinish(Boolean isAll) {
        rfRvSearchBooks.finishRefresh(isAll, true);
    }

    @Override
    public void loadMoreFinish(Boolean isAll) {
        rfRvSearchBooks.finishLoadMore(isAll, true);
    }

    @Override
    public void searchBookError(Boolean isRefresh) {
        if (isRefresh) {
            rfRvSearchBooks.refreshError();
        } else {
            rfRvSearchBooks.loadMoreError();
        }
    }

    @Override
    public void loadMoreSearchBook(final List<SearchBook> books) {
        searchBookAdapter.addAll(books);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        explosionField.clear();
    }

    @Override
    public EditText getEdtContent() {
        return edtContent;
    }

    @Override
    public void addBookShelfFailed(int code) {
        Toast.makeText(this, NetworkUtil.getErrorTip(code), Toast.LENGTH_SHORT).show();
    }

    @Override
    public SearchBookAdapter getSearchBookAdapter() {
        return searchBookAdapter;
    }

    @Override
    public void updateSearchItem(int index) {
        if (index < searchBookAdapter.getItemcount()) {
            int startIndex = ((LinearLayoutManager)
                    Objects.requireNonNull(rfRvSearchBooks.getRecyclerView().getLayoutManager())).findFirstVisibleItemPosition();
            TextView tvAddShelf = rfRvSearchBooks.getRecyclerView().getChildAt(index -
                    startIndex).findViewById(R.id.tv_addshelf);
            if (tvAddShelf != null) {
                if (searchBookAdapter.getSearchBooks().get(index).getAdd()) {
                    tvAddShelf.setText("已添加");
                    tvAddShelf.setEnabled(false);
                } else {
                    tvAddShelf.setText("+添加");
                    tvAddShelf.setEnabled(true);
                }
            }
        }
    }

    @Override
    public Boolean checkIsExist(SearchBook searchBook) {
        boolean result = false;
        for (int i = 0; i < searchBookAdapter.getItemcount(); i++) {
            if (searchBookAdapter.getSearchBooks().get(i).getNoteUrl().equals(searchBook.getNoteUrl()) &&
                    searchBookAdapter.getSearchBooks().get(i).getTag().equals(searchBook.getTag())) {
                result = true;
                break;
            }
        }
        return result;
    }
}