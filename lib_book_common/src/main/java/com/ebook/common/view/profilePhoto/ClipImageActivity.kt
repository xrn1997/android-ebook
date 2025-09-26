package com.ebook.common.view.profilePhoto

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import com.ebook.common.databinding.ActivityClipImageBinding
import com.xrn1997.common.mvvm.view.BaseActivity
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * 头像裁剪Activity
 */
class ClipImageActivity : BaseActivity<ActivityClipImageBinding>() {
    private lateinit var clipViewLayout: ClipViewLayout

    //类别 1: 圆形, 2: 正方形
    private var type = 0


    /**
     * 初始化组件
     */
    override fun initView() {
        clipViewLayout = binding.clipViewLayout
        val btnCancel = binding.btnCancel
        val btnOk = binding.btOk
        //设置点击事件监听器
        btnCancel.setOnClickListener { finish() }
        btnOk.setOnClickListener { generateUriAndReturn() }
    }

    override fun initData() {
        type = intent.getIntExtra("type", 1)
    }

    override fun onResume() {
        super.onResume()
        clipViewLayout.setClipType(type)
        //设置图片资源
        intent.data?.let { clipViewLayout.setImageSrc(it) }
    }

    override var toolBarTitle: String
        get() = "截取"
        set(toolBarTitle) {
            super.toolBarTitle = toolBarTitle
        }

    override fun enableToolbar(): Boolean {
        return true
    }

    /**
     * 生成Uri并且通过setResult返回给打开的activity
     */
    private fun generateUriAndReturn() {
        //调用返回剪切图
        val zoomedCropBitmap = clipViewLayout.clip()
        val mSaveUri =
            Uri.fromFile(File(cacheDir, "cropped_" + System.currentTimeMillis() + ".jpg"))
        if (mSaveUri != null) {
            var outputStream: OutputStream? = null
            try {
                outputStream = contentResolver.openOutputStream(mSaveUri)
                if (outputStream != null) {
                    zoomedCropBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }
            } catch (ex: IOException) {
                Log.e("android", "Cannot open file: $mSaveUri", ex)
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "generateUriAndReturn: ", e)
                    }
                }
            }
            val intent = Intent()
            intent.setData(mSaveUri)
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivityClipImageBinding {
        return ActivityClipImageBinding.inflate(inflater, parent, attachToParent)
    }

    companion object {
        private val TAG = ClipImageActivity::class.java.simpleName
    }
}
