package com.ebook.basebook.mvp.view.impl;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ToastUtils;
import com.ebook.basebook.R;
import com.ebook.basebook.base.activity.BaseActivity;
import com.ebook.basebook.mvp.presenter.IImportBookPresenter;
import com.ebook.basebook.mvp.presenter.impl.ImportBookPresenterImpl;
import com.ebook.basebook.mvp.view.IImportBookView;
import com.ebook.basebook.mvp.view.adapter.ImportBookAdapter;
import com.ebook.basebook.view.modialog.MoProgressHUD;
import com.ebook.common.event.RxBusTag;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.permissionx.guolindev.PermissionX;
import com.victor.loading.rotate.RotateLoading;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ImportBookActivity extends BaseActivity<IImportBookPresenter> implements IImportBookView {
    private final String TAG = "ImportBookActivity";
    private LinearLayout llContent;
    private ImageButton ivReturn;
    private TextView tvScan;
    private TextView tvPath;
    private FrameLayout flScan;
    private RotateLoading rlLoading;
    private TextView tvCount;

    private TextView tvAddShelf;

    private ImportBookAdapter importBookAdapter;

    private Animation animIn;
    private Animation animOut;

    private MoProgressHUD moProgressHUD;
    private Boolean isExiting = false;

    @Override
    protected IImportBookPresenter initInjector() {
        return new ImportBookPresenterImpl();
    }

    @Override
    protected void onCreateActivity() {
        setContentView(R.layout.activity_importbook);
        ActivityResultLauncher<Intent> requestPermission = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !Environment.isExternalStorageManager()) {
                onBackPressed();
            }
        });
        PermissionX
                .init(this)
                .permissions(allNeedPermissions())
                .onExplainRequestReason((scope, deniedList) -> scope.showRequestReasonDialog(deniedList, "即将重新申请的权限是程序必须依赖的权限(请选择始终)", "我已明白", "取消"))
                .onForwardToSettings((scope, deniedList) -> scope.showForwardToSettingsDialog(deniedList, "您需要去应用程序设置当中手动开启权限", "我已明白", "取消"))
                .request((allGranted, grantedList, deniedList) -> {
                    if (!allGranted) {
                        onBackPressed();
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                !Environment.isExternalStorageManager()) {
                            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                                    .setMessage("在Android11及以上的版本中，本程序还需要您同意允许访问所有文件权限，不然无法打开和扫描本地文件")
                                    .setPositiveButton("确定", (dialog, which) -> {
                                        requestPermission.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                    })
                                    .setNegativeButton("取消", ((dialog, which) -> {
                                        onBackPressed();
                                    }));
                            AlertDialog dialog = builder.create();
                            //点击dialog之外的空白处，dialog不能消失
                            dialog.setCanceledOnTouchOutside(false);
                            dialog.show();
                        }
                    }
                });
    }

    /**
     * 所有需要的权限
     */
    public List<String> allNeedPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        permissions.add(PermissionX.permission.POST_NOTIFICATIONS);
        return permissions;
    }

    @Override
    protected void initData() {
        animIn = AnimationUtils.loadAnimation(this, R.anim.anim_act_importbook_in);
        animOut = AnimationUtils.loadAnimation(this, R.anim.anim_act_importbook_out);

        importBookAdapter = new ImportBookAdapter(count -> tvAddShelf.setVisibility(count == 0 ? View.INVISIBLE : View.VISIBLE));
    }

    @Override
    protected void bindView() {
        moProgressHUD = new MoProgressHUD(this);

        llContent = findViewById(R.id.ll_content);
        ivReturn = findViewById(R.id.iv_return);
        tvScan = findViewById(R.id.tv_scan);
        tvPath = findViewById(R.id.tv_path);
        rlLoading = findViewById(R.id.rl_loading);
        tvCount = findViewById(R.id.tv_count);
        flScan = findViewById(R.id.fl_scan);
        tvAddShelf = findViewById(R.id.tv_addshelf);

        RecyclerView rcvBooks = findViewById(R.id.rcv_books);
        rcvBooks.setAdapter(importBookAdapter);
        rcvBooks.setLayoutManager(new LinearLayoutManager(this));
    }

    @Override
    protected void bindEvent() {
        tvScan.setOnClickListener(v -> {
            mPresenter.searchLocationBook();
            tvScan.setVisibility(View.INVISIBLE);
            rlLoading.start();
            tvCount.setText(getResources().getString(R.string.scan_cancel));
        });
        flScan.setOnClickListener(v -> {
            if (tvCount.getText().equals(getResources().getString(R.string.scan_cancel))) {
                mPresenter.ScanCancel();
            }
        });
        animOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                ImportBookActivity.super.finish();
                overridePendingTransition(0, 0);
                isExiting = false;
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        ivReturn.setOnClickListener(v -> {
            mPresenter.ScanCancel();
            onBackPressed();
        });

        tvAddShelf.setOnClickListener(v -> {
            //添加书籍
            moProgressHUD.showLoading("放入书架中...");
            mPresenter.importBooks(importBookAdapter.getSelectFileList());
        });
    }

    @Override
    protected void firstRequest() {
        llContent.startAnimation(animIn);
    }

    @Override
    public void finish() {
        if (!isExiting) {
            if (moProgressHUD.isShow()) {
                moProgressHUD.dismiss();
            }
            isExiting = true;
            llContent.startAnimation(animOut);
        }
    }

    @Override
    public void addNewBook(File newFile) {
        importBookAdapter.addData(newFile);
    }

    @Override
    public void searchFinish() {
        rlLoading.stop();
        rlLoading.setVisibility(View.INVISIBLE);
        if (importBookAdapter.getFileList().size() == 0) {
            tvScan.setVisibility(View.VISIBLE);
            ToastUtils.showShort("未发现本地书籍");
        } else {
            tvCount.setText(String.format(getResources().getString(R.string.tv_importbook_count), importBookAdapter.getItemCount()));
            importBookAdapter.setCanCheck(true);
        }

    }

    @Override
    public void addSuccess() {
        moProgressHUD.dismiss();
        Toast.makeText(this, "添加书籍成功", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void addError() {
        moProgressHUD.showInfo("放入书架失败!");
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {
                    @Tag(RxBusTag.SHOW_SCAN_PATH)
            }
    )
    /**
     * 扫描时实时显示路径，给用户扫描反馈。
     */
    @SuppressWarnings("unused")
    public void showScanPath(String absolutePath) {
        tvPath.setText(absolutePath);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Boolean a = moProgressHUD.onKeyDown(keyCode, event);
        if (a)
            return true;
        return super.onKeyDown(keyCode, event);
    }
}