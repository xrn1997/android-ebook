package com.ebook.me;

import static com.ebook.common.util.FileUtil.getRealFilePathFromUri;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.SPUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ebook.api.config.API;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.view.SettingBarView;
import com.ebook.common.view.profilePhoto.CircleImageView;
import com.ebook.common.view.profilePhoto.PhotoCutDialog;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.ModifyViewModel;
import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.therouter.TheRouter;
import com.therouter.router.Route;
import com.xrn1997.common.mvvm.view.BaseMvvmActivity;

@Route(path = KeyCode.Me.MODIFY_PATH, params = {"needLogin", "true"})
public class ModifyInformationActivity extends BaseMvvmActivity<ViewDataBinding, ModifyViewModel> {
    private CircleImageView imageView;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RxBus.get().register(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RxBus.get().unregister(this);
    }
    @Override
    public int onBindLayout() {
        return R.layout.activity_modify_information;
    }

    @NonNull
    @Override
    public Class<ModifyViewModel> onBindViewModel() {
        return ModifyViewModel.class;
    }

    @NonNull
    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return MeViewModelFactory.INSTANCE;
    }

    @Override
    public void initViewObservable() {

    }

    @Override
    public int onBindVariableId() {
        return BR.viewModel;
    }

    @Override
    public void initView() {
        SettingBarView mSetModifyImage = findViewById(R.id.view_modify_profile_photo);
        SettingBarView mSetModifyNickname = findViewById(R.id.view_modify_nickname);
        SettingBarView mSetModifyPwd = findViewById((R.id.view_modify_pwd));
        imageView = findViewById(R.id.view_profile_photo);
        mSetModifyImage.setOnClickSettingBarViewListener(this::uploadHeadImage);
        mSetModifyPwd.setOnClickSettingBarViewListener(() -> TheRouter.build(KeyCode.Login.MODIFY_PATH)
                .navigation());
        mSetModifyNickname.setOnClickSettingBarViewListener(() -> startActivity(new Intent(ModifyInformationActivity.this, ModifyNicknameActivity.class)));
    }

    @Override
    public void initData() {
        String url = SPUtils.getInstance().getString(KeyCode.Login.SP_IMAGE);
        Glide.with(this)
                .load(API.URL_HOST_USER + "user/image/" + url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .dontAnimate()
                .placeholder(ContextCompat.getDrawable(this, R.drawable.image_default))
                .into(imageView);
    }

    /**
     * 上传头像
     */
    public void uploadHeadImage() {
        PhotoCutDialog photoCutDialog = PhotoCutDialog.newInstance();
        photoCutDialog.setOnClickListener(uri -> {
            String cropImagePath = getRealFilePathFromUri(getApplicationContext(), uri);
            mViewModel.modifyProfilePhoto(cropImagePath);
            // Bitmap bitMap = BitmapFactory.decodeFile(cropImagePath);
        });
        photoCutDialog.show(getSupportFragmentManager(), "photoDialog");
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.MODIFY_PROFILE_PICTURE)
            }
    )
    public void setProfilePicture(String path) {
        Glide.with(ModifyInformationActivity.this)
                .load(API.URL_HOST_USER + "user/image/" + path)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .dontAnimate()
                .placeholder(ContextCompat.getDrawable(ModifyInformationActivity.this, R.drawable.image_default))
                .into(imageView);
    }
}
