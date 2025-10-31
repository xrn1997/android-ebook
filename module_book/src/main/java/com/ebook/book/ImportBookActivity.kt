package com.ebook.book

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.ebook.book.adapter.ImportBookAdapter
import com.ebook.book.databinding.ActivityImportbookBinding
import com.ebook.book.mvvm.viewmodel.BookImportViewModel
import com.ebook.common.view.modialog.MoProgressHUD
import com.permissionx.guolindev.PermissionX
import com.permissionx.guolindev.request.ExplainScope
import com.permissionx.guolindev.request.ForwardScope
import com.victor.loading.rotate.RotateLoading
import com.xrn1997.common.mvvm.view.BaseMvvmActivity
import com.xrn1997.common.util.ToastUtil.showShort
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ImportBookActivity : BaseMvvmActivity<ActivityImportbookBinding, BookImportViewModel>() {
    override val mViewModel: BookImportViewModel by viewModels()
    private lateinit var llContent: LinearLayout
    private lateinit var tvScan: TextView
    private lateinit var rlLoading: RotateLoading
    private lateinit var tvCount: TextView

    private lateinit var importBookAdapter: ImportBookAdapter

    private lateinit var animIn: Animation
    private lateinit var animOut: Animation

    private lateinit var moProgressHUD: MoProgressHUD
    private var isExiting = false

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != RESULT_OK) {
                return@registerForActivityResult
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                onBackPressedDispatcher.onBackPressed()
            }
        }

    /**
     * 所有需要的权限
     */
    private fun allNeedPermissions(): List<String> {
        val permissions: MutableList<String> = ArrayList()
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissions.add(PermissionX.permission.POST_NOTIFICATIONS)
        return permissions
    }

    override fun initView() {
        initPermission()
        moProgressHUD = MoProgressHUD(this)
        llContent = binding.llContent
        tvScan = binding.tvScan
        rlLoading = binding.rlLoading
        tvCount = binding.tvCount

        importBookAdapter = ImportBookAdapter(this)
        importBookAdapter.setCheckBookListener { count ->
            binding.tvAddshelf.visibility =
                if (count == 0) View.INVISIBLE else View.VISIBLE
        }
        binding.rcvBooks.adapter = importBookAdapter
        binding.rcvBooks.layoutManager = LinearLayoutManager(this)

        animIn = AnimationUtils.loadAnimation(this, R.anim.anim_act_importbook_in)
        animOut =
            AnimationUtils.loadAnimation(this, R.anim.anim_act_importbook_out)

        ViewCompat.setOnApplyWindowInsetsListener(llContent) { v, insets ->
            val stateBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(stateBars.left, stateBars.top, stateBars.right, stateBars.bottom)
            insets
        }
        tvScan.setOnClickListener {
            mViewModel.searchLocationBook()
            tvScan.visibility = View.INVISIBLE
            rlLoading.start()
            tvCount.text = resources.getString(com.ebook.api.R.string.scan_cancel)
        }
        binding.flScan.setOnClickListener {
            if (tvCount.text == resources.getString(com.ebook.api.R.string.scan_cancel)) {
                mViewModel.scanCancel()
            }
        }
        animOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
            }

            override fun onAnimationEnd(animation: Animation) {
                super@ImportBookActivity.finish()
                overridePendingTransition(0, 0)
                isExiting = false
            }

            override fun onAnimationRepeat(animation: Animation) {
            }
        })
        binding.ivReturn.setOnClickListener {
            mViewModel.scanCancel()
            onBackPressedDispatcher.onBackPressed()
        }
        binding.tvAddshelf.setOnClickListener {
            //添加书籍
            moProgressHUD.showLoading("放入书架中...")
            mViewModel.importBooks(importBookAdapter.getSelectFileList())
        }

        llContent.startAnimation(animIn)
    }

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun initBaseViewObservable() {
        super.initBaseViewObservable()
        mViewModel.mImportBookList.observe(this) {
            importBookAdapter.submitList(it)
        }
        mViewModel.searchFinishEvent.observe(this) {
            searchFinish()
        }
        mViewModel.addErrorEvent.observe(this) {
            addError()
        }
        mViewModel.addSuccessEvent.observe(this) {
            addSuccess()
        }
    }

    private fun initPermission() {
        PermissionX
            .init(this)
            .permissions(allNeedPermissions())
            .onExplainRequestReason { scope: ExplainScope, deniedList: List<String> ->
                scope.showRequestReasonDialog(
                    deniedList,
                    "即将重新申请的权限是程序必须依赖的权限(请选择始终)",
                    "我已明白",
                    "取消"
                )
            }
            .onForwardToSettings { scope: ForwardScope, deniedList: List<String> ->
                scope.showForwardToSettingsDialog(
                    deniedList,
                    "您需要去应用程序设置当中手动开启权限",
                    "我已明白",
                    "取消"
                )
            }
            .request { allGranted: Boolean, _: List<String?>?, _: List<String?>? ->
                if (!allGranted) {
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !Environment.isExternalStorageManager()
                    ) {
                        val builder = AlertDialog.Builder(this)
                            .setMessage("在Android11及以上的版本中，本程序还需要您同意允许访问所有文件权限，不然无法扫描本地文件")
                            .setPositiveButton(
                                "确定"
                            ) { _: DialogInterface?, _: Int ->
                                requestPermission.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                                    )
                                )
                            }
                            .setNegativeButton(
                                "取消",
                                (DialogInterface.OnClickListener { _: DialogInterface?, _: Int -> onBackPressedDispatcher.onBackPressed() })
                            )
                        val dialog = builder.create()
                        //点击dialog之外的空白处，dialog不能消失
                        dialog.setCanceledOnTouchOutside(false)
                        //取消就关闭activity
                        dialog.setOnCancelListener {
                            finish()  // 关闭当前 Activity
                        }
                        dialog.show()
                    }
                }
            }
    }

    override fun initData() {

    }


    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityImportbookBinding {
        return ActivityImportbookBinding.inflate(inflater, parent, attachToParent)
    }

    override fun finish() {
        if (!isExiting) {
            if (moProgressHUD.isShow()) {
                moProgressHUD.dismiss()
            }
            isExiting = true
            llContent.startAnimation(animOut)
        }
    }

    private fun searchFinish() {
        rlLoading.stop()
        rlLoading.visibility = View.INVISIBLE
        if (importBookAdapter.currentList.isEmpty()) {
            tvScan.visibility = View.VISIBLE
            showShort(this, "未发现本地书籍")
        } else {
            tvCount.text = String.format(
                resources.getString(com.ebook.common.R.string.tv_importbook_count),
                importBookAdapter.itemCount
            )
            importBookAdapter.setCanCheck(true)
        }
    }

    private fun addSuccess() {
        moProgressHUD.dismiss()
        Toast.makeText(this, "添加书籍成功", Toast.LENGTH_SHORT).show()
    }

    private fun addError() {
        moProgressHUD.showInfo("放入书架失败!")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val a = moProgressHUD.onKeyDown(keyCode, event)
        if (a) return true
        return super.onKeyDown(keyCode, event)
    }
}