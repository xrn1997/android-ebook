package com.ebook.basebook.view.refreshview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.ebook.basebook.R;

import java.util.Objects;

public class RefreshRecyclerView extends FrameLayout {
    private static final String TAG = "RefreshRecyclerView";
    private final RefreshProgressBar rpb;
    private final RecyclerView recyclerView;

    private View noDataView;
    private View refreshErrorView;

    public RefreshRecyclerView(Context context) {
        this(context, null);
    }

    public RefreshRecyclerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RefreshRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        View view = LayoutInflater.from(context).inflate(R.layout.view_refresh_recyclerview, this, false);
        rpb = view.findViewById(R.id.rpb);
        recyclerView = view.findViewById(R.id.rv);

        @SuppressLint("CustomViewStyleable") TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RefreshProgressBar);
        rpb.setSpeed(a.getDimensionPixelSize(R.styleable.RefreshProgressBar_speed, rpb.getSpeed()));
        rpb.setMaxProgress(a.getInt(R.styleable.RefreshProgressBar_max_progress, rpb.getMaxProgress()));
        rpb.setSecondMaxProgress(a.getDimensionPixelSize(R.styleable.RefreshProgressBar_second_max_progress, rpb.getSecondMaxProgress()));
        rpb.setBgColor(a.getColor(R.styleable.RefreshProgressBar_bg_color, rpb.getBgColor()));
        rpb.setSecondColor(a.getColor(R.styleable.RefreshProgressBar_second_color, rpb.getSecondColor()));
        rpb.setFontColor(a.getColor(R.styleable.RefreshProgressBar_font_color, rpb.getFontColor()));
        a.recycle();

        bindEvent();

        addView(view);
    }

    private float durTouchY = -1000000;
    private BaseRefreshListener baseRefreshListener;

    public void setBaseRefreshListener(BaseRefreshListener baseRefreshListener) {
        this.baseRefreshListener = baseRefreshListener;
    }

    private OnLoadMoreListener loadMoreListener;

    public void setLoadMoreListener(OnLoadMoreListener loadMoreListener) {
        this.loadMoreListener = loadMoreListener;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindEvent() {
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                RefreshRecyclerViewAdapter adapter = (RefreshRecyclerViewAdapter) recyclerView.getAdapter();
                if (adapter != null) {
                    if (adapter.canLoadMore() &&
                            adapter.getItemCount() - 1 == ((LinearLayoutManager) Objects.requireNonNull(recyclerView.getLayoutManager())).findLastVisibleItemPosition()) {
                        if (!adapter.getLoadMoreError()) {
                            if (null != loadMoreListener) {
                                adapter.setIsRequesting(2, false);
                                loadMoreListener.startLoadMore();
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "onScrolled,adapter为空");
                }

            }
        });

        recyclerView.setOnTouchListener(refreshTouchListener);
    }

    public RefreshProgressBar getRpb() {
        return rpb;
    }

    public RecyclerView getRecyclerView() {
        return recyclerView;
    }

    public void refreshError() {
        RefreshRecyclerViewAdapter adapter = (RefreshRecyclerViewAdapter) recyclerView.getAdapter();
        if (adapter != null) {
            rpb.setIsAutoLoading(false);
            rpb.clean();
            adapter.setIsRequesting(0, true);
            if (noDataView != null) {
                noDataView.setVisibility(GONE);
            }
            if (refreshErrorView != null) {
                refreshErrorView.setVisibility(VISIBLE);
            }
        } else {
            Log.e(TAG, "refreshError,adapter为空");
        }
    }

    public void startRefresh() {
        RefreshRecyclerViewAdapter adapter = (RefreshRecyclerViewAdapter) recyclerView.getAdapter();
        if (adapter != null) {
            if (baseRefreshListener != null && baseRefreshListener instanceof OnRefreshWithProgressListener) {
                adapter.setIsAll(false, false);
                adapter.setIsRequesting(1, false);
                rpb.setSecondDurProgress(rpb.getSecondMaxProgress());
                rpb.setMaxProgress(((OnRefreshWithProgressListener) baseRefreshListener).getMaxProgress());
            } else {
                adapter.setIsRequesting(1, true);
                rpb.setIsAutoLoading(true);
                if (noDataView != null) {
                    noDataView.setVisibility(GONE);
                }
                if (refreshErrorView != null) {
                    refreshErrorView.setVisibility(GONE);
                }
            }
        } else {
            Log.e(TAG, "startRefresh,adapter为空");
        }
    }

    public void finishRefresh(Boolean needNoti) {
        RefreshRecyclerViewAdapter adapter = (RefreshRecyclerViewAdapter) recyclerView.getAdapter();
        if (adapter != null) {
            finishRefresh(adapter.getItemcount() == 0, needNoti);
        } else {
            Log.e(TAG, "finishRefresh,adapter为空");
        }

    }

    public void finishRefresh(Boolean isAll, Boolean needNoti) {
        RefreshRecyclerViewAdapter adapter = (RefreshRecyclerViewAdapter) recyclerView.getAdapter();
        if (adapter != null) {
            rpb.setDurProgress(0);
            if (isAll) {
                adapter.setIsRequesting(0, false);
                rpb.setIsAutoLoading(false);
                adapter.setIsAll(isAll, needNoti);
            } else {
                rpb.setIsAutoLoading(false);
                adapter.setIsRequesting(0, needNoti);
            }

            if (isAll) {
                if (noDataView != null) {
                    if (adapter.getItemcount() == 0)
                        noDataView.setVisibility(VISIBLE);
                    else
                        noDataView.setVisibility(GONE);
                }
                if (refreshErrorView != null) {
                    refreshErrorView.setVisibility(GONE);
                }
            }
        } else {
            Log.e(TAG, "finishRefresh,adapter为空");
        }
    }

    public void finishLoadMore(Boolean isAll, Boolean needNoti) {
        RefreshRecyclerViewAdapter adapter = (RefreshRecyclerViewAdapter) recyclerView.getAdapter();
        if (adapter != null) {
            if (isAll) {
                adapter.setIsRequesting(0, false);
                adapter.setIsAll(isAll, needNoti);
            } else {
                adapter.setIsRequesting(0, needNoti);
            }
            if (noDataView != null) {
                noDataView.setVisibility(GONE);
            }
            if (refreshErrorView != null) {
                refreshErrorView.setVisibility(GONE);
            }
        } else {
            Log.e(TAG, "finishLoadMore,adapter为空");
        }
    }

    public void setRefreshRecyclerViewAdapter(RefreshRecyclerViewAdapter refreshRecyclerViewAdapter, RecyclerView.LayoutManager layoutManager) {
        refreshRecyclerViewAdapter.setClickTryAgainListener(() -> {
            if (loadMoreListener != null)
                loadMoreListener.loadMoreErrorTryAgain();
        });
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(refreshRecyclerViewAdapter);
    }

    public void loadMoreError() {
        RefreshRecyclerViewAdapter adapter = (RefreshRecyclerViewAdapter) recyclerView.getAdapter();
        if (adapter != null) {
            adapter.setLoadMoreError(true, true);
            rpb.setIsAutoLoading(false);
            rpb.clean();
        } else {
            Log.e(TAG, "loadMoreError,adapter为空");
        }
    }

    public void setNoDataAndRefreshErrorView(View noData, View refreshError) {
        if (noData != null) {
            noDataView = noData;
//            noDataView.setOnTouchListener(refreshTouchListener);
            noDataView.setVisibility(GONE);
            addView(noDataView, getChildCount() - 1);

        }
        if (refreshError != null) {
            refreshErrorView = refreshError;
//            refreshErrorView.setOnTouchListener(refreshTouchListener);
            addView(refreshErrorView, 2);
            refreshErrorView.setVisibility(GONE);
        }
    }

    private final OnTouchListener refreshTouchListener = new OnTouchListener() {
        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    durTouchY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (durTouchY == -1000000)
                        durTouchY = event.getY();
                    float dY = event.getY() - durTouchY;  //>0下拉
                    durTouchY = event.getY();
                    if (baseRefreshListener != null && ((RefreshRecyclerViewAdapter) Objects.requireNonNull(recyclerView.getAdapter())).getIsRequesting() == 0 && rpb.getSecondDurProgress() == rpb.getSecondFinalProgress()) {
                        if (rpb.getVisibility() != View.VISIBLE) {
                            rpb.setVisibility(View.VISIBLE);
                        }
                        if (recyclerView.getAdapter().getItemCount() > 0) {
                            if (0 == ((LinearLayoutManager) Objects.requireNonNull(recyclerView.getLayoutManager())).findFirstCompletelyVisibleItemPosition()) {
                                rpb.setSecondDurProgress((int) (rpb.getSecondDurProgress() + dY));
                            }
                        } else {
                            rpb.setSecondDurProgress((int) (rpb.getSecondDurProgress() + dY));
                        }
                        return rpb.getSecondDurProgress() > 0;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    RefreshRecyclerViewAdapter adapter = (RefreshRecyclerViewAdapter) recyclerView.getAdapter();
                    if (adapter != null) {
                        if (baseRefreshListener != null && rpb.getSecondMaxProgress() > 0 && rpb.getSecondDurProgress() > 0) {
                            if (rpb.getSecondDurProgress() >= rpb.getSecondMaxProgress() && ((RefreshRecyclerViewAdapter) Objects.requireNonNull(recyclerView.getAdapter())).getIsRequesting() == 0) {
                                if (baseRefreshListener instanceof OnRefreshWithProgressListener) {
                                    //带有进度的
                                    //执行刷新响应
                                    adapter.setIsAll(false, false);
                                    adapter.setIsRequesting(1, true);
                                    rpb.setMaxProgress(((OnRefreshWithProgressListener) baseRefreshListener).getMaxProgress());
                                    baseRefreshListener.startRefresh();
                                    if (noDataView != null) {
                                        noDataView.setVisibility(GONE);
                                    }
                                    if (refreshErrorView != null) {
                                        refreshErrorView.setVisibility(GONE);
                                    }
                                } else {
                                    //不带进度的
                                    adapter.setIsAll(false, false);
                                    adapter.setIsRequesting(1, true);
                                    baseRefreshListener.startRefresh();
                                    if (noDataView != null) {
                                        noDataView.setVisibility(GONE);
                                    }
                                    if (refreshErrorView != null) {
                                        refreshErrorView.setVisibility(GONE);
                                    }
                                    rpb.setIsAutoLoading(true);
                                }
                            } else {
                                if (adapter.getIsRequesting() != 1)
                                    rpb.setSecondDurProgressWithAnim(0);
                            }
                        }
                        durTouchY = -1000000;
                    } else {
                        Log.e(TAG, "refreshTouchListener,adapter为空");
                    }
                    break;
            }
            return false;
        }
    };
}