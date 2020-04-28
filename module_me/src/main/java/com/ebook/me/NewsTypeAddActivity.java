package com.ebook.me;

import android.app.Activity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import android.content.Intent;
import androidx.annotation.Nullable;

import com.ebook.common.event.EventCode;
import com.ebook.common.event.me.NewsTypeCrudEvent;
import com.ebook.common.mvvm.BaseMvvmActivity;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.BR;
import com.ebook.me.R;
import com.ebook.me.databinding.ActivityNewsTypeAddBinding;
import com.ebook.me.mvvm.viewmodel.NewsTypeAddViewModel;

import org.greenrobot.eventbus.EventBus;


public class NewsTypeAddActivity extends BaseMvvmActivity<ActivityNewsTypeAddBinding,NewsTypeAddViewModel> {

    @Override
    public int onBindLayout() {
        return R.layout.activity_news_type_add;
    }

    @Override
    public Class<NewsTypeAddViewModel> onBindViewModel() {
        return NewsTypeAddViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return MeViewModelFactory.getInstance(getApplication());
    }
    @Override
    public void initViewObservable() {
        mViewModel.getAddNewsTypeSuccViewEvent().observe(this, new Observer<Void>() {
            @Override
            public void onChanged(@Nullable Void aVoid) {
                EventBus.getDefault().post(new NewsTypeCrudEvent(EventCode.MeCode.NEWS_TYPE_ADD));
                setResult(Activity.RESULT_OK, new Intent());
                finishActivity();
            }
        });
    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

}
