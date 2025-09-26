package com.ebook.common.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.ebook.common.databinding.FragmentDeleteDialogBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DeleteDialog : BottomSheetDialogFragment() {
    private var mOnClickListener: (() -> Unit)? = null

    fun setOnClickListener(onDeleteClickListener: () -> Unit) {
        mOnClickListener = onDeleteClickListener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentDeleteDialogBinding.inflate(inflater, container, false)
        binding.btnDelete.setOnClickListener {
            mOnClickListener?.invoke()
            dismiss()
        }
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
        return binding.root
    }
    companion object {
        private val TAG: String = DeleteDialog::class.java.simpleName

        @JvmStatic
        fun newInstance(): DeleteDialog {
            return DeleteDialog()
        }
    }
}
