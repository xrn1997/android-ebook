package com.ebook.me;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.alibaba.android.arouter.launcher.ARouter;
import com.bumptech.glide.Glide;
import com.ebook.common.event.KeyCode;
import com.ebook.common.event.RxBusTag;
import com.ebook.common.mvvm.BaseMvvmActivity;
import com.ebook.common.view.SettingBarView;
import com.ebook.common.view.profilePhoto.CircleImageView;
import com.ebook.common.view.profilePhoto.PhotoCutDialog;
import com.ebook.me.mvvm.factory.MeViewModelFactory;
import com.ebook.me.mvvm.viewmodel.ModifyViewModel;
import com.hwangjr.rxbus.RxBus;

import java.io.ByteArrayOutputStream;

import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.ViewModelProvider;

import static com.ebook.common.util.FileUtil.getRealFilePathFromUri;

public class EditInfromActivity extends BaseMvvmActivity<ViewDataBinding, ModifyViewModel> {
    private SettingBarView mSetModifyPwd;
    private SettingBarView mSetModifyImage;
    private SettingBarView mSetModifyNickname;
    private CircleImageView imageView;

    @Override
    public int onBindLayout() {
        return R.layout.activity_edit_inform;
    }

    @Override
    public Class<ModifyViewModel> onBindViewModel() {
        return ModifyViewModel.class;
    }

    @Override
    public ViewModelProvider.Factory onBindViewModelFactory() {
        return MeViewModelFactory.getInstance(getApplication());
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
        mSetModifyImage = findViewById(R.id.view_modify_profile_photo);
        mSetModifyNickname = findViewById(R.id.view_modify_nickname);
        mSetModifyPwd = findViewById((R.id.view_modify_pwd));
        imageView = findViewById(R.id.view_profile_photo);
    }

    @Override
    public void initListener() {
        super.initListener();
        mSetModifyImage.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                uploadHeadImage();
            }
        });
        mSetModifyPwd.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                ARouter.getInstance().build(KeyCode.Login.Modify_PATH)
                        .navigation();
            }
        });
        mSetModifyNickname.setOnClickSettingBarViewListener(new SettingBarView.OnClickSettingBarViewListener() {
            @Override
            public void onClick() {
                startActivity(new Intent(EditInfromActivity.this, ModifyNicknameActivity.class));
            }
        });
    }

    @Override
    public void initData() {

    }

    /**
     * 上传头像
     */
    private void uploadHeadImage() {
        PhotoCutDialog photoCutDialog = PhotoCutDialog.newInstance();
        photoCutDialog.setOnClickLisener(new PhotoCutDialog.OnPhotoClickLisener() {
            @Override
            public void onScreenPhotoClick(Uri uri) {
                String cropImagePath = getRealFilePathFromUri(getApplicationContext(), uri);
                Bitmap bitMap = BitmapFactory.decodeFile(cropImagePath);
                Glide.with(EditInfromActivity.this)
                        .load(bitMap)
                        .into(imageView);
                RxBus.get().post(RxBusTag.MODIFY_PROFIE_PICTURE,bitMap);
                //此处后面可以将bitMap转为二进制上传后台网络
                //......
            }
        });
        photoCutDialog.show(getSupportFragmentManager(), "dialog");
    }
}
