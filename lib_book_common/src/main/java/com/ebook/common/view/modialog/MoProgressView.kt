package com.ebook.common.view.modialog

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ebook.common.R
import com.victor.loading.rotate.RotateLoading

class MoProgressView : LinearLayout {
    companion object {
        private const val TAG = "MoProgressView"
    }

    private var context: Context

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        this.context = context
        orientation = VERTICAL
    }

    // 转圈的载入
    fun showLoading(text: String?) {
        removeAllViews()
        LayoutInflater.from(context).inflate(R.layout.moprogress_dialog_loading, this, true)
        val msgTv = findViewById<TextView>(R.id.msg_tv)
        if (!text.isNullOrEmpty()) {
            msgTv.text = text
        }

        val rlLoading = findViewById<RotateLoading>(R.id.rl_loading)
        rlLoading.start()
    }

    // 单个按钮的信息提示框
    fun showInfo(msg: String, listener: OnClickListener) {
        removeAllViews()
        LayoutInflater.from(context).inflate(R.layout.moprogress_dialog_infor, this, true)
        val msgTv = findViewById<TextView>(R.id.msg_tv)
        msgTv.text = msg
        val tvClose = findViewById<TextView>(R.id.tv_close)
        tvClose.setOnClickListener(listener)
    }

    // 单个按钮的信息提示框（带自定义按钮文本）
    fun showInfo(msg: String, btnText: String, listener: OnClickListener) {
        removeAllViews()
        LayoutInflater.from(context).inflate(R.layout.moprogress_dialog_infor, this, true)
        val msgTv = findViewById<TextView>(R.id.msg_tv)
        msgTv.text = msg
        val tvClose = findViewById<TextView>(R.id.tv_close)
        tvClose.text = btnText
        tvClose.setOnClickListener(listener)
    }

    // 两个不同等级的按钮
    fun showTwoButton(
        msg: String,
        firstBtn: String,
        firstListener: OnClickListener,
        secondBtn: String,
        secondListener: OnClickListener
    ) {
        removeAllViews()
        LayoutInflater.from(context).inflate(R.layout.moprogress_dialog_two, this, true)
        val tvMsg = findViewById<TextView>(R.id.tv_msg)
        val tvCancel = findViewById<TextView>(R.id.tv_cancel)
        val tvDone = findViewById<TextView>(R.id.tv_done)
        tvMsg.text = msg
        tvCancel.text = firstBtn
        tvCancel.setOnClickListener(firstListener)
        tvDone.text = secondBtn
        tvDone.setOnClickListener(secondListener)
    }

    // 离线章节选择
    fun showDownloadList(
        startIndex: Int,
        endIndex: Int,
        all: Int,
        clickDownload: MoProgressHUD.OnClickDownload,
        cancel: OnClickListener
    ) {
        removeAllViews()
        LayoutInflater.from(context).inflate(R.layout.moprogress_dialog_downloadchoice, this, true)
        val edtStart = findViewById<EditText>(R.id.edt_start)
        val edtEnd = findViewById<EditText>(R.id.edt_end)
        val tvCancel = findViewById<TextView>(R.id.tv_cancel)
        val tvDownload = findViewById<TextView>(R.id.tv_download)

        tvCancel.setOnClickListener(cancel)
        edtStart.setText(context.getString(R.string.chapter_number, startIndex + 1))
        edtEnd.setText(context.getString(R.string.chapter_number, endIndex + 1))

        edtStart.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (edtStart.text.isNotEmpty()) {
                    try {
                        val temp = edtStart.text.toString().trim().toInt()
                        when {
                            temp > all -> {
                                edtStart.setText(all.toString())
                                edtStart.setSelection(edtStart.text.length)
                                Toast.makeText(context, "超过总章节", Toast.LENGTH_SHORT).show()
                            }

                            temp <= 0 -> {
                                edtStart.setText("1")
                                edtStart.setSelection(edtStart.text.length)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "afterTextChanged: ", e)
                    }
                }
            }
        })

        edtEnd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (edtEnd.text.isNotEmpty()) {
                    try {
                        val temp = edtEnd.text.toString().trim().toInt()
                        when {
                            temp > all -> {
                                edtEnd.setText(all.toString())
                                edtEnd.setSelection(edtEnd.text.length)
                                Toast.makeText(context, "超过总章节", Toast.LENGTH_SHORT).show()
                            }

                            temp <= 0 -> {
                                edtEnd.setText("1")
                                edtEnd.setSelection(edtEnd.text.length)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "afterTextChanged: ", e)
                    }
                }
            }
        })

        tvDownload.setOnClickListener {
            if (edtStart.text.isNotEmpty() && edtEnd.text.isNotEmpty()) {
                val start = edtStart.text.toString().toInt()
                val end = edtEnd.text.toString().toInt()
                if (start > end) {
                    Toast.makeText(context, "输入错误", Toast.LENGTH_SHORT).show()
                } else {
                    clickDownload.download(start - 1, end - 1)
                }
            } else {
                Toast.makeText(context, "请输入要离线的章节", Toast.LENGTH_SHORT).show()
            }
        }
    }
}