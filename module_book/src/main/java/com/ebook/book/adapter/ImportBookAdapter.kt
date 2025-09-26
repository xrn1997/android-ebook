package com.ebook.book.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.ebook.common.callback.FileDifferCallback
import com.ebook.common.databinding.ViewAdapterImportbookBinding
import com.ebook.common.view.checkbox.SmoothCheckBox
import com.xrn1997.common.adapter.BaseBindAdapter
import java.io.File
import java.text.DecimalFormat

class ImportBookAdapter(context: Context) :
    BaseBindAdapter<File, ViewAdapterImportbookBinding>(context, FileDifferCallback()) {
    private val selectFileList: MutableList<File> =
        ArrayList()
    private var canCheck = false

    private var mCheckBookListener: ((count: Int) -> Unit)? = null

    @SuppressLint("NotifyDataSetChanged")
    fun setCanCheck(canCheck: Boolean) {
        this.canCheck = canCheck
        notifyDataSetChanged()
    }

    override fun onBindItem(binding: ViewAdapterImportbookBinding, item: File, position: Int) {
        binding.tvName.text = item.name
        binding.tvSize.text =
            convertByte(item.length())
        binding.tvLoc.text = item.absolutePath.replace(
            Environment.getExternalStorageDirectory().absolutePath,
            "存储空间"
        )

        binding.scbSelect.setOnCheckedChangeListener { _: SmoothCheckBox?, isChecked: Boolean ->
            if (isChecked) {
                selectFileList.add(item)
            } else {
                selectFileList.remove(item)
            }
            mCheckBookListener?.invoke(selectFileList.size)
        }
        if (canCheck) {
            binding.scbSelect.visibility = View.VISIBLE
            binding.llContent.setOnClickListener {
                binding.scbSelect.setChecked(
                    !binding.scbSelect.isChecked,
                    true
                )
            }
        } else {
            binding.scbSelect.visibility = View.INVISIBLE
            binding.llContent.setOnClickListener(null)
        }

    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup,
        attachToParent: Boolean,
        viewType: Int
    ): ViewAdapterImportbookBinding {
        return ViewAdapterImportbookBinding.inflate(inflater, parent, attachToParent)
    }

    fun getSelectFileList(): List<File> {
        return selectFileList
    }

    fun setCheckBookListener(checkBookListener: (count: Int) -> Unit) {
        mCheckBookListener = checkBookListener
    }

    companion object {
        fun convertByte(size: Long): String {
            val df = DecimalFormat("###.#")
            val f: Float
            if (size < 1024) {
                f = size.toFloat()
                return (df.format(f.toDouble()) + "B")
            } else if (size < 1024 * 1024) {
                f = size.toFloat() / 1024f
                return (df.format(f.toDouble()) + "KB")
            } else if (size < 1024 * 1024 * 1024) {
                f = size.toFloat() / (1024 * 1024).toFloat()
                return (df.format(f.toDouble()) + "MB")
            } else {
                f = size.toFloat() / (1024 * 1024 * 1024).toFloat()
                return (df.format(f.toDouble()) + "GB")
            }
        }
    }
}
