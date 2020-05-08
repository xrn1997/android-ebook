package com.ebook.me.mvvm.viewmodel;

import android.app.Application;

import com.ebook.common.mvvm.viewmodel.BaseViewModel;
import com.ebook.me.mvvm.model.ModifyModel;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableField;

public class ModifyViewModel extends BaseViewModel<ModifyModel> {
    public ObservableField<String> username = new ObservableField<>();
    public ModifyViewModel(@NonNull Application application, ModifyModel model) {
        super(application, model);
    }


}
