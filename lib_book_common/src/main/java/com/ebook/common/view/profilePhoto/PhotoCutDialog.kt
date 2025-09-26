package com.ebook.common.view.profilePhoto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.ebook.common.databinding.FragmentPhotoSelectBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xrn1997.common.util.FileUtil
import java.io.File

class PhotoCutDialog : BottomSheetDialogFragment() {
    private var mOnClickListener: ((uri: Uri) -> Unit)? = null
    private lateinit var mPhotoFile: File

    // 注册用于接收 activity 结果的启动器
    private lateinit var selectLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var takePhotoLauncher: ActivityResultLauncher<Uri>
    private lateinit var cropPhotoLauncher: ActivityResultLauncher<Intent>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mPhotoFile = FileUtil.getPrivateFile(requireContext(), "profile.jpeg")
        selectLauncher =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) {
                    gotoClipActivity(uri)
                }
            }

        takePhotoLauncher =
            registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (success) {
                    gotoClipActivity(Uri.fromFile(mPhotoFile))
                }
            }

        cropPhotoLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let {
                        mOnClickListener?.invoke(it)
                    }
                    dismiss()
                }
            }
    }

    fun setOnClickListener(onPhotoClickListener: (uri: Uri) -> Unit) {
        mOnClickListener = onPhotoClickListener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentPhotoSelectBinding.inflate(inflater, container, false)
        binding.btnSelectPhoto.setOnClickListener {
            selectLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnTakePhoto.setOnClickListener {
            takePhotoLauncher.launch(FileUtil.getUri(requireContext(), mPhotoFile))
        }
        binding.btnCancel.setOnClickListener { dismiss() }
        return binding.root
    }


    /**
     * 打开截图界面
     */
    private fun gotoClipActivity(uri: Uri?) {
        if (uri == null) return
        val intent = Intent()
        intent.setClass(requireActivity(), ClipImageActivity::class.java)
        // 1: 圆形 2: 正方形
        val cutType = 1
        intent.putExtra("type", cutType)
        intent.setData(uri)
        cropPhotoLauncher.launch(intent)
    }

    companion object {
        private val TAG: String = PhotoCutDialog::class.java.simpleName

        @JvmStatic
        fun newInstance(): PhotoCutDialog {
            return PhotoCutDialog()
        }
    }
}
